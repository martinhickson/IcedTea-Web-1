# Visual UX smoke test for Running Apps button gating and Java console controls.
param(
    [string]$DistDir = "",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if ([string]::IsNullOrWhiteSpace($DistDir)) {
    $DistDir = Join-Path $Root "icedtea-web-distribution\target\dist\icedtea-web-2.0.1-SNAPSHOT"
}
$DistBin = Join-Path $DistDir "bin"
$Javaws = Join-Path $DistBin "javaws.exe"
$Settings = Join-Path $DistBin "itweb-settings.exe"
$HoldSrc = Join-Path $Root "itw-assertj-it\src\test\java\net\sourceforge\icedteaweb\it\apps\HeadlessHoldJnlpMain.java"
$HeadlessJar = Join-Path $Root "icedtea-web-integration\target\icedtea-web-integration-2.0.1-SNAPSHOT-headless-app.jar"
$ShotDir = Join-Path $Root "icedtea-web-distribution\target\smoke-artifacts"
$ReportPath = Join-Path $ShotDir "ux-smoke-report.txt"
$RunningAppsShot = Join-Path $ShotDir "ux-running-apps-default.png"
$RunningAppsFlipShot = Join-Path $ShotDir "ux-running-apps-trim-enabled.png"
$ConsoleShot = Join-Path $ShotDir "ux-java-console-default.png"
$ConsoleFlipShot = Join-Path $ShotDir "ux-java-console-gc-enabled.png"

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "=== $Message ==="
}

function Stop-TestProcesses {
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
                if ($cmd -and ($cmd -like "*icedtea-web-uber*" -or $cmd -like "*hold-test.jnlp*" -or $cmd -like "*headless-test.jnlp*" -or $cmd -like "*ux-smoke*")) {
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

function Write-DeploymentProperties {
    param(
        [string]$ConfigHome,
        [hashtable]$Extra = @{}
    )
    $deployDir = Join-Path $ConfigHome "icedtea-web"
    New-Item -ItemType Directory -Force -Path $deployDir, (Join-Path $deployDir "log") | Out-Null
    $path = Join-Path $deployDir "deployment.properties"
    $lines = @(
        "deployment.console.startup.mode=SHOW"
    )
    foreach ($key in ($Extra.Keys | Sort-Object)) {
        $lines += "$key=$($Extra[$key])"
    }
    [System.IO.File]::WriteAllLines($path, $lines, [System.Text.UTF8Encoding]::new($false))
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

function Save-WindowScreenshot {
    param(
        [IntPtr]$Handle,
        [string]$Path
    )
    Ensure-ItwWin32
    Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class ItwWinRect {
  [StructLayout(LayoutKind.Sequential)] public struct RECT {
    public int Left; public int Top; public int Right; public int Bottom;
  }
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);
}
"@
    $rect = New-Object ItwWinRect+RECT
    if (-not [ItwWinRect]::GetWindowRect($Handle, [ref]$rect)) {
        Save-Screenshot $Path
        return
    }
    $width = $rect.Right - $rect.Left
    $height = $rect.Bottom - $rect.Top
    if ($width -le 0 -or $height -le 0) {
        Save-Screenshot $Path
        return
    }
    Add-Type -AssemblyName System.Drawing
    $bmp = New-Object System.Drawing.Bitmap $width, $height
    $gfx = [System.Drawing.Graphics]::FromImage($bmp)
    $gfx.CopyFromScreen([System.Drawing.Point]::new($rect.Left, $rect.Top), [System.Drawing.Point]::Empty, [System.Drawing.Size]::new($width, $height))
    $bmp.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
    $gfx.Dispose(); $bmp.Dispose()
}

function Assert-DeploymentProperty {
    param(
        [string]$ConfigHome,
        [string]$Key,
        [string]$ExpectedValue,
        [string]$Message
    )
    $path = Join-Path $ConfigHome "icedtea-web\deployment.properties"
    $found = $false
    foreach ($line in [System.IO.File]::ReadAllLines($path)) {
        if ($line.Trim() -eq "$Key=$ExpectedValue") {
            $found = $true
            break
        }
    }
    Assert-True $found $Message
}

function Assert-ScreenshotCaptured {
    param(
        [string]$Path,
        [string]$Message,
        [int]$MinBytes = 5000
    )
    $ok = (Test-Path $Path) -and ((Get-Item $Path).Length -gt $MinBytes)
    Assert-True $ok $Message
    if ($ok) { Add-Report "Screenshot: $Path" }
}

function Ensure-ItwWin32 {
    if (-not ("ItwWin32" -as [type])) {
        Add-Type @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class ItwWin32 {
  public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr hWnd, StringBuilder lpString, int nMaxCount);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
}
"@
    }
}

function Get-WindowHandleByTitle {
    param(
        [string]$Title,
        [switch]$Exact
    )
    Ensure-ItwWin32
    $script:foundHandle = [IntPtr]::Zero
    [ItwWin32]::EnumWindows({
        param($hWnd, $lParam)
        if (-not [ItwWin32]::IsWindowVisible($hWnd)) { return $true }
        $sb = New-Object System.Text.StringBuilder 512
        [void][ItwWin32]::GetWindowText($hWnd, $sb, 512)
        $text = $sb.ToString()
        if ([string]::IsNullOrWhiteSpace($text)) { return $true }
        $isMatch = if ($Exact) { $text -eq $Title } else { $text -like "*$Title*" }
        if ($isMatch) {
            $script:foundHandle = $hWnd
            return $false
        }
        return $true
    }, [IntPtr]::Zero) | Out-Null
    return $script:foundHandle
}

function Wait-WindowHandleByTitle {
    param(
        [string]$Title,
        [int]$TimeoutSeconds = 30,
        [switch]$Exact
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $handle = Get-WindowHandleByTitle -Title $Title -Exact:$Exact
        if ($handle -ne [IntPtr]::Zero) { return $handle }
        Start-Sleep -Milliseconds 500
    }
    return [IntPtr]::Zero
}

function Get-VisibleUiLabelsInWindowHandle {
    param(
        [IntPtr]$Handle,
        [string[]]$Labels
    )
    Add-Type -AssemblyName UIAutomationClient,UIAutomationTypes
    if ($Handle -eq [IntPtr]::Zero) { return @() }
    $root = [System.Windows.Automation.AutomationElement]::FromHandle($Handle)
    if ($null -eq $root) { return @() }
    $found = New-Object System.Collections.Generic.List[string]
    foreach ($label in $Labels) {
        $cond = New-Object System.Windows.Automation.PropertyCondition(
            [System.Windows.Automation.AutomationElement]::NameProperty, $label)
        $matches = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $cond)
        foreach ($match in $matches) {
            if (-not $match.Current.IsOffscreen) {
                if (-not $found.Contains($label)) {
                    [void]$found.Add($label)
                }
                break
            }
        }
    }
    return ,$found.ToArray()
}

