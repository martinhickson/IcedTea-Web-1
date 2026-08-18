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

function Get-WindowsInternetProxyUrl {
    if (-not ($IsWindows -or $env:OS -eq 'Windows_NT')) {
        return $null
    }

    try {
        $settings = Get-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings' -ErrorAction Stop
        if ($settings.ProxyEnable -ne 1) {
            return $null
        }

        $raw = [string]$settings.ProxyServer
        if ([string]::IsNullOrWhiteSpace($raw)) {
            return $null
        }

        $proxyHost = $null
        if ($raw -match '(?i)https=([^;]+)') {
            $proxyHost = $Matches[1]
        } elseif ($raw -match '(?i)http=([^;]+)') {
            $proxyHost = $Matches[1]
        } else {
            $proxyHost = $raw
        }

        if ($proxyHost -notmatch '^https?://') {
            $proxyHost = "http://$proxyHost"
        }
        return $proxyHost
    } catch {
        return $null
    }
}

function Get-ProxyDisplayString {
    param([string]$ProxyUrl)

    if ([string]::IsNullOrWhiteSpace($ProxyUrl)) {
        return '(none)'
    }

    try {
        $uri = [Uri]$ProxyUrl
        if ($uri.UserInfo) {
            return "$($uri.Scheme)://***@$($uri.Host):$($uri.Port)"
        }
        return $ProxyUrl
    } catch {
        return $ProxyUrl
    }
}

