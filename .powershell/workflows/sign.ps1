# Windows release build and sign on the host (no Docker).
# Copy sign.env.template to sign.env, customize, then run:
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .powershell\workflows\sign.ps1

param(
    [string]$EnvFile = ''
)

$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'jdk.ps1')
. (Join-Path $PSScriptRoot 'ensure-pack200.ps1')

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

function Add-UniquePathPrefix {
    param([string]$Dir)

    if ([string]::IsNullOrWhiteSpace($Dir)) {
        return
    }

    $normalized = $Dir.Trim().TrimEnd('\')
    if ((Test-Path -LiteralPath $normalized) -and ($env:Path -notlike "*$normalized*")) {
        $env:Path = "$normalized;$env:Path"
    }
}

function Get-GitInstallRootFromGitExe {
    param([Parameter(Mandatory = $true)][string]$GitExe)

    $binDir = (Split-Path -Parent $GitExe).TrimEnd('\')
    $leaf = Split-Path -Leaf $binDir
    switch ($leaf.ToLowerInvariant()) {
        'cmd' { return (Split-Path -Parent $binDir) }
        'bin' {
            $parent = Split-Path -Parent $binDir
            if ((Split-Path -Leaf $parent).ToLowerInvariant() -eq 'mingw64') {
                return (Split-Path -Parent $parent)
            }
            return $parent
        }
    }

    return $null
}

function Get-BashCandidatesFromGitRoot {
    param([Parameter(Mandatory = $true)][string]$GitRoot)

    $root = $GitRoot.Trim().TrimEnd('\')
    return @(
        (Join-Path $root 'bin\bash.exe'),
        (Join-Path $root 'usr\bin\bash.exe')
    )
}

function Get-GitPathDirsFromRoot {
    param([Parameter(Mandatory = $true)][string]$GitRoot)

    $root = $GitRoot.Trim().TrimEnd('\')
    return @(
        (Join-Path $root 'cmd'),
        (Join-Path $root 'bin'),
        (Join-Path $root 'mingw64\bin'),
        (Join-Path $root 'usr\bin')
    )
}

function Test-IsWslOrAppsBashStub {
    param([string]$Path)

    if ([string]::IsNullOrWhiteSpace($Path)) {
        return $true
    }

    $normalized = $Path.ToLowerInvariant()
    return (
        $normalized -like '*\windowsapps\*' -or
        $normalized -like '*\system32\bash.exe'
    )
}

function Resolve-GitBashExe {
    $candidateList = New-Object 'System.Collections.Generic.List[string]'
    $seen = @{}

    function Add-LocalGitBashCandidate {
        param([AllowEmptyString()][AllowNull()][string]$Path)

        if ([string]::IsNullOrWhiteSpace($Path)) {
            return
        }

        $normalized = $Path.Trim().Trim('"')
        if (-not $normalized.EndsWith('.exe', [StringComparison]::OrdinalIgnoreCase)) {
            $normalized = Join-Path $normalized 'bin\bash.exe'
        }

        $key = $normalized.ToLowerInvariant()
        if ($seen.ContainsKey($key)) {
            return
        }

        $seen[$key] = $true
        [void]$candidateList.Add($normalized)
    }

    foreach ($name in @('GIT_BASH', 'GIT_HOME', 'GIT_INSTALL_ROOT')) {
        $value = [Environment]::GetEnvironmentVariable($name)
        if ($name -eq 'GIT_BASH') {
            Add-LocalGitBashCandidate -Path $value
            continue
        }
        if (-not [string]::IsNullOrWhiteSpace($value)) {
            foreach ($bash in (Get-BashCandidatesFromGitRoot -GitRoot $value)) {
                Add-LocalGitBashCandidate -Path $bash
            }
        }
    }

    $gitExePaths = New-Object 'System.Collections.Generic.List[string]'
    foreach ($gitCmd in @(Get-Command git -All -ErrorAction SilentlyContinue)) {
        if ($gitCmd.Source) {
            [void]$gitExePaths.Add($gitCmd.Source)
        }
    }

    $whereGit = & where.exe git 2>$null
    if ($LASTEXITCODE -eq 0 -and $whereGit) {
        foreach ($line in @($whereGit)) {
            if (-not [string]::IsNullOrWhiteSpace($line)) {
                [void]$gitExePaths.Add($line.Trim())
            }
        }
    }

    foreach ($gitExe in ($gitExePaths | Select-Object -Unique)) {
        if (-not (Test-Path -LiteralPath $gitExe)) {
            continue
        }

        $gitRoot = Get-GitInstallRootFromGitExe -GitExe $gitExe
        if ($gitRoot) {
            foreach ($bash in (Get-BashCandidatesFromGitRoot -GitRoot $gitRoot)) {
                Add-LocalGitBashCandidate -Path $bash
            }
        }
    }

    foreach ($registryPath in @(
        'HKLM:\SOFTWARE\GitForWindows',
        'HKLM:\SOFTWARE\WOW6432Node\GitForWindows'
    )) {
        if (-not (Test-Path -LiteralPath $registryPath)) {
            continue
        }
        $installPath = (Get-ItemProperty -LiteralPath $registryPath -ErrorAction SilentlyContinue).InstallPath
        if ([string]::IsNullOrWhiteSpace($installPath)) {
            continue
        }
        foreach ($bash in (Get-BashCandidatesFromGitRoot -GitRoot $installPath)) {
            Add-LocalGitBashCandidate -Path $bash
        }
    }

    foreach ($root in @('D:\Git', 'C:\Git', 'C:\Program Files\Git', 'C:\Program Files (x86)\Git')) {
        foreach ($bash in (Get-BashCandidatesFromGitRoot -GitRoot $root)) {
            Add-LocalGitBashCandidate -Path $bash
        }
    }

    foreach ($candidate in $candidateList) {
        if (Test-IsWslOrAppsBashStub -Path $candidate) {
            continue
        }
        if (Test-Path -LiteralPath $candidate) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    return $null
}

function Ensure-GitBashOnPath {
    $gitBash = Resolve-GitBashExe
    if (-not $gitBash) {
        throw @(
            'Git Bash is required for Maven build steps but bash.exe was not found.'
            'Install Git for Windows (choco install git) or disable the Windows "bash.exe" app execution alias under Settings -> Apps -> App execution aliases.'
        ) -join ' '
    }

    $gitRoot = Split-Path -Parent (Split-Path -Parent $gitBash)
    if ((Split-Path -Leaf (Split-Path -Parent $gitBash)).ToLowerInvariant() -eq 'usr') {
        $gitRoot = Split-Path -Parent $gitRoot
    }

    foreach ($dir in (Get-GitPathDirsFromRoot -GitRoot $gitRoot)) {
        Add-UniquePathPrefix -Dir $dir
    }

    # Maven exec plugin resolves "bash" via PATH. Windows App Execution Aliases can
    # intercept that name with the WSL installer stub unless Git Bash wins first.
    $shimDir = Join-Path $PSScriptRoot '.bash-shim'
    New-Item -ItemType Directory -Force -Path $shimDir | Out-Null
    $shimPath = Join-Path $shimDir 'bash.cmd'
    Set-Content -LiteralPath $shimPath -Encoding ascii -Value "@echo off`r`n`"$gitBash`" %*"
    $env:Path = "$shimDir;$env:Path"

    Write-Detail "Git install root:    $gitRoot"
    Write-Detail "Git Bash executable: $gitBash"
    Write-Detail "bash PATH shim:      $shimPath"
}

function Resolve-WorkflowScript {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$RelativePath
    )

    $candidates = @(
        (Join-Path $Root $RelativePath),
        (Join-Path (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path $RelativePath)
    )

    foreach ($path in $candidates) {
        if (Test-Path -LiteralPath $path) {
            return (Resolve-Path -LiteralPath $path).Path
        }
    }

    throw "Workflow script not found: $RelativePath (checked cloned source and host checkout)."
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

function Resolve-SignEnvFile {
    param(
        [Parameter(Mandatory = $true)][string]$WorkflowDir,
        [string]$ExplicitPath = ''
    )

    if (-not [string]::IsNullOrWhiteSpace($ExplicitPath)) {
        if (-not (Test-Path -LiteralPath $ExplicitPath)) {
            throw "Env file not found: $ExplicitPath"
        }
        return (Resolve-Path -LiteralPath $ExplicitPath).Path
    }

    $envFile = Join-Path $WorkflowDir 'sign.env'
    $templateFile = Join-Path $WorkflowDir 'sign.env.template'

    if (Test-Path -LiteralPath $envFile) {
        return (Resolve-Path -LiteralPath $envFile).Path
    }

    if (-not (Test-Path -LiteralPath $templateFile)) {
        throw "Missing sign.env. Copy sign.env.template to sign.env and customize it."
    }

    Copy-Item -LiteralPath $templateFile -Destination $envFile
    throw "Created sign.env from sign.env.template. Customize credentials and paths, then re-run."
}

function Read-EnvFile {
    param([Parameter(Mandatory = $true)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Missing env file: $Path"
    }

    $envMap = @{}
    foreach ($line in (Get-Content -LiteralPath $Path | Where-Object { $_ -notmatch '^\s*(#|$)' -and $_ -match '=' })) {
        $parts = $line -split '=', 2
        $value = $parts[1].Trim()
        if ($value.Length -ge 2 -and $value.StartsWith('"') -and $value.EndsWith('"')) {
            $value = $value.Substring(1, $value.Length - 2)
        } elseif ($value.Length -ge 2 -and $value.StartsWith("'") -and $value.EndsWith("'")) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $envMap[$parts[0].Trim()] = $value
    }
    return $envMap
}

function Resolve-TimestampUrl {
    param(
        [Parameter(Mandatory = $true)][string]$Value
    )

    $url = $Value.Trim()
    if ([string]::IsNullOrWhiteSpace($url)) {
        throw 'JNLP_JCA_TSA_URL is required. Example: http://timestamp.digicert.com'
    }

    if ($url -notmatch '^https?://') {
        $url = "http://$url"
    }

    $uri = $null
    if (-not [Uri]::TryCreate($url, [UriKind]::Absolute, [ref]$uri)) {
        throw "JNLP_JCA_TSA_URL must be an absolute http or https URL (got: $Value)"
    }
    if ($uri.Scheme -notin @('http', 'https')) {
        throw "JNLP_JCA_TSA_URL must use http or https (got: $Value)"
    }

    return $uri.AbsoluteUri
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

    if (-not [string]::IsNullOrWhiteSpace($chainHostFile) -and -not (Test-Path -LiteralPath $chainHostFile)) {
        if (-not $DryRun) {
            Write-Detail "Warning: JNLP_JCA_SIGN_CERTCHAIN_HOST_FILE not found: $chainHostFile (optional; merge intermediates into Key Vault cert instead)"
        }
    }

    if ($missing.Count -gt 0 -and -not $DryRun) {
        throw "sign.env is missing required values: $($missing -join ', ')"
    }

    if ($DryRun) {
        $tempChainDir = Join-Path $WorkflowDir '.signing-temp'
        New-Item -ItemType Directory -Force -Path $tempChainDir | Out-Null
        $tempChain = Join-Path $tempChainDir 'dry-run-certchain.pem'
        if (-not (Test-Path -LiteralPath $tempChain)) {
            Set-Content -LiteralPath $tempChain -Value '# dry run placeholder' -Encoding ascii
        }
    } elseif (-not [string]::IsNullOrWhiteSpace($inlineChain)) {
        $tempChainDir = Join-Path $WorkflowDir '.signing-temp'
        New-Item -ItemType Directory -Force -Path $tempChainDir | Out-Null
        $tempChain = Join-Path $tempChainDir 'certchain.pem'
        Set-Content -LiteralPath $tempChain -Value $inlineChain -Encoding utf8
    }

    foreach ($name in $required) {
        if ($DryRun -and [string]::IsNullOrWhiteSpace($Config[$name])) {
            Set-Item -Path "Env:$name" -Value 'dry-run'
        } else {
            Set-Item -Path "Env:$name" -Value $Config[$name]
        }
    }

    if (-not $DryRun) {
        $env:JNLP_JCA_TSA_URL = Resolve-TimestampUrl -Value $env:JNLP_JCA_TSA_URL
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
        'It mirrors the github repository to https://securemvn.com/releases for io.pack200:pack200.'
        'Expected under .powershell\workflows\ or .jenkins\workflows\ in the checkout.'
    ) -join ' '
}

function Invoke-HostSignPipeline {
    param([Parameter(Mandatory = $true)][string]$Root)

    Set-Location $Root

    $dryRun = Test-IsDryRun
    if ($dryRun) {
        Write-Detail 'Dry run mode: enabled (full build; signing step runs sign --version only)'
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
    Write-Detail "Sign cert alias:     $($env:JNLP_JCA_SIGN_ALIAS)"

    $mavenSettings = Get-MavenSettingsArgs -Root $Root
    Write-Detail "Maven settings:      $($mavenSettings.Path)"

    Ensure-Pack200MavenDependency
    Ensure-GitBashOnPath

    $mvnArgs = $mavenSettings.Args + @(
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

    Invoke-CheckedCommand -StepName 'Step 1/4: Maven distribution build (win-x64)' -Command {
        Write-Detail "Running: mvn $($mvnArgs -join ' ')"
        & mvn @mvnArgs
    }

    $distDir = Join-Path $Root "icedtea-web-distribution\target\dist\icedtea-web-$version"
    $env:ITW_DIST_DIR = $distDir
    Write-Detail "Distribution tree:   $distDir"

    $distZip = Get-ChildItem (Join-Path $Root 'icedtea-web-distribution\target\*.zip') |
        Where-Object { $_.Name -like '*-win-x64.zip' } |
        Select-Object -First 1
    if ($null -eq $distZip) {
        throw 'Expected Windows distribution ZIP was not produced under icedtea-web-distribution\target.'
    }
    Write-Detail "Built distribution ZIP: $($distZip.FullName)"

    $signScript = Resolve-WorkflowScript -Root $Root -RelativePath '.jenkins\workflows\sign.ps1'
    Write-Detail "Signing script:      $signScript"

    if (-not $dryRun) {
        Invoke-CheckedCommand -StepName 'Step 2/4: Sign launcher EXEs in dist and rebuild ZIP' -Command {
            Write-Detail "Running: $signScript -Phase Distribution"
            & $signScript -Phase Distribution
        }
    }

    $msiScript = Resolve-WorkflowScript -Root $Root -RelativePath '.packaging\workflows\windows\container-build-msi.ps1'
    Write-Detail "MSI build script:   $msiScript"

    Invoke-CheckedCommand -StepName $(if ($dryRun) { 'Step 2/4: WiX MSI build' } else { 'Step 3/4: WiX MSI build' }) -Command {
        Write-Detail "Running: $msiScript"
        & $msiScript
    }

    $msiFiles = @(Get-ChildItem (Join-Path $Root 'icedtea-web-distribution\target\native-packages\*.msi') -ErrorAction SilentlyContinue)
    if ($msiFiles.Count -eq 0) {
        throw 'Expected MSI was not produced under icedtea-web-distribution\target\native-packages.'
    }
    Write-Detail "Built MSI: $($msiFiles[0].FullName)"

    Invoke-CheckedCommand -StepName $(if ($dryRun) { 'Step 3/4: Sign EXE and MSI with Azure Key Vault (dry run)' } else { 'Step 4/4: Sign MSI with Azure Key Vault' }) -Command {
        if ($dryRun) {
            Write-Detail "Running: $signScript -Phase All"
            & $signScript -Phase All
        } else {
            Write-Detail "Running: $signScript -Phase Msi"
            & $signScript -Phase Msi
        }
    }

    if ($dryRun) {
        Write-Step 'Host PowerShell workflow completed successfully (dry run; artifacts not signed)'
    }
    Write-Detail "MSI checksum:      $($msiFiles[0].FullName).sha256.txt"
}

function Update-DotNetToolsPath {
    $machinePath = [Environment]::GetEnvironmentVariable('Path', 'Machine')
    $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    if ($userPath) {
        $env:Path = "$machinePath;$userPath"
    } else {
        $env:Path = $machinePath
    }

    $dotnetTools = Join-Path $env:USERPROFILE '.dotnet\tools'
    if ((Test-Path -LiteralPath $dotnetTools) -and ($env:Path -notlike "*$dotnetTools*")) {
        $env:Path = "$dotnetTools;$env:Path"
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
    $EnvFile = Resolve-SignEnvFile -WorkflowDir $WorkflowDir -ExplicitPath $EnvFile
    $toolchainScript = Join-Path $WorkflowDir 'install-toolchain.ps1'

    Write-Step 'IcedTea-Web Windows release build and sign (host PowerShell workflow)'
    Write-Detail "Host script:      $PSCommandPath"
    Write-Detail "Env file:         $EnvFile"
    Write-Detail "Toolchain script: $toolchainScript"

    Write-Step 'Step 1/4: Load signing environment'
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

    Update-DotNetToolsPath

    if (-not (Get-Command sign -ErrorAction SilentlyContinue)) {
        throw "Microsoft Sign CLI ('sign') is not available after toolchain install. Re-run: install-toolchain.ps1 -InstallTools"
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
