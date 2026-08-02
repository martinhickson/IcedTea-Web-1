param(
    [ValidateSet('Distribution', 'Msi', 'All')]
    [string]$Phase = 'All'
)

$ErrorActionPreference = 'Stop'

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

function Test-SigningSecretsPresent {
    $required = @(
        'KEYVAULT_URL',
        'AZURE_CLIENT_ID',
        'AZURE_TENANT_ID',
        'AZURE_CLIENT_SECRET',
        'JNLP_JCA_SIGN_ALIAS',
        'JNLP_JCA_TSA_URL'
    )
    $missing = @($required | Where-Object {
            [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_))
        })
    return @{
        Present = ($missing.Count -eq 0)
        Missing = $missing
    }
}

function Get-WindowsZipPath {
    $zip = Get-ChildItem -Path . -Filter 'icedtea-web-*.win.bin.zip' -File |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($null -eq $zip) {
        throw 'Windows zip not found (expected icedtea-web-*.win.bin.zip in workspace root).'
    }
    return $zip.FullName
}

function Get-WindowsMsiPath {
    $msi = @(
        Get-ChildItem -Path 'win-installer.build' -Filter 'icedtea-web-*.msi' -File -ErrorAction SilentlyContinue
    ) + @(
        Get-ChildItem -Path . -Recurse -Filter 'icedtea-web-*.msi' -File -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -notmatch '\\release\\' }
    ) | Select-Object -First 1
    if ($null -eq $msi) {
        throw 'Windows MSI not found (expected icedtea-web-*.msi under win-installer.build/).'
    }
    return $msi.FullName
}

function Get-ReleaseDirectory {
    $releaseDir = Join-Path (Get-Location) 'release'
    New-Item -ItemType Directory -Force -Path $releaseDir | Out-Null
    return (Resolve-Path -LiteralPath $releaseDir).Path
}

function Write-ReleaseArtifactChecksum {
    param([Parameter(Mandatory = $true)][string]$Path)

    $file = Get-Item -LiteralPath $Path
    $hash = Get-FileHash -Algorithm SHA256 $file.FullName
    $checksumPath = "$($file.FullName).sha256.txt"
    "$($hash.Hash.ToLowerInvariant())  $($file.Name)" | Out-File -FilePath $checksumPath -Encoding ASCII
    Write-Host "Wrote checksum: $checksumPath"
}

function Get-IcedTeaWebLauncherNames {
    # Classic 1.8 Windows native launchers (no javawsc on this line).
    return @('javaws.exe', 'itweb-settings.exe', 'policyeditor.exe')
}

function Get-IcedTeaWebSignableExes {
    param([Parameter(Mandatory = $true)][string]$DistRoot)

    $binDir = Join-Path $DistRoot 'bin'
    if (-not (Test-Path -LiteralPath $binDir)) {
        throw "Distribution bin directory not found: $binDir"
    }

    $exes = @()
    foreach ($name in Get-IcedTeaWebLauncherNames) {
        $path = Join-Path $binDir $name
        if (-not (Test-Path -LiteralPath $path)) {
            throw "Expected IcedTea-Web launcher missing from distribution: $path"
        }
        $exes += Get-Item -LiteralPath $path
    }
    return $exes
}

