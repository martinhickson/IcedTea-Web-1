# Run Pack200 unpack integration tests (JDK 17 + io.github.martinhickson:pack200), matching CI ci-pack200-unpack profile.
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

function Write-Detail { param([string]$Message) Write-Host "  $Message" }

. (Join-Path $Root '.powershell\workflows\ensure-pack200.ps1')
. (Join-Path $Root '.powershell\workflows\git-bash.ps1')

if (-not $env:JAVA_HOME) {
    throw 'Set JAVA_HOME to JDK 17 before running Pack200 unpack tests.'
}

$major = (& "$env:JAVA_HOME\bin\java" -version 2>&1 | Select-String -Pattern 'version "(\d+)' | ForEach-Object { $_.Matches[0].Groups[1].Value })
if ([int]$major -lt 17) {
    throw "Pack200 unpack tests require JDK 17 or newer (JAVA_HOME is Java $major)."
}

Ensure-Pack200MavenDependency
$gitBash = Ensure-GitBashOnPath -WriteDetail ${function:Write-Detail}
$bashForMaven = ($gitBash -replace '\\', '/')
$mvnSettings = Join-Path $Root '.powershell\workflows\maven-settings.xml'

Write-Host "Running Pack200 unpack tests (profile ci-pack200-unpack) with bash: $gitBash"
& mvn -s $mvnSettings test -pl icedtea-web -am -Pci-pack200-unpack `
  "-Djdk11.home=$env:JAVA_HOME" `
  "-Dbash.executable=$bashForMaven"
exit $LASTEXITCODE
