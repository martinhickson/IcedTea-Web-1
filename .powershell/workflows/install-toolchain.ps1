# Host-side Windows toolchain for the full classic Windows job:
#   Cygwin autotools build + rust launchers + WiX 3 MSI + Sign CLI.
# Installs missing tools only; skips packages that are already available.
#
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .powershell\workflows\install-toolchain.ps1 -VerifyOnly
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .powershell\workflows\install-toolchain.ps1 -InstallTools
#
# Toolchain set for this branch:
#   - Cygwin x86_64 + classic autotools/mingw packages (same set as GitHub Actions Windows job)
#   - Chocolatey bootstrap
#   - BellSoft Liberica JDK 8 (compile JDK for windows_build.sh)
#   - Rust via rustup (pinned to match Linux CI)
#   - WiX Toolset v3.14 (verify / install if missing)
#   - .NET 8 SDK + Microsoft Sign CLI (prerelease)
#
# Not used on this line (Trunk-only): maven, wix dotnet tool.

param(
    [string]$ChocolateyVersion = '2.7.3',
    [string]$RustToolchain = '1.85.1',
    [string]$BellSoftJdkUrl = 'https://download.bell-sw.com/java/8u462+11/bellsoft-jdk8u462+11-windows-amd64.zip',
    [string]$CygwinRoot = 'C:\cygwin64',
    [string]$CygwinPackageDir = 'C:\cygwin_packages',
    [string]$CygwinSite = 'https://mirrors.kernel.org/sourceware/cygwin/',
    [string]$CygwinPackages = 'autoconf,automake,cpio,curl,gcc,git,gnupg,grep,libtool,make,mingw64-x86_64-gcc-core,perl,pkg-config,unzip,wget,zip',
    [int]$RequiredJdkMajor = 8,
    [int]$MaxRetries = 5,
    [switch]$VerifyOnly,
    [switch]$InstallTools
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

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

    $cygwinBin = Join-Path $CygwinRoot 'bin'
    if ((Test-Path -LiteralPath $cygwinBin) -and ($env:Path -notlike "*$cygwinBin*")) {
        $env:Path = "$cygwinBin;$env:Path"
    }

    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $javaBin = Join-Path $env:JAVA_HOME 'bin'
        if ((Test-Path -LiteralPath $javaBin) -and ($env:Path -notlike "*$javaBin*")) {
            $env:Path = "$javaBin;$env:Path"
        }
    }

    $cargoBin = Join-Path $env:USERPROFILE '.cargo\bin'
    if ((Test-Path -LiteralPath $cargoBin) -and ($env:Path -notlike "*$cargoBin*")) {
        $env:Path = "$cargoBin;$env:Path"
    }

    $dotnetRoot = 'C:\Program Files\dotnet'
    if ((Test-Path -LiteralPath $dotnetRoot) -and ($env:Path -notlike "*$dotnetRoot*")) {
        $env:Path = "$dotnetRoot;$env:Path"
    }

    $dotnetTools = Join-Path $env:USERPROFILE '.dotnet\tools'
    if ((Test-Path -LiteralPath $dotnetTools) -and ($env:Path -notlike "*$dotnetTools*")) {
        $env:Path = "$dotnetTools;$env:Path"
    }
}

function Add-GitHubPathEntry {
    param([Parameter(Mandatory = $true)][string]$Dir)

    if ([string]::IsNullOrWhiteSpace($env:GITHUB_PATH)) {
        return
    }
    if (-not (Test-Path -LiteralPath $Dir)) {
        return
    }
    $Dir | Out-File -FilePath $env:GITHUB_PATH -Encoding utf8 -Append
}

function Add-GitHubEnvEntry {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Value
    )

    if ([string]::IsNullOrWhiteSpace($env:GITHUB_ENV)) {
        return
    }
    "${Name}=${Value}" | Out-File -FilePath $env:GITHUB_ENV -Encoding utf8 -Append
}

