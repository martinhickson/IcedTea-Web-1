$ErrorActionPreference = "Stop"

$RootDir = if ($env:ITW_WORKSPACE) { $env:ITW_WORKSPACE } else { "C:\workspace" }
$Version = if ($env:ITW_VERSION) { $env:ITW_VERSION } else { "1.0.1-SNAPSHOT" }
$DistDir = if ($env:ITW_DIST_DIR) {
    $env:ITW_DIST_DIR
} else {
    Join-Path $RootDir "icedtea-web-distribution\target\dist\icedtea-web-maven-$Version"
}
$OutputDir = if ($env:ITW_NATIVE_OUTPUT_DIR) {
    $env:ITW_NATIVE_OUTPUT_DIR
} else {
    Join-Path $RootDir "icedtea-web-distribution\target\native-packages"
}
$PackageName = if ($env:ITW_PACKAGE_NAME) { $env:ITW_PACKAGE_NAME } else { "IcedTea-Web" }
$Manufacturer = if ($env:ITW_PACKAGE_MANUFACTURER) { $env:ITW_PACKAGE_MANUFACTURER } else { "IcedTea-Web Maintainers" }
$PackageId = "IcedTeaWeb"
$UpgradeCode = "6F7858B2-4764-4D75-9F3A-E8B87BB71D89"
$InstallDirName = "IcedTea-Web"
$SafeVersion = ($Version -replace "-SNAPSHOT$", ".0" -replace "[^0-9.]", ".")
if ($SafeVersion -notmatch "^\d+\.\d+\.\d+(\.\d+)?$") {
    $SafeVersion = "1.0.1.0"
}

if (-not (Test-Path (Join-Path $DistDir "bin\javaws.exe") -PathType Leaf)) {
    throw "Distribution does not contain bin\javaws.exe: $DistDir"
}

New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
$WxsPath = Join-Path $OutputDir "icedtea-web-dotnet.wxs"
$MsiPath = Join-Path $OutputDir "icedtea-web-dotnet-$Version-win-x64.msi"

function New-StableId {
    param([string]$Prefix, [string]$Value)
    $bytes = [System.Security.Cryptography.SHA256]::HashData([System.Text.Encoding]::UTF8.GetBytes($Value))
    $hex = -join ($bytes[0..11] | ForEach-Object { $_.ToString("x2") })
    return "$Prefix$hex"
}

function Convert-ToWixPath {
    param([string]$Path)
    return $Path.Replace("\", "\\")
}

function Escape-Xml {
    param([string]$Value)
    return [System.Security.SecurityElement]::Escape($Value)
}

$files = Get-ChildItem -Path $DistDir -File -Recurse | Sort-Object FullName
$directories = @{}
foreach ($file in $files) {
    $relativeDir = [System.IO.Path]::GetRelativePath($DistDir, $file.DirectoryName)
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

$componentsXml = New-Object System.Text.StringBuilder
$componentRefsXml = New-Object System.Text.StringBuilder
foreach ($file in $files) {
    $relativeFile = [System.IO.Path]::GetRelativePath($DistDir, $file.FullName)
    $relativeDir = [System.IO.Path]::GetRelativePath($DistDir, $file.DirectoryName)
    $normalizedRelativeFile = $relativeFile.Replace("/", "\")
    $directoryId = if ($relativeDir -eq ".") { "INSTALLFOLDER" } else { $directories[$relativeDir] }
    $componentId = New-StableId "Cmp" $relativeFile
    $fileId = New-StableId "File" $relativeFile
    $source = Escape-Xml (Convert-ToWixPath $file.FullName)
    [void]$componentsXml.AppendLine("    <Component Id=`"$componentId`" Directory=`"$directoryId`" Guid=`"*`">")
    [void]$componentsXml.AppendLine("      <File Id=`"$fileId`" Source=`"$source`" KeyPath=`"yes`" />")
    if ($normalizedRelativeFile -ieq "bin\javaws.exe") {
        [void]$componentsXml.AppendLine("      <Shortcut Id=`"JavawsStartMenuShortcut`" Directory=`"ProgramMenuFolder`" Name=`"IcedTea-Web Java Web Start`" WorkingDirectory=`"INSTALLFOLDER`" Advertise=`"yes`" />")
    }
    if ($normalizedRelativeFile -ieq "bin\itweb-settings.exe") {
        [void]$componentsXml.AppendLine("      <Shortcut Id=`"SettingsStartMenuShortcut`" Directory=`"ProgramMenuFolder`" Name=`"IcedTea-Web Settings`" WorkingDirectory=`"INSTALLFOLDER`" Advertise=`"yes`" />")
    }
    [void]$componentsXml.AppendLine("    </Component>")
    [void]$componentRefsXml.AppendLine("      <ComponentRef Id=`"$componentId`" />")
}

@"
<?xml version="1.0" encoding="UTF-8"?>
<Wix xmlns="http://wixtoolset.org/schemas/v4/wxs">
  <Package Name="$(Escape-Xml $PackageName)" Manufacturer="$(Escape-Xml $Manufacturer)" Version="$SafeVersion" UpgradeCode="$UpgradeCode" Scope="perMachine">
    <SummaryInformation Description="$(Escape-Xml "$PackageName installer")" Manufacturer="$(Escape-Xml $Manufacturer)" />
    <MajorUpgrade DowngradeErrorMessage="A newer version of $(Escape-Xml $PackageName) is already installed." />
    <MediaTemplate EmbedCab="yes" />

    <StandardDirectory Id="ProgramFiles64Folder">
      <Directory Id="INSTALLFOLDER" Name="$(Escape-Xml $InstallDirName)" />
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

wix build $WxsPath -arch x64 -o $MsiPath
if ($LASTEXITCODE -ne 0) {
    throw "WiX MSI build failed."
}

Write-Host "Built MSI: $MsiPath"
