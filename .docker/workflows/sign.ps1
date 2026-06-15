param(
    [string]$EnvFile = (Join-Path $PSScriptRoot "sign.env"),
    [switch]$Container
)

$ErrorActionPreference = "Stop"

function Write-Step {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Message
    )

    Write-Host ""
    Write-Host "=== $Message ==="
}

function Write-Detail {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Message
    )

    Write-Host "  $Message"
}

function Write-Failure {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Message
    )

    Write-Host ""
    Write-Host "ERROR: $Message" -ForegroundColor Red
}

function Write-NextStep {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Message
    )

    Write-Host "  NEXT STEP: $Message" -ForegroundColor Yellow
}

function Stop-SignWorkflow {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Message,
        [string[]]$NextSteps = @()
    )

    Write-Failure $Message
    foreach ($step in $NextSteps) {
        Write-NextStep $step
    }
    exit 1
}

function Invoke-CheckedCommand {
    param(
        [Parameter(Mandatory = $true)]
        [string]$StepName,
        [Parameter(Mandatory = $true)]
        [scriptblock]$Command
    )

    Write-Step $StepName
    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "$StepName failed with exit code $LASTEXITCODE."
    }
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
        Write-Detail "Git authentication: using token from sign.env"
    }

    if (Test-Path -LiteralPath $cloneDir) {
        Write-Detail "Removing existing clone directory: $cloneDir"
        Remove-Item -LiteralPath $cloneDir -Recurse -Force
    }
    $parentDir = Split-Path -Parent $cloneDir
    if (-not [string]::IsNullOrWhiteSpace($parentDir)) {
        New-Item -ItemType Directory -Force -Path $parentDir | Out-Null
    }

    Write-Detail "Remote:  $remoteUrl"
    Write-Detail "Ref:     $gitRef"
    Write-Detail "Clone:   $cloneDir"
    Write-Detail "Running: git clone --depth 1 --single-branch --branch $gitRef <remote> $cloneDir"

    & git clone --depth 1 --single-branch --branch $gitRef $cloneUrl $cloneDir
    if ($LASTEXITCODE -ne 0) {
        throw "git clone failed with exit code $LASTEXITCODE."
    }

    $resolved = (Resolve-Path -LiteralPath $cloneDir).Path
    Write-Detail "Checkout ready: $resolved"
    return $resolved
}

