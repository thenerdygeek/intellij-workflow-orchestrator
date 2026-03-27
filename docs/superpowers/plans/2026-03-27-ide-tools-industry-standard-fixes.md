# IDE Tools Industry Standard Fixes

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix 2 HIGH and 1 MEDIUM threading/API issue found during IDE tools audit — eliminate EDT blocking, deadlock risks, and use non-blocking read actions.

**Architecture:** Each fix is isolated to one file. StartDebugSessionTool needs EDT only for the launch call, not the 30s wait. RefactorRenameTool needs `withContext(EDT)` instead of `invokeAndWait`, and `findUsages()` must run outside WriteAction. ProblemViewTool needs `ReadAction.nonBlocking` instead of blocking `ReadAction.compute`.

**Tech Stack:** Kotlin coroutines, IntelliJ Platform SDK (EDT dispatcher, ReadAction.nonBlocking, WriteCommandAction)

---

### Task 1: Fix StartDebugSessionTool — Remove 30s EDT block

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/StartDebugSessionTool.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/debug/StartDebugSessionToolTest.kt`

**Problem:** The entire `execute()` body from line 81-105 runs inside `withContext(Dispatchers.EDT)`, including the `suspendCancellableCoroutine` that waits up to 30s for `XDebuggerManagerListener.processStarted`. Only `ExecutionEnvironmentBuilder.create().build()` and `ProgramRunnerUtil.executeConfiguration()` require EDT. The callback wait must happen off-EDT.

**Fix:** Subscribe to the message bus listener BEFORE switching to EDT. Launch on EDT via `invokeLater`. Wait for callback off-EDT.

- [ ] **Step 1: Rewrite the execute method to separate EDT-bound launch from off-EDT wait**

Replace lines 80-105 in `StartDebugSessionTool.kt` with:

```kotlin
            // Subscribe to debugger events BEFORE launching (off-EDT) so we don't miss the callback
            val sessionId = withTimeoutOrNull(30_000L) {
                suspendCancellableCoroutine<String> { cont ->
                    val connection = project.messageBus.connect()
                    cont.invokeOnCancellation { connection.disconnect() }
                    connection.subscribe(XDebuggerManager.TOPIC, object : XDebuggerManagerListener {
                        override fun processStarted(debugProcess: XDebugProcess) {
                            val session = debugProcess.session
                            val id = controller.registerSession(session)
                            connection.disconnect()
                            if (cont.isActive) cont.resume(id)
                        }
                    })

                    // Launch on EDT — only the launch call requires EDT, not the wait
                    com.intellij.openapi.application.invokeLater {
                        try {
                            val executor = DefaultDebugExecutor.getDebugExecutorInstance()
                            val env = ExecutionEnvironmentBuilder.create(project, executor, settings.configuration).build()
                            ProgramRunnerUtil.executeConfiguration(env, true, true)
                        } catch (e: Exception) {
                            connection.disconnect()
                            if (cont.isActive) cont.resume("")
                        }
                    }
                }
            }
