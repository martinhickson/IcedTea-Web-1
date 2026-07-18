# Build and jarsign the JDK-17 sample JNLP app for itw-autodetect-it.
$ErrorActionPreference = "Stop"

$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Target = Join-Path $Root "target"
$Apps = Join-Path $Root "src\test\java\net\sourceforge\icedteaweb\autodetect\it\apps"
$Res = Join-Path $Root "src\test\resources\jnlp-samples"
$SignDir = Join-Path $Target "test-signing"
$Ks = Join-Path $SignDir "itw-test.jks"
$Crt = Join-Path $SignDir "itw-test.crt"
$Alias = "itw-it-test"
$StorePass = if ($env:ITW_TEST_STORE_PASS) { $env:ITW_TEST_STORE_PASS } else { "changeit" }

$Jdk17Home = if ($env:ITW_JDK17_HOME) {
    $env:ITW_JDK17_HOME
} elseif ($env:JDK17_HOME) {
    $env:JDK17_HOME
} else {
    "C:\Program Files\Amazon Corretto\jdk17.0.16_8"
}

$Javac = Join-Path $Jdk17Home "bin\javac.exe"
$Jar = Join-Path $Jdk17Home "bin\jar.exe"
$Jarsigner = Join-Path $Jdk17Home "bin\jarsigner.exe"
$Keytool = Join-Path $Jdk17Home "bin\keytool.exe"

if (-not (Test-Path -LiteralPath $Javac)) {
    Write-Warning "JDK 17 javac not found: $Javac (set ITW_JDK17_HOME). Skipping sample build; IT will be skipped."
    exit 0
}

New-Item -ItemType Directory -Force -Path $SignDir, (Join-Path $Target "test-app-classes"), (Join-Path $Target "test-jnlp-samples") | Out-Null

if (-not ((Test-Path -LiteralPath $Ks) -and (Test-Path -LiteralPath $Crt))) {
    $dname = "CN=IcedTea-Web IT Test Signer, OU=Development, O=IcedTea-Web, C=US"
    & $Keytool -genkeypair `
        -alias $Alias `
        -keyalg RSA -keysize 2048 -validity 8250 `
        -dname $dname `
        -keystore $Ks -storepass $StorePass -keypass $StorePass
    if ($LASTEXITCODE -ne 0) { throw "keytool -genkeypair failed" }
    & $Keytool -exportcert -rfc -alias $Alias -keystore $Ks -storepass $StorePass -file $Crt
    if ($LASTEXITCODE -ne 0) { throw "keytool -exportcert failed" }
    Write-Host "Generated test signing keystore: $Ks"
}

$SampleDir = "java17-app"
$Classes = Join-Path $Target "test-app-classes\$SampleDir"
$Out = Join-Path $Target "test-jnlp-samples\$SampleDir"
New-Item -ItemType Directory -Force -Path $Classes, $Out | Out-Null

$MainSrc = Join-Path $Apps "Java17HoldJnlpMain.java"
$MainClass = "net.sourceforge.icedteaweb.autodetect.it.apps.Java17HoldJnlpMain"
$AppTitle = "ITW Java 17 Autodetect Sample"

& $Javac -d $Classes $MainSrc
if ($LASTEXITCODE -ne 0) { throw "javac failed for $SampleDir" }

$Manifest = Join-Path $Classes "MANIFEST.MF"
@(
    "Manifest-Version: 1.0"
    "Main-Class: $MainClass"
    "Application-Name: $AppTitle"
    "Permissions: all-permissions"
    "Codebase: *"
    "Application-Library-Allowable-Codebase: *"
    ""
) | Set-Content -LiteralPath $Manifest -Encoding ascii

$JarPath = Join-Path $Out "app.jar"
& $Jar cfm $JarPath $Manifest -C $Classes .
if ($LASTEXITCODE -ne 0) { throw "jar failed for $SampleDir" }

& $Jarsigner -keystore $Ks -storepass $StorePass -keypass $StorePass `
    -digestalg SHA-256 -sigalg SHA256withRSA `
    $JarPath $Alias
if ($LASTEXITCODE -ne 0) { throw "jarsigner failed for $SampleDir" }

Copy-Item -LiteralPath (Join-Path $Res "$SampleDir\app.jnlp") -Destination (Join-Path $Out "app.jnlp") -Force
Write-Host "Built and signed $JarPath with $Jdk17Home"

# Import signer into the isolated ITW test trust stores when ITW_TEST_HOME is set.
$TestHome = if ($env:ITW_TEST_HOME) { $env:ITW_TEST_HOME } else { Join-Path $Target "itw-test-home" }
$SecurityDir = Join-Path $TestHome ".config\icedtea-web\security"
New-Item -ItemType Directory -Force -Path $SecurityDir | Out-Null
foreach ($storeName in @("trusted.cacerts", "trusted.certs")) {
    $store = Join-Path $SecurityDir $storeName
    if (-not (Test-Path -LiteralPath $store)) {
        & $Keytool -genkeypair -alias bootstrap -dname "CN=bootstrap" -validity 1 `
            -keystore $store -storepass $StorePass -keypass $StorePass 2>$null | Out-Null
        & $Keytool -delete -alias bootstrap -keystore $store -storepass $StorePass 2>$null | Out-Null
    }
    & $Keytool -list -alias "itw-it-test-signer" -keystore $store -storepass $StorePass 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) {
        & $Keytool -delete -alias "itw-it-test-signer" -keystore $store -storepass $StorePass 2>$null | Out-Null
    }
    & $Keytool -importcert -noprompt -alias "itw-it-test-signer" -file $Crt `
        -keystore $store -storepass $StorePass
    if ($LASTEXITCODE -ne 0) { throw "Failed to import test cert into $store" }
}
Write-Host "Installed test signer into $SecurityDir"
