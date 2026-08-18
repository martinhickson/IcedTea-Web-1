# Bootstrap io.github.martinhickson:pack200 into ~/.m2 from Maven Central
# (fallback: GitHub release jar) when the host cannot resolve Central.

function Get-Pack200MavenArtifactPaths {
    param([Parameter(Mandatory = $true)][string]$Version)

    $repoRoot = Join-Path $env:USERPROFILE ".m2\repository\io\github\martinhickson\pack200\$Version"
    return @{
        Directory = $repoRoot
        Jar       = Join-Path $repoRoot "pack200-$Version.jar"
        Pom       = Join-Path $repoRoot "pack200-$Version.pom"
    }
}

function Test-Pack200IntrinsicInJar {
    param([Parameter(Mandatory = $true)][string]$JarPath)

    if (-not (Test-Path -LiteralPath $JarPath)) {
        return $false
    }

    $resourcePath = 'io/pack200/pack/intrinsic.properties'
    $jarExe = 'jar'
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $candidate = Join-Path $env:JAVA_HOME 'bin\jar.exe'
        if (Test-Path -LiteralPath $candidate) {
            $jarExe = $candidate
        }
    }

    $entries = @(& $jarExe tf $JarPath | ForEach-Object { $_.TrimEnd("`r") })
    if ($LASTEXITCODE -ne 0) {
        return $false
    }

    return ($entries -contains $resourcePath)
}

function Test-Pack200MavenArtifactInstalled {
    param([Parameter(Mandatory = $true)][string]$Version)

    $paths = Get-Pack200MavenArtifactPaths -Version $Version
    return ((Test-Path -LiteralPath $paths.Jar) -and (Test-Path -LiteralPath $paths.Pom) -and
        (Test-Pack200IntrinsicInJar -JarPath $paths.Jar))
}

function Get-ProxyUrlForDownload {
    foreach ($name in @('HTTPS_PROXY', 'HTTP_PROXY', 'https_proxy', 'http_proxy')) {
        $value = [Environment]::GetEnvironmentVariable($name)
        if (-not [string]::IsNullOrWhiteSpace($value)) {
            return $value.Trim()
        }
    }
    return $null
}

function Invoke-Pack200Download {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [Parameter(Mandatory = $true)][string]$OutFile,
        [Parameter(Mandatory = $true)][int]$MinBytes
    )

    $proxyUrl = Get-ProxyUrlForDownload
    for ($attempt = 1; $attempt -le 8; $attempt++) {
        try {
            if (Test-Path -LiteralPath $OutFile) {
                Remove-Item -LiteralPath $OutFile -Force
            }
            $params = @{
                Uri             = $Url
                OutFile         = $OutFile
                UseBasicParsing = $true
            }
            if ($proxyUrl) {
                $params.Proxy = $proxyUrl
                $params.ProxyUseDefaultCredentials = $true
            }
            Invoke-WebRequest @params
            if ((Get-Item -LiteralPath $OutFile).Length -lt $MinBytes) {
                throw "Download too small: $Url"
            }
            return
        } catch {
            if ($attempt -eq 8) {
                throw "Failed to download $Url`: $_"
            }
            # GitHub/Adoptium sometimes return 503 under load; back off harder.
            Start-Sleep -Seconds ([Math]::Min(60, 5 * $attempt))
        }
    }
}

function Ensure-Pack200MavenDependency {
    param([string]$Version = '11.0.4')

    if (Test-Pack200MavenArtifactInstalled -Version $Version) {
        Write-Detail "pack200 $Version already in local Maven repository with intrinsic.properties; skipping bootstrap."
        return
    }

    $paths = Get-Pack200MavenArtifactPaths -Version $Version
    if (Test-Path -LiteralPath $paths.Jar) {
        Write-Detail "pack200 $Version jar present but missing intrinsic.properties; re-bootstrapping from Maven Central."
        Remove-Item -LiteralPath $paths.Jar -Force
    }

    New-Item -ItemType Directory -Force -Path $paths.Directory | Out-Null

    $tempDir = Join-Path $env:TEMP 'itw-pack200-bootstrap'
    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
    $tempJar = Join-Path $tempDir "pack200-$Version.jar"

    $jarUrls = @(
        "https://repo1.maven.org/maven2/io/github/martinhickson/pack200/$Version/pack200-$Version.jar",
        "https://github.com/martinhickson/pack200/releases/download/pack200-$Version/pack200-$Version.jar"
    )

    $downloaded = $false
    foreach ($url in $jarUrls) {
        try {
            Write-Detail "Downloading pack200 $Version from $url"
            Invoke-Pack200Download -Url $url -OutFile $tempJar -MinBytes 100000
            if (-not (Test-Pack200IntrinsicInJar -JarPath $tempJar)) {
                throw "Downloaded jar missing io/pack200/pack/intrinsic.properties: $url"
            }
            $downloaded = $true
            break
        } catch {
            Write-Detail "Download failed: $_"
        }
    }

    if (-not $downloaded) {
        throw @(
            "Could not bootstrap io.github.martinhickson:pack200:$Version into the local Maven repository."
            'Tried Maven Central and the GitHub release URL.'
            'Check network/proxy or copy the jar into ~/.m2/repository/io/github/martinhickson/pack200 manually.'
        ) -join ' '
    }

    Write-Detail "Installing pack200 $Version into local Maven repository via mvn install:install-file..."
    & mvn -q install:install-file `
        "-Dfile=$tempJar" `
        '-DgroupId=io.github.martinhickson' `
        '-DartifactId=pack200' `
        "-Dversion=$Version" `
        '-Dpackaging=jar'
    if ($LASTEXITCODE -ne 0) {
        throw "mvn install:install-file for pack200 $Version failed with exit code $LASTEXITCODE."
    }

    if (-not (Test-Pack200MavenArtifactInstalled -Version $Version)) {
        throw "pack200 $Version was not installed into the local Maven repository with intrinsic.properties."
    }

    Write-Detail "pack200 $Version bootstrapped into $($paths.Directory)"
}
