# IcedTea-Web Windows JDK autodetect integration tests

Standalone Maven module (not part of the main reactor) that exercises the
**missing-JDK Autodetect** dialog on Windows:

1. Undertow serves a signed JNLP app that requires **JDK 17** (exact; not `17+`)
2. `javaws.exe` launches it with **no configured JDKs** (starter JVM is JDK 11)
3. A small **javaagent** (`*-dialog-agent.jar`) injected into javaws drives the
   real Swing dialogs: Byte Buddy arms a clicker on `showOptionDialog`, which
   presses **Apply** / **Autodetect** via `AbstractButton.doClick()` on the
   dialog EDT (no Access Bridge)
4. Asserts the app starts on JDK 17
5. Opens the ITW control panel and asserts the autodetected JDK 17 is listed

Failsafe ignores test failures by default (`itw.autodetect.it.failure.ignore=true`).
Set `-Ditw.autodetect.it.failure.ignore=false` for strict CI runs (used by the Integration Tests workflow).

## Prerequisites

- Windows desktop session (dialogs must be able to show)
- JDK 11 (starter for `javaws`) and a discoverable JDK 17
- Built Windows distribution with `javaws.exe` (unsigned is fine for this IT)

## Build distribution without signing

Use either approach:

**Maven distribution only** (no signing steps):

```powershell
mvn -P maven-distribution -pl icedtea-web-distribution -am install `
  -Dmaven.test.skip=true -DskipTests `
  "-Djdk11.home=$env:JAVA_HOME" `
  "-Ditw.dotnet.runtime.identifier=win-x64" `
  "-Ditw.dotnet.selfContained=true"
```

**Host PowerShell workflow without signing** — already supported via dry run:

```powershell
# In .powershell\workflows\sign.env:
ITW_DRY_RUN=true

powershell.exe -NoProfile -ExecutionPolicy Bypass -File .powershell\workflows\sign.ps1
```

`ITW_DRY_RUN=true` runs the full Maven/MSI build but skips Azure Key Vault /
launcher signing. That is the supported “build without signing” path for local
integration tests.

Override the launcher if needed:

```powershell
$env:ITW_JAVAWS_BIN = "C:\path\to\javaws.exe"
# or pass -Ditw.javaws.bin=...
```

## Run the integration test

```powershell
cd itw-autodetect-it
mvn verify `
  "-Ditw.jdk11.home=C:\Program Files\Eclipse Adoptium\jdk-11.0.17.8-hotspot" `
  "-Ditw.jdk17.home=C:\path\to\jdk-17"
```

Override paths to match local installs. Both JDK 11 and JDK 17 must exist or the test is skipped.

Strict verify (no failure ignore):

```powershell
mvn verify "-Ditw.autodetect.it.failure.ignore=false"
```

Each run uses an isolated config home under `target/itw-test-home/` via
`-Duser.home=...` / `XDG_CONFIG_HOME`.

## CI

The [**Integration Tests**](https://github.com/martinhickson/IcedTea-Web-1/actions/workflows/integration.yml) workflow runs this module in the **autodetect-windows** job. The strict path (`itw.autodetect.it.failure.ignore=false`) passed for release **2.8.3**.

Also covered in that same Windows job:

- `SqliteCacheCatalogDualJvmIT` — two-process insert/count/find against `{cachedir}/db/`, legacy-tree isolation, kill-switch (`createForTests(false, …)`)
- After a successful JNLP launch, asserts `cache/db/cache_catalog.sqlite` exists and numbered dirs live under `db/`

## Manual sample host (no javaws)

Undertow is started without `javaws`; a landing-page URL and JNLP link are
printed, and the host stays up so that the URL can be launched manually.

```powershell
cd itw-autodetect-it
mvn -Psample-app verify "-Ditw.jdk17.home=C:\itw-jdks\jdk-17"
```
