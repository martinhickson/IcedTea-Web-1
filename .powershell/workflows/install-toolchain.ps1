# Host-side Windows toolchain for .powershell/workflows/sign.ps1.
# Installs missing tools only; skips packages that are already available.
#
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .powershell\workflows\install-toolchain.ps1 -VerifyOnly
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .powershell\workflows\install-toolchain.ps1 -InstallTools

param(
    [string]$ChocolateyVersion = '2.7.3',
    [int]$MaxRetries = 5,
    [switch]$VerifyOnly,
    [switch]$InstallTools
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

. (Join-Path $PSScriptRoot 'jdk.ps1')

function Write-Step {
    param([Parameter(Mandatory = $true)][string]$Message)
    Write-Host ""
    Write-Host "=== $Message ==="
}

function Write-Detail {
    param([Parameter(Mandatory = $true)][string]$Message)
    Write-Host "  $Message"
}

function Update-SessionPath {
    $machinePath = [Environment]::GetEnvironmentVariable('Path', 'Machine')
    $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    if ($userPath) {
        $env:Path = "$machinePath;$userPath"
    } else {
        $env:Path = $machinePath
    }

    if ($env:ChocolateyInstall) {
        $chocoBin = Join-Path $env:ChocolateyInstall 'bin'
        if ((Test-Path -LiteralPath $chocoBin) -and ($env:Path -notlike "*$chocoBin*")) {
            $env:Path = "$chocoBin;$env:Path"
        }
    }

    $dotnetTools = Join-Path $env:USERPROFILE '.dotnet\tools'
    if ((Test-Path -LiteralPath $dotnetTools) -and ($env:Path -notlike "*$dotnetTools*")) {
        $env:Path = "$dotnetTools;$env:Path"
    }
}

function Test-CommandAvailable {
    param([Parameter(Mandatory = $true)][string]$Name)
    return $null -ne (Get-Command $Name -ErrorAction SilentlyContinue)
}

function Test-ConfiguredJdkInstalled {
    $major = Get-ConfiguredJdkMajor
    return $null -ne (Find-JdkHomeWithMajor -Major $major)
}

function Set-ConfiguredJdkEnvironment {
    $resolved = Resolve-CompileJdkHome
    Set-CompileJdkEnvironment -JdkHome $resolved.JdkHome -Major $resolved.Major
    if ($resolved.Major -eq 11) {
        [Environment]::SetEnvironmentVariable('JAVA_HOME', $resolved.JdkHome, 'Machine')
        [Environment]::SetEnvironmentVariable('JDK11_HOME', $resolved.JdkHome, 'Machine')
    }
    Update-SessionPath
    return $resolved
}

function Test-DotNet8SdkInstalled {
    if (-not (Test-CommandAvailable dotnet)) {
        return $false
    }
    $sdks = @(& dotnet --list-sdks 2>$null)
    return ($sdks | Where-Object { $_ -match '^8\.' }).Count -gt 0
}

function Test-DotNetGlobalToolInstalled {
    param([Parameter(Mandatory = $true)][string]$PackageId)

    if (-not (Test-CommandAvailable dotnet)) {
        return $false
    }

    $list = & dotnet tool list --global 2>$null | Out-String
    return $list -match [regex]::Escape($PackageId)
}

function Get-ProxyUrl {
    foreach ($name in @('HTTPS_PROXY', 'HTTP_PROXY', 'https_proxy', 'http_proxy')) {
        $value = [Environment]::GetEnvironmentVariable($name)
        if (-not [string]::IsNullOrWhiteSpace($value)) {
            return $value.Trim()
        }
    }
    return $null
}

function Initialize-ProxyEnvironment {
    $proxyUrl = Get-ProxyUrl
    if (-not $proxyUrl) {
        return
    }

    foreach ($name in @('HTTP_PROXY', 'HTTPS_PROXY', 'http_proxy', 'https_proxy')) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
            [Environment]::SetEnvironmentVariable($name, $proxyUrl, 'Process')
        }
    }

    $proxy = New-Object System.Net.WebProxy($proxyUrl, $true)
    $proxy.Credentials = [System.Net.CredentialCache]::DefaultCredentials
    [System.Net.WebRequest]::DefaultWebProxy = $proxy
    Write-Detail "Using HTTP(S) proxy: $proxyUrl"
}

