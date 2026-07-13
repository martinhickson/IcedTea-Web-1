$ErrorActionPreference = "Stop"

$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$DistBin = Join-Path $Root "icedtea-web-distribution\target\dist\icedtea-web-2.0.1-SNAPSHOT\bin"
$Javaws = Join-Path $DistBin "javaws.exe"
$Settings = Join-Path $DistBin "itweb-settings.exe"
$HoldSrc = Join-Path $Root "itw-assertj-it\src\test\java\net\sourceforge\icedteaweb\it\apps\HeadlessHoldJnlpMain.java"
$HoldClasses = Join-Path $env:TEMP "itw-hold-classes"
$HoldJar = Join-Path $env:TEMP "itw-hold-app.jar"
$WorkDir = Join-Path $env:TEMP ("itw-running-apps-shot." + [Guid]::NewGuid().ToString("N"))
$WebRoot = Join-Path $WorkDir "web"
$ShotDir = Join-Path $Root "icedtea-web-distribution\target\smoke-artifacts"
$ShotPath = Join-Path $ShotDir "running-apps-tab.png"
$HandoffExtract = Join-Path $ShotDir "handoff-log-excerpt.txt"

New-Item -ItemType Directory -Force -Path $WorkDir, $WebRoot, $ShotDir, $HoldClasses | Out-Null

function Stop-All {
    Get-Process javaws,itweb-settings,java,python -ErrorAction SilentlyContinue | ForEach-Object {
        $proc = $_
        $shouldStop = $false
        if ($proc.ProcessName -eq "python") {
            $shouldStop = $true
        } elseif ($proc.Path -and ($proc.Path -like "*$Root*" -or $proc.Path -like "*icedtea-web*")) {
            $shouldStop = $true
        } else {
            try {
                $cmd = (Get-CimInstance Win32_Process -Filter "ProcessId=$($proc.Id)" -ErrorAction Stop).CommandLine
                if ($cmd -and ($cmd -like "*icedtea-web-uber*" -or $cmd -like "*hold-test.jnlp*" -or $cmd -like "*headless-test.jnlp*")) {
                    $shouldStop = $true
                }
            } catch {
            }
        }
        if ($shouldStop) {
            Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
        }
    }
}

function Write-JnlpNoBom {
    param([string]$Path, [string]$Content)
    [System.IO.File]::WriteAllText($Path, $Content, [System.Text.UTF8Encoding]::new($false))
}

function Get-FreePort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Parse("127.0.0.1"), 0)
    $listener.Start()
    try { return $listener.LocalEndpoint.Port } finally { $listener.Stop() }
}

function Select-RunningAppsTab {
    Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class ItwWin32 {
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
}
"@
    $cp = Get-Process | Where-Object { $_.MainWindowTitle -eq "IcedTea-Web Control Panel" } | Select-Object -First 1
    if ($null -eq $cp) { throw "Control panel window not found" }
    [void][ItwWin32]::ShowWindow($cp.MainWindowHandle, 9)
    [void][ItwWin32]::SetForegroundWindow($cp.MainWindowHandle)
    Start-Sleep -Milliseconds 500
    Add-Type -AssemblyName System.Windows.Forms
    # About is selected by default; Running Apps is the 8th entry (index 7).
    [System.Windows.Forms.SendKeys]::SendWait("{DOWN 7}{ENTER}")
    Start-Sleep -Milliseconds 800
}

function Save-Screenshot {
    param([string]$Path)
    Add-Type -AssemblyName System.Windows.Forms,System.Drawing
    $bounds = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
    $bmp = New-Object System.Drawing.Bitmap $bounds.Width, $bounds.Height
    $gfx = [System.Drawing.Graphics]::FromImage($bmp)
    $gfx.CopyFromScreen($bounds.Location, [System.Drawing.Point]::Empty, $bounds.Size)
    $bmp.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
    $gfx.Dispose(); $bmp.Dispose()
}

