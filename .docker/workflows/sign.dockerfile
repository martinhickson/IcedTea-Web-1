# syntax=docker/dockerfile:1

FROM mcr.microsoft.com/windows/servercore:ltsc2025

SHELL ["C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe", "-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command"]

ENV DOTNET_CLI_TELEMETRY_OPTOUT=1
ENV DOTNET_SKIP_FIRST_TIME_EXPERIENCE=1

RUN [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; \
    $gitInstaller = 'C:\git-installer.exe'; \
    Invoke-WebRequest -UseBasicParsing 'https://github.com/git-for-windows/git/releases/download/v2.47.1.windows.1/Git-2.47.1-64-bit.exe' -OutFile $gitInstaller; \
    Start-Process -FilePath $gitInstaller -ArgumentList '/VERYSILENT','/NORESTART','/NOCANCEL','/SP-' -Wait; \
    Remove-Item $gitInstaller -Force

RUN [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; \
    $correttoZip = 'C:\corretto.zip'; \
    Invoke-WebRequest -UseBasicParsing 'https://corretto.aws/downloads/latest/amazon-corretto-11-x64-windows-jdk.zip' -OutFile $correttoZip; \
    Expand-Archive -LiteralPath $correttoZip -DestinationPath 'C:\build-tools\tmp' -Force; \
    $jdkDir = Get-ChildItem 'C:\build-tools\tmp' -Directory | Select-Object -First 1; \
    Move-Item -LiteralPath $jdkDir.FullName -Destination 'C:\build-tools\jdk11'; \
    Remove-Item $correttoZip, 'C:\build-tools\tmp' -Recurse -Force

RUN [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; \
    $mavenZip = 'C:\maven.zip'; \
    Invoke-WebRequest -UseBasicParsing 'https://dlcdn.apache.org/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.zip' -OutFile $mavenZip; \
    Expand-Archive -LiteralPath $mavenZip -DestinationPath 'C:\build-tools' -Force; \
    Remove-Item $mavenZip -Force

RUN [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; \
    Invoke-WebRequest -UseBasicParsing https://dot.net/v1/dotnet-install.ps1 -OutFile C:\dotnet-install.ps1; \
    & C:\dotnet-install.ps1 -Channel 8.0 -InstallDir C:\dotnet; \
    Remove-Item C:\dotnet-install.ps1 -Force

ENV DOTNET_ROOT=C:\dotnet
ENV JDK11_HOME=C:\build-tools\jdk11
ENV JAVA_HOME=C:\build-tools\jdk11
ENV MAVEN_HOME=C:\build-tools\apache-maven-3.9.9
ENV PATH=C:\build-tools\jdk11\bin;C:\build-tools\apache-maven-3.9.9\bin;C:\Program Files\Git\bin;C:\Program Files\Git\usr\bin;C:\dotnet;C:\Users\ContainerAdministrator\.dotnet\tools;C:\Windows\System32;C:\Windows;C:\Windows\System32\WindowsPowerShell\v1.0

RUN ["C:\\dotnet\\dotnet.exe", "tool", "install", "--global", "wix"]
RUN ["C:\\dotnet\\dotnet.exe", "tool", "install", "--global", "AzureSignTool"]
RUN ["C:\\Users\\ContainerAdministrator\\.dotnet\\tools\\wix.exe", "--version"]
RUN ["C:\\dotnet\\dotnet.exe", "tool", "list", "--global"]

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
    throw "ITW_WORKSPACE_ROOT is not set (sign.ps1 must set ITW_REPO_ROOT from the git clone)."
}
if (-not (Test-Path -LiteralPath $root)) {
    throw "Workspace root not found: $root"
}

Set-Location $root

$version = if ([string]::IsNullOrWhiteSpace($env:ITW_VERSION)) {
    Get-ProjectVersion -Root $root
} else {
    $env:ITW_VERSION
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
$env:JDK11_HOME = if ($env:JDK11_HOME) { $env:JDK11_HOME } else { "C:\build-tools\jdk11" }
$env:JAVA_HOME = $env:JDK11_HOME
$env:DOTNET_ROOT = if ($env:DOTNET_ROOT) { $env:DOTNET_ROOT } else { "C:\dotnet" }

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
$signScript = Join-Path $root "scripts\sign-windows-azure-keyvault.ps1"
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
