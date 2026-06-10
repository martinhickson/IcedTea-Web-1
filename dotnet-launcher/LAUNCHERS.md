# IcedTea-Web launchers: javaws vs javawsc

**Runnable layout:** `test-dist/bin/` (synced from merged publish output below)

| Binary | Subsystem | Role |
|--------|-----------|------|
| `javaws.exe` | WinExe (GUI) | Explorer double-click, JNLP, desktop — no console flash |
| `javawsc.exe` | Exe (console) | cmd/bash CLI — `-version`, `-help`, scripts; **cmd waits** on this binary |
| `itweb-settings.exe` | WinExe (GUI) | Copy of `javaws.exe`; `CommandLine` / `ControlPanel` main class (chosen from binary name) |
| `policyeditor.exe` | WinExe (GUI) | Copy of `javaws.exe`; `PolicyEditor` main class (chosen from binary name) |

Pattern mirrors `java.exe` / `javaw.exe`: GUI vs console entry points. `itweb-settings` and `policyeditor` are not separate builds — Maven and `publish-dotnet-launchers.ps1` copy `javaws` to each alias.

## javaws.exe (GUI)

- `OutputType=WinExe`, build flag `ITW_LAUNCHER_GUI`
- **Explorer / double-click:** `java.exe` + null stdio + `CREATE_NO_WINDOW` (no console flash)
- **Launched from cmd/bash:** `AttachConsole(ATTACH_PARENT_PROCESS)` then inherited stdio — matches Rust; `javaws --version` prints normally
- **JDK major probe:** `javaw.exe` + uber jar `--java-version` when not in a console; `java -version` when console-attached and args are console-only

## javawsc.exe (console)

- `OutputType=Exe`, build flag `ITW_LAUNCHER_CONSOLE`
- Spawns `java.exe` with **inherited stdio** + `WaitForExit()` — shell waits naturally
- **No args:** same JNLP file chooser as `javaws` (`JavawsUberLauncher` accepts `icedtea-web.bin.name=javawsc`)
- **JDK major probe:** `java -version` when args are console-only; else uber jar `--java-version` via `java.exe`
- Linux and Windows from the same `Program.cs` / `JavawscLauncher.csproj`

## Build (Maven — canonical)

Both launchers are published during `icedtea-web-distribution` packaging:

```
mvn -P maven-distribution -pl icedtea-web-distribution -am package -DskipTests \
  -Djdk11.home=/path/to/jdk11
```

Maven and the local publish script use **separate publish output folders** and **`dotnet clean` both projects between GUI and console publish** (they share `dotnet-launcher/bin/Release/.../win-x64`; without a clean, the wrong apphost subsystem can leak across builds). Never publish both projects to the same `-o` path either — that corrupts `javaws.exe`.

| Stage | Maven | Local dev (`scripts/publish-dotnet-launchers.ps1`) |
|-------|-------|-----------------------------------------------------|
| GUI publish | `target/dotnet-publish` | `test-dist/dotnet-publish` |
| Console publish | `target/dotnet-publish-console` | `test-dist/dotnet-publish-console` |
| Merge | `javawsc*` → `dotnet-publish` | same |
| Runnable tree | `target/dist/.../bin/` | `test-dist/bin/` (synced from merged `dotnet-publish`) |

Local dev shortcut (Windows): `scripts/publish-dotnet-launchers.ps1`

**Icons (official tea-leaf product icon from `win-installer/icon.ico`):**

| Platform | Asset | Where it lands |
|----------|-------|----------------|
| Windows | `win-installer/icon.ico` embedded in PE | `ApplicationIcon` in `Directory.Build.props` (win RID only) + local publish script |
| Linux | `packaging/icons/icedtea-web.png` | `share/pixmaps/` in dist (`javaws`, `itweb-settings`, `policyeditor`); DEB/RPM install `/usr/share/pixmaps/` + `Icon=` in `.desktop` files |
| macOS | `packaging/icons/*.png` → `icedtea-web.icns` via `prepare-macos-icon.sh` | `.app` `Resources/icedtea-web.icns` + `CFBundleIconFile` in DMG build |

`javawsc` is published on all platforms (merged into `dotnet-publish` before assembly). Linux packages symlink `/usr/bin/javawsc`; macOS DMG includes `bin/javawsc` and a `MacOS/javawsc` wrapper script. Legacy repo-root `javaws.ico` / `javaws.png` (Duke mascot) is not used.

## CLI usage

```
javaws.exe --version    # works from cmd (Rust-compatible); use javawsc for scripts that must block/wait
javawsc.exe --version   # canonical console entry; cmd waits on this binary
javawsc.exe -help
javaws.exe foo.jnlp
```
