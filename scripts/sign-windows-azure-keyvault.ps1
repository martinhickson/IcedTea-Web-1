$ErrorActionPreference = "Stop"

$required = @(
    "KEYVAULT_URL",
    "AZURE_CLIENT_ID",
    "AZURE_TENANT_ID",
    "AZURE_CLIENT_SECRET",
    "JNLP_JCA_SIGN_ALIAS",
    "JNLP_JCA_TSA_URL",
    "JNLP_JCA_SIGN_CERTCHAIN"
)
$missing = $required | Where-Object { [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_)) }
if ($missing.Count -gt 0) {
    throw "Windows artifact signing is enabled, but required signing environment variables are missing: $($missing -join ', ')"
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

    $args = @()
    $index = 0
    foreach ($match in $matches) {
        $certPath = Join-Path $WorkDir "signing-chain-$index.pem"
        [System.IO.File]::WriteAllText($certPath, $match.Value.TrimEnd())
        $args += "--additional-certificates"
        $args += $certPath
        $index++
    }
    return ,$args
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

$work = Join-Path $env:RUNNER_TEMP "itw-signed-win-dist"
$chainWork = Join-Path $env:RUNNER_TEMP "itw-signing-certchain"
Remove-Item $work, $chainWork -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $work, $chainWork | Out-Null

$additionalCertArgs = Get-AdditionalCertificateArgs -PemContent $env:JNLP_JCA_SIGN_CERTCHAIN -WorkDir $chainWork
Write-Host "Using $($additionalCertArgs.Count / 2) certificate chain file(s) from JNLP_JCA_SIGN_CERTCHAIN."

$vaultAuthArgs = @(
    "--azure-key-vault-url", $env:KEYVAULT_URL
    "--azure-key-vault-client-id", $env:AZURE_CLIENT_ID
    "--azure-key-vault-tenant-id", $env:AZURE_TENANT_ID
    "--azure-key-vault-client-secret", $env:AZURE_CLIENT_SECRET
    "--azure-key-vault-certificate", $env:JNLP_JCA_SIGN_ALIAS
)

Expand-Archive -LiteralPath $zip.FullName -DestinationPath $work
$exeFiles = @(Get-ChildItem $work -Recurse -Filter "*.exe")
if ($exeFiles.Count -eq 0) {
    throw "No EXE files found inside $($zip.FullName)."
}

foreach ($exe in $exeFiles) {
    Write-Host "Signing EXE: $($exe.FullName)"
    AzureSignTool sign `
        @vaultAuthArgs `
        @additionalCertArgs `
        --file-digest sha256 `
        --timestamp-rfc3161 $env:JNLP_JCA_TSA_URL `
        --timestamp-digest sha256 `
        --description "IcedTea-Web" `
        --description-url "https://github.com/$env:GITHUB_REPOSITORY" `
        $exe.FullName
}

Remove-Item $zip.FullName -Force
Compress-Archive -Path (Join-Path $work "*") -DestinationPath $zip.FullName
Write-Host "Repacked signed Windows distribution ZIP: $($zip.FullName)"

foreach ($msi in $msiFiles) {
    Write-Host "Signing MSI: $($msi.FullName)"
    AzureSignTool sign `
        @vaultAuthArgs `
        @additionalCertArgs `
        --file-digest sha256 `
        --timestamp-rfc3161 $env:JNLP_JCA_TSA_URL `
        --timestamp-digest sha256 `
        --description "IcedTea-Web Installer" `
        --description-url "https://github.com/$env:GITHUB_REPOSITORY" `
        $msi.FullName
}