function Run-ContainerSignWorkflow {
    Write-Step "IcedTea-Web Windows release build (container)"
    Write-Detail "Pipeline: Maven distribution (win-x64) -> WiX MSI -> Azure Key Vault signing"

    $root = $env:ITW_WORKSPACE_ROOT
    if ([string]::IsNullOrWhiteSpace($root)) {
        throw "ITW_WORKSPACE_ROOT is not set. The host workflow must bind-mount a git checkout into the container."
    }
    if (-not (Test-Path -LiteralPath $root)) {
        throw "Workspace root not found: $root"
    }
    if (-not (Test-Path -LiteralPath (Join-Path $root "pom.xml"))) {
        throw "Workspace is missing pom.xml: $root"
    }

    Set-Location $root

    $version = if ([string]::IsNullOrWhiteSpace($env:ITW_VERSION)) {
        Get-ProjectVersion -Root $root
    } else {
        $env:ITW_VERSION.Trim()
    }
    $selfContained = if ([string]::IsNullOrWhiteSpace($env:ITW_DOTNET_SELF_CONTAINED)) {
        "true"
    } else {
        $env:ITW_DOTNET_SELF_CONTAINED.Trim()
    }
    $correttoUrl = if ([string]::IsNullOrWhiteSpace($env:ITW_CORRETTO_URL)) {
        "https://corretto.aws/downloads/latest/amazon-corretto-11-x64-windows-jdk.zip"
    } else {
        $env:ITW_CORRETTO_URL.Trim()
    }

    $env:ITW_VERSION = $version
    $env:ITW_WORKSPACE = $root
    $env:JDK11_HOME = if ($env:JDK11_HOME) { $env:JDK11_HOME } else { "C:\build-tools\jdk11" }
    $env:JAVA_HOME = $env:JDK11_HOME
    $env:DOTNET_ROOT = if ($env:DOTNET_ROOT) { $env:DOTNET_ROOT } else { "C:\dotnet" }

    Write-Detail "Workspace:           $root"
    Write-Detail "Version:             $version"
    Write-Detail "JDK 11:              $($env:JDK11_HOME)"
    Write-Detail "Self-contained .NET: $selfContained"
    Write-Detail "Corretto URL:        $correttoUrl"
    Write-Detail "Key Vault:           $($env:KEYVAULT_URL)"
    Write-Detail "Sign alias:          $($env:JNLP_JCA_SIGN_ALIAS)"
    Write-Detail "TSA URL:             $($env:JNLP_JCA_TSA_URL)"
    Write-Detail "Cert chain file:     $($env:JNLP_JCA_SIGN_CERTCHAIN_FILE)"

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

    Invoke-CheckedCommand -StepName "Step 1/3: Maven distribution build (win-x64)" -Command {
        Write-Detail "Running: mvn $($mvnArgs -join ' ')"
        & mvn @mvnArgs
    }

    $distZip = Get-ChildItem (Join-Path $root "icedtea-web-distribution\target\*.zip") |
        Where-Object { $_.Name -like "*-win-x64.zip" } |
        Select-Object -First 1
    if ($null -eq $distZip) {
        throw "Expected Windows distribution ZIP was not produced under icedtea-web-distribution\target."
    }
    Write-Detail "Built distribution ZIP: $($distZip.FullName)"

    $msiScript = Join-Path $root ".packaging\workflows\windows\container-build-msi.ps1"
    if (-not (Test-Path -LiteralPath $msiScript)) {
        throw "MSI build script not found at $msiScript"
    }

    Invoke-CheckedCommand -StepName "Step 2/3: WiX MSI build" -Command {
        Write-Detail "Running: $msiScript"
        & $msiScript
    }

    $msiFiles = @(Get-ChildItem (Join-Path $root "icedtea-web-distribution\target\native-packages\*.msi") -ErrorAction SilentlyContinue)
    if ($msiFiles.Count -eq 0) {
        throw "Expected MSI was not produced under icedtea-web-distribution\target\native-packages."
    }
    Write-Detail "Built MSI: $($msiFiles[0].FullName)"

    $signScript = Join-Path $root "scripts\sign-windows-azure-keyvault.ps1"
    if (-not (Test-Path -LiteralPath $signScript)) {
        throw "Signing script not found at $signScript"
    }

    Invoke-CheckedCommand -StepName "Step 3/3: Sign EXE and MSI with Azure Key Vault" -Command {
        Write-Detail "Running: $signScript"
        & $signScript
    }

    Write-Step "Windows release build and signing completed successfully"
    Write-Detail "Distribution ZIP: $($distZip.FullName)"
    Write-Detail "Signed MSI:       $($msiFiles[0].FullName)"
}