function Publish-ToolchainPaths {
    Add-GitHubPathEntry -Dir (Join-Path $CygwinRoot 'bin')
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        Add-GitHubEnvEntry -Name 'JAVA_HOME' -Value $env:JAVA_HOME
        Add-GitHubPathEntry -Dir (Join-Path $env:JAVA_HOME 'bin')
    }
    Add-GitHubPathEntry -Dir (Join-Path $env:USERPROFILE '.cargo\bin')
    Add-GitHubPathEntry -Dir 'C:\Program Files\dotnet'
    Add-GitHubPathEntry -Dir (Join-Path $env:USERPROFILE '.dotnet\tools')
    if ($env:ChocolateyInstall) {
        Add-GitHubPathEntry -Dir (Join-Path $env:ChocolateyInstall 'bin')
    }
}

function Test-CygwinInstalled {
    $bash = Join-Path $CygwinRoot 'bin\bash.exe'
    $make = Join-Path $CygwinRoot 'bin\make.exe'
    return ((Test-Path -LiteralPath $bash) -and (Test-Path -LiteralPath $make))
}

function Install-CygwinIfMissing {
    if (Test-CygwinInstalled) {
        Write-Detail ("Cygwin already available: {0}" -f (Join-Path $CygwinRoot 'bin\bash.exe'))
        $cygwinBin = Join-Path $CygwinRoot 'bin'
        if ($env:Path -notlike "*$cygwinBin*") {
            $env:Path = "$cygwinBin;$env:Path"
        }
        return
    }

    Write-Step 'Install Cygwin (classic Windows autotools toolchain)'
    Write-Detail ("Root:     {0}" -f $CygwinRoot)
    Write-Detail ("Packages: {0}" -f $CygwinPackages)
    Write-Detail ("Site:     {0}" -f $CygwinSite)

    $setupDir = 'C:\temp'
    New-Item -ItemType Directory -Force -Path $setupDir | Out-Null
    New-Item -ItemType Directory -Force -Path $CygwinPackageDir | Out-Null

    $setupExe = Join-Path $setupDir 'cygwin-setup-x86_64.exe'
    $setupSig = Join-Path $setupDir 'cygwin-setup-x86_64.exe.sig'
    Invoke-DownloadWithRetry -Url 'https://cygwin.com/setup-x86_64.exe' -OutFile $setupExe -MinBytes 500000
    try {
        Invoke-DownloadWithRetry -Url 'https://cygwin.com/setup-x86_64.exe.sig' -OutFile $setupSig -MinBytes 100
        if (Test-CommandAvailable gpg) {
            Write-Detail 'Verifying Cygwin setup signature (best effort)...'
            & gpg --keyid-format=long --with-fingerprint --verify $setupSig $setupExe
            if ($LASTEXITCODE -ne 0) {
                Write-Warning "gpg verify returned $LASTEXITCODE; continuing with downloaded setup (same as CI keyserver best-effort)."
            }
        } else {
            Write-Detail 'gpg not on PATH yet; skipping setup signature verify for bootstrap.'
        }
    } catch {
        Write-Warning "Could not download/verify Cygwin setup signature: $_"
    }

    $setupArgs = @(
        '--packages', $CygwinPackages
        '--quiet-mode'
        '--download'
        '--local-install'
        '--delete-orphans'
        '--site', $CygwinSite
        '--local-package-dir', $CygwinPackageDir
        '--root', $CygwinRoot
        '--no-desktop'
        '--no-shortcuts'
        '--no-startmenu'
    )

    Write-Detail "Running: $setupExe $($setupArgs -join ' ')"
    $proc = Start-Process -Wait -PassThru -FilePath $setupExe -ArgumentList $setupArgs
    if ($proc.ExitCode -ne 0) {
        throw "Cygwin setup failed with exit code $($proc.ExitCode)."
    }

    if (-not (Test-CygwinInstalled)) {
        throw "Cygwin install finished but bash/make not found under $CygwinRoot\bin"
    }

    $cygwinBin = Join-Path $CygwinRoot 'bin'
    if ($env:Path -notlike "*$cygwinBin*") {
        $env:Path = "$cygwinBin;$env:Path"
    }

    # Match GitHub Actions Windows job git safety defaults inside Cygwin.
    $bash = Join-Path $CygwinRoot 'bin\bash.exe'
    & $bash -lc "mkdir -p `$HOME; git config --system core.autocrlf false; git config --system --add safe.directory '*'" | Out-Null
    Write-Detail ("Cygwin ready: {0}" -f $bash)
}

