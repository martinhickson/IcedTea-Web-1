$ErrorActionPreference = "Stop"

# Run a local Windows .NET-launcher JNLP smoke test.
#
# This consumes the normal IcedTea-Web Windows distribution artifact from
# icedtea-web-distribution/target, extracts its javaws.exe launcher, serves a
# JNLP from a temporary local HTTP server, and prints the javaws output.

$RootDir = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Version = if ($env:ITW_VERSION) { $env:ITW_VERSION } else { "2.0.1-SNAPSHOT" }
$DistZip = if ($env:ITW_DOTNET_WINDOWS_ZIP) {
    $env:ITW_DOTNET_WINDOWS_ZIP
} else {
    Join-Path $RootDir "icedtea-web-distribution\target\icedtea-web-$Version-win-x64.zip"
}
$DistDir = $env:ITW_DOTNET_DIST_DIR
$JavawsBin = $env:ITW_JAVAWS_BIN
$AppJar = if ($env:ITW_HEADLESS_APP_JAR) {
    $env:ITW_HEADLESS_APP_JAR
} else {
    Join-Path $RootDir "icedtea-web-integration\target\icedtea-web-integration-$Version-headless-app.jar"
}
$BuildTestApp = if ($env:ITW_SMOKE_BUILD_TEST_APP) { $env:ITW_SMOKE_BUILD_TEST_APP } else { "true" }
$Port = $env:ITW_SMOKE_PORT
$TimeoutSeconds = if ($env:ITW_SMOKE_TIMEOUT_SECONDS) { [int]$env:ITW_SMOKE_TIMEOUT_SECONDS } else { 180 }
$KeepWorkDir = $env:ITW_SMOKE_KEEP_WORKDIR -eq "true"

$WorkDir = Join-Path ([System.IO.Path]::GetTempPath()) ("itw-dotnet-jnlp." + [System.Guid]::NewGuid().ToString("N"))
$WebRoot = $null
$ServerProcess = $null
$JavawsProcess = $null

Write-Host "Windows .NET JNLP smoke script revision: redirected-process-logs-v2"

function Stop-SmokeProcess {
    param($Process)
    if ($null -ne $Process -and -not $Process.HasExited) {
        Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue
    }
}

function Prepare-DotnetDistribution {
    if ($JavawsBin) {
        if (-not (Test-Path $JavawsBin -PathType Leaf)) {
            throw "ITW_JAVAWS_BIN is set but does not exist: $JavawsBin"
        }
        Write-Host "Using javaws from ITW_JAVAWS_BIN: $JavawsBin"
        return $JavawsBin
    }

    if ($DistDir) {
        $candidate = Join-Path $DistDir "bin\javaws.exe"
        if (-not (Test-Path $candidate -PathType Leaf)) {
            throw "ITW_DOTNET_DIST_DIR is set but bin\javaws.exe does not exist: $candidate"
        }
        Write-Host "Using javaws from ITW_DOTNET_DIST_DIR: $candidate"
        return $candidate
    }

    if (-not (Test-Path $DistZip -PathType Leaf)) {
        throw @"
Windows .NET distribution artifact not found:
  $DistZip

Build it first with:
  mvn -P maven-distribution -pl icedtea-web-distribution -am install `
    -Dmaven.test.skip=true -DskipTests `
    "-Djdk8.home=`$env:JAVA_HOME" `
    "-Ditw.dotnet.runtime.identifier=win-x64" `
    "-Ditw.dotnet.selfContained=true"

Or point this script at an existing artifact with ITW_DOTNET_WINDOWS_ZIP,
ITW_DOTNET_DIST_DIR, or ITW_JAVAWS_BIN.
"@
    }

    $unpackDir = Join-Path $WorkDir "dotnet-dist"
    New-Item -ItemType Directory -Path $unpackDir -Force | Out-Null
    Write-Host "Extracting normal Windows .NET distribution artifact: $DistZip"
    Expand-Archive -Path $DistZip -DestinationPath $unpackDir -Force

    $root = Get-ChildItem -Path $unpackDir -Directory | Select-Object -First 1
    if ($null -eq $root) {
        throw "Extracted artifact did not contain a distribution directory: $DistZip"
    }

    $candidate = Join-Path $root.FullName "bin\javaws.exe"
    if (-not (Test-Path $candidate -PathType Leaf)) {
        throw "Extracted artifact does not contain bin\javaws.exe: $candidate"
    }

    Write-Host "Using javaws from normal Windows .NET distribution artifact: $candidate"
    return $candidate
}

