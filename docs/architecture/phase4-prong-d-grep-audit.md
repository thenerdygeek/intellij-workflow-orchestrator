# Phase 4 Prong D-grep — ReadAction deprecation audit
Date: 2026-04-25
Auditor: opus max-effort subagent
Branch: `refactor/cleanup-perf-caching`

## Summary

- **Total `ReadAction.compute` / `ReadAction.run` / `runReadAction` lines in production sources: 53**
  - 8 are doc-comment / log-string mentions (not real calls)
  - **45 real production call sites**
- **Test sources: 30 mentions** in `agent/src/test/...` — all `mockkStatic(ReadAction::class)` interceptor shims. They do **not** invoke the deprecated platform API; they intercept it. Tests do **not** themselves require migration, BUT the shims become inert after production migration — tests must be migrated in the same commit.

### Bucket counts (45 real production sites)

| Bucket | Count | Notes |
|---|---|---|
| **A. SAFE-TO-LEAVE** (doc/comment, not real calls) | 8 | KDoc references only — fix when their source file's call site migrates. |
| **B. CONVERT to `readAction { }`** (off-EDT coroutine, short read) | 33 | Vast majority. Mechanical swap — mark surrounding helper `suspend`, swap `ReadAction.compute<T, E> { … }` for `readAction { … }`. |
| **C. CONVERT to `smartReadAction(project) { }`** | 8 | Sites that touch `JavaPsiFacade.findClass` / `PsiShortNamesCache` / `FileBasedIndex`. Indexes-required. |
| **D. CONVERT to `ReadAction.nonBlocking { }`** | 0 | No site does ReferencesSearch / unbounded tree walks — `readAction` is correct everywhere this might apply. |
| **E. CONVERT to `readActionBlocking { }`** (EDT short, can't move) | 1 | `CurrentWorkSection.showBranchPicker` — EDT after MouseClicked, popup must show synchronously. |
| **F. NEEDS-CALLER-REFACTOR** | 2 | `MentionContextBuilder.buildFileContext`, `EnvironmentDetailsBuilder.appendActiveEditor`. |
| Test-only sites (mocks) | 30 (separate) | Migrated alongside their production file. |

---

## Per-site classification

### A. SAFE-TO-LEAVE (doc-comment / log-string — not a real call)

| File | Line | Why no fix needed |
|---|---|---|
| `agent/tools/runtime/BuildSystemValidator.kt` | 90 | KDoc — bundle with code commit for that file |
| `agent/tools/project/ListLibrariesAction.kt` | 24 | KDoc reference |
| `agent/tools/project/ResolveFileAction.kt` | 22 | KDoc reference |
| `agent/tools/project/TopologyAction.kt` | 24 | KDoc reference |
| `agent/tools/runtime/RuntimeExecTool.kt` | 650 | Comment about other tools |
| `agent/tools/project/ListSdksAction.kt` | 17 | KDoc reference |
| `agent/tools/project/ModuleDetailAction.kt` | 32 | KDoc reference |
| `agent/tools/project/ListFacetsAction.kt` | 21 | KDoc reference |

When migrating the surrounding KDoc when the actual call below changes, update KDoc references in lock-step (e.g. "all model reads run inside [readAction]").

### B. CONVERT to `readAction { }` (off-EDT coroutine, short read — mechanical)

| File | Line | Caller context | Notes |
|---|---|---|---|
| `core/healthcheck/HealthCheckService.kt` | 35 | `classifyChanges()` called from `suspend runChecks()` | Mark suspend; mechanical swap. |
| `jira/ui/CurrentWorkSection.kt` | 143 | Inside `scope.launch { … }` | Mechanical. |
| `jira/ui/SprintDashboardPanel.kt` | 793, 821, 886 | Inside `scope.launch { … }` (3 sites) | Mechanical. |
| `bamboo/ui/PrBar.kt` | 285 | Inside `scope.launch { … }` | Mechanical. |
| `jira/service/BranchingService.kt` | 107, 121, 211, 226 | All inside suspend functions (4 sites) | Mechanical. |
| `pullrequest/service/PrDescriptionGenerator.kt` | 197, 213, 229 | Caller is `generateDescription` (suspend) | Mark helpers suspend; mechanical. |
| `pullrequest/action/CreatePrPrefetch.kt` | 121, 231, 236 | All inside suspend (3 sites) | Mechanical. |
| `agent/tools/framework/build/BuildModuleDependencyGraphAction.kt` | 27, 48, 89 | Tool action chain → suspend (3 sites) | Mark helpers suspend; mechanical. |
| `agent/tools/runtime/RuntimeConfigTool.kt` | 528 | suspend `executeModifyRunConfig` chain | Mechanical. |
| `agent/tools/project/SetLanguageLevelAction.kt` | 60 | suspend | Mechanical. |
| `agent/tools/project/SetModuleDependencyAction.kt` | 70, 77, 102 | suspend | Mechanical. |
| `agent/tools/project/RemoveModuleDependencyAction.kt` | 49, 71 | suspend | Mechanical. |
| `agent/tools/runtime/BuildSystemValidator.kt` | 91 | suspend chain | Mark `validateForTestRun` suspend; update KDoc line 90. |
| `agent/tools/project/ListLibrariesAction.kt` | 30 | Tool chain → suspend | Mark helper suspend; update KDoc. |
| `agent/tools/vcs/ChangelistShelveTool.kt` | 84 | suspend `execute` chain | Mark `listChangelists` suspend. |
| `agent/tools/project/SetModuleSdkAction.kt` | 44 | suspend | Mechanical. |
| `agent/tools/project/AddSourceRootAction.kt` | 75, 114, 120 | suspend (3 sites) | Mechanical. |
| `agent/tools/project/AddContentRootAction.kt` | 52, 97 | suspend | Mechanical. |
| `agent/tools/project/RemoveContentRootAction.kt` | 52, 82 | suspend | Mechanical. |
| `agent/tools/builtin/ProjectContextTool.kt` | 256 | Cline-port file; suspend chain | Mark helper suspend; verify Cline parity isn't broken (single shim, low risk). |
| `agent/tools/runtime/RuntimeExecTool.kt` | 652, 655 | suspend `executeRunConfig` | Mechanical. (Site 694 is bucket C.) |
| `agent/tools/project/ListSdksAction.kt` | 21 | suspend chain | Mechanical; update KDoc. |
| `agent/tools/project/TopologyAction.kt` | 37 | suspend chain | Mechanical; update KDoc. |
| `agent/tools/project/ModuleDetailAction.kt` | 46 | suspend chain | Mechanical; update KDoc. |
| `agent/tools/project/ListFacetsAction.kt` | 30 | suspend chain | Mechanical; update KDoc. |
| `agent/tools/runtime/CoverageTool.kt` | 1121 | suspend chain | `resolveToClassName` — index-free PSI file lookup. Mechanical. |
| `agent/tools/builtin/SyntaxValidator.kt` | 56 | suspend `EditFileTool.execute` chain | Mark helper suspend. |
| `agent/prompt/EnvironmentDetailsBuilder.kt` | 121 | `appendOpenTabs` — read `FileEditorManager.openFiles` | Make `build(...)` suspend (same as bucket-F site at L85). |

### C. CONVERT to `smartReadAction(project) { }` (off-EDT, indexes-required)

| File | Line | Caller context | Why smartReadAction |
|---|---|---|---|
| `agent/tools/runtime/RuntimeExecTool.kt` | 694 | suspend chain | `config.checkConfiguration()` triggers `FileBasedIndex.ensureUpToDate()` (Spring Boot path). Inline comment 690-692 confirms. |
| `agent/tools/runtime/JavaRuntimeExecTool.kt` | 905, 924 | suspend chain | `JavaPsiFacade.findClass` + `ModuleUtilCore.findModuleForPsiElement`. |
| `agent/tools/runtime/CoverageTool.kt` | 1089, 1106 | suspend chain | Same shape as JavaRuntimeExecTool. |
| `agent/tools/project/ResolveFileAction.kt` | 47 | suspend chain | `ProjectFileIndex` + `findModuleForFile`. Already gates on `DumbService.isDumb` at line 38; smartReadAction is the idiomatic 2026.1 form. |
| `agent/tools/debug/DebugBreakpointsTool.kt` | 365 | `executeMethodBreakpoint` (suspend) | `JavaPsiFacade.findClass(GlobalSearchScope.allScope)`. |
| `agent/tools/debug/DebugBreakpointsTool.kt` | 540 | `executeFieldWatchpoint` | Currently wrapped in `withContext(Dispatchers.IO) { ReadAction.compute { … } }` — collapse to single `smartReadAction(project) { … }`. |

**Helper duplication:** `detectTestFramework` and `findModuleForClass` are duplicated identically between `JavaRuntimeExecTool.kt` and `CoverageTool.kt`. Out-of-scope for Prong D — flag for Phase 5 consolidation into `agent/util/JvmTestFrameworkResolver`.

### D. CONVERT to `ReadAction.nonBlocking { }` — empty

No qualifying sites.

### E. CONVERT to `readActionBlocking { }` (EDT short critical work)

| File | Line | Why this bucket | Notes |
|---|---|---|---|
| `jira/ui/CurrentWorkSection.kt` | 183 | `showBranchPicker()` from `MouseAdapter.mouseClicked` (EDT) | `readActionBlocking` is the mechanical fix. **Better long-term:** dispatch via `scope.launch { val repo = readAction { … }; withContext(Dispatchers.EDT) { showPopup(repo) } }` — a small refactor, defer for now. |

### F. NEEDS-CALLER-REFACTOR

| File | Line | Why caller refactor needed |
|---|---|---|
| `agent/ui/MentionContextBuilder.kt` | 93 | `buildFileContext` is non-suspend; `buildContext` chain is non-suspend. Make both suspend; propagate up to `AgentController.executeTaskWithMentions:1157` (already in coroutine). Function-color change rippling through ~3 callers. |
| `agent/prompt/EnvironmentDetailsBuilder.kt` | 85 | `appendActiveEditor` reads EDT-affine APIs (`editor.caretModel`, `editor.selectionModel`) from a background coroutine. Works today only because the agent loop happens to invoke with EDT-stable state. Make `build(...)` suspend; change `AgentLoop.environmentDetailsProvider: (() -> String?)?` to `suspend (() -> String?)?`; thread `Dispatchers.EDT` for the editor read; everything else stays as plain `readAction`. **Largest blast radius commit — touches `EnvironmentDetailsBuilder.kt`, `AgentLoop.kt`, `AgentService.kt`.** |

### Test-only sites (must migrate alongside their production file)

30 lines across 15 test files in `agent/src/test/...`. All are `mockkStatic(ReadAction::class)` interceptor shims. Pattern:

```kotlin
mockkStatic(ReadAction::class)
every { ReadAction.compute<T, E>(capture(slot)) } answers { slot.captured.compute() }
```

After production swap to `readAction { }` (suspending builder), these shims become inert. Three migration patterns possible:
1. Replace `mockkStatic(ReadAction::class)` with `mockkStatic("com.intellij.openapi.application.CoroutinesKt")` — fragile; the suspending builder is awkward to mock.
2. Drop the static mock entirely and use `runTest { }` + `TestScope` — works for tests already using `runTest`.
3. Use the `kotlinx.coroutines.test.runTest` builder around the production call — `readAction { }` will run on the test scheduler, no shim needed.

**Pattern 3 is the cleanest and matches IntelliJ Platform 2026.1 test conventions.**

| Test file | Lines |
|---|---|
| `runtime/RuntimeExecRunConfigTest.kt` | 176, 181 |
| `project/AddSourceRootActionTest.kt` | 128, 172, 228 |
| `runtime/BuildSystemValidatorTest.kt` | 61, 65 |
| `project/RemoveContentRootActionTest.kt` | 110, 141, 183, 233, 286, 358 |
| `project/ListLibrariesActionTest.kt` | 74 |
| `project/AddContentRootActionTest.kt` | 77, 81 |
| `project/SetModuleDependencyActionTest.kt` | 148, 182, 223, 271 |
| `project/SetLanguageLevelActionTest.kt` | 119, 150, 187, 251, 314 |
| `project/ListFacetsActionTest.kt` | 80, 115 |
| `project/RemoveModuleDependencyActionTest.kt` | 107, 138, 175, 219, 270 |
| `project/SetModuleSdkActionTest.kt` | 95, 126, 160, 202, 260, 303 |
| `project/ListSdksActionTest.kt` | 25, 78 |
| `project/ModuleDetailActionTest.kt` | 91, 190 |
| `project/ResolveFileActionTest.kt` | 97, 100 |
| `project/TopologyActionTest.kt` | 91 |

---

## Notable findings

1. **`AgentTool.execute` is universally suspend** — 31 of 45 sites are mechanical inside `:agent`.
2. **Two latent EDT-correctness bugs surfaced** — `MentionContextBuilder.buildFileContext:93` (works by lock-coincidence) and `EnvironmentDetailsBuilder.appendActiveEditor:85` (reads EDT-affine APIs from background).
3. **Tests silently break if migrated separately from production.** 30 mock interceptors become inert after the swap. Plan must bundle test+prod per file.
4. **No bucket D sites.** No ReferencesSearch or unbounded tree walks — `readAction` is correct everywhere this might apply.
5. **`RuntimeExecTool.kt:690-692` already documents** the smart-mode requirement inline (Spring Boot Index dependency).
6. **Three duplicated PSI helpers** between `JavaRuntimeExecTool` and `CoverageTool` — consolidation candidate flagged for Phase 5.
7. **`PrDescriptionGenerator.kt`** wraps git-subprocess invocations in `ReadAction.compute` — defensive but unused lock. Could delete the wrap, not just migrate it. Treat as bucket B for safety.
8. **No Cline-port "leave-as-is" sites.** The Cline-protected pattern is the per-listener pollScope, not the ReadAction wrappers.

---

## Suggested commit ordering

Constraint: **tests + production bundled per file**. Within that, sequence by module + risk.

| Commit | Files | Bucket(s) | Risk |
|---|---|---|---|
| **D1** | `core/healthcheck/HealthCheckService.kt` | B | Very low |
| **D2** | `bamboo/ui/PrBar.kt` | B | Very low |
| **D3** | `:jira` (3 files: CurrentWorkSection, SprintDashboardPanel, BranchingService) | B + 1 E | Low |
| **D4** | `:pullrequest` (2 files: PrDescriptionGenerator, CreatePrPrefetch) | B | Low |
| **D5** | `:agent` Wave 1 — files with no test rewiring (BuildModuleDependencyGraphAction, RuntimeConfigTool, SyntaxValidator, ProjectContextTool, ChangelistShelveTool) | B | Low |
| **D6a–D6n** | `:agent` Wave 2 — 12-14 small commits, each test+prod bundled (per project-action file) | B | Medium per-commit (test rewiring), low individually |
| **D7** | `:agent` smartReadAction sites — RuntimeExecTool + CoverageTool + JavaRuntimeExecTool + DebugBreakpointsTool | C | Medium |
| **D8a** | `MentionContextBuilder.kt` + `AgentController` propagation | F | Medium-high |
| **D8b** | `EnvironmentDetailsBuilder.kt` + `AgentLoop` provider type change | F | High |

**Estimated commit count: 18–22.** If plan phase consolidates D6 into ~3 bundled commits (3-5 files each), total drops to ~10–12.

---

## Out-of-scope observations

1. **`PrDescriptionGenerator.kt` wraps git subprocess in ReadAction** — defensive but unused; could delete entirely.
2. **`HealthCheckService.classifyChanges` ProjectFileIndex calls** don't strictly need a read lock either.
3. **`BuildSystemValidator.validateForTestRun`** could benefit from `ProgressManager.checkCanceled()` calls inside its `iterateChildrenRecursively` traversal.
4. **`AgentLoop.environmentDetailsProvider` lambda type modernization** — make suspend, broader change beyond Prong D scope.
5. **`detectTestFramework` / `findModuleForClass` duplication** between JavaRuntimeExecTool and CoverageTool — Phase 5 follow-up.
6. **No `runReadAction { … }` Kotlin shortcut form** — every site uses fully-qualified `ReadAction.compute<T, E>`. Migration is therefore uniform.
