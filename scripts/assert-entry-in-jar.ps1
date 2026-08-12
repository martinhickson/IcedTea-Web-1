# Fail if a required entry path is missing from a jar (e.g. shaded uber contents).
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$JarFile,

    [Parameter(Mandatory = $true, Position = 1)]
    [string]$EntryPath,

    [Parameter(Position = 2)]
    [string]$Label
)

$ErrorActionPreference = 'Stop'

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

$list = @(& $jarExe tf $JarFile | ForEach-Object { $_.TrimEnd("`r") })
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

if ($list -contains $EntryPath) {
    Write-Host "OK [$Label]: $EntryPath present in $([IO.Path]::GetFileName($JarFile))"
    exit 0
}

Write-Error "ERROR [$Label]: $EntryPath missing from $JarFile"
exit 1
