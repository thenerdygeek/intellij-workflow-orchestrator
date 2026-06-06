# Security Policy

The Workflow Orchestrator plugin executes shell commands, writes to the filesystem,
handles credentials, and sends source code to an external LLM. We take reports of
security weaknesses seriously.

## Supported Versions

This is a solo-maintained project distributed as a public core that is forked per
company. Security fixes are applied to the latest release on `main` only. Company
forks are responsible for merging upstream security fixes into their overlays.

| Version | Supported |
|---|---|
| Latest release on `main` | ✅ |
| Older releases | ❌ |
| Company forks | Maintained by the fork owner |

## Reporting a Vulnerability

**Do not open a public GitHub issue for security problems.**

Use GitHub's private vulnerability reporting:
**[Report a vulnerability](https://github.com/thenerdygeek/intellij-workflow-orchestrator/security/advisories/new)**
(repo → *Security* tab → *Report a vulnerability*).

Please include:
- Affected component (e.g. agent shell execution, credential handling, LLM egress).
- Reproduction steps and impact.
- Plugin version (Settings → Plugins → Workflow Orchestrator) and IDE version.

### What to expect

- Acknowledgement within **7 days** (best-effort; solo maintainer).
- A fix or mitigation plan for confirmed issues, prioritized by severity.
- Credit in the release notes unless you prefer to remain anonymous.

## Scope

In scope: the plugin's own code — agent tool execution, credential storage,
network egress, file operations, and the extension seams.

Out of scope: vulnerabilities in IntelliJ Platform itself, the configured LLM
provider, or third-party services (Jira/Bamboo/Bitbucket/Sonar). Report those to
their respective vendors.

See [THREAT_MODEL.md](THREAT_MODEL.md) for the trust-boundary model that informs
this policy.
