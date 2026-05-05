# VFS Staleness After Shell Mutation — Research & Fix Architecture

> **Date:** 2026-05-05  •  **Branch:** feature/context-compaction  •  **Status:** Research only (read-only, no source edits)
> **Audience:** plugin maintainers about to wire a post-`run_command` cache-coherency fix
> **Out of scope:** the `revert_file` / `git revert` UX surface; LLM prompt changes other than the small ToolResult hint described in Layer D.

---

## 1. Problem statement

The agent runs a `run_command` subprocess that mutates files **outside** IntelliJ's `WriteCommandAction`/Document API
(e.g. `git stash`, `git checkout <file>`, `git apply`, `git reset --hard`, `sed -i`, `mv`, `rm`, `npm install`,
`mvn clean`, `gradle build`). The IntelliJ Platform's Virtual File System (VFS) is **a persistent snapshot
of disk state** that is only synchronized with disk via *refresh*. After the subprocess exits, VFS still holds
the old `VirtualFile` snapshots, the document/PSI caches still hold the pre-mutation text, JPS still trusts its
incremental compile stamps, and indexes have not been told the source set changed. The next test-runner tool
(`java_runtime_exec.run_tests`, `python_runtime_exec.run_tests`, `coverage.run_with_coverage`,
`runtime_exec.run_config`) calls `ProjectTaskManager.build(module)` and launches against this stale snapshot —
producing test results that don't reflect the disk truth, which the LLM then mis-diagnoses, looping on a phantom
bug. Today our tools have **zero VFS-refresh coupling around `run_command`**: a grep across `agent/src/main` and
`core/src/main` for `VirtualFileManager.{sync,async}Refresh` returns nothing.

---

## 2. VFS Refresh API comparison

### 2.1 Authoritative threading rules (JetBrains docs)

> "Both synchronous and asynchronous refreshes can be initiated from any thread. **If a refresh is initiated from
> a background thread, the calling thread must not hold a read lock**, because otherwise, a deadlock would occur.
> **VFS events are always fired in the event dispatch thread and in a write action.** … In nearly all cases,
> using asynchronous refreshes is strongly preferred."
> — Virtual File System (`plugins.jetbrains.com/docs/intellij/virtual-file-system.html`)

> `refreshAndFindFileByNioPath`: "Useful when the file was created externally. **If invoked outside Swing event
> dispatch thread, must not occur inside a read action**."
> — `VirtualFileManager.java` JavaDoc

> "The method refreshes the **whole VFS**, which may take time and produce unrelated events. Use
> `VirtualFile#refresh` instead." — JavaDoc on both `VirtualFileManager.syncRefresh()` and `asyncRefresh()`,
> both flagged `@ApiStatus.Obsolete`.

> Since IntelliJ Platform 2025.3: **VFS refresh now runs as a background write action** (no longer EDT-only).
> `Dispatchers.UI` is added; `backgroundWriteAction()` / `readAndBackgroundWriteAction()` are stabilized as
> suspending APIs. Plugins targeting 2025.1+ generally need no code change because the platform handles the
> internal scheduling.

### 2.2 Per-API matrix (use-case: post-`run_command`, on `Dispatchers.IO`, no read action held)

| API | Scope | Sync? | OK from `Dispatchers.IO`? | Read-action req. | PSI/index invalidation? | Cost on 50k-file repo | When to use |
|---|---|---|---|---|---|---|---|
| `VirtualFileManager.getInstance().syncRefresh()` | **Whole VFS** | sync | Yes, **iff** no read action held; otherwise deadlock. Doc-flagged `@Obsolete` ("use `VirtualFile#refresh`"). | Must NOT hold one. | Yes — fires `VFileEvent`s on EDT in a write action; that triggers PSI/index invalidation downstream. | Heavy: walks every watched root. Seconds on big repo. **Avoid as default.** | Last-resort hammer when paths are unknown AND scope is unknown. |
| `VirtualFileManager.getInstance().asyncRefresh(callback)` | Whole VFS | async | Yes (callback runs on EDT, write-action). | None. | Yes (downstream of `VFileEvent`). | Same walk cost but doesn't block caller. | Use when you must refresh everything but don't need to block. **Doc-flagged `@Obsolete` — prefer `VirtualFile#refresh`.** |
| `LocalFileSystem.getInstance().refresh(asynchronous: Boolean)` | All `LocalFileSystem` roots | configurable | Yes, when `asynchronous = false` and no read lock. | None held. | Yes. | Similar to `syncRefresh` (a tad lighter — only the local FS, not jars/http). | Marginal benefit over `VirtualFileManager.syncRefresh`. |
| `LocalFileSystem.getInstance().refreshAndFindFileByPath(path)` / `refreshAndFindFileByNioFile(nio)` | **Single path** | sync (partial refresh) | Yes — JavaDoc explicitly: "if invoked outside EDT, must NOT occur inside a read action." | Must NOT hold one. | Yes for that path. | O(1)–O(few). Cheap. | Use when you know the *exact* file. |
| `LocalFileSystem.getInstance().refreshFiles(files, async, recursive, postRunnable)` | **Set of `VirtualFile`s** | configurable | Yes (when `async=true` always; when `async=false` no read lock). | Same rule. | Yes for those files. | O(N). Bounded. | Use when you have a known set of `VirtualFile` objects (a few dozen paths from `git diff --name-only`). |
| `VfsUtil.markDirtyAndRefresh(async, recursive, reloadChildren, vararg files)` | Set of files; **forces** "dirty" so refresh re-stats children even if parent timestamp unchanged | configurable | Yes; same rule. | Same rule. | Yes. | O(N) but defeats the timestamp short-circuit, so heavier per-file than plain refresh. | **The right tool when source mtimes were rolled back** (git stash / git checkout / git reset). |
| `RefreshQueue.getInstance().createSession(async, recursive, postRunnable).addAllFiles(files); session.launch()` | Set of files | configurable | Yes; same rule. | Same rule. | Yes. | O(N). | Low-level: only when you need post-refresh callbacks coalesced or want to control session granularity. Internally what `LocalFileSystem.refreshFiles` builds for you. |
| `WriteAction.runAndWait { LocalFileSystem.getInstance().refresh(false) }` from a coroutine | Whole local FS | sync | **Antipattern post-2025.3.** Use `backgroundWriteAction { … }` instead. | Acquires write lock. | Yes. | Heavy + EDT round-trip. | Don't. |

