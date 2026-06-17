# Shared JDK discovery for .powershell/workflows.
# Target runtime: Windows PowerShell 5.1 (powershell.exe), not pwsh.

function Get-ConfiguredJdkMajor {
    param([hashtable]$Config = @{})

    foreach ($name in @('ITW_JDK_VERSION', 'ITW_COMPILER_VERSION')) {
        $value = if ($Config.ContainsKey($name)) { $Config[$name] } else { [Environment]::GetEnvironmentVariable($name) }
        if (-not [string]::IsNullOrWhiteSpace($value)) {
            return [int]$value.Trim()
        }
    }
    return 11
}

function Get-JdkMajorFromJavaExe {
    param([Parameter(Mandatory = $true)][string]$JavaExe)

    if (-not (Test-Path -LiteralPath $JavaExe)) {
        return $null
    }

    $output = (& $JavaExe -version 2>&1 | Select-Object -First 1 | Out-String).Trim()
    if ($output -match 'version "1\.(\d+)') {
        return [int]$Matches[1]
    }
    if ($output -match 'version "(\d+)') {
        return [int]$Matches[1]
    }
    return $null
}

function Test-JdkHome {
    param(
        [Parameter(Mandatory = $true)][string]$JdkHome,
        [Parameter(Mandatory = $true)][int]$Major
    )

    $javaExe = Join-Path $JdkHome 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $javaExe)) {
        return $false
    }

    $foundMajor = Get-JdkMajorFromJavaExe -JavaExe $javaExe
    return ($foundMajor -eq $Major)
}

