# Audit Campaign — P2 Backlog Handoff

**For:** the next session, to work the remaining **P2 (quality/cleanup)** findings.
**Date written:** 2026-05-25
**Branch:** `bugfix` (now **129 commits** ahead of `origin/bugfix` — **NOT pushed, no PR**)
**Latest commit:** `c6cd0753b`
**Plugin version:** `0.85.47-alpha`
**Build gate:** `./gradlew verifyPlugin` → **BUILD SUCCESSFUL** (full failure level, IU-251/252/253)

> This supersedes `HANDOFF.md` for what-remains. Read `HANDOFF.md` too for the deep build/gotcha detail — everything there still applies. This file is the P2-specific resume doc.

---

## ✅ P2 CLEAN-WINS WAVE DONE (2026-05-25, `6b439ef55..c6cd0753b`, 8 commits)

All 28 P2s were triaged (7 parallel verification agents) and the **12 low/trivial-risk "clean wins" are FIXED**, one commit per module:

| Commit | Findings |
|---|---|
| `6b439ef55` | core:F-17 — drop misleading `suspend` on `EventBus.emit` (SAFE variant; NOT tryEmit→emit) |
| `0cd5b4e3c` | agent:F-27 — 4 companion `Json` → 2 |
| `cf53058a3` | jira:F-13 (dedup mapper via `full: Boolean`) + F-14 (`postCommitTransitionTriggerStatuses` setting + UI) |
| `ba5ceb385` | bamboo:F-13 — `BambooService` interface in `BambooBuildRunState` |
| `05d83c9e3` | sonar:F-17 (`SonarMetricKey` consts) + F-14 (injected `cs` scope) + F-15 (`getIssuesSinglePage` server-side paging) |
| `210dfc129` | pullrequest:F-14 (`PrState` consts) + F-15 (tab panels → Disposable + `Disposer.dispose(old)`) |
| `090f52bfa` | automation:F-13 — BaselineCache disk I/O out of data lock (snapshot + version-guard + `diskMutex`) |
| `c6cd0753b` | handover:F-15 — drop debug comment-preview log |

### Triage verdicts for the 16 NOT-fixed (do NOT redo verification — these are settled)
- **ALREADY-FIXED (3):** agent:F-26, core:F-19, automation:F-12.
- **NOT-REAL (1):** core:F-16 (body read once into `val`).
- **⚠ NOT-REAL / HARMFUL — do NOT touch:** **automation:F-11** — the audit's "safe delete TRIGGERING enum" is WRONG; deleting it crashes startup via `QueueEntryStatus.valueOf()` on persisted rows (same as TAG_INVALID) and it has live render branches + 2 tests.
- **WONTFIX:** jira:F-16 (runReadAction Phase-4 survivor, by-design until 2026.1 bump), sonar:F-18 (throw is a sound unreachable-guard; no-op fix harmful), core:F-18 (CredentialStore churn across 7 modules + loses test seam; "45+" is stale = actually 22), pullreq:F-13 (impact already neutralized by core:F-6 shared pool).

