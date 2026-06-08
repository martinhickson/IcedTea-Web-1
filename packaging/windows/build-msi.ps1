param(
    [string]$DockerBin = $(if ($env:DOCKER_BIN) { $env:DOCKER_BIN } else { "docker" }),
    [string]$ImageName = $(if ($env:ITW_WINDOWS_PACKAGE_IMAGE) { $env:ITW_WINDOWS_PACKAGE_IMAGE } else { "icedtea-web-msi:ltsc2022" })
)

$ErrorActionPreference = "Stop"

$RootDir = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$OutputDir = if ($env:ITW_NATIVE_OUTPUT_DIR) {
    $env:ITW_NATIVE_OUTPUT_DIR
} else {
    Join-Path $RootDir "icedtea-web-distribution\target\native-packages"
}

if (-not (Get-Command $DockerBin -ErrorAction SilentlyContinue)) {
    throw "Docker-compatible CLI not found: $DockerBin"
}

New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null

& $DockerBin build `
    --file (Join-Path $RootDir "packaging\windows\docker\msi.Dockerfile") `
    --tag $ImageName `
    (Join-Path $RootDir "packaging\windows\docker")
if ($LASTEXITCODE -ne 0) {
    throw "Windows MSI Docker image build failed."
}

& $DockerBin run --rm `
    --volume "${RootDir}:C:\workspace" `
    --workdir "C:\workspace" `
    --env "ITW_VERSION=$env:ITW_VERSION" `
    --env "ITW_DIST_DIR=$env:ITW_DIST_DIR" `
    --env "ITW_NATIVE_OUTPUT_DIR=$env:ITW_NATIVE_OUTPUT_DIR" `
    --env "ITW_PACKAGE_NAME=$env:ITW_PACKAGE_NAME" `
    --env "ITW_PACKAGE_MANUFACTURER=$env:ITW_PACKAGE_MANUFACTURER" `
    $ImageName `
    "C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "C:\workspace\packaging\windows\container-build-msi.ps1"
if ($LASTEXITCODE -ne 0) {
    throw "Windows MSI Docker build failed."
}
