#Requires -Version 5.1
param(
    [Parameter(Mandatory = $true)][string]$Sample
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$SampleRoot = Join-Path $Root $Sample
$JavaSrc = Join-Path $SampleRoot "java\src"
$OutDir = Join-Path $Root "public\jnlp\$Sample"
$Manifest = Join-Path $SampleRoot "java\META-INF\MANIFEST.MF"
$SignScript = Join-Path $Root "scripts\generate-signing.ps1"
$Ks = Join-Path $SampleRoot "signing\$Sample.jks"
$Alias = $Sample
$StorePass = if ($env:ITW_SAMPLE_STORE_PASS) { $env:ITW_SAMPLE_STORE_PASS } else { "changeit" }
$Port = if ($env:ITW_SAMPLE_PORT) { $env:ITW_SAMPLE_PORT } else { "4200" }
$Codebase = "http://127.0.0.1:$Port/jnlp/$Sample/"

function Resolve-Jdk17Home {
    if ($env:JDK17_HOME -and (Test-Path (Join-Path $env:JDK17_HOME "bin\javac.exe"))) {
        return $env:JDK17_HOME
    }
    $candidates = @(
        "C:\Program Files\Amazon Corretto\jdk17*",
        "C:\Program Files\Eclipse Adoptium\jdk-17*",
        "C:\Program Files\Java\jdk-17*",
        "C:\Program Files\Microsoft\jdk-17*"
    )
    foreach ($pattern in $candidates) {
        $match = Get-Item $pattern -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
        if ($match -and (Test-Path (Join-Path $match.FullName "bin\javac.exe"))) {
            return $match.FullName
        }
    }
    if ($env:JAVA_HOME) {
        $version = & (Join-Path $env:JAVA_HOME "bin\java.exe") -version 2>&1 | Out-String
        if ($version -match 'version "17') {
            return $env:JAVA_HOME
        }
    }
    throw "JDK 17 not found. Set JDK17_HOME or install Amazon Corretto / Temurin 17."
}

& $SignScript -Sample $Sample
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$JdkHome = Resolve-Jdk17Home
$Javac = Join-Path $JdkHome "bin\javac.exe"
$Jar = Join-Path $JdkHome "bin\jar.exe"
$Jarsigner = Join-Path $JdkHome "bin\jarsigner.exe"

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$BuildDir = Join-Path $SampleRoot "java\build"
if (Test-Path $BuildDir) {
    Remove-Item -Recurse -Force $BuildDir
}
New-Item -ItemType Directory -Force -Path $BuildDir | Out-Null

Write-Host "Compiling $Sample with $JdkHome"
& $Javac --release 17 -encoding UTF-8 -d $BuildDir (Get-ChildItem -Recurse -Filter *.java $JavaSrc | ForEach-Object { $_.FullName })
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$AppJar = Join-Path $OutDir "app.jar"
if (Test-Path $AppJar) { Remove-Item -Force $AppJar }
& $Jar --create --file $AppJar --manifest $Manifest -C $BuildDir .
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "Signing $AppJar"
& $Jarsigner -keystore $Ks -storepass $StorePass -keypass $StorePass -digestalg SHA-256 -sigalg SHA256withRSA $AppJar $Alias
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$env:ITW_SAMPLE = $Sample
node (Join-Path $Root "scripts\write-jnlp.mjs") $Sample
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "Built and signed $AppJar"
Write-Host "Launch URL: ${Codebase}app.jnlp"
