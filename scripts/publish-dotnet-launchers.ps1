# Local dev shortcut: publish javaws (GUI) and javawsc (console) like Maven does.
# Canonical build: mvn -P maven-distribution -pl icedtea-web-distribution -am package
#
# Layout (mirrors icedtea-web-distribution/pom.xml):
#   test-dist/dotnet-publish/         <- JavawsLauncher.csproj
#   test-dist/dotnet-publish-console/ <- JavawscLauncher.csproj
#   merge javawsc* into dotnet-publish, then sync merged output to test-dist/bin/
param(
    [string]$DistRoot = "C:\work\IcedTea-Web-1\test-dist",
    [string]$Dotnet = "C:\work\IcedTea-Web-1\tools\dotnet8\dotnet.exe"
)

$ErrorActionPreference = "Stop"
$launcherDir = "C:\work\IcedTea-Web-1\dotnet-launcher"
$launcherIcon = Join-Path (Split-Path $launcherDir -Parent) "win-installer\icon.ico"
if (-not (Test-Path $launcherIcon)) {
    throw "Launcher icon not found: $launcherIcon"
}
$iconArg = "-p:ApplicationIcon=$launcherIcon"
$guiPublish = Join-Path $DistRoot "dotnet-publish"
$consolePublish = Join-Path $DistRoot "dotnet-publish-console"
$binDir = Join-Path $DistRoot "bin"

function Clear-LauncherSharedBuild {
    # Both csproj files share dotnet-launcher/bin/Release/net8.0/win-x64 (apphost cache).
    & $Dotnet clean (Join-Path $launcherDir "JavawsLauncher.csproj") -c Release -v q | Out-Null
    & $Dotnet clean (Join-Path $launcherDir "JavawscLauncher.csproj") -c Release -v q | Out-Null
}

function Stop-LauncherProcesses {
    Get-Process -Name "javaws", "javawsc", "icedtea-web-settings", "itweb-settings", "policyeditor" -ErrorAction SilentlyContinue |
        Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 500
}

function Sync-PublishedTree {
    param(
        [string]$SourceDir,
        [string]$TargetDir
    )
    if (-not (Test-Path $TargetDir)) {
        return
    }
    New-Item -ItemType Directory -Path $TargetDir -Force | Out-Null
    # Copy launchers first so a locked runtime DLL cannot leave stale exes behind.
    foreach ($name in @("javaws.exe", "javawsc.exe", "icedtea-web-settings.exe", "itweb-settings.exe", "policyeditor.exe")) {
        $src = Join-Path $SourceDir $name
        if (Test-Path $src) {
            Copy-Item -Path $src -Destination (Join-Path $TargetDir $name) -Force
        }
    }
    Copy-Item -Path (Join-Path $SourceDir "*") -Destination $TargetDir -Recurse -Force
}

Clear-LauncherSharedBuild
& $Dotnet publish (Join-Path $launcherDir "JavawsLauncher.csproj") -c Release -r win-x64 $iconArg -o $guiPublish

Clear-LauncherSharedBuild
& $Dotnet publish (Join-Path $launcherDir "JavawscLauncher.csproj") -c Release -r win-x64 $iconArg -o $consolePublish

Copy-Item -Path (Join-Path $consolePublish "javawsc*") -Destination $guiPublish -Force

# Maven copies javaws -> icedtea-web-settings / itweb-settings / policyeditor
# (same launcher; main class chosen from binary name). Windows cannot safely
# hard-link or symlink these for MSI repair, so they are duplicate PEs.
Copy-Item -Path (Join-Path $guiPublish "javaws.exe") -Destination (Join-Path $guiPublish "icedtea-web-settings.exe") -Force
Copy-Item -Path (Join-Path $guiPublish "javaws.exe") -Destination (Join-Path $guiPublish "itweb-settings.exe") -Force
Copy-Item -Path (Join-Path $guiPublish "javaws.exe") -Destination (Join-Path $guiPublish "policyeditor.exe") -Force

Stop-LauncherProcesses
New-Item -ItemType Directory -Path $binDir -Force | Out-Null
Sync-PublishedTree -SourceDir $guiPublish -TargetDir $binDir
$testRunBin = Join-Path $DistRoot "test-run\bin"
Sync-PublishedTree -SourceDir $guiPublish -TargetDir $testRunBin

$publishedJavaws = Get-Item (Join-Path $guiPublish "javaws.exe")
Write-Host "Published (Maven-style layout):"
Write-Host "  GUI:     $guiPublish"
Write-Host "  Console: $consolePublish"
Write-Host "  Merged:  $guiPublish  (+ javawsc* from console)"
Write-Host "  Runtime: $binDir"
if (Test-Path $testRunBin) {
    Write-Host "  Synced:  $testRunBin"
}
Write-Host "  javaws:  $($publishedJavaws.Length) bytes, $($publishedJavaws.LastWriteTime)"
Get-ChildItem $binDir -Filter "javaws.exe"
Get-ChildItem $binDir -Filter "javawsc.exe"
Get-ChildItem $binDir -Filter "icedtea-web-settings.exe"
Get-ChildItem $binDir -Filter "itweb-settings.exe"
Get-ChildItem $binDir -Filter "policyeditor.exe"
