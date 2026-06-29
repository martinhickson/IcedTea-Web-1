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

function Get-IcedTeaWebSignableExes {
    param(
        [Parameter(Mandatory = $true)][string]$ExtractRoot
    )

    $launcherNames = @(
        'javaws.exe',
        'javawsc.exe',
        'itweb-settings.exe',
        'policyeditor.exe'
    )

    $binDirs = @(Get-ChildItem -LiteralPath $ExtractRoot -Directory -Recurse -Filter 'bin' |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'javaws.exe') })
    if ($binDirs.Count -eq 0) {
        throw "No distribution bin directory containing javaws.exe found under $ExtractRoot"
    }

    $binDir = $binDirs | Sort-Object { $_.FullName.Length } | Select-Object -First 1
    $exes = @()
    foreach ($name in $launcherNames) {
        $path = Join-Path $binDir.FullName $name
        if (-not (Test-Path -LiteralPath $path)) {
            throw "Expected IcedTea-Web launcher missing from distribution: $path"
        }
        $exes += Get-Item -LiteralPath $path
    }

    $skipped = @(Get-ChildItem -LiteralPath $binDir.FullName -Filter '*.exe' |
        Where-Object { $launcherNames -notcontains $_.Name })
    if ($skipped.Count -gt 0) {
        Write-Host ("Skipping $($skipped.Count) bundled third-party EXE(s) in $($binDir.FullName): {0}" -f ($skipped.Name -join ', '))
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

if (-not (Get-Command sign -ErrorAction SilentlyContinue) -and -not (Test-Path -LiteralPath (Join-Path $dotnetTools 'sign.exe'))) {
    throw "Microsoft Sign CLI ('sign') is not available on PATH. Install with: dotnet tool install --global --prerelease sign"
}

$zip = Get-ChildItem "icedtea-web-distribution/target/*.zip" | Where-Object {
    $_.Name -like "*-win-x64.zip"
} | Select-Object -First 1
if ($null -eq $zip) {
    throw "Windows distribution ZIP not found under icedtea-web-distribution/target."
}

$msiFiles = @(Get-ChildItem "icedtea-web-distribution/target/native-packages/*.msi" -ErrorAction SilentlyContinue)
if ($msiFiles.Count -eq 0) {
    throw "Windows MSI not found under icedtea-web-distribution/target/native-packages."
}

if (Test-IsDryRun) {
    Write-Host "=== Dry run mode (signing step) ==="
    Write-Host "Built distribution ZIP: $($zip.FullName)"
    Write-Host "Built MSI: $($msiFiles[0].FullName)"
    Write-Host "Running: sign --version"
    & (Resolve-SignCliExe) --version
    if ($LASTEXITCODE -ne 0) {
        throw "sign --version failed with exit code $LASTEXITCODE."
    }

    Write-Host ""
    Write-Host "Dry run: skipping EXE/MSI Key Vault signing."
    exit 0
}

$work = Join-Path $env:RUNNER_TEMP "itw-signed-win-dist"
Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $work | Out-Null

Write-Host "Timestamp URL:       $($env:JNLP_JCA_TSA_URL)"
Write-Host "Distribution ZIP:    $($zip.FullName)"
Write-Host "MSI:                 $($msiFiles[0].FullName)"

Expand-Archive -LiteralPath $zip.FullName -DestinationPath $work
$exeFiles = @(Get-IcedTeaWebSignableExes -ExtractRoot $work)
if ($exeFiles.Count -eq 0) {
    throw "No IcedTea-Web launcher EXE files found inside $($zip.FullName)."
}

Write-Host "Found $($exeFiles.Count) IcedTea-Web launcher EXE file(s) to sign"
Invoke-SignCliKeyVault `
    -TargetFiles @($exeFiles | ForEach-Object { $_.FullName }) `
    -Description 'IcedTea-Web' `
    -DescriptionUrl "https://github.com/$env:GITHUB_REPOSITORY"

Remove-Item $zip.FullName -Force
Compress-Archive -Path (Join-Path $work "*") -DestinationPath $zip.FullName
Write-Host "Repacked signed Windows distribution ZIP: $($zip.FullName)"

Invoke-SignCliKeyVault `
    -TargetFiles @($msiFiles | ForEach-Object { $_.FullName }) `
    -Description 'IcedTea-Web Installer' `
    -DescriptionUrl "https://github.com/$env:GITHUB_REPOSITORY"
