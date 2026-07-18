#Requires -Version 5.1
param(
    [string]$Sample = 'console'
)

$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $PSScriptRoot
$Port = if ($env:ITW_SAMPLE_PORT) { $env:ITW_SAMPLE_PORT } else { '4200' }
$JnlpUrl = "http://127.0.0.1:$Port/jnlp/$Sample/app.jnlp"

$Javawsc = if ($env:ITW_JAVAWSC_BIN) {
    $env:ITW_JAVAWSC_BIN
} else {
    'C:\Program Files\IcedTeaWeb\WebStart\bin\javawsc.exe'
}

if (-not (Test-Path $Javawsc -PathType Leaf)) {
    throw "javawsc not found at: $Javawsc. Set ITW_JAVAWSC_BIN or install IcedTea-Web."
}

Write-Host "Launching console JNLP with javawsc (.NET launcher)"
Write-Host "  $JnlpUrl"
& $Javawsc -verbose -Xtrustall --auto-accept-https-certificate=true $JnlpUrl
exit $LASTEXITCODE
