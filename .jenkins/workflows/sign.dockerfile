FROM mcr.microsoft.com/windows/servercore:ltsc2022

SHELL ["C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe", "-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command"]

ENV DOTNET_CLI_TELEMETRY_OPTOUT=1
ENV DOTNET_SKIP_FIRST_TIME_EXPERIENCE=1

COPY build-image.ps1 C:/scripts/build-image.ps1
RUN C:/scripts/build-image.ps1

RUN $$ErrorActionPreference = 'Stop'; \
    choco install -y --no-progress git; \
    if ($$LASTEXITCODE -ne 0) { throw 'choco install git failed' }; \
    git --version

RUN $$ErrorActionPreference = 'Stop'; \
    choco install -y --no-progress corretto11jdk; \
    if ($$LASTEXITCODE -ne 0) { throw 'choco install corretto11jdk failed' }; \
    $$corretto = (Get-ChildItem 'C:\Program Files\Amazon Corretto' -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName; \
    if (-not $$corretto) { throw 'Corretto 11 not found after choco install (corretto11jdk).' }; \
    [Environment]::SetEnvironmentVariable('JAVA_HOME', $$corretto, 'Machine'); \
    [Environment]::SetEnvironmentVariable('JDK11_HOME', $$corretto, 'Machine'); \
    & (Join-Path $$corretto 'bin\java.exe') -version

RUN $$ErrorActionPreference = 'Stop'; \
    choco install -y --no-progress maven; \
    if ($$LASTEXITCODE -ne 0) { throw 'choco install maven failed' }; \
    $$mavenHome = Get-ChildItem 'C:\ProgramData\chocolatey\lib\maven\tools' -Directory -ErrorAction SilentlyContinue | Select-Object -First 1; \
    if ($$mavenHome) { [Environment]::SetEnvironmentVariable('MAVEN_HOME', $$mavenHome.FullName, 'Machine') }; \
    mvn --version

RUN $$ErrorActionPreference = 'Stop'; \
    choco install -y --no-progress dotnet-8.0-sdk; \
    if ($$LASTEXITCODE -ne 0) { throw 'choco install dotnet-8.0-sdk failed' }; \
    [Environment]::SetEnvironmentVariable('DOTNET_ROOT', 'C:\Program Files\dotnet', 'Machine'); \
    $$machinePath = [Environment]::GetEnvironmentVariable('Path', 'Machine'); \
    [Environment]::SetEnvironmentVariable('Path', "$$machinePath;C:\Program Files\dotnet;C:\Users\ContainerAdministrator\.dotnet\tools", 'Machine'); \
    & 'C:\Program Files\dotnet\dotnet.exe' --version

ENV DOTNET_ROOT="C:\Program Files\dotnet"

RUN ["C:\\Program Files\\dotnet\\dotnet.exe", "tool", "install", "--global", "wix"]
RUN ["C:\\Program Files\\dotnet\\dotnet.exe", "tool", "install", "--global", "AzureSignTool"]
RUN ["C:\\Users\\ContainerAdministrator\\.dotnet\\tools\\wix.exe", "--version"]
RUN ["C:\\Program Files\\dotnet\\dotnet.exe", "tool", "list", "--global"]

COPY <<EOF C:/scripts/sign.ps1
$ErrorActionPreference = "Stop"

function Get-ProjectVersion {
    param([string]$Root)
    $pomPath = Join-Path $Root "pom.xml"
    if (-not (Test-Path -LiteralPath $pomPath)) {
        throw "Root pom.xml not found at $pomPath"
    }
    $match = Select-String -LiteralPath $pomPath -Pattern '<version>([^<]+)</version>' | Select-Object -First 1
    if ($null -eq $match) {
        throw "Could not read project version from $pomPath"
    }
    return $match.Matches.Groups[1].Value
}

$root = $env:ITW_WORKSPACE_ROOT
if ([string]::IsNullOrWhiteSpace($root)) {
    throw "ITW_WORKSPACE_ROOT is not set (Jenkins must checkout source into WORKSPACE before compose)."
}
if (-not (Test-Path -LiteralPath $root)) {
    throw "Workspace root not found: $root"
}

Set-Location $root

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

$version = if ([string]::IsNullOrWhiteSpace($env:ITW_VERSION)) {
    Get-ProjectVersion -Root $root
} else {
    $env:ITW_VERSION
}

if (Test-IsDryRun) {
    Write-Host "=== Dry run mode ==="
    Write-Host "Workspace: $root"
    Write-Host "Project version: $version"
    if (-not (Get-Command AzureSignTool -ErrorAction SilentlyContinue)) {
        throw "AzureSignTool is not available on PATH."
    }
    Write-Host "Running: AzureSignTool --version"
    & AzureSignTool --version
    if ($LASTEXITCODE -ne 0) {
        throw "AzureSignTool --version failed with exit code $LASTEXITCODE."
    }
    Write-Host ""
    Write-Host "Dry run: not signing in dry run mode."
    exit 0
}

$selfContained = if ([string]::IsNullOrWhiteSpace($env:ITW_DOTNET_SELF_CONTAINED)) {
    "true"
} else {
    $env:ITW_DOTNET_SELF_CONTAINED
}
$correttoUrl = if ([string]::IsNullOrWhiteSpace($env:ITW_CORRETTO_URL)) {
    "https://corretto.aws/downloads/latest/amazon-corretto-11-x64-windows-jdk.zip"
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
    $corretto = Get-ChildItem 'C:\Program Files\Amazon Corretto' -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
    if ($null -eq $corretto) {
        throw "JDK 11 home not found. The sign image should install corretto11jdk via Chocolatey."
    }
    $env:JDK11_HOME = $corretto.FullName
}
$env:JAVA_HOME = $env:JDK11_HOME
$env:DOTNET_ROOT = if ($env:DOTNET_ROOT) { $env:DOTNET_ROOT } else { "C:\Program Files\dotnet" }

Write-Host "=== IcedTea-Web Windows release build (Maven -> MSI -> sign) ==="
Write-Host "Workspace: $root"
Write-Host "Version:   $version"
Write-Host "JDK 11:    $($env:JDK11_HOME)"
Write-Host "Self-contained .NET: $selfContained"

Write-Host "`n=== Step 1/3: Maven distribution build (win-x64) ==="
$mvnArgs = @(
    "-P", "maven-distribution",
    "-pl", "icedtea-web-distribution",
    "-am",
    "install",
    "-Dmaven.test.skip=true",
    "-DskipTests",
    "-Djdk11.home=$($env:JDK11_HOME)",
    "-Ditw.dotnet.selfContained=$selfContained",
    "-Ditw.dotnet.runtime.identifier=win-x64",
    "-Ditw.corretto.url=$correttoUrl"
)
& mvn @mvnArgs
if ($LASTEXITCODE -ne 0) {
    throw "Maven distribution build failed with exit code $LASTEXITCODE."
}

$distZip = Get-ChildItem (Join-Path $root "icedtea-web-distribution\target\*.zip") |
    Where-Object { $_.Name -like "*-win-x64.zip" } |
    Select-Object -First 1
if ($null -eq $distZip) {
    throw "Expected Windows distribution ZIP was not produced under icedtea-web-distribution\target."
}
Write-Host "Built distribution ZIP: $($distZip.FullName)"

Write-Host "`n=== Step 2/3: WiX MSI build ==="
$msiScript = Join-Path $root ".packaging\workflows\windows\container-build-msi.ps1"
if (-not (Test-Path -LiteralPath $msiScript)) {
    throw "MSI build script not found at $msiScript"
}
& $msiScript
if ($LASTEXITCODE -ne 0) {
    throw "WiX MSI build failed with exit code $LASTEXITCODE."
}

$msiFiles = @(Get-ChildItem (Join-Path $root "icedtea-web-distribution\target\native-packages\*.msi") -ErrorAction SilentlyContinue)
if ($msiFiles.Count -eq 0) {
    throw "Expected MSI was not produced under icedtea-web-distribution\target\native-packages."
}
Write-Host "Built MSI: $($msiFiles[0].FullName)"

Write-Host "`n=== Step 3/3: Sign EXE and MSI with Azure Key Vault ==="
$signScript = Join-Path $root ".jenkins\workflows\sign.ps1"
if (-not (Test-Path -LiteralPath $signScript)) {
    throw "Signing script not found at $signScript"
}
& $signScript
if ($LASTEXITCODE -ne 0) {
    throw "Windows artifact signing failed with exit code $LASTEXITCODE."
}

Write-Host "`n=== Windows release build and signing completed successfully ==="
EOF

ENTRYPOINT ["C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe", "-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "C:\\scripts\\sign.ps1"]