function Get-VisibleUiLabelsByWindowTitle {
    param(
        [string]$WindowTitle,
        [string[]]$Labels,
        [switch]$Exact
    )
    $handle = Get-WindowHandleByTitle -Title $WindowTitle -Exact:$Exact
    return Get-VisibleUiLabelsInWindowHandle -Handle $handle -Labels $Labels
}

function Focus-WindowHandle {
    param([IntPtr]$Handle)
    Ensure-ItwWin32
    if ($Handle -eq [IntPtr]::Zero) { return }
    [void][ItwWin32]::ShowWindow($Handle, 9)
    [void][ItwWin32]::SetForegroundWindow($Handle)
}

function Close-ControlPanel {
    Get-Process itweb-settings -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 600
}

function Open-RunningAppsPanel {
    Start-Process $Settings -WindowStyle Normal | Out-Null
    Start-Sleep -Seconds 6
    Select-RunningAppsTab
    Start-Sleep -Seconds 2
}

function Get-UiButtonsByWindowTitle {
    param([string]$WindowTitle)
    Add-Type -AssemblyName UIAutomationClient,UIAutomationTypes
    $proc = Get-Process | Where-Object { $_.MainWindowTitle -eq $WindowTitle } | Select-Object -First 1
    if ($null -eq $proc -or $proc.MainWindowHandle -eq [IntPtr]::Zero) {
        return @()
    }
    $root = [System.Windows.Automation.AutomationElement]::FromHandle($proc.MainWindowHandle)
    if ($null -eq $root) {
        return @()
    }
    $names = New-Object System.Collections.Generic.List[string]
    $all = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants,
        [System.Windows.Automation.Condition]::TrueCondition)
    foreach ($item in $all) {
        $name = $item.Current.Name
        if (-not [string]::IsNullOrWhiteSpace($name)) {
            $trimmed = $name.Trim()
            $isButton = ($trimmed -in @(
                "Stop", "Force Stop", "Trim Heap", "Refresh", "Close", "Clear",
                "Run GC", "Run Finalizers", "Memory Info", "System Properties",
                "Class Loaders", "Thread List"
            )) -or ($trimmed -like 'Run *') -or ($trimmed -like '*Memory*')
            if ($isButton) {
                if (-not $names.Contains($trimmed)) {
                    [void]$names.Add($trimmed)
                }
            }
        }
    }
    return ,$names.ToArray()
}

