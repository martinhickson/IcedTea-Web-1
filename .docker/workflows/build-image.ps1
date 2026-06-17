# Windows sign image build helpers (Chocolatey bootstrap and toolchain).
# Preflight on the host before docker build:
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .docker/workflows/build-image.ps1 -VerifyOnly
# Network diagnostics (nslookup, Test-NetConnection, etc.) are off by default; enable with:
#   -NetworkDiagnostics
#   ITW_NETWORK_DIAGNOSTICS=true
param(
    [string]$ChocolateyVersion = '2.7.3',
    [int]$MaxRetries = 5,
    [switch]$VerifyOnly,
    [switch]$InstallTools,
    [switch]$NetworkDiagnostics
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

function Get-ProxyUrl {
    foreach ($name in @('HTTPS_PROXY', 'HTTP_PROXY', 'https_proxy', 'http_proxy')) {
        $value = [Environment]::GetEnvironmentVariable($name)
        if (-not [string]::IsNullOrWhiteSpace($value)) {
            return $value.Trim()
        }
    }
    return $null
}

function Get-NoProxyList {
    foreach ($name in @('NO_PROXY', 'no_proxy')) {
        $value = [Environment]::GetEnvironmentVariable($name)
        if (-not [string]::IsNullOrWhiteSpace($value)) {
            return $value.Trim()
        }
    }
    return $null
}

function Test-ShouldBypassProxy {
    param([Parameter(Mandatory = $true)][string]$Url)

    $noProxy = Get-NoProxyList
    if ([string]::IsNullOrWhiteSpace($noProxy)) {
        return $false
    }

    $hostName = ([Uri]$Url).Host
    foreach ($entry in ($noProxy -split '[,\s;]+' | Where-Object { $_ })) {
        $entry = $entry.Trim()
        if ($entry -eq '*') {
            return $true
        }
        if ($entry.StartsWith('.')) {
            if ($hostName.EndsWith($entry, [StringComparison]::OrdinalIgnoreCase)) {
                return $true
            }
            continue
        }
        if ($hostName -eq $entry -or $hostName.EndsWith(".$entry", [StringComparison]::OrdinalIgnoreCase)) {
            return $true
        }
    }
    return $false
}

function Initialize-ProxyEnvironment {
    $proxyUrl = Get-ProxyUrl
    if (-not $proxyUrl) {
        return
    }

    $noProxy = Get-NoProxyList
    foreach ($name in @('HTTP_PROXY', 'HTTPS_PROXY', 'http_proxy', 'https_proxy')) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
            [Environment]::SetEnvironmentVariable($name, $proxyUrl, 'Process')
        }
    }
    if ($noProxy) {
        foreach ($name in @('NO_PROXY', 'no_proxy')) {
            if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
                [Environment]::SetEnvironmentVariable($name, $noProxy, 'Process')
            }
        }
    }

    $proxy = New-Object System.Net.WebProxy($proxyUrl, $true)
    if ($noProxy) {
        $proxy.BypassList = @($noProxy -split '[,\s;]+' | Where-Object { $_ } | ForEach-Object { $_.Trim() })
    }
    $proxy.Credentials = [System.Net.CredentialCache]::DefaultCredentials
    [System.Net.WebRequest]::DefaultWebProxy = $proxy

    Write-Host "Using HTTP(S) proxy for outbound downloads."
}

function Configure-ChocolateyProxy {
    $proxyUrl = Get-ProxyUrl
    if (-not $proxyUrl) {
        return
    }

    if (-not (Get-Command choco -ErrorAction SilentlyContinue)) {
        return
    }

    & choco config set proxy $proxyUrl | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "choco config set proxy failed (exit code $LASTEXITCODE)."
    }
}

Initialize-ProxyEnvironment

function Test-NetworkDiagnosticsEnabled {
    if ($NetworkDiagnostics) {
        return $true
    }

    $value = $env:ITW_NETWORK_DIAGNOSTICS
    if ([string]::IsNullOrWhiteSpace($value)) {
        return $false
    }

    switch ($value.Trim().ToLowerInvariant()) {
        '1' { return $true }
        'true' { return $true }
        'yes' { return $true }
        'on' { return $true }
        default { return $false }
    }
}

function Write-BootstrapStep {
    param([Parameter(Mandatory = $true)][string]$Message)
    Write-Host ""
    Write-Host "=== $Message ==="
}

function Write-DiagnosticLine {
    param(
        [Parameter(Mandatory = $true)][string]$Label,
        [string]$Value = '(not set)'
    )

    if ([string]::IsNullOrWhiteSpace($Value)) {
        $Value = '(not set)'
    }
    Write-Host ("  {0}: {1}" -f $Label, $Value)
}