### 2.3 Critical answers to the questions in the brief

**Can sync refresh be called from a non-EDT IO coroutine without a write action?**
Yes — and that is precisely the supported path. The doc says "from any thread, must not hold a read lock."
You do not need to wrap it in a write action; the platform fires the resulting VFS events on EDT inside an
internal write action. The only deadlock is "background thread holding a read lock calls sync refresh."
Our `run_command` runs on `Dispatchers.IO` and holds no read lock at process exit, so a direct call is safe.

**What's the right pattern when we know the *exact* set of paths the command touched (e.g., from
`git diff --name-only HEAD`) vs. when we don't?**

- **Known paths:** collect `VirtualFile` (or `Path`) objects, call
  `VfsUtil.markDirtyAndRefresh(async = true, recursive = false, reloadChildren = false, *files)`.
  `markDirtyAndRefresh` is the variant that survives "git stash restored an older mtime" because it forces
  dirtiness regardless of the timestamp — a subtle but critical detail (a vanilla `refresh()` that compares
  cached vs disk mtime can no-op when mtimes were rolled back).
- **Unknown paths but known *roots* (project basePath, working_dir):** `LocalFileSystem.refreshFiles(rootVfs,
  async = true, recursive = true, postRunnable = null)` scoped to the project root. Excluded dirs (see §6) are
  honored by VFS roots automatically when they're in `Module.excludeRoots`.
- **Unknown anything (worst case fallback):** `VirtualFileManager.getInstance().asyncRefresh(null)` (or the
  newer `VirtualFile#refresh` chain on the project root). Whole-VFS hammer; cost is the dominant concern.

**Does `ProjectTaskManager.build(module)` internally trigger a VFS refresh, or does it trust whatever VFS state
it gets?**

It trusts the VFS state. There is **no documented or observed VFS-refresh side effect** of `ProjectTaskManager.build`.
The contract reads:

> "Build modules and all modules these modules depend on recursively." (`ProjectTaskManager.build(Module...)`)
> "Build all modules with **modified files** and all modules with files that depend on them." (`buildAllModules`)

The "modified files" set is computed against IntelliJ's source-snapshot view, which is the VFS. If VFS is
stale, the modification set is wrong, and JPS happily reuses its incremental cache against the wrong inputs.
**This is the root cause** of the symptom in the brief.

---

## 3. JPS / compiler-cache findings

### 3.1 Does `ProjectTaskManager.build(module)` honor an external VFS refresh and recompile when source mtime is newer than the .class output?

JPS keys incremental decisions on its own per-source stamps stored in `BuildManager`'s state directory
(`~/.idea/system/compile-server/<project>/`). It compares those stamps to the *current snapshot*, which JPS
re-reads at the start of a build. If the VFS has been refreshed prior to the build kickoff, JPS sees
the newly-current mtimes and rebuilds anything where the new mtime ≠ the recorded stamp, **even if the new
mtime is older than the recorded stamp** (mtime equality, not directionality, is what the cache keys on for
incremental decisions; an older mtime still differs and triggers a recompile).

But: when VFS is **stale**, JPS sees no change → no recompile. That is the failure mode the brief describes.

### 3.2 Force-clean compile vs incremental compile from background code

