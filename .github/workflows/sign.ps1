param(
    [ValidateSet('Distribution', 'Msi', 'All')]
    [string]$Phase = 'All'
)

$ErrorActionPreference = 'Stop'

$jenkinsSign = Join-Path $PSScriptRoot '..\..\.jenkins\workflows\sign.ps1'
if (-not (Test-Path -LiteralPath $jenkinsSign)) {
    throw "Signing script not found: $jenkinsSign"
}

& $jenkinsSign -Phase $Phase
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
