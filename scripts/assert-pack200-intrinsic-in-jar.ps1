# Fail fast when Pack200's intrinsic.properties is missing from a jar.
# PropMap.<clinit> throws "intrinsic.properties cannot be loaded" at runtime without it.
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$JarFile,

    [Parameter(Position = 1)]
    [string]$Label
)

$ErrorActionPreference = 'Stop'
$ResourcePath = 'io/pack200/pack/intrinsic.properties'

if ([string]::IsNullOrWhiteSpace($Label)) {
    $Label = [IO.Path]::GetFileName($JarFile)
}

if (-not (Test-Path -LiteralPath $JarFile)) {
    Write-Error "ERROR [$Label]: jar not found: $JarFile"
    exit 1
}

$jarExe = 'jar'
if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    $candidate = Join-Path $env:JAVA_HOME 'bin\jar.exe'
    if (Test-Path -LiteralPath $candidate) {
        $jarExe = $candidate
    }
}

$list = & $jarExe tf $JarFile
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

if ($list -contains $ResourcePath) {
    Write-Host "OK [$Label]: $ResourcePath present in $([IO.Path]::GetFileName($JarFile))"
    exit 0
}

Write-Error @(
    "ERROR [$Label]: $ResourcePath missing from $JarFile"
    'Pack200 will fail at runtime with: intrinsic.properties cannot be loaded'
) -join [Environment]::NewLine
exit 1
