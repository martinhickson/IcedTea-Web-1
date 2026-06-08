$ErrorActionPreference = "Stop"

$required = @(
    "KEYVAULT_URL",
    "JNLP_JCA_SIGN_ALIAS",
    "JNLP_JCA_TSA_URL"
)
$missing = $required | Where-Object { [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_)) }
if ($missing.Count -gt 0) {
    throw "Windows artifact signing is enabled, but required signing environment variables are missing: $($missing -join ', ')"
}

if (-not (Get-Command AzureSignTool -ErrorAction SilentlyContinue)) {
    throw "AzureSignTool is not available on PATH."
}
if (-not (Get-Command az -ErrorAction SilentlyContinue)) {
    throw "Azure CLI is not available on PATH."
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

$accessToken = az account get-access-token `
    --resource https://vault.azure.net `
    --query accessToken `
    --output tsv
if ([string]::IsNullOrWhiteSpace($accessToken)) {
    throw "Azure CLI did not return a Key Vault access token."
}

$work = Join-Path $env:RUNNER_TEMP "itw-signed-win-dist"
Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $work | Out-Null

Expand-Archive -LiteralPath $zip.FullName -DestinationPath $work
$exeFiles = @(Get-ChildItem $work -Recurse -Filter "*.exe")
if ($exeFiles.Count -eq 0) {
    throw "No EXE files found inside $($zip.FullName)."
}

foreach ($exe in $exeFiles) {
    Write-Host "Signing EXE: $($exe.FullName)"
    AzureSignTool sign `
        --azure-key-vault-url $env:KEYVAULT_URL `
        --azure-key-vault-certificate $env:JNLP_JCA_SIGN_ALIAS `
        --azure-key-vault-accesstoken $accessToken `
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
        --azure-key-vault-url $env:KEYVAULT_URL `
        --azure-key-vault-certificate $env:JNLP_JCA_SIGN_ALIAS `
        --azure-key-vault-accesstoken $accessToken `
        --file-digest sha256 `
        --timestamp-rfc3161 $env:JNLP_JCA_TSA_URL `
        --timestamp-digest sha256 `
        --description "IcedTea-Web Installer" `
        --description-url "https://github.com/$env:GITHUB_REPOSITORY" `
        $msi.FullName
}