function Get-JavaVersionOutput {
    param([Parameter(Mandatory = $true)][string]$JavaExe)

    # java -version writes to stderr; with $ErrorActionPreference=Stop that becomes terminating.
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        return (& $JavaExe -version 2>&1 | ForEach-Object { "$_" })
    } finally {
        $ErrorActionPreference = $prevEap
    }
}

function Get-JdkMajorFromJavaExe {
    param([Parameter(Mandatory = $true)][string]$JavaExe)

    if (-not (Test-Path -LiteralPath $JavaExe)) {
        return $null
    }

    $output = (Get-JavaVersionOutput -JavaExe $JavaExe) | Out-String
    if ($output -match 'version "1\.(\d+)') {
        return [int]$Matches[1]
    }
    if ($output -match 'version "(\d+)') {
        return [int]$Matches[1]
    }
    return $null
}

function Test-JdkHomeMajor {
    param(
        [Parameter(Mandatory = $true)][string]$JdkHome,
        [Parameter(Mandatory = $true)][int]$Major
    )

    $javaExe = Join-Path $JdkHome 'bin\java.exe'
    $javacExe = Join-Path $JdkHome 'bin\javac.exe'
    if (-not ((Test-Path -LiteralPath $javaExe) -and (Test-Path -LiteralPath $javacExe))) {
        return $false
    }
    return ((Get-JdkMajorFromJavaExe -JavaExe $javaExe) -eq $Major)
}

function Find-ConfiguredJdkHome {
    $candidates = @()
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $candidates += $env:JAVA_HOME.Trim()
    }
    $bellsoftRoot = Join-Path $env:USERPROFILE 'bellsoft-jdk'
    if (Test-Path -LiteralPath $bellsoftRoot) {
        $candidates += @(
            Get-ChildItem -LiteralPath $bellsoftRoot -Directory -ErrorAction SilentlyContinue |
                ForEach-Object { $_.FullName }
        )
    }

    foreach ($candidate in $candidates) {
        if (Test-JdkHomeMajor -JdkHome $candidate -Major $RequiredJdkMajor) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    return $null
}

function Set-JdkEnvironment {
    param([Parameter(Mandatory = $true)][string]$JdkHome)

    $env:JAVA_HOME = $JdkHome
    [Environment]::SetEnvironmentVariable('JAVA_HOME', $JdkHome, 'Process')
    Update-SessionPath
}

