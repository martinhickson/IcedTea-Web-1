$ErrorActionPreference = "Stop"

$env:DOTNET_ROOT = if ($env:DOTNET_ROOT) { $env:DOTNET_ROOT } else { "C:\dotnet" }
$env:DOTNET_CLI_TELEMETRY_OPTOUT = "1"
$dotnetTools = "C:\Users\ContainerAdministrator\.dotnet\tools"
$pathEntries = @($env:DOTNET_ROOT, $dotnetTools, $env:PATH) | Where-Object { $_ }
$env:PATH = ($pathEntries -join ";")

$RootDir = if ($env:ITW_WORKSPACE) { $env:ITW_WORKSPACE } else { "C:\workspace" }
$Version = if ($env:ITW_VERSION) { $env:ITW_VERSION } else { "2.0.1-SNAPSHOT" }

function Resolve-WorkspacePath {
    param(
        [string]$ConfiguredPath,
        [string]$DefaultPath
    )

    if ([string]::IsNullOrWhiteSpace($ConfiguredPath)) {
        return $DefaultPath
    }

    if (Test-Path -LiteralPath $ConfiguredPath) {
        return $ConfiguredPath
    }

    Write-Warning "Configured path does not exist inside container: $ConfiguredPath. Using $DefaultPath"
    return $DefaultPath
}

$DistDir = Resolve-WorkspacePath -ConfiguredPath $env:ITW_DIST_DIR -DefaultPath (Join-Path $RootDir "icedtea-web-distribution\target\dist\icedtea-web-$Version")
$OutputDir = Resolve-WorkspacePath -ConfiguredPath $env:ITW_NATIVE_OUTPUT_DIR -DefaultPath (Join-Path $RootDir "icedtea-web-distribution\target\native-packages")
$PackageName = if ($env:ITW_PACKAGE_NAME) { $env:ITW_PACKAGE_NAME } else { "IcedTea-Web" }
$Manufacturer = if ($env:ITW_PACKAGE_MANUFACTURER) { $env:ITW_PACKAGE_MANUFACTURER } else { "IcedTea-Web Maintainers" }
$PackageId = "IcedTeaWeb"
$UpgradeCode = "6F7858B2-4764-4D75-9F3A-E8B87BB71D89"
# Match win-installer/installer.json.in: vendorDirName + installDirName (no hyphen in path)
# and environmentVariables PATH append of [INSTALLDIR]bin.
$VendorDirName = if ($env:ITW_VENDOR_DIR_NAME) { $env:ITW_VENDOR_DIR_NAME } else { "IcedTeaWeb" }
$InstallDirName = if ($env:ITW_INSTALL_DIR_NAME) { $env:ITW_INSTALL_DIR_NAME } else { "WebStart" }
# Stable GUID so upgrades keep the same PATH Environment component identity.
$PathEnvironmentComponentGuid = "8C2E4F91-6A7B-4D3E-9F1C-2B5A8D0E7C34"
$SafeVersion = ($Version -replace "-SNAPSHOT$", ".0" -replace "[^0-9.]", ".")
if ($SafeVersion -notmatch "^\d+\.\d+\.\d+(\.\d+)?$") {
    $SafeVersion = "1.0.1.0"
}

foreach ($required in @("javaws.exe", "javawsc.exe", "itweb-settings.exe", "policyeditor.exe")) {
    if (-not (Test-Path (Join-Path $DistDir "bin\$required") -PathType Leaf)) {
        throw "Distribution does not contain bin\$required`: $DistDir"
    }
}

function Test-RequireSignedDistributionLaunchers {
    param([string]$Value = $env:ITW_REQUIRE_SIGNED_DIST)

    if ([string]::IsNullOrWhiteSpace($Value)) {
        return $false
    }

    switch ($Value.Trim().ToLowerInvariant()) {
        '1' { return $true }
        'true' { return $true }
        'yes' { return $true }
        'on' { return $true }
        default { return $false }
    }
}

if (Test-RequireSignedDistributionLaunchers) {
    $unsigned = @()
    foreach ($required in @("javaws.exe", "javawsc.exe", "itweb-settings.exe", "policyeditor.exe")) {
        $launcherPath = Join-Path $DistDir "bin\$required"
        $signature = Get-AuthenticodeSignature -LiteralPath $launcherPath
        if ($signature.Status -ne 'Valid') {
            $unsigned += "$required ($($signature.Status))"
        } else {
            Write-Host "Verified Authenticode signature before MSI build: $launcherPath"
        }
    }

    if ($unsigned.Count -gt 0) {
        throw @(
            "Distribution launchers must be Authenticode-signed before building the MSI."
            "Unsigned: $($unsigned -join ', ')"
            "Dist dir: $DistDir"
            "Run the signing workflow Step 2 (-Phase Distribution) against this dist tree first."
        ) -join ' '
    }
}