try {
    Stop-All
    $javaExe = Join-Path $env:JAVA_HOME "bin\javac.exe"
    if (-not (Test-Path $javaExe)) {
        $javaExe = "javac"
    }
    & $javaExe -d $HoldClasses $HoldSrc
    if ($LASTEXITCODE -ne 0) { throw "javac failed for HeadlessHoldJnlpMain" }
    $jarExe = Join-Path $env:JAVA_HOME "bin\jar.exe"
    if (-not (Test-Path $jarExe)) { $jarExe = "jar" }
    if (Test-Path $HoldJar) { Remove-Item $HoldJar -Force }
    & $jarExe cfe $HoldJar net.sourceforge.icedteaweb.it.apps.HeadlessHoldJnlpMain -C $HoldClasses .
    if ($LASTEXITCODE -ne 0) { throw "jar failed for hold app" }
    $ks = Join-Path $env:TEMP "itw-test-keystore.jks"
    if (-not (Test-Path $ks)) {
        & keytool -genkeypair -alias itwtest -keyalg RSA -keysize 2048 -validity 3650 `
            -keystore $ks -storepass changeit -keypass changeit -dname "CN=ITW Test"
    }
    & jarsigner -keystore $ks -storepass changeit $HoldJar itwtest | Out-Null

    Copy-Item $HoldJar (Join-Path $WebRoot "hold-app.jar") -Force
    $Port = Get-FreePort
    $Codebase = "http://127.0.0.1:$Port/"
    $JnlpUrl = "${Codebase}hold-test.jnlp"
    $ConfigHome = Join-Path $WorkDir "xdg-config"
    New-Item -ItemType Directory -Force -Path (Join-Path $ConfigHome "icedtea-web\log") | Out-Null
    $env:XDG_CONFIG_HOME = $ConfigHome

    Write-JnlpNoBom (Join-Path $WebRoot "hold-test.jnlp") @"
<?xml version="1.0" encoding="UTF-8"?>
<jnlp spec="1.0+" codebase="$Codebase" href="hold-test.jnlp">
  <information>
    <title>ITW hold app for Running Apps screenshot</title>
    <vendor>IcedTea-Web</vendor>
  </information>
  <security><all-permissions/></security>
  <resources>
    <property name="itw.test.hold.seconds" value="300"/>
    <j2se version="1.8+"/>
    <jar href="hold-app.jar" main="true"/>
  </resources>
  <application-desc main-class="net.sourceforge.icedteaweb.it.apps.HeadlessHoldJnlpMain"/>
</jnlp>
"@

    $server = Start-Process python -ArgumentList @("-m", "http.server", "$Port", "--bind", "127.0.0.1") `
        -WorkingDirectory $WebRoot -PassThru -WindowStyle Hidden
    Start-Sleep -Seconds 2

    Write-Host "Launching held JNLP: $JnlpUrl"
    $launcher = Start-Process $Javaws -ArgumentList @(
        "-headless", "-verbose", "-Xtrustall", "--auto-accept-https-certificate=true", "-Xnofork", $JnlpUrl
    ) -PassThru -WindowStyle Hidden
    Start-Sleep -Seconds 12

    $mainHandoff = Get-ChildItem (Join-Path $ConfigHome "icedtea-web\log") -Filter "itw-javantx-*.log" |
        Where-Object { $_.Name -notlike "*.err.log" -and $_.Name -notlike "*-prelaunch.log" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($null -eq $mainHandoff) { throw "Main handoff log not found" }
    $childPid = [int](Select-String -Path $mainHandoff.FullName -Pattern "^Child process ID \(handed to\): (\d+)$" |
        ForEach-Object { $_.Matches[0].Groups[1].Value } | Select-Object -Last 1)
    $childAlive = $null -ne (Get-Process -Id $childPid -ErrorAction SilentlyContinue)
    Write-Host "Main handoff child PID: $childPid (alive=$childAlive)"
    if (-not $childAlive) { throw "Held Java child process $childPid is not running" }

    Write-Host "Opening control panel..."
    $settings = Start-Process $Settings -PassThru -WindowStyle Normal
    Start-Sleep -Seconds 6
    Select-RunningAppsTab
    Start-Sleep -Seconds 3
    Save-Screenshot $ShotPath
    Write-Host "Screenshot saved: $ShotPath"

    $logsDir = Join-Path $ConfigHome "icedtea-web\log"
    $handoffLines = @()
    $handoffLogs = Get-ChildItem $logsDir -Filter "itw-javantx-*.log" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike "*.err.log" -and (Select-String -Path $_.FullName -Pattern "Handoff status: SUCCESS" -Quiet) } |
        Sort-Object LastWriteTime
    foreach ($log in $handoffLogs) {
        $handoffLines += "===== $($log.FullName) ====="
        $handoffLines += Get-Content $log.FullName
        $handoffLines += ""
    }
    $handoffLines | Set-Content $HandoffExtract -Encoding UTF8
    Write-Host "Handoff excerpt saved: $HandoffExtract"
}
finally {
    Stop-All
}
