# Phase 4 Prong D-grep — ReadAction deprecation plan

**Status:** draft, pending user approval
**Branch:** `refactor/cleanup-perf-caching`
**Date:** 2026-04-25
**Audit:** `docs/architecture/phase4-prong-d-grep-audit.md` (45 production sites, 30 test mocks)

---

## Scope

IntelliJ Platform 2026.1 deprecated `ReadAction.compute { }`, `ReadAction.run { }`, and `runReadAction { }` (the deprecated APIs are non-cancellable — a user edit mid-read must wait for the read to complete). Replace all 45 production call sites with the appropriate 2024.1+ API.

Live IDE not required. Source + compile + test verifiable.

---

## Audit summary

- **A. SAFE-TO-LEAVE (doc-only):** 8 — fix when their source file's call site migrates
- **B. CONVERT to `readAction { }`:** 33 — most are mechanical
- **C. CONVERT to `smartReadAction(project) { }`:** 8 — index-required
- **D. nonBlocking:** 0 — no qualifying sites
- **E. `readActionBlocking { }`:** 1 — one EDT-only popup
- **F. NEEDS-CALLER-REFACTOR:** 2 — `MentionContextBuilder` + `EnvironmentDetailsBuilder`
- **Test mocks:** 30 across 15 files — bundled with their production file

**Most-affected module:** `:agent` (31 of 45 sites).

---

## Commit plan — 12 commits

The audit suggested 18-22; consolidating Wave 2 (D6) into bundled per-category commits brings it to 12. The bundling rule is **test + production same commit, ≤ 5 files per commit** to keep subagent runtime under the C7b timeout threshold.

| # | Commit | Sites | Files | Risk | Model |
|---|---|---|---|---|---|
| **D1** | `perf(core): convert HealthCheckService.classifyChanges to readAction` | 1 | 1 | Very low | Sonnet |
| **D2** | `perf(bamboo): convert PrBar.scope.launch read to readAction` | 1 | 1 | Very low | Sonnet |
| **D3** | `perf(jira): convert 7 ReadAction sites to readAction/readActionBlocking` | 7 | 3 | Low | Sonnet |
| **D4** | `perf(pullrequest): convert 6 ReadAction sites to readAction` | 6 | 2 | Low | Sonnet |
| **D5** | `perf(agent): convert 5 :agent files (no test rewiring) to readAction` | 8 | 5 | Low | Sonnet |
| **D6a** | `perf(agent): convert 4 :agent project-action files (with test rewiring) to readAction` | ~14 | 8 | Medium | Opus |
| **D6b** | `perf(agent): convert 4 :agent project-action files (with test rewiring) to readAction` | ~12 | 8 | Medium | Opus |
| **D6c** | `perf(agent): convert remaining :agent project-action files (with test rewiring) to readAction` | ~10 | 6-8 | Medium | Opus |
| **D7a** | `perf(agent): convert RuntimeExecTool / CoverageTool to smartReadAction (index-required)` | 5 | 3 | Medium | Opus |
| **D7b** | `perf(agent): convert DebugBreakpointsTool + JavaRuntimeExecTool to smartReadAction` | 4 | 2 | Medium | Opus |
| **D8a** | `refactor(agent): make MentionContextBuilder.buildContext suspend (caller-refactor)` | 1 | 2-3 | Medium-high | Opus |
| **D8b** | `refactor(agent): make EnvironmentDetailsBuilder.build suspend, propagate to AgentLoop` | 1 | 3 | High | Opus |

**12 commits.** Same plan/audit/site-by-site shape as Prong A and C. Each commit gets a reviewer.

---

## Per-commit detail

### D1 — HealthCheckService

**Target:** `core/src/main/kotlin/com/workflow/orchestrator/core/healthcheck/HealthCheckService.kt:35`

**Fix:**
```kotlin
// BEFORE
private fun classifyChanges(project: Project, changedFiles: List<VirtualFile>): ChangeClassification =
    ReadAction.compute<ChangeClassification, RuntimeException> {
        // body
    }

// AFTER
private suspend fun classifyChanges(project: Project, changedFiles: List<VirtualFile>): ChangeClassification =
    readAction {
        // body unchanged
    }
```

