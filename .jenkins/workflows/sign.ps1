$ErrorActionPreference = "Stop"

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

if (-not [string]::IsNullOrWhiteSpace($env:JNLP_JCA_SIGN_CERTCHAIN_FILE)) {
    if (-not (Test-Path -LiteralPath $env:JNLP_JCA_SIGN_CERTCHAIN_FILE)) {
        throw "JNLP_JCA_SIGN_CERTCHAIN_FILE not found: $($env:JNLP_JCA_SIGN_CERTCHAIN_FILE)"
    }
    $env:JNLP_JCA_SIGN_CERTCHAIN = Get-Content -Raw -LiteralPath $env:JNLP_JCA_SIGN_CERTCHAIN_FILE
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

if ([string]::IsNullOrWhiteSpace($env:JNLP_JCA_SIGN_CERTCHAIN)) {
    throw "Windows artifact signing requires JNLP_JCA_SIGN_CERTCHAIN or JNLP_JCA_SIGN_CERTCHAIN_FILE."
}

if ([string]::IsNullOrWhiteSpace($env:RUNNER_TEMP)) {
    $env:RUNNER_TEMP = if ([string]::IsNullOrWhiteSpace($env:TEMP)) { [System.IO.Path]::GetTempPath() } else { $env:TEMP }
}

if ([string]::IsNullOrWhiteSpace($env:GITHUB_REPOSITORY)) {
    $env:GITHUB_REPOSITORY = "martinhickson/IcedTea-Web-1"
}

function Format-HResultMessage {
    param([int]$ExitCode)

    if ($ExitCode -eq 0) {
        return 'Success (0).'
    }

    $unsigned = $ExitCode -band 0xFFFFFFFF
    switch ($unsigned) {
        0x80070002 { return '0x80070002 (ERROR_FILE_NOT_FOUND). AzureSignTool often reports this when a file path is wrong, or when Key Vault auth/permissions fail (misleading "file not found" for the certificate name). Verify AZURE_CLIENT_SECRET, vault RBAC (certificate read + key sign), and that each target file exists.' }
        0x80070005 { return '0x80070005 (ERROR_ACCESS_DENIED). Check Key Vault permissions for the service principal.' }
        0x80004005 { return '0x80004005 (E_FAIL). Signing failed; run with --verbose for SignTool details.' }
        default { return ('0x{0:X8} ({1}).' -f $unsigned, $ExitCode) }
    }
}

function Get-AdditionalCertificateArgs {
    param(
        [string]$PemContent,
        [string]$WorkDir
    )

    $pattern = '(?ms)^-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----\r?\n?'
    $matches = [regex]::Matches($PemContent, $pattern)
    if ($matches.Count -eq 0) {
        throw "JNLP_JCA_SIGN_CERTCHAIN does not contain any PEM certificates."
    }

    $certificateArgList = @()
    $index = 0
    foreach ($match in $matches) {
        $certPath = Join-Path $WorkDir "signing-chain-$index.pem"
        [System.IO.File]::WriteAllText($certPath, $match.Value.TrimEnd())
        if (-not (Test-Path -LiteralPath $certPath)) {
            throw "Failed to write intermediate certificate file: $certPath"
        }
        $certificateArgList += '--additional-certificates'
        $certificateArgList += $certPath
        $index++
    }
    return ,$certificateArgList
}

function Invoke-AzureSignToolSign {
    param(
        [Parameter(Mandatory = $true)][string[]]$VaultAuthArgs,
        [Parameter(Mandatory = $true)][string[]]$AdditionalCertArgs,
        [Parameter(Mandatory = $true)][string]$TargetFile,
        [Parameter(Mandatory = $true)][string]$Description,
        [Parameter(Mandatory = $true)][string]$DescriptionUrl
    )

    if (-not (Test-Path -LiteralPath $TargetFile)) {
        throw "Signing target does not exist: $TargetFile"
    }

    $signArgs = @(
        'sign'
    ) + $VaultAuthArgs + $AdditionalCertArgs + @(
        '--file-digest', 'sha256',
        '--timestamp-rfc3161', $env:JNLP_JCA_TSA_URL,
        '--timestamp-digest', 'sha256',
        '--description', $Description,
        '--description-url', $DescriptionUrl,
        '--verbose',
        $TargetFile
    )

    $azureSignTool = (Get-Command AzureSignTool -ErrorAction Stop).Source
    Write-Host "AzureSignTool: $azureSignTool"
    Write-Host "Signing:       $TargetFile"

    & $azureSignTool @signArgs
    if ($LASTEXITCODE -ne 0) {
        throw ("AzureSignTool failed for {0}. Exit code {1}. {2}" -f $TargetFile, $LASTEXITCODE, (Format-HResultMessage -ExitCode $LASTEXITCODE))
    }
}

if (-not (Get-Command AzureSignTool -ErrorAction SilentlyContinue)) {
    throw "AzureSignTool is not available on PATH."
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
    if (-not (Get-Command AzureSignTool -ErrorAction SilentlyContinue)) {
        throw "AzureSignTool is not available on PATH."
    }

    Write-Host "=== Dry run mode (signing step) ==="
    Write-Host "Built distribution ZIP: $($zip.FullName)"
    Write-Host "Built MSI: $($msiFiles[0].FullName)"
    Write-Host "Running: AzureSignTool --version"
    & AzureSignTool --version
    if ($LASTEXITCODE -ne 0) {
        throw "AzureSignTool --version failed with exit code $LASTEXITCODE."
    }

    Write-Host ""
    Write-Host "Dry run: skipping EXE/MSI Key Vault signing."
    exit 0
}

$work = Join-Path $env:RUNNER_TEMP "itw-signed-win-dist"
$chainWork = Join-Path $env:RUNNER_TEMP "itw-signing-certchain"
Remove-Item $work, $chainWork -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $work, $chainWork | Out-Null

$additionalCertArgs = Get-AdditionalCertificateArgs -PemContent $env:JNLP_JCA_SIGN_CERTCHAIN -WorkDir $chainWork
Write-Host "Using $($additionalCertArgs.Count / 2) certificate chain file(s) from JNLP_JCA_SIGN_CERTCHAIN."

if ($env:KEYVAULT_URL -notmatch '^https?://') {
    throw "KEYVAULT_URL must include the scheme, e.g. https://your-vault.vault.azure.net/"
}

$vaultAuthArgs = @(
    '--azure-key-vault-url', $env:KEYVAULT_URL.TrimEnd('/')
    '--azure-key-vault-client-id', $env:AZURE_CLIENT_ID
    '--azure-key-vault-tenant-id', $env:AZURE_TENANT_ID
    '--azure-key-vault-client-secret', $env:AZURE_CLIENT_SECRET
    '--azure-key-vault-certificate', $env:JNLP_JCA_SIGN_ALIAS
)

Write-Host "Key Vault URL:       $($env:KEYVAULT_URL.TrimEnd('/'))"
Write-Host "Key Vault cert name: $($env:JNLP_JCA_SIGN_ALIAS)"
Write-Host "Timestamp URL:       $($env:JNLP_JCA_TSA_URL)"
Write-Host "Distribution ZIP:    $($zip.FullName)"
Write-Host "MSI:                 $($msiFiles[0].FullName)"

Expand-Archive -LiteralPath $zip.FullName -DestinationPath $work
$exeFiles = @(Get-ChildItem $work -Recurse -Filter '*.exe')
if ($exeFiles.Count -eq 0) {
    throw "No EXE files found inside $($zip.FullName)."
}

Write-Host "Found $($exeFiles.Count) EXE file(s) to sign under $work"
foreach ($exe in $exeFiles) {
    Invoke-AzureSignToolSign `
        -VaultAuthArgs $vaultAuthArgs `
        -AdditionalCertArgs $additionalCertArgs `
        -TargetFile $exe.FullName `
        -Description 'IcedTea-Web' `
        -DescriptionUrl "https://github.com/$env:GITHUB_REPOSITORY"
}

Remove-Item $zip.FullName -Force
Compress-Archive -Path (Join-Path $work "*") -DestinationPath $zip.FullName
Write-Host "Repacked signed Windows distribution ZIP: $($zip.FullName)"

foreach ($msi in $msiFiles) {
    Invoke-AzureSignToolSign `
        -VaultAuthArgs $vaultAuthArgs `
        -AdditionalCertArgs $additionalCertArgs `
        -TargetFile $msi.FullName `
        -Description 'IcedTea-Web Installer' `
        -DescriptionUrl "https://github.com/$env:GITHUB_REPOSITORY"
}
