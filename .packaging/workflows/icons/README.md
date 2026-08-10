# IcedTea-Web launcher icons

Official product icon (tea leaves), sourced from `win-installer/icon.ico`.

| File | Use |
|------|-----|
| `icedtea-web.png` | Linux pixmaps / desktop entries (256 px); copied as `javaws.png`, `icedtea-web-settings.png`, `policyeditor.png` |
| `icedtea-web-{16,32,48,64,128,256}.png` | macOS `.icns` generation |
| `icedtea-web.icns` | macOS `.app` bundle (built on macOS via `.packaging/workflows/macos/prepare-macos-icon.sh`) |

Windows embeds `win-installer/icon.ico` via MSBuild `ApplicationIcon` (see `dotnet-launcher/Directory.Build.props`).

Regenerate PNGs from the ICO (Windows):

```powershell
# From repo root; requires System.Drawing (Windows PowerShell / .NET Framework)
$iconsDir = ".packaging/workflows/icons"
$icoPath = "win-installer/icon.ico"
Add-Type -AssemblyName System.Drawing
foreach ($s in 16,32,48,64,128,256) {
  $icon = [System.Drawing.Icon]::new($icoPath, [System.Drawing.Size]::new($s,$s))
  $icon.ToBitmap().Save("$iconsDir/icedtea-web-$s.png", [System.Drawing.Imaging.ImageFormat]::Png)
}
Copy-Item "$iconsDir/icedtea-web-256.png" "$iconsDir/icedtea-web.png" -Force
```