function Get-UiLabelMatches {
    param([string[]]$Labels)
    Add-Type -AssemblyName UIAutomationClient,UIAutomationTypes
    $root = [System.Windows.Automation.AutomationElement]::RootElement
    $found = New-Object System.Collections.Generic.List[string]
    foreach ($label in $Labels) {
        $cond = New-Object System.Windows.Automation.PropertyCondition(
            [System.Windows.Automation.AutomationElement]::NameProperty, $label)
        $match = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $cond)
        if ($null -ne $match) {
            [void]$found.Add($label)
        }
    }
    return ,$found.ToArray()
}

function Wait-WindowTitle {
    param(
        [string]$Title,
        [int]$TimeoutSeconds = 30
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $proc = Get-Process | Where-Object { $_.MainWindowTitle -eq $Title } | Select-Object -First 1
        if ($null -ne $proc) { return $proc }
        Start-Sleep -Milliseconds 500
    }
    return $null
}

function Select-RunningAppsTab {
    Ensure-ItwWin32
    $cpHandle = Get-WindowHandleByTitle -Title "IcedTea-Web Control Panel" -Exact
    if ($cpHandle -eq [IntPtr]::Zero) { throw "Control panel window not found" }
    Focus-WindowHandle $cpHandle
    Start-Sleep -Milliseconds 500
    Add-Type -AssemblyName System.Windows.Forms
    [System.Windows.Forms.SendKeys]::SendWait("{DOWN 7}{ENTER}")
    Start-Sleep -Milliseconds 1200
}

function Build-HoldJar {
    param([string]$HoldClasses, [string]$HoldJar)
    $javaExe = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\javac.exe" } else { "javac" }
    if (-not (Get-Command $javaExe -ErrorAction SilentlyContinue)) { $javaExe = "javac" }
    & $javaExe -d $HoldClasses $HoldSrc
    if ($LASTEXITCODE -ne 0) { throw "javac failed for hold app" }
    $jarExe = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\jar.exe" } else { "jar" }
    if (-not (Get-Command $jarExe -ErrorAction SilentlyContinue)) { $jarExe = "jar" }
    if (Test-Path $HoldJar) { Remove-Item $HoldJar -Force }
    & $jarExe cfe $HoldJar net.sourceforge.icedteaweb.it.apps.HeadlessHoldJnlpMain -C $HoldClasses .
    if ($LASTEXITCODE -ne 0) { throw "jar failed for hold app" }
    $ks = Join-Path $env:TEMP "itw-test-keystore.jks"
    if (-not (Test-Path $ks)) {
        & keytool -genkeypair -alias itwtest -keyalg RSA -keysize 2048 -validity 3650 `
            -keystore $ks -storepass changeit -keypass changeit -dname "CN=ITW Test"
    }
    & jarsigner -keystore $ks -storepass changeit $HoldJar itwtest | Out-Null
}

function Ensure-Distribution {
    if ($SkipBuild) {
        if (-not (Test-Path $Javaws)) {
            throw "javaws.exe not found: $Javaws"
        }
        Write-Host "Using existing distribution: $DistDir"
        return
    }
    Write-Step "Rebuilding icedtea-web and launchers"
    $jdk11 = $env:JAVA_HOME
    if ([string]::IsNullOrWhiteSpace($jdk11)) {
        $jdk11 = "C:\Program Files\Eclipse Adoptium\jdk-11.0.17.8-hotspot"
    }
    $env:JAVA_HOME = $jdk11
    $env:Path = "$jdk11\bin;$env:Path"
    $settings = Join-Path $Root ".powershell\workflows\maven-settings.xml"
    Push-Location $Root
    try {
        & mvn -s $settings -pl icedtea-web,icedtea-web-distribution -am package -P maven-distribution -DskipTests `
            "-Djdk11.home=$jdk11" "-Djdk8.home=$jdk11" -q
        if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }
    } finally {
        Pop-Location
    }
}

if (-not $SkipBuild) {
    Ensure-Distribution
}
if (-not (Test-Path $Javaws)) {
    throw "Distribution launcher missing: $Javaws"
}

New-Item -ItemType Directory -Force -Path $ShotDir | Out-Null
$report = New-Object System.Collections.Generic.List[string]
function Add-Report([string]$Line) {
    Write-Host $Line
    [void]$report.Add($Line)
}