New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
$WxsPath = Join-Path $OutputDir "icedtea-web.wxs"
$MsiPath = Join-Path $OutputDir "icedtea-web-$Version-win-x64.msi"

function New-StableId {
    param([string]$Prefix, [string]$Value)
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = $sha256.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($Value))
        $hex = -join ($bytes[0..11] | ForEach-Object { $_.ToString("x2") })
        return "$Prefix$hex"
    } finally {
        $sha256.Dispose()
    }
}

function Convert-ToWixPath {
    param([string]$Path)
    return $Path.Replace("\", "\\")
}

function Escape-Xml {
    param([string]$Value)
    return [System.Security.SecurityElement]::Escape($Value)
}

function Get-RelativePath {
    param(
        [string]$BasePath,
        [string]$Path
    )

    $baseFullPath = [System.IO.Path]::GetFullPath($BasePath)
    if (-not $baseFullPath.EndsWith([System.IO.Path]::DirectorySeparatorChar)) {
        $baseFullPath += [System.IO.Path]::DirectorySeparatorChar
    }
    $pathFullPath = [System.IO.Path]::GetFullPath($Path)
    $baseUri = New-Object System.Uri($baseFullPath)
    $pathUri = New-Object System.Uri($pathFullPath)
    $relativeUri = $baseUri.MakeRelativeUri($pathUri).ToString()
    if ([string]::IsNullOrWhiteSpace($relativeUri)) {
        return "."
    }
    return [System.Uri]::UnescapeDataString($relativeUri).Replace("/", "\")
}

$files = Get-ChildItem -Path $DistDir -Recurse | Where-Object { -not $_.PSIsContainer } | Sort-Object FullName
$directories = @{}
foreach ($file in $files) {
    $relativeDir = Get-RelativePath $DistDir $file.DirectoryName
    if ($relativeDir -eq ".") {
        continue
    }
    $parts = $relativeDir -split "[\\/]"
    $currentPath = ""
    foreach ($part in $parts) {
        $currentPath = if ($currentPath) { Join-Path $currentPath $part } else { $part }
        if (-not $directories.ContainsKey($currentPath)) {
            $directories[$currentPath] = New-StableId "Dir" $currentPath
        }
    }
}

$dirXml = New-Object System.Text.StringBuilder
foreach ($entry in $directories.GetEnumerator() | Sort-Object { ($_.Key -split "[\\/]").Count }, Key) {
    $relative = $entry.Key
    $name = Escape-Xml (Split-Path $relative -Leaf)
    $parent = Split-Path $relative -Parent
    $parentId = if ($parent) { $directories[$parent] } else { "INSTALLFOLDER" }
    [void]$dirXml.AppendLine("    <DirectoryRef Id=`"$parentId`">")
    [void]$dirXml.AppendLine("      <Directory Id=`"$($entry.Value)`" Name=`"$name`" />")
    [void]$dirXml.AppendLine("    </DirectoryRef>")
}

$launcherIconId = "IcedTeaWebIcon.exe"
$launcherIconSource = Escape-Xml (Convert-ToWixPath (Join-Path $DistDir "bin\javaws.exe"))
$iconsXml = "    <Icon Id=`"$launcherIconId`" SourceFile=`"$launcherIconSource`" />`n"

$componentsXml = New-Object System.Text.StringBuilder
$componentRefsXml = New-Object System.Text.StringBuilder
foreach ($file in $files) {
    $relativeFile = Get-RelativePath $DistDir $file.FullName
    $relativeDir = Get-RelativePath $DistDir $file.DirectoryName
    $normalizedRelativeFile = $relativeFile.Replace("/", "\")
    $directoryId = if ($relativeDir -eq ".") { "INSTALLFOLDER" } else { $directories[$relativeDir] }
    $componentId = New-StableId "Cmp" $relativeFile
    $fileId = New-StableId "File" $relativeFile
    $source = Escape-Xml (Convert-ToWixPath $file.FullName)
    [void]$componentsXml.AppendLine("    <Component Id=`"$componentId`" Directory=`"$directoryId`" Guid=`"*`">")
    [void]$componentsXml.AppendLine("      <File Id=`"$fileId`" Source=`"$source`" KeyPath=`"yes`" />")
    # Non-advertised shortcuts: advertised + byte-identical javaws copies caused flaky installs
    # (policyeditor components sometimes not laid down on first pass).
    if ($normalizedRelativeFile -ieq "bin\javaws.exe") {
        [void]$componentsXml.AppendLine("      <Shortcut Id=`"JavawsStartMenuShortcut`" Directory=`"ProgramMenuFolder`" Name=`"IcedTea-Web Java Web Start`" Target=`"[#$fileId]`" WorkingDirectory=`"INSTALLFOLDER`" Icon=`"$launcherIconId`" IconIndex=`"0`" />")
    }
    if ($normalizedRelativeFile -ieq "bin\itweb-settings.exe") {
        [void]$componentsXml.AppendLine("      <Shortcut Id=`"SettingsStartMenuShortcut`" Directory=`"ProgramMenuFolder`" Name=`"IcedTea-Web Control Panel`" Target=`"[#$fileId]`" WorkingDirectory=`"INSTALLFOLDER`" Icon=`"$launcherIconId`" IconIndex=`"0`" />")
    }
    if ($normalizedRelativeFile -ieq "bin\policyeditor.exe") {
        [void]$componentsXml.AppendLine("      <Shortcut Id=`"PolicyEditorStartMenuShortcut`" Directory=`"ProgramMenuFolder`" Name=`"IcedTea-Web Policy Editor`" Target=`"[#$fileId]`" WorkingDirectory=`"INSTALLFOLDER`" Icon=`"$launcherIconId`" IconIndex=`"0`" />")
    }
    [void]$componentsXml.AppendLine("    </Component>")
    [void]$componentRefsXml.AppendLine("      <ComponentRef Id=`"$componentId`" />")
}

# Restore legacy win-installer PATH registration (installer.json.in environmentVariables).
# Appends [INSTALLFOLDER]bin to the machine PATH; removed on uninstall.
$pathComponentId = "CmpPathEnvironment"
[void]$componentsXml.AppendLine("    <Component Id=`"$pathComponentId`" Directory=`"INSTALLFOLDER`" Guid=`"$PathEnvironmentComponentGuid`" KeyPath=`"yes`">")
[void]$componentsXml.AppendLine("      <Environment Id=`"IcedTeaWebPath`" Name=`"PATH`" Value=`"[INSTALLFOLDER]bin`" Permanent=`"no`" Part=`"last`" Action=`"set`" System=`"yes`" />")
[void]$componentsXml.AppendLine("    </Component>")
[void]$componentRefsXml.AppendLine("      <ComponentRef Id=`"$pathComponentId`" />")

@"
<?xml version="1.0" encoding="UTF-8"?>
<Wix xmlns="http://wixtoolset.org/schemas/v4/wxs">
  <Package Name="$(Escape-Xml $PackageName)" Manufacturer="$(Escape-Xml $Manufacturer)" Version="$SafeVersion" UpgradeCode="$UpgradeCode" Scope="perMachine">
    <SummaryInformation Description="$(Escape-Xml "$PackageName installer")" Manufacturer="$(Escape-Xml $Manufacturer)" />
    <MajorUpgrade DowngradeErrorMessage="A newer version of $(Escape-Xml $PackageName) is already installed." />
    <MediaTemplate EmbedCab="yes" />

$iconsXml
    <StandardDirectory Id="ProgramFiles64Folder">
      <Directory Id="VENDORFOLDER" Name="$(Escape-Xml $VendorDirName)">
        <Directory Id="INSTALLFOLDER" Name="$(Escape-Xml $InstallDirName)" />
      </Directory>
    </StandardDirectory>
    <StandardDirectory Id="ProgramMenuFolder" />

$dirXml

$componentsXml

    <Feature Id="MainFeature" Title="$(Escape-Xml $PackageName)" Level="1">
$componentRefsXml
    </Feature>
  </Package>
</Wix>
"@ | Set-Content -Path $WxsPath -Encoding UTF8

$WixExe = if (Test-Path "C:\Users\ContainerAdministrator\.dotnet\tools\wix.exe" -PathType Leaf) {
    "C:\Users\ContainerAdministrator\.dotnet\tools\wix.exe"
} else {
    "wix"
}

& $WixExe build -acceptEula wix7 $WxsPath -arch x64 -o $MsiPath
if ($LASTEXITCODE -ne 0) {
    throw "WiX MSI build failed."
}

Write-Host "Built MSI: $MsiPath"
