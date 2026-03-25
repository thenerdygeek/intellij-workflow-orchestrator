# IDE Tools Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix 8 CRITICAL and 14 HIGH findings from the IDE tools audit to prevent IDE freezes, hangs, and silent failures.

**Architecture:** Fixes are grouped by pattern — each task targets one fix pattern across all affected tools. This minimizes context-switching and ensures consistent patterns.

**Tech Stack:** Kotlin, IntelliJ Platform 2025.1+ APIs (ReadAction.nonBlocking, Dispatchers.EDT, withTimeoutOrNull, suspendCancellableCoroutine)

**Audit Report:** `docs/audits/2026-03-25-ide-tools-audit.md`

---

### Task 1: Fix blocking ReadAction in RunInspectionsTool, ListQuickfixesTool, SemanticDiagnosticsTool (C1, C2)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/RunInspectionsTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/ListQuickFixesTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/SemanticDiagnosticsTool.kt`

**Problem:** These tools use `ReadAction.compute<T, Exception> { ... }` which blocks write actions and can freeze the IDE. All other PSI tools correctly use `ReadAction.nonBlocking`.

- [ ] **Step 1: Read all three files to find the exact ReadAction.compute calls**

Read each file, find lines with `ReadAction.compute` and note the exact code block.

- [ ] **Step 2: Replace ReadAction.compute with ReadAction.nonBlocking in RunInspectionsTool**

Replace:
```kotlin
ReadAction.compute<ToolResult, Exception> {
    // ... PSI inspection code ...
}
```

With:
```kotlin
ReadAction.nonBlocking<ToolResult> {
    // ... same PSI inspection code ...
}.inSmartMode(project).executeSynchronously()
```

Ensure the lambda is idempotent (check `isValid` on PSI elements at the start).

- [ ] **Step 3: Replace ReadAction.compute in ListQuickFixesTool**

Same pattern as Step 2.

- [ ] **Step 4: Replace ReadAction.compute in SemanticDiagnosticsTool**

Same pattern as Step 2. The `PsiRecursiveElementWalkingVisitor` inside the lambda is fine — `nonBlocking` will cancel and restart if a write action arrives.

- [ ] **Step 5: Compile and verify**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew :agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/RunInspectionsTool.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/ListQuickFixesTool.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/SemanticDiagnosticsTool.kt
git commit -m "fix(C1,C2): replace blocking ReadAction.compute with nonBlocking in PSI tools"
```

---

### Task 2: Add timeout to StartDebugSessionTool (C3)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/StartDebugSessionTool.kt`

**Problem:** `suspendCancellableCoroutine` inside `withContext(Dispatchers.EDT)` has no timeout. If debug session fails to start, the IDE freezes permanently.

- [ ] **Step 1: Read the file and find the suspendCancellableCoroutine block**

- [ ] **Step 2: Wrap in withTimeoutOrNull and add cleanup**

Find the `suspendCancellableCoroutine` and wrap it:

```kotlin
val result = withTimeoutOrNull(30_000L) {
    suspendCancellableCoroutine<DebugSessionResult> { cont ->
        val connection = project.messageBus.connect()

        cont.invokeOnCancellation {
            connection.disconnect()
        }

        connection.subscribe(XDebuggerManager.TOPIC, object : XDebuggerManagerListener {
            override fun processStarted(debugProcess: XDebugProcess) {
                connection.disconnect()
                if (cont.isActive) {
                    cont.resume(DebugSessionResult.Success(debugProcess))
                }
            }
        })

        // ... launch debug session ...
    }
}

if (result == null) {
    return ToolResult(
        "Debug session failed to start within 30 seconds. Check your run configuration, build errors, or port conflicts.",
        "Debug session timeout",
        ToolResult.ERROR_TOKEN_ESTIMATE,
        isError = true
    )
}
```

Make sure the message bus `connection` is disconnected on both timeout and cancellation.

- [ ] **Step 3: Compile and verify**

Run: `./gradlew :agent:compileKotlin`

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/StartDebugSessionTool.kt
git commit -m "fix(C3): add 30s timeout to StartDebugSessionTool to prevent EDT freeze"
```

---

### Task 3: Fix RunTestsTool timer leak and continuation safety (C4) + IntelliJ dialog prevention

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/RunTestsTool.kt`

