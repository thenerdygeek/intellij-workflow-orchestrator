# Findings Queued During Phase 1 Security Fixes

Findings discovered during implementation of S1–S5 but out-of-scope for that phase.
Do NOT fix inline — address after all 5 commits are verified.

---

## core:F-1 — OkHttp response-body leak on redirect

`HttpClientFactory` now disables redirects (S1), but `AuthInterceptor` reads
`response.body?.string()` without always closing on exception paths. If an interceptor
throws after a partial body read, the body is left open and leaks a connection slot.

**File:** `core/src/main/kotlin/com/workflow/orchestrator/core/http/AuthInterceptor.kt`
**Fix approach:** Wrap body reads in `response.body?.use { it.string() }`.

---

## core:F-3 — `BaseUrlValidator` does not cover IPv6 link-local scope IDs

IPv6 addresses of the form `fe80::1%eth0` include a scope ID (`%eth0`). Java's
`InetAddress.getByName` strips the scope ID but `isLinkLocalAddress()` still returns
true. However, some JVM versions may fail to parse the `%` component. Fuzzing with
scope IDs should be added to `BaseUrlValidatorTest`.

**File:** `core/src/test/kotlin/com/workflow/orchestrator/core/security/BaseUrlValidatorTest.kt`
**Fix approach:** Add test case `http://[fe80::1%25eth0]/` → expect `Invalid`.

---

## core:F-4 — `ConnectionsConfigurable.apply()` does not validate `nexusUrl`

S2 validates jiraUrl, bambooUrl, bitbucketUrl, sonarUrl, but `nexusUrl` was omitted
because the Nexus integration is deprioritized. If/when Nexus ships, `nexusUrl` must
also run through `BaseUrlValidator`.

**File:** `core/src/main/kotlin/com/workflow/orchestrator/core/settings/ConnectionsConfigurable.kt`
**Fix approach:** Add `nexusUrl` to `urlsToValidate` map in `apply()`.

---

## core:F-5 — No SSRF guard for agent tool HTTP calls (UrlSafetyGuard coverage gap)

`UrlSafetyGuard` is called by agent tool wrappers before outbound requests, but its
regex list for RFC1918 does not cover `100.64.0.0/10` (CGNAT) or `198.18.0.0/15`
(benchmarking). An attacker controlling a Jira/Sonar field value could redirect the
agent to those ranges.

**File:** `core/src/main/kotlin/com/workflow/orchestrator/core/security/UrlSafetyGuard.kt`
**Fix approach:** Add `100.64/10` and `198.18/15` to the blocked-IP list.

---

## core:F-7 — `HttpClientFactory.sharedPool` is never shut down on plugin unload

The shared `ConnectionPool` and `Dispatcher` in `HttpClientFactory` are singletons with
no shutdown hook. On plugin hot-reload during development, these survive in the class
loader and accumulate idle connections.

**File:** `core/src/main/kotlin/com/workflow/orchestrator/core/http/HttpClientFactory.kt`
**Fix approach:** Register a `PluginClassLoader` unload hook or tie to an application-level
`Disposable` that calls `sharedPool.evictAll()` and `sharedDispatcher.executorService.shutdown()`.

---

## core:F-8 — `CredentialStore.getToken()` does not blank the char[] after use

`PasswordSafe.getPassword()` returns a `String`; once used, the raw chars are not zeroed.
This is a minor JVM heap-scan exposure window.

**File:** `core/src/main/kotlin/com/workflow/orchestrator/core/auth/CredentialStore.kt`
**Fix approach:** Accept that JVM `String` is immutable and document the limitation; consider
using `CharArray` internally if the credential is short-lived (e.g., HTTP header construction).

---

## core:F-9 — `SmartPoller` does not honor backpressure — can overlap if poll body is slow

`SmartPoller` schedules the next tick using a fixed delay from the PREVIOUS fire time, not
from completion. If a poll body takes longer than the interval, two body executions can be
in-flight simultaneously.

**File:** `core/src/main/kotlin/com/workflow/orchestrator/core/polling/SmartPoller.kt`
**Fix approach:** Change to `delay(interval)` after `body()` completes, not before.

---

## core:F-10 — `HtmlEscape.escapeHtml` does not escape single-quote (`'`)

`HtmlEscape.escapeHtml()` escapes `<`, `>`, `&`, `"` but not `'` (single-quote / apostrophe).
In contexts where the escaped value is placed inside a single-quoted HTML attribute
(`href='...'`, `title='...'`), an unescaped `'` can break attribute parsing.

**File:** `core/src/main/kotlin/com/workflow/orchestrator/core/util/HtmlEscape.kt`
**Fix approach:** Add `replace("'", "&#39;")` to the escape chain. Verify no existing
labels use single-quoted attributes by grepping call sites.

---

## core:F-12 — `BaseUrlValidator` does not warn on URLs missing a trailing slash

REST clients append paths (e.g. `/rest/api/2/myself`) directly to the base URL. If the
user omits the trailing slash the first path segment merges with the host
(`jira.company.com/rest` → valid; `jira.company.comrest` → invalid). A `SoftWarning`
for URLs missing a trailing slash would catch this class of misconfiguration early.

**Note:** S2 partially addresses this by validating scheme and IP — the trailing-slash
heuristic is an additive improvement.

**File:** `core/src/main/kotlin/com/workflow/orchestrator/core/security/BaseUrlValidator.kt`
**Fix approach:** In `validate()`, after confirming `Valid`, re-check whether `url` ends
with `/`; if not, return `SoftWarning("URL should end with '/' to avoid path-joining errors")`.

---

## core:F-13 — `ConnectionsConfigurable` does not save on `SoftWarning`