function Invoke-DiagnosticCommand {
    param(
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][string]$CommandLine
    )

    Write-Host ""
    Write-Host "--- $Label ---"
    Write-Host "  command: $CommandLine"
    try {
        $output = cmd.exe /c $CommandLine 2>&1 | Out-String
        if ([string]::IsNullOrWhiteSpace($output)) {
            Write-Host "  (no output)"
        } else {
            foreach ($line in ($output.TrimEnd() -split "`r?`n")) {
                Write-Host "  $line"
            }
        }
    } catch {
        Write-Warning "  command failed: $_"
    }
}

function Write-BuildNetworkDiagnostics {
    param(
        [Parameter(Mandatory = $true)][string]$Phase
    )

    if (-not (Test-NetworkDiagnosticsEnabled)) {
        return
    }

    Write-BootstrapStep "Network diagnostics ($Phase)"

    Write-DiagnosticLine 'Computer name' $env:COMPUTERNAME
    Write-DiagnosticLine 'HTTP_PROXY' $env:HTTP_PROXY
    Write-DiagnosticLine 'HTTPS_PROXY' $env:HTTPS_PROXY
    Write-DiagnosticLine 'NO_PROXY' $env:NO_PROXY
    Write-DiagnosticLine 'ITW_DNS_SERVERS' $env:ITW_DNS_SERVERS

    $chocoHosts = @(
        'community.chocolatey.org',
        'packages.chocolatey.org'
    )

    foreach ($dnsName in $chocoHosts) {
        Invoke-DiagnosticCommand -Label "nslookup $dnsName" -CommandLine "nslookup $dnsName"

        Write-Host ""
        Write-Host "--- Resolve-DnsName $dnsName ---"
        try {
            $records = @(Resolve-DnsName -Name $dnsName -ErrorAction Stop)
            foreach ($record in $records) {
                Write-Host ("  {0} {1}" -f $record.Type, $record.IPAddress)
            }
        } catch {
            Write-Warning "  Resolve-DnsName failed: $_"
        }

        Write-Host ""
        Write-Host "--- [System.Net.Dns]::GetHostAddresses($dnsName) ---"
        try {
            $addresses = [System.Net.Dns]::GetHostAddresses($dnsName)
            foreach ($address in $addresses) {
                Write-Host "  $($address.ToString())"
            }
        } catch {
            Write-Warning "  .NET DNS lookup failed: $_"
        }
    }

    Write-Host ""
    Write-Host '--- DNS client configuration ---'
    try {
        $dnsConfigs = @(Get-DnsClientServerAddress -AddressFamily IPv4 -ErrorAction Stop | Where-Object { $_.ServerAddresses })
        if ($dnsConfigs.Count -eq 0) {
            Write-Host '  (no IPv4 DNS servers configured)'
        } else {
            foreach ($dnsConfig in $dnsConfigs) {
                Write-Host ("  {0}: {1}" -f $dnsConfig.InterfaceAlias, ($dnsConfig.ServerAddresses -join ', '))
            }
        }
    } catch {
        Write-Warning "  Get-DnsClientServerAddress failed: $_"
    }

    Write-Host ""
    Write-Host '--- Network adapters ---'
    try {
        Get-NetAdapter -ErrorAction Stop |
            Select-Object Name, InterfaceDescription, Status, LinkSpeed |
            Format-Table -AutoSize |
            Out-String -Width 200 |
            ForEach-Object {
                foreach ($line in ($_.TrimEnd() -split "`r?`n")) {
                    Write-Host "  $line"
                }
            }
    } catch {
        Write-Warning "  Get-NetAdapter failed: $_"
    }

    Write-Host ""
    Write-Host '--- IP configuration ---'
    try {
        $ipConfigs = @(Get-NetIPConfiguration -ErrorAction Stop | Where-Object { $_.IPv4Address -or $_.IPv4DefaultGateway })
        if ($ipConfigs.Count -eq 0) {
            Write-Host '  (no IPv4 configuration found)'
        } else {
            foreach ($ipConfig in $ipConfigs) {
                $ipv4 = ($ipConfig.IPv4Address | ForEach-Object { $_.IPAddress }) -join ', '
                $gateway = if ($ipConfig.IPv4DefaultGateway) { $ipConfig.IPv4DefaultGateway.NextHop } else { '(none)' }
                $dnsServers = ($ipConfig.DnsServer | ForEach-Object { $_.ServerAddresses }) -join ', '
                Write-Host ("  {0}: IPv4={1} gateway={2} DNS={3}" -f $ipConfig.InterfaceAlias, $ipv4, $gateway, $dnsServers)
            }
        }
    } catch {
        Write-Warning "  Get-NetIPConfiguration failed: $_"
    }

    foreach ($dnsName in $chocoHosts) {
        Write-Host ""
        Write-Host "--- Test-NetConnection $dnsName`:443 ---"
        try {
            $test = Test-NetConnection -ComputerName $dnsName -Port 443 -WarningAction SilentlyContinue -ErrorAction Stop
            Write-DiagnosticLine 'TcpTestSucceeded' $test.TcpTestSucceeded
            Write-DiagnosticLine 'RemoteAddress' $test.RemoteAddress
            Write-DiagnosticLine 'NameResolutionSucceeded' $test.NameResolutionSucceeded
            Write-DiagnosticLine 'InterfaceAlias' $test.InterfaceAlias
        } catch {
            Write-Warning "  Test-NetConnection failed: $_"
        }
    }
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

    $outDir = Split-Path -Parent $OutFile
    if ($outDir -and -not (Test-Path -LiteralPath $outDir)) {
        New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    }

    for ($attempt = 1; $attempt -le $MaxRetries; $attempt++) {
        try {
            Write-Host "Downloading $Url (attempt $attempt/$MaxRetries)..."
            if (Test-Path -LiteralPath $OutFile) {
                Remove-Item -LiteralPath $OutFile -Force
            }
            $requestParams = @{
                Uri             = $Url
                OutFile         = $OutFile
                UseBasicParsing = $true
            }
            if (-not (Test-ShouldBypassProxy -Url $Url)) {
                $proxyUrl = Get-ProxyUrl
                if ($proxyUrl) {
                    $requestParams.Proxy = $proxyUrl
                    $requestParams.ProxyUseDefaultCredentials = $true
                }
            }
            Invoke-WebRequest @requestParams
            $length = (Get-Item -LiteralPath $OutFile).Length
            if ($length -lt $MinBytes) {
                throw "Download too small ($length bytes, expected at least $MinBytes)."
            }
            Write-Host "  OK ($length bytes)"
            return
        }
        catch {
            if ($attempt -ne $MaxRetries) {
                Write-Warning "  Attempt $attempt failed: $_"
                Start-Sleep -Seconds (5 * $attempt)
                continue
            }
            throw "Download failed after $MaxRetries attempts: $Url`n$_"
        }
    }
}

