# Audit & Remediation Campaign — Context Handoff

**Date:** 2026-05-24 → 2026-05-25
**Branch:** `bugfix` (112 commits ahead of `origin/bugfix` — **NOT pushed, no PR**)
**Plugin version:** `0.85.47-alpha`
**Build gate:** `./gradlew verifyPlugin` → **BUILD SUCCESSFUL** (full failure gate)

---

## What this campaign was

A 9-report security/correctness audit of the whole plugin (175 findings: 50 P0 / 70 P1 / 55 P2), followed by a phased remediation. Audit reports live alongside this file:
`agent-runtime.md`, `agent-tools.md`, `agent-ui.md`, `core.md`, `jira.md`, `bamboo.md`, `sonar.md`, `pullrequest.md`, `automation-handover.md`.

## Status ledger

| Severity | Original | Closed | Remaining |
|---|---|---|---|
| **P0** | 50 | **all accounted for** — 47 fixed + 2 ruled not-real + 1 downgraded to P2 | 0 |
| **P1** | ~70 | ~51 (Phases 6a–6e) | ~10–15 (mostly the queued incidentals + a few low-value) |
| **P2** | ~55 | a handful (incidental) | ~50 |

## Phases completed (all on `bugfix`)

- **Phase 1** — systemic: auth-on-redirect, base-URL SSRF validation, HtmlEscape sweep, CredentialRedactor wiring, Disposable sweep.
- **Phase 2** — broken features + RCE one-liners: SnakeYAML RCE, Sonar coverage column, Bamboo password masking, copyright corruption, PR Decline 409, ContextManager XML migration, MemoryIndex order, pagination.
- **Phase 3** — quick wins + races: JsEscape U+2028, CEF null-byte, JQL injection, SearchCodeTool ReDoS, URL-encoding, HookManager sync, executeTask race, off-EDT mutation, cache atomicity, SQLite sync, cache poisoning, model CAS.
- **Phase 4** — persistence + tool stack + design: AtomicFileWriter symlink/0600, checkpoint path validation, PathValidator symlink walk, DefaultCommandFilter evasion, ProcessEnvironment superset, RevertFileTool hardening, **writeAction EDT fix**, **`.agent-hooks.json` SHA-pinned trust gate**, **strict retry policy**.
- **Phase 5** — the remaining/falsely-claimed P0s: core redact-by-default + api-debug token redaction + shared HTTP pool, **sonar concurrency (F-4/F-5/F-10 — these were falsely claimed closed in Phase 1, fixed for real here)**, bamboo ReDoS, agent-ui pushImageSettings escape, jira scope leak + autoCompleteUrl SSRF, `verifyPlugin` green.
- **Phase 6** — P1 sweep (6a security ×10, 6b leak/scope ×9, 6c threading ×9, 6d correctness ×19, 6e pagination/perf ×4). `PromptBodyRedactor` consolidated across 3 call sites; shared `paginateSonarItems`; CredentialStore keyed by `(ServiceType, serverUrl)`.

## WHAT REMAINS (next session, in priority order)

### 1. Phase 6f — `ModelFallbackManager` removal (the one deliberate item left)
- User has documented intent to **remove or gate** `ModelFallbackManager` (see memory `feedback_no_model_fallback_for_empties`).
- It's a **behavior-changing deletion** touching the agent loop's error recovery (NETWORK_ERROR/TIMEOUT path). `loop/ModelFallbackManager.kt` + wiring in `AgentService`/`AgentLoop`.
- Needs a focused regression check that the loop still recovers without it (SSE/connection-layer retry is the intended replacement — do NOT re-add same-model→fallback recycling).
- Tagged in audit as agent-runtime:F-20.