| Goal | API | Notes |
|---|---|---|
| Incremental rebuild of one module + dependents | `ProjectTaskManager.getInstance(project).build(module).onSuccess { … }` | Returns a `Promise<Result>`. Used today at `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/JavaRuntimeExecTool.kt:478`. |
| Incremental project rebuild (all modules with modified files) | `ProjectTaskManager.getInstance(project).buildAllModules()` | |
| **Force-clean rebuild** (discard JPS incremental state for the target) | `ProjectTaskManager.getInstance(project).rebuild(module)` / `rebuildAllModules()` | Equivalent to user-hit "Build > Rebuild Project". |
| Lower-level synchronous build | `CompilerManager.getInstance(project).make(scope, callback)` / `compile(scope, callback)` / `rebuild(callback)` | Used today at `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/JavaRuntimeExecTool.kt:1665`. |
| Hard-reset JPS state | `BuildManager.getInstance().clearState(project)` | Drops in-memory cache for that project; next build rereads stamps from disk. Useful when external tools (npm install, gradle clean) destroyed `out/` / `target/` underneath JPS without a build invocation. |
| Drop ALL JPS state | `BuildManager.getInstance().clearState()` | Whole-IDE; never appropriate from a tool invocation. |
| Tell JPS that "the source tree was mutated externally" | No single documented call. The closest is `clearState(project)` + a project-root `markDirtyAndRefresh`. JPS picks up changes on the next build because the refresh fires `VFileContentChangeEvent`s, which JPS listens to. | This is the recommended pattern for "shell hose mutated stuff under JPS". |

### 3.3 Recommendation for the post-mutator path

When a `run_command` is detected as a mutator (see §5 Layer B), the safest sequence before letting a test runner proceed is:

1. `markDirtyAndRefresh(async = false, recursive = true, reloadChildren = true, projectRootVfs)` — force VFS to re-stat.
2. *If* the command was a build-tool clean (`mvn clean`, `gradle clean`, `npm ci`, `gradle build --rerun-tasks`):
   `BuildManager.getInstance().clearState(project)` — JPS in-memory cache dropped.
3. Let the test runner call `ProjectTaskManager.build(module)` as it already does. JPS now sees current mtimes
   and recompiles correctly.

Forcing `rebuild(module)` is too aggressive as a default — it can take minutes on a 50k-file repo where the
mutation only touched two files. Reserve it for an explicit user opt-in or for `git reset --hard` on a wide
range (heuristic: > 20 files reported by `git diff --name-only`).

---

## 4. Indexing / dumb mode

### 4.1 What happens after a wide refresh

`git checkout main` swapping thousands of files fires `VFileContentChangeEvent` for each, which causes
`FileBasedIndex` to reindex. While reindexing is in progress, `DumbService.isDumb(project) == true`. Most of
our tools that touch indexes already gate on this — `RuntimeExecTool` lines 642–646 returns
`DUMB_MODE: IDE is currently indexing. Please wait for indexing to complete before launching a run
configuration.` `ListQuickFixesTool`, `RunInspectionsTool`, `OptimizeImportsTool`, `FormatCodeTool`,
`SemanticDiagnosticsTool`, `ProblemViewTool`, `RefactorRenameTool`, `ResolveFileAction`,
`SpringJpaEntitiesAction`, `ModuleDetailAction` all already do the same.

`JavaRuntimeExecTool.run_tests` does **not** explicitly gate on `isDumb` — it relies on
`ProjectTaskManager.build(module)` to fail cleanly. That is *probably* fine because build-during-indexing
queues anyway, but it doesn't surface the indexing reason cleanly to the LLM.

### 4.2 Right way to wait for indexing from a coroutine

`smartReadAction(project) { … }` does what we want when `Dispatchers.IO` code needs to read indexes:
it suspends the coroutine until smart mode (no active dumb-mode), runs the lambda inside a read action,
and is auto-cancelled if a write action arrives (WARA — write-allowing). It is the modern replacement for
`DumbService.runWhenSmart { … }` callback wiring.

For "I just need to *wait* until indexing is done with no read action," the pattern in the platform is
`smartReadAction(project) { /* no-op */ }` — abuse but works. A cleaner alternative for our domain is
`DumbService.getInstance(project).waitForSmartMode()` from the `DumbService` API, which blocks the calling
thread until smart mode (NOT a coroutine-suspending API; wrap in `withContext(Dispatchers.IO) { … }`).

### 4.3 Should the agent gate test-runner tools behind a `DumbService.isDumb` check + wait?

Yes, for two reasons:

1. **Symmetry with `runtime_exec.run_config`** — already does it; `java_runtime_exec.run_tests` and
   `coverage.run_with_coverage` should match.
2. **Determinism** — when our Layer A always-on refresh kicks off a wide refresh (e.g. after `git checkout
   main` swapped 5,000 files), reindexing follows. Without a wait, the test runner sees `isDumb=true` mid-
   `ProjectTaskManager.build(module)`, which yields cryptic Maven/Gradle "module not configured" errors
   (the BuildSystemValidator already handles many of these but the LLM-facing message is poor).

---

## 5. Existing patterns in the repo

### 5.1 What's already wired (file:line)