function Install-BellSoftJdkIfMissing {
    Write-Detail ("Compile JDK major version: {0}" -f $RequiredJdkMajor)

    $existing = Find-ConfiguredJdkHome
    if ($existing) {
        Set-JdkEnvironment -JdkHome $existing
        Write-Detail ("JDK $RequiredJdkMajor already available: $existing")
        $verLine = @(Get-JavaVersionOutput -JavaExe (Join-Path $existing 'bin\java.exe') | Select-Object -First 1) -join ' '
        Write-Detail $verLine
        return
    }

    Write-Step 'Install BellSoft Liberica JDK 8'
    $zipPath = Join-Path $env:TEMP 'bellsoft-jdk8-windows-amd64.zip'
    $extractRoot = Join-Path $env:USERPROFILE 'bellsoft-jdk'

    Invoke-DownloadWithRetry -Url $BellSoftJdkUrl -OutFile $zipPath -MinBytes 1000000
    if (Test-Path -LiteralPath $extractRoot) {
        Remove-Item -LiteralPath $extractRoot -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $extractRoot | Out-Null
    Write-Detail "Extracting BellSoft JDK to $extractRoot..."
    Expand-Archive -LiteralPath $zipPath -DestinationPath $extractRoot -Force

    $jdkHome = Get-ChildItem -LiteralPath $extractRoot -Directory |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin\javac.exe') } |
        Select-Object -First 1
    if (-not $jdkHome) {
        throw "BellSoft JDK extract did not contain a JDK home under $extractRoot"
    }

    $resolved = (Resolve-Path -LiteralPath $jdkHome.FullName).Path
    if (-not (Test-JdkHomeMajor -JdkHome $resolved -Major $RequiredJdkMajor)) {
        throw "Installed BellSoft JDK at $resolved is not JDK $RequiredJdkMajor"
    }

    Set-JdkEnvironment -JdkHome $resolved
    Write-Detail ("JAVA_HOME=$resolved")
    $verLine = @(Get-JavaVersionOutput -JavaExe (Join-Path $resolved 'bin\java.exe') | Select-Object -First 1) -join ' '
    Write-Detail $verLine
}

function Test-CommandAvailable {
    param([Parameter(Mandatory = $true)][string]$Name)
    return $null -ne (Get-Command $Name -ErrorAction SilentlyContinue)
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

    foreach ($line in (& dotnet tool list --global 2>$null)) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0) {
            continue
        }
        if ($trimmed -like 'Package Id*' -or $trimmed -like '-----*') {
            continue
        }

        $package = ($trimmed -split '\s+')[0]
        if ($package -eq $PackageId) {
            return $true
        }
    }

    return $false
}

function Get-DotNetGlobalToolCommandName {
    param([Parameter(Mandatory = $true)][string]$PackageId)

    foreach ($line in (& dotnet tool list --global 2>$null)) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0) {
            continue
        }
        if ($trimmed -like 'Package Id*' -or $trimmed -like '-----*') {
            continue
        }

        $parts = $trimmed -split '\s+'
        if ($parts.Count -lt 3) {
            continue
        }
        if ($parts[0] -ne $PackageId) {
            continue
        }

        return $parts[$parts.Count - 1]
    }

    return $PackageId
}

function Resolve-DotNetGlobalToolExe {
    param([Parameter(Mandatory = $true)][string]$PackageId)

    Update-SessionPath
    $commandName = Get-DotNetGlobalToolCommandName -PackageId $PackageId
    if (Test-CommandAvailable $commandName) {
        return (Get-Command $commandName -ErrorAction Stop).Source
    }

    $dotnetTools = Join-Path $env:USERPROFILE '.dotnet\tools'
    $exePath = Join-Path $dotnetTools "$commandName.exe"
    if (Test-Path -LiteralPath $exePath) {
        return (Resolve-Path -LiteralPath $exePath).Path
    }

    return $null
}

function Test-DotNetGlobalToolCommandAvailable {
    param([Parameter(Mandatory = $true)][string]$PackageId)

    return $null -ne (Resolve-DotNetGlobalToolExe -PackageId $PackageId)
}

function Test-RustupAvailable {
    Update-SessionPath
    return Test-CommandAvailable rustup
}

function Test-MsvcLinkerAvailable {
    $vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
    if (-not (Test-Path -LiteralPath $vswhere)) {
        return $false
    }
    $installPath = & $vswhere -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath 2>$null |
        Select-Object -First 1
    return -not [string]::IsNullOrWhiteSpace($installPath)
}