**Problems:**
1. Timer not cancelled on coroutine cancellation — leaks
2. `cont.resume()` called without checking `cont.isActive` — can crash
3. `ProgramRunnerUtil.executeConfiguration(env, true, true)` — the first `true` means `showSettings=true` which shows the Edit Configuration dialog

- [ ] **Step 1: Read RunTestsTool.kt and find executeWithNativeRunner**

- [ ] **Step 2: Fix ProgramRunnerUtil to prevent Edit Config dialog**

Change:
```kotlin
ProgramRunnerUtil.executeConfiguration(env, true, true)
```
To:
```kotlin
ProgramRunnerUtil.executeConfiguration(env, false, true)
//                                          ^^^^^
//                                    don't show settings dialog
```

- [ ] **Step 3: Fix timer leak — add invokeOnCancellation**

In the `suspendCancellableCoroutine` block, after creating the timer:

```kotlin
cont.invokeOnCancellation {
    timer.cancel()
}
```

- [ ] **Step 4: Guard all cont.resume calls with isActive check**

Replace every `continuation.resume(...)` with:
```kotlin
if (continuation.isActive) {
    continuation.resume(...)
}
```

Or use `cont.tryResume(value)?.let { cont.completeResume(it) }` for a safer pattern.

- [ ] **Step 5: Fix shell fallback to stream output instead of readText()**

In `executeWithShell`, replace:
```kotlin
val output = process.inputStream.bufferedReader().readText()
```

With a capped reader:
```kotlin
val output = buildString {
    process.inputStream.bufferedReader().use { reader ->
        var line = reader.readLine()
        while (line != null && length < MAX_OUTPUT_CHARS) {
            appendLine(line)
            line = reader.readLine()
        }
    }
}
```

This also fixes H5 (OOM risk on verbose test output).

- [ ] **Step 6: Compile and verify**

Run: `./gradlew :agent:compileKotlin`

- [ ] **Step 7: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/RunTestsTool.kt
git commit -m "fix(C4,H5): fix timer leak, dialog prevention, output cap in RunTestsTool"
```

---

### Task 4: Fix RefactorRenameTool dialog suppression (C5)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/RefactorRenameTool.kt`

**Problem:** `RenameProcessor.run()` can trigger conflict dialogs and preview windows.

- [ ] **Step 1: Read the file and find the RenameProcessor usage**

- [ ] **Step 2: Replace processor.run() with direct findUsages + performRefactoring**

Replace:
```kotlin
processor.setPreviewUsages(false)
processor.run()
```

With:
```kotlin
processor.setPreviewUsages(false)
processor.setInteractive(null)  // suppress all dialogs
val usages = processor.findUsages()
processor.performRefactoring(usages)
```

This bypasses the full `run()` pipeline which can trigger dialogs, and directly applies the refactoring.

- [ ] **Step 3: Compile and verify**

Run: `./gradlew :agent:compileKotlin`

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/RefactorRenameTool.kt
git commit -m "fix(C5): suppress all dialogs in RefactorRenameTool with direct findUsages+performRefactoring"
```

---

### Task 5: Add timeouts to AgentDebugController and CompileModuleTool (C6, C7)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/AgentDebugController.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/CompileModuleTool.kt`

**Problems:**
- C6: `computeChildren` and `resolvePresentation` in AgentDebugController have no timeout — hang if debuggee is unresponsive
- C7: CompileModuleTool has no timeout — hangs indefinitely on broken builds

- [ ] **Step 1: Read AgentDebugController.kt, find computeChildren and resolvePresentation**

- [ ] **Step 2: Wrap each suspendCancellableCoroutine in withTimeoutOrNull(5000)**

For `computeChildren`:
```kotlin
val children = withTimeoutOrNull(5000L) {
    suspendCancellableCoroutine<XValueChildrenList> { cont ->
        // ... existing callback code ...
        cont.invokeOnCancellation { /* cleanup */ }
    }
} ?: return emptyList()  // timeout = return empty
```

Same pattern for `resolvePresentation`.

- [ ] **Step 3: Read CompileModuleTool.kt, find the make() call**

- [ ] **Step 4: Add 120s timeout to CompileModuleTool**