function Configure-ChocolateyProxy {
    $proxyUrl = Get-ProxyUrl
    if (-not $proxyUrl -or -not (Test-CommandAvailable choco)) {
        return
    }

    & choco config set proxy $proxyUrl | Out-Null
}

function Test-ZipFile {
    param([Parameter(Mandatory = $true)][string]$Path)
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    if ($bytes.Length -lt 4) { return $false }
    return ($bytes[0] -eq 0x50 -and $bytes[1] -eq 0x4B)
}

function Invoke-DownloadWithRetry {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [Parameter(Mandatory = $true)][string]$OutFile,
        [Parameter(Mandatory = $true)][int]$MinBytes
    )

    $proxyUrl = Get-ProxyUrl
    for ($attempt = 1; $attempt -le $MaxRetries; $attempt++) {
        try {
            Write-Detail "Downloading $Url (attempt $attempt/$MaxRetries)..."
            if (Test-Path -LiteralPath $OutFile) {
                Remove-Item -LiteralPath $OutFile -Force
            }
            $params = @{
                Uri             = $Url
                OutFile         = $OutFile
                UseBasicParsing = $true
            }
            if ($proxyUrl) {
                $params.Proxy = $proxyUrl
                $params.ProxyUseDefaultCredentials = $true
            }
            Invoke-WebRequest @params
            $length = (Get-Item -LiteralPath $OutFile).Length
            if ($length -lt $MinBytes) {
                throw "Download too small ($length bytes, expected at least $MinBytes)."
            }
            return
        } catch {
            if ($attempt -eq $MaxRetries) {
                throw "Download failed after $MaxRetries attempts: $Url`n$_"
            }
            Start-Sleep -Seconds (5 * $attempt)
        }
    }
}

function Ensure-Chocolatey {
    Update-SessionPath
    if (Test-CommandAvailable choco) {
        Write-Detail 'Chocolatey already installed; skipping bootstrap.'
        Configure-ChocolateyProxy
        return
    }

    if ($env:OS -ne 'Windows_NT') {
        throw 'Chocolatey installation requires Windows PowerShell on Windows.'
    }

    Write-Step 'Install Chocolatey'
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor 3072

    $tempDir = Join-Path $env:TEMP 'choco-bootstrap'
    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
    $installScript = Join-Path $tempDir 'install.ps1'
    $nupkgPath = Join-Path $tempDir "chocolatey.$ChocolateyVersion.nupkg"

    Invoke-DownloadWithRetry -Url 'https://community.chocolatey.org/install.ps1' -OutFile $installScript -MinBytes 10000
    $nupkgUrls = @(
        "https://packages.chocolatey.org/chocolatey.$ChocolateyVersion.nupkg",
        "https://community.chocolatey.org/api/v2/package/chocolatey/$ChocolateyVersion"
    )
    $nupkgDownloaded = $false
    foreach ($nupkgUrl in $nupkgUrls) {
        try {
            Invoke-DownloadWithRetry -Url $nupkgUrl -OutFile $nupkgPath -MinBytes 100000
            $nupkgDownloaded = $true
            break
        } catch {
            Write-Warning "Nupkg download failed from $nupkgUrl : $_"
        }
    }
    if (-not $nupkgDownloaded -or -not (Test-ZipFile -Path $nupkgPath)) {
        throw "Could not download chocolatey.$ChocolateyVersion.nupkg."
    }

    Set-ExecutionPolicy Bypass -Scope Process -Force
    & $installScript -ChocolateyDownloadUrl $nupkgPath
    if ($LASTEXITCODE -ne 0 -and $null -ne $LASTEXITCODE) {
        throw "Chocolatey install.ps1 failed with exit code $LASTEXITCODE."
    }

    Update-SessionPath
    if (-not (Test-CommandAvailable choco)) {
        throw 'choco.exe not found after bootstrap.'
    }
    Configure-ChocolateyProxy
    Write-Detail ("Chocolatey {0} ready." -f (& choco --version))
}

function Install-ChocoPackageIfMissing {
    param(
        [Parameter(Mandatory = $true)][string]$Package,
        [Parameter(Mandatory = $true)][scriptblock]$TestInstalled,
        [Parameter(Mandatory = $true)][string]$FailureMessage
    )

    Update-SessionPath
    if (& $TestInstalled) {
        Write-Detail "$Package already available; skipping choco install."
        return
    }

    Ensure-Chocolatey
    Write-Detail "Installing $Package via Chocolatey..."
    & choco install -y --no-progress $Package
    if ($LASTEXITCODE -ne 0) {
        throw $FailureMessage
    }
    Update-SessionPath
}

