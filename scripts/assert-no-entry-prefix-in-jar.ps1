# Fail if any jar entry starts with the given prefix (e.g. unshaded org/slf4j/).
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$JarFile,

    [Parameter(Mandatory = $true, Position = 1)]
    [string]$EntryPrefix,

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

$hits = @($list | Where-Object { $_.StartsWith($EntryPrefix) })
if ($hits.Count -eq 0) {
    Write-Host "OK [$Label]: no ${EntryPrefix}* in $([IO.Path]::GetFileName($JarFile))"
    exit 0
}

Write-Error "ERROR [$Label]: unexpected ${EntryPrefix}* in $JarFile"
$hits | Select-Object -First 20 | ForEach-Object { Write-Host $_ }
exit 1
