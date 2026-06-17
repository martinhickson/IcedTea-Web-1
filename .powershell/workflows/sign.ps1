# Windows release build and sign on the host (no Docker).
# Fill in sign.env and run:
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .powershell\workflows\sign.ps1

param(
    [string]$EnvFile = (Join-Path $PSScriptRoot 'sign.env')
)

$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'jdk.ps1')

function Write-Step {
    param([Parameter(Mandatory = $true)][string]$Message)
    Write-Host ""
    Write-Host "=== $Message ==="
}

function Write-Detail {
    param([Parameter(Mandatory = $true)][string]$Message)
    Write-Host "  $Message"
}

function Write-Failure {
    param([Parameter(Mandatory = $true)][string]$Message)
    Write-Host ""
    Write-Host "ERROR: $Message" -ForegroundColor Red
}

function Write-NextStep {
    param([Parameter(Mandatory = $true)][string]$Message)
    Write-Host "  NEXT STEP: $Message" -ForegroundColor Yellow
}

function Stop-SignWorkflow {
    param(
        [Parameter(Mandatory = $true)][string]$Message,
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
        [Parameter(Mandatory = $true)][string]$StepName,
        [Parameter(Mandatory = $true)][scriptblock]$Command
    )

    Write-Step $StepName
    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "$StepName failed with exit code $LASTEXITCODE."
    }
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

