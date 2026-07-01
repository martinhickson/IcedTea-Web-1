# Container entrypoint for the Jenkins sign image (Maven -> MSI -> sign).
$ErrorActionPreference = 'Stop'

function Get-ProjectVersion {
    param([string]$Root)
    $pomPath = Join-Path $Root 'pom.xml'
    if (-not (Test-Path -LiteralPath $pomPath)) {
        throw "Root pom.xml not found at $pomPath"
    }
    $match = Select-String -LiteralPath $pomPath -Pattern '<version>([^<]+)</version>' | Select-Object -First 1
    if ($null -eq $match) {
        throw "Could not read project version from $pomPath"
    }
    return $match.Matches.Groups[1].Value
}

function Test-IsDryRun {
    param([string]$Value = $env:ITW_DRY_RUN)
    if ([string]::IsNullOrWhiteSpace($Value)) { return $false }
    switch ($Value.Trim().ToLowerInvariant()) {
        '1' { return $true }
        'true' { return $true }
        'yes' { return $true }
        'on' { return $true }
        default { return $false }
    }
}

$root = $env:ITW_WORKSPACE_ROOT
if ([string]::IsNullOrWhiteSpace($root)) {
    throw 'ITW_WORKSPACE_ROOT is not set (Jenkins must checkout source into WORKSPACE before compose).'
}
if (-not (Test-Path -LiteralPath $root)) {
    throw "Workspace root not found: $root"
}

Set-Location $root

$dryRun = Test-IsDryRun
$version = if ([string]::IsNullOrWhiteSpace($env:ITW_VERSION)) {
    Get-ProjectVersion -Root $root
} else {
    $env:ITW_VERSION
}

$selfContained = if ([string]::IsNullOrWhiteSpace($env:ITW_DOTNET_SELF_CONTAINED)) {
    'true'
} else {
    $env:ITW_DOTNET_SELF_CONTAINED
}
$correttoUrl = if ([string]::IsNullOrWhiteSpace($env:ITW_CORRETTO_URL)) {
    'https://corretto.aws/downloads/latest/amazon-corretto-11-x64-windows-jdk.zip'
} else {
    $env:ITW_CORRETTO_URL
}

$env:ITW_VERSION = $version
$env:ITW_WORKSPACE = $root
if (-not [string]::IsNullOrWhiteSpace($env:JDK11_HOME)) {
    $env:JDK11_HOME = $env:JDK11_HOME.Trim()
} elseif (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    $env:JDK11_HOME = $env:JAVA_HOME.Trim()
} else {
    $corretto = Get-ChildItem 'C:\Program Files\Amazon Corretto' -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if ($null -eq $corretto) {
        throw 'JDK 11 home not found. The sign image should install corretto11jdk via Chocolatey.'
    }
    $env:JDK11_HOME = $corretto.FullName
}
$env:JAVA_HOME = $env:JDK11_HOME
$env:DOTNET_ROOT = if ($env:DOTNET_ROOT) { $env:DOTNET_ROOT } else { 'C:\Program Files\dotnet' }

Write-Host '=== IcedTea-Web Windows release build (Maven -> MSI -> sign) ==='
Write-Host "Workspace: $root"
Write-Host "Version:   $version"
Write-Host "JDK 11:    $($env:JDK11_HOME)"
Write-Host "Self-contained .NET: $selfContained"
if ($dryRun) {
    Write-Host 'Dry run mode: full build; signing step runs AzureSignTool --version only'
}

Write-Host "`n=== Step 1/3: Maven distribution build (win-x64) ==="
$mvnArgs = @(
    '-P', 'maven-distribution',
    '-pl', 'icedtea-web-distribution',
    '-am',
    'install',
    '-Dmaven.test.skip=true',
    '-DskipTests',
    "-Djdk11.home=$($env:JDK11_HOME)",
    "-Ditw.dotnet.selfContained=$selfContained",
    '-Ditw.dotnet.runtime.identifier=win-x64',
    "-Ditw.corretto.url=$correttoUrl"
)
& mvn @mvnArgs
if ($LASTEXITCODE -ne 0) {
    throw "Maven distribution build failed with exit code $LASTEXITCODE."
}

$distZip = Get-ChildItem (Join-Path $root 'icedtea-web-distribution\target\*.zip') |
    Where-Object { $_.Name -like '*-win-x64.zip' } |
    Select-Object -First 1
if ($null -eq $distZip) {
    throw 'Expected Windows distribution ZIP was not produced under icedtea-web-distribution\target.'
}
Write-Host "Built distribution ZIP: $($distZip.FullName)"

Write-Host "`n=== Step 2/3: WiX MSI build ==="
$msiScript = Join-Path $root '.packaging\workflows\windows\container-build-msi.ps1'
if (-not (Test-Path -LiteralPath $msiScript)) {
    throw "MSI build script not found at $msiScript"
}
& $msiScript
if ($LASTEXITCODE -ne 0) {
    throw "WiX MSI build failed with exit code $LASTEXITCODE."
}

$msiFiles = @(Get-ChildItem (Join-Path $root 'icedtea-web-distribution\target\native-packages\*.msi') -ErrorAction SilentlyContinue)
if ($msiFiles.Count -eq 0) {
    throw 'Expected MSI was not produced under icedtea-web-distribution\target\native-packages.'
}
Write-Host "Built MSI: $($msiFiles[0].FullName)"

Write-Host "`n=== Step 3/3: Sign EXE and MSI with Azure Key Vault ==="
$signScript = Join-Path $root '.jenkins\workflows\sign.ps1'
if (-not (Test-Path -LiteralPath $signScript)) {
    throw "Signing script not found at $signScript"
}
& $signScript
if ($LASTEXITCODE -ne 0) {
    throw "Windows artifact signing failed with exit code $LASTEXITCODE."
}

if ($dryRun) {
    Write-Host "`n=== Windows release build completed successfully (dry run; artifacts not signed) ==="
} else {
    Write-Host "`n=== Windows release build and signing completed successfully ==="
}
