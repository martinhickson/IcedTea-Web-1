# Fast probe runner: Groovy 5.0.8 + icedtea-web (no Maven Surefire).
param(
    [switch]$CompileJava,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$ScriptArgs
)
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$bash = Join-Path $Root 'scripts/run-itw-groovy.sh'
$gitBash = (Get-Command bash.exe -ErrorAction SilentlyContinue)?.Source
if (-not $gitBash) {
    $candidates = @(
        'C:\Program Files\Git\bin\bash.exe',
        'C:\Program Files\Git\usr\bin\bash.exe'
    )
    foreach ($c in $candidates) {
        if (Test-Path $c) { $gitBash = $c; break }
    }
}
if (-not $gitBash) { throw 'Git Bash (bash.exe) required to run scripts/run-itw-groovy.sh' }

$argsList = @()
if ($CompileJava) { $argsList += '--compile-java' }
$argsList += $ScriptArgs
& $gitBash -lc ("cd `"$Root`" && ./scripts/run-itw-groovy.sh " + ($argsList | ForEach-Object { "'$_'" }) -join ' ')
exit $LASTEXITCODE
