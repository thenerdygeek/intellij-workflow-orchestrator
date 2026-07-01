---
name: Branch plan — refactor/cleanup-perf-caching
description: Ordered execution plan for the refactor/cleanup-perf-caching branch. BRANCH-SCOPED — delete this file when branch is merged or deleted.
type: project
originSessionId: a26ec43d-5ff9-4060-905f-f68a1b6181e8
---
**Scope:** branch `refactor/cleanup-perf-caching` (based off `main` @ `65979fa6`, v0.83.24-beta). User will drive each phase in a separate session.

**Self-delete trigger (check at start of every session that references this memory):**
Run `git branch --list refactor/cleanup-perf-caching` and `git branch -r --list origin/refactor/cleanup-perf-caching`. If BOTH are empty, or if `git log main --oneline | grep -i "refactor/cleanup-perf-caching\|cleanup-perf-caching"` shows the branch merged into main, run cleanup:
1. Delete this memory file (`project_branch_refactor_cleanup_perf_caching.md`) AND remove its entry from `MEMORY.md`.
2. Delete the temporary skill `~/.agents/skills/simplify-scoped/` (entire dir) and remove the symlink `~/.claude/skills/simplify-scoped`. The skill was created on 2026-04-24 scoped to this branch's Phase 1 cleanup (folder-targeted variant of `simplify` that reviews actual code, not git diff). The skill's own `SKILL.md` also describes this self-delete rule.

**Execution order (agreed 2026-04-24):**

