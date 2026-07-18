# Contributing to IcedTea-Web

Thank you for helping maintain Java Web Start on modern JDKs. This fork uses **Maven**, **.NET launchers**, and GitHub Actions — not the legacy autotools workflow from upstream IcedTea-Web.

Start with [README.md](README.md) for build commands, sample apps, and repository layout.

## Before you start

- **Default branch:** `1.8` — branch from and target PRs at `1.8` unless a maintainer asks otherwise.
- **License:** Contributions are under the same terms as the project ([LICENSE](LICENSE): LGPLv2+ / GPLv2 with exceptions).
- **`sample-apps/tensor4j/`:** A nested standalone repo ([tensor4j/tensor4j](https://github.com/tensor4j/tensor4j)). Run git operations inside that directory; do not mix tensor4j source changes into IcedTea-Web commits unless you are only wiring sample-app integration (JNLP, Angular catalog entry).

## Development setup

| Platform | Typical build |
|----------|----------------|
| **Windows** | `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .powershell\workflows\sign.ps1 -Signing Off -UseLocalSource -SkipToolchain` |
| **Linux / macOS** | `mvn -P maven-distribution -pl icedtea-web-distribution -am install -Dmaven.test.skip=true -DskipTests` plus packaging scripts under `.packaging/workflows/` |

**Requirements:** JDK 11+, Maven 3.9+, .NET SDK 8. On Windows you also need **Git Bash** (for Maven helper scripts) and **WiX 4** (the host workflow can install it).

Fast iteration on Java runtime code:

```bash
mvn install -pl icedtea-web -am -DskipTests
```

Use `.powershell/workflows/maven-settings.xml` on Windows if Maven fails to resolve the Pack200 dependency.

## What to test

Test the areas your change touches. You do not need every platform for every PR, but call out gaps in the PR test plan.

| Change type | Suggested verification |
|-------------|------------------------|
| `netx/` runtime, control panel | `mvn install -pl icedtea-web -am -DskipTests`; relevant unit tests under `tests/netx/unit/` |
| Control panel / Swing UI | [itw-assertj-it](itw-assertj-it/README.md): `cd itw-assertj-it && mvn verify` |
| Windows JDK autodetect | [itw-autodetect-it](itw-autodetect-it/README.md) (Windows desktop session required) |
| Launcher / handoff | [icedtea-web-integration](icedtea-web-integration/README.md) or `scripts/run-windows-dotnet-jnlp-smoke.ps1` |
| JNLP samples / dev UX | [sample-apps](sample-apps/README.md): `npm run build:jnlp`, `npm run test:console` |
| Windows MSI / packaging | Full `sign.ps1 -Signing Off -UseLocalSource`; install MSI and smoke-launch a JNLP |
| `.github/workflows/` | Describe which workflow you exercised; Release runs on PRs to `1.8` |

## Code guidelines

- **Match existing style** in the file you edit (naming, imports, error handling).
- **Keep diffs focused** — one logical change per PR when possible.
- **Prefer extending existing helpers** over duplicating logic (see `netx/net/sourceforge/jnlp/util/`).
- **Comments:** only for non-obvious behaviour; let code and tests explain the rest.
- **Tests:** add or update unit/IT coverage when fixing a bug or changing observable behaviour. Skip tests that only restate the obvious.

## Pull requests

1. Fork and create a feature branch from `1.8`.
2. Commit with a clear message — imperative subject, body explaining **why** when it is not obvious. Examples from this repo:
   - `Fix Windows JDK selection so listed JDK 17 installs are used at launch`
   - `Improve legacy JNLP compatibility for JDK 11+ and JDK selection`
3. Open a PR against `1.8` with:
   - **Summary** — what changed and why (1–3 bullets).
   - **Test plan** — commands run and results (platform, JDK version, pass/fail).
   - **Screenshots** — for control panel or Running Apps UI changes.
4. Link related **GitHub Issues** when applicable.

Maintainers may ask for a rebuild on a specific OS before merge.

## Reporting bugs

Use [GitHub Issues](https://github.com/martinhickson/IcedTea-Web-1/issues). Include:

- OS and IcedTea-Web version (or commit SHA)
- JDK version(s) installed
- JNLP URL or minimal repro steps
- Relevant log excerpts (`~/.config/icedtea-web` or Windows `%USERPROFILE%\.config\icedtea-web`, plus launcher handoff logs if applicable)

## Releases

Release builds are triggered manually via the [Release workflow](.github/workflows/release.yml). Maintainers cut tags and upload MSI/DMG/deb/rpm assets. Contributors normally do not need to run signed release builds; unsigned local MSI builds are enough for validation.

## Questions

Open a [GitHub Issue](https://github.com/martinhickson/IcedTea-Web-1/issues) for design questions or blocked PRs. For historical upstream context, see the [IcedTea-Web wiki](http://icedtea.classpath.org/wiki/IcedTea-Web).