| Pattern | Where | Notes |
|---|---|---|
| Single-file refresh after our own write | `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/EditFileTool.kt:269` (`vFile.refresh(false, false)`) | Async, non-recursive, on a `VirtualFile` we just wrote. |
| Single-file refresh fallback after `java.io.File.writeText` | `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/EditFileTool.kt:283` and `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/CreateFileTool.kt:137` | `LocalFileSystem.getInstance().refreshAndFindFileByPath(path)` |
| Single-file refresh before reading | `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/EditFileTool.kt:190` | `?: LocalFileSystem.getInstance().refreshAndFindFileByPath(resolvedPath)` |
| Single-file refresh in IDE tools | `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/OptimizeImportsTool.kt:48`, `…/ide/FormatCodeTool.kt:48` | Same pattern. |
| Project-level external-system refresh | `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/project/RefreshExternalProjectAction.kt` | Maven/Gradle re-import; `MavenProjectsManager.forceUpdateAllProjectsOrFindAllAvailablePomFiles()` reflectively + `ExternalSystemUtil.refreshProject(...)` for Gradle. **This is the right tool when `pom.xml`/`build.gradle` itself was edited or `npm install` rewrote `package.json`.** Not currently wired into `run_command` flow. |
| `DumbService.isDumb` guards | 12+ call sites incl. `RuntimeExecTool.kt:642`, all `tools/ide/*` | Consistent error string; LLM-actionable. |
| `smartReadAction` pattern from coroutines | `RuntimeExecTool.kt:695`, `JavaRuntimeExecTool.kt:925`, `CoverageTool.kt:1090`, `MentionSearchProvider.kt:353`, `ResolveFileAction.kt:49` | All inside `Dispatchers.IO` workers. The `ReadActionTestShim` (`agent/src/test/kotlin/.../testutil/ReadActionTestShim.kt`) is required in tests. |
| `ProjectTaskManager.build(module)` for tests | `JavaRuntimeExecTool.kt:478`, `JavaRuntimeExecTool.kt:1530` (rerun_failed_tests) | NO pre-build VFS refresh. |
| `CompilerManager.make(scope, callback)` for `compile_module` | `JavaRuntimeExecTool.kt:1691` | NO pre-compile VFS refresh. |
| `BuildSystemValidator` pre-flight | `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/BuildSystemValidator.kt` | Already runs read-action-bound checks. Good place to insert a pre-build VFS-coherency assertion. |

### 5.2 What's NOT wired

- **Zero** `VirtualFileManager.{sync,async}Refresh` call sites in `agent/src/main` or `core/src/main`.
- **Zero** `markDirtyAndRefresh` call sites.
- **Zero** post-`run_command` refresh hooks. The `RunCommandTool` (`builtin/RunCommandTool.kt`) returns
  immediately after `process.exitValue()` and `OutputCollector.processOutputTailBiased` (line 595);
  no VFS hook in sight. `AgentLoop` (`loop/AgentLoop.kt:1603–1653`) has the post-tool
  hook point, with a `WRITE_TOOLS` set including `run_command` and an `onWriteCheckpoint` callback
  (line 1849) — but `onWriteCheckpoint` is wired to *checkpoint persistence* (`AgentService.kt:1870`),
  not to VFS refresh.
- **Zero** integration between `BuildSystemValidator` and a "VFS is fresh" precondition.

So the surface for a fix is clean: there is no legacy refresh code to consolidate, and the post-tool hook
already exists.

### 5.3 Where the new helper belongs

Based on the layering rules in `CLAUDE.md` (`:agent` depends only on `:core`; new platform-touching
utilities go into `core/`), the new helper should live at:

`core/src/main/kotlin/com/workflow/orchestrator/core/vfs/PostMutationRefresh.kt`

Public surface:

```kotlin
package com.workflow.orchestrator.core.vfs

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

/**
 * Cache-coherency helper for "external mutator just exited; bring IntelliJ in sync with disk".
 *
 * Usage from a Dispatchers.IO coroutine (no read action held):
 *   PostMutationRefresh.refresh(project, scope = Scope.WorkingDir(workDir))
 *   PostMutationRefresh.refresh(project, scope = Scope.KnownPaths(listOfPaths))
 *   PostMutationRefresh.refresh(project, scope = Scope.WholeProject)
 *
 * Always non-blocking when async = true (the default). When async = false, must NOT be called
 * while holding a read lock — that is a documented platform deadlock.
 */
object PostMutationRefresh {
    sealed interface Scope {
        data object WholeProject : Scope
        data class WorkingDir(val dir: Path) : Scope
        data class KnownPaths(val paths: List<Path>) : Scope
    }

    suspend fun refresh(project: Project, scope: Scope, async: Boolean = true)

    /** Invoke after a build-tool clean (mvn clean / gradle clean / npm ci) to drop JPS state. */
    fun clearJpsCache(project: Project)
}
```

---

## 6. Recommended fix architecture

### Layer A — post-command, cheap, always-on

**Purpose:** never let a `run_command` exit return to the LLM with VFS more than ~50 ms behind disk.

**API call site:** in `RunCommandTool.execute()` immediately before `buildExitResult` is returned to the
caller (`builtin/RunCommandTool.kt` around line 381, the `if (!process.isAlive)` block).

```kotlin
// Inside RunCommandTool.execute(), after process exit and before buildExitResult():
//   - we know the absolute working_dir (resolved earlier, line 225–229)
//   - we are on Dispatchers.IO (the coroutine that executes the tool)
//   - we hold no read action

PostMutationRefresh.refresh(
    project = project,
    scope   = PostMutationRefresh.Scope.WorkingDir(java.nio.file.Path.of(workingDir)),
    async   = true,
)
```