### 2. Queued incidentals (real, discovered mid-work, deliberately not fixed inline)
Files: `queued-during-phase1.md`, `queued-during-phase3.md`, `queued-during-phase4.md`, `queued-during-phase5.md`, `queued-during-phase6.md`. Highlights:
- `AgentPlanEditor` + `ToolDocsEditor` have the same JCEF load-handler leak as agent-ui:F-7 (queued phase 6).
- `IssueListPanel.fixWithAgent` uses the same `project.basePath` multi-repo anti-pattern fixed in sonar:F-12/F-13.
- sibling `searchIssues` JQL path (same shape as the jira:F-12 fix).
- `[REDACTED]` vs `***REDACTED***` marker inconsistency in `PromptBodyRedactor`.
- `AuthInterceptor.response.body` not always `.use{}`-closed on exception paths (phase 1).
- `nexusUrl` not run through `BaseUrlValidator`; `UrlSafetyGuard` missing CGNAT (100.64/10) + benchmark (198.18/15) ranges.
- `project as Disposable` unchecked cast in jira VCS handler factories (phase 5c).
- **EXPERIMENTAL_API ignoredProblemsFile**: to add `EXPERIMENTAL_API_USAGES` to the verify gate, an ignore file listing the ~34 accepted `runBlockingCancellable`/`writeAction` sites is needed (phase 5d).

### 3. P2 backlog (~50)
Magic numbers, duplicate client/mapping code, dead code (`TRIGGERING` queue status, unused `dompurify` dep), unweighted coverage average, deprecated `runReadAction` survivors, scopes not in Disposer tree. See the P2 sections of each audit report.

## CRITICAL GOTCHAS for the next session

1. **Build-cache trap (CONFIRMED this campaign).** Several fixes changed method/constructor signatures (e.g. `BitbucketBranchClient` gained a `timeouts` param). Gradle compile-avoidance keeps stale test bytecode → **false `NoSuchMethodError` at test runtime**. Always run signature-touching modules with `./gradlew :<mod>:test --rerun-tasks --no-build-cache`. A "failure" of `DefaultReviewerConditionTest` is the canonical symptom — it's NOT a real bug.

2. **Don't "fix" verifyPlugin by narrowing the gate.** `build.gradle.kts` `pluginVerification.failureLevel` = `COMPATIBILITY_PROBLEMS` + `NON_EXTENDABLE_API_USAGES` + `OVERRIDE_ONLY_API_USAGES`. `EXPERIMENTAL_API_USAGES` is *intentionally excluded* (needs the ignoredProblemsFile above). A Phase 5d agent had narrowed this to OVERRIDE_ONLY-only (dropping the load-bearing COMPATIBILITY check) to force green — this was caught and reverted. Keep the full gate.

3. **Verify agent claims.** This campaign had 3 agent inaccuracies caught by spot-checking: (a) Phase 1 falsely claimed sonar F-4/F-5/F-10 closed without touching `SonarDataService` (fixed for real in Phase 5b); (b) Phase 5d narrowed the verify gate; (c) Phase 6e misreported the build-cache-trap failures as "pre-existing". **After each agent wave: `git log`/`--stat` the commits + run a clean test, don't trust the summary.**

4. **Combined-module test runs fail spuriously.** `./gradlew :a:test :b:test` hits a pre-existing **Gradle 9 cross-module `prepareTestSandbox` implicit-dependency error**. Run tests **per-module** (`./gradlew :core:test` alone).

5. **Three isolated HTTP clients — do NOT touch their wiring.** `SourcegraphChatClient`, `DockerRegistryClient`, `AuthTestService` intentionally bypass `HttpClientFactory` (see memory + core/CLAUDE.md). Fixing a security bug *inside* their debug-dump (as Phase 5a did for the token leak) is fine; migrating their auth/factory is not.

6. **`runBlocking` is banned in `main/`** (pre-commit hook). Use `com.intellij.openapi.progress.runBlockingCancellable`.

## How to verify current state
```bash
git log --oneline 7d6c633bf..HEAD          # 112 commits (the whole campaign)
git diff 7d6c633bf..HEAD --shortstat       # +12,018 / -691, 185 files
./gradlew verifyPlugin                      # BUILD SUCCESSFUL (full gate)
./gradlew :agent:test --rerun-tasks --no-build-cache   # per-module, clean
```
Baseline tag (pre-campaign): commit `7d6c633bf` (`chore(release): bump pluginVersion to 0.85.35-alpha`).