$failures = New-Object System.Collections.Generic.List[string]
function Assert-True([bool]$Condition, [string]$Message) {
    if ($Condition) {
        Add-Report "PASS: $Message"
    } else {
        Add-Report "FAIL: $Message"
        [void]$failures.Add($Message)
    }
}

$WorkDir = Join-Path $env:TEMP ("itw-ux-smoke." + [Guid]::NewGuid().ToString("N"))
$WebRoot = Join-Path $WorkDir "web"
$HoldClasses = Join-Path $WorkDir "hold-classes"
$HoldJar = Join-Path $WorkDir "hold-app.jar"
$ConfigHome = Join-Path $WorkDir "xdg-config"
New-Item -ItemType Directory -Force -Path $WorkDir, $WebRoot, $HoldClasses | Out-Null

try {
    Stop-TestProcesses
    Write-DeploymentProperties -ConfigHome $ConfigHome
    $env:XDG_CONFIG_HOME = $ConfigHome

    Write-Step "Running Apps UX (default config)"
    Build-HoldJar -HoldClasses $HoldClasses -HoldJar $HoldJar
    Copy-Item $HoldJar (Join-Path $WebRoot "hold-app.jar") -Force
    $Port = Get-FreePort
    $Codebase = "http://127.0.0.1:$Port/"
    $JnlpUrl = "${Codebase}hold-test.jnlp"
    Write-JnlpNoBom (Join-Path $WebRoot "hold-test.jnlp") @"
<?xml version="1.0" encoding="UTF-8"?>
<jnlp spec="1.0+" codebase="$Codebase" href="hold-test.jnlp">
  <information>
    <title>ITW UX smoke hold app</title>
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
    Start-Process $Javaws -ArgumentList @(
        "-headless", "-verbose", "-Xtrustall", "--auto-accept-https-certificate=true", "-Xnofork", $JnlpUrl
    ) -WindowStyle Hidden | Out-Null
    Start-Sleep -Seconds 12

    $mainLog = Get-ChildItem (Join-Path $ConfigHome "icedtea-web\log") -Filter "itw-javantx-*.log" |
        Where-Object { $_.Name -notlike "*.err.log" -and $_.Name -notlike "*-prelaunch.log" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($null -eq $mainLog) { throw "Main javantx log not found" }
    $logText = Get-Content $mainLog.FullName -Raw
    Assert-True ($logText -match "Handoff status: SUCCESS") "Handoff block present in $($mainLog.Name)"
    Assert-True ($logText -notmatch "\.launch\.log") "No separate .launch.log handoff file referenced"

    Start-Process $Settings -WindowStyle Normal | Out-Null
    Start-Sleep -Seconds 6
    Select-RunningAppsTab
    Start-Sleep -Seconds 3
    $cpHandle = Get-WindowHandleByTitle -Title "IcedTea-Web Control Panel" -Exact
    Focus-WindowHandle $cpHandle
    Save-Screenshot $RunningAppsShot
    Assert-ScreenshotCaptured -Path $RunningAppsShot -Message "Running Apps default screenshot captured" -MinBytes 20000
    Add-Report "Visual check: default Running Apps should show Stop + Force Stop, hide Trim Heap, count=1"

    Write-Step "Running Apps gating flip (trim on, stop/force off)"
    Close-ControlPanel
    Write-DeploymentProperties -ConfigHome $ConfigHome -Extra @{
        "deployment.runningapps.stop" = "false"
        "deployment.runningapps.force.stop" = "false"
        "deployment.runningapps.trim.heap" = "true"
    }
    Assert-DeploymentProperty -ConfigHome $ConfigHome -Key "deployment.runningapps.trim.heap" -ExpectedValue "true" `
        "deployment.properties enables Trim Heap for gating flip"
    Assert-DeploymentProperty -ConfigHome $ConfigHome -Key "deployment.runningapps.stop" -ExpectedValue "false" `
        "deployment.properties disables Stop for gating flip"
    Open-RunningAppsPanel
    $cpHandle = Get-WindowHandleByTitle -Title "IcedTea-Web Control Panel" -Exact
    Focus-WindowHandle $cpHandle
    Save-Screenshot $RunningAppsFlipShot
    Assert-ScreenshotCaptured -Path $RunningAppsFlipShot -Message "Running Apps gating-flip screenshot captured" -MinBytes 20000
    Add-Report "Visual check: gating flip should show Trim Heap and hide Stop/Force Stop"
    Close-ControlPanel

    Write-Step "Java Console UX (default config, diagnostic buttons off)"
    Stop-TestProcesses
    Write-DeploymentProperties -ConfigHome $ConfigHome
    $env:XDG_CONFIG_HOME = $ConfigHome

    $Port2 = Get-FreePort
    $Codebase2 = "http://127.0.0.1:$Port2/"
    $JnlpUrl2 = "${Codebase2}hold-test.jnlp"
    $server2 = Start-Process python -ArgumentList @("-m", "http.server", "$Port2", "--bind", "127.0.0.1") `
        -WorkingDirectory $WebRoot -PassThru -WindowStyle Hidden
    Start-Sleep -Seconds 2
    Start-Process $Javaws -ArgumentList @(
        "-verbose", "-Xtrustall", "--auto-accept-https-certificate=true", "-Xnofork", $JnlpUrl2
    ) -WindowStyle Normal | Out-Null
    Start-Sleep -Seconds 15

    $consoleHandle = Wait-WindowHandleByTitle -Title "Java Console" -TimeoutSeconds 30
    if ($consoleHandle -eq [IntPtr]::Zero) {
        $titles = New-Object System.Collections.Generic.List[string]
        Ensure-ItwWin32
        [ItwWin32]::EnumWindows({
            param($hWnd, $lParam)
            if ([ItwWin32]::IsWindowVisible($hWnd)) {
                $sb = New-Object System.Text.StringBuilder 256
                [void][ItwWin32]::GetWindowText($hWnd, $sb, 256)
                $t = $sb.ToString()
                if (-not [string]::IsNullOrWhiteSpace($t)) { [void]$titles.Add($t) }
            }
            return $true
        }, [IntPtr]::Zero) | Out-Null
        Add-Report ("Visible windows while waiting for console: " + ($titles -join " | "))
        throw "Java Console window not found"
    }

    Focus-WindowHandle $consoleHandle
    Start-Sleep -Milliseconds 800
    Save-Screenshot $ConsoleShot
    Assert-ScreenshotCaptured -Path $ConsoleShot -Message "Java Console default screenshot captured" -MinBytes 5000
    Add-Report "Visual check: default Java Console should show Close + output spinner, hide diagnostic buttons"

    Write-Step "Java Console gating flip (Run GC on)"
    Stop-TestProcesses
    Write-DeploymentProperties -ConfigHome $ConfigHome -Extra @{
        "deployment.console.run.gc" = "true"
    }
    Assert-DeploymentProperty -ConfigHome $ConfigHome -Key "deployment.console.run.gc" -ExpectedValue "true" `
        "deployment.properties enables Run GC for gating flip"
    $env:XDG_CONFIG_HOME = $ConfigHome
    $Port3 = Get-FreePort
    $Codebase3 = "http://127.0.0.1:$Port3/"
    $JnlpUrl3 = "${Codebase3}hold-test.jnlp"
    $server3 = Start-Process python -ArgumentList @("-m", "http.server", "$Port3", "--bind", "127.0.0.1") `
        -WorkingDirectory $WebRoot -PassThru -WindowStyle Hidden
    Start-Sleep -Seconds 2
    Start-Process $Javaws -ArgumentList @(
        "-verbose", "-Xtrustall", "--auto-accept-https-certificate=true", "-Xnofork", $JnlpUrl3
    ) -WindowStyle Normal | Out-Null
    Start-Sleep -Seconds 15
    $consoleHandle2 = Wait-WindowHandleByTitle -Title "Java Console" -TimeoutSeconds 30
    if ($consoleHandle2 -eq [IntPtr]::Zero) { throw "Java Console window not found for gating flip" }
    Focus-WindowHandle $consoleHandle2
    Start-Sleep -Milliseconds 800
    Save-Screenshot $ConsoleFlipShot
    Assert-ScreenshotCaptured -Path $ConsoleFlipShot -Message "Java Console gating-flip screenshot captured" -MinBytes 5000
    Add-Report "Visual check: gating flip should show Run GC when deployment.console.run.gc=true"

    Add-Report ""
    if ($failures.Count -eq 0) {
        Add-Report "UX smoke result: PASS"
    } else {
        Add-Report "UX smoke result: FAIL ($($failures.Count) assertion(s))"
        $failures | ForEach-Object { Add-Report "  - $_" }
        $report | Set-Content $ReportPath -Encoding UTF8
        throw "UX smoke failed; see $ReportPath"
    }
    $report | Set-Content $ReportPath -Encoding UTF8
    Add-Report "Report: $ReportPath"
}
finally {
    Stop-TestProcesses
    if (Test-Path $WorkDir) {
        Remove-Item $WorkDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}