**Threading context:** `Dispatchers.IO` worker. No wrapping needed.

**Internal implementation:**

```kotlin
suspend fun refresh(project: Project, scope: Scope, async: Boolean = true) {
    val files = when (scope) {
        Scope.WholeProject -> listOfNotNull(project.guessProjectDir())
        is Scope.WorkingDir -> listOfNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(scope.dir.toFile()))
        is Scope.KnownPaths -> scope.paths.mapNotNull { LocalFileSystem.getInstance().findFileByNioFile(it.toFile()) }
    }
    if (files.isEmpty()) return
    // markDirtyAndRefresh forces a re-stat even when mtimes were rolled back (git stash / git checkout).
    com.intellij.openapi.vfs.VfsUtil.markDirtyAndRefresh(
        /* async = */ async,
        /* recursive = */ scope !is Scope.KnownPaths,
        /* reloadChildren = */ true,
        *files.toTypedArray(),
    )
}
```

**Cost on 50k-file repo:** scope is the *working_dir*, not the whole project, so for a typical
`run_command` that ran in `src/main/java/foo` the recursive walk hits ~hundreds of files, not 50k.
Async path returns immediately; the EDT-side refresh runs ~10–200 ms later for that subtree.

**Failure mode if we get it wrong:**
- *Sync from a thread that holds a read lock* → deadlock. We are on `Dispatchers.IO` with no read action;
  this is fine. CI guard: existing test util `ReadActionTestShim` plus a new unit test that verifies the
  call site is called *outside* any `readAction { }` block.
- *Working-dir is outside any project root* → `LocalFileSystem.refreshAndFindFileByNioFile` returns null
  and we fall through to a no-op. Harmless.

### Layer B — post-command, mutator-aware, scoped

**Purpose:** when we can detect the command was a heavy mutator (git, package manager, build clean), broaden
the refresh scope and (for build cleans) drop JPS state.

**Detection rules** (extend `ShellResolver.isLikelyBuildCommand` or, cleaner, add a sibling
`CommandMutationClassifier` in `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/process/`):

| Pattern | Class | Action |
|---|---|---|
| `git stash`, `git stash pop`, `git checkout <ref>`, `git checkout <file>`, `git apply`, `git reset --hard`, `git pull`, `git merge`, `git rebase`, `git revert`, `git clean` | `GitMutator` | Whole-project refresh (`Scope.WholeProject`); the touched paths are unknown without parsing diff output. |
| `git checkout <branch>` followed in shell by another command via `&&` / `;` | `GitMutator` | Same. |
| `sed -i …`, `mv …`, `rm …`, `cp …`, `find … -delete`, `chmod` | `FsMutator` | Working-dir refresh (recursive). Same as Layer A scope. |
| `npm install`, `npm ci`, `pnpm install`, `yarn install`, `yarn`, `bun install` | `PackageInstall` | Working-dir refresh + invoke `RefreshExternalProjectAction.executeRefreshExternalProject` for `package.json` if present. (No JPS state to clear; `node_modules` is excluded by default in IntelliJ web modules.) |
| `mvn clean`, `mvn clean install`, `gradle clean`, `gradlew clean`, `./gradlew clean*`, `gradle build --rerun-tasks`, `cargo clean` | `BuildClean` | Working-dir refresh **plus** `BuildManager.getInstance().clearState(project)`. |
| `mvn …` / `mvn package`, `gradle …`, `gradle build` (without `clean`) | `BuildIncremental` | Working-dir refresh only. |
| `cargo build`, `make`, `make install`, `bazel build` | `BuildIncremental` | Working-dir refresh only. |
| Anything else | `Generic` | Layer A only (handled by always-on). |

**API call site:** at the same return point in `RunCommandTool.execute()`, *replacing* the unconditional Layer A
call:

```kotlin
val mutation = CommandMutationClassifier.classify(command)
val scope = when (mutation) {
    is GitMutator -> PostMutationRefresh.Scope.WholeProject
    is FsMutator, BuildIncremental, Generic ->
        PostMutationRefresh.Scope.WorkingDir(Path.of(workingDir))
    is PackageInstall ->
        PostMutationRefresh.Scope.WorkingDir(Path.of(workingDir))
    is BuildClean ->
        PostMutationRefresh.Scope.WorkingDir(Path.of(workingDir))
}
PostMutationRefresh.refresh(project, scope, async = true)
if (mutation is BuildClean) PostMutationRefresh.clearJpsCache(project)
if (mutation is PackageInstall) {
    // Only when the agent module classpath includes the existing helper:
    com.workflow.orchestrator.agent.tools.project.executeRefreshExternalProject(
        params = buildJsonObject { put("mode", "reload") },
        project = project,
        tool = this,  // approval gate is internal to the helper
    )
}
```

**Optional scoped-by-diff variant:** for `git`, when we have an in-process Bitbucket/git client we can
optionally run `git diff --name-only HEAD@{1} HEAD` (or `HEAD~1 HEAD` for `git pull`) to get exact paths
and use `Scope.KnownPaths(...)` instead of `WholeProject`. This is a follow-up; the unconditional whole-
project refresh after a git mutator is usually fast enough (recursive refresh short-circuits on unchanged
mtimes for the bulk of the tree).

