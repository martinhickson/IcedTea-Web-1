# IcedTea-Web AssertJ Swing integration tests

Standalone Maven module (not part of the main reactor) that drives the
`itweb-settings` control panel with [AssertJ Swing](https://github.com/assertj/assertj-swing).

## Prerequisites

- JDK 11+ for running tests
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
mvn verify
```

Integration tests are executed by **Failsafe** (`*IT.java`). Unit-test Surefire is disabled.

Each run uses an isolated config home under `target/itw-test-home/` via `-Duser.home=...`.

## Run headless (CI / GitHub runner)

Use the `vnc` profile to start a TigerVNC virtual framebuffer before integration tests:

```bash
mvn verify -Pvnc
```

Requires **TigerVNC** (`tigervnc-standalone-server`, providing `/usr/bin/Xvnc`) and optionally
`x11-utils` (`xdpyinfo`) for fast display readiness checks.

The profile uses `scripts/start-vnc.sh` and `scripts/stop-vnc.sh`.

## What is covered

`ControlPanelJvmSelectionIT` opens the control panel, selects **JVM Settings**, adds the
current `java.home` through the file chooser, clicks **Apply**, and verifies
`deployment.jdk.1` and `deployment.jre.dir` in `deployment.properties`.