function Get-GitCloneUrl {
    param([hashtable]$Config)

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

function Read-EnvFile {
    param([Parameter(Mandatory = $true)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Missing env file: $Path"
    }

    $envMap = @{}
    foreach ($line in (Get-Content -LiteralPath $Path | Where-Object { $_ -notmatch '^\s*(#|$)' -and $_ -match '=' })) {
        $parts = $line -split '=', 2
        $envMap[$parts[0].Trim()] = $parts[1].Trim()
    }
    return $envMap
}

function Initialize-SigningEnvironment {
    param(
        [hashtable]$Config,
        [string]$WorkflowDir,
        [bool]$DryRun
    )

    $required = @(
        'KEYVAULT_URL',
        'AZURE_CLIENT_ID',
        'AZURE_TENANT_ID',
        'AZURE_CLIENT_SECRET',
        'JNLP_JCA_SIGN_ALIAS',
        'JNLP_JCA_TSA_URL'
    )

    $inlineChain = $Config['JNLP_JCA_SIGN_CERTCHAIN']
    $chainHostFile = $Config['JNLP_JCA_SIGN_CERTCHAIN_HOST_FILE']
    $missing = @($required | Where-Object { [string]::IsNullOrWhiteSpace($Config[$_]) })

    if ([string]::IsNullOrWhiteSpace($inlineChain)) {
        if ([string]::IsNullOrWhiteSpace($chainHostFile)) {
            $missing += 'JNLP_JCA_SIGN_CERTCHAIN_HOST_FILE'
        } elseif (-not (Test-Path -LiteralPath $chainHostFile)) {
            if (-not $DryRun) {
                throw "JNLP_JCA_SIGN_CERTCHAIN_HOST_FILE points to a missing file: $chainHostFile"
            }
        }
    }

    if ($missing.Count -gt 0 -and -not $DryRun) {
        throw "sign.env is missing: $($missing -join ', ')"
    }

    if ($DryRun) {
        $tempChainDir = Join-Path $WorkflowDir '.signing-temp'
        New-Item -ItemType Directory -Force -Path $tempChainDir | Out-Null
        $tempChain = Join-Path $tempChainDir 'dry-run-certchain.pem'
        if (-not (Test-Path -LiteralPath $tempChain)) {
            Set-Content -LiteralPath $tempChain -Value '# dry run placeholder' -Encoding ascii
        }
        $env:JNLP_JCA_SIGN_CERTCHAIN_FILE = $tempChain
    } elseif (-not [string]::IsNullOrWhiteSpace($inlineChain)) {
        $tempChainDir = Join-Path $WorkflowDir '.signing-temp'
        New-Item -ItemType Directory -Force -Path $tempChainDir | Out-Null
        $tempChain = Join-Path $tempChainDir 'certchain.pem'
        Set-Content -LiteralPath $tempChain -Value $inlineChain -Encoding utf8
        $env:JNLP_JCA_SIGN_CERTCHAIN_FILE = $tempChain
    } else {
        $env:JNLP_JCA_SIGN_CERTCHAIN_FILE = $chainHostFile
    }

    foreach ($name in $required) {
        if ($DryRun -and [string]::IsNullOrWhiteSpace($Config[$name])) {
            Set-Item -Path "Env:$name" -Value 'dry-run'
        } else {
            Set-Item -Path "Env:$name" -Value $Config[$name]
        }
    }

    if (-not [string]::IsNullOrWhiteSpace($Config['ITW_VERSION'])) {
        $env:ITW_VERSION = $Config['ITW_VERSION'].Trim()
    }
    if (-not [string]::IsNullOrWhiteSpace($Config['ITW_DOTNET_SELF_CONTAINED'])) {
        $env:ITW_DOTNET_SELF_CONTAINED = $Config['ITW_DOTNET_SELF_CONTAINED'].Trim()
    }
    if (-not [string]::IsNullOrWhiteSpace($Config['GITHUB_REPOSITORY'])) {
        $env:GITHUB_REPOSITORY = $Config['GITHUB_REPOSITORY'].Trim()
    } else {
        $env:GITHUB_REPOSITORY = 'martinhickson/IcedTea-Web-1'
    }
    if (-not [string]::IsNullOrWhiteSpace($Config['ITW_CORRETTO_URL'])) {
        $env:ITW_CORRETTO_URL = $Config['ITW_CORRETTO_URL'].Trim()
    }
    if (-not [string]::IsNullOrWhiteSpace($Config['ITW_JDK_VERSION'])) {
        $env:ITW_JDK_VERSION = $Config['ITW_JDK_VERSION'].Trim()
    } else {
        $env:ITW_JDK_VERSION = '11'
    }
    if (-not [string]::IsNullOrWhiteSpace($Config['ITW_JDK11_HOME'])) {
        $env:ITW_JDK11_HOME = $Config['ITW_JDK11_HOME'].Trim()
    }
    if (-not [string]::IsNullOrWhiteSpace($Config['JDK11_HOME'])) {
        $env:JDK11_HOME = $Config['JDK11_HOME'].Trim()
    }
    if (-not [string]::IsNullOrWhiteSpace($Config['JAVA_HOME'])) {
        $env:JAVA_HOME = $Config['JAVA_HOME'].Trim()
    }

    foreach ($name in @('HTTP_PROXY', 'HTTPS_PROXY', 'NO_PROXY')) {
        if (-not [string]::IsNullOrWhiteSpace($Config[$name])) {
            Set-Item -Path "Env:$name" -Value $Config[$name].Trim()
        }
    }
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
        Join-Path $WorkflowDirectory '.source'
    }

    $cloneUrl = $remoteUrl
    if (-not [string]::IsNullOrWhiteSpace($Config['GIT_TOKEN']) -and $cloneUrl -match '^https://') {
        $token = $Config['GIT_TOKEN'].Trim()
        $cloneUrl = $cloneUrl -replace '^https://', "https://x-access-token:${token}@"
        Write-Detail 'Git authentication: using token from sign.env'
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

    return (Resolve-Path -LiteralPath $cloneDir).Path
}

function Resolve-CompileJdkForBuild {
    param([hashtable]$Config = @{})

    $resolved = Resolve-CompileJdkHome -Config $Config
    Set-CompileJdkEnvironment -JdkHome $resolved.JdkHome -Major $resolved.Major
    return $resolved
}

function Invoke-HostSignPipeline {
    param([Parameter(Mandatory = $true)][string]$Root)

    Set-Location $Root

    $dryRun = Test-IsDryRun
    if ($dryRun) {
        Write-Detail 'Dry run mode: enabled (full build; signing step runs AzureSignTool --version only)'
    }

    $version = if ([string]::IsNullOrWhiteSpace($env:ITW_VERSION)) {
        Get-ProjectVersion -Root $Root
    } else {
        $env:ITW_VERSION.Trim()
    }
    $selfContained = if ([string]::IsNullOrWhiteSpace($env:ITW_DOTNET_SELF_CONTAINED)) {
        'true'
    } else {
        $env:ITW_DOTNET_SELF_CONTAINED.Trim()
    }
    $correttoUrl = if ([string]::IsNullOrWhiteSpace($env:ITW_CORRETTO_URL)) {
        'https://corretto.aws/downloads/latest/amazon-corretto-11-x64-windows-jdk.zip'
    } else {
        $env:ITW_CORRETTO_URL.Trim()
    }

    $env:ITW_VERSION = $version
    $env:ITW_WORKSPACE = $Root
    $env:ITW_WORKSPACE_ROOT = $Root
    $resolvedJdk = Resolve-CompileJdkForBuild
    $env:DOTNET_ROOT = if ($env:DOTNET_ROOT) { $env:DOTNET_ROOT } else { 'C:\Program Files\dotnet' }

    Write-Detail "Workspace:           $Root"
    Write-Detail "Version:             $version"
    Write-Detail "ITW_JDK_VERSION:     $($resolvedJdk.Major)"
    Write-Detail "Compile JDK home:    $($resolvedJdk.JdkHome)"
    Write-Detail "Self-contained .NET: $selfContained"
    Write-Detail "Cert chain file:     $($env:JNLP_JCA_SIGN_CERTCHAIN_FILE)"

    $mvnArgs = @(
        '-P', 'maven-distribution',
        '-pl', 'icedtea-web-distribution',
        '-am',
        'install',
        '-Dmaven.test.skip=true',
        '-DskipTests',
        "-Djdk11.home=$($resolvedJdk.JdkHome)",
        "-Ditw.dotnet.selfContained=$selfContained",
        '-Ditw.dotnet.runtime.identifier=win-x64',
        "-Ditw.corretto.url=$correttoUrl"
    )

    Invoke-CheckedCommand -StepName 'Step 1/3: Maven distribution build (win-x64)' -Command {
        Write-Detail "Running: mvn $($mvnArgs -join ' ')"
        & mvn @mvnArgs
    }

    $distZip = Get-ChildItem (Join-Path $Root 'icedtea-web-distribution\target\*.zip') |
        Where-Object { $_.Name -like '*-win-x64.zip' } |
        Select-Object -First 1
    if ($null -eq $distZip) {
        throw 'Expected Windows distribution ZIP was not produced under icedtea-web-distribution\target.'
    }
    Write-Detail "Built distribution ZIP: $($distZip.FullName)"

    $msiScript = Join-Path $Root '.packaging\workflows\windows\container-build-msi.ps1'
    if (-not (Test-Path -LiteralPath $msiScript)) {
        throw "MSI build script not found at $msiScript"
    }

    Invoke-CheckedCommand -StepName 'Step 2/3: WiX MSI build' -Command {
        Write-Detail "Running: $msiScript"
        & $msiScript
    }

    $msiFiles = @(Get-ChildItem (Join-Path $Root 'icedtea-web-distribution\target\native-packages\*.msi') -ErrorAction SilentlyContinue)
    if ($msiFiles.Count -eq 0) {
        throw 'Expected MSI was not produced under icedtea-web-distribution\target\native-packages.'
    }
    Write-Detail "Built MSI: $($msiFiles[0].FullName)"

    $signScript = Join-Path $Root '.jenkins\workflows\sign.ps1'
    if (-not (Test-Path -LiteralPath $signScript)) {
        throw "Signing script not found at $signScript"
    }

    Invoke-CheckedCommand -StepName 'Step 3/3: Sign EXE and MSI with Azure Key Vault' -Command {
        Write-Detail "Running: $signScript"
        & $signScript
    }

    if ($dryRun) {
        Write-Step 'Host PowerShell workflow completed successfully (dry run; artifacts not signed)'
    }
}

function Invoke-WindowsPowerShellFile {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter()][string[]]$ArgumentList = @()
    )

    $powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    if (-not (Test-Path -LiteralPath $powershell)) {
        throw "Windows PowerShell not found: $powershell"
    }

    & $powershell -NoProfile -ExecutionPolicy Bypass -File $FilePath @ArgumentList
}

