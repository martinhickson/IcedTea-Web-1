param(
    [ValidateSet('Distribution', 'Msi', 'All')]
    [string]$Phase = 'All'
)

$ErrorActionPreference = "Stop"

$dotnetTools = Join-Path $env:USERPROFILE '.dotnet\tools'
if ((Test-Path -LiteralPath $dotnetTools) -and ($env:Path -notlike "*$dotnetTools*")) {
    $env:Path = "$dotnetTools;$env:Path"
}

function Resolve-SignCliExe {
    if (Test-Path -LiteralPath (Join-Path $dotnetTools 'sign.exe')) {
        return (Resolve-Path -LiteralPath (Join-Path $dotnetTools 'sign.exe')).Path
    }
    if (Get-Command sign -ErrorAction SilentlyContinue) {
        return (Get-Command sign -ErrorAction Stop).Source
    }
    throw "Microsoft Sign CLI ('sign') is not available on PATH. Install with: dotnet tool install --global --prerelease sign"
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

function Get-ProjectVersionFromPom {
    $pomPath = Join-Path (Get-Location) "pom.xml"
    if (-not (Test-Path -LiteralPath $pomPath)) {
        throw "Root pom.xml not found at $pomPath"
    }

    $match = Select-String -LiteralPath $pomPath -Pattern '<version>([^<]+)</version>' | Select-Object -First 1
    if ($null -eq $match) {
        throw "Could not read project version from $pomPath"
    }

    return $match.Matches.Groups[1].Value
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

function Get-DistributionDirectory {
    if (-not [string]::IsNullOrWhiteSpace($env:ITW_DIST_DIR)) {
        $path = $env:ITW_DIST_DIR.Trim()
        if (-not (Test-Path -LiteralPath $path)) {
            throw "ITW_DIST_DIR does not exist: $path"
        }
        return (Resolve-Path -LiteralPath $path).Path
    }

    $version = if ([string]::IsNullOrWhiteSpace($env:ITW_VERSION)) {
        Get-ProjectVersionFromPom
    } else {
        $env:ITW_VERSION.Trim()
    }

    $distDir = Join-Path (Get-Location) "icedtea-web-distribution/target/dist/icedtea-web-$version"
    if (Test-Path -LiteralPath $distDir) {
        return (Resolve-Path -LiteralPath $distDir).Path
    }

    $candidates = @(Get-ChildItem (Join-Path (Get-Location) 'icedtea-web-distribution/target/dist') -Directory -ErrorAction SilentlyContinue)
    if ($candidates.Count -eq 1) {
        return $candidates[0].FullName
    }

    throw "Distribution directory not found. Expected icedtea-web-distribution/target/dist/icedtea-web-$version"
}

function Get-DistributionZipPath {
    $zip = Get-ChildItem "icedtea-web-distribution/target/*.zip" | Where-Object {
        $_.Name -like "*-win-x64.zip"
    } | Select-Object -First 1
    if ($null -eq $zip) {
        throw "Windows distribution ZIP not found under icedtea-web-distribution/target."
    }
    return $zip.FullName
}

function Get-IcedTeaWebSignableExes {
    param(
        [Parameter(Mandatory = $true)][string]$DistRoot
    )

    $launcherNames = @(
        'javaws.exe',
        'javawsc.exe',
        'itweb-settings.exe',
        'policyeditor.exe'
    )

    $binDir = Join-Path $DistRoot 'bin'
    if (-not (Test-Path -LiteralPath (Join-Path $binDir 'javaws.exe'))) {
        throw "Distribution bin directory not found or missing javaws.exe: $binDir"
    }

    $exes = @()
    foreach ($name in $launcherNames) {
        $path = Join-Path $binDir $name
        if (-not (Test-Path -LiteralPath $path)) {
            throw "Expected IcedTea-Web launcher missing from distribution: $path"
        }
        $exes += Get-Item -LiteralPath $path
    }

    $skipped = @(Get-ChildItem -LiteralPath $binDir -Filter '*.exe' |
        Where-Object { $launcherNames -notcontains $_.Name })
    if ($skipped.Count -gt 0) {
        Write-Host ("Skipping $($skipped.Count) bundled third-party EXE(s) in $binDir`: {0}" -f ($skipped.Name -join ', '))
    }

    return $exes
}

function Invoke-SignCliKeyVault {
    param(
        [Parameter(Mandatory = $true)][string[]]$TargetFiles,
        [Parameter(Mandatory = $true)][string]$Description,
        [Parameter(Mandatory = $true)][string]$DescriptionUrl
    )

    if ($env:KEYVAULT_URL -notmatch '^https?://') {
        throw "KEYVAULT_URL must include the scheme, e.g. https://your-vault.vault.azure.net/"
    }

    foreach ($target in $TargetFiles) {
        if (-not (Test-Path -LiteralPath $target)) {
            throw "Signing target does not exist: $target"
        }
    }

    $signCli = Resolve-SignCliExe
    $signArgs = @(
        'code', 'azure-key-vault'
    ) + $TargetFiles + @(
        '--azure-key-vault-url', $env:KEYVAULT_URL.TrimEnd('/')
        '--azure-key-vault-certificate', $env:JNLP_JCA_SIGN_ALIAS
        '--file-digest', 'sha256'
        '--timestamp-url', $env:JNLP_JCA_TSA_URL
        '--timestamp-digest', 'sha256'
        '--description', $Description
        '--description-url', $DescriptionUrl
        '--verbosity', 'information'
    )

    Write-Host "Sign CLI:          $signCli"
    Write-Host "Key Vault URL:     $($env:KEYVAULT_URL.TrimEnd('/'))"
    Write-Host "Key Vault cert:    $($env:JNLP_JCA_SIGN_ALIAS)"
    Write-Host "Signing $($TargetFiles.Count) file(s):"
    foreach ($target in $TargetFiles) {
        Write-Host "  $target"
    }

    & $signCli @signArgs
    if ($LASTEXITCODE -ne 0) {
        throw "sign code azure-key-vault failed with exit code $LASTEXITCODE."
    }
}

function Invoke-SignDistributionExesAndZip {
    $distDir = Get-DistributionDirectory
    $zipPath = Get-DistributionZipPath
    $exeFiles = @(Get-IcedTeaWebSignableExes -DistRoot $distDir)

    Write-Host "Distribution tree:   $distDir"
    Write-Host "Distribution ZIP:    $zipPath"
    Write-Host "Timestamp URL:       $($env:JNLP_JCA_TSA_URL)"
    Write-Host "Signing $($exeFiles.Count) launcher EXE(s) in place under bin\"

    Invoke-SignCliKeyVault `
        -TargetFiles @($exeFiles | ForEach-Object { $_.FullName }) `
        -Description 'IcedTea-Web' `
        -DescriptionUrl "https://github.com/$env:GITHUB_REPOSITORY"

    Remove-Item -LiteralPath $zipPath -Force
    Compress-Archive -LiteralPath $distDir -DestinationPath $zipPath
    Write-Host "Rebuilt distribution ZIP from signed tree: $zipPath"
}

function Invoke-SignMsiArtifacts {
    $msiFiles = @(Get-ChildItem "icedtea-web-distribution/target/native-packages/*.msi" -ErrorAction SilentlyContinue)
    if ($msiFiles.Count -eq 0) {
        throw "Windows MSI not found under icedtea-web-distribution/target/native-packages."
    }

    Write-Host "Signing MSI: $($msiFiles[0].FullName)"
    Invoke-SignCliKeyVault `
        -TargetFiles @($msiFiles | ForEach-Object { $_.FullName }) `
        -Description 'IcedTea-Web Installer' `
        -DescriptionUrl "https://github.com/$env:GITHUB_REPOSITORY"
}

function Assert-SigningEnvironment {
    $required = @(
        "KEYVAULT_URL",
        "AZURE_CLIENT_ID",
        "AZURE_TENANT_ID",
        "AZURE_CLIENT_SECRET",
        "JNLP_JCA_SIGN_ALIAS",
        "JNLP_JCA_TSA_URL"
    )
    $missing = $required | Where-Object { [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_)) }
    if ($missing.Count -gt 0) {
        throw "Windows artifact signing is enabled, but required signing environment variables are missing: $($missing -join ', ')"
    }

    if ([string]::IsNullOrWhiteSpace($env:RUNNER_TEMP)) {
        $env:RUNNER_TEMP = if ([string]::IsNullOrWhiteSpace($env:TEMP)) { [System.IO.Path]::GetTempPath() } else { $env:TEMP }
    }

    if ([string]::IsNullOrWhiteSpace($env:GITHUB_REPOSITORY)) {
        $env:GITHUB_REPOSITORY = "martinhickson/IcedTea-Web-1"
    }

    $env:JNLP_JCA_TSA_URL = Resolve-TimestampUrl -Value $env:JNLP_JCA_TSA_URL

    if (-not (Get-Command sign -ErrorAction SilentlyContinue) -and -not (Test-Path -LiteralPath (Join-Path $dotnetTools 'sign.exe'))) {
        throw "Microsoft Sign CLI ('sign') is not available on PATH. Install with: dotnet tool install --global --prerelease sign"
    }
}

if (Test-IsDryRun) {
    Write-Host "=== Dry run mode (signing step) ==="
    Write-Host "Phase: $Phase"
    if ($Phase -in @('Distribution', 'All')) {
        Write-Host "Distribution tree: $(Get-DistributionDirectory)"
        Write-Host "Distribution ZIP:  $(Get-DistributionZipPath)"
    }
    if ($Phase -in @('Msi', 'All')) {
        $msiFiles = @(Get-ChildItem "icedtea-web-distribution/target/native-packages/*.msi" -ErrorAction SilentlyContinue)
        if ($msiFiles.Count -gt 0) {
            Write-Host "Built MSI: $($msiFiles[0].FullName)"
        }
    }
    Write-Host "Running: sign --version"
    & (Resolve-SignCliExe) --version
    if ($LASTEXITCODE -ne 0) {
        throw "sign --version failed with exit code $LASTEXITCODE."
    }
    Write-Host ""
    Write-Host "Dry run: skipping EXE/MSI Key Vault signing."
    exit 0
}

Assert-SigningEnvironment

switch ($Phase) {
    'Distribution' {
        Invoke-SignDistributionExesAndZip
    }
    'Msi' {
        Invoke-SignMsiArtifacts
    }
    'All' {
        Invoke-SignDistributionExesAndZip
        Invoke-SignMsiArtifacts
    }
}
