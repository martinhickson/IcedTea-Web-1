# IcedTea-Web AssertJ Swing integration tests

Standalone Maven module (not part of the main reactor) that drives the
`itweb-settings` control panel with [AssertJ Swing](https://github.com/assertj/assertj-swing).

## Prerequisites

- JDK 11+ for running tests; JDK 17 and 21 homes for multi-JDK launch cases
- A built and installed `icedtea-web` uber JAR in the local Maven repository
- A graphical display for the default profile (`$DISPLAY` set)

Build and install the main project first:

```bash
cd ..
mvn install -pl icedtea-web -am -DskipTests
```

## Run locally (visible UI)

```bash
cd itw-assertj-it
mvn verify \
  -Ditw.jdk17.home=/path/to/jdk17 \
  -Ditw.jdk21.home=/path/to/jdk21
```

Integration tests are executed by **Failsafe** (`*IT.java`). Unit-test Surefire is disabled.

Each run uses an isolated config home under `target/itw-test-home/` via `-Duser.home=...`.

## Run headless (CI / GitHub runner)

Use the `vnc` profile to start a TigerVNC virtual framebuffer before integration tests:

```bash
mvn verify -Pvnc \
  -Ditw.jdk17.home=/path/to/jdk17 \
  -Ditw.jdk21.home=/path/to/jdk21
```

Requires **TigerVNC** (`tigervnc-standalone-server`, providing `/usr/bin/Xvnc`) and optionally
`x11-utils` (`xdpyinfo`) for fast display readiness checks.

The profile uses `scripts/start-vnc.sh` and `scripts/stop-vnc.sh`.

## What is covered

| Area | Example test classes |
|------|----------------------|
| JVM settings | `ControlPanelJvmSelectionIT`, `ControlPanelJvmAutodetectIT`, `DeploymentAutodetectJdksOnLoadIT` |
| JDK assignments | `ControlPanelJdkAssignmentIT`, `ControlPanelJdkAssignmentLaunchIT`, `ControlPanelJdkAssignmentGuiLaunchIT` |
| Match strategies & overrides | `JvmMatchStrategyIT`, `JvmAssignmentOverrideIT`, `ControlPanelJdkAssignmentOverrideIT` |
| JNLP launch paths | `JdkAssignmentJavawsLaunchIT`, `JdkAutodetectJavawsLaunchIT`, `MultiBytecodeJnlpLaunchIT` |
| Running Apps & cache | `ControlPanelRunningAppsIT`, `FileUrlCacheIT`, `ControlPanelJdkAssignmentCacheListIT` |

## CI

The [**Integration Tests**](https://github.com/martinhickson/IcedTea-Web-1/actions/workflows/integration.yml) workflow runs this module in the **assertj-it** job (Ubuntu + TigerVNC). All ITs passed for release **2.8.3**.

On GitHub Actions, JFileChooser-dependent tests are skipped automatically (`GITHUB_ACTIONS=true`).
