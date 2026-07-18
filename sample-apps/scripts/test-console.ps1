#Requires -Version 5.1
param(
    [string]$Sample = 'console'
)

$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $PSScriptRoot
$Port = if ($env:ITW_SAMPLE_PORT) { $env:ITW_SAMPLE_PORT } else { '4200' }
$JnlpUrl = "http://127.0.0.1:$Port/jnlp/$Sample/app.jnlp"
$Javawsc = if ($env:ITW_JAVAWSC_BIN) { $env:ITW_JAVAWSC_BIN } else { 'C:\Program Files\IcedTeaWeb\WebStart\bin\javawsc.exe' }
$LogDir = Join-Path $env:TEMP 'sample-apps-test'
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
$OutLog = Join-Path $LogDir "$Sample.out.log"
$ErrLog = Join-Path $LogDir "$Sample.err.log"

$jnlp = Invoke-WebRequest -Uri $JnlpUrl -UseBasicParsing -TimeoutSec 15
if ($jnlp.StatusCode -ne 200) { throw "JNLP not reachable: $JnlpUrl" }
Write-Host "JNLP OK ($($jnlp.StatusCode))"

try {
    Invoke-WebRequest -Uri "http://127.0.0.1:$Port/favicon.ico" -UseBasicParsing -TimeoutSec 5 | Out-Null
    Write-Host 'Shell favicon: present (Angular public/)'
} catch [System.Net.WebException] {
    Write-Host 'Shell favicon: not served at root'
}

$faviconJnlp = "http://127.0.0.1:$Port/jnlp/$Sample/favicon.ico"
try {
    Invoke-WebRequest -Uri $faviconJnlp -UseBasicParsing -TimeoutSec 5 | Out-Null
    if ($Sample -eq 'console') { throw "Expected no favicon at $faviconJnlp" }
} catch [System.Net.WebException] {
    if ($_.Exception.Response.StatusCode.value__ -eq 404) {
        Write-Host "JNLP tree favicon 404 OK for $Sample"
    } else {
        throw
    }
}

Write-Host "Launching via $Javawsc"
$p = Start-Process -FilePath $Javawsc -ArgumentList @('-verbose','-Xtrustall','--auto-accept-https-certificate=true',$JnlpUrl) -Wait -PassThru -RedirectStandardOutput $OutLog -RedirectStandardError $ErrLog -NoNewWindow
$combined = (Get-Content $OutLog -Raw) + "`n" + (Get-Content $ErrLog -Raw)

$marker = if ($Sample -eq 'console') { 'ITW_SAMPLE_APP_2_SUCCESS' } else { 'ITW_SAMPLE_APP_1_SUCCESS' }
$faviconFnf = ([regex]::Matches((Get-Content $ErrLog -Raw), 'FileNotFoundException.*favicon')).Count
$faviconStacks = ([regex]::Matches((Get-Content $ErrLog -Raw), '(?s)FileNotFoundException[^\n]*favicon[^\n]*\r?\n\s+at ')).Count

Write-Host "SUCCESS marker: $($combined -match $marker)"
Write-Host "Favicon FileNotFoundException (stderr): $faviconFnf"
Write-Host "Favicon stack traces (stderr): $faviconStacks"
Write-Host "javawsc exit: $($p.ExitCode)"

if ($combined -notmatch $marker) { exit 1 }
if ($Sample -eq 'console' -and ($faviconFnf -gt 0 -or $faviconStacks -gt 0)) { exit 2 }
if ($p.ExitCode -ne 0) { exit $p.ExitCode }
Write-Host 'TEST PASSED'