**Threading context:** `Dispatchers.IO`; no wrapping.

**Cost on 50k-file repo:**
- `GitMutator` → recursive whole-project refresh. ~200 ms–2 s depending on how many files actually changed
  on disk (refresh skips unchanged stamps, except the `markDirtyAndRefresh` flag forces a re-stat which
  is a `getattr` syscall per file ≈ ~5 µs each → ~250 ms for 50k files cold-cache, ~50 ms hot).
- `BuildClean` → working-dir refresh + `clearState` is ~ms.
- `PackageInstall` → working-dir refresh + ExternalSystemUtil.refreshProject runs in IDE BG; the agent
  call returns quickly.

**Failure mode:** false-classification of a non-mutator as a mutator wastes a refresh; cost is bounded
by the Layer A scope. A false-negative (genuine mutator misclassified as Generic) leaves us at Layer A
which is still a working-dir refresh — usually enough for `sed -i ./somefile`.

### Layer C — pre-launch barrier in test runners

**Purpose:** even with Layers A+B in place, a parallel write-action somewhere else in the IDE could put us
in dumb mode just as the runner tries to launch. Make the test runners explicitly wait for smart mode.

**API call sites:**

1. `JavaRuntimeExecTool.executeWithNativeRunner` — immediately before
   `ProjectTaskManager.getInstance(project).build(testModule)` at line 478.
2. `JavaRuntimeExecTool.executeRerunFailedTests` — same, before line 1530.
3. `CoverageTool.executeRunWithCoverage` — before the implicit before-run-build path (line 287
   `invokeLater { … }`).
4. `RuntimeExecTool.executeRunConfig` — already gates on `isDumb` (line 642); upgrade to wait, not error.

```kotlin
// At top of each runner, just after argument parsing and just before build:
if (DumbService.isDumb(project)) {
    // Was a recent run_command refresh that's still being indexed?
    // Wait up to N seconds for smart mode rather than fail with DUMB_MODE.
    val waitedSec = withTimeoutOrNull(60_000L) {
        smartReadAction(project) { /* no-op: lambda runs only in smart mode */ }
        true
    }
    if (waitedSec == null) {
        return ToolResult(
            content = "DUMB_MODE: indexing did not complete within 60s. " +
                "A recent file mutation triggered reindexing. Retry shortly.",
            summary  = "DUMB_MODE: timeout waiting for indexing",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true,
        )
    }
}
```

**Threading context:** `Dispatchers.IO`. `smartReadAction` is the canonical suspend-and-wait primitive
from coroutines.

**Cost:** dominated by the actual indexing; `smartReadAction` itself is overhead-free.

**Failure mode:** if indexing genuinely never finishes (corrupt index), we'd hit the 60 s timeout and
surface a clean `DUMB_MODE: timeout waiting for indexing`. The user can run `File > Invalidate Caches`.

**Force-clean rebuild after detecting a mutator?** Plan to keep this **opt-in** (a future
`force_rebuild=true` parameter on `run_tests` / `run_with_coverage`). Auto-rebuilding after every git
checkout is too slow on a large repo; `BuildClean` already triggers `clearState(project)` which is enough
for the common case (`mvn clean` followed by `run_tests`). For `git checkout` + `run_tests`, JPS will
already recompile what changed because the VFS refresh updated mtimes. The runner does NOT need to know
about the recent mutator history; the cache-coherency is in place by the time it runs.

### Layer D — LLM-facing hint

**Purpose:** the LLM still has prior `read_file` outputs in its context that may have been invalidated by
a `git stash` / `git checkout`. We can't rewrite history; we can warn.

**API call site:** in `RunCommandTool.buildExitResult` (`builtin/RunCommandTool.kt:587`) — the function
that builds the `ToolResult.content` string. When `CommandMutationClassifier.classify(command)` is
`GitMutator` or `BuildClean`, prepend:

```text
[VFS NOTE] This command may have changed files on disk. Earlier read_file outputs in this conversation
may now be stale. Re-read any file you intend to edit before applying changes.
```

For `Scope.KnownPaths` cases (when we used `git diff --name-only`), include the actual list:

```text
[VFS NOTE] This command modified the following files on disk:
  - src/main/kotlin/Foo.kt
  - src/test/kotlin/FooTest.kt
Earlier read_file outputs for these paths in this conversation are stale. Re-read before editing.
```

**Threading context:** plain string concatenation in the existing tool result builder. No threading
concerns.

**Cost:** ~300–800 token-budget bytes per mutator invocation. Low.

**Failure mode:** none. The hint is informational; the actual cache-coherency is delivered by Layers A–C.

### 6.5 Layered summary