function Ensure-HeadlessApp {
    if (Test-Path $AppJar -PathType Leaf) {
        return
    }
    if ($BuildTestApp -ne "true") {
        throw "Headless app jar not found: $AppJar"
    }

    Write-Host "Building signed headless JNLP app from icedtea-web-integration"
    $mvnArgs = @(
        "-pl", "icedtea-web-integration",
        "process-test-classes",
        "-DskipTests"
    )
    if ($env:JAVA_HOME) {
        $mvnArgs += "-Djdk8.home=$env:JAVA_HOME"
        $mvnArgs += "-Ditw.jdk8.home=$env:JAVA_HOME"
    }
    Push-Location $RootDir
    try {
        & mvn @mvnArgs
        if ($LASTEXITCODE -ne 0) {
            throw "Maven failed while building the signed headless app"
        }
    } finally {
        Pop-Location
    }
}

function Get-FreePort {
    if ($Port) {
        return [int]$Port
    }
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Parse("127.0.0.1"), 0)
    $listener.Start()
    try {
        return $listener.LocalEndpoint.Port
    } finally {
        $listener.Stop()
    }
}

function Wait-ForServer {
    param([string]$Url)
    $deadline = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $deadline) {
        try {
            Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 3 | Out-Null
            return
        } catch {
            Start-Sleep -Milliseconds 500
        }
    }
    throw "Timed out waiting for local HTTP server at $Url"
}

function Start-Javaws {
    param(
        [string]$Launcher,
        [string]$JnlpUrl,
        [string]$OutLogFile,
        [string]$ErrLogFile
    )

    Start-Process `
        -FilePath $Launcher `
        -ArgumentList @("-headless", "-verbose", "-Xtrustall", "--auto-accept-https-certificate=true", "-Xnofork", $JnlpUrl) `
        -RedirectStandardOutput $OutLogFile `
        -RedirectStandardError $ErrLogFile `
        -PassThru `
        -WindowStyle Hidden
}

function Test-LogForSuccess {
    param([string[]]$LogFiles)

    foreach ($logFile in $LogFiles) {
        if ((Test-Path $logFile -PathType Leaf) -and (Select-String -Path $logFile -Pattern "ITW_INTEGRATION_SUCCESS" -Quiet)) {
            return $true
        }
    }
    return $false
}