function Run-HostSignWorkflow {
    if ($env:OS -ne 'Windows_NT') {
        Stop-SignWorkflow -Message 'This workflow requires Windows PowerShell on Windows.' -NextSteps @(
            'Run: powershell.exe -NoProfile -ExecutionPolicy Bypass -File .powershell\workflows\sign.ps1'
        )
    }

    $WorkflowDir = $PSScriptRoot
    $toolchainScript = Join-Path $WorkflowDir 'install-toolchain.ps1'

    Write-Step 'IcedTea-Web Windows release build and sign (host PowerShell workflow)'
    Write-Detail "Host script:      $PSCommandPath"
    Write-Detail "Env file:         $EnvFile"
    Write-Detail "Toolchain script: $toolchainScript"

    Write-Step 'Step 1/4: Load sign.env'
    $envMap = Read-EnvFile -Path $EnvFile
    $dryRun = Test-IsDryRun -Value $env:ITW_DRY_RUN
    if (-not $dryRun) {
        $dryRun = Test-IsDryRun -Value $envMap['ITW_DRY_RUN']
    }
    if ($dryRun) {
        $env:ITW_DRY_RUN = 'true'
        Write-Detail 'Dry run mode: enabled'
    }

    Initialize-SigningEnvironment -Config $envMap -WorkflowDir $WorkflowDir -DryRun:$dryRun

    Write-Step 'Step 2/4: Ensure host toolchain (skip already-installed tools)'
    if (-not (Test-Path -LiteralPath $toolchainScript)) {
        throw "install-toolchain.ps1 not found: $toolchainScript"
    }
    Invoke-WindowsPowerShellFile -FilePath $toolchainScript -ArgumentList @('-InstallTools')
    if ($LASTEXITCODE -ne 0) {
        throw "install-toolchain.ps1 failed with exit code $LASTEXITCODE."
    }

    if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
        throw 'git is still unavailable after toolchain install.'
    }

    Write-Step 'Step 3/4: Clone fresh git source on host'
    $repoRoot = Initialize-SourceCheckout -WorkflowDirectory $WorkflowDir -Config $envMap

    Write-Step 'Step 4/4: Build, package, and sign on host'
    Invoke-HostSignPipeline -Root $repoRoot

    Write-Step 'Host PowerShell workflow completed successfully'
    Write-Detail "Artifacts under: $repoRoot\icedtea-web-distribution\target\"
}

try {
    Run-HostSignWorkflow
} catch {
    Write-Failure $_.Exception.Message
    exit 1
}