| Layer | Trigger | Scope | Cost (50k-repo) | Owner |
|---|---|---|---|---|
| A — always-on | every `run_command` exit | working_dir, recursive, async | <50 ms | `RunCommandTool` + new `core/vfs/PostMutationRefresh` |
| B — mutator-aware | classifier match (git / build clean / package install / sed / mv / rm) | whole project (git) / working_dir (others) + `clearState` for build cleans + `RefreshExternalProjectAction` for package installs | 50–500 ms typical | `RunCommandTool` + `CommandMutationClassifier` + `core/vfs/PostMutationRefresh` |
| C — pre-launch barrier | all test/runtime tools | wait-for-smart-mode | 0 ms when smart, indefinite up to 60 s when dumb | `JavaRuntimeExecTool`, `CoverageTool`, `PythonRuntimeExecTool`, `RuntimeExecTool` |
| D — LLM hint | mutator classifier match | string append | ~500 chars | `RunCommandTool.buildExitResult` |

---

## 7. Edge cases & gotchas

1. **Indexing in progress (`isDumb`)** — Layer C handles this in the runners. For Layer A/B, our refresh
   is fire-and-forget async, so it's safe to call mid-indexing — the platform queues VFS events and folds
   them into the existing indexing pass. Worst case: the very first indexing pass after a wide refresh is a
   bit longer, but no second pass is required.

2. **`node_modules` / `target` / `build` / `out` / `.gradle` / `.idea`** — These are excluded roots by default
   in Maven/Gradle/IDEA modules. `Scope.WorkingDir` recursive refresh respects exclusions through VFS
   automatically as long as the working_dir is *inside* a content root. **However**, a `run_command` invoked
   with `working_dir = projectRoot` will recurse into `target/` and `node_modules/` at the FS level — we should
   add an explicit skip filter in `PostMutationRefresh.refresh` that elides any directory matching:

   ```
   ^(node_modules|target|build|out|dist|\.gradle|\.idea|\.git|__pycache__|\.venv|venv|\.tox|\.mypy_cache|\.pytest_cache)$
   ```

   This is the same exclusion list `PythonFileScanner.shouldScanDir()` already uses (`agent/src/main/kotlin/.../tools/framework/PythonFileScanner.kt`); the pattern can be lifted/shared.

3. **`git stash` reverted files; prior `read_file` outputs in conversation are now lying** — Layer D handles
   the LLM hint. Beyond that, no automatic remedy is sound: silently re-injecting a "current contents of file
   X" message after every mutator would balloon context. The hint is the right balance — let the LLM decide
   to `read_file` again when it cares.

4. **Symlinks** — VFS *follows* symlinks by default. A `mv` of a symlink target outside the project will
   leave a dangling symlink that `markDirtyAndRefresh` correctly invalidates. A `mv` of the symlink itself
   needs `recursive = true` on the parent, which Layer A's working-dir scope provides.

5. **Case-insensitive macOS filesystem** — `LocalFileSystem` already handles case folding correctly on
   APFS / HFS+. No special handling needed. Watch out: `git checkout` on a Mac can produce a phantom
   `MyClass.java` ↔ `myclass.java` ambiguity that VFS resolves to one or the other. The refresh sees both
   stamps differ and re-stats — same outcome as Linux.

6. **Refresh inside an active write action elsewhere** — extremely unlikely from the agent (we don't hold
   write actions while waiting for subprocess), but if a parallel UI action holds a write lock when our
   `markDirtyAndRefresh(async=true, …)` enqueues, the platform serializes the two. No deadlock, no surprise.

7. **`run_command` running the agent's *own* test/build commands** (e.g. user types `mvn -pl :core test` as a
   `run_command` for some reason) — Layer B's `BuildIncremental` triggers a working-dir refresh, fine. The
   results that come back through stdout still reflect the JPS-side run that the *subprocess* did, which has
   nothing to do with IDE JPS; that's a separate cache. No issue.

8. **macOS file-system events lag** — kqueue events from the OS can lag 50–500 ms behind a write. The
   `markDirtyAndRefresh` call is **not** dependent on FS events — it explicitly forces a re-stat — so this
   is unaffected.

9. **JCEF / Sourcegraph webview thread** — none of these refresh paths touch JCEF; the live UI is updated
   only after the test runner returns, and that flow is unchanged.

10. **Background `run_command` (`background=true` path, `RunCommandTool.launchBackgroundAndReturn` line 476)** —
    background processes mutate continuously; we cannot refresh on every output line. The right scope is
    "refresh when the bg process is read or attached to" plus "refresh on bg-completion notification."
    Out of scope for this fix; flag as a follow-up TODO in `BackgroundProcessTool`.

---

## 8. Open questions

1. **Should Layer A's whole-project refresh cost be measured before shipping?** The 50–500 ms estimates here
   are derived from JetBrains docs and the syscall arithmetic; we have no in-repo benchmark. Recommend a
   one-shot manual benchmark on a known large repo (`intellij-community` itself is convenient) before
   defaulting Layer B's `GitMutator` scope to `WholeProject`. If it's >1 s we may want to require an explicit
   diff parse.

2. **Does `BuildManager.getInstance().clearState(project)` block?** The unofficial API doc lists the method
   but doesn't document threading. The class implements `Disposable`. If it acquires the build lock, calling
   it from `Dispatchers.IO` could in principle deadlock against an in-progress build. Worth a quick test
   harness — wrap in a `withTimeoutOrNull(5_000)` defensively until verified.

