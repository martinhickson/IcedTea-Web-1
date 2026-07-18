#Requires -Version 5.1
param(
    [Parameter(Mandatory = $true)][string]$Sample
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$SampleRoot = Join-Path $Root $Sample
$SignDir = Join-Path $SampleRoot "signing"
$Ks = Join-Path $SignDir "$Sample.jks"
$Crt = Join-Path $SignDir "$Sample.crt"
$Alias = $Sample
$StorePass = if ($env:ITW_SAMPLE_STORE_PASS) { $env:ITW_SAMPLE_STORE_PASS } else { "changeit" }
$Dname = "CN=IcedTea-Web Sample $Sample Signer, OU=Development, O=IcedTea-Web, C=US"

New-Item -ItemType Directory -Force -Path $SignDir | Out-Null

$keytool = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\keytool.exe"))) {
    Join-Path $env:JAVA_HOME "bin\keytool.exe"
} else {
    "keytool"
}

if ((Test-Path $Ks) -and (Test-Path $Crt)) {
    & $keytool -list -alias $Alias -keystore $Ks -storepass $StorePass 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) {
        exit 0
    }
    Write-Host "Keystore alias '$Alias' missing; regenerating $Ks"
    Remove-Item -Force $Ks, $Crt
}

$genArgs = @(
    "-genkeypair",
    "-alias", $Alias,
    "-keyalg", "RSA",
    "-keysize", "2048",
    "-validity", "8250",
    "-dname", $Dname,
    "-keystore", $Ks,
    "-storepass", $StorePass,
    "-keypass", $StorePass
)

& $keytool @genArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

& $keytool -exportcert -rfc -alias $Alias -keystore $Ks -storepass $StorePass -file $Crt
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "Generated signing keystore: $Ks"
