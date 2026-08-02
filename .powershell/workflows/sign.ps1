# Windows host release build and sign for classic 1.8 (Cygwin autotools + rust launchers + WiX 3).
# Mirrors Trunk .powershell/workflows/sign.ps1, adapted to this branch's Windows build shape.
#
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .powershell\workflows\sign.ps1 -Signing Off -UseLocalSource
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .powershell\workflows\sign.ps1 -Signing On

param(
    [string]$EnvFile = '',
    [ValidateSet('', 'On', 'Off')]
    [string]$Signing = '',
    [switch]$UseLocalSource,
    [switch]$SkipToolchain
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

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
    if ($null -ne $LASTEXITCODE -and $LASTEXITCODE -ne 0) {
        throw "$StepName failed with exit code $LASTEXITCODE."
    }
}

function Invoke-WindowsPowerShellFile {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$ArgumentList = @()
    )

    $powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    if (-not (Test-Path -LiteralPath $powershell)) {
        throw "Windows PowerShell not found: $powershell"
    }
    & $powershell -NoProfile -ExecutionPolicy Bypass -File $FilePath @ArgumentList
}

function Update-DotNetToolsPath {
    $dotnetTools = Join-Path $env:USERPROFILE '.dotnet\tools'
    if ((Test-Path -LiteralPath $dotnetTools) -and ($env:Path -notlike "*$dotnetTools*")) {
        $env:Path = "$dotnetTools;$env:Path"
    }
    $cargoBin = Join-Path $env:USERPROFILE '.cargo\bin'
    if ((Test-Path -LiteralPath $cargoBin) -and ($env:Path -notlike "*$cargoBin*")) {
        $env:Path = "$cargoBin;$env:Path"
    }
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $javaBin = Join-Path $env:JAVA_HOME 'bin'
        if ((Test-Path -LiteralPath $javaBin) -and ($env:Path -notlike "*$javaBin*")) {
            $env:Path = "$javaBin;$env:Path"
        }
    }
}

