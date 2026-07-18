# IcedTea-Web

Free Software implementation of **Java Web Start (JNLP)** — a maintained fork for running legacy desktop applications on modern JDKs (11–21+) when Oracle Java Web Start is unavailable.

This repository continues development from [AdoptOpenJDK/IcedTea-Web](https://github.com/AdoptOpenJDK/IcedTea-Web). Default branch: **`1.8`**.

## What you get

- **`javaws` / `javawsc` launchers** — .NET-based native executables with bundled or system JVM support
- **Control panel** (`itweb-settings`) — JVM selection, JDK assignments, cache, security, running apps
- **Multi-JDK autodetect** — discovers installed JDKs on Windows, macOS, and Linux; per-JNLP JDK overrides
- **Cross-platform packages** — MSI (Windows), DMG/ZIP (macOS), deb/rpm/ZIP (Linux)
- **Sample catalog** — Angular dev server plus signed JNLP samples for manual and automated testing

## Releases

Pre-built installers and checksums are published on [GitHub Releases](https://github.com/martinhickson/IcedTea-Web-1/releases).

Typical Windows install location:

`C:\Program Files\IcedTeaWeb\WebStart\`

Launch a JNLP file:

```powershell
& "C:\Program Files\IcedTeaWeb\WebStart\bin\javaws.exe" -Xtrustall path\to\app.jnlp
```

## Quick start — sample apps

Use the bundled catalog to exercise a local IcedTea-Web install without writing JNLP by hand.

**Prerequisites:** Node.js 20+, JDK 11+ (JDK 17 for some samples), IcedTea-Web installed or on `PATH`.

```powershell
cd sample-apps
npm install
npm start
```

| URL | Purpose |
|-----|---------|
| http://127.0.0.1:4200/ | Sample catalog UI |
| http://127.0.0.1:4200/jnlp/swing-gui/app.jnlp | Swing GUI sample |
| http://127.0.0.1:4200/jnlp/console/app.jnlp | Console / headless sample |

Built JNLP artifacts land in `sample-apps/jnlp-dist/`. See [sample-apps/README.md](sample-apps/README.md) for build and test commands.

## Build from source

### Requirements

| Tool | Version / notes |
|------|-----------------|
| JDK 11 | Compile and Maven runtime (Corretto recommended) |
| Maven 3.9+ | Main build |
| .NET SDK 8 | Native launchers (`dotnet-launcher/`) |
| Git Bash | Required on Windows for Maven helper scripts |
| WiX 4 | Windows MSI (installed by host workflow if missing) |
| Node.js 20+ | Optional — sample-apps only |

### Windows — distribution + MSI (recommended)

Unsigned local build from your checkout:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .powershell\workflows\sign.ps1 -Signing Off -UseLocalSource -SkipToolchain
```

Output:

- `icedtea-web-distribution/target/dist/icedtea-web-*-win-x64.zip`
- `icedtea-web-distribution/target/native-packages/icedtea-web-*-win-x64.msi`

For signed release builds, copy [`.powershell/workflows/sign.env.template`](.powershell/workflows/sign.env.template) to `sign.env` and configure Azure Key Vault. See comments in [`.powershell/workflows/sign.ps1`](.powershell/workflows/sign.ps1).

Install the MSI:

```powershell
msiexec /i "icedtea-web-distribution\target\native-packages\icedtea-web-*-win-x64.msi" /passive
```

### Linux / macOS — Maven distribution

```bash
mvn -P maven-distribution -pl icedtea-web-distribution -am install \
  -Dmaven.test.skip=true -DskipTests \
  -Ditw.dotnet.selfContained=true \
  -Ditw.dotnet.runtime.identifier=linux-x64   # or osx-x64 / osx-arm64
```

Native packages (deb, rpm, DMG) are produced by scripts under [`.packaging/workflows/`](.packaging/workflows/). CI uses the [Release workflow](.github/workflows/release.yml) (manual dispatch) to build all platforms.

### Maven modules (development)

```bash
# Core uber JAR + unit tests
mvn install -pl icedtea-web -am -DskipTests

# Integration tests (separate modules, not in parent reactor)
cd itw-assertj-it && mvn verify
cd itw-autodetect-it && mvn verify    # Windows desktop + JDK 17 autodetect
```

## Testing

| Module | Doc | Scope |
|--------|-----|--------|
| `tests/netx/unit/` | — | JUnit unit tests (via `icedtea-web` module) |
| [itw-assertj-it](itw-assertj-it/README.md) | AssertJ Swing | Control panel, JVM selection, JDK assignments |
| [itw-autodetect-it](itw-autodetect-it/README.md) | Failsafe + javaagent | Windows missing-JDK autodetect dialog |
| [icedtea-web-integration](icedtea-web-integration/README.md) | Process launch | Multi-JDK JNLP launch, .NET handoff |
| [sample-apps](sample-apps/README.md) | npm scripts | End-to-end JNLP launch smoke |

GitHub Actions: [`.github/workflows/build.yml`](.github/workflows/build.yml) (matrix build + smoke) and [`.github/workflows/release.yml`](.github/workflows/release.yml) (release artifacts).

## Repository layout

```
icedtea-web/              Maven uber JAR, control panel, netx runtime
icedtea-web-distribution/ Distribution assembly + native package hooks
dotnet-launcher/          C# javaws/javawsc native launchers
netx/                     JNLP engine (NetX fork)
sample-apps/              Angular catalog + JNLP sample projects
itw-assertj-it/           Swing UI integration tests
itw-autodetect-it/        Windows JDK autodetect integration tests
.powershell/workflows/    Windows host build + sign pipeline
.packaging/workflows/     WiX MSI, Linux deb/rpm, macOS DMG scripts
.github/workflows/        CI and release automation
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for setup, testing expectations, and pull request guidelines.

Bug reports and feature requests: [GitHub Issues](https://github.com/martinhickson/IcedTea-Web-1/issues).

## Legacy upstream

IcedTea-Web originated as a browser plugin and JNLP stack for GNU Classpath. Historical docs:

- [IcedTea-Web wiki](http://icedtea.classpath.org/wiki/IcedTea-Web)
- [FAQ](http://icedtea.classpath.org/wiki/FrequentlyAskedQuestions)

The autotools (`./configure && make`) workflow described in old upstream READMEs is **not** the primary build path in this fork. Use Maven and the workflows above.

## License

LGPLv2+ and GPLv2 with exceptions — see [LICENSE](LICENSE).