Wrap the `suspendCancellableCoroutine` in:
```kotlin
val result = withTimeoutOrNull(120_000L) {
    suspendCancellableCoroutine<CompileResult> { cont ->
        cont.invokeOnCancellation {
            // Try to cancel compilation via ProgressIndicator if accessible
        }
        // ... existing make() callback ...
    }
}

if (result == null) {
    return ToolResult(
        "Compilation timed out after 120 seconds. The build may be stuck. Check for build system errors.",
        "Compile timeout",
        ToolResult.ERROR_TOKEN_ESTIMATE,
        isError = true
    )
}
```

- [ ] **Step 5: Compile and verify**

Run: `./gradlew :agent:compileKotlin`

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/AgentDebugController.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/CompileModuleTool.kt
git commit -m "fix(C6,C7): add timeouts to debug variable resolution (5s) and compilation (120s)"
```

---

### Task 6: Add PathValidator to tools missing it (H10-H14)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/FileStructureTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/AddBreakpointTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/RemoveBreakpointTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugRunToCursorTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/FindReferencesTool.kt`

**Problem:** These tools resolve file paths without `PathValidator.resolveAndValidate()`, allowing potential path traversal.

- [ ] **Step 1: Read each file and find the path resolution code**

Look for patterns like:
```kotlin
val path = if (rawPath.startsWith("/")) rawPath else "${project.basePath}/$rawPath"
```

- [ ] **Step 2: Replace with PathValidator in each file**

Replace manual path resolution with:
```kotlin
val path = PathValidator.resolveAndValidate(rawPath, project.basePath ?: "")
    ?: return ToolResult(
        "Error: path '$rawPath' is outside the project directory",
        "Error: invalid path",
        ToolResult.ERROR_TOKEN_ESTIMATE,
        isError = true
    )
```

Add import: `import com.workflow.orchestrator.agent.tools.builtin.PathValidator`

- [ ] **Step 3: Compile and verify**

Run: `./gradlew :agent:compileKotlin`

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/FileStructureTool.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/AddBreakpointTool.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/RemoveBreakpointTool.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugRunToCursorTool.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/FindReferencesTool.kt
git commit -m "fix(H10-H14): add PathValidator security check to 5 tools missing it"
```

---

### Task 7: Fix GetRunOutputTool console text extraction (H7)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/GetRunOutputTool.kt`

**Problem:** Reflection-based `getText()` almost always returns null. `ConsoleViewImpl` has a direct `getText()` method.

- [ ] **Step 1: Read the file and find extractConsoleText**

- [ ] **Step 2: Replace reflection with direct ConsoleViewImpl.getText()**

Replace the reflection-based extraction with:
```kotlin
private fun extractConsoleText(descriptor: RunContentDescriptor): String? {
    val console = descriptor.executionConsole ?: return null

    // Direct approach: ConsoleViewImpl has getText()
    if (console is com.intellij.execution.impl.ConsoleViewImpl) {
        // Ensure all deferred text is flushed
        return com.intellij.openapi.application.invokeAndWaitIfNeeded {
            console.flushDeferredText()
            console.text
        }
    }

    // Fallback: try via Editor document
    try {
        val editorMethod = console.javaClass.methods.find { it.name == "getEditor" && it.parameterCount == 0 }
        val editor = editorMethod?.invoke(console) as? com.intellij.openapi.editor.Editor
        return editor?.document?.text
    } catch (_: Exception) {
        return null
    }
}
```

- [ ] **Step 3: Compile and verify**

Run: `./gradlew :agent:compileKotlin`

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/GetRunOutputTool.kt
git commit -m "fix(H7): use ConsoleViewImpl.getText() directly instead of broken reflection"
```

---

### Task 8: Fix FormatCodeTool and OptimizeImportsTool invokeAndWait deadlock (H9)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/FormatCodeTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/OptimizeImportsTool.kt`

**Problem:** `ApplicationManager.getApplication().invokeAndWait { ... }` deadlocks if already on EDT.

- [ ] **Step 1: Read both files and find invokeAndWait calls**

- [ ] **Step 2: Replace with withContext(Dispatchers.EDT)**

Since these are `suspend fun` tools called from coroutine context, replace:
```kotlin
ApplicationManager.getApplication().invokeAndWait {
    // ... code ...
}
```

With:
```kotlin
withContext(Dispatchers.EDT) {
    // ... same code ...
}
```

Add import: `import kotlinx.coroutines.Dispatchers` and `import com.intellij.openapi.application.EDT`

