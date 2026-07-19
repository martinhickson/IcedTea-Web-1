# Legacy WildFly integration tests (superseded)

> **Note:** This directory is **not** part of the active Maven reactor or CI.
> Current process-launch integration tests live in
> [`icedtea-web-integration/`](../icedtea-web-integration/README.md).
> AssertJ Swing and Windows autodetect ITs are in
> [`itw-assertj-it/`](../itw-assertj-it/README.md) and
> [`itw-autodetect-it/`](../itw-autodetect-it/README.md).

The content below describes an older WildFly/Arquillian experiment and is kept for reference only.

---

This module contains integration tests for IcedTea-Web using WildFly 36 and Arquillian.

## Setup

1. The tests use Arquillian to automatically download and start WildFly 36
2. A sample JNLP application is deployed to WildFly
3. The tests verify that IcedTea-Web can launch and run the JNLP application

## Prerequisites

- JDK 17 or higher
- Maven 3.6.0 or higher

## Running the Tests

```bash
mvn clean verify
```

Or to run just the integration tests:

```bash
mvn clean integration-test
```

**Note:** The current setup uses basic JUnit tests. Full Arquillian/WildFly integration requires Arquillian container dependencies that may need to be configured based on your Maven repository setup. WildFly will be automatically downloaded during the build process.

## Test Structure

- `SampleApplication.java` - A simple Java application that will be packaged as a JAR and served via JNLP
- `JNLPIntegrationTest.java` - Arquillian-based integration tests that:
  - Deploy the sample application to WildFly
  - Verify the JNLP file is accessible
  - Test that IcedTea-Web can launch the application

## Configuration

- `arquillian.xml` - Arquillian container configuration for WildFly
- The tests use the `wildfly-managed` container which automatically downloads and manages WildFly
