param(
    [string]$DockerBin = $(if ($env:DOCKER_BIN) { $env:DOCKER_BIN } else { "docker" }),
    [string]$ImageName = $(if ($env:ITW_WINDOWS_PACKAGE_IMAGE) { $env:ITW_WINDOWS_PACKAGE_IMAGE } else { "icedtea-web-msi:ltsc2022" })
)

$ErrorActionPreference = "Stop"

$RootDir = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
$ContainerWorkspace = "C:\workspace"
$OutputDir = if ($env:ITW_NATIVE_OUTPUT_DIR) {
    $env:ITW_NATIVE_OUTPUT_DIR
} else {
    Join-Path $RootDir "icedtea-web-distribution\target\native-packages"
}

function Convert-ToContainerPath {
    param(
        [string]$Path,
        [string]$HostRoot,
        [string]$ContainerRoot = $ContainerWorkspace
    )

    if ([string]::IsNullOrWhiteSpace($Path)) {
        return $null
    }

    $normalizedHostRoot = [System.IO.Path]::GetFullPath($HostRoot)
    if (-not $normalizedHostRoot.EndsWith([System.IO.Path]::DirectorySeparatorChar)) {
        $normalizedHostRoot += [System.IO.Path]::DirectorySeparatorChar
    }

    try {
        $normalizedPath = [System.IO.Path]::GetFullPath($Path)
    } catch {
        return $null
    }

    if ($normalizedPath.StartsWith($normalizedHostRoot, [StringComparison]::OrdinalIgnoreCase)) {
        $relative = $normalizedPath.Substring($normalizedHostRoot.Length)
        return Join-Path $ContainerRoot $relative
    }

    return $Path
}

$ContainerDistDir = Convert-ToContainerPath -Path $env:ITW_DIST_DIR -HostRoot $RootDir
$ContainerOutputDir = Convert-ToContainerPath -Path $env:ITW_NATIVE_OUTPUT_DIR -HostRoot $RootDir

if (-not (Get-Command $DockerBin -ErrorAction SilentlyContinue)) {
    throw "Docker-compatible CLI not found: $DockerBin"
}

New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null

& $DockerBin build `
    --file (Join-Path $RootDir ".packaging\workflows\windows\docker\msi.Dockerfile") `
    --tag $ImageName `
    (Join-Path $RootDir ".packaging\workflows\windows\docker")
if ($LASTEXITCODE -ne 0) {
    throw "Windows MSI Docker image build failed."
}

$dockerRunArgs = @(
    "run", "--rm",
    "--volume", "${RootDir}:${ContainerWorkspace}",
    "--workdir", $ContainerWorkspace,
    "--env", "ITW_VERSION=$env:ITW_VERSION",
    "--env", "ITW_REQUIRE_SIGNED_DIST=$env:ITW_REQUIRE_SIGNED_DIST",
    "--env", "ITW_SKIP_CRITICAL_RELEASE_GATES=$env:ITW_SKIP_CRITICAL_RELEASE_GATES",
    "--env", "ITW_PACKAGE_NAME=$env:ITW_PACKAGE_NAME",
    "--env", "ITW_PACKAGE_MANUFACTURER=$env:ITW_PACKAGE_MANUFACTURER",
    "--env", "ITW_VENDOR_DIR_NAME=$env:ITW_VENDOR_DIR_NAME",
    "--env", "ITW_INSTALL_DIR_NAME=$env:ITW_INSTALL_DIR_NAME",
    "--env", "DOTNET_ROOT=C:\dotnet"
)
if ($ContainerDistDir) {
    $dockerRunArgs += @("--env", "ITW_DIST_DIR=$ContainerDistDir")
}
if ($ContainerOutputDir) {
    $dockerRunArgs += @("--env", "ITW_NATIVE_OUTPUT_DIR=$ContainerOutputDir")
}
$dockerRunArgs += @(
    $ImageName,
    "C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe",
    "-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass",
    "-File", "$ContainerWorkspace\.packaging\workflows\windows\container-build-msi.ps1"
)

& $DockerBin @dockerRunArgs
if ($LASTEXITCODE -ne 0) {
    throw "Windows MSI Docker build failed."
}
