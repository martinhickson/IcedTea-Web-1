# Security policy

## Supported versions

Security fixes are applied on the active development branch **`1.8`** and released via [GitHub Releases](https://github.com/martinhickson/IcedTea-Web-1/releases). The current release is **[2.8.3](https://github.com/martinhickson/IcedTea-Web-1/releases/tag/icedtea-web-2.8.3)**.

Older release tags may receive fixes at maintainer discretion. Prefer upgrading to the latest release.

## Reporting a vulnerability

**Do not open a public GitHub issue for undisclosed security problems.**

Report security issues privately by opening a [GitHub Security Advisory](https://github.com/martinhickson/IcedTea-Web-1/security/advisories/new) (preferred) or by filing a **private** report through [GitHub Issues](https://github.com/martinhickson/IcedTea-Web-1/issues) with the title prefix `Security:` and minimal public detail until a fix is available.

Include:

- Affected IcedTea-Web version or commit SHA
- Operating system and JDK version
- Steps to reproduce and expected vs actual behaviour
- Impact assessment (code execution, privilege escalation, data exposure, etc.)
- Proof-of-concept if available

## Response expectations

Maintainers will acknowledge reports within a reasonable timeframe and coordinate a fix and release when appropriate. This is a community-maintained fork; timelines depend on severity and availability.

## Scope notes

IcedTea-Web launches **signed and unsigned JNLP applications** with user-granted permissions. Many reported “issues” are inherited JNLP trust-model behaviour rather than implementation bugs. Reports should distinguish:

- **Launcher / control panel defects** (in scope)
- **Malicious or untrusted JNLP content** (user policy and deployment configuration)
- **Vulnerabilities in third-party JNLP applications** (out of scope unless the launcher enables them incorrectly)
