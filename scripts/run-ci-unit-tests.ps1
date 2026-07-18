# Run the same unit-test profile as GitHub Actions CI (requires JDK 11+ and Maven).
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

function Write-Detail { param([string]$Message) Write-Host "  $Message" }

. (Join-Path $Root '.powershell\workflows\git-bash.ps1')

if (-not $env:JAVA_HOME) {
    throw 'Set JAVA_HOME to JDK 11 (for example Amazon Corretto 11) before running tests.'
}

$gitBash = Ensure-GitBashOnPath -WriteDetail ${function:Write-Detail}
$bashForMaven = ($gitBash -replace '\\', '/')
$mvnSettings = Join-Path $Root '.powershell\workflows\maven-settings.xml'

Write-Host "Running CI unit tests (profile ci) with bash: $gitBash"
& mvn -s $mvnSettings test -pl icedtea-web -am -Pci `
  "-Djdk11.home=$env:JAVA_HOME" `
  "-Dbash.executable=$bashForMaven"
exit $LASTEXITCODE
