# IcedTea-Web process-launch integration tests

Maven module in the parent reactor that launches JNLP applications via the shaded
IcedTea-Web `javaws` wrapper and the .NET launcher handoff path.

## Prerequisites

- JDK 11+ to run Maven
- Installed JDKs for multi-JDK cases (8, 11, 17, 21) — paths passed as Maven properties
- Built `icedtea-web` uber JAR (the module resolves `javaws` from the distribution layout)

Build the main project first:

```bash
mvn install -pl icedtea-web -am -DskipTests
```

## Run locally

From the repository root (recommended — pulls in `icedtea-web`):

```bash
mvn verify -pl icedtea-web-integration -am \
  -Ditw.jdk8.home=/path/to/jdk8 \
  -Ditw.jdk11.home=/path/to/jdk11 \
  -Ditw.jdk17.home=/path/to/jdk17 \
  -Ditw.jdk21.home=/path/to/jdk21
```

On Windows, set `-Dbash.executable` to Git Bash if Maven helper scripts are invoked.

Integration tests are executed by **Failsafe** (`*IT.java`).

## What is covered

| Test class | Scope |
|------------|--------|
| `MultiJdkJnlpLaunchIT` | Headless JNLP launch under JDK 8, 11, 17, and 21 via local Undertow JNLP host |
| `JnlpRelaunchIT` | Relaunch when JNLP declares a different JRE version |
| `DotnetLauncherDetachIT` | .NET `javaws` handoff, detached launcher, and audit log output |

## CI

The [**Integration Tests**](https://github.com/martinhickson/IcedTea-Web-1/actions/workflows/integration.yml) workflow runs this module in the **process-launch** job (Ubuntu, multi-JDK). Use `-Pcoverage` to collect JaCoCo reports alongside unit tests:

```bash
mvn verify -Pcoverage \
  -Ditw.jdk8.home=... -Ditw.jdk11.home=... \
  -Ditw.jdk17.home=... -Ditw.jdk21.home=...
```

## Coverage note

JaCoCo instruments the **test JVM**. These ITs fork external `javaws` processes, so runtime `netx/` code inside the uber JAR is not counted unless the agent is attached to those subprocesses separately.
