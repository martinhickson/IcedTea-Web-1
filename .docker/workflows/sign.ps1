param(
    [string]$EnvFile = (Join-Path $PSScriptRoot "sign.env")
)

$ErrorActionPreference = "Stop"

$WorkflowDir = $PSScriptRoot
$ComposeFile = Join-Path $WorkflowDir "sign.compose"
$DockerBin = if ($env:DOCKER_BIN) { $env:DOCKER_BIN } else { "docker" }

if (-not (Test-Path -LiteralPath $EnvFile)) {
    throw "Missing $EnvFile. Create or fill in sign.env with signing values."
}

if (-not (Test-Path -LiteralPath $ComposeFile)) {
    throw "Sign compose file not found: $ComposeFile"
}

if (-not (Get-Command $DockerBin -ErrorAction SilentlyContinue)) {
    throw "Docker-compatible CLI not found: $DockerBin"
}

if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    throw "git is required on the host to clone source before running Docker compose."
}

$envLines = Get-Content -LiteralPath $EnvFile | Where-Object {
    $_ -notmatch '^\s*(#|$)' -and $_ -match '='
}
$envMap = @{}
foreach ($line in $envLines) {
    $parts = $line -split '=', 2
    $envMap[$parts[0].Trim()] = $parts[1].Trim()
}

function Get-GitCloneUrl {
    param(
        [hashtable]$Config
    )

    if (-not [string]::IsNullOrWhiteSpace($Config['GIT_REMOTE_URL'])) {
        return $Config['GIT_REMOTE_URL'].Trim()
    }

    $repository = if ($Config['GITHUB_REPOSITORY']) {
        $Config['GITHUB_REPOSITORY'].Trim()
    } else {
        'martinhickson/IcedTea-Web-1'
    }
    return "https://github.com/$repository.git"
}

function Initialize-SourceCheckout {
    param(
        [string]$WorkflowDirectory,
        [hashtable]$Config
    )

    $remoteUrl = Get-GitCloneUrl -Config $Config
    $gitRef = if ($Config['GIT_REF']) { $Config['GIT_REF'].Trim() } else { '1.8' }
    $cloneDir = if ($Config['GIT_CLONE_DIR']) {
        $Config['GIT_CLONE_DIR'].Trim()
    } else {
        Join-Path $WorkflowDirectory ".source"
    }

    $cloneUrl = $remoteUrl
    if (-not [string]::IsNullOrWhiteSpace($Config['GIT_TOKEN']) -and $cloneUrl -match '^https://') {
        $token = $Config['GIT_TOKEN'].Trim()
        $cloneUrl = $cloneUrl -replace '^https://', "https://x-access-token:${token}@"
    }

    if (Test-Path -LiteralPath $cloneDir) {
        Remove-Item -LiteralPath $cloneDir -Recurse -Force
    }
    $parentDir = Split-Path -Parent $cloneDir
    if (-not [string]::IsNullOrWhiteSpace($parentDir)) {
        New-Item -ItemType Directory -Force -Path $parentDir | Out-Null
    }

    Write-Host "Cloning fresh source from $remoteUrl (ref: $gitRef)"
    Write-Host "Clone directory: $cloneDir"

    & git clone --depth 1 --single-branch --branch $gitRef $cloneUrl $cloneDir
    if ($LASTEXITCODE -ne 0) {
        throw "git clone failed with exit code $LASTEXITCODE."
    }

    return (Resolve-Path -LiteralPath $cloneDir).Path
}

$inlineChain = $envMap['JNLP_JCA_SIGN_CERTCHAIN']
$chainHostFile = $envMap['JNLP_JCA_SIGN_CERTCHAIN_HOST_FILE']
if ([string]::IsNullOrWhiteSpace($inlineChain)) {
    if ([string]::IsNullOrWhiteSpace($chainHostFile)) {
        throw "sign.env must set JNLP_JCA_SIGN_CERTCHAIN_HOST_FILE or JNLP_JCA_SIGN_CERTCHAIN."
    }
    if (-not (Test-Path -LiteralPath $chainHostFile)) {
        throw "JNLP_JCA_SIGN_CERTCHAIN_HOST_FILE not found: $chainHostFile"
    }
    $env:JNLP_JCA_SIGN_CERTCHAIN_HOST_FILE = $chainHostFile
} else {
    $tempChainDir = Join-Path $WorkflowDir ".signing-temp"
    New-Item -ItemType Directory -Force -Path $tempChainDir | Out-Null
    $tempChain = Join-Path $tempChainDir "certchain.pem"
    Set-Content -LiteralPath $tempChain -Value $inlineChain -Encoding utf8
    $env:JNLP_JCA_SIGN_CERTCHAIN_HOST_FILE = $tempChain
}

$required = @(
    'KEYVAULT_URL',
    'AZURE_CLIENT_ID',
    'AZURE_TENANT_ID',
    'AZURE_CLIENT_SECRET',
    'JNLP_JCA_SIGN_ALIAS',
    'JNLP_JCA_TSA_URL'
)
$missing = $required | Where-Object { [string]::IsNullOrWhiteSpace($envMap[$_]) }
if ($missing.Count -gt 0) {
    throw "sign.env is missing signing values: $($missing -join ', ')"
}

foreach ($name in $required) {
    Set-Item -Path "Env:$name" -Value $envMap[$name]
}

$repoRoot = Initialize-SourceCheckout -WorkflowDirectory $WorkflowDir -Config $envMap

$env:ITW_REPO_ROOT = $repoRoot
$env:ITW_WORKSPACE_ROOT = $repoRoot
if ([string]::IsNullOrWhiteSpace($env:JNLP_JCA_SIGN_CERTCHAIN_FILE)) {
    $env:JNLP_JCA_SIGN_CERTCHAIN_FILE = 'C:\signing\certchain.pem'
}
if ([string]::IsNullOrWhiteSpace($envMap['GITHUB_REPOSITORY'])) {
    $env:GITHUB_REPOSITORY = 'martinhickson/IcedTea-Web-1'
} else {
    $env:GITHUB_REPOSITORY = $envMap['GITHUB_REPOSITORY']
}
if (-not [string]::IsNullOrWhiteSpace($envMap['ITW_VERSION'])) {
    $env:ITW_VERSION = $envMap['ITW_VERSION']
}
if (-not [string]::IsNullOrWhiteSpace($envMap['ITW_DOTNET_SELF_CONTAINED'])) {
    $env:ITW_DOTNET_SELF_CONTAINED = $envMap['ITW_DOTNET_SELF_CONTAINED']
}

Write-Host "Building and signing Windows release via Docker compose."
Write-Host "Cloned source root: $repoRoot"
Write-Host "Compose file:       $ComposeFile"

& $DockerBin compose `
    -f $ComposeFile `
    --project-directory $WorkflowDir `
    up --build --abort-on-container-exit --remove-orphans

if ($LASTEXITCODE -ne 0) {
    throw "sign compose failed with exit code $LASTEXITCODE."
}