function Wait-ForLaunch {
    param(
        [string]$Marker,
        [string[]]$LogFiles
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if ((Test-Path $Marker -PathType Leaf) -and ((Get-Item $Marker).Length -gt 0)) {
            return $true
        }
        if (Test-LogForSuccess -LogFiles $LogFiles) {
            return $true
        }
        if ($JavawsProcess.HasExited) {
            return Test-LogForSuccess -LogFiles $LogFiles
        }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

try {
    New-Item -ItemType Directory -Path $WorkDir -Force | Out-Null
    $JavawsBin = Prepare-DotnetDistribution
    Ensure-HeadlessApp

    if (-not (Test-Path $AppJar -PathType Leaf)) {
        throw "Headless app jar not found: $AppJar"
    }

    $WebRoot = Join-Path $WorkDir "web"
    New-Item -ItemType Directory -Path $WebRoot -Force | Out-Null
    Copy-Item -Path $AppJar -Destination (Join-Path $WebRoot "headless-app.jar") -Force

    $SelectedPort = Get-FreePort
    $Marker = Join-Path $WorkDir "success.marker"
    $MarkerForJnlp = $Marker -replace "\\", "/"
    $JavawsOutLog = Join-Path $WorkDir "javaws.out.log"
    $JavawsErrLog = Join-Path $WorkDir "javaws.err.log"
    $ServerOutLog = Join-Path $WorkDir "server.out.log"
    $ServerErrLog = Join-Path $WorkDir "server.err.log"
    $Codebase = "http://127.0.0.1:$SelectedPort/"
    $JnlpUrl = "${Codebase}headless-test.jnlp"

    @"
<?xml version="1.0" encoding="UTF-8"?>
<jnlp spec="1.0+" codebase="$Codebase" href="headless-test.jnlp">
  <information>
    <title>ITW .NET launcher smoke test</title>
    <vendor>IcedTea-Web</vendor>
  </information>
  <security><all-permissions/></security>
  <resources>
    <property name="itw.test.success.marker" value="$MarkerForJnlp"/>
    <j2se version="1.8+"/>
    <jar href="headless-app.jar" main="true"/>
  </resources>
  <application-desc main-class="net.sourceforge.jnlp.integration.HeadlessJnlpMain"/>
</jnlp>
"@ | Set-Content -Path (Join-Path $WebRoot "headless-test.jnlp") -Encoding UTF8

    Write-Host "Serving JNLP from $WebRoot"
    $ServerProcess = Start-Process -FilePath "python" -ArgumentList @("-m", "http.server", "$SelectedPort", "--bind", "127.0.0.1") -WorkingDirectory $WebRoot -RedirectStandardOutput $ServerOutLog -RedirectStandardError $ServerErrLog -PassThru -WindowStyle Hidden
    Wait-ForServer $JnlpUrl

    Write-Host "Launching with .NET javaws: $JavawsBin"
    Write-Host "JNLP URL: $JnlpUrl"
    Write-Host "Marker: $Marker"
    Write-Host "javaws stdout log: $JavawsOutLog"
    Write-Host "javaws stderr log: $JavawsErrLog"
    $JavawsProcess = Start-Javaws -Launcher $JavawsBin -JnlpUrl $JnlpUrl -OutLogFile $JavawsOutLog -ErrLogFile $JavawsErrLog

    $succeeded = Wait-ForLaunch -Marker $Marker -LogFiles @($JavawsOutLog, $JavawsErrLog)
    if ($succeeded -and -not $JavawsProcess.HasExited) {
        [void]$JavawsProcess.WaitForExit(10000)
    }

    if (Test-Path $JavawsOutLog -PathType Leaf) {
        Get-Content -Path $JavawsOutLog
    }
    if (Test-Path $JavawsErrLog -PathType Leaf) {
        Get-Content -Path $JavawsErrLog
    }

    if (-not $succeeded) {
        Write-Error "JNLP launch did not report success within ${TimeoutSeconds}s. javaws logs: $JavawsOutLog, $JavawsErrLog; server logs: $ServerOutLog, $ServerErrLog"
        exit 1
    }

    Write-Host ""
    Write-Host "JNLP launch succeeded."
    if ((Test-Path $Marker -PathType Leaf) -and ((Get-Item $Marker).Length -gt 0)) {
        Write-Host "Marker contents:"
        Get-Content -Path $Marker
    }
} finally {
    Stop-SmokeProcess $JavawsProcess
    Stop-SmokeProcess $ServerProcess
    if (-not $KeepWorkDir -and (Test-Path $WorkDir -PathType Container)) {
        Remove-Item -Path $WorkDir -Recurse -Force
    } elseif (Test-Path $WorkDir -PathType Container) {
        Write-Host "Kept smoke-test work dir: $WorkDir"
    }
}