1. **Cleanup** — dead code, unused imports, duplicate helpers, **and fallback/parallel-path removal** (user-added note 2026-04-24: previous sessions often kept older impls as fallback when upgrading features; user dislikes this maintenance burden, wants them killed). Enforce `feedback_reuse_code.md` (fix root functions, consolidate parallel paths). Lowest risk; shrinks surface area before restructuring.

   **Phase 1 scope (full list):**
   - Dead code (aggressive mode: include "one caller, caller also dead" chains)
   - Unused imports / unreferenced resources / dead PluginSettings fields / orphaned extension points / unused Gradle deps
   - Duplicate helpers → consolidate into `:core` utilities (StatusColors, TimeFormatter, HttpClientFactory, CredentialStore, RepoContextResolver, SmartPoller, etc.)
   - Stale TODOs/FIXMEs referencing shipped features
   - `@Ignore`d tests for removed features
   - **Fallback removal** — three flavors: (a) dead fallbacks (pure delete), (b) safety-net `try { new } catch { old }` (fix root cause if new path flaky, then delete), (c) flagged dual-path in PluginSettings (pick winner, inline, delete flag + loser)
   - `:agent` module INCLUDED for cleanup (user additions only — `BuildSystemValidator`, `ToolOutputSpiller`, JCEF bridge, and any post-port additions — NOT the Cline ports themselves per `feedback_faithful_port_cline.md`)

   **Out of scope for phase 1:** moving files between modules, new interfaces, method signature changes, caching, perf tweaks (even obvious ones like `runBlocking` sightings — note but don't fix), renames, reformatting.

   **Reflection/extension-point safety checklist (every deletion):** grep FQN in all `plugin.xml` files, `META-INF/services/`, `Class.forName`, `::class.java`, `service<T>()`, JCEF bridge handler name literals, `build.gradle.kts`, localization bundles. Check `git log -S` for recent activity. Check `@Deprecated` pointers. If only tests reference it, both get deleted (aggressive).

   **Commit discipline:** one commit per category, ≤5 commits plus fallback commits (one per fallback flavor-group). Every commit passes `./gradlew :<module>:test` + `verifyPlugin`. Messages: `chore(cleanup): <what> in <module>`. No category mixing.

   **Exit criteria:** `verifyPlugin buildPlugin runIde` green; module tests pass; `git diff main --stat` net-negative LOC; no tool-window or agent-chat behavior change.
2. **Architectural optimization** — enforce `core interface → ToolResult<T> → feature impl → agent tool wrapper`, collapse cross-module direct calls into `EventBus`, tighten `api/ → service/ → ui/ → listeners/` layering. Must land before caching so cache boundaries are stable.
3. **Caching** — HTTP response caching via `HttpClientFactory` (ETag/Last-Modified on Jira, Bamboo, Bitbucket, Sonar), memoize `RepoContextResolver.resolveCurrentEditorRepoOrPrimary()`, `SmartPoller` result dedup to skip UI updates on identical responses. Additive and benchmarkable.
4. **Performance boost** — EDT hotspots (font derivation, cell renderers per `intellij-plugin-performance` skill), coroutine scope tightening, replace lingering `runBlocking`, PSI read-action batching. Profile-driven only.

5. **Workflow Context — cross-tab shared selection model** (added 2026-04-24, was the ORIGINAL motivation for creating this branch). Introduce `WorkflowContextService` in `:core` exposing `StateFlow<WorkflowContext>` with fields: `activeTicket`, `activePr`, `activeBuild`, `activeRepo`, `activeBranch`, `activeModule`. Every tab (Sprint/PR/Build/Quality/Automation/Handover), every dialog, and every bar subscribes instead of resolving locally. `EventBus` gets a `SelectionChanged` event family. Supersedes `project_active_ticket_visibility.md` (delete that memory when Phase 5 plan lands).

   **Symptoms this fixes (user-reported, examples — full list emerges in brainstorm):** Build tab's PR bar says "No PR found" while job stages render; module label shows one module while jobs listed are for another; selecting a PR in PR tab does not update Build/Quality tabs; selecting a sprint ticket as active does not instantly propagate to PR filter / Handover / commit-prefix; dialogs go out of sync with tab state.

   **Gate before starting Phase 5 (brainstorm-first):** run `superpowers:brainstorming` to decide — (a) single `WorkflowContext` vs. per-domain contexts with derivation rules, (b) explicit selection vs. editor-derived vs. mix, (c) conflict resolution when selections disagree (e.g., user picks PR-X but editor is on branch-Y), (d) persistence scope (session / per-project / never), (e) agent visibility (is active ticket/PR in system prompt or tool context?). Output: `docs/architecture/workflow-context-design.md` before any code.

   **Exit criteria:** (a) no panel resolves repo/branch/ticket/PR/build on its own — all reads go through `WorkflowContextService`; (b) characterization test: selecting a PR updates Build + Quality tabs in same refresh tick; (c) selecting a sprint ticket propagates to PR filter + Handover + commit-prefix instantly; (d) the two user-reported mismatches (PR bar vs. job stages, module vs. jobs) are structurally impossible (same snapshot drives both); (e) `verifyPlugin buildPlugin` green + module tests pass.

**Gate between phase 3 and phase 4 (revised 2026-04-25):** formal `phase3-baseline.md` capture is NOT required for Phase 3. User chose the "targeted measurement" path over the "formal baseline document" path. Rationale: Prong B (RepoContextResolver memoization) and Prong A mechanical commits (scaffold, interceptor, policy, Caffeine store, mutation invalidation, Jira cache consolidation) are correctness-driven or self-comparative (emit hit-rate counters from commit 1, compare against 0% at that point). Only Prong C (SmartPoller dedup) needs a before/after timing comparison — captured in-commit via a single tool-window refresh timing measurement, recorded in the commit message itself, not in a separate doc. Perf claims in Phase 4 WILL still need profile-driven measurement, but that's Phase 4's gate, not Phase 3's.

**Gate between phase 4 and phase 5:** release the plugin (Phase 4 close). Phase 5 is a redesign that adds surface area, so running it after a stable release means (i) the redesign has a clean perf baseline to regress against, and (ii) a shippable fallback exists if Phase 5 stalls.

**Why:** user is running each phase in a separate session, so this memory is the shared plan across sessions. Ordering rationale: cleanup before architecture (don't restructure soon-to-be-deleted code); architecture before caching (don't cache a layer that's moving); caching before perf (cache hits are the biggest perf win and must be measured first); perf before workflow context (Phase 5 benefits from stable perf numbers); workflow context last because it is additive surface area and the original motivation for the branch — resolving the cross-tab state-inconsistency tech debt that prompted the branch in the first place.

**How to apply:** when a new session starts on this branch, read this file to pick up the current phase. Check git log on this branch to infer which phase is in progress or done. Do not re-plan — execute the agreed order. Update this file only if the user explicitly revises the plan.

---

## Progress log

- **Commit 1** `01cd57fc` (2026-04-24) — `chore(cleanup): remove unused imports` — 44 imports removed across 29 files. No commented-out code blocks met the 3+-line rule. Compile verified.
- **Commit 2** SKIPPED (2026-04-24) — stale TODOs/FIXMEs/@Deprecated referencing shipped features. Full inventory of 83 markers across 9 modules checked. Zero stale matches found. All TODOs are either active open work (AgentService iteration budget, AgentLoop ThreadLocal, PrBar Phase 10, Insights tab wiring), forward-looking phase markers (`TODO(phase7)` — asserted on in tests), or stable `NOTE:` documentation. Zero FIXMEs in the Kotlin codebase. Codebase's markers are well-maintained.
- **Commit 3** `437920dd` (2026-04-24) — `chore(cleanup): remove dead settings, orphaned extension points, and unused dependencies`. 3 dead PluginSettings fields (rawApiTrace{Enabled,MaxBodyMb} + rawTraceRedactPromptSecrets), 2 plugin.xml orphans (ConnectivityMonitor, GlobalSessionIndex), 1 plugin.xml package-typo fix (DatabaseSettings FQN — platform service registration was silently broken pre-fix), 6 unused Gradle deps removed. Compile + verifyPlugin green (IU-251/252/253).
- **Commit 3b** `557925bf` (2026-04-24) — `chore(cleanup): resolve flagged dead settings UI and unused coroutines-jdk8 dep`. Follow-up resolving all 4 flagged items from commit 3: removed `PluginSettings.reviewerFieldId` + `testerFieldId` (+ 4 UI rows across `JiraWorkflowConfigurable` and `AgentAdvancedConfigurable`), removed `AgentSettings.backgroundOutputSpillThresholdBytes` (+ UI row), removed `kotlinx.coroutines.jdk8` from `core/build.gradle.kts` and `agent/build.gradle.kts` (verified zero `.await()` on CompletableFuture / no `future { }` builder), removed `implementation(libs.okhttp)` from root `build.gradle.kts` (root has no .kt sources, okhttp reaches plugin JAR transitively via `:core`). 7 files, 52 deletions. Compile green.
- **Commit 4** `564439c1` (2026-04-24) — `chore(cleanup): remove dead code (aggressive, reflection-safety verified)`. Executed as 3 waves in reverse-dependency order (6 subagents total, reflection-safety checklist per candidate). 6 whole files deleted: `bamboo/ui/BuildStatusBarWidget.kt`, `agent/security/OutputValidator.kt` (+ Test), `agent/ui/CommandApprovalDialog.kt`, `agent/ui/EditApprovalDialog.kt`, `core/logging/StructuredLogger.kt`. 6 files with partial deletions (AdminState.logRequest, BitbucketBranchClientCache.invalidate, optionWidgetFor, simpleDocumentListener, HaikuPhraseGenerator.generateTitle+checkTitleUpdate, 3 AgentColors values). 12 files, 835 deletions. Compile + verifyPlugin green (IU-251/252/253). Wave 2D (agent/tools/) yielded ZERO deletions — highly cohesive subsystem. Items deliberately kept (documented): QueueEntryStatus SQLite-persisted enum values, BackgroundCapable + ProcessRegistry idle-reaper API (documented planned extension points), CredentialRedactor (documented injection point for RawApiTraceInterceptor), all WorkflowEvent subtypes (contract surface).
- **Commit 5** `8b927a2c` (2026-04-24) — `chore(cleanup): remove dead inline PR form from PrBar` (Phase 1, flavor (a) dead fallback). Sonnet sweep produced 5 candidates; Opus verification pass disqualified 4 — only PrBar legacy form survived as truly unreachable. Deleted: `formExpanded` field (init false, only ever assigned false), `formPanel` + 6 form-only Swing fields, `buildFormPanel()` + `onSubmitPr()` + `onRegenerateDescription()`, 12 form-only imports, plus stale "is unchanged" line in `bamboo/CLAUDE.md`. 2 files, 199 deletions. Compile + verifyPlugin green (IU-251/252/253).
- **Commit 5b** `985d27a3` (2026-04-24) — `chore(cleanup): drop pre-v3 session migrator and pre-5.5 Sourcegraph fallback`. Two dead-fallbacks unlocked by explicit user policy decisions after the Commit-5 Opus verification pass: (1) drop Sourcegraph < 5.5.0 support → remove `checkLlmEnabledViaGraphQL` + 404 branch in `checkLlmEnabled`; (2) plugin is pre-release / single-user → remove `SessionMigrator` object + test file + startup hook + `OldSession` inner class. 5 files, 341 deletions.
- **Commit 5c** `872683d4` (2026-04-24) — `chore(cleanup): collapse HttpMetricsInterceptor duplicate catch blocks`. Two identical-body catch branches (IOException then Exception) reduced to single `catch (Exception)`. 1 file, 4 deletions.
- **Commit 6** `479989b1` (2026-04-24) — `chore(cleanup): remove AgentSettings.approvalRequiredForEdits — UI wrote a value nothing read`. Opus max-effort audit on `PluginSettings` / `AgentSettings` / `ConnectionSettings` found ZERO true flavor-(c) dual-path flags — prior sweeps already removed them. The only genuine finding was a misleading UI checkbox: `approvalRequiredForEdits` had only self-referential UI reads; actual approval gate is driven per-tool by `ApprovalPolicy.kt`. Deleted the field + UI row. Reframed Commit 6 as "dead settings follow-up to commits 3/3b" (still single-category). 2 files, 9 deletions. Phase-1 `toolExecutionMode` explicitly NOT touched — Cline port-provenance (see `feedback_faithful_port_cline.md`).
- **Commit 6b** `38bd6359` (2026-04-24) — `chore(ui): remove "Cline-style" mention from Tool Execution Mode setting comment`. User policy: no "Cline" string in any UI surface. Audit of settings Configurables + webview found only ONE user-visible mention — combo box comment in `AgentAdvancedConfigurable`. Saved rule as `feedback_no_cline_in_ui.md`. Source-code KDoc / port-provenance comments are untouched.
- **Commit 7** `5dc3696f` (2026-04-24) — `chore(cleanup): remove three safety-net try/catch blocks whose try bodies cannot throw`. Opus max-effort audit walked all ~468 catch sites in the codebase including `git log -S` archaeology. Only 3 qualified as flavor (b) safety-nets covering nothing: `jira/TicketKeyCache.kt:43` (shadowed by specific PatternSyntaxException handler), `agent/ui/plan/AgentPlanEditor.kt:52` (empty catch around non-throwing callback invoke), `agent/ui/plan/AgentPlanEditor.kt:71` (empty catch around `toIntOrNull + callback`). Zero CancellationException-swallow bugs found. 2 files, 11 deletions.
- **Commit 7b** `5564e492` (2026-04-24) — `fix(polling): flip SmartPoller.isIdeFocused fallback to false (conservative)`. Surfaced during the Commit-7 audit as "needs judgment" — fixed at user's direction. When IdeFocusManager throws during shutdown / startup, the fallback now returns `false` (treat as unfocused → slow down polling) instead of `true` (which made polling MORE aggressive as the IDE died). One-line behavior fix with explanatory comment.
- **Commit 8** (eight sub-commits, Phase 1 — duplicate helper consolidation). Opus max-effort sweep found 8 LOW-risk consolidation groups; 3 MEDIUM-risk groups deferred (user-visible output changes in MonitorPanel/TicketDetailPanel formatters, and a markdown-converter fragment API). Test-first pattern: each winner got characterization tests in `:core` BEFORE any duplicate was removed, locking in ground truth for equivalence proof. Also CORRECTED two items the branch memory tracked as duplicates but Opus verified are NOT duplicates (`RawApiTraceInterceptor.truncate` is byte-based + returns Pair, `RuntimeExecShared.formatDuration` vs `RichStreamingPanel.formatDuration` differ on minute branch).
  - `07065589` — `test(core)` — 16 StringUtils tests
  - `ded53880` — **Commit 8a** `BackgroundProcessTool.truncate` → `StringUtils.truncate`
  - `28be3005` — `feat(core)` — `TimeFormatter.formatFileAge` + 10 tests
  - `690d187d` — **Commit 8b** Poetry/Uv `formatFileAge` → `TimeFormatter.formatFileAge`
  - `13d0358f` — `test(core)` — 11 HtmlEscape tests
  - `f4406ea8` — **Commit 8c** `:pullrequest` renderer `String.htmlEscape()` → `HtmlEscape.escapeHtml + \n→<br>` (slightly stricter: also escapes `"` / `'` — verified safe)
  - `77d2fe92` — **Commit 8d** `BambooTestResultConverter.escape` → `TeamCityMessageConverter.escapeValue` (+2 exact-match tests added)
  - `058d913c` — **Commit 8e** dedup `sanitizeHref` inside `:pullrequest` (PrDetailPanel copy removed; MarkdownToHtml copy promoted to `internal`; 9 tests added)
  - `e1439c46` — **Commit 8f** `ShellResolver.ANSI_REGEX/stripAnsi` → delegate to `OutputCollector`; redundant tests removed (equivalent coverage already in OutputCollectorTest)
  - `92a347d7` — **Commit 8g** `CoverageThresholds.GREEN/YELLOW/RED` → reference `StatusColors.SUCCESS/WARNING/ERROR`
  - `7c9e08b1` — **Commit 8h** promote `StatusColors.htmlColor` signature from `JBColor` to `Color`; delete `OverviewPanel.htmlColor` copy

## Commit 8 completed (all 3 MEDIUM-risk groups resolved per user decision 2026-04-24)

- **8i** `adf03576` — `MonitorPanel.formatDuration` → `TimeFormatter.formatDurationSeconds(zero="")`. User picked (a) accept hour-split upgrade. Run Monitor now shows `"1h 1m 5s"` instead of `"61m 5s"` for 1h+ automations.
- **8j** `5c86551b` + `50b34484` — `TicketDetailPanel.formatRelativeTime` → new `TimeFormatter.relativeFromIso(iso, maxDaysAsRelative=30, fallbackDateOnly=true)`. User picked (b) add variant matching Jira contract. Byte-equivalent migration.
- **8k** `5445f891` + `98927a5f` — `PrDetailPanel.markdownToHtml` → extended `MarkdownToHtml.convertFragment`. User picked (a) extend the canonical converter. PrDetailPanel keeps its own body wrapper (SECONDARY_TEXT color). Side effects: fenced code blocks now render in PR description (was missing), inline code gets canonical grey background, italic continues to render via new inline rule.

All of Commit 8 is now complete — 11 sub-commits (8a through 8k) plus 4 prep-test commits.

- **Commit 9** `fb378bfd` (2026-04-24) — `chore(cleanup): re-enable EditFileToolTest allowedWorkers assertion, remove stale @Disabled comments`. Re-enabled `assertEquals(setOf(WorkerType.CODER), tool.allowedWorkers)` with updated FQN (`agent.tools.WorkerType`). Cleaned 2 stale `@Disabled` comments in `RuntimeExecRunConfigTest` that referenced a disabled state the tests no longer had. Other `@Disabled` tests in codebase (RunInspectionsToolTest F5, ListQuickFixesToolTest F6, InsightsPromptPrototype) kept — they have active blocker docs for real upstream API gaps or manual prototype contexts.

## PHASE 1 COMPLETE (2026-04-24)

**Exit gate PASSED:** `./gradlew clean verifyPlugin buildPlugin` green on IU-251/252/253.

**Totals:**
- 28 commits since `65979fa6` (v0.83.24-beta)
- 85 files changed
- 632 insertions / 1682 deletions (net −1050 LOC)
- ~70 new characterization tests added in `:core` + `:pullrequest`
- 1 latent bug fixed (SmartPoller shutdown-direction)

**Ready to start Phase 2:** Architectural optimization (enforce `core interface → ToolResult<T> → feature impl → agent tool wrapper`, collapse cross-module direct calls into `EventBus`, tighten `api/ → service/ → ui/ → listeners/` layering).

## PHASE 2 COMPLETE (2026-04-24)

**Exit gate PASSED:** `./gradlew clean verifyPlugin buildPlugin` green on IU-251/252/253.

**Totals:**
- 11 commits since `fb378bfd` (Phase 1 close)
- 24 files changed
- 848 insertions / 330 deletions (net **+518 LOC**)
- 5 new characterization tests added in `:jira` (`JiraServiceImplSearchBoardsTest`)
- 1 latent threading bug fixed (removed `runBlocking` from `JiraWorkflowConfigurable` — was violating root CLAUDE.md "Never `runBlocking` in Swing")

**LOC-positive rationale (Phase 1 was −1050; Phase 2 is +518):** Phase 2 is additive because Rule C fixes require extracting services out of listeners and glue folders. New classes: `DismissedBranchStore`, `TicketDetectionPresenter`, `TicketDetectionStartupActivity`, `TagValidationService`, plus `JiraService.searchBoards` impl + DTO + tests, plus a new `WorkflowEvent.TicketDetectedInteractive` event type. The 11 commits break down as:
- 7 pure refactor commits (net −112 LOC): A, B, C, E, F, H2, I
- 3 extraction commits (net +473 LOC): D, G, H1
- 1 docs commit (net +147 LOC): J (audit file + module-structure section)

Excluding tests and docs, true architectural scaffolding cost is ~+228 LOC — acceptable for the enforcement payoff.

**Audit findings:** Rules A (agent bypass) and B (feature↔feature) showed **zero violations** — the codebase already enforced those perfectly. All Phase 2 work was Rule C (intra-module layering), concentrated in `:jira` (8 of 10 fixes), with smaller pieces in `:bamboo/run` and `:automation/run`. Audit archived at `docs/architecture/phase2-violations-audit.md` in git.

**Key architectural decisions recorded during Phase 2:**
- Rule C extended to cover IntelliJ extension-point glue folders: `vcs/`, `search/`, `tasks/`, `run/`, `settings/` must delegate to `service/`. Documented in `docs/architecture/module-structure.md` + `index.html`.
- `TicketDetected` emission semantics preserved byte-for-byte by introducing a sibling `TicketDetectedInteractive` event — presenter consumes one, banner consumes the other.
- `ToolResult` duplication (agent's non-generic vs core's generic `ToolResult<T>`) left as-is — bridged by `ServiceLookup.toAgentToolResult()`. Flagged as possible Phase 3+ cleanup but low priority.
- `:mock-server` included in `settings.gradle.kts` confirmed — initial audit misread.

**Dormant infrastructure activated by Phase 2:**
- `HttpClientFactory.clientFor(ServiceType.NEXUS)` was wired but had no production caller before Commit D. Now in use.
- All Jira HTTP (including attachments after Commit I) now routes through `HttpClientFactory.clientFor(ServiceType.JIRA)` — Phase 3 caching installs once, covers everything.

**Ready to start Phase 3:** HTTP response caching (ETag/Last-Modified) via `HttpClientFactory`, memoize `RepoContextResolver.resolveCurrentEditorRepoOrPrimary()`, `SmartPoller` result dedup.

## PHASE 3 COMPLETE (2026-04-25)

**Exit gate PASSED:** `./gradlew verifyPlugin buildPlugin` green on IU-251/252/253.

**Totals:**
- 13 commits since `27705b98` (Phase 2 close)
- ~1400 net LOC (Prong A infrastructure + tests; partially offset by Phase 2 tail cleanup of −28 LOC)
- ~70 new unit tests in `:core`
- 1 new runtime dependency (Caffeine 3.2.3, ~1 MB JAR)

**Breakdown:**
- Phase 2 tail (routing consolidation): 5 commits — migrated JiraApiClient, BambooApiClient, SonarApiClient, BitbucketBranchClient, JiraTaskRepository to `HttpClientFactory.clientFor(ServiceType)`. Three intentional exceptions documented as memory (`SourcegraphChatClient`, `DockerRegistryClient`, `AuthTestService` — protocol-level reasons).
- Prong A (HTTP response cache): 7 commits — `HttpCacheMetrics` scaffold, pass-through interceptor, URL-pattern policy registry, Caffeine-backed `HttpResponseCache` (5 MB cap, weight-eviction), activation, synthetic-ETag stale-match, `MutationInvalidationInterceptor`.
- Prong B (RepoContextResolver memoization): 1 commit via `CachedValuesManager` + `SimpleModificationTracker`.
- Prong C (SmartPoller dedup): **analysis only, no code**. MutableStateFlow's built-in `Object.equals` dedup already provides the mechanism for 4 of 5 poller consumers. Documented in `docs/architecture/phase3-research/P2-prong-c-analysis.md`. The remaining consumer (`CommentsTabPanel`) deferred to Phase 4 profile-driven evaluation.

**Measurement policy actually honoured:** no formal `phase3-baseline.md` captured (user chose the targeted-measurement path 2026-04-25). Prong A's `HttpCacheMetrics` counters emit continuously; the activation commit (4b) was self-comparative against the pass-through baseline from 4a.

**Post-close polish (2026-04-25):**
- `96af0921` — HTTP Cache diagnostics section in Telemetry & Logs settings. Live per-service stats table + "Purge HTTP cache" button. Lives under Tools > Workflow Orchestrator > Telemetry & Logs. No auto-purge on IDE close — cache is in-memory only so JVM exit already purges it; the OkHttp disk cache is preserved intentionally.

**Deferred from Phase 3 (revisit later):**
- Jira ad-hoc cache consolidation (`TicketKeyCache`, `IssueDetailCache`, `SprintPaginationCache`) — `TicketKeyCache` and `SprintPaginationCache` serve different purposes; `IssueDetailCache` is a parsed-DTO cache one layer above Prong A. Consolidation net win is small; defer to Phase 4 or later.
- Nexus asset-download disk cache (D2) — gated on measurement showing Nexus traffic > 5% of session bytes; not evaluated yet.

**Ready to start Phase 4:** EDT hotspots, coroutine scope tightening, `runBlocking` removal (list already in branch memory). Phase 4 needs its own formal baseline — this gate was deferred by Phase 3, not cancelled.

## PHASE 4 PRONG A COMPLETE (2026-04-25)

**Exit gate PASSED:** `./gradlew verifyPlugin buildPlugin` green on IU-251/252/253.

**Totals:**
- 4 commits since `96af0921` (Phase 3 close)
- 1 file changed (`agent/ui/AgentController.kt`)
- 41 insertions / 19 deletions (net +22 LOC — executeTask method split into launcher + suspend helper)
- **Zero `runBlocking` code sites remain in AgentController.kt** (one doc-comment mention at line 1205 preserved as historical reference)
- No new test failures (3 pre-existing flakes unchanged)

**Commits:**
- `1e673d5f` — **A1+A2** `perf(agent): convert AgentController.executeTask to coroutine` — fixes `runBlocking { hookManager.dispatch(...) }` (1216) and `runBlocking { channel.send(task) }` (1257). Added `executeTaskInternal` suspend helper; outer `executeTask` launches on `controllerScope + Dispatchers.EDT`. Pre-existing `controllerScope` field used (plan referenced `service.cs` but that was private; `controllerScope` has equivalent semantics — lifecycle-bound to controller).
- `29f5a6d4` — **A3** `perf(agent): launch channel.send in revisePlan instead of runBlocking on EDT`. 3-line substitution.
- `9a7e1e6a` — **A4** `perf(agent): launch channel.send in performPlanDiscard instead of runBlocking on EDT`. 3-line substitution. Chose "Option A" (keep `performPlanDiscard` synchronous) over Option B (make it suspend) — minimal scope.
- `118520f8` — **A5** `perf(agent): sequence dismissPlan history rewrite + discard inside one coroutine`. Corrects the misleading "JCEF thread, not EDT" comment from the original code — JBCefJSQuery handlers DO run on EDT. Rewrite + discard chained inside one `controllerScope.launch` so the ordering invariant (rewrite before channel.send) holds without blocking EDT.

**Re-audit correction landed 2026-04-25:** Phase 1 grep tagged 13 of 16 `runBlocking` sites as "EDT, fix" — real audit found only 5 on EDT (all in AgentController). The other 8 are on BG threads by framework contract (`runBackgroundableTask`, `executeOnPooledThread`, `Task.Backgroundable.run`, `SearchEverywhereContributor.fetchWeightedElements`, `ExternalAnnotator.doAnnotate`). The corrected Phase 4 target table and re-verification protocol are documented in `docs/architecture/phase4-prong-a-plan.md`. This dogfood test of the upgraded `intellij-plugin-performance` skill exposed the mis-classification before any bad-template commits landed.

**Deferred from Prong A (Prong A.2 — optional polish):** 7 BG-thread `runBlocking` sites. Not freezes; `runBlockingCancellable` (2025.2+) would propagate cancellation more cleanly but is lower priority. Revisit after Prongs B–E.
- `core/insights/GenerateReportAction.kt:32` (Task.Backgroundable)
- `core/settings/ConnectionsConfigurable.kt:231, 331, 444` (runBackgroundableTask)
- `core/settings/RepositoriesConfigurable.kt:247` (executeOnPooledThread)
- `core/onboarding/SetupDialog.kt:86, 137` (runBackgroundableTask)
- `jira/search/JiraSearchContributorFactory.kt:79` (SearchEverywhere BG pool)
- `sonar/ui/SonarIssueAnnotator.kt:97` (ExternalAnnotator.doAnnotate — off-EDT by contract)
- `agent/tools/builtin/ProjectContextTool.kt:226` (coroutine tool execute)

**Ready to start Prong B:** EDT hotspot fixes (font derivation, cell renderers, paint paths, allocation pressure) — profile-driven per `intellij-plugin-performance` skill § scroll-jank-playbook. Needs baseline capture first (see SKILL.md §1 Diagnosis).

## PHASE 4 PRONG C COMPLETE (2026-04-25)

**Exit gate PASSED:** `./gradlew verifyPlugin buildPlugin` green on IU-251/252/253.

**Totals:**
- 11 commits since `1cf618de` (Prong A close)
- ~25 files changed across `:core`, `:agent`, `:automation`, `:bamboo`, `:handover`, `:jira`, `:pullrequest`, `:sonar`
- Net LOC modest (~+150 / -120 across all C commits)
- Subagent-driven with Opus max effort + reviewer per commit
- 1 latent leak fixed (`TicketTransitionServiceImpl.kt:83` — `parentScope = null` fallback created unmanaged scope on every project)

**Commits (11):**
- `58351ab8` — **C1** `perf(core): cascade tool-window content dispose to Disposable panels`. WorkflowToolWindowFactory.materializeTab + buildTabs now wire `content.setDisposer(panel as Disposable)`. Cascades dispose to ~10 panels that previously had written but never-firing dispose() methods.
- `230e641a` — **C1b** `perf(ui): cascade child-panel dispose from tool-window dashboards`. Follow-up to C1 review: `Disposer.register(this, detailPanel)` in SprintDashboardPanel + PrDashboardPanel (replacing manual chain). BuildDashboardPanel needed no change — StageDetailPanel already registered correctly.
- `9b825444` — **C2** `perf(agent): consolidate 14 ad-hoc AgentController scopes onto controllerScope`. 14 fire-and-forget `CoroutineScope(Dispatchers.IO + SupervisorJob()).launch` sites collapsed onto the existing `controllerScope` field.
- `a73f33e0` — **C3** `fix(core): use supervisorScope suspend-builder in InsightsNarrativeService`. Misnamed local `val supervisorScope = CoroutineScope(...)` swapped for the real `supervisorScope { }` suspend-builder. Closes a latent cancellation orphan bug in 3 async children.
- `7a89d226` — **C4** `fix(core): inline WeeklyDigestStartupActivity body, remove unmanaged scope`. Field scope deleted; `runCatching { withContext(Dispatchers.IO) { … } }` inlined in `suspend fun execute(project)`. Platform now cancels the work on project close.
- `3b0610db` — **C5** `perf(sonar): make CoveragePreviewPanel Disposable`. Audit row was based on incorrect ownership; CoveragePreviewPanel is owned by CoverageTablePanel (not QualityDashboardPanel directly). Landed the safe partial.
- `ecff0ea3` — **C5b** `perf(sonar): make CoverageTablePanel Disposable, complete coverage panel cascade`. Closed the chain `QualityDashboardPanel → CoverageTablePanel (now Disposable) → CoveragePreviewPanel`.
- `e1b1e853` — **C6** `perf(core): make InsightsPanel Disposable, stop poller and cancel scope on dispose`. Single-file edit; C1 factory cascade picks it up via the extension-tab branch.
- `1cf618de` — **C7a** `perf(core): convert HealthCheckService and DefaultBranchResolver to service-injected scope`. 2024.1+ pattern. Both lost `: Disposable` (no other cleanup). Tests updated to use TestScope().
- `0374a6f4` — **C7b** `perf(modules): convert 8 project services to platform-injected coroutine scope`. AgentService, BackgroundPool, QueueService, BuildMonitorService, HandoverStateService, PrListService, TicketDetectionPresenter, TicketTransitionServiceImpl. AgentService + BackgroundPool kept `: Disposable` for non-scope cleanup. BuildDashboardPanel updated to call `monitorService.stopPolling()` instead of removed `dispose()`. C7b implementer subagent timed out before commit; parent agent verified compile + test (3 pre-existing flakes only) and committed.
- `76012ac0` — **C8** `perf(settings): unify Configurable scope re-init pattern (var + createComponent)`. JiraWorkflowConfigurable + AgentParentConfigurable adopt AutomationConfigurable's pattern (`var scope` + re-init guard).
- `9945bfde` — **C7c** `perf(modules): add explicit Dispatchers.IO to launch sites missed in C7b`. C7b review follow-up. 4 sites in QueueService got explicit IO; 2 sites (HandoverStateService L55, TicketTransitionServiceImpl L86) correctly kept on Default after re-audit (pure in-memory EventBus collectors).

**Audit revisions during execution (Phase 1 audit overcount, again):**
- C1 review found the original audit overstated reach: factory cascade only covers direct panels, not nested children. C1b closes the gap for Sprint + PR (3 panels).
- C5 audit said `coveragePreviewPanel` was a direct field of `QualityDashboardPanel`; it is not. Correct ownership chain: `QualityDashboardPanel → CoverageTablePanel → CoveragePreviewPanel`. C5b cascade fix closes the leak.
- C7b review found 6 launch sites silently shifted from `Dispatchers.IO` to `Dispatchers.Default` when migrating to platform-injected `cs`. C7c fixed 4 (HTTP/SQLite I/O); 2 correctly kept on Default (pure in-memory).

**Dogfood pattern paid off again:** the upgraded `intellij-plugin-performance` skill's "audit before fix" protocol caught 3 distinct audit-overstatements before they became code churn.

**Documents shipped:**
- `dcde66b4` — `docs(architecture): Phase 4 Prong A + Prong C plans and audit` (3 files: phase4-prong-a-plan.md, phase4-prong-c-audit.md, phase4-prong-c-plan.md)
- `fd4d00b9` — `docs(architecture): Phase 4 parked-prongs protocol for B / D-profile / E` (1 file: phase4-parked-prongs.md — resumption protocol when live-IDE capacity is available)

## PHASE 4 PRONG A.2 + A.2b COMPLETE (2026-04-25)

**Exit gate PASSED:** `./gradlew verifyPlugin buildPlugin` green on IU-251/252/253.

**Totals:**
- 2 commits since `fd4d00b9` (parking-lot doc)
- 12 `runBlocking` → `runBlockingCancellable` swaps across `:core` (8), `:agent` (1), `:jira` (1), `:sonar` (1), `:core/vcs` (1)
- 9 files changed, ~+46 / -27 lines
- **Inventory closed:** 0 `kotlinx.coroutines.runBlocking` matches across all 8 module main sources

**Commits:**
- `4231ce0d` — **A.2** `perf(modules): swap runBlocking → runBlockingCancellable on background-thread sites`. 10 sites in 7 files: GenerateReportAction (1), ConnectionsConfigurable (3), RepositoriesConfigurable (1), SetupDialog (2), JiraSearchContributorFactory (1), SonarIssueAnnotator (1), ProjectContextTool (1). Comment updates at JiraSearchContributorFactory + SonarIssueAnnotator. No `withContext` wrapping needed (no site had explicit dispatcher arg).
- `4fc3609d` — **A.2b** `perf(core): finish BG-thread runBlocking inventory (A.2b)`. 2 sites missed by Prong A re-audit, surfaced by A.2 review:
  - `core/healthcheck/HealthCheckCheckinHandlerFactory.kt:89` — inside `Task.Modal.run()` with cancellable=true; Modal Cancel button now propagates.
  - `core/vcs/GenerateCommitMessageAction.kt:111` — inside `Task.Backgroundable.run` with cancellable=true; per-token AI streaming inside, so Cancel button now produces immediate teardown rather than waiting for stream completion (cooperative cancel still wired as belt-and-braces).

**Audit correction logged 2026-04-25:** Prong A re-audit table claimed "10 BG-thread sites total"; actual count is 12 (the 2 A.2b sites were missed). Reviewer caught both during A.2 post-commit review. Worth a one-line note in `phase4-prong-a-plan.md` for the historical record.

## PHASE 4 PRONG D-GREP COMPLETE (2026-04-25)

**Exit gate PASSED:** `./gradlew verifyPlugin buildPlugin` green on IU-251/252/253.

**Totals:**
- 13 commits since `4fc3609d` (A.2b close)
- 49 production sites migrated from deprecated `ReadAction.compute / ReadAction.run` (the audit's 45 sites + 2 audit-miss sites surfaced by D8b grep + 2 already migrated in D3)
- ~30 files changed across `:core` (1), `:bamboo` (1), `:jira` (3), `:pullrequest` (2), `:agent` (~23 incl. tests)
- 30+ test files migrated from `mockkStatic(ReadAction::class)` shim to `mockkStatic("com.intellij.openapi.application.CoroutinesKt")` + `coEvery { readAction<Any?>(any()) } coAnswers { firstArg<() -> Any?>().invoke() }` + `runTest { }` (D6a-pioneered pattern)
- 2 latent EDT-correctness bugs fixed (MentionContextBuilder.buildFileContext + EnvironmentDetailsBuilder.appendActiveEditor)

**Final inventory:** zero `ReadAction.compute` / `ReadAction.run` matches in production across all 8 modules. Two intentional `runReadAction { }` sites remain with TODO comments for 2026.1 platform bump:
- `jira/ui/CurrentWorkSection.kt:185` (D3 — `MouseAdapter.mouseClicked` is non-suspend EDT)
- `agent/ide/IdeContextDetector.kt:114` (D9 — `@Service.init { }` chain is synchronous)

**Commits (13):**
- `d1049497` — **D1** `perf(core): convert HealthCheckService.classifyChanges to readAction`. 1 file, +3/-3.
- `522003c0` — **D2** `perf(bamboo): convert PrBar scope.launch ReadAction to readAction`. 1 file, +2/-1.
- `737885c8` — **D3** `perf(jira): convert 8 ReadAction sites to readAction/readActionBlocking`. 3 files, +13/-9. CurrentWorkSection:185 used `runReadAction` (non-suspend EDT MouseAdapter — substituted at compile target 2025.1).
- `dd58dd3e` — **D4** `perf(pullrequest): convert 6 ReadAction sites to readAction`. 2 files, +11/-11.
- `2e2ac34a` — **D5** `perf(agent): convert 5 :agent files (no test rewiring) to readAction`. 6 files, +45/-44. RuntimeConfigTool cascade through `trySetProperty` → 13 helpers became suspend.
- `143f8536` — **D6a** `perf(agent): convert 4 project-action files + tests to readAction`. 8 files, +65/-152. **Pioneered the `mockkStatic(CoroutinesKt) + coEvery readAction` shim** because the planned `runTest`-only template was insufficient — `readAction { }` calls `ApplicationManager.getService(ReadWriteActionSupport::class.java)` internally and NPE'd in unit tests.
- `c86a0691` — **D6b** `perf(agent): convert 4 project-action / build-validator files + tests to readAction`. 8 files, +73/-121. Implementer subagent hit content-filtering API error mid-execution; parent agent resumed (3 erroneous `import io.mockk.coAnswers` removed — it's an infix function, not a top-level import; BuildSystemValidatorTest migrated separately).
- `23847987` — **D6c** `perf(agent): convert 5 project-action list/detail files + tests to readAction`. 10 files, +81/-99.
- `48bbb627` — **D7a** `perf(agent): convert RuntimeExecTool + CoverageTool to readAction/smartReadAction`. 3 files, +35/-34. Mixed bucket: 3 B (`readAction`) + 3 C (`smartReadAction(project)`). RuntimeExecTool:694 uses smartReadAction because Spring Boot's checkConfiguration triggers `FileBasedIndex.ensureUpToDate`. CoverageTool cascade through 4 helpers (suspend modifier).
- `5f3b6490` — **D7b** `perf(agent): convert DebugBreakpointsTool / JavaRuntimeExecTool / ResolveFileAction to smartReadAction`. 4 files, +38/-35. All bucket C. DebugBreakpointsTool:540 collapsed `withContext(Dispatchers.IO) { ReadAction.compute { … } }` to single `smartReadAction(project) { … }`.
- `ca3af219` — **D8a** `refactor(agent): make MentionContextBuilder.buildContext suspend`. 2 files, +20/-4. Audit overstatement: `buildContext` was already suspend; only `buildFileContext` needed the modifier.
- `976f3aca` — **D8b** `refactor(agent): make EnvironmentDetailsBuilder.build suspend, propagate to AgentLoop`. 4 files, +110/-65. Highest-blast-radius: `appendActiveEditor` wraps EDT-affine reads in `withContext(Dispatchers.EDT) { readActionBlocking { … } }`; `appendOpenTabs` uses plain `readAction { }`; `AgentLoop.environmentDetailsProvider` typed `(suspend () -> String?)?`. **Build cache trap discovered:** `compileKotlin` cached old Function0 bytecode against new test compile → 50 NoSuchMethodErrors. Solution: `--no-build-cache` for verification of type-only signature changes.
- `80dcaa13` — **D9** `perf(agent): close D-grep audit-miss sites in IdeContextDetector + MentionSearchProvider`. 3 files, +28/-14. 2 audit-miss sites surfaced by D8b grep:
  - IdeContextDetector:114 — bucket E `runReadAction { }` + TODO (synchronous `@Service.init` chain).
  - MentionSearchProvider:353 — bucket C `smartReadAction(project) { }` (PsiShortNamesCache + index-required).

**Audit corrections recorded across the prong:**
- D6a: `runTest`-only test template insufficient → required `mockkStatic(CoroutinesKt) + coEvery { readAction<Any?>(any()) } coAnswers { firstArg<() -> Any?>().invoke() }` shim.
- D6b: `import io.mockk.coAnswers` is wrong — `coAnswers` is an infix function on `MockKStubScope`, not a top-level. Use `coEvery { ... } coAnswers { ... }` infix syntax.
- D7a: `Dispatchers.IO` redundant with `smartReadAction` (which is dispatcher-aware suspending).
- D8a: audit said both `buildContext` and `buildFileContext` were non-suspend; only `buildFileContext` was — implementer correctly applied minimum scope.
- D8b: surfaced 2 production sites missed by audit (IdeContextDetector + MentionSearchProvider) — D9 closed.
- D8b: build-cache trap with type-only suspend signature changes — `--no-build-cache` required for verification.

**Phase 4 status — correctness-complete (Prong A + A.2 + A.2b + C + D-grep):**
- Prong A done (5 EDT freezes fixed)
- Prong A.2 + A.2b done (12 BG-thread `runBlocking` → `runBlockingCancellable`)
- Prong C done (11 commits scope leak elimination)
- **Prong D-grep done (13 commits; 49 ReadAction sites migrated; 2 latent EDT bugs fixed)**
- Prong B (EDT hotspots) — **parked** pending live-IDE profiling capacity
- Prong D-profile (PSI batching for hot paths) — **parked**
- Prong E (JCEF rendering) — **parked**

**Releasable now.** Profile-driven optimization (B / D-profile / E) follows in a future release cycle per the parked-prongs doc.

## PHASE 4 CLOSEOUT COMPLETE (2026-04-25)

**Exit gate PASSED:** `./gradlew verifyPlugin buildPlugin` green on IU-251/252/253. Background `./gradlew :agent:clean :agent:test --no-build-cache` confirmed only 4 pre-existing flakes (no Phase 4 regressions).

**Closeout commits (P4-1 through P4-6 — 6 commits):**

- `2378fce9` — **P4-1** `refactor(agent-test): extract ReadActionTestShim helper, dedupe 17 test files`. New `agent/src/test/.../testutil/ReadActionTestShim.kt::installReadActionInlineShim()` stubs all 3 builders (`readAction`, `readActionBlocking`, `smartReadAction`) uniformly. 18 files changed, +60/-144 (net -84 LOC; helper adds 26 lines, gross shim removal -110 LOC). Reviewer flagged a transient "77 failures" claim from the implementer that turned out to be unverifiable from disk; clean baseline confirms only 4 pre-existing flakes.
- `de4a5aa1` — **P4-2** `docs(claude.md): document Gradle build-cache trap for suspend signature changes`. Single paragraph in CLAUDE.md § Rebase explaining the D8b discovery: when a commit flips lambda type to/from `suspend`, Gradle's compile-avoidance can keep stale `Function0` bytecode and produce `NoSuchMethodError` at runtime. Solution: `--no-build-cache`. 1 file, +2/-0.
- `fa3b074c` — **P4-3** `docs(architecture): mark D-grep complete + commit D-grep plan/audit`. Updated parked-prongs doc header (commit counts per prong); also committed previously-untracked `phase4-prong-d-grep-plan.md` + `phase4-prong-d-grep-audit.md` (mirrors Prong A and C plan/audit pair structure). 3 files, +417/-6.
- `0793c251` — **P4-4** `docs(modules): refresh CLAUDE.md files for Phase 4 patterns`. 8 module CLAUDE.md files updated. `:core/CLAUDE.md` carries the canonical "Service & threading conventions (Phase 4)" 9-line block; sibling modules cite it terselely. Cross-doc consistency verified. 8 files, +29/-6.
- `ae8ba689` — **P4-5** `docs(architecture): refresh threading-model + index.html for Phase 4`. `threading-model.md` Rule 5 rewritten as "Service-Injected CoroutineScope (2024.1+)"; Rule 6 added (`runBlocking` policy); new "Read Actions" section with 4-API decision-tree table + 2 survivors + build-cache trap; "Tool-Window Dispose Cascade" mini-section; anti-patterns table refreshed. `index.html` § 13 gets new "Service & Threading Conventions (Phase 4)" subsection. `module-structure.md` correctly left untouched (purely module-dependency-graph content). 2 files, +85/-20.
- `5c84747c` — **P4-6** `docs(architecture): Phase 4 closeout summary`. New `docs/architecture/phase4-closeout.md` (72 lines) — single-page digest: what landed (4 correctness prongs + 6 closeout commits), what's parked (B / D-profile / E with resumption protocol pointer), 2 platform-bump debt items (`CurrentWorkSection.kt:185`, `IdeContextDetector.kt:114`), notable patterns established, "what's next" pointer to Phase 5. 1 file, +72/-0.

**Audit-overstatement pattern across the entire branch (recorded for future feedback memory):**
- Phase 1 grep → Prong A re-audit (overcounted EDT freezes 8×)
- C1 → C1b (factory cascade reach overstated)
- C5 → C5b (CoveragePreviewPanel ownership chain wrong)
- C7b → C7c (6 launch sites lost implicit Dispatchers.IO)
- A.2 → A.2b (2 BG-thread sites missed)
- D8b → D9 (2 ReadAction sites missed)
- D6a runTest-only template → CoroutinesKt mockkStatic shim (test infrastructure pattern incomplete)

**Conclusion:** subagent-driven dev with both implementer + reviewer is reliably better than implementer-alone. Audits without reviewer-pass tend to overstate or undercount sites; reviewer-pass surfaces audit gaps as APPROVE-WITH-FOLLOWUP verdicts that produce small targeted follow-up commits.

**Phase 4 final commit count:** 39 commits (Prong A: 4, A.2/A.2b: 2, C: 11, D-grep: 13, P4 closeout: 6, plus 3 doc commits during prongs — `dcde66b4` Prong A+C plans, `fd4d00b9` parked-prongs doc, in-prong commits for plan/audit docs).

**Branch is 93 commits ahead of main.** Releasable as v0.83.25-beta (or whatever bump cadence dictates).

**Phase 5 (next session):** brainstorm-gated `WorkflowContextService` redesign per branch's original motivation. Run `superpowers:brainstorming` first per the gate condition.

## PHASE 5 COMPLETE (2026-04-26)

**Exit gate PASSED:** `./gradlew clean verifyPlugin buildPlugin` green on IU-251/252/253. All module tests green except 3 pre-existing `:agent` debug-test flakes (NPE on `ApplicationManager.getApplication()` in `AgentDebugControllerTest` × 2 + `DebugBreakpointsToolTest` — platform fixture issue, not Phase 5 surface).

**Brainstorm + spec + plan + execution complete:**
- Spec: `docs/architecture/workflow-context-design.md` (HEAD `73de193a`).
- Plan: `docs/architecture/phase5-workflow-context-plan.md` v3 (HEAD `7bd9f862`) — 20 tasks, all shipped.

**T1-T20 commit map:**
- `c6b3890a` + `bf64e57a` — T1 data types (`WorkflowContext`, `Refs`, `InteractionMode`) + purity tests
- `7a191fec` — T2 EP interfaces (`OpenPrLister`, `LatestBuildLookup`)
- `294131ec` — T3 `OpenPrListerImpl` in `:pullrequest`
- `d64ea435` — T4 `LatestBuildLookupImpl` in `:bamboo`
- `0bbb48f3` — T5 `WorkflowContextService` skeleton + boot anchor load
- `2653ddfd` — T6 editor listeners + `setActiveTicket` cascade with PR auto-seed
- `b74e2f19` + `04f6da80` — T7 `focusPr` cascade with mutex serialization
- `ebbf7e6c` + `54b624e5` — T8 `WorkflowEventMirror` + `WorkflowContextProjectActivity` + §5.3 dual-write test
- `1e0c9024` — T9 active-ticket bar reads from service (single-source migration)
- `a14520d3` — T10 BuildDashboardPanel PrBar + job stages SAME COMMIT (NB6); coherence test
- `335d6c44` — T11 QualityDashboardPanel reads `focusQualityScope`
- `82312b94` — T12 PR row click drives `service.focusPr` + §5.3 re-emit + cross-tab test
- `05c4fb82` — T13 Sprint Start Work + ActiveTicketService facade (synchronous-write contract preserved) + HandoverStateService + Automation panel
- `389f8fca` + `e90d0ce3` — T14 `ReadOnlyBanner` + `bindLiveOnlyEnablement` + flicker test
- `8662afef` — T15 banner wired into Build/Quality/PrDetail; perform-time gate on `PrDetailPanel.navigateToFile`
- `234116ca` — T16 `EventBus.prContextMap` deleted; `:agent ProjectContextTool` migrated to `service.state.value.focusPr`
- `eea88181` — T17 `<workflow_context>` block in agent system prompt + `MentionSearchProvider` active-ticket fallback
- `46b78ca6` + `eff867d0` — T18 `WorkflowContextEditorIntegrationTest` (`BasePlatformTestCase`) + `LoggedErrorProcessor` strict-error guard; `junit-vintage-engine` wired
- `566dd960` — T19 `core/CLAUDE.md` + `threading-model.md` + `index.html` refreshed for Phase 5

**Phase 5 totals:**
- 30 commits since `04f6da80` (T7 close): 13 task commits + 4 followup commits + T20 closeout.
- Branch: 124 commits ahead of `main` total (Phase 1: 28, Phase 2: 11, Phase 3: 13, Phase 4: 39, Phase 5: 30 + closeout).
- New `:core` packages: `core/model/workflow/`, `core/workflow/`, `core/workflow/ui/`.
- New EPs: `openPrLister`, `latestBuildLookup` (cross-module bridge without dep cycle).
- ~150 new tests in `:core` / `:bamboo` / `:pullrequest` / `:jira` / `:handover` / `:agent` covering the spec's §9.1, §9.2, §9.3 invariants.

**Exit-criteria audit (spec §12):**
- (a) No panel resolves repo/branch/ticket/PR/build on its own — all reads go through `WorkflowContextService.state`. ✓
- (b) `PrDashboardCrossTabTest` passes (T12). ✓
- (c) Sprint Start Work propagates via facade transitively (T13). ✓
- (d) Within-tab mismatch structurally impossible — `BuildDashboardPanelCoherenceTest` proves single-emission invariant via Turbine (T10). ✓
- (e) `interactionMode` flips correctly across §9.1 cases — `InteractionModePurityTest` (T1). ✓
- (f) `<workflow_context>` block included when state non-empty — `EnvironmentDetailsBuilderWorkflowContextTest` (T17). ✓
- (g) `git grep prContextMap` returns ZERO active-code hits (3 comment-only references documenting the migration history are intentional per plan; tracked). ✓
- (h) `verifyPlugin buildPlugin` green on IU-251/252/253. ✓
- (i) Docs refreshed (T19). ✓

**Reviewer-cadence pattern (per task):** implementer subagent (Opus, max effort) + `superpowers:code-reviewer` subagent (Opus). 5 APPROVE verdicts, 8 APPROVE-WITH-FOLLOWUP verdicts (each producing a small follow-up commit). Zero REQUEST-CHANGES verdicts — all reviewer concerns either satisfied via small follow-ups or correctly deferred to Phase 5b (e.g., `SonarDataService.subscribeToEvents` deletion, missed-migration on `else { detailPanel.showPr(prId) }` branch, real-EDT flicker test).

**Phase 5b backlog (post-merge cleanup, separate branch — NOT blocking this branch):**
- Delete `WorkflowEvent.PrSelected` / `TicketChanged` / `BranchChanged` after all subscribers migrated. Mirror becomes vestigial; remove `WorkflowEventMirror` + `WorkflowContextProjectActivity`.
- Delete `SonarDataService.subscribeToEvents` (currently dual-fires with `QualityDashboardPanel.renderForQualityScope`; coalesced by SonarDataService's existing 500ms debounce).
- Migrate `else { detailPanel.showPr(prId) }` in PrDashboardPanel to also drive `service.focusPr` after fetch.
- Real-EDT integration test for banner visibility (T14 followup) using `LightPlatformTestCase` instead of `runTest`'s conflated StateFlow.
- Delete the legacy `# Active Ticket` markdown section in `EnvironmentDetailsBuilder` once all `ActiveTicketService` callers migrate to `WorkflowContextService` directly.

**Supersedes:** `project_active_ticket_visibility.md` (the deferred memory targeted in this Phase). Mark deletable.

**Releasable now.** Branch is at `124 commits ahead` of `main`, working tree clean.

## Self-delete trigger (re-record)

When this branch merges to main or is deleted, this memory file should be removed AND the temporary skill `~/.agents/skills/simplify-scoped/` should be deleted (created on 2026-04-24 scoped to this branch's Phase 1 cleanup). See top-of-file self-delete trigger for the protocol.

## Sub-agent / reviewer pattern recorded for future prongs

User directive 2026-04-25: "subagent-driven but with opus max effort and with reviewers". Pattern used throughout Prong C:
- 1 implementer subagent per commit (Opus model, max effort)
- 1 `superpowers:code-reviewer` subagent per commit (Opus model)
- Sequential dispatch where commits touch the same file; parallel dispatch when files don't overlap
- Reviews surfaced 3 audit-overstatement issues that became follow-up commits (C1b, C5b, C7c)
- One C7b subagent timed out at 198 tool uses / 140 minutes before committing; parent agent verified compile + test and committed manually. Future plans should consider splitting commits with > 5 file edits into smaller scopes to reduce subagent timeout risk.

**GATE BEFORE PHASE 3:** capture baseline metrics against `main` — startup time, tool-window open latency, build-tab refresh time, agent first-token latency. Without baseline, any Phase 3 perf/caching claim is unfalsifiable. This is a manual step the user performs in the IDE; NOT a scheduled task. Record numbers in a new `docs/architecture/phase3-baseline.md` before the first Phase 3 commit.

## Policy decisions recorded during Phase 1 (2026-04-24)

These shaped Commit 5b and inform future sweeps:

- **Sourcegraph minimum supported version: 5.5.0.** Plugin may assume `/.api/client-config`, `codyEnabled` in client-config payload, and `Authorization: token` scheme work. GraphQL `isCodyEnabled` fallback is gone.
- **Pre-release / single-user status.** Until formal external release, there is no obligation to preserve one-shot migration code, legacy format readers, or compat shims for "old plugin users" because there are none. This specifically covers pre-v3 session data readers. Revisit this policy on first public release (bump to v1.0.0).
- **Delete-over-defer bias.** When a fallback exists only to cover a version/format the user has committed to dropping, delete now rather than deferring — the memory cost of "we plan to delete this later" accumulates faster than the deletion itself.

## Disqualified dead-fallback candidates (do NOT re-investigate in later commits)

After Commit 5c, only one candidate remains disqualified (still live):

- **CreatePrDialog `transitionCheckbox`/`transitionCombo`** (`pullrequest/ui/CreatePrDialog.kt:223-225,300-310,1075-1083`) — Opus-verified live. The `if` branch at line 300-306 leaves the checkbox at default Swing visibility (visible) and fires whenever `context.transitions` is non-empty. To delete requires a behaviour-change refactor retiring the legacy pre-PR transition combo in favour of `postPrTransitionCombo` (the new TicketTransitionService path) — out of scope for Phase 1.

## Deferred findings (collected during Phase 1, to act on in later commits)

### For Phase 4 (performance) — `runBlocking` sites — re-classified 2026-04-25 after dogfood

The phase-1 inventory was a grep, not a thread-context audit. Real risk depends on the caller's thread. Phase 4 must re-verify each site before fixing.

**High risk (EDT — freeze):**
- `core/settings/ConnectionsConfigurable.kt:231,331,444` — 3× button click listeners
- `core/settings/RepositoriesConfigurable.kt:243` — Swing action
- `core/onboarding/SetupDialog.kt:86,137` — 2× dialog action listeners
- `jira/settings/JiraWorkflowConfigurable.kt` — settings UI (one site killed in Phase 2 H2)
- `sonar/ui/SonarIssueAnnotator.kt` — `ExternalAnnotator.apply()` runs on EDT (move PSI work to `doAnnotate()`)
- `agent/ui/AgentController.kt:1216,1257,2697,2727,2742` — 5× JCEF bridge callbacks (EDT by default)

**Low risk (BG thread — no freeze, but `runBlockingCancellable` is cleaner):**
- `core/insights/GenerateReportAction.kt:32` — inside `Task.Backgroundable.run()`
- `jira/search/JiraSearchContributorFactory.kt:79` — `SearchEverywhereContributor.fetchWeightedElements` runs on SE's BG pool (code comment in file confirms)
- `agent/tools/builtin/ProjectContextTool.kt:226` — coroutine tool execute path

### For Commit 8 (:core consolidation)
- `core/http/RawApiTraceInterceptor.kt:216` — private `truncate(text, maxBytes)` duplicates `StringUtils.truncate`
- `agent/tools/runtime/RuntimeExecShared.kt:315` + `agent/ui/RichStreamingPanel.kt:344` — `formatDuration(ms: Long)` duplicated verbatim
- `agent/tools/framework/build/PoetryActions.kt:373` + `UvActions.kt:251` — `formatFileAge`/`formatUvFileAge` near-identical
- `agent/tools/builtin/BackgroundProcessTool.kt:223` — local `truncate(s, max)` duplicates `StringUtils.truncate`

### For Commits 4–7 (dead code / fallbacks) — candidates to investigate
- `agent/tools/builtin/RunCommandTool.kt` — `ShellType` enum may be dead if `ShellResolver` internalized type resolution (an unused `ShellType` import was removed in commit 1; verify)
