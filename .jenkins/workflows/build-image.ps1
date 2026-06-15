# Windows sign image build helpers (Chocolatey bootstrap).
# Preflight on the host before docker build:
#   pwsh -File .jenkins/workflows/build-image.ps1 -VerifyOnly
param(
    [string]$ChocolateyVersion = '2.7.3',
    [int]$MaxRetries = 5,
    [switch]$VerifyOnly
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor 3072

$installUrl = 'https://community.chocolatey.org/install.ps1'
$nupkgUrls = @(
    "https://packages.chocolatey.org/chocolatey.$ChocolateyVersion.nupkg",
    "https://community.chocolatey.org/api/v2/package/chocolatey/$ChocolateyVersion"
)
$tempRoot = if ($IsWindows -or $env:OS -eq 'Windows_NT') { 'C:\Temp' } else { if ($env:TEMP) { $env:TEMP } else { '/tmp' } }
$tempDir = Join-Path $tempRoot 'choco-bootstrap'

function Write-BootstrapStep {
    param([Parameter(Mandatory = $true)][string]$Message)
    Write-Host ""
    Write-Host "=== $Message ==="
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
            Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $OutFile
            $length = (Get-Item -LiteralPath $OutFile).Length
            if ($length -lt $MinBytes) {
                throw "Download too small ($length bytes, expected at least $MinBytes)."
            }
            Write-Host "  OK ($length bytes)"
            return
        }
        catch {
            if ($attempt -eq $MaxRetries) {
                throw "Download failed after $MaxRetries attempts: $Url`n$_"
            }
            Write-Warning "  Attempt $attempt failed: $_"
            Start-Sleep -Seconds (5 * $attempt)
        }
    }
}

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

if ($VerifyOnly) {
    Write-BootstrapStep "VerifyOnly: all Chocolatey bootstrap downloads OK"
    Write-Host "  install.ps1: $installScript"
    Write-Host "  nupkg:       $nupkgPath"
    exit 0
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
    throw "choco.exe not found after bootstrap (checked `$env:ChocolateyInstall\bin and C:\ProgramData\chocolatey\bin)."
}

$installedVersion = & $choco --version
Write-BootstrapStep "Chocolatey bootstrap complete: choco $installedVersion"