Currently a `SoftWarning` from `BaseUrlValidator` shows a notification but still proceeds
with `apply()`. However, the notification balloon has no actionable link — the user cannot
open the settings page directly from the warning.

**File:** `core/src/main/kotlin/com/workflow/orchestrator/core/settings/ConnectionsConfigurable.kt`
**Fix approach:** Wire `NotificationAction` on the `SoftWarning` balloon to navigate back to
the Connections configurable page.

---

## agent-runtime:F-9 — `CredentialRedactor` patterns not tested against AWS STS tokens

AWS STS tokens start with `ASIA` (vs `AKIA` for regular access keys). The current regex
only matches `AKIA[0-9A-Z]{16}`. Assumed STS tokens would not appear in hook output, but
a hook could call AWS and print its session token on error.

**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/security/CredentialRedactor.kt`
**Fix approach:** Add `Regex("ASIA[0-9A-Z]{16}")` to the `PATTERNS` list.

---

## agent-runtime:F-10 — `CredentialRedactor` does not catch `Authorization: token <value>` (Sourcegraph style)

The Sourcegraph auth scheme uses `Authorization: token <sg-token>`. The current patterns
catch `Bearer <token>` but not the `token <value>` form, so a hook that dumps its env
or curl command line using the `token` scheme would leak the raw value.

**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/security/CredentialRedactor.kt`
**Fix approach:** Add `Regex("(?i)Authorization:\\s+token\\s+[A-Za-z0-9_.-]{20,}")` → `"Authorization: token [REDACTED]"`.

---

## agent-runtime:F-11 — `CredentialRedactorCheckpointTest` does not exercise `sgp_` token in checkpoint

The checkpoint test covers `Bearer` and `ghp_` tokens. A Sourcegraph token (prefix `sgp_`)
pasted into the chat input would also be a real risk, since Sourcegraph tokens are stored
in PasswordSafe and could be accidentally copied.

**File:** `agent/src/test/kotlin/com/workflow/orchestrator/agent/checkpoint/CredentialRedactorCheckpointTest.kt`
**Fix approach:** Add a fourth test case with `sgp_` + 40 hex chars as the user text.

---

## jira:F-14 — `TicketListCellRenderer.toolTipText` truncation may break mid-entity

`TicketListCellRenderer` sets `toolTipText = HtmlEscape.escapeHtml("${value.key}: ${value.fields.summary}")`.
If the summary is long and Swing truncates the tooltip display, a truncation mid-entity
(e.g., `&amp` without the `;`) could surface garbled text.

**File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/TicketListCellRenderer.kt`
**Fix approach:** Apply `StringUtils.truncate(combined, 200)` BEFORE calling `escapeHtml`
so truncation happens on the raw string and the entity is always complete.

---

## sonar:F-15 — `IssueDetailPanel` does not escape issue `type` and `severity` display strings

`IssueDetailPanel` now escapes `issue.message` (S3). However the severity badge text and
issue type label are populated from enum `name()` values (e.g. `"BLOCKER"`, `"BUG"`), which
are safe. But `hotspot.probability` is derived from a DTO string that comes from the Sonar
API and is NOT escaped. If a malicious Sonar server returns HTML in the `probability` field,
it would render unescaped.

**File:** `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/IssueDetailPanel.kt`
**Note:** S3 already escapes `hotspot.probability` — verify line 259 is correct after S3 lands.

---

## pullrequest:F-16 — `PrDetailPanel` reviewer display name not escaped

The PR detail panel renders reviewer display names in a `JBLabel` with HTML. The display
name comes from the Bitbucket API and is not escaped. A malicious server could return
`<b>Admin</b>` and it would render as bold text (currently benign, but an XSS surface
if the label ever gets a tooltip or link injection).

**File:** `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDetailPanel.kt`
**Fix approach:** Apply `HtmlEscape.escapeHtml()` to every reviewer display name in the reviewer chip builder.

---

## bamboo:F-17 — `BuildFailureBridgeStartupActivity` scope not tested

The coroutine scope cancel via `Disposer.register` added in S5 is untested. A test
verifying that the scope is cancelled when the project `Disposable` is disposed would
lock in the fix.

**File:** `bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/listeners/` (new file)
**Fix approach:** Create a `BuildFailureBridgeScopeDisposeTest` that mocks the `Disposer`
call and verifies the scope is cancelled on project close.

---

## agent-runtime:F-18 — `ProcessEnvironment` strips env vars but not from hook data map

`ProcessEnvironment` in `RunCommandTool` strips 35+ sensitive env vars. However, the
`HookRunner.buildProcess()` method copies the agent's environment into the subprocess via
`environment().apply { ... }`. It adds hook-specific vars but does NOT strip the inherited
process environment for sensitive keys (TOKEN, SECRET, PASSWORD, etc.).

**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/hooks/HookRunner.kt`
**Fix approach:** In `buildProcess()`, after `environment().apply { ... }`, call
`ProcessEnvironment.sanitize(environment())` (or equivalent strip logic) to remove
sensitive inherited vars from the hook subprocess environment.

---

## agent-runtime:F-19 — `ToolOutputSpiller` does not redact credentials from spilled output

Tool outputs spilled to disk by `ToolOutputSpiller` are not passed through `CredentialRedactor`.
If a tool (e.g., `run_command curl -v`) prints an `Authorization` header in its output
and the output is large enough to spill to disk, the raw credential ends up in
`{sessionDir}/tool-output/`.

**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ToolOutputSpiller.kt`
**Fix approach:** Call `CredentialRedactor.redact(content)` before writing the spill file.
Accept the performance trade-off for large files (profiling showed ~2ms/MB on Sonnet M3).