This is safe from any thread and won't deadlock.

- [ ] **Step 3: Compile and verify**

Run: `./gradlew :agent:compileKotlin`

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/FormatCodeTool.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/OptimizeImportsTool.kt
git commit -m "fix(H9): replace invokeAndWait with Dispatchers.EDT to prevent deadlock"
```

---

### Task 9: Fix SearchCodeTool cancellation + memory (H1, H2) and GlobFilesTool early exit (H3)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SearchCodeTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/GlobFilesTool.kt`

**Problems:**
- H1: SearchCodeTool recursive scan has no cancellation support
- H2: SearchCodeTool reads entire files into memory
- H3: GlobFilesTool walks all files even after max_results reached

- [ ] **Step 1: Read SearchCodeTool.kt and find the recursive search**

- [ ] **Step 2: Add cancellation checks and streaming reads**

In the recursive file walk, add `ensureActive()` at the top:
```kotlin
private suspend fun searchFiles(dir: File, pattern: Regex, results: MutableList<Match>, maxResults: Int) {
    kotlinx.coroutines.ensureActive()  // check for cancellation
    if (results.size >= maxResults) return  // early exit
    // ...
}
```

Replace `file.readLines()` with streaming:
```kotlin
file.bufferedReader().useLines { lines ->
    lines.forEachIndexed { lineNum, line ->
        if (pattern.containsMatchIn(line)) {
            results.add(Match(file, lineNum + 1, line.trim()))
            if (results.size >= maxResults) return@useLines
        }
    }
}
```

- [ ] **Step 3: Read GlobFilesTool.kt and add early exit**

In the `FileVisitor`, check the count:
```kotlin
override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
    if (matches.size >= maxResults * 2) return FileVisitResult.TERMINATE
    // ... existing matching logic ...
}
```

- [ ] **Step 4: Compile and verify**

Run: `./gradlew :agent:compileKotlin`

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SearchCodeTool.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/GlobFilesTool.kt
git commit -m "fix(H1-H3): add cancellation + streaming reads to SearchCodeTool, early exit to GlobFilesTool"
```

---

### Task 10: Fix remaining HIGH issues (H4, H6, H8) and key MEDIUM issues (M4, M8, M12)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/EvaluateExpressionTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/GetVariablesTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/CallHierarchyTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RunCommandTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/CompileModuleTool.kt`

- [ ] **Step 1: Remove frame_index parameter from EvaluateExpressionTool and GetVariablesTool (H6)**

Remove `"frame_index"` from the `parameters` map entirely since it's not implemented. Don't half-implement features.

- [ ] **Step 2: Fix CallHierarchyTool to use findClassAnywhere (M4)**

In `CallHierarchyTool.kt`, replace:
```kotlin
PsiToolUtils.findClass(project, className)
```
With:
```kotlin
PsiToolUtils.findClassAnywhere(project, className)
```

- [ ] **Step 3: Allow remote refs in read-only git commands (M8)**

In `RunCommandTool.kt`, find the blocked remote refs pattern. Modify to allow `origin/` and `upstream/` in read-only commands (git log, git diff, git show, git rev-list, git merge-base):

Add a check: if the git command is read-only AND contains remote refs, allow it instead of blocking.

- [ ] **Step 4: Add line numbers to CompileModuleTool errors (M12)**

In `CompileModuleTool.kt`, find where compiler errors are formatted. Change:
```kotlin
"$file: ${msg.message}"
```
To:
```kotlin
"$file:${msg.line}:${msg.column}: ${msg.message}"
```

- [ ] **Step 5: Compile and verify**

Run: `./gradlew :agent:compileKotlin`

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/EvaluateExpressionTool.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/GetVariablesTool.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/CallHierarchyTool.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RunCommandTool.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/CompileModuleTool.kt
git commit -m "fix(H4,H6,M4,M8,M12): remove fake frame_index, fix class lookup, allow git remote refs, add compile line numbers"
```

---

### Task 11: Run full test suite and final verification

**Files:** None (verification only)

- [ ] **Step 1: Run agent tests**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew :agent:test`
Expected: All tests pass

- [ ] **Step 2: Build plugin**

Run: `./gradlew buildPlugin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Fix any test failures and commit**

If needed:
```bash
git commit -m "fix: resolve test failures from IDE tools audit fixes"
```