function Assert-DistributionLaunchersSigned {
    param([Parameter(Mandatory = $true)][string]$DistRoot)

    $binDir = Join-Path $DistRoot 'bin'
    $problems = @()
    foreach ($name in Get-IcedTeaWebLauncherNames) {
        $path = Join-Path $binDir $name
        $signature = Get-AuthenticodeSignature -LiteralPath $path
        if ($signature.Status -ne 'Valid') {
            $problems += "$name ($($signature.Status))"
            continue
        }
        Write-Host "Verified Authenticode signature: $path"
        if ($signature.SignerCertificate) {
            Write-Host "  Signer: $($signature.SignerCertificate.Subject)"
        }
    }
    if ($problems.Count -gt 0) {
        throw "Distribution launchers are not Authenticode-signed under $binDir`: $($problems -join ', ')"
    }
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

function Expand-WindowsZipToTemp {
    param([Parameter(Mandatory = $true)][string]$ZipPath)

    $workRoot = Join-Path $env:RUNNER_TEMP ("itw-sign-" + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path $workRoot | Out-Null
    Expand-Archive -LiteralPath $ZipPath -DestinationPath $workRoot -Force

    $top = @(Get-ChildItem -LiteralPath $workRoot -Directory)
    if ($top.Count -ne 1) {
        throw "Expected exactly one top-level directory inside $ZipPath (found $($top.Count))."
    }
    return @{
        WorkRoot = $workRoot
        DistRoot = $top[0].FullName
    }
}

function Invoke-StageUnsignedArtifacts {
    $releaseDir = Get-ReleaseDirectory
    $zipPath = Get-WindowsZipPath
    Copy-Item -LiteralPath $zipPath -Destination (Join-Path $releaseDir (Split-Path -Leaf $zipPath)) -Force
    Write-ReleaseArtifactChecksum -Path (Join-Path $releaseDir (Split-Path -Leaf $zipPath))

    try {
        $msiPath = Get-WindowsMsiPath
        Copy-Item -LiteralPath $msiPath -Destination (Join-Path $releaseDir (Split-Path -Leaf $msiPath)) -Force
        Write-ReleaseArtifactChecksum -Path (Join-Path $releaseDir (Split-Path -Leaf $msiPath))
    } catch {
        Write-Warning $_.Exception.Message
    }

    Write-Host "Staged unsigned artifacts under $releaseDir"
    Get-ChildItem -LiteralPath $releaseDir | ForEach-Object { Write-Host "  $($_.Name)" }
}

function Invoke-SignDistributionExesAndZip {
    $zipPath = Get-WindowsZipPath
    $expanded = Expand-WindowsZipToTemp -ZipPath $zipPath
    $distRoot = $expanded.DistRoot
    $exeFiles = @(Get-IcedTeaWebSignableExes -DistRoot $distRoot)

    Write-Host "Distribution tree:   $distRoot"
    Write-Host "Distribution ZIP:    $zipPath"
    Write-Host "Timestamp URL:       $($env:JNLP_JCA_TSA_URL)"
    Write-Host "Signing $($exeFiles.Count) launcher EXE(s) in place under bin\"

    $descriptionUrl = if ([string]::IsNullOrWhiteSpace($env:GITHUB_REPOSITORY)) {
        'https://github.com/martinhickson/IcedTea-Web-1'
    } else {
        "https://github.com/$env:GITHUB_REPOSITORY"
    }

    Invoke-SignCliKeyVault `
        -TargetFiles @($exeFiles | ForEach-Object { $_.FullName }) `
        -Description 'IcedTea-Web' `
        -DescriptionUrl $descriptionUrl

    Assert-DistributionLaunchersSigned -DistRoot $distRoot

    Remove-Item -LiteralPath $zipPath -Force
    # Preserve classic zip layout: single top-level directory (e.g. icedtea-web-image/).
    Compress-Archive -LiteralPath $distRoot -DestinationPath $zipPath
    Write-Host "Rebuilt distribution ZIP from signed tree: $zipPath"

    $releaseDir = Get-ReleaseDirectory
    $releaseZip = Join-Path $releaseDir (Split-Path -Leaf $zipPath)
    Copy-Item -LiteralPath $zipPath -Destination $releaseZip -Force
    Write-ReleaseArtifactChecksum -Path $releaseZip

    Remove-Item -LiteralPath $expanded.WorkRoot -Recurse -Force -ErrorAction SilentlyContinue
}

function Invoke-SignMsiArtifacts {
    $msiPath = Get-WindowsMsiPath
    $descriptionUrl = if ([string]::IsNullOrWhiteSpace($env:GITHUB_REPOSITORY)) {
        'https://github.com/martinhickson/IcedTea-Web-1'
    } else {
        "https://github.com/$env:GITHUB_REPOSITORY"
    }

    Write-Host "Signing MSI: $msiPath"
    Invoke-SignCliKeyVault `
        -TargetFiles @($msiPath) `
        -Description 'IcedTea-Web Installer' `
        -DescriptionUrl $descriptionUrl

    $releaseDir = Get-ReleaseDirectory
    $releaseMsi = Join-Path $releaseDir (Split-Path -Leaf $msiPath)
    Copy-Item -LiteralPath $msiPath -Destination $releaseMsi -Force
    Write-ReleaseArtifactChecksum -Path $releaseMsi
}

function Assert-SigningEnvironment {
    $check = Test-SigningSecretsPresent
    if (-not $check.Present) {
        throw "Windows artifact signing is enabled, but required signing environment variables are missing: $($check.Missing -join ', ')"
    }

    if ([string]::IsNullOrWhiteSpace($env:RUNNER_TEMP)) {
        $env:RUNNER_TEMP = if ([string]::IsNullOrWhiteSpace($env:TEMP)) {
            [System.IO.Path]::GetTempPath()
        } else {
            $env:TEMP
        }
    }

    $env:JNLP_JCA_TSA_URL = Resolve-TimestampUrl -Value $env:JNLP_JCA_TSA_URL
    Resolve-SignCliExe | Out-Null
}

$secretCheck = Test-SigningSecretsPresent
if (-not $secretCheck.Present) {
    Write-Warning ("Skipping Key Vault signing; missing secrets: {0}" -f ($secretCheck.Missing -join ', '))
    Invoke-StageUnsignedArtifacts
    exit 0
}

Assert-SigningEnvironment

switch ($Phase) {
    'Distribution' { Invoke-SignDistributionExesAndZip }
    'Msi' { Invoke-SignMsiArtifacts }
    'All' {
        Invoke-SignDistributionExesAndZip
        Invoke-SignMsiArtifacts
    }
}