function Initialize-DockerBuildProxy {
    param([hashtable]$Config = @{})

    $proxyUrl = $null
    foreach ($name in @('HTTPS_PROXY', 'HTTP_PROXY', 'https_proxy', 'http_proxy')) {
        $value = if ($Config.ContainsKey($name)) { $Config[$name] } else { [Environment]::GetEnvironmentVariable($name) }
        if (-not [string]::IsNullOrWhiteSpace($value)) {
            $proxyUrl = $value.Trim()
            break
        }
    }
    if (-not $proxyUrl) {
        $proxyUrl = Get-WindowsInternetProxyUrl
    }
    if (-not $proxyUrl) {
        return $false
    }

    $noProxy = $null
    foreach ($name in @('NO_PROXY', 'no_proxy')) {
        $value = if ($Config.ContainsKey($name)) { $Config[$name] } else { [Environment]::GetEnvironmentVariable($name) }
        if (-not [string]::IsNullOrWhiteSpace($value)) {
            $noProxy = $value.Trim()
            break
        }
    }

    $env:HTTP_PROXY = $proxyUrl
    $env:HTTPS_PROXY = $proxyUrl
    $env:http_proxy = $proxyUrl
    $env:https_proxy = $proxyUrl
    if ($noProxy) {
        $env:NO_PROXY = $noProxy
        $env:no_proxy = $noProxy
    }
    return $true
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

function Write-DockerCommandLine {
    param(
        [Parameter(Mandatory = $true)]
        [string]$DockerBin,
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    $commandLine = ($Arguments | ForEach-Object {
        if ($_ -match '\s') { '"' + $_ + '"' } else { $_ }
    }) -join ' '
    Write-Detail ("Running: {0} {1}" -f $DockerBin, $commandLine)
}

function Invoke-DockerCommand {
    param(
        [Parameter(Mandatory = $true)]
        [string]$StepName,
        [Parameter(Mandatory = $true)]
        [string]$DockerBin,
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    Write-Step $StepName
    Write-DockerCommandLine -DockerBin $DockerBin -Arguments $Arguments
    & $DockerBin @Arguments
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "$StepName failed with exit code $exitCode."
    }
}

function Show-DockerHostContext {
    param(
        [Parameter(Mandatory = $true)]
        [string]$DockerBin,
        [Parameter(Mandatory = $true)]
        [string]$ComposeFile,
        [Parameter(Mandatory = $true)]
        [string]$WorkflowDir,
        [Parameter(Mandatory = $true)]
        [string]$DockerfilePath
    )

    Write-Step "Docker host context (pre-flight)"
    Invoke-DockerCommand -StepName "Docker version" -DockerBin $DockerBin -Arguments @("version")
    Invoke-DockerCommand -StepName "Docker Compose version" -DockerBin $DockerBin -Arguments @("compose", "version")

    Write-Step "Docker engine details"
    Write-DockerCommandLine -DockerBin $DockerBin -Arguments @("info", "--format", "OSType={{.OSType}} OperatingSystem={{.OperatingSystem}} ServerVersion={{.ServerVersion}}")
    & $DockerBin info --format "OSType={{.OSType}} OperatingSystem={{.OperatingSystem}} ServerVersion={{.ServerVersion}}"
    if ($LASTEXITCODE -ne 0) {
        throw "docker info failed with exit code $LASTEXITCODE."
    }

    if (Test-Path -LiteralPath $DockerfilePath) {
        $fromLine = Get-Content -LiteralPath $DockerfilePath | Where-Object { $_ -match '^\s*FROM\s+' } | Select-Object -First 1
        if ($fromLine) {
            Write-Detail "Dockerfile base image: $($fromLine.Trim())"
        }
        Write-Detail "Dockerfile path:       $DockerfilePath"
    } else {
        Write-Detail "Dockerfile path:       $DockerfilePath (not found on host)"
    }

    Write-Detail "Compose file:          $ComposeFile"
    Write-Detail "Compose project dir:   $WorkflowDir"
    Write-Detail "Build context:         $WorkflowDir"
    Write-Detail "Target platform:       windows/amd64"
    Write-Detail "Build HTTP proxy:      $(Get-ProxyDisplayString -ProxyUrl $env:HTTP_PROXY)"
    if ($env:NO_PROXY) {
        Write-Detail "Build NO_PROXY:        $($env:NO_PROXY)"
    }

    $osType = (& $DockerBin info --format "{{.OSType}}" 2>$null | Out-String).Trim()
    if ($osType) {
        Write-Detail "Docker engine OSType:  $osType"
        if ($osType -eq "linux") {
            Stop-SignWorkflow `
                -Message "Docker engine OSType is linux; this workflow builds windows/amd64 images and must run on a Windows containers engine." `
                -NextSteps @(
                    "Switch Docker Desktop to Windows containers, then verify: docker info --format ""{{.OSType}}"""
                )
        }
    }
}

function Invoke-DockerComposeWorkflow {
    param(
        [Parameter(Mandatory = $true)]
        [string]$DockerBin,
        [Parameter(Mandatory = $true)]
        [string]$ComposeFile,
        [Parameter(Mandatory = $true)]
        [string]$WorkflowDir,
        [Parameter(Mandatory = $true)]
        [string]$DockerfilePath
    )

    $composeArgs = @(
        "compose",
        "--file", $ComposeFile,
        "--project-directory", $WorkflowDir
    )

    $previousBuildKitProgress = $env:BUILDKIT_PROGRESS
    $previousComposeBake = $env:COMPOSE_BAKE
    $env:BUILDKIT_PROGRESS = "plain"
    $env:COMPOSE_BAKE = "false"

    Write-Detail "Compose build pulls the latest base image from MCR (--pull), not ACR."
    Write-Detail "Base image: mcr.microsoft.com/windows/servercore:ltsc2022"
    Write-Detail "Equivalent manual steps:"
    Write-Detail "  docker pull mcr.microsoft.com/windows/servercore:ltsc2022"
    Write-Detail "  docker build --file sign.dockerfile ."

    try {
        Invoke-DockerCommand `
            -StepName "Docker Compose config (resolved services/volumes/build)" `
            -DockerBin $DockerBin `
            -Arguments ($composeArgs + @("config"))

        Invoke-DockerCommand `
            -StepName "Docker build via compose (--pull sign; COMPOSE_BAKE=false)" `
            -DockerBin $DockerBin `
            -Arguments ($composeArgs + @("build", "--pull", "sign"))

        Invoke-DockerCommand `
            -StepName "Docker Compose up (run sign container to completion)" `
            -DockerBin $DockerBin `
            -Arguments ($composeArgs + @("up", "--no-build", "--abort-on-container-exit", "--remove-orphans", "sign"))
    } finally {
        if ($null -eq $previousBuildKitProgress) {
            Remove-Item Env:BUILDKIT_PROGRESS -ErrorAction SilentlyContinue
        } else {
            $env:BUILDKIT_PROGRESS = $previousBuildKitProgress
        }
        if ($null -eq $previousComposeBake) {
            Remove-Item Env:COMPOSE_BAKE -ErrorAction SilentlyContinue
        } else {
            $env:COMPOSE_BAKE = $previousComposeBake
        }
    }
}

function Write-DockerFailureHelp {
    param(
        [string]$Message = "",
        [string]$FailedStep = "Docker Compose workflow",
        [string]$WorkflowDir = "",
        [string]$DockerfilePath = ""
    )

    Write-Host ""
    Write-Host "=== Docker failure summary ===" -ForegroundColor Yellow
    Write-Detail "Failed during: $FailedStep"
    if (-not [string]::IsNullOrWhiteSpace($Message)) {
        Write-Detail "Error:         $Message"
    }
    Write-Detail "Base image:    mcr.microsoft.com/windows/servercore:ltsc2022"
    Write-Detail "Compose file:  sign.compose (service: sign, platform: windows/amd64)"
    if ($WorkflowDir) {
        Write-Detail "Project dir:   $WorkflowDir"
    }
    Write-Detail "Commands run by this script:"
    Write-Detail "  docker compose --file sign.compose --project-directory <workflows> config"
    Write-Detail "  docker compose --file sign.compose --project-directory <workflows> build --pull sign"
    Write-Detail "  docker compose --file sign.compose --project-directory <workflows> up --no-build --abort-on-container-exit --remove-orphans sign"
    Write-Detail "Manual equivalent:"
    if ($WorkflowDir -and $DockerfilePath) {
        Write-Detail "  cd $WorkflowDir"
        Write-Detail "  docker pull mcr.microsoft.com/windows/servercore:ltsc2022"
        Write-Detail "  docker build --file sign.dockerfile ."
    }

    if ($Message -notmatch 'unauthorized|authentication|401|basic auth|denied') {
        return
    }

    Write-Host ""
    Write-Host "=== Registry auth note ===" -ForegroundColor Yellow
    Write-Detail "Pulls come from MCR (mcr.microsoft.com), not Azure ACR."
    Write-Detail "Azure credentials in sign.env are for Key Vault signing, not Docker registry login."
    Write-NextStep "Confirm pull works: docker pull mcr.microsoft.com/windows/servercore:ltsc2022"
    Write-NextStep "Then confirm build works: docker build --file sign.dockerfile ."
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

function Test-IsDryRun {
    param(
        [string]$Value = $env:ITW_DRY_RUN
    )

    if ([string]::IsNullOrWhiteSpace($Value)) {
        return $false
    }

    switch ($Value.Trim().ToLowerInvariant()) {
        '1' { return $true }
        'true' { return $true }
        'yes' { return $true }
        'on' { return $true }
        default { return $false }
    }
}

function Invoke-DryRunSigningStep {
    param(
        [Parameter(Mandatory = $true)][string]$DistZip,
        [Parameter(Mandatory = $true)][string]$MsiPath
    )

    Write-Step "Step 3/3: Dry run signing check (sign --version only)"
    Write-Detail "Distribution ZIP: $DistZip"
    Write-Detail "MSI:              $MsiPath"
    Write-Detail "Dry run: full Windows build completed; skipping EXE/MSI Key Vault signing."

    if (-not (Get-Command sign -ErrorAction SilentlyContinue)) {
        throw "Microsoft Sign CLI ('sign') is not available on PATH."
    }

    Write-Detail "Running: sign --version"
    & sign --version
    if ($LASTEXITCODE -ne 0) {
        throw "sign --version failed with exit code $LASTEXITCODE."
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

function Resolve-ContainerJdk11Home {
    if (-not [string]::IsNullOrWhiteSpace($env:JDK11_HOME)) {
        return $env:JDK11_HOME.Trim()
    }
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        return $env:JAVA_HOME.Trim()
    }

    $corretto = Get-ChildItem 'C:\Program Files\Amazon Corretto' -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if ($null -ne $corretto) {
        return $corretto.FullName
    }

    throw "JDK 11 home not found. The sign image should install corretto11jdk via Chocolatey."
}

function Get-MavenSettingsArgs {
    param([string]$Root = '')

    $candidates = @(
        (Join-Path $PSScriptRoot 'maven-settings.xml')
    )
    if ($Root) {
        $candidates += @(
            (Join-Path $Root '.powershell\workflows\maven-settings.xml'),
            (Join-Path $Root '.jenkins\workflows\maven-settings.xml')
        )
    }

    foreach ($path in $candidates) {
        if (Test-Path -LiteralPath $path) {
            return @{
                Path = (Resolve-Path -LiteralPath $path).Path
                Args = @('-s', $path)
            }
        }
    }

    throw @(
        'maven-settings.xml not found.'
        'It mirrors Maven Central through the GCS mirror (io.github.martinhickson:pack200).'
        'Expected under .powershell\workflows\ or .jenkins\workflows\ in the checkout.'
    ) -join ' '
}

function Resolve-WorkflowScript {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$RelativePath
    )

    $driverRoot = if (-not [string]::IsNullOrWhiteSpace($env:ITW_WORKFLOW_SCRIPTS_ROOT)) {
        $env:ITW_WORKFLOW_SCRIPTS_ROOT.Trim()
    } elseif (-not [string]::IsNullOrWhiteSpace($env:ITW_WORKSPACE_ROOT)) {
        $env:ITW_WORKSPACE_ROOT.Trim()
    } else {
        throw 'ITW_WORKFLOW_SCRIPTS_ROOT or ITW_WORKSPACE_ROOT must be set.'
    }

    $candidates = @(
        (Join-Path $driverRoot $RelativePath),
        (Join-Path $Root $RelativePath)
    )

    foreach ($path in $candidates) {
        if (Test-Path -LiteralPath $path) {
            return (Resolve-Path -LiteralPath $path).Path
        }
    }

    throw "Workflow script not found: $RelativePath (checked driver checkout at $driverRoot and cloned source at $Root)."
}

function Run-ContainerSignWorkflow {
    Write-Step "IcedTea-Web Windows release build (container)"
    Write-Detail "Pipeline: Maven distribution (win-x64) -> sign EXEs in dist -> WiX MSI -> sign MSI"
    if (-not [string]::IsNullOrWhiteSpace($env:ITW_WORKFLOW_SCRIPTS_ROOT)) {
        Write-Detail "Workflow scripts:    $($env:ITW_WORKFLOW_SCRIPTS_ROOT)"
    }

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

    $dryRun = Test-IsDryRun
    if ($dryRun) {
        Write-Detail "Dry run mode: enabled (full build; signing step runs sign --version only)"
    }

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
    $env:JDK11_HOME = Resolve-ContainerJdk11Home
    $env:JAVA_HOME = $env:JDK11_HOME
    $env:DOTNET_ROOT = if ($env:DOTNET_ROOT) { $env:DOTNET_ROOT } else { "C:\Program Files\dotnet" }

    Write-Detail "Workspace:           $root"
    Write-Detail "Version:             $version"
    Write-Detail "JDK 11:              $($env:JDK11_HOME)"
    Write-Detail "Self-contained .NET: $selfContained"
    Write-Detail "Corretto URL:        $correttoUrl"
    Write-Detail "Key Vault:           $($env:KEYVAULT_URL)"
    Write-Detail "Sign alias:          $($env:JNLP_JCA_SIGN_ALIAS)"
    Write-Detail "TSA URL:             $($env:JNLP_JCA_TSA_URL)"
    Write-Detail "Cert chain file:     $($env:JNLP_JCA_SIGN_CERTCHAIN_FILE)"

    $mavenSettings = Get-MavenSettingsArgs -Root $root
    Write-Detail "Maven settings:      $($mavenSettings.Path)"

    $ensurePack200 = Join-Path $root '.powershell\workflows\ensure-pack200.ps1'
    if (-not (Test-Path -LiteralPath $ensurePack200)) {
        $ensurePack200 = Join-Path $PSScriptRoot '..\..\.powershell\workflows\ensure-pack200.ps1'
    }
    if (Test-Path -LiteralPath $ensurePack200) {
        . $ensurePack200
        Ensure-Pack200MavenDependency
    } else {
        Write-Detail 'ensure-pack200.ps1 not found; relying on Maven repository resolution.'
    }

    $mvnArgs = $mavenSettings.Args + @(
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

    Invoke-CheckedCommand -StepName "Step 1/4: Maven distribution build (win-x64)" -Command {
        Write-Detail "Running: mvn $($mvnArgs -join ' ')"
        & mvn @mvnArgs
    }

    $env:ITW_DIST_DIR = Join-Path $root "icedtea-web-distribution\target\dist\icedtea-web-$version"
    Write-Detail "Distribution tree:   $($env:ITW_DIST_DIR)"

    $distZip = Get-ChildItem (Join-Path $root "icedtea-web-distribution\target\*.zip") |
        Where-Object { $_.Name -like "*-win-x64.zip" } |
        Select-Object -First 1
    if ($null -eq $distZip) {
        throw "Expected Windows distribution ZIP was not produced under icedtea-web-distribution\target."
    }
    Write-Detail "Built distribution ZIP: $($distZip.FullName)"

    $signScript = Resolve-WorkflowScript -Root $root -RelativePath '.jenkins\workflows\sign.ps1'
    Write-Detail "Signing script:      $signScript"

    if (-not $dryRun) {
        Invoke-CheckedCommand -StepName "Step 2/4: Sign launcher EXEs in dist and rebuild ZIP" -Command {
            Write-Detail "Running: $signScript -Phase Distribution"
            & $signScript -Phase Distribution
        }
        $env:ITW_REQUIRE_SIGNED_DIST = 'true'
    }

    $msiScript = Resolve-WorkflowScript -Root $root -RelativePath '.packaging\workflows\windows\container-build-msi.ps1'
    Write-Detail "MSI build script:   $msiScript"

    Invoke-CheckedCommand -StepName $(if ($dryRun) { "Step 2/4: WiX MSI build" } else { "Step 3/4: WiX MSI build" }) -Command {
        Write-Detail "Running: $msiScript"
        & $msiScript
    }

    $msiFiles = @(Get-ChildItem (Join-Path $root "icedtea-web-distribution\target\native-packages\*.msi") -ErrorAction SilentlyContinue)
    if ($msiFiles.Count -eq 0) {
        throw "Expected MSI was not produced under icedtea-web-distribution\target\native-packages."
    }
    Write-Detail "Built MSI: $($msiFiles[0].FullName)"

    if ($dryRun) {
        Invoke-DryRunSigningStep -DistZip $distZip.FullName -MsiPath $msiFiles[0].FullName
    } else {
        Invoke-CheckedCommand -StepName "Step 4/4: Sign MSI with Azure Key Vault" -Command {
            Write-Detail "Running: $signScript -Phase Msi"
            & $signScript -Phase Msi
        }
    }

    if ($dryRun) {
        Write-Step "Windows release build completed successfully (dry run; artifacts not signed)"
    } else {
        Write-Step "Windows release build and signing completed successfully"
    }
    Write-Detail "Distribution ZIP: $($distZip.FullName)"
    Write-Detail "MSI:              $($msiFiles[0].FullName)"
    Write-Detail "MSI checksum:     $($msiFiles[0].FullName).sha256.txt"
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
    Write-Detail "Container pipeline:"
    Write-Detail "  1. Maven distribution build for win-x64"
    Write-Detail "  2. Sign launcher EXEs in dist and rebuild ZIP"
    Write-Detail "  3. WiX MSI packaging from signed dist"
    Write-Detail "  4. Sign MSI"
    if (Test-IsDryRun -Value $env:ITW_DRY_RUN) {
        Write-Detail "Dry run mode:      enabled (full build; signing step runs sign --version only)"
    }

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
    $dryRun = Test-IsDryRun -Value $env:ITW_DRY_RUN
    if (-not $dryRun) {
        $dryRun = Test-IsDryRun -Value $envMap['ITW_DRY_RUN']
    }
    if ($dryRun) {
        $env:ITW_DRY_RUN = 'true'
    }

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
        if (-not $dryRun) {
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

        Write-Detail "Dry run mode: skipping signing credential validation."
    }

    if ($dryRun) {
        $tempChainDir = Join-Path $WorkflowDir ".signing-temp"
        New-Item -ItemType Directory -Force -Path $tempChainDir | Out-Null
        $tempChain = Join-Path $tempChainDir "dry-run-certchain.pem"
        if (-not (Test-Path -LiteralPath $tempChain)) {
            Set-Content -LiteralPath $tempChain -Value "# dry run placeholder" -Encoding ascii
        }
        $env:JNLP_JCA_SIGN_CERTCHAIN_HOST_FILE = $tempChain
        Write-Detail "Certificate chain source: dry-run placeholder $tempChain"
    } elseif ([string]::IsNullOrWhiteSpace($inlineChain)) {
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
        if ($dryRun -and [string]::IsNullOrWhiteSpace($envMap[$name])) {
            Set-Item -Path "Env:$name" -Value 'dry-run'
        } else {
            Set-Item -Path "Env:$name" -Value $envMap[$name]
        }
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
    Write-Detail "TSA URL:               $(if ($dryRun -and [string]::IsNullOrWhiteSpace($envMap['JNLP_JCA_TSA_URL'])) { 'dry-run' } else { $envMap['JNLP_JCA_TSA_URL'] })"
    Write-Detail "Container cert path:   $(if ($envMap['JNLP_JCA_SIGN_CERTCHAIN_FILE']) { $envMap['JNLP_JCA_SIGN_CERTCHAIN_FILE'] } else { 'C:\signing\certchain.pem' })"
    if ($dryRun) {
        Write-Detail "Dry run mode:          enabled"
    }

    Write-Step "Step 3/5: Clone fresh git source on host"
    $driverRoot = (Resolve-Path -LiteralPath (Join-Path $WorkflowDir '..\..')).Path
    $env:ITW_WORKFLOW_SCRIPTS_ROOT = $driverRoot
    Write-Detail "Workflow scripts root: $driverRoot"
    $repoRoot = Initialize-SourceCheckout -WorkflowDirectory $WorkflowDir -Config $envMap

    Write-Step "Step 4/5: Export build environment for Docker Compose"
    $proxyConfigured = Initialize-DockerBuildProxy -Config $envMap
    if ($proxyConfigured) {
        Write-Detail "Docker build proxy:    $(Get-ProxyDisplayString -ProxyUrl $env:HTTP_PROXY)"
        if ($env:NO_PROXY) {
            Write-Detail "Docker build NO_PROXY: $($env:NO_PROXY)"
        }
    } else {
        Write-Detail "Docker build proxy:    (none; set HTTP_PROXY/HTTPS_PROXY in sign.env or Windows Internet Settings)"
    }

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
    Write-Detail "This step runs docker compose config, build, and up as separate commands."
    Write-Detail "Container pipeline after image build: Maven build -> WiX MSI -> Azure Key Vault signing"

    $DockerfilePath = Join-Path $WorkflowDir "sign.dockerfile"
    try {
        Show-DockerHostContext `
            -DockerBin $DockerBin `
            -ComposeFile $ComposeFile `
            -WorkflowDir $WorkflowDir `
            -DockerfilePath $DockerfilePath

        Invoke-DockerComposeWorkflow `
            -DockerBin $DockerBin `
            -ComposeFile $ComposeFile `
            -WorkflowDir $WorkflowDir `
            -DockerfilePath $DockerfilePath
    } catch {
        Write-DockerFailureHelp `
            -Message $_.Exception.Message `
            -FailedStep "Step 5/5 Docker Compose workflow" `
            -WorkflowDir $WorkflowDir `
            -DockerfilePath $DockerfilePath
        throw
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