function Run-HostSignWorkflow {
    param(
        [string]$EnvFilePath = (Join-Path $PSScriptRoot "sign.env")
    )

    $WorkflowDir = $PSScriptRoot
    $ComposeFile = Join-Path $WorkflowDir "sign.compose"
    $DockerBin = if ($env:DOCKER_BIN) { $env:DOCKER_BIN } else { "docker" }

    Write-Step "IcedTea-Web Windows release build and sign (Docker workflow)"
    Write-Detail "Host script:   $PSCommandPath"
    Write-Detail "Env file:      $EnvFilePath"
    Write-Detail "Compose file:  $ComposeFile"
    Write-Detail "Docker CLI:    $DockerBin"
    Write-Detail "Requires:      Windows Docker host (Windows containers mode)"
    Write-Detail "Container pipeline:"
    Write-Detail "  1. Maven distribution build for win-x64"
    Write-Detail "  2. WiX MSI packaging"
    Write-Detail "  3. Azure Key Vault code signing"

    Write-Step "Step 1/5: Validate host prerequisites"
    if (-not (Test-Path -LiteralPath $EnvFilePath)) {
        Stop-SignWorkflow `
            -Message "Missing env file: $EnvFilePath" `
            -NextSteps @(
                "Copy sign.env.example values into sign.env (or edit the existing sign.env)."
                "Fill in Azure Key Vault URL, service principal credentials, and signing alias."
                "Set JNLP_JCA_SIGN_CERTCHAIN_HOST_FILE to your PEM certificate chain file."
                "Re-run: pwsh -File $PSCommandPath"
            )
    }
    Write-Detail "Found env file: $EnvFilePath"

    if (-not (Test-Path -LiteralPath $ComposeFile)) {
        Stop-SignWorkflow -Message "Sign compose file not found: $ComposeFile"
    }
    Write-Detail "Found compose file: $ComposeFile"

    if (-not (Get-Command $DockerBin -ErrorAction SilentlyContinue)) {
        Stop-SignWorkflow `
            -Message "Docker-compatible CLI not found: $DockerBin" `
            -NextSteps @(
                "Install Docker and ensure the docker command is on PATH."
                "This workflow requires a Windows machine running Docker in Windows containers mode."
            )
    }
    Write-Detail "Docker CLI available: $DockerBin"

    if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
        Stop-SignWorkflow `
            -Message "git is required on the host to clone source before running Docker compose." `
            -NextSteps @("Install git and ensure the git command is on PATH.")
    }
    Write-Detail "git available: $(Get-Command git | Select-Object -ExpandProperty Source)"

    Write-Step "Step 2/5: Load and validate sign.env"
    $envLines = Get-Content -LiteralPath $EnvFilePath | Where-Object {
        $_ -notmatch '^\s*(#|$)' -and $_ -match '='
    }
    $envMap = @{}
    foreach ($line in $envLines) {
        $parts = $line -split '=', 2
        $envMap[$parts[0].Trim()] = $parts[1].Trim()
    }

    $required = @(
        'KEYVAULT_URL',
        'AZURE_CLIENT_ID',
        'AZURE_TENANT_ID',
        'AZURE_CLIENT_SECRET',
        'JNLP_JCA_SIGN_ALIAS',
        'JNLP_JCA_TSA_URL'
    )
    $missing = @($required | Where-Object { [string]::IsNullOrWhiteSpace($envMap[$_]) })

    $inlineChain = $envMap['JNLP_JCA_SIGN_CERTCHAIN']
    $chainHostFile = $envMap['JNLP_JCA_SIGN_CERTCHAIN_HOST_FILE']
    $chainProblems = @()
    if ([string]::IsNullOrWhiteSpace($inlineChain)) {
        if ([string]::IsNullOrWhiteSpace($chainHostFile)) {
            $missing += 'JNLP_JCA_SIGN_CERTCHAIN_HOST_FILE'
        } elseif (-not (Test-Path -LiteralPath $chainHostFile)) {
            $chainProblems += "JNLP_JCA_SIGN_CERTCHAIN_HOST_FILE points to a missing file: $chainHostFile"
        }
    }

    if ($missing.Count -gt 0 -or $chainProblems.Count -gt 0) {
        $nextSteps = @(
            "Edit $EnvFilePath and set every required signing value."
        )
        foreach ($name in $missing) {
            $nextSteps += "Set $name in sign.env."
        }
        if ($chainProblems.Count -gt 0) {
            $nextSteps += "Update JNLP_JCA_SIGN_CERTCHAIN_HOST_FILE to the real PEM chain path on this machine."
            $nextSteps += "Alternatively set JNLP_JCA_SIGN_CERTCHAIN to the PEM contents inline."
        }
        $nextSteps += "Re-run: pwsh -File $PSCommandPath"

        $messages = @()
        if ($missing.Count -gt 0) {
            $messages += "missing or empty values: $($missing -join ', ')"
        }
        if ($chainProblems.Count -gt 0) {
            $messages += ($chainProblems -join '; ')
        }
        Stop-SignWorkflow `
            -Message "sign.env is not ready ($($messages -join '; '))" `
            -NextSteps $nextSteps
    }

    if ([string]::IsNullOrWhiteSpace($inlineChain)) {
        $env:JNLP_JCA_SIGN_CERTCHAIN_HOST_FILE = $chainHostFile
        Write-Detail "Certificate chain source: host file $chainHostFile"
    } else {
        $tempChainDir = Join-Path $WorkflowDir ".signing-temp"
        New-Item -ItemType Directory -Force -Path $tempChainDir | Out-Null
        $tempChain = Join-Path $tempChainDir "certchain.pem"
        Set-Content -LiteralPath $tempChain -Value $inlineChain -Encoding utf8
        $env:JNLP_JCA_SIGN_CERTCHAIN_HOST_FILE = $tempChain
        Write-Detail "Certificate chain source: inline PEM written to $tempChain"
    }

    foreach ($name in $required) {
        Set-Item -Path "Env:$name" -Value $envMap[$name]
    }

    $gitRef = if ($envMap['GIT_REF']) { $envMap['GIT_REF'].Trim() } else { '1.8' }
    $repository = if ($envMap['GITHUB_REPOSITORY']) { $envMap['GITHUB_REPOSITORY'].Trim() } else { 'martinhickson/IcedTea-Web-1' }
    $gitRemote = Get-GitCloneUrl -Config $envMap
    $dotnetSelfContained = if ($envMap['ITW_DOTNET_SELF_CONTAINED']) { $envMap['ITW_DOTNET_SELF_CONTAINED'].Trim() } else { 'true' }
    $itwVersion = if ($envMap['ITW_VERSION']) { $envMap['ITW_VERSION'].Trim() } else { '(from pom.xml in container)' }

    Write-Detail "Git remote:            $gitRemote"
    Write-Detail "Git ref:               $gitRef"
    Write-Detail "GitHub repository:     $repository"
    Write-Detail "ITW version:           $itwVersion"
    Write-Detail "Self-contained .NET:   $dotnetSelfContained"
    Write-Detail "Key Vault URL:         $($envMap['KEYVAULT_URL'])"
    Write-Detail "Azure client ID:       $($envMap['AZURE_CLIENT_ID'])"
    Write-Detail "Azure tenant ID:       $($envMap['AZURE_TENANT_ID'])"
    Write-Detail "Azure client secret:   [set, redacted]"
    Write-Detail "Sign alias:            $($envMap['JNLP_JCA_SIGN_ALIAS'])"
    Write-Detail "TSA URL:               $($envMap['JNLP_JCA_TSA_URL'])"
    Write-Detail "Container cert path:   $(if ($envMap['JNLP_JCA_SIGN_CERTCHAIN_FILE']) { $envMap['JNLP_JCA_SIGN_CERTCHAIN_FILE'] } else { 'C:\signing\certchain.pem' })"

    Write-Step "Step 3/5: Clone fresh git source on host"
    $repoRoot = Initialize-SourceCheckout -WorkflowDirectory $WorkflowDir -Config $envMap

    Write-Step "Step 4/5: Export build environment for Docker Compose"
    $env:ITW_REPO_ROOT = $repoRoot
    $env:ITW_WORKSPACE_ROOT = $repoRoot
    if ([string]::IsNullOrWhiteSpace($env:JNLP_JCA_SIGN_CERTCHAIN_FILE)) {
        $env:JNLP_JCA_SIGN_CERTCHAIN_FILE = 'C:\signing\certchain.pem'
    }
    $env:GITHUB_REPOSITORY = $repository
    if (-not [string]::IsNullOrWhiteSpace($envMap['ITW_VERSION'])) {
        $env:ITW_VERSION = $envMap['ITW_VERSION']
    }
    if (-not [string]::IsNullOrWhiteSpace($envMap['ITW_DOTNET_SELF_CONTAINED'])) {
        $env:ITW_DOTNET_SELF_CONTAINED = $envMap['ITW_DOTNET_SELF_CONTAINED']
    }

    Write-Detail "ITW_REPO_ROOT:                     $repoRoot"
    Write-Detail "ITW_WORKSPACE_ROOT:                $repoRoot"
    Write-Detail "JNLP_JCA_SIGN_CERTCHAIN_HOST_FILE: $($env:JNLP_JCA_SIGN_CERTCHAIN_HOST_FILE)"
    Write-Detail "JNLP_JCA_SIGN_CERTCHAIN_FILE:      $($env:JNLP_JCA_SIGN_CERTCHAIN_FILE)"

    Write-Step "Step 5/5: Build Windows signing container and run release pipeline"
    Write-Detail "Running: $DockerBin compose -f $ComposeFile --project-directory $WorkflowDir up --build --abort-on-container-exit --remove-orphans --progress plain"
    Write-Detail "Watch for container steps: Maven build -> WiX MSI -> Azure Key Vault signing"

    Invoke-CheckedCommand -StepName "Docker Compose sign workflow" -Command {
        & $DockerBin compose `
            -f $ComposeFile `
            --project-directory $WorkflowDir `
            up --build --abort-on-container-exit --remove-orphans --progress plain
    }

    Write-Step "Docker workflow completed successfully"
    Write-Detail "Signed artifacts should be under:"
    Write-Detail "  $repoRoot\icedtea-web-distribution\target\"
    Write-Detail "  $repoRoot\icedtea-web-distribution\target\native-packages\"
}

if ($Container) {
    Run-ContainerSignWorkflow
} else {
    try {
        Run-HostSignWorkflow -EnvFilePath $EnvFile
    } catch {
        Write-Failure $_.Exception.Message
        if ($_.ScriptStackTrace) {
            Write-Host $_.ScriptStackTrace -ForegroundColor DarkRed
        }
        exit 1
    }
}