function Install-ChocolateyBootstrap {
    Write-BuildNetworkDiagnostics -Phase 'before Chocolatey bootstrap'
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor 3072

    $installUrl = 'https://community.chocolatey.org/install.ps1'
    $nupkgUrls = @(
        "https://packages.chocolatey.org/chocolatey.$ChocolateyVersion.nupkg",
        "https://community.chocolatey.org/api/v2/package/chocolatey/$ChocolateyVersion"
    )
    $tempRoot = if ($IsWindows -or $env:OS -eq 'Windows_NT') { 'C:\Temp' } else { if ($env:TEMP) { $env:TEMP } else { '/tmp' } }
    $tempDir = Join-Path $tempRoot 'choco-bootstrap'

    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
    $installScript = Join-Path $tempDir 'install.ps1'
    $nupkgPath = Join-Path $tempDir "chocolatey.$ChocolateyVersion.nupkg"

    Write-BootstrapStep "Chocolatey bootstrap: install.ps1"
    Invoke-DownloadWithRetry -Url $installUrl -OutFile $installScript -MinBytes 10000

    Write-BootstrapStep "Chocolatey bootstrap: chocolatey.$ChocolateyVersion.nupkg"
    $nupkgDownloaded = $false
    foreach ($nupkgUrl in $nupkgUrls) {
        try {
            Invoke-DownloadWithRetry -Url $nupkgUrl -OutFile $nupkgPath -MinBytes 100000
            $nupkgDownloaded = $true
            break
        }
        catch {
            Write-Warning "Nupkg download failed from $nupkgUrl : $_"
        }
    }
    if (-not $nupkgDownloaded) {
        throw "Could not download chocolatey.$ChocolateyVersion.nupkg from any configured source."
    }
    if (-not (Test-ZipFile -Path $nupkgPath)) {
        throw "Downloaded nupkg is not a valid zip archive: $nupkgPath"
    }

    if (-not ($IsWindows -or $env:OS -eq 'Windows_NT')) {
        throw "Chocolatey installation requires Windows. Re-run without -VerifyOnly on a Windows host or inside the sign container build."
    }

    Write-BootstrapStep "Chocolatey bootstrap: running install.ps1 (local nupkg)"
    Set-ExecutionPolicy Bypass -Scope Process -Force
    & $installScript -ChocolateyDownloadUrl $nupkgPath
    if ($LASTEXITCODE -ne 0 -and $null -ne $LASTEXITCODE) {
        throw "install.ps1 failed with exit code $LASTEXITCODE."
    }

    $choco = Join-Path $env:ChocolateyInstall 'bin\choco.exe'
    if (-not (Test-Path -LiteralPath $choco)) {
        $choco = 'C:\ProgramData\chocolatey\bin\choco.exe'
    }
    if (-not (Test-Path -LiteralPath $choco)) {
        throw "choco.exe not found after bootstrap."
    }

    Configure-ChocolateyProxy

    $installedVersion = & $choco --version
    Write-BootstrapStep "Chocolatey bootstrap complete: choco $installedVersion"
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
}