Caller `runChecks` is already suspend. Imports: replace `com.intellij.openapi.application.ReadAction` with `com.intellij.openapi.application.readAction`.

### D2 — PrBar

**Target:** `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/PrBar.kt:285`

Inside `scope.launch { … readAction { … } }`. Mechanical swap.

### D3 — `:jira` (3 files)

**Targets:**
- `jira/ui/CurrentWorkSection.kt:143` (B), `:183` (E — `readActionBlocking`)
- `jira/ui/SprintDashboardPanel.kt:793, 821, 886` (B)
- `jira/service/BranchingService.kt:107, 121, 211, 226` (B)

Mechanical for all B sites. Line 183's `readActionBlocking` is the EDT-popup case — see audit §E.

### D4 — `:pullrequest` (2 files)

**Targets:**
- `pullrequest/service/PrDescriptionGenerator.kt:197, 213, 229` (B) — note: lambda body wraps git-subprocess; could be deleted entirely (audit §1 of out-of-scope), but treat as B for safety.
- `pullrequest/action/CreatePrPrefetch.kt:121, 231, 236` (B)

### D5 — `:agent` Wave 1 (no test rewiring)

**Targets (5 files, 8 sites):**
- `agent/tools/framework/build/BuildModuleDependencyGraphAction.kt:27, 48, 89` (3)
- `agent/tools/runtime/RuntimeConfigTool.kt:528` (1)
- `agent/tools/builtin/SyntaxValidator.kt:56` (1)
- `agent/tools/builtin/ProjectContextTool.kt:256` (1) — Cline-port file
- `agent/tools/vcs/ChangelistShelveTool.kt:84` (1)

Verify with `grep -rn "mockkStatic(ReadAction" agent/src/test` that none of these have test mocks; if any do, move to D6.

### D6 — `:agent` Wave 2 (with test rewiring) — 3 bundled commits

The 12 `agent/tools/project/...` files all share a similar test pattern. Bundle in 3 commits, ~4 files each, test+prod together.

**D6a (4 files):**
- `SetLanguageLevelAction.kt` + test
- `SetModuleDependencyAction.kt` + test
- `SetModuleSdkAction.kt` + test
- `SetModuleDependencyAction.kt` already in this commit; instead pick `RemoveModuleDependencyAction.kt` + test

**D6b (4 files):**
- `AddSourceRootAction.kt` + test
- `AddContentRootAction.kt` + test
- `RemoveContentRootAction.kt` + test
- `BuildSystemValidator.kt` + test (also in `agent/tools/runtime/`)

**D6c (4 files):**
- `ListLibrariesAction.kt` + test
- `ListSdksAction.kt` + test
- `ListFacetsAction.kt` + test
- `ModuleDetailAction.kt` + test

Plus `TopologyAction.kt` + test (5th in D6c if it fits) or roll into the next commit.

**Test rewiring template per file:**

```kotlin
// BEFORE (mockkStatic shim)
@BeforeEach
fun setUp() {
    mockkStatic(ReadAction::class)
    val slot = slot<ThrowableComputable<Any, *>>()
    every { ReadAction.compute(capture(slot)) } answers { slot.captured.compute() }
}

@Test
fun someTest() {
    val result = action.executeFoo(...)
    // assertions
}

// AFTER (runTest)
@Test
fun someTest() = runTest {
    val result = action.executeFoo(...)
    // assertions — readAction { } now runs on the test scheduler
}
```

Drop `mockkStatic(ReadAction::class)` entirely. Make every test method `= runTest { … }`.

### D7 — `:agent` Wave 3 (smartReadAction, index-required) — 2 commits

**D7a — RuntimeExecTool / CoverageTool:**
- `agent/tools/runtime/RuntimeExecTool.kt:652, 655` (B), `:694` (C — Spring Boot index req)
- `agent/tools/runtime/CoverageTool.kt:1089, 1106` (C), `:1121` (B — index-free)
- Test: `RuntimeExecRunConfigTest.kt:176, 181`