```

Also update the null/empty check below:

```kotlin
            if (sessionId == null || sessionId.isEmpty()) {
```

- [ ] **Step 2: Remove unused EDT import if no longer needed**

Check if `Dispatchers.EDT` and `withContext` are still used elsewhere in the file. If not, remove:
```kotlin
import com.intellij.openapi.application.EDT
```

Keep `withContext` and `Dispatchers` if used for other parts. In this case, `withContext(Dispatchers.EDT)` is no longer called, so remove the EDT import. Keep the `Dispatchers` import only if needed (it isn't — remove it too).

The remaining imports should be:
```kotlin
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerManagerListener
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume
```

- [ ] **Step 3: Run existing tests**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew :agent:test --tests "*.StartDebugSessionToolTest" --rerun --no-build-cache`
Expected: All existing tests pass (they test parameter validation and metadata, not EDT threading).

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/StartDebugSessionTool.kt
git commit -m "fix(agent): remove 30s EDT block in StartDebugSessionTool

Subscribe to XDebuggerManagerListener off-EDT, launch via invokeLater,
wait for callback off-EDT. Only the ProgramRunnerUtil.executeConfiguration
call runs on EDT now."
```

---

### Task 2: Fix RefactorRenameTool — Replace invokeAndWait, separate read from write

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/RefactorRenameTool.kt`

**Problem:** Two issues:
1. Line 61: `ApplicationManager.getApplication().invokeAndWait { ... }` — can deadlock when called from a coroutine that already holds a read lock or from EDT. Should use `withContext(Dispatchers.EDT)`.
2. Lines 72-73: `RenameProcessor.findUsages()` (a read operation) runs inside `WriteCommandAction` — holds the write lock unnecessarily during usage search. Standard pattern: find usages first (read), then perform refactoring (write).

- [ ] **Step 1: Add EDT import**

Add these imports to the file (if not already present):
```kotlin
import com.intellij.openapi.application.EDT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
```

Remove this import (no longer needed):
```kotlin
import com.intellij.openapi.application.ApplicationManager
```

- [ ] **Step 2: Rewrite the execute method's rename block**

Replace lines 59-92 (the `return try { ... } catch` block) with:

```kotlin
        return try {
            // Phase 1: Find usages (read-only, off-EDT via NBRA)
            val usages = com.intellij.openapi.application.ReadAction.nonBlocking<Array<com.intellij.usageView.UsageInfo>> {
                val processor = RenameProcessor(
                    project,
                    element,
                    newName,
                    false, // searchInComments
                    false  // searchTextOccurrences
                )
                processor.setPreviewUsages(false)
                processor.findUsages()
            }.inSmartMode(project).executeSynchronously()

            // Phase 2: Perform refactoring (write, on EDT)
            withContext(Dispatchers.EDT) {
                WriteCommandAction.runWriteCommandAction(project, "Agent: Rename $oldName → $newName", null, {
                    val processor = RenameProcessor(
                        project,
                        element,
                        newName,
                        false,
                        false
                    )
                    processor.setPreviewUsages(false)
                    processor.performRefactoring(usages)
                })
            }

            ToolResult(
                "Renamed '$oldName' → '$newName'. All references, imports, and usages updated.",
                "Renamed $oldName → $newName",
                10
            )
        } catch (e: Exception) {
            ToolResult("Error during rename: ${e.message}", "Rename error", 5, isError = true)
        }
```

- [ ] **Step 3: Wrap findElement in ReadAction**

The `findElement` call at line 49 accesses PSI without a read lock. Wrap it:

Replace:
```kotlin
        val element = findElement(project, symbol, rawFile)
```

With:
```kotlin
        val element = com.intellij.openapi.application.ReadAction.nonBlocking<com.intellij.psi.PsiElement?> {
            findElement(project, symbol, rawFile)
        }.inSmartMode(project).executeSynchronously()
```

- [ ] **Step 4: Verify compilation**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew :agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/RefactorRenameTool.kt
git commit -m "fix(agent): replace invokeAndWait with EDT coroutine in RefactorRenameTool

- Use withContext(Dispatchers.EDT) instead of invokeAndWait to prevent
  deadlocks when called from coroutine contexts.
- Separate findUsages (read) from performRefactoring (write) so the
  write lock isn't held during usage search.
- Wrap findElement in ReadAction.nonBlocking for PSI thread safety."
```

---

### Task 3: Fix ProblemViewTool — Use non-blocking ReadAction

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/ProblemViewTool.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/ide/ProblemViewToolTest.kt`

**Problem:** Lines 70 and 101 use `ReadAction.compute<ToolResult, Exception> { ... }` which blocks the calling thread for the entire read lock duration. Every other read-only tool in the codebase uses `ReadAction.nonBlocking<T> { ... }.executeSynchronously()` which is cancellable and doesn't hold the thread hostage.

- [ ] **Step 1: Convert getProblemsForFile to non-blocking**

Replace line 66-98 (`getProblemsForFile` method) — change the method signature to `suspend` and replace `ReadAction.compute` with `ReadAction.nonBlocking`:

```kotlin
    private suspend fun getProblemsForFile(filePath: String, severity: String, project: Project): ToolResult {
        val (path, pathError) = PathValidator.resolveAndValidate(filePath, project.basePath)
        if (pathError != null) return pathError

        return ReadAction.nonBlocking<ToolResult> {
            val vf = LocalFileSystem.getInstance().findFileByIoFile(java.io.File(path!!))
                ?: return@nonBlocking ToolResult(
                    "File not found: $filePath",
                    "Not found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                )

            val problems = collectHighlightProblems(vf, severity, project)
            val relativePath = project.basePath?.let {
                vf.path.removePrefix(it).removePrefix("/")
            } ?: vf.name

            if (problems.isEmpty()) {
                val wolf = WolfTheProblemSolver.getInstance(project)
                val hasProblemFlag = wolf.isProblemFile(vf)
                if (hasProblemFlag) {
                    ToolResult(
                        "No detailed problems available for $relativePath (file flagged as problematic by IDE but no HighlightInfo — file may not be open in editor).",
                        "Flagged but no details", 10
                    )
                } else {
                    ToolResult("No problems in $relativePath.", "No problems", 5)
                }
            } else {
                val content = formatFileProblems(relativePath, problems)
                ToolResult(content, "${problems.size} problems in ${vf.name}", TokenEstimator.estimate(content))
            }
        }.executeSynchronously()
    }
```

- [ ] **Step 2: Convert getProblemsForAllOpenFiles to non-blocking**

Replace line 100-145 — same pattern:

```kotlin
    private suspend fun getProblemsForAllOpenFiles(severity: String, project: Project): ToolResult {
        return ReadAction.nonBlocking<ToolResult> {
            val openFiles = FileEditorManager.getInstance(project).openFiles
            if (openFiles.isEmpty()) {
                return@nonBlocking ToolResult("No files are open in the editor.", "No open files", 5)
            }

            val wolf = WolfTheProblemSolver.getInstance(project)
            val filesWithProblems = mutableListOf<Pair<String, List<ProblemEntry>>>()

            for (vf in openFiles) {
                val problems = collectHighlightProblems(vf, severity, project)
                if (problems.isNotEmpty()) {
                    val relativePath = project.basePath?.let {
                        vf.path.removePrefix(it).removePrefix("/")
                    } ?: vf.name
                    filesWithProblems.add(relativePath to problems)
                } else if (wolf.isProblemFile(vf)) {
                    val relativePath = project.basePath?.let {
                        vf.path.removePrefix(it).removePrefix("/")
                    } ?: vf.name
                    filesWithProblems.add(relativePath to listOf(
                        ProblemEntry("WARNING", 0, "File flagged as problematic (no detailed info available)")
                    ))
                }
            }

            if (filesWithProblems.isEmpty()) {
                ToolResult("No problems found in ${openFiles.size} open file(s).", "No problems", 5)
            } else {
                val totalProblems = filesWithProblems.sumOf { it.second.size }
                val sb = StringBuilder()
                sb.appendLine("Problems in project (${filesWithProblems.size} file(s)):")
                for ((path, problems) in filesWithProblems) {
                    sb.appendLine()
                    sb.append(formatFileProblems(path, problems))
                }
                val content = sb.toString().trimEnd()
                ToolResult(
                    content,
                    "$totalProblems problems in ${filesWithProblems.size} files",
                    TokenEstimator.estimate(content)
                )
            }
        }.executeSynchronously()
    }
```

- [ ] **Step 3: Add LocalFileSystem import if needed**

Verify `LocalFileSystem` import exists (it does at line 9). No change needed.

- [ ] **Step 4: Run existing tests**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew :agent:test --tests "*.ProblemViewToolTest" --rerun --no-build-cache`
Expected: All 14 existing tests pass.

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/ProblemViewTool.kt
git commit -m "fix(agent): use non-blocking ReadAction in ProblemViewTool

Replace ReadAction.compute (blocking) with ReadAction.nonBlocking
(cancellable) for consistency with all other read-only tools."
```

---

### Task 4: Verify all agent tests pass

- [ ] **Step 1: Run full agent test suite**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew :agent:test --rerun --no-build-cache`
Expected: All ~470 tests pass.

- [ ] **Step 2: Verify plugin compatibility**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew verifyPlugin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit version bump if needed**

Only if all tests pass and verify succeeds.
