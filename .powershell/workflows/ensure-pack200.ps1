# Bootstrap io.pack200:pack200 into ~/.m2 when Maven cannot download from securemvn/GitHub Packages
# (e.g. TLS handshake failures from Java on some Windows hosts).

function Get-Pack200MavenArtifactPaths {
    param([Parameter(Mandatory = $true)][string]$Version)

    $repoRoot = Join-Path $env:USERPROFILE ".m2\repository\io\pack200\pack200\$Version"
    return @{
        Directory = $repoRoot
        Jar       = Join-Path $repoRoot "pack200-$Version.jar"
        Pom       = Join-Path $repoRoot "pack200-$Version.pom"
    }
}

function Test-Pack200MavenArtifactInstalled {
    param([Parameter(Mandatory = $true)][string]$Version)

    $paths = Get-Pack200MavenArtifactPaths -Version $Version
    return ((Test-Path -LiteralPath $paths.Jar) -and (Test-Path -LiteralPath $paths.Pom))
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
    for ($attempt = 1; $attempt -le 3; $attempt++) {
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
            if ($attempt -eq 3) {
                throw "Failed to download $Url`: $_"
            }
            Start-Sleep -Seconds (3 * $attempt)
        }
    }
}

function Ensure-Pack200MavenDependency {
    param([string]$Version = '11.0.2')

    if (Test-Pack200MavenArtifactInstalled -Version $Version) {
        Write-Detail "pack200 $Version already in local Maven repository; skipping bootstrap."
        return
    }

    $paths = Get-Pack200MavenArtifactPaths -Version $Version
    New-Item -ItemType Directory -Force -Path $paths.Directory | Out-Null

    $tempDir = Join-Path $env:TEMP 'itw-pack200-bootstrap'
    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
    $tempJar = Join-Path $tempDir "pack200-$Version.jar"

    $jarUrls = @(
        "https://securemvn.com/releases/io/pack200/pack200/$Version/pack200-$Version.jar",
        "https://github.com/martinhickson/pack200/releases/download/pack200-$Version/pack200-$Version.jar",
        "https://github.com/martinhickson/pack200/releases/download/pack200-$Version/pack.jar"
    )

    $downloaded = $false
    foreach ($url in $jarUrls) {
        try {
            Write-Detail "Downloading pack200 $Version from $url"
            Invoke-Pack200Download -Url $url -OutFile $tempJar -MinBytes 100000
            $downloaded = $true
            break
        } catch {
            Write-Detail "Download failed: $_"
        }
    }

    if (-not $downloaded) {
        throw @(
            "Could not bootstrap io.pack200:pack200:$Version into the local Maven repository."
            'Tried securemvn.com and GitHub release URLs.'
            'Check network/proxy or copy the jar into ~/.m2/repository/io/pack200/pack200 manually.'
        ) -join ' '
    }

    Write-Detail "Installing pack200 $Version into local Maven repository via mvn install:install-file..."
    & mvn -q install:install-file `
        "-Dfile=$tempJar" `
        '-DgroupId=io.pack200' `
        '-DartifactId=pack200' `
        "-Dversion=$Version" `
        '-Dpackaging=jar'
    if ($LASTEXITCODE -ne 0) {
        throw "mvn install:install-file for pack200 $Version failed with exit code $LASTEXITCODE."
    }

    if (-not (Test-Pack200MavenArtifactInstalled -Version $Version)) {
        throw "pack200 $Version was not installed into the local Maven repository."
    }

    Write-Detail "pack200 $Version bootstrapped into $($paths.Directory)"
}