function Set-MavenEnvironment {
    $mavenHome = $null
    $mavenLib = 'C:\ProgramData\chocolatey\lib\maven'
    $legacyToolsDir = Join-Path $mavenLib 'tools'
    if (Test-Path -LiteralPath $legacyToolsDir) {
        $mavenHome = Get-ChildItem -LiteralPath $legacyToolsDir -Directory -ErrorAction SilentlyContinue |
            Select-Object -First 1
    }
    if (-not $mavenHome -and (Test-Path -LiteralPath $mavenLib)) {
        $mavenHome = Get-ChildItem -LiteralPath $mavenLib -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -like 'apache-maven-*' } |
            Select-Object -First 1
    }
    if ($mavenHome) {
        [Environment]::SetEnvironmentVariable('MAVEN_HOME', $mavenHome.FullName, 'Machine')
        $env:MAVEN_HOME = $mavenHome.FullName
    }
    Update-SessionPath
}

function Set-DotNetEnvironment {
    $dotnetRoot = 'C:\Program Files\dotnet'
    [Environment]::SetEnvironmentVariable('DOTNET_ROOT', $dotnetRoot, 'Machine')
    $env:DOTNET_ROOT = $dotnetRoot

    $machinePath = [Environment]::GetEnvironmentVariable('Path', 'Machine')
    $dotnetTools = Join-Path $env:USERPROFILE '.dotnet\tools'
    $suffix = "$dotnetRoot;$dotnetTools"
    if ($machinePath -notlike "*$dotnetRoot*") {
        [Environment]::SetEnvironmentVariable('Path', "$machinePath;$suffix", 'Machine')
    }
    Update-SessionPath
}

function Ensure-DotNetNuGetSources {
    $nugetOrgUrl = 'https://api.nuget.org/v3/index.json'
    $nugetOrgName = 'nuget.org'

    Update-SessionPath
    if (-not (Test-CommandAvailable dotnet)) {
        return
    }

    Write-Detail 'Ensuring NuGet.org feed is available for dotnet tool install...'
    $sourceList = (& dotnet nuget list source 2>&1 | Out-String)

    if ($sourceList -notmatch [regex]::Escape($nugetOrgUrl)) {
        Write-Detail "Adding NuGet source: $nugetOrgUrl"
        & dotnet nuget add source $nugetOrgUrl -n $nugetOrgName
        if ($LASTEXITCODE -ne 0) {
            throw "dotnet nuget add source failed. WiX/AzureSignTool require $nugetOrgUrl."
        }
    }

    if ($sourceList -match 'nuget\.org\s+\[Disabled\]' -or $sourceList -notmatch 'nuget\.org\s+\[Enabled\]') {
        Write-Detail 'Enabling NuGet.org feed...'
        & dotnet nuget enable source $nugetOrgName 2>&1 | Out-Null
    }
}

function Install-DotNetGlobalToolIfMissing {
    param(
        [Parameter(Mandatory = $true)][string]$PackageId,
        [Parameter(Mandatory = $true)][string]$FailureMessage
    )

    Update-SessionPath
    if (Test-DotNetGlobalToolInstalled -PackageId $PackageId) {
        Write-Detail "dotnet global tool '$PackageId' already installed; skipping."
        return
    }

    if (-not (Test-DotNet8SdkInstalled)) {
        throw "Cannot install dotnet tool '$PackageId' because .NET 8 SDK is not available."
    }

    Ensure-DotNetNuGetSources

    Write-Detail "Installing dotnet global tool '$PackageId'..."
    & dotnet tool install --global --ignore-failed-sources $PackageId
    if ($LASTEXITCODE -ne 0) {
        throw @(
            $FailureMessage
            'Check NuGet feeds with: dotnet nuget list source'
            "Ensure this feed is present and enabled: https://api.nuget.org/v3/index.json"
        ) -join ' '
    }
    Update-SessionPath
}