function Install-ChocoPackage {
    param(
        [Parameter(Mandatory = $true)][string]$Package,
        [Parameter(Mandatory = $true)][string]$FailureMessage
    )

    & choco install -y --no-progress $Package
    if ($LASTEXITCODE -ne 0) {
        throw $FailureMessage
    }
    Update-SessionPath
}

function Install-SignImageToolchain {
    Write-BuildNetworkDiagnostics -Phase 'before Chocolatey package installs'
    Initialize-ProxyEnvironment
    Configure-ChocolateyProxy
    Update-SessionPath

    Write-BootstrapStep 'Install Git for Windows'
    Install-ChocoPackage -Package 'git' -FailureMessage 'choco install git failed'
    git --version

    Write-BootstrapStep 'Install Amazon Corretto 11'
    Install-ChocoPackage -Package 'corretto11jdk' -FailureMessage 'choco install corretto11jdk failed'
    $corretto = (Get-ChildItem 'C:\Program Files\Amazon Corretto' -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName
    if (-not $corretto) {
        throw 'Corretto 11 not found after choco install (corretto11jdk).'
    }
    [Environment]::SetEnvironmentVariable('JAVA_HOME', $corretto, 'Machine')
    [Environment]::SetEnvironmentVariable('JDK11_HOME', $corretto, 'Machine')
    & (Join-Path $corretto 'bin\java.exe') -version

    Write-BootstrapStep 'Install Maven'
    Install-ChocoPackage -Package 'maven' -FailureMessage 'choco install maven failed'
    $mavenHome = Get-ChildItem 'C:\ProgramData\chocolatey\lib\maven\tools' -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($mavenHome) {
        [Environment]::SetEnvironmentVariable('MAVEN_HOME', $mavenHome.FullName, 'Machine')
    }
    Update-SessionPath
    mvn --version

    Write-BootstrapStep 'Install .NET 8 SDK'
    Install-ChocoPackage -Package 'dotnet-8.0-sdk' -FailureMessage 'choco install dotnet-8.0-sdk failed'
    [Environment]::SetEnvironmentVariable('DOTNET_ROOT', 'C:\Program Files\dotnet', 'Machine')
    $machinePath = [Environment]::GetEnvironmentVariable('Path', 'Machine')
    [Environment]::SetEnvironmentVariable(
        'Path',
        "$machinePath;C:\Program Files\dotnet;C:\Users\ContainerAdministrator\.dotnet\tools",
        'Machine'
    )
    Update-SessionPath
    & 'C:\Program Files\dotnet\dotnet.exe' --version

    Write-BootstrapStep 'Install WiX and AzureSignTool dotnet tools'
    & 'C:\Program Files\dotnet\dotnet.exe' tool install --global wix
    if ($LASTEXITCODE -ne 0) { throw 'dotnet tool install wix failed' }
    & 'C:\Program Files\dotnet\dotnet.exe' tool install --global AzureSignTool
    if ($LASTEXITCODE -ne 0) { throw 'dotnet tool install AzureSignTool failed' }
    & 'C:\Users\ContainerAdministrator\.dotnet\tools\wix.exe' --version
    & 'C:\Program Files\dotnet\dotnet.exe' tool list --global
}

if ($VerifyOnly) {
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor 3072
    $tempRoot = if ($env:TEMP) { $env:TEMP } else { '/tmp' }
    $tempDir = Join-Path $tempRoot 'choco-bootstrap'
    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
    $installScript = Join-Path $tempDir 'install.ps1'
    $nupkgPath = Join-Path $tempDir "chocolatey.$ChocolateyVersion.nupkg"
    Write-BootstrapStep 'VerifyOnly: Chocolatey bootstrap downloads'
    Write-BuildNetworkDiagnostics -Phase 'before VerifyOnly downloads'
    Invoke-DownloadWithRetry -Url 'https://community.chocolatey.org/install.ps1' -OutFile $installScript -MinBytes 10000
    Invoke-DownloadWithRetry -Url "https://packages.chocolatey.org/chocolatey.$ChocolateyVersion.nupkg" -OutFile $nupkgPath -MinBytes 100000
    if (-not (Test-ZipFile -Path $nupkgPath)) {
        throw "Downloaded nupkg is not a valid zip archive: $nupkgPath"
    }
    Write-BootstrapStep 'VerifyOnly: all Chocolatey bootstrap downloads OK'
    Write-Host "  install.ps1: $installScript"
    Write-Host "  nupkg:       $nupkgPath"
    exit 0
}

if ($InstallTools) {
    Install-SignImageToolchain
    exit 0
}

Install-ChocolateyBootstrap