### STILL-OPEN actionable P2 backlog (6) — for the next session
- **jira:F-15** (medium) — add `validateGitBranchName` to the **agent tools** (`JiraTool` start_work, `BitbucketRepoTool` create_branch). NOTE: the named `BranchNameValidator.isValidBranchName` is **dead code** — fixing it changes nothing; fix the tools.
- **sonar:F-16** (medium) — weighted coverage. Correct fix needs `linesToCover` (+`conditionsToCover`) added to `FileCoverageData` model + `CoverageMapper` (`lines_to_cover` is fetched but dropped today). Fallback-only path, so narrow value.
- **agent:F-28** (medium) — replace the `RunCommandTool`/`BackgroundProcessTool` ThreadLocals with a `CoroutineContext.Element`; touches live streaming plumbing across `RunCommandTool`/`BackgroundProcessTool`/`RuntimeExecTool`/`SonarTool` + the `@Volatile` static `streamCallback`.
- **bamboo:F-15** (defer) — naive "share the client" fix violates the core-interface layering; only a `:bamboo` shared cached-client provider is clean.
- **automation:F-14** (defer) — `NO_SHA` cache key is pointless until the T8 diff-capture ships.
- **automation:F-10** (defer) — substring suite-matching is a medium UI-model refactor (positional fixed rows), not a one-liner.
- bamboo:F-14 — cosmetic; only the bytes-vs-chars unit nit is substantive (don't force a single value — behavioral).

---

## Where the campaign stands

- **All 50 P0 + ~51 P1 closed** (the original 6-phase remediation).
- **All queued incidentals (Tier A/B/C/D) closed** this session.
- **Phase 6f done** (ModelFallbackManager removed).
- **Remaining: the P2 backlog only** — 28 `[P2]`-tagged findings across the audit reports (the older "~50" estimate counted sub-items now folded in; the concrete actionable list is the 28 below).

### This session's commits (do NOT redo these)
| Commit | Work |
|---|---|
| `ca003bb58` | Phase 6f — removed `ModelFallbackManager`; recovery is now L1-recycle + L2 tier escalation |
| `236a4d91b` | Incidentals Tier A — 11 security/leak/correctness fixes |
| `0a91895eb` | Incidentals Tier B — HttpClientFactory shutdown listener, owner-only perms, SoftWarning action |
| `555f8b8fe` | Incidentals Tier C — 3 coverage tests |
| `fe36dc579` | **Code-review follow-up** — fixed L2-escalation reachability regression (MAX_TIMEOUT_RETRIES 3→4 + reset apiRetryCount on L2) |
| `536f47ab3` | Incidentals Tier D — PromptBodyRedactor marker normalize + EXPERIMENTAL gate decision |

---

## THE P2 BACKLOG (28 findings)

Source: the `[P2]` sections of each `audit/2026-05-24/<module>.md` report. Read the finding's full entry (evidence + fix sketch) before acting.

### agent-runtime.md (3)
- **F-26** — Magic numbers throughout (200 memory lines, 88 compaction %, 30K spill, 5/3 loop thresholds, 30s hook timeout, 30-day cleanup, 5000 LCS). → extract named consts.
- **F-27** — Five JSON instances in `MessageStateHandler.companion` — duplication. → single shared `Json`.
- **F-28** — `BackgroundProcessTool.currentSessionId` / `RunCommandTool` ThreadLocal smell (explicit TODO).

### core.md (4)
- **F-16** — `AuthTestService.executeTestRequest` double-read risk on the error-branch body inside `response.use{}`. *(Not purely cosmetic — verify.)*
- **F-17** — `EventBus.emit()` is `suspend` but uses `tryEmit()` — wasted suspend + silently drops events on failure. *(Potential correctness — triage carefully.)*
- **F-18** — `CredentialStore()` instantiated in 45+ places (no shared state beyond static cache).
- **F-19** — `ConnectionPool` size discrepancy undocumented (confusing for HttpClientFactory users). *(Note: HttpClientFactory was touched in Tier B `0a91895eb` — re-read current state first.)*

### jira.md (4)
- **F-13** — Duplicate ticket→`TicketData` mapping in `JiraServiceImpl`.
- **F-14** — Magic status strings in `PostCommitTransitionLogic.NEEDS_TRANSITION_STATUSES`.
- **F-15** — `BranchNameValidator.isValidBranchName` only checks ticket-key presence, not overall validity.
- **F-16** — `CurrentWorkSection.showBranchPicker` uses deprecated `runReadAction` on EDT in a MouseAdapter. *(KNOWN Phase-4 survivor — documented TODO at `jira/ui/CurrentWorkSection.kt:185`, retire on 2026.1 platform bump. Likely WONTFIX-until-platform-bump; confirm.)*

### bamboo.md (3)
- **F-13** — `BambooBuildRunState` uses `BambooServiceImpl.getInstance` directly, bypassing the `BambooService` interface.
- **F-14** — Magic numbers — log-cap constants differ between `BuildDashboardPanel` and `StageDetailPanel`.
- **F-15** — `BuildMonitorService` creates a second `BambooApiClient` when `_apiClient` is null (diverges from cached client).

### sonar.md (5)
- **F-14** — `SonarDataService` uses `CoroutineScope(SupervisorJob()+…)` instead of platform-injected scope. *(Same anti-pattern family as Tier-B/D items.)*
- **F-15** — `getIssuesPaged` fetches ALL issues then paginates client-side. *(Perf — not cosmetic.)*
- **F-16** — `calculateOverallCoverage` computes an UNWEIGHTED average of per-file percentages. *(Correctness — a 5-line file and a 500-line file count equally. Real bug, highest-value P2.)*
- **F-17** — Magic strings for metric names throughout.
- **F-18** — `SonarCoverageEngine.createCoverageEnabledConfiguration` throws unconditionally. *(Investigate — may be dead/broken path.)*

### pullrequest.md (3)
- **F-13** — Duplicate Bitbucket client construction (`fromConfiguredSettings()` in `PrDetailPanel` alongside `BitbucketBranchClientCache`).
- **F-14** — Magic string `"OPEN"` used throughout (no enum/constant).
- **F-15** — `AiReviewTabPanel` / `CommentsTabPanel` create own `CoroutineScope` not registered with `Disposer`. *(Leak — same family as agent-ui:F-7 / Tier-B work.)*

### automation-handover.md (6)
- **F-10** — `ChecksTab` suite rows matched by hardcoded substring patterns (tight coupling to plan-key naming).
- **F-11** — `TRIGGERING` status in `QueueEntryStatus` never set by `QueueService` — **dead code** (safe delete).
- **F-12** — `AutomationStatusBarWidgetFactory` scope not tied to widget disposal. *(Leak.)*
- **F-13** — `BaselineCacheService.persistToDisk()` called while holding `Mutex` — disk I/O inside lock. *(Perf/contention — not cosmetic.)*
- **F-14** — `HandoverAiSummaryCache` hardcoded `NO_SHA="no-sha"` cache key — stale AI summary survives branch changes. *(Correctness-ish — triage.)*
- **F-15** — `JiraClosureService.buildClosureComment` logs a comment preview that may include user-supplied content. *(Minor info-leak in logs.)*

---

## Suggested approach for the P2 session

These are mostly **quality/cleanup**, but **not all cosmetic** — triage first. Rough buckets:

1. **Real-behavior P2s (do first, with care + tests):** sonar:F-16 (unweighted coverage — genuine bug), core:F-17 (EventBus drops events), sonar:F-15 (client-side pagination perf), automation:F-13 (I/O under lock), automation:F-14 (stale summary), core:F-16 (double-read).
2. **Leak/scope P2s (mechanical, mirror existing fixes):** sonar:F-14, pullrequest:F-15, automation:F-12 — same "register scope with Disposer / use injected `cs`" pattern already applied in Tier-B/D.
3. **Dead code (safe delete):** automation:F-11 (`TRIGGERING`).
4. **Magic numbers / strings / dup mapping (low-risk, bundle per module):** agent-runtime:F-26/27/28, jira:F-13/14, bamboo:F-13/14/15, pullrequest:F-13/14, sonar:F-17, core:F-18/19, automation:F-10/F-15.
5. **Likely WONTFIX-until-platform-bump:** jira:F-16 (`runReadAction` survivor — already a documented Phase-4 TODO). Confirm, don't force.

**Commit cadence:** group by module (per-module test runs are required anyway — see gotchas). One commit per module-batch keeps it reviewable.

---

## CRITICAL GOTCHAS (carry-over + reinforced this session)

1. **VERIFY, DON'T TRUST THE FINDING.** ~9 of ~25 findings examined this session were already-fixed, not-real, or *harmful to fix* (e.g. pullreq:F-16 escaping would have regressed display; core:F-9 SmartPoller "bug" didn't exist). **Read the current code before editing every P2.** Many P2s reference code that the 6-phase remediation already changed.
2. **Build-cache trap.** Signature-touching commits need `./gradlew :<mod>:test --rerun-tasks --no-build-cache` or you get false `NoSuchMethodError`. (Also bit us on a forward `const val` reference this session — Kotlin forbids referencing a `const` declared later in the same object; use a literal.)
3. **Per-module tests only.** `./gradlew :a:test :b:test` hits a Gradle-9 cross-module `prepareTestSandbox` error. Run each module in its own invocation.
4. **Do NOT narrow the verify gate.** `build.gradle.kts` `failureLevel` = COMPATIBILITY_PROBLEMS + NON_EXTENDABLE_API_USAGES + OVERRIDE_ONLY_API_USAGES. `EXPERIMENTAL_API_USAGES` is **excluded BY DESIGN** (decided 2026-05-25 — shrinking surface + brittle ignore file would be net-negative; documented in `build.gradle.kts`). **Do not re-open it** unless explicitly asked.
5. **Three isolated HTTP clients — don't touch their wiring:** `SourcegraphChatClient`, `DockerRegistryClient`, `AuthTestService` bypass `HttpClientFactory` intentionally. (Relevant to core:F-16 / F-18 / F-19 — fixing a bug *inside* them is fine; migrating their auth/factory is not.)
6. **`runBlocking` banned in `main/`** (pre-commit hook). Use `runBlockingCancellable`.
7. **`.claire/` is untracked and NOT ours** — exclude it from commits (use `git add -- <specific paths>`, not `git add -A`).
8. **Per-module CLAUDE.md + `docs/architecture/index.html`** must be updated in the same commit as any architecture change.

---

## How to verify current state & resume
```bash
git log --oneline 6553af57d..HEAD            # this session's 6 commits
git status --short                            # clean apart from untracked .claire/
./gradlew verifyPlugin                        # BUILD SUCCESSFUL (full gate)
./gradlew :agent:test --rerun-tasks --no-build-cache   # per-module, clean
```
Resume by reading this file + the relevant `audit/2026-05-24/<module>.md` P2 sections.
Memory entry: `project_audit_remediation_campaign.md` (the live status ledger).
