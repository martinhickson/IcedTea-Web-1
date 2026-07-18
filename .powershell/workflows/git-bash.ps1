# Git for Windows / Git Bash discovery for host PowerShell workflows.

function Test-AccessiblePathRoot {
    param([AllowEmptyString()][AllowNull()][string]$Path)

    if ([string]::IsNullOrWhiteSpace($Path)) {
        return $false
    }

    $root = $Path.Trim().Trim('"').TrimEnd('\')
    if ($root -match '^([A-Za-z]):') {
        $driveRoot = ($matches[1] + ':\')
        if (-not (Test-Path -LiteralPath $driveRoot)) {
            return $false
        }
    }

    return Test-Path -LiteralPath $root
}

function Test-IsWslOrAppsBashStub {
    param([string]$Path)

    if ([string]::IsNullOrWhiteSpace($Path)) {
        return $true
    }

    $normalized = $Path.ToLowerInvariant()
    return (
        $normalized -like '*\windowsapps\*' -or
        $normalized -like '*\system32\bash.exe'
    )
}

function Get-GitInstallRootFromGitExe {
    param([Parameter(Mandatory = $true)][string]$GitExe)

    if (-not (Test-Path -LiteralPath $GitExe)) {
        return $null
    }

    $binDir = (Split-Path -Parent $GitExe).TrimEnd('\')
    $leaf = Split-Path -Leaf $binDir
    switch ($leaf.ToLowerInvariant()) {
        'cmd' { return (Split-Path -Parent $binDir) }
        'bin' {
            $parent = Split-Path -Parent $binDir
            if ((Split-Path -Leaf $parent).ToLowerInvariant() -eq 'mingw64') {
                return (Split-Path -Parent $parent)
            }
            return $parent
        }
    }

    return $null
}

function Get-BashCandidatesFromGitRoot {
    param([Parameter(Mandatory = $true)][string]$GitRoot)

    if (-not (Test-AccessiblePathRoot -Path $GitRoot)) {
        return @()
    }

    $root = $GitRoot.Trim().TrimEnd('\')
    return @(
        (Join-Path $root 'usr\bin\bash.exe'),
        (Join-Path $root 'bin\bash.exe')
    )
}

function Get-GitPathDirsFromRoot {
    param([Parameter(Mandatory = $true)][string]$GitRoot)

    if (-not (Test-AccessiblePathRoot -Path $GitRoot)) {
        return @()
    }

    $root = $GitRoot.Trim().TrimEnd('\')
    return @(
        (Join-Path $root 'cmd'),
        (Join-Path $root 'bin'),
        (Join-Path $root 'mingw64\bin'),
        (Join-Path $root 'usr\bin')
    )
}

function Get-GitForWindowsRegistryInstallPaths {
    $paths = New-Object 'System.Collections.Generic.List[string]'
    $seen = @{}

    function Add-InstallPath {
        param([AllowEmptyString()][AllowNull()][string]$InstallPath)

        if ([string]::IsNullOrWhiteSpace($InstallPath)) {
            return
        }
        $normalized = $InstallPath.Trim().Trim('"').TrimEnd('\')
        $key = $normalized.ToLowerInvariant()
        if ($seen.ContainsKey($key)) {
            return
        }
        $seen[$key] = $true
        if (Test-AccessiblePathRoot -Path $normalized) {
            [void]$paths.Add($normalized)
        }
    }

    foreach ($registryPath in @(
        'HKLM:\SOFTWARE\GitForWindows',
        'HKLM:\SOFTWARE\WOW6432Node\GitForWindows',
        'HKCU:\SOFTWARE\GitForWindows'
    )) {
        if (-not (Test-Path -LiteralPath $registryPath)) {
            continue
        }
        $props = Get-ItemProperty -LiteralPath $registryPath -ErrorAction SilentlyContinue
        if ($null -eq $props) {
            continue
        }
        Add-InstallPath -InstallPath $props.InstallPath
        Add-InstallPath -InstallPath $props.Path
    }

    return @($paths)
}

function Get-CommonGitInstallRoots {
    $roots = New-Object 'System.Collections.Generic.List[string]'
    $seen = @{}

    function Add-Root {
        param([AllowEmptyString()][AllowNull()][string]$Root)

        if ([string]::IsNullOrWhiteSpace($Root)) {
            return
        }
        $normalized = $Root.Trim().TrimEnd('\')
        $key = $normalized.ToLowerInvariant()
        if ($seen.ContainsKey($key)) {
            return
        }
        $seen[$key] = $true
        if (Test-AccessiblePathRoot -Path $normalized) {
            [void]$roots.Add($normalized)
        }
    }

    foreach ($root in @(
        'C:\Program Files\Git',
        'C:\Program Files (x86)\Git',
        'C:\Git'
    )) {
        Add-Root -Root $root
    }

    if (Test-Path -LiteralPath 'D:\') {
        Add-Root -Root 'D:\Git'
    }

    return @($roots)
}

function Test-IsValidGitBashExe {
    param([string]$Path)

    if ([string]::IsNullOrWhiteSpace($Path) -or (Test-IsWslOrAppsBashStub -Path $Path)) {
        return $false
    }
    if (-not (Test-Path -LiteralPath $Path)) {
        return $false
    }

    $info = Get-Item -LiteralPath $Path -ErrorAction SilentlyContinue
    if ($null -eq $info -or $info.Length -lt 1024) {
        return $false
    }

    return $true
}

function Resolve-GitBashExe {
    $candidateList = New-Object 'System.Collections.Generic.List[string]'
    $seen = @{}

    function Add-Candidate {
        param([AllowEmptyString()][AllowNull()][string]$Path)

        if ([string]::IsNullOrWhiteSpace($Path)) {
            return
        }

        $normalized = $Path.Trim().Trim('"')
        if (-not $normalized.EndsWith('.exe', [StringComparison]::OrdinalIgnoreCase)) {
            if (-not (Test-AccessiblePathRoot -Path $normalized)) {
                return
            }
            $normalized = Join-Path $normalized 'bin\bash.exe'
        }

        if (Test-IsWslOrAppsBashStub -Path $normalized) {
            return
        }

        $key = $normalized.ToLowerInvariant()
        if ($seen.ContainsKey($key)) {
            return
        }

        $seen[$key] = $true
        [void]$candidateList.Add($normalized)
    }

    function Add-FromGitRoot {
        param([AllowEmptyString()][AllowNull()][string]$GitRoot)

        foreach ($bash in (Get-BashCandidatesFromGitRoot -GitRoot $GitRoot)) {
            Add-Candidate -Path $bash
        }
    }

    # 1) Explicit environment overrides
    foreach ($name in @('GIT_BASH', 'GIT_HOME', 'GIT_INSTALL_ROOT')) {
        $value = [Environment]::GetEnvironmentVariable($name)
        if ($name -eq 'GIT_BASH') {
            Add-Candidate -Path $value
            continue
        }
        if (-not [string]::IsNullOrWhiteSpace($value)) {
            Add-FromGitRoot -GitRoot $value
        }
    }

    # 2) git.exe on PATH (most reliable on machines with Git installed normally)
    $gitExePaths = New-Object 'System.Collections.Generic.List[string]'
    foreach ($gitCmd in @(Get-Command git -All -ErrorAction SilentlyContinue)) {
        if ($gitCmd.Source) {
            [void]$gitExePaths.Add($gitCmd.Source)
        }
    }

    $whereGit = & where.exe git 2>$null
    if ($LASTEXITCODE -eq 0 -and $whereGit) {
        foreach ($line in @($whereGit)) {
            if (-not [string]::IsNullOrWhiteSpace($line)) {
                [void]$gitExePaths.Add($line.Trim())
            }
        }
    }

    foreach ($gitExe in ($gitExePaths | Select-Object -Unique)) {
        if (-not (Test-Path -LiteralPath $gitExe)) {
            continue
        }
        $gitRoot = Get-GitInstallRootFromGitExe -GitExe $gitExe
        if ($gitRoot) {
            Add-FromGitRoot -GitRoot $gitRoot
        }
    }

    # 3) Git for Windows registry InstallPath
    foreach ($installPath in (Get-GitForWindowsRegistryInstallPaths)) {
        Add-FromGitRoot -GitRoot $installPath
    }

    # 4) Common install locations (D:\Git only when D: exists)
    foreach ($root in (Get-CommonGitInstallRoots)) {
        Add-FromGitRoot -GitRoot $root
    }

    foreach ($candidate in $candidateList) {
        if (Test-IsValidGitBashExe -Path $candidate) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    return $null
}

function Remove-WslBashStubPathEntries {
    param([string]$PathValue)

    if ([string]::IsNullOrWhiteSpace($PathValue)) {
        return ''
    }

    $filtered = foreach ($entry in ($PathValue -split ';')) {
        if ([string]::IsNullOrWhiteSpace($entry)) {
            continue
        }

        $normalized = $entry.Trim().TrimEnd('\')
        $lower = $normalized.ToLowerInvariant()
        if ($lower -like '*\microsoft\windowsapps' -or $lower -like '*\windowsapps\*') {
            continue
        }
        if ($lower -like '*\system32\bash.exe') {
            continue
        }
        $normalized
    }

    return ($filtered -join ';')
}

function Set-GitBashPathPriority {
    param(
        [Parameter(Mandatory = $true)][string]$GitRoot,
        [Parameter(Mandatory = $true)][string]$GitBash
    )

    $env:Path = Remove-WslBashStubPathEntries -PathValue $env:Path

    $priorityDirs = New-Object 'System.Collections.Generic.List[string]'
    $seen = @{}

    function Add-PriorityDir {
        param([string]$Dir)

        if ([string]::IsNullOrWhiteSpace($Dir)) {
            return
        }
        $normalized = $Dir.Trim().TrimEnd('\')
        if (-not (Test-Path -LiteralPath $normalized)) {
            return
        }
        $key = $normalized.ToLowerInvariant()
        if ($seen.ContainsKey($key)) {
            return
        }
        $seen[$key] = $true
        [void]$priorityDirs.Add($normalized)
    }

    Add-PriorityDir -Dir (Split-Path -Parent $GitBash)
    foreach ($dir in (Get-GitPathDirsFromRoot -GitRoot $GitRoot)) {
        Add-PriorityDir -Dir $dir
    }

    $remaining = New-Object 'System.Collections.Generic.List[string]'
    foreach ($entry in ($env:Path -split ';')) {
        if ([string]::IsNullOrWhiteSpace($entry)) {
            continue
        }
        $normalized = $entry.Trim().TrimEnd('\')
        $key = $normalized.ToLowerInvariant()
        if ($seen.ContainsKey($key)) {
            continue
        }
        $seen[$key] = $true
        [void]$remaining.Add($normalized)
    }

    $env:Path = (($priorityDirs + $remaining) -join ';')
}

function Ensure-GitBashOnPath {
    param(
        [string]$ShimDirectory = (Join-Path $PSScriptRoot '.bash-shim'),
        [scriptblock]$WriteDetail = { param($Message) Write-Host "  $Message" }
    )

    $gitBash = Resolve-GitBashExe
    if (-not $gitBash) {
        throw @(
            'Git Bash is required for Maven build steps but bash.exe was not found.'
            'Install Git for Windows (choco install git), set GIT_HOME, or disable the Windows "bash.exe" app execution alias under Settings -> Apps -> App execution aliases.'
        ) -join ' '
    }

    $gitRoot = Split-Path -Parent (Split-Path -Parent $gitBash)
    if ((Split-Path -Leaf (Split-Path -Parent $gitBash)).ToLowerInvariant() -eq 'usr') {
        $gitRoot = Split-Path -Parent $gitRoot
    }

    Set-GitBashPathPriority -GitRoot $gitRoot -GitBash $gitBash
    $env:GIT_BASH = $gitBash

    New-Item -ItemType Directory -Force -Path $ShimDirectory | Out-Null
    $shimPath = Join-Path $ShimDirectory 'bash.cmd'
    Set-Content -LiteralPath $shimPath -Encoding ascii -Value "@echo off`r`n`"$gitBash`" %*"
    if ($env:Path -notlike "*$ShimDirectory*") {
        $env:Path = "$ShimDirectory;$env:Path"
    }

    & $WriteDetail "Git install root:    $gitRoot"
    & $WriteDetail "Git Bash executable: $gitBash"
    & $WriteDetail "bash PATH shim:      $shimPath"

    return $gitBash
}