function Resolve-RustToolchainId {
    # Bare pins like "1.85.1" resolve via rustup default-host (MSVC on Windows).
    # Signing hosts without VS Build Tools need the gnu toolchain + mingw.
    if ($RustToolchain -match '-(windows-msvc|windows-gnu|unknown-linux-|apple-darwin)') {
        return $RustToolchain
    }
    if ($env:OS -match 'Windows' -and -not (Test-MsvcLinkerAvailable)) {
        return ('{0}-x86_64-pc-windows-gnu' -f $RustToolchain)
    }
    return $RustToolchain
}

function Test-PinnedRustInstalled {
    Update-SessionPath
    if (-not (Test-CommandAvailable rustc) -or -not (Test-CommandAvailable cargo)) {
        return $false
    }
    if (-not (Test-CommandAvailable rustup)) {
        # cargo/rustc present without rustup (e.g. standalone); accept for build.
        return $true
    }
    $wanted = Resolve-RustToolchainId
    $active = (& rustup show active-toolchain 2>$null | Select-Object -First 1)
    return ($active -match [regex]::Escape($wanted))
}

function Get-WixToolsetBinPath {
    $candidates = @(
        'C:\Program Files (x86)\WiX Toolset v3.14\bin'
        'C:\Program Files (x86)\WiX Toolset v3.11\bin'
        'C:\Program Files (x86)\WiX Toolset v3.14'
    )
    foreach ($candidate in $candidates) {
        $candle = Join-Path $candidate 'candle.exe'
        if (Test-Path -LiteralPath $candle) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
        $nested = Join-Path $candidate 'bin\candle.exe'
        if (Test-Path -LiteralPath $nested) {
            return (Resolve-Path -LiteralPath (Join-Path $candidate 'bin')).Path
        }
    }
    return $null
}