3. **Is there a public API for "tell JPS exact file paths X, Y, Z were mutated externally"?** A grep over
   `intellij-community` for `BuildManager` + `notifyFilesChanged` returned no public hit. Pursuing this would
   let us avoid the JPS-state clear for `mvn clean` cases — but the clear is cheap, so this is low priority.

4. **Does `smartReadAction` actually wait for dumb mode?** The KDoc on `smartReadAction` is sparse. Empirical
   answer (from the existing `ResolveFileAction.kt:49` pattern, which asserts indexes are required and works
   under dumb mode): yes, it suspends. A unit test that puts the project into dumb mode artificially and
   asserts `smartReadAction` suspends would harden the Layer C contract.

5. **PyCharm's `python_runtime_exec.run_tests` (no `ProjectTaskManager.build` phase)** — the brief lists it
   as a victim but pytest on stale VFS still imports stale `.py` files from disk through Python's own import
   machinery. The agent's tool doesn't need a JPS-style build, but Layer A's working-dir refresh is still
   useful so that follow-up `read_file` / `diagnostics` see current state. Recommend including it in Layer C's
   pre-launch barrier so a recent `git checkout` doesn't leave PyCharm in dumb mode when pytest collection
   tries to resolve fixtures via PSI.

6. **Should we share the Cline-port helper for `git diff --name-only HEAD@{1} HEAD`?** That parser is
   conspicuously absent from the codebase; building it is a few dozen lines of `Process` + parse, but the
   existing `BitbucketBranchClient` in `:core` may already have reusable plumbing worth extending.

7. **Does our `RefreshExternalProjectAction` work cleanly when invoked from within a `run_command` post-hook
   on a concurrent background thread?** It already calls `MavenProjectsManager.forceUpdateAllProjectsOrFindAllAvailablePomFiles`
   reflectively and `ExternalSystemUtil.refreshProject` (which itself is `IN_BACKGROUND_ASYNC`), so it should
   be safe. Need a test that runs `npm install` from `run_command` then immediately reads a refreshed
   `package.json` via `read_file`.

---

## 9. Sources

- IntelliJ Platform SDK — Virtual File System: <https://plugins.jetbrains.com/docs/intellij/virtual-file-system.html>
- IntelliJ Platform SDK — Virtual Files: <https://plugins.jetbrains.com/docs/intellij/virtual-file.html>
- IntelliJ Platform SDK — Threading Model: <https://plugins.jetbrains.com/docs/intellij/threading-model.html>
- IntelliJ Platform SDK — Coroutine Read Actions: <https://plugins.jetbrains.com/docs/intellij/coroutine-read-actions.html>
- IntelliJ Platform SDK — Coroutines on EDT and Locks: <https://plugins.jetbrains.com/docs/intellij/coroutine-edt-and-locks.html>
- IntelliJ Platform SDK — Coroutine Dispatchers: <https://plugins.jetbrains.com/docs/intellij/coroutine-dispatchers.html>
- IntelliJ Platform SDK — Indexing & PSI Stubs: <https://plugins.jetbrains.com/docs/intellij/indexing-and-psi-stubs.html>
- IntelliJ Platform SDK — API Notable List 2025 (background write actions, `Dispatchers.UI`, 2025.3 changes): <https://plugins.jetbrains.com/docs/intellij/api-notable-list-2025.html>
- IntelliJ Platform SDK — API Notable List 2026 (deprecation of non-cancellable read actions): <https://plugins.jetbrains.com/docs/intellij/api-notable-list-2026.html>
- VirtualFileManager source: <https://github.com/JetBrains/intellij-community/blob/master/platform/core-api/src/com/intellij/openapi/vfs/VirtualFileManager.java>
- ProjectTaskManager API: <https://dploeger.github.io/intellij-api-doc/com/intellij/task/ProjectTaskManager.html>
- BuildManager API: <https://dploeger.github.io/intellij-api-doc/com/intellij/compiler/server/BuildManager.html>
- JetBrains Support — full VFS refresh advice: <https://intellij-support.jetbrains.com/hc/en-us/community/posts/207545205-How-can-i-FULLY-refresh-VirtualFileSystem-VFS->
- JetBrains Support — refresh after external changes: <https://intellij-support.jetbrains.com/hc/en-us/community/posts/206118439-Refresh-after-external-changes-to-project-structure-and-sources>
- In-repo prior art (referenced inline above):
  - `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RunCommandTool.kt`
  - `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/EditFileTool.kt:269,283`
  - `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/CreateFileTool.kt:137`
  - `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/JavaRuntimeExecTool.kt:478,925,1530,1665`
  - `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/CoverageTool.kt:287,1090`
  - `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/RuntimeExecTool.kt:642,693`
  - `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/PythonRuntimeExecTool.kt:189` (note "no ProjectTaskManager build phase")
  - `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/BuildSystemValidator.kt`
  - `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/process/ShellResolver.kt:151` (`isLikelyBuildCommand`)
  - `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/project/RefreshExternalProjectAction.kt`
  - `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/PythonFileScanner.kt` (exclusion list)
  - `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt:1603,1849` (post-tool hook + `onWriteCheckpoint`)
