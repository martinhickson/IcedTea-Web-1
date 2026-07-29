param(
    [Parameter(Mandatory = $true)]
    [string]$MsiPath
)

# Verifies a built IcedTea-Web MSI registers machine PATH for [INSTALLFOLDER]bin.
# Throws if the Environment table entry is missing (the 2.9.5 release regression).
$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $MsiPath -PathType Leaf)) {
    throw "MSI not found: $MsiPath"
}

$installer = New-Object -ComObject WindowsInstaller.Installer
$msiDb = $installer.OpenDatabase((Resolve-Path -LiteralPath $MsiPath).Path, 0)
try {
    $envView = $msiDb.OpenView('SELECT `Name`, `Value` FROM Environment')
    $envView.Execute() | Out-Null
    $pathEntry = $null
    while (($rec = $envView.Fetch()) -ne $null) {
        $name = $rec.StringData(1)
        $value = $rec.StringData(2)
        # MSI Environment.Name includes =*+- flag prefixes.
        if ($name -match "PATH" -and $value -match "INSTALLFOLDER") {
            $pathEntry = "$name=$value"
            break
        }
    }
    $envView.Close()
    if (-not $pathEntry) {
        throw "MSI is missing machine PATH Environment entry for [INSTALLFOLDER]bin: $MsiPath"
    }
    Write-Host "Verified MSI PATH registration: $pathEntry"
}
catch {
    if ($_.Exception.Message -match "OpenView") {
        throw "MSI has no Environment table (PATH component was not compiled): $MsiPath"
    }
    throw
}
finally {
    if ($msiDb) {
        [System.Runtime.InteropServices.Marshal]::ReleaseComObject($msiDb) | Out-Null
    }
    if ($installer) {
        [System.Runtime.InteropServices.Marshal]::ReleaseComObject($installer) | Out-Null
    }
}