function Test-WixToolsetInstalled {
    return $null -ne (Get-WixToolsetBinPath)
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

function Set-DotNetEnvironment {
    $dotnetRoot = 'C:\Program Files\dotnet'
    [Environment]::SetEnvironmentVariable('DOTNET_ROOT', $dotnetRoot, 'Machine')
    $env:DOTNET_ROOT = $dotnetRoot

    $dotnetTools = Join-Path $env:USERPROFILE '.dotnet\tools'

    $machinePath = [Environment]::GetEnvironmentVariable('Path', 'Machine')
    if ($machinePath -notlike "*$dotnetRoot*") {
        [Environment]::SetEnvironmentVariable('Path', "$machinePath;$dotnetRoot", 'Machine')
    }

    $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    if ($userPath -notlike "*$dotnetTools*") {
        $nextUserPath = if ([string]::IsNullOrWhiteSpace($userPath)) {
            $dotnetTools
        } else {
            "$userPath;$dotnetTools"
        }
        [Environment]::SetEnvironmentVariable('Path', $nextUserPath, 'User')
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
            throw "dotnet nuget add source failed. Sign CLI requires $nugetOrgUrl."
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
        [Parameter(Mandatory = $true)][string]$FailureMessage,
        [switch]$Prerelease
    )

    Update-SessionPath
    if ((Test-DotNetGlobalToolInstalled -PackageId $PackageId) -and (Test-DotNetGlobalToolCommandAvailable -PackageId $PackageId)) {
        Write-Detail "dotnet global tool '$PackageId' already installed; skipping."
        return
    }

    if (Test-DotNetGlobalToolInstalled -PackageId $PackageId) {
        Write-Detail "dotnet global tool '$PackageId' is registered but its command is missing; reinstalling..."
        & dotnet tool uninstall --global $PackageId 2>$null | Out-Null
    }

    if (-not (Test-DotNet8SdkInstalled)) {
        throw "Cannot install dotnet tool '$PackageId' because .NET 8 SDK is not available."
    }

    Ensure-DotNetNuGetSources

    Write-Detail "Installing dotnet global tool '$PackageId'..."
    $installArgs = @('tool', 'install', '--global', '--ignore-failed-sources')
    if ($Prerelease) {
        $installArgs += '--prerelease'
    }
    $installArgs += $PackageId
    & dotnet @installArgs
    if ($LASTEXITCODE -ne 0) {
        throw @(
            $FailureMessage
            'Check NuGet feeds with: dotnet nuget list source'
            "Ensure this feed is present and enabled: https://api.nuget.org/v3/index.json"
        ) -join ' '
    }
    Update-SessionPath
    Set-DotNetEnvironment

    if (-not (Test-DotNetGlobalToolCommandAvailable -PackageId $PackageId)) {
        throw "dotnet tool '$PackageId' was installed but its command is still unavailable. Check %USERPROFILE%\.dotnet\tools is on PATH."
    }
}

function Ensure-LegacyRustPathJunction {
    # windows_build.sh historically looks for /cygdrive/c/rust/bin
    $cargoBin = Join-Path $env:USERPROFILE '.cargo\bin'
    $legacyBin = 'C:\rust\bin'
    if (-not (Test-Path -LiteralPath (Join-Path $cargoBin 'cargo.exe'))) {
        return
    }
    if (Test-Path -LiteralPath (Join-Path $legacyBin 'cargo.exe')) {
        return
    }

    Write-Detail "Creating legacy rust path junction: $legacyBin -> $cargoBin"
    New-Item -ItemType Directory -Force -Path 'C:\rust' | Out-Null
    if (Test-Path -LiteralPath $legacyBin) {
        Remove-Item -LiteralPath $legacyBin -Force -Recurse -ErrorAction SilentlyContinue
    }
    cmd /c mklink /J "$legacyBin" "$cargoBin" | Out-Null
}

function Install-RustIfMissing {
    $toolchainId = Resolve-RustToolchainId
    Write-Detail ("Rust toolchain pin: {0} (resolved: {1})" -f $RustToolchain, $toolchainId)

    if (-not (Test-RustupAvailable)) {
        Install-ChocoPackageIfMissing `
            -Package 'rustup.install' `
            -TestInstalled { Test-RustupAvailable } `
            -FailureMessage 'choco install rustup.install failed'
    } else {
        Write-Detail 'rustup already available; skipping choco install.'
    }

    Update-SessionPath
    if (-not (Test-CommandAvailable rustup)) {
        throw 'rustup is not available after install.'
    }

    if ($toolchainId -match 'x86_64-pc-windows-gnu') {
        Write-Detail 'No MSVC linker detected; using windows-gnu rustup host/toolchain.'
        & rustup set default-host x86_64-pc-windows-gnu
    }

    Write-Detail "Installing/selecting rustup toolchain $toolchainId..."
    & rustup toolchain install $toolchainId
    if ($LASTEXITCODE -ne 0) {
        throw "rustup toolchain install $toolchainId failed with exit code $LASTEXITCODE."
    }
    & rustup default $toolchainId
    if ($LASTEXITCODE -ne 0) {
        throw "rustup default $toolchainId failed with exit code $LASTEXITCODE."
    }
    $env:RUSTUP_TOOLCHAIN = $toolchainId

    Update-SessionPath
    Ensure-LegacyRustPathJunction
    Update-SessionPath

    if (-not (Test-CommandAvailable rustc) -or -not (Test-CommandAvailable cargo)) {
        throw 'rustc/cargo not available after rustup install.'
    }
    Write-Detail ("rustc {0}" -f (& rustc --version))
    Write-Detail ("cargo {0}" -f (& cargo --version))
    Write-Detail ("rustc host: {0}" -f ((& rustc -Vv) | Select-String '^host:').ToString())
}

function Get-ToolchainStatus {
    return [ordered]@{
        cygwin     = Test-CygwinInstalled
        jdk8       = ($null -ne (Find-ConfiguredJdkHome))
        choco      = Test-CommandAvailable choco
        rustup     = Test-RustupAvailable
        rust       = Test-PinnedRustInstalled
        wix3       = Test-WixToolsetInstalled
        dotnet8sdk = Test-DotNet8SdkInstalled
        sign       = Test-DotNetGlobalToolCommandAvailable -PackageId 'sign'
    }
}

function Get-MissingToolchainComponents {
    $missing = @()
    foreach ($entry in (Get-ToolchainStatus).GetEnumerator()) {
        if (-not $entry.Value) {
            $missing += $entry.Key
        }
    }
    return $missing
}

function Write-ToolchainStatus {
    param([string]$Heading = 'Toolchain status')

    Write-Step $Heading
    Write-Detail ("RustToolchain: {0}" -f $RustToolchain)
    Write-Detail ("RequiredJdkMajor: {0}" -f $RequiredJdkMajor)
    foreach ($entry in (Get-ToolchainStatus).GetEnumerator()) {
        $state = if ($entry.Value) { 'installed' } else { 'missing' }
        Write-Detail ("{0,-14} {1}" -f $entry.Key, $state)
    }
    $jdkHome = Find-ConfiguredJdkHome
    if ($jdkHome) {
        Write-Detail ("JAVA_HOME      {0}" -f $jdkHome)
    }
    $wixBin = Get-WixToolsetBinPath
    if ($wixBin) {
        Write-Detail ("wix3_bin       {0}" -f $wixBin)
    }
}

function Install-HostWindowsToolchain {
    if ($env:OS -ne 'Windows_NT') {
        throw 'Host toolchain installation requires Windows PowerShell on Windows.'
    }

    Initialize-ProxyEnvironment
    Update-SessionPath

    Write-Step 'Ensure classic Windows toolchain (install missing tools only)'
    Write-Detail 'Set: Cygwin, BellSoft JDK 8, rustup/rust, WiX Toolset 3.x, .NET 8 SDK, Microsoft Sign CLI'

    Install-CygwinIfMissing

    Install-BellSoftJdkIfMissing

    Install-RustIfMissing

    if (-not (Test-WixToolsetInstalled)) {
        Install-ChocoPackageIfMissing `
            -Package 'wixtoolset' `
            -TestInstalled { Test-WixToolsetInstalled } `
            -FailureMessage 'choco install wixtoolset failed'
    } else {
        Write-Detail ("WiX Toolset already available: {0}" -f (Get-WixToolsetBinPath))
    }
    if (-not (Test-WixToolsetInstalled)) {
        throw 'WiX Toolset v3.x candle.exe not found after install. Expected under Program Files (x86)\WiX Toolset v3.14\bin'
    }

    Install-ChocoPackageIfMissing `
        -Package 'dotnet-8.0-sdk' `
        -TestInstalled { Test-DotNet8SdkInstalled } `
        -FailureMessage 'choco install dotnet-8.0-sdk failed'
    Set-DotNetEnvironment
    Write-Detail ("dotnet {0}" -f (& dotnet --version))

    Install-DotNetGlobalToolIfMissing -PackageId 'sign' -Prerelease -FailureMessage 'dotnet tool install sign failed'
    $signExe = Resolve-DotNetGlobalToolExe -PackageId 'sign'
    if (-not $signExe) {
        throw "Microsoft Sign CLI ('sign') is not available after install. Run: dotnet tool install --global --prerelease sign"
    }
    Write-Detail ("sign {0}" -f (& $signExe --version))

    Publish-ToolchainPaths
    Write-ToolchainStatus -Heading 'Toolchain ready'
}

if (-not ($VerifyOnly -or $InstallTools)) {
    throw 'Specify -VerifyOnly or -InstallTools.'
}

if ($InstallTools) {
    Install-HostWindowsToolchain
    exit 0
}

Update-SessionPath
Write-ToolchainStatus -Heading 'VerifyOnly: toolchain status'
$missing = @(Get-MissingToolchainComponents)
if ($missing.Count -gt 0) {
    throw "Toolchain missing: $($missing -join ', '). Run with -InstallTools to install."
}
exit 0
