# Ensure JDK 17 is visible to JvmAutodetector Windows vendor scanning
# (%LOCALAPPDATA%\Programs\Eclipse Adoptium\...).
param(
    [string]$Jdk17Home = $env:ITW_JDK17_HOME
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($Jdk17Home)) {
    $portable = Join-Path (Resolve-Path (Join-Path $PSScriptRoot "..")).Path "target\jdk17"
    if (Test-Path $portable) {
        $found = Get-ChildItem $portable -Directory | Where-Object {
            Test-Path (Join-Path $_.FullName "bin\java.exe")
        } | Select-Object -First 1
        if ($null -ne $found) {
            $Jdk17Home = $found.FullName
        }
    }
}

if ([string]::IsNullOrWhiteSpace($Jdk17Home) -or -not (Test-Path (Join-Path $Jdk17Home "bin\java.exe"))) {
    Write-Warning "No JDK 17 home found. Set ITW_JDK17_HOME or unpack under target\jdk17."
    exit 0
}

$destRoot = Join-Path $env:LOCALAPPDATA "Programs\Eclipse Adoptium"
$leaf = Split-Path $Jdk17Home -Leaf
$dest = Join-Path $destRoot $leaf
New-Item -ItemType Directory -Force -Path $destRoot | Out-Null

$srcFull = (Resolve-Path -LiteralPath $Jdk17Home).Path
if (Test-Path -LiteralPath $dest) {
    $existing = Get-Item -LiteralPath $dest
    if ($existing.Attributes -band [IO.FileAttributes]::ReparsePoint) {
        cmd /c rmdir "`"$dest`"" | Out-Null
    } elseif ((Resolve-Path -LiteralPath $dest).Path -ieq $srcFull) {
        Write-Host "JDK 17 already at discoverable path: $dest"
        Write-Host "ITW_JDK17_HOME=$dest"
        exit 0
    }
}

cmd /c mklink /J "`"$dest`"" "`"$srcFull`""
if ($LASTEXITCODE -ne 0 -and -not (Test-Path (Join-Path $dest "bin\java.exe"))) {
    throw "Failed to create junction $dest -> $srcFull"
}
Write-Host "Discoverable JDK 17: $dest"
Write-Host "ITW_JDK17_HOME=$dest"
