# IcedTea-Web

[![CI](https://github.com/martinhickson/IcedTea-Web-1/actions/workflows/ci.yml/badge.svg?branch=1.8)](https://github.com/martinhickson/IcedTea-Web-1/actions/workflows/ci.yml)

Free Software implementation of **Java Web Start (JNLP)** — a maintained fork for running legacy desktop applications on modern JDKs (11–21+) when Oracle Java Web Start is unavailable.

**Latest release:** [IcedTea-Web 2.8.3](https://github.com/martinhickson/IcedTea-Web-1/releases/tag/icedtea-web-2.8.3) · [All releases](https://github.com/martinhickson/IcedTea-Web-1/releases)

This repository continues development from [AdoptOpenJDK/IcedTea-Web](https://github.com/AdoptOpenJDK/IcedTea-Web). Default branch: **`1.8`**.

## Why this fork?

Oracle removed Java Web Start after JDK 8, and most upstream IcedTea-Web branches are unmaintained. **This fork is for teams that still depend on signed JNLP desktop apps** and need a supported launcher stack on current JDKs — not a browser plugin revival.

| Capability | What you get |
|------------|--------------|
| **Multi-JDK discovery** | Scans common vendor install locations on Windows, macOS, and Linux (`JvmAutodetector`); honours `JAVA_HOME`, `JDK17_HOME`, and related env vars. |
| **Per-JNLP JDK assignments** | Map each cached JNLP URL to a specific installed JDK in **JDK Assignments**; launch or re-test from the control panel without editing global defaults. |
| **Match strategies** | Choose **exact** or **minimum** JDK version matching when a JNLP declares `<j2se version="…"/>`. |
| **Missing-JDK autodetect** | When a JNLP needs a JDK that is not configured, the launcher can drive an **Autodetect** flow (Windows integration tests cover this path). |
| **Running Apps** | See live JNLP processes, their JVM, heap/RSS usage, **Stop** / **Force Stop**, **Trim Heap**, and **Tune** (max heap, GC) with optional relaunch. |
| **Modern runtime** | Pack200 unpack via [`io.github.martinhickson:pack200`](https://repo1.maven.org/maven2/io/github/martinhickson/pack200/) (Java package `io.pack200`) on JDK 14+; build and test matrix covers JDK 11–21+. |
| **Native launchers** | `javaws` / `javawsc` .NET executables (self-contained or system runtime) — no browser or NPAPI dependency. |
| **Shippable packages** | MSI, DMG/ZIP, deb/rpm from maintained workflows; sample Angular catalog for local smoke testing. |

## What you get

- **`javaws` / `javawsc` launchers** — .NET-based native executables with bundled or system JVM support
- **Control panel** (`itweb-settings`) — JVM selection, JDK assignments, cache, security, running apps
- **Multi-JDK autodetect** — discovers installed JDKs on Windows, macOS, and Linux; per-JNLP JDK overrides
- **Cross-platform packages** — MSI (Windows), DMG/ZIP (macOS), deb/rpm/ZIP (Linux)
- **Sample catalog** — Angular dev server plus signed JNLP samples for manual and automated testing

## Releases

Pre-built installers and checksums are published on [GitHub Releases](https://github.com/martinhickson/IcedTea-Web-1/releases). The current stable tag is **[icedtea-web-2.8.3](https://github.com/martinhickson/IcedTea-Web-1/releases/tag/icedtea-web-2.8.3)** (MSI, DMG, deb, rpm, and ZIP assets).

**2.8.3 highlights:**

- **Multi-JDK relaunch fixes** — correct handling of `18+` / `11+` JNLP version tags when relaunching on a different installed JDK; environment propagation (`DISPLAY`, `JDK*_HOME`, `XDG_*`) for forked `javaws` on Linux CI.
- **Integration test suite green** — AssertJ Swing control-panel ITs (Ubuntu + VNC), multi-JDK process-launch ITs, and Windows JDK autodetect ITs validated in CI.
- **JaCoCo coverage** — unit-test coverage reports uploaded from CI (`-Pcoverage` profile).

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

GitHub Actions:

- [**CI**](.github/workflows/ci.yml) — **`ci` Maven profile** on Ubuntu and Windows (JDK 11) plus Pack200 unpack tests (JDK 17) on every push/PR to `1.8`. Runs with **JaCoCo** (`-Pcoverage`); HTML reports uploaded from Ubuntu JDK 11. Locally: `powershell -File scripts/run-ci-unit-tests.ps1` (add `-Coverage` for the report).
- [**Integration Tests**](.github/workflows/integration.yml) — manual workflow for AssertJ Swing control-panel ITs (Linux + VNC), multi-JDK process-launch ITs, and Windows JDK autodetect ITs. Not run on every PR (GUI / multi-JDK / desktop session); validated green for **2.8.3**.

Unit tests with coverage: `mvn test -pl icedtea-web -am -Pci,coverage` — report at `icedtea-web/target/site/jacoco/index.html`.

Full unit suite (includes excluded CI tests): `mvn test -pl icedtea-web -am`.

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
.github/workflows/        CI, integration, and release automation
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