function Add-JdkCandidate {
    param(
        [AllowEmptyString()]
        [AllowNull()]
        [string]$Candidate,
        [Parameter(Mandatory = $true)][hashtable]$Seen,
        [AllowEmptyCollection()]
        [Parameter(Mandatory = $true)][System.Collections.Generic.List[string]]$Candidates
    )

    if ([string]::IsNullOrWhiteSpace($Candidate)) {
        return
    }

    $normalized = $Candidate.Trim().TrimEnd('\')
    $key = $normalized.ToLowerInvariant()
    if ($Seen.ContainsKey($key)) {
        return
    }

    $Seen[$key] = $true
    [void]$Candidates.Add($normalized)
}

function Get-ExplicitJdkHomeCandidates {
    param([hashtable]$Config = @{})

    $candidates = New-Object 'System.Collections.Generic.List[string]'
    $seen = @{}

    foreach ($name in @('ITW_JDK11_HOME', 'JDK11_HOME', 'JAVA_HOME')) {
        $value = if ($Config.ContainsKey($name)) { $Config[$name] } else { [Environment]::GetEnvironmentVariable($name) }
        if ([string]::IsNullOrWhiteSpace($value)) {
            continue
        }
        Add-JdkCandidate -Candidate $value -Seen $seen -Candidates $candidates
    }

    return @($candidates)
}

function Get-WindowsJdkSearchRoots {
    $roots = @(
        'C:\Program Files\Amazon Corretto',
        'C:\Program Files\Java',
        'C:\Program Files\Eclipse Adoptium',
        'C:\Program Files\Microsoft',
        'C:\Program Files\Zulu',
        'C:\Program Files\BellSoft',
        'C:\Program Files\Semeru',
        'C:\Program Files (x86)\Java'
    )

    return @($roots | Where-Object { Test-Path -LiteralPath $_ })
}

function Get-JdkHomesFromRegistry {
    param([Parameter(Mandatory = $true)][int]$Major)

    $homes = New-Object 'System.Collections.Generic.List[string]'
    $seen = @{}
    $registryPaths = @(
        'HKLM:\SOFTWARE\JavaSoft\JDK',
        'HKLM:\SOFTWARE\JavaSoft\Java Development Kit',
        'HKLM:\SOFTWARE\Eclipse Adoptium\JDK'
    )

    foreach ($registryPath in $registryPaths) {
        if (-not (Test-Path -LiteralPath $registryPath)) {
            continue
        }

        Get-ChildItem -LiteralPath $registryPath -ErrorAction SilentlyContinue | ForEach-Object {
            if ($_.PSChildName -notmatch "^$Major(\.|$|-)") {
                return
            }

            $props = Get-ItemProperty -LiteralPath $_.PSPath -ErrorAction SilentlyContinue
            if ($props.JavaHome) {
                Add-JdkCandidate -Candidate $props.JavaHome -Seen $seen -Candidates $homes
            }
        }
    }

    return @($homes)
}

function Get-JdkHomesFromSearchRoots {
    param([Parameter(Mandatory = $true)][int]$Major)

    $homes = New-Object 'System.Collections.Generic.List[string]'
    $seen = @{}

    foreach ($root in (Get-WindowsJdkSearchRoots)) {
        Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue | ForEach-Object {
            $name = $_.Name
            if ($name -notmatch "(^jdk-$Major(\.|-|$)|^$Major(\.|-|$)|jdk$Major|corretto.*$Major|zulu-$Major)") {
                return
            }
            Add-JdkCandidate -Candidate $_.FullName -Seen $seen -Candidates $homes
        }
    }

    return @($homes)
}

function Get-JdkHomeFromPathJava {
    $javaCmd = Get-Command java -ErrorAction SilentlyContinue
    if (-not $javaCmd) {
        return $null
    }

    $javaExe = $javaCmd.Source
    if (-not $javaExe) {
        return $null
    }

    $binDir = Split-Path -Parent $javaExe
    return (Split-Path -Parent $binDir)
}

function Find-JdkHomeWithMajor {
    param(
        [Parameter(Mandatory = $true)][int]$Major,
        [hashtable]$Config = @{}
    )

    $candidates = New-Object 'System.Collections.Generic.List[string]'
    $seen = @{}

    foreach ($candidate in (Get-ExplicitJdkHomeCandidates -Config $Config)) {
        Add-JdkCandidate -Candidate $candidate -Seen $seen -Candidates $candidates
    }
    foreach ($candidate in (Get-JdkHomesFromRegistry -Major $Major)) {
        Add-JdkCandidate -Candidate $candidate -Seen $seen -Candidates $candidates
    }
    foreach ($candidate in (Get-JdkHomesFromSearchRoots -Major $Major)) {
        Add-JdkCandidate -Candidate $candidate -Seen $seen -Candidates $candidates
    }

    $pathJavaHome = Get-JdkHomeFromPathJava
    if (-not [string]::IsNullOrWhiteSpace($pathJavaHome)) {
        Add-JdkCandidate -Candidate $pathJavaHome -Seen $seen -Candidates $candidates
    }

    foreach ($candidate in $candidates) {
        if (Test-JdkHome -JdkHome $candidate -Major $Major) {
            return $candidate
        }
    }

    return $null
}

function Resolve-CompileJdkHome {
    param([hashtable]$Config = @{})

    $major = Get-ConfiguredJdkMajor -Config $Config
    $jdkHome = Find-JdkHomeWithMajor -Major $major -Config $Config
    if (-not $jdkHome) {
        throw @(
            "No JDK $major found for compilation."
            "Install JDK $major (for example corretto${major}jdk via Chocolatey) or set ITW_JDK11_HOME in sign.env."
        ) -join ' '
    }

    return @{
        Major   = $major
        JdkHome = $jdkHome
    }
}

function Set-CompileJdkEnvironment {
    param(
        [Parameter(Mandatory = $true)][string]$JdkHome,
        [Parameter(Mandatory = $true)][int]$Major
    )

    [Environment]::SetEnvironmentVariable('JAVA_HOME', $JdkHome, 'Process')
    $env:JAVA_HOME = $JdkHome

    if ($Major -eq 11) {
        [Environment]::SetEnvironmentVariable('JDK11_HOME', $JdkHome, 'Process')
        $env:JDK11_HOME = $JdkHome
    }
}

function Get-ChocoJdkPackageName {
    param([Parameter(Mandatory = $true)][int]$Major)

    switch ($Major) {
        8 { return 'corretto8jdk' }
        11 { return 'corretto11jdk' }
        17 { return 'corretto17jdk' }
        21 { return 'corretto21jdk' }
        default { return "corretto${Major}jdk" }
    }
}