function Test-IsSigningEnabled {
    param(
        [string]$SigningParam = '',
        [hashtable]$Config = @{}
    )

    if ($SigningParam -eq 'On') { return $true }
    if ($SigningParam -eq 'Off') { return $false }

    $fromEnv = $Config['ITW_SIGNING']
    if ([string]::IsNullOrWhiteSpace($fromEnv)) {
        $fromEnv = $env:ITW_SIGNING
    }
    if (-not [string]::IsNullOrWhiteSpace($fromEnv)) {
        switch ($fromEnv.Trim().ToLowerInvariant()) {
            'on' { return $true }
            'off' { return $false }
            'true' { return $true }
            'false' { return $false }
            '1' { return $true }
            '0' { return $false }
        }
    }

    $dryRun = $Config['ITW_DRY_RUN']
    if ([string]::IsNullOrWhiteSpace($dryRun)) {
        $dryRun = $env:ITW_DRY_RUN
    }
    if (-not [string]::IsNullOrWhiteSpace($dryRun)) {
        switch ($dryRun.Trim().ToLowerInvariant()) {
            'true' { return $false }
            '1' { return $false }
            'yes' { return $false }
        }
    }

    return $true
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
    param([Parameter(Mandatory = $true)][string]$Value)

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

    $missing = @($required | Where-Object { [string]::IsNullOrWhiteSpace($Config[$_]) })
    if ($missing.Count -gt 0 -and -not $DryRun) {
        throw "sign.env is missing required values: $($missing -join ', ')"
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
    if (-not [string]::IsNullOrWhiteSpace($Config['GITHUB_REPOSITORY'])) {
        $env:GITHUB_REPOSITORY = $Config['GITHUB_REPOSITORY'].Trim()
    } else {
        $env:GITHUB_REPOSITORY = 'martinhickson/IcedTea-Web-1'
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

function Get-GitCloneUrl {
    param([hashtable]$Config)

    if (-not [string]::IsNullOrWhiteSpace($Config['GIT_REMOTE_URL'])) {
        return $Config['GIT_REMOTE_URL'].Trim()
    }
    $repo = if (-not [string]::IsNullOrWhiteSpace($Config['GITHUB_REPOSITORY'])) {
        $Config['GITHUB_REPOSITORY'].Trim()
    } else {
        'martinhickson/IcedTea-Web-1'
    }
    return "https://github.com/$repo.git"
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

    & git clone --depth 1 --single-branch --branch $gitRef $cloneUrl $cloneDir
    if ($LASTEXITCODE -ne 0) {
        throw "git clone failed with exit code $LASTEXITCODE."
    }

    return (Resolve-Path -LiteralPath $cloneDir).Path
}

function Resolve-WorkflowScript {
    param(
        [Parameter(Mandatory = $true)][string]$DriverRoot,
        [Parameter(Mandatory = $true)][string]$RelativePath
    )

    $candidate = Join-Path $DriverRoot $RelativePath
    if (-not (Test-Path -LiteralPath $candidate)) {
        throw "Workflow script not found: $candidate"
    }
    return (Resolve-Path -LiteralPath $candidate).Path
}

function Get-CygwinBashExe {
    $candidates = @(
        'C:\cygwin64\bin\bash.exe'
        'C:\cygwin\bin\bash.exe'
    )
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    return $null
}

function ConvertTo-CygwinPath {
    param([Parameter(Mandatory = $true)][string]$WindowsPath)

    $full = (Resolve-Path -LiteralPath $WindowsPath).Path
    if ($full -match '^([A-Za-z]):\\(.*)$') {
        $drive = $Matches[1].ToLowerInvariant()
        $rest = ($Matches[2] -replace '\\', '/')
        return "/cygdrive/$drive/$rest"
    }
    throw "Cannot convert path to Cygwin form: $WindowsPath"
}

function Assert-CygwinReady {
    $bash = Get-CygwinBashExe
    if (-not $bash) {
        Stop-SignWorkflow -Message 'Cygwin bash not found (expected C:\cygwin64\bin\bash.exe).' -NextSteps @(
            'Re-run without -SkipToolchain so install-toolchain.ps1 can install Cygwin automatically.'
            'Or run: powershell.exe -NoProfile -ExecutionPolicy Bypass -File .powershell\workflows\install-toolchain.ps1 -InstallTools'
        )
    }
    Write-Detail "Cygwin bash: $bash"
    return $bash
}

function Get-Sha256Upper {
    param([Parameter(Mandatory = $true)][string]$Path)
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToUpperInvariant()
}

function Install-VerifiedJar {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [Parameter(Mandatory = $true)][string]$OutFile,
        [Parameter(Mandatory = $true)][string]$ExpectedSha256
    )

    if ((Test-Path -LiteralPath $OutFile) -and ((Get-Sha256Upper -Path $OutFile) -eq $ExpectedSha256)) {
        Write-Detail "OK: $OutFile"
        return
    }

    Write-Detail "Downloading $Url"
    Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $OutFile
    $actual = Get-Sha256Upper -Path $OutFile
    if ($actual -ne $ExpectedSha256) {
        throw "Checksum mismatch for $OutFile (got $actual, expected $ExpectedSha256)"
    }
}

function Ensure-ClassicWindowsJavaDeps {
    $javaShare = 'C:\cygwin64\usr\share\java'
    if (-not (Test-Path -LiteralPath 'C:\cygwin64')) {
        throw 'C:\cygwin64 not found; cannot install classic Windows Java deps.'
    }
    New-Item -ItemType Directory -Force -Path $javaShare | Out-Null

    Install-VerifiedJar `
        -Url 'https://repo1.maven.org/maven2/org/ccil/cowan/tagsoup/tagsoup/1.2.1/tagsoup-1.2.1.jar' `
        -OutFile (Join-Path $javaShare 'tagsoup.jar') `
        -ExpectedSha256 'AC97F7B4B1D8E9337EDFA0E34044F8D0EFE7223F6AD8F3A85D54CC1018EA2E04'
    Install-VerifiedJar `
        -Url 'https://repo1.maven.org/maven2/com/github/vatbub/mslinks/1.0.5/mslinks-1.0.5.jar' `
        -OutFile (Join-Path $javaShare 'mslinks.jar') `
        -ExpectedSha256 'E14D756F81B310B75BAEB5BAF219D25592B6A8635EB215C4059F17493B0CEA5C'
    Install-VerifiedJar `
        -Url 'https://github.com/akashche/wixgen/releases/download/1.7/wixgen.jar' `
        -OutFile (Join-Path $javaShare 'wixgen.jar') `
        -ExpectedSha256 '57E68A91C46A2F4B1B41A3F93793E331D62D6D151DDC222FA4D3EC9CE876F967'
    Install-VerifiedJar `
        -Url 'https://github.com/martinhickson/pack200/releases/download/pack200-11.0.2/pack200-11.0.2.jar' `
        -OutFile (Join-Path $javaShare 'pack.jar') `
        -ExpectedSha256 '13CEC5CB108C2BCC1AA22845F53DCA94D9D3CF148C73EE98457CDA50B0642728'
    Install-VerifiedJar `
        -Url 'https://repo1.maven.org/maven2/net/bytebuddy/byte-buddy/1.14.10/byte-buddy-1.14.10.jar' `
        -OutFile (Join-Path $javaShare 'byte-buddy.jar') `
        -ExpectedSha256 '30E6E0446437A67DB37E2B7F7D33F50787DDFD970359319DFD05469DAA2DCBCE'
    Install-VerifiedJar `
        -Url 'https://repo1.maven.org/maven2/net/bytebuddy/byte-buddy-agent/1.14.10/byte-buddy-agent-1.14.10.jar' `
        -OutFile (Join-Path $javaShare 'byte-buddy-agent.jar') `
        -ExpectedSha256 '67993A89D47CA58FF868802A4448DDD150E5FE4E5A5645DED990D7B4D557A6B9'

    $rhinoJar = Join-Path $javaShare 'js.jar'
    if (-not (Test-Path -LiteralPath $rhinoJar)) {
        $zip = Join-Path $env:TEMP 'rhino1_6R7.zip'
        Install-VerifiedJar `
            -Url 'https://ftp.mozilla.org/pub/mozilla.org/js/rhino1_6R7.zip' `
            -OutFile $zip `
            -ExpectedSha256 'C94C6DE3A29B3ACBC4EEE732E688F75A5D94BD02C9878BE4CEB4D3CD220F3866'
        $extract = Join-Path $env:TEMP 'rhino1_6R7-extract'
        if (Test-Path -LiteralPath $extract) {
            Remove-Item -LiteralPath $extract -Recurse -Force
        }
        Expand-Archive -LiteralPath $zip -DestinationPath $extract -Force
        $found = Get-ChildItem -LiteralPath $extract -Recurse -Filter 'js.jar' | Select-Object -First 1
        if (-not $found) {
            throw 'Rhino js.jar not found after extract.'
        }
        Copy-Item -LiteralPath $found.FullName -Destination $rhinoJar -Force
    }
    Write-Detail "OK: $rhinoJar"
}

function Ensure-WixShortName {
    $wixDir = 'C:\Program Files (x86)\WiX Toolset v3.14'
    $short = 'C:\Program Files (x86)\WIXTOO~1.14'
    if (-not (Test-Path -LiteralPath $wixDir)) {
        throw "WiX Toolset v3.14 not found at $wixDir (install-toolchain should provide it)."
    }
    if (Test-Path -LiteralPath $short) {
        Write-Detail "WiX short name present: $short"
        return
    }
    Write-Detail 'Setting WiX 8.3 short name WIXTOO~1.14'
    & fsutil behavior set disable8dot3 0 | Out-Null
    & fsutil file setshortname $wixDir 'WIXTOO~1.14'
    if (-not (Test-Path -LiteralPath $short)) {
        throw "Failed to create WiX short name $short"
    }
}

function Invoke-CygwinBashScript {
    param(
        [Parameter(Mandatory = $true)][string]$BashExe,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][string]$ScriptPath
    )

    if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        throw 'JAVA_HOME is not set. Run install-toolchain.ps1 -InstallTools or set JAVA_HOME in sign.env.'
    }

    $scriptCyg = ConvertTo-CygwinPath -WindowsPath $ScriptPath
    $workCyg = ConvertTo-CygwinPath -WindowsPath $WorkingDirectory
    $javaCyg = ConvertTo-CygwinPath -WindowsPath $env:JAVA_HOME

    # Login shell so Cygwin PATH/tools are available; cd to source tree then run driver script.
    # Keep Windows USERPROFILE form; windows_build.sh runs cygpath on it for cargo.
    $userProfile = $env:USERPROFILE.Replace("'", "'\''")
    $command = "cd '$workCyg' && export JAVA_HOME='$javaCyg' && export USERPROFILE='$userProfile' && bash '$scriptCyg'"
    Write-Detail "Cygwin workdir: $workCyg"
    Write-Detail "Build script:   $scriptCyg"
    Write-Detail "JAVA_HOME:      $env:JAVA_HOME"

    & $BashExe -lc $command
    if ($LASTEXITCODE -ne 0) {
        throw "Cygwin build failed with exit code $LASTEXITCODE."
    }
}

function Invoke-StageUnsignedArtifacts {
    param([Parameter(Mandatory = $true)][string]$Root)

    $releaseDir = Join-Path $Root 'release'
    New-Item -ItemType Directory -Force -Path $releaseDir | Out-Null

    $zip = Get-ChildItem -LiteralPath $Root -Filter 'icedtea-web-*.win.bin.zip' -File |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $zip) {
        throw "Windows zip not found under $Root after build."
    }
    Copy-Item -LiteralPath $zip.FullName -Destination (Join-Path $releaseDir $zip.Name) -Force
    $hash = Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $releaseDir $zip.Name)
    "$($hash.Hash.ToLowerInvariant())  $($zip.Name)" |
        Out-File -FilePath (Join-Path $releaseDir "$($zip.Name).sha256.txt") -Encoding ASCII

    $msi = Get-ChildItem -LiteralPath (Join-Path $Root 'win-installer.build') -Filter 'icedtea-web-*.msi' -File -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($msi) {
        Copy-Item -LiteralPath $msi.FullName -Destination (Join-Path $releaseDir $msi.Name) -Force
        $msiHash = Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $releaseDir $msi.Name)
        "$($msiHash.Hash.ToLowerInvariant())  $($msi.Name)" |
            Out-File -FilePath (Join-Path $releaseDir "$($msi.Name).sha256.txt") -Encoding ASCII
    }

    Write-Detail "Staged unsigned artifacts under $releaseDir"
    Get-ChildItem -LiteralPath $releaseDir | ForEach-Object { Write-Detail $_.Name }
}

function Invoke-HostSignPipeline {
    param(
        [Parameter(Mandatory = $true)][string]$SourceRoot,
        [Parameter(Mandatory = $true)][string]$DriverRoot,
        [bool]$SigningEnabled = $true
    )

    Set-Location $SourceRoot

    if ($SigningEnabled) {
        Write-Detail 'Signing: on'
    } else {
        Write-Detail 'Signing: off (full classic Windows build; Authenticode / Key Vault signing skipped)'
    }

    $bash = Assert-CygwinReady

    Invoke-CheckedCommand -StepName 'Ensure classic Windows Java deps (Cygwin share)' -Command {
        Ensure-ClassicWindowsJavaDeps
    }

    Invoke-CheckedCommand -StepName 'Ensure WiX Toolset v3.14 short name' -Command {
        Ensure-WixShortName
    }

    $buildScript = Resolve-WorkflowScript -DriverRoot $DriverRoot -RelativePath '.github\workflows\windows_build.sh'
    Invoke-CheckedCommand -StepName 'Step: Cygwin autotools build (windows_build.sh)' -Command {
        Invoke-CygwinBashScript -BashExe $bash -WorkingDirectory $SourceRoot -ScriptPath $buildScript
    }

    $zip = Get-ChildItem -LiteralPath $SourceRoot -Filter 'icedtea-web-*.win.bin.zip' -File -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $zip) {
        throw 'Build finished but icedtea-web-*.win.bin.zip was not produced.'
    }
    Write-Detail "Built zip: $($zip.FullName)"

    if ($SigningEnabled) {
        $signScript = Resolve-WorkflowScript -DriverRoot $DriverRoot -RelativePath '.github\workflows\sign.ps1'
        Invoke-CheckedCommand -StepName 'Step: Sign launchers + MSI (Sign CLI / Key Vault)' -Command {
            Update-DotNetToolsPath
            & $signScript -Phase All
        }
    } else {
        Invoke-CheckedCommand -StepName 'Step: Stage unsigned release artifacts' -Command {
            Invoke-StageUnsignedArtifacts -Root $SourceRoot
        }
    }

    Write-Detail "Artifacts under: $SourceRoot\release\"
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
    Write-Detail 'Build shape: classic Cygwin autotools + rust launchers + WiX 3 MSI'
    Write-Detail "Host script:      $PSCommandPath"
    Write-Detail "Env file:         $EnvFile"
    Write-Detail "Toolchain script: $toolchainScript"

    Write-Step 'Step 1/4: Load signing environment'
    $envMap = Read-EnvFile -Path $EnvFile
    $signingEnabled = Test-IsSigningEnabled -SigningParam $Signing -Config $envMap
    if (-not $signingEnabled) {
        $env:ITW_DRY_RUN = 'true'
        $env:ITW_SIGNING = 'off'
        Write-Detail 'Signing: off'
    } else {
        $env:ITW_SIGNING = 'on'
        Write-Detail 'Signing: on'
    }

    Initialize-SigningEnvironment -Config $envMap -DryRun:(-not $signingEnabled)

    Write-Step 'Step 2/4: Ensure host toolchain (skip already-installed tools)'
    if ($SkipToolchain) {
        Write-Detail 'SkipToolchain: verifying existing tools only'
        Update-DotNetToolsPath
        if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME) -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
            throw 'JAVA_HOME is not a usable JDK. Install JDK 8 or run without -SkipToolchain.'
        }
        if (-not (Get-Command cargo -ErrorAction SilentlyContinue)) {
            throw 'cargo is not available. Install rustup or run without -SkipToolchain.'
        }
        if ($signingEnabled -and -not (Get-Command sign -ErrorAction SilentlyContinue)) {
            throw "Microsoft Sign CLI ('sign') is not available. Run install-toolchain.ps1 -InstallTools."
        }
    } else {
        if (-not (Test-Path -LiteralPath $toolchainScript)) {
            throw "install-toolchain.ps1 not found: $toolchainScript"
        }
        Invoke-WindowsPowerShellFile -FilePath $toolchainScript -ArgumentList @('-InstallTools')
        if ($LASTEXITCODE -ne 0) {
            throw "install-toolchain.ps1 failed with exit code $LASTEXITCODE."
        }
        Update-DotNetToolsPath
    }

    if ($signingEnabled -and -not (Get-Command sign -ErrorAction SilentlyContinue)) {
        throw "Microsoft Sign CLI ('sign') is not available after toolchain install. Re-run: install-toolchain.ps1 -InstallTools"
    }

    if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
        throw 'git is still unavailable after toolchain install.'
    }

    $driverRoot = (Resolve-Path -LiteralPath (Join-Path $WorkflowDir '..\..')).Path
    $env:ITW_WORKFLOW_SCRIPTS_ROOT = $driverRoot
    Write-Detail "Workflow scripts root: $driverRoot"

    if ($UseLocalSource) {
        Write-Step 'Step 3/4: Use local checkout (skip git clone)'
        $sourceRoot = $driverRoot
        Write-Detail "Source root:         $sourceRoot"
    } else {
        Write-Step 'Step 3/4: Clone fresh git source on host'
        $sourceRoot = Initialize-SourceCheckout -WorkflowDirectory $WorkflowDir -Config $envMap
    }

    Write-Step 'Step 4/4: Build, package, and sign on host'
    Invoke-HostSignPipeline -SourceRoot $sourceRoot -DriverRoot $driverRoot -SigningEnabled:$signingEnabled

    Write-Step 'Host PowerShell workflow completed successfully'
    Write-Detail "Artifacts under: $sourceRoot\release\"
}

try {
    Run-HostSignWorkflow
} catch {
    Write-Failure $_.Exception.Message
    exit 1
}