function Get-ToolchainStatus {
    $jdkMajor = Get-ConfiguredJdkMajor
    $jdkHome = Find-JdkHomeWithMajor -Major $jdkMajor
    $jdkLabel = "jdk$jdkMajor"

    $status = [ordered]@{
        git           = Test-CommandAvailable git
        maven         = Test-CommandAvailable mvn
        dotnet8sdk    = Test-DotNet8SdkInstalled
        wix           = (Test-DotNetGlobalToolInstalled -PackageId 'wix') -or (Test-CommandAvailable wix)
        AzureSignTool = Test-CommandAvailable AzureSignTool
        choco         = Test-CommandAvailable choco
    }
    $status[$jdkLabel] = ($null -ne $jdkHome)
    if ($jdkHome) {
        $status["${jdkLabel}_home"] = $jdkHome
    }
    return $status
}

function Write-ToolchainStatus {
    param([string]$Heading = 'Toolchain status')

    Write-Step $Heading
    Write-Detail ("ITW_JDK_VERSION: {0}" -f (Get-ConfiguredJdkMajor))
    foreach ($entry in (Get-ToolchainStatus).GetEnumerator()) {
        if ($entry.Key -like '*_home') {
            Write-Detail ("{0}: {1}" -f $entry.Key, $entry.Value)
            continue
        }
        $state = if ($entry.Value) { 'installed' } else { 'missing' }
        Write-Detail ("{0,-14} {1}" -f $entry.Key, $state)
    }
}

function Install-HostSignToolchain {
    if ($env:OS -ne 'Windows_NT') {
        throw 'Host toolchain installation requires Windows PowerShell on Windows.'
    }

    Initialize-ProxyEnvironment
    Update-SessionPath

    Write-Step 'Ensure Windows sign toolchain (install missing tools only)'

    $jdkMajor = Get-ConfiguredJdkMajor
    $jdkPackage = Get-ChocoJdkPackageName -Major $jdkMajor
    Write-Detail ("Compile JDK major version: {0}" -f $jdkMajor)

    Install-ChocoPackageIfMissing -Package 'git' -TestInstalled { Test-CommandAvailable git } -FailureMessage 'choco install git failed'
    if (Test-CommandAvailable git) {
        Write-Detail ("git {0}" -f (git --version))
    }

    if (-not (Test-ConfiguredJdkInstalled)) {
        Install-ChocoPackageIfMissing -Package $jdkPackage -TestInstalled { Test-ConfiguredJdkInstalled } -FailureMessage "choco install $jdkPackage failed"
    } else {
        Write-Detail "JDK $jdkMajor already available; skipping choco install."
    }

    $resolvedJdk = Set-ConfiguredJdkEnvironment
    Write-Detail ("Compile JDK home: {0}" -f $resolvedJdk.JdkHome)
    Write-Detail (Invoke-JavaVersionOutput -JavaExe (Join-Path $resolvedJdk.JdkHome 'bin\java.exe') | Select-Object -First 1)

    Install-ChocoPackageIfMissing -Package 'maven' -TestInstalled { Test-CommandAvailable mvn } -FailureMessage 'choco install maven failed'
    Set-MavenEnvironment
    if (Test-CommandAvailable mvn) {
        Write-Detail ("maven {0}" -f ((mvn --version | Select-Object -First 1) -join ' '))
    }

    Install-ChocoPackageIfMissing -Package 'dotnet-8.0-sdk' -TestInstalled { Test-DotNet8SdkInstalled } -FailureMessage 'choco install dotnet-8.0-sdk failed'
    Set-DotNetEnvironment
    Write-Detail ("dotnet {0}" -f (& dotnet --version))

    Install-DotNetGlobalToolIfMissing -PackageId 'wix' -FailureMessage 'dotnet tool install wix failed'
    Install-DotNetGlobalToolIfMissing -PackageId 'AzureSignTool' -FailureMessage 'dotnet tool install AzureSignTool failed'

    if (Test-CommandAvailable wix) {
        Write-Detail ("wix {0}" -f (& wix --version))
    }
    if (Test-CommandAvailable AzureSignTool) {
        Write-Detail ("AzureSignTool {0}" -f (& AzureSignTool --version))
    }

    Write-ToolchainStatus -Heading 'Toolchain ready'
}

if (-not ($VerifyOnly -or $InstallTools)) {
    throw 'Specify -VerifyOnly or -InstallTools.'
}

if ($VerifyOnly) {
    Update-SessionPath
    Write-ToolchainStatus -Heading 'VerifyOnly: toolchain status'
    exit 0
}

Install-HostSignToolchain
