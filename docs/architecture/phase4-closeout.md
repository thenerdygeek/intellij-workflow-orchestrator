# Phase 4 closeout

**Status:** correctness-complete; profile-driven prongs parked
**Branch:** `refactor/cleanup-perf-caching`
**Date closed:** 2026-04-25
**Total Phase 4 commits:** 39 (38 before this doc + P4-6)

---

## What landed

### Correctness prongs (no live IDE required)

| Prong | Commits | Sites | Outcome |
|---|---|---|---|
| A — EDT freezes | 4 | 5 sites in `AgentController.kt` | All EDT `runBlocking` removed; `executeTask` split into launcher + suspend helper; `revisePlan`, `performPlanDiscard`, `dismissPlan` chained in coroutines |
| A.2 + A.2b — BG `runBlocking` polish | 2 | 12 BG-thread sites | `runBlockingCancellable` substituted; cancel now propagates from `ProgressIndicator` and streaming AI tasks |
| C — Coroutine scope tightening | 11 | 58 audited sites | Service-injected scope (2024.1+ pattern) adopted for 10 services; factory-level dispose cascade wired for all tool-window panels; 2 latent leaks fixed (`InsightsNarrativeService` cancellation orphan, `TicketTransitionServiceImpl` unmanaged scope) |
| D-grep — ReadAction deprecation | 13 (+2 aux) | 49 production sites | `readAction`/`smartReadAction`/`readActionBlocking` adopted; 30 test `mockkStatic(ReadAction)` shims replaced; 2 latent EDT-correctness bugs fixed (`MentionContextBuilder.buildFileContext`, `EnvironmentDetailsBuilder.appendActiveEditor`); 2 intentional `runReadAction` survivors with TODO |

Plus 6 closeout commits (P4-1 through P4-6): `ReadActionTestShim` helper extraction, build-cache trap doc, module `CLAUDE.md` sweep, architecture docs refresh (threading-model + index.html), this closeout doc.

### Parked prongs (live IDE required)

| Prong | Why parked | Resumption protocol |
|---|---|---|
| B — EDT hotspots | Profile-driven (Async Profiler flame graphs for scroll, dialog open, tab switch, LAF toggle) | `phase4-parked-prongs.md` §B |
| D-profile — PSI batching for hot paths | Profile-driven (`readAction.nonBlocking` upgrade decisions; `CachedValuesManager` candidates) | `phase4-parked-prongs.md` §D-profile |
| E — JCEF rendering | Profile-driven (Chrome DevTools Long Tasks; list virtualization; streaming-token batching) | `phase4-parked-prongs.md` §E |

---

## Two intentional debt items (deferred to platform bump)

Both surfaced during D-grep; both carry `// TODO` comments in source. Re-migrate when `pluginUntilBuild` rises to `261.*`.

- `jira/ui/CurrentWorkSection.kt:185` — `runReadAction { }` from `MouseAdapter.mouseClicked` (D3). Non-suspend EDT context; `readActionBlocking` would be the correct mechanical swap but is functionally equivalent at this platform level. Long-term fix: dispatch via `scope.launch { val repo = readAction { … }; withContext(Dispatchers.EDT) { showPopup(repo) } }`.
- `agent/ide/IdeContextDetector.kt:114` — `runReadAction { }` from a `@Service.init { }` synchronous initializer chain (D9). The chain is not suspendable at its entry point; requires restructuring the service init before the API can change.

---

## Notable patterns established

- **Audit before fix, always.** Every prong discovered that the Phase 1 grep inventory overstated risk (5 real EDT `runBlocking` not 13; 49 real ReadAction sites not 53). The upgraded `intellij-plugin-performance` skill's "measure before you touch" rule paid dividends in avoiding churn.
- **Factory-level dispose fix multiplies impact.** One two-line change in `WorkflowToolWindowFactory` (`content.setDisposer(panel as Disposable)`) cascaded correct disposal to ~10 panels that had written but never-firing `dispose()` methods.
- **Type-only suspend signature changes require `--no-build-cache`.** Gradle's incremental compiler caches the old `Function0` bytecode against a new `suspend` signature, producing `NoSuchMethodError` at test time. Documented in root `CLAUDE.md` + `docs/architecture/` threading model.
- **Test shim template evolved mid-prong.** D6a discovered that `runTest`-only was insufficient — `readAction { }` calls `ApplicationManager.getService(ReadWriteActionSupport::class.java)` internally and NPE'd. The pioneered shim (`mockkStatic(CoroutinesKt) + coEvery { readAction<Any?>(any()) } coAnswers { firstArg<() -> Any?>().invoke() }`) is now extracted into `ReadActionTestShim` (P4-1) and used by all 17 affected test files.
- **Service-injected scope (`cs: CoroutineScope`) eliminates `Disposable` boilerplate.** Migrating to the 2024.1+ platform-injected pattern removed `override fun dispose() { scope.cancel() }` from 8 services. The pattern is now the documented standard in module `CLAUDE.md` files.

---

## Provenance

| Doc | What it covers |
|---|---|
| `docs/architecture/phase4-prong-a-plan.md` | Prong A site inventory, re-audit correction, fix templates, commit order |
| `docs/architecture/phase4-prong-c-plan.md` | Prong C commit plan (C1–C8), per-commit detail, exit criteria |
| `docs/architecture/phase4-prong-c-audit.md` | 58-site classification (SAFE/FIX/CONVERT/LEAKY), notable findings |
| `docs/architecture/phase4-prong-d-grep-plan.md` | D-grep 12-commit plan, per-commit detail, exit criteria |
| `docs/architecture/phase4-prong-d-grep-audit.md` | 45-site classification (A–F buckets + 30 test mocks), notable findings |
| `docs/architecture/phase4-parked-prongs.md` | B/D-profile/E capture targets, commands, resumption protocol, release gate |
| Branch memory `project_branch_refactor_cleanup_perf_caching.md` | Progress log with commit SHAs for all 4 prongs + full Phase 1–3 history |

---

## What's next

**Release gate:** Phase 4 is correctness-complete. The plugin is releasable now per `phase4-parked-prongs.md` §Release gate. Bump `pluginVersion`, `./gradlew clean buildPlugin`, `gh release create`.

**Phase 5 — WorkflowContextService:** after release, run `superpowers:brainstorming` to design the cross-tab shared selection model (`WorkflowContextService` in `:core`, `StateFlow<WorkflowContext>`, `SelectionChanged` event family). This was the original motivation for creating the branch. Gate: brainstorm-first, then `docs/architecture/workflow-context-design.md`, then code. See branch memory §Phase 5 for full scope and exit criteria.

**Profile-driven prongs (B/D-profile/E):** resume in a follow-up release cycle when a live IntelliJ instance is available. The capture targets, commands, and per-hotspot decision trees are fully documented in `phase4-parked-prongs.md` — pick up cold with no additional planning.