**D7b — DebugBreakpointsTool / JavaRuntimeExecTool:**
- `agent/tools/runtime/JavaRuntimeExecTool.kt:905, 924` (C)
- `agent/tools/debug/DebugBreakpointsTool.kt:365, 540` (C — :540 collapses `withContext + ReadAction.compute` to single `smartReadAction`)
- No tests in grep (verify before commit)

`smartReadAction(project) { … }` for index-required sites; plain `readAction` for the bucket-B sites (RuntimeExecTool:652, 655; CoverageTool:1121).

### D8 — Bucket F caller refactors — 2 commits

**D8a — MentionContextBuilder caller chain:**
- Mark `buildContext` and `buildFileContext` suspend.
- Update caller `AgentController.executeTaskWithMentions:1157` (already in coroutine).
- Wider grep for any other caller of `buildContext`.
- Files: `MentionContextBuilder.kt`, `AgentController.kt`, possibly tests.

**D8b — EnvironmentDetailsBuilder + AgentLoop provider type:**
- Mark `EnvironmentDetailsBuilder.build(...)` suspend.
- Change `AgentLoop.environmentDetailsProvider: (() -> String?)?` to `suspend (() -> String?)?`.
- Inside `appendActiveEditor`, `withContext(Dispatchers.EDT) { readActionBlocking { … } }` for editor-affine reads.
- Inside `appendOpenTabs` (line 121), plain `readAction { … }`.
- Files: `EnvironmentDetailsBuilder.kt`, `AgentLoop.kt`, `AgentService.kt` (provider lambda).
- Highest blast radius commit. Opus, separate commit, dedicated review.

---

## Verification per commit

1. `./gradlew :<module>:compileKotlin` for each touched module
2. `./gradlew :<module>:test` for each — confirm only the 3 pre-existing flakes from Prong A
3. `./gradlew verifyPlugin buildPlugin` after the last commit (D8b) — green on IU-251/252/253
4. `grep -rn "ReadAction\.compute\|ReadAction\.run\|runReadAction" --include='*.kt' core/src/main jira/src/main bamboo/src/main sonar/src/main pullrequest/src/main automation/src/main handover/src/main agent/src/main` — should return only doc-comment matches and possibly `MockServerMain.kt`

---

## Exit criteria

- Zero `ReadAction.compute` / `ReadAction.run` / `runReadAction` call sites in production sources across all 8 modules.
- All test mocks (`mockkStatic(ReadAction::class)`) replaced or removed.
- `verifyPlugin buildPlugin` green on IU-251/252/253.
- All module tests pass (only the 3 pre-existing flakes from Prong A).
- Branch memory updated with D-grep commit SHAs.

---

## Out of scope for D-grep

- Per `phase4-prong-d-grep-audit.md` §Out-of-scope observations:
  1. Deleting truly-unused `ReadAction.compute` wraps (e.g. PrDescriptionGenerator git-subprocess sites)
  2. `BuildSystemValidator.validateForTestRun` `ProgressManager.checkCanceled()` polish
  3. `JvmTestFrameworkResolver` consolidation of `detectTestFramework` / `findModuleForClass` between JavaRuntimeExecTool + CoverageTool
  4. Profile-driven decisions about which sites should upgrade to `ReadAction.nonBlocking` — Prong D-profile (parked)
- All deferred until Phase 5 or a post-release cleanup pass.

---

## Decisions for the user before execution

1. **Approve the 12-commit plan?** (Y/N + changes)
2. **D6 bundling: 3 commits of ~4 files each (current plan), or split further (≤2 files each, ~6 commits)?** I recommend **3 commits**. Subagent C7b timed out at ~15 files; ~4 files per D6 commit stays well under that ceiling.
3. **D8b caller-refactor scope:** include `AgentLoop.environmentDetailsProvider` type change, or carve it into a follow-up commit if blast radius worries you? I recommend **bundle** — splitting leaves an intermediate broken state where the lambda is suspend but the caller isn't.
4. **Execution mode:** subagent-driven sequential, Opus max effort, with reviewer per commit (per your prior directive). Confirm.
