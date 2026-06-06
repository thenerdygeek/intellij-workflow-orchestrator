# Threat Model

This document states the trust boundaries of the Workflow Orchestrator plugin so
that users, auditors, and fork maintainers understand what the plugin can do and
where the security controls sit. It is intentionally lightweight (solo-maintained
project) but explicit.

## Assets

- **Source code** in the open project (read by the agent, sent to the LLM).
- **Credentials** for Jira / Bamboo / Bitbucket / Sonar / Nexus / the LLM provider.
- **The developer's machine** (filesystem, shell, network position).

## Actors & trust levels

| Actor | Trust |
|---|---|
| The developer running the IDE | Fully trusted (owns the machine). |
| The LLM provider | Semi-trusted external service — receives code/context. |
| Configured backends (Jira/Bamboo/Bitbucket/Sonar) | Trusted endpoints, authenticated. |
| Arbitrary web content fetched by tools | **Untrusted** — screened on egress and ingress. |
| A company fork's overlay code | Trusted as much as the fork owner trusts it. |

## Trust boundaries & controls

### 1. Shell execution
The agent can run shell commands on the developer's machine.
- **Control:** `agent/.../security/CommandSafetyAnalyzer.kt` screens commands; per-tool
  timeouts (120s/600s/unlimited per tool class); approval gates in the agent loop.
- **Residual risk:** a trusted developer can approve a destructive command. The plugin
  defends against accidental/agent-initiated harm, not a malicious operator.

### 2. File writes
The agent can create/modify files.
- **Control:** `agent/.../tools/builtin/PathValidator.kt` constrains write paths;
  writes go through `WriteCommandAction` (undoable) and flush via the Document API.

### 3. LLM egress (code leaves the machine)
Source code and IDE context are sent to the configured LLM to power chat/agent features.
- **Control:** this is disclosed to the user; egress URLs/content pass through
  `core/.../security/UrlSafetyGuard.kt`. Telemetry/data-handling controls are expanded
  in roadmap Phase 4. There is **no** silent telemetry beyond the LLM calls the user invokes.
- **Residual risk:** the LLM provider sees the code the user submits. Air-gapped/on-prem
  endpoint support is roadmap Phase 4.

### 4. Network requests / SSRF
Tools and clients make outbound HTTP requests.
- **Control:** `core/.../security/UrlSafetyGuard.kt` (SSRF guard); TLS via the IDE
  truststore through `core/.../http/HttpClientFactory.kt`; requests routed through the
  IDE proxy where configured.

### 5. Credential handling
Backend and LLM tokens are stored and used for authenticated calls.
- **Control:** all secrets live in IntelliJ **PasswordSafe** via
  `core/.../auth/CredentialStore.kt` — **never** in XML/settings files. Auth schemes:
  Bearer (Jira/Bamboo/Bitbucket/Sonar), `token` (Sourcegraph).

### 6. Supply chain
Third-party dependencies could introduce CVEs or tampering.
- **Control:** `verification-metadata.xml` (SHA-256 pinning) + per-module Gradle lockfiles;
  Dependabot vulnerability alerts + auto-fixes (`.github/dependabot.yml`).

## Out of scope

- Vulnerabilities in the IntelliJ Platform, the JVM, or the OS.
- Compromise of the configured LLM provider or backend services.
- A malicious developer operating their own machine.

See [SECURITY.md](SECURITY.md) for how to report a weakness in any of the above controls.
