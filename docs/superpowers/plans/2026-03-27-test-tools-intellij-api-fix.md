# Fix Test Execution Tools — Proper IntelliJ API Usage

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix three agent tools (`run_tests`, `get_test_results`, `get_run_output`) that are broken because they use polling/reflection instead of official IntelliJ Platform APIs.

**Architecture:** Replace polling-based descriptor matching and reflection-based console access with official callback APIs: `ProgramRunner.Callback` for execution, `TestResultsViewer.EventsListener` for test completion, `SMTRunnerConsoleView` direct casts for results and console text. All three tools share the pattern of needing to handle both `SMTRunnerConsoleView` (test consoles) and `ConsoleViewImpl` (regular consoles), plus wrapper consoles via `BaseTestsOutputConsoleView.getConsole()`.

**Tech Stack:** Kotlin, IntelliJ Platform SDK (`com.intellij.execution.*`, `com.intellij.execution.testframework.sm.runner.*`)

**Current state:** All three files have in-progress changes that compile but have known issues (documented below). This plan captures remaining gaps.

---

## Remaining Issues in Current Code

### RunTestsTool.kt (partially fixed)
1. **`executeConfigurationAsync` may not exist** — The research found this method in `ProgramRunnerUtil.java`, but it may not be available in 2025.1 (it could be a newer addition). Need fallback: set callback on `ExecutionEnvironment` directly via `env.setCallback()` before calling `executeConfiguration()`.
2. **Non-test consoles fallback uses a `Timer` with 2-second delay** — fragile. Should use `ProcessHandler.addProcessListener` → `processTerminated` + `waitForResultsThenResume` poll (same as before but on the correct descriptor).
3. **`collectTestResults` uses `root.allTests` in the `onTestingFinished` callback comment but actually calls `collectLeafTests`** — cosmetic but should use `root.allTests` (public API on `SMTestProxy`) instead of manual tree walk.
4. **No `Disposable` cleanup** for the `TestResultsViewer.EventsListener` — if the tool times out, the listener leaks.

### GetTestResultsTool.kt (partially fixed)
1. **`MAX_PROCESS_WAIT_SECONDS = 600`** — correct but the delay loop uses `delay(1000)` which suspends the coroutine. This is fine for the agent (tool runs in IO dispatcher), but the tool has no progress feedback to the LLM during the wait.
2. **`hasTestResults` checks `root.children.isNotEmpty()`** — good, but should also handle the case where `findTestRoot` succeeds but `resultsViewer.isRunning` is still true (test tree partially populated).

### GetRunOutputTool.kt (partially fixed)
1. **`readConsoleViewText` uses `invokeAndWaitIfNeeded`** — this blocks the calling thread. Should use `withContext(Dispatchers.EDT)` for consistency with the codebase's threading model.
2. **No handling for `ConsoleView` (interface)** — there's a `getText()` extension or method on some `ConsoleView` implementations beyond `ConsoleViewImpl`. The `readViaEditor` fallback is reasonable.

---

## File Structure

| Action | File | Responsibility |
|--------|------|---------------|
| Modify | `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/RunTestsTool.kt` | Fix `executeConfigurationAsync` fallback, use `root.allTests`, clean up listener |
| Modify | `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/GetTestResultsTool.kt` | Already mostly correct. Fix EDT threading, add progress streaming |
| Modify | `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/GetRunOutputTool.kt` | Fix EDT call to use `withContext(Dispatchers.EDT)` |
| Modify | `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/runtime/GetTestResultsToolTest.kt` | Update tests for new wait behavior |
| Modify | `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/runtime/GetRunOutputToolTest.kt` | Update tests for BaseTestsOutputConsoleView handling |

---

### Task 1: Fix RunTestsTool — executeConfigurationAsync fallback

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/RunTestsTool.kt:150-170`

The `ProgramRunnerUtil.executeConfigurationAsync()` with a `ProgramRunner.Callback` is the preferred API, but it may not be available in all IntelliJ 2025.1 builds. Add a fallback that sets the callback directly on the `ExecutionEnvironment`.

- [ ] **Step 1: Add callback fallback in executeWithNativeRunner**

Replace the current EDT block (lines 153-245) with a version that tries `executeConfigurationAsync` first, falls back to `env.callback = ...` + `executeConfiguration()`:

```kotlin
com.intellij.openapi.application.invokeLater {
    try {
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val env = ExecutionEnvironmentBuilder
            .createOrNull(executor, settings)
            ?.build()

        if (env == null) {
            if (continuation.isActive) continuation.resume(null)
            return@invokeLater
        }

        val callback = object : ProgramRunner.Callback {
            override fun processStarted(descriptor: RunContentDescriptor?) {
                if (descriptor == null) {
                    if (continuation.isActive) continuation.resume(null)
                    return
                }
                handleDescriptorReady(descriptor, continuation, testTarget)
            }
        }

        // Try the async variant first (gives us the callback directly)
        // Fall back to setting callback on env + sync call
        try {
            ProgramRunnerUtil.executeConfigurationAsync(env, false, true, callback)
        } catch (_: NoSuchMethodError) {
            env.callback = callback
            ProgramRunnerUtil.executeConfiguration(env, false, true)
        }
    } catch (e: Exception) {
        if (continuation.isActive) continuation.resume(null)
    }
}
```

- [ ] **Step 2: Extract handleDescriptorReady method**

Extract the `processStarted` body into a separate method for readability:

```kotlin
private fun handleDescriptorReady(
    descriptor: RunContentDescriptor,
    continuation: kotlinx.coroutines.CancellableContinuation<ToolResult?>,
    testTarget: String
) {
    descriptorRef.set(descriptor)
    val handler = descriptor.processHandler
    processHandlerRef.set(handler)

    // Stream console output
    val toolCallId = RunCommandTool.currentToolCallId.get()
    val activeStreamCallback = RunCommandTool.streamCallback
    if (handler != null && toolCallId != null) {
        handler.addProcessListener(object : ProcessAdapter() {
            override fun onTextAvailable(event: ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                val text = event.text ?: return
                if (text.isNotBlank()) activeStreamCallback?.invoke(toolCallId, text)
            }
        })
    }

    val console = descriptor.executionConsole
    if (console is SMTRunnerConsoleView) {
        console.resultsViewer.addEventsListener(object : TestResultsViewer.EventsListener {
            override fun onTestingFinished(sender: TestResultsViewer) {
                if (!continuation.isActive) return
                val root = sender.testsRootNode as? SMTestProxy.SMRootTestProxy
                if (root != null) {
                    val allTests = root.allTests
                        .filterIsInstance<SMTestProxy>()
                        .filter { it.isLeaf }
                        .map { mapToTestResultEntry(it) }
                    val result = if (allTests.isNotEmpty()) {
                        formatStructuredResults(allTests, descriptor.displayName ?: testTarget)
                    } else {
                        ToolResult("Test run completed for $testTarget but no test methods found.", "No tests found", 10)
                    }
                    continuation.resume(result)
                }
            }
        })
    } else {
        // Non-test console — wait for process termination
        if (handler == null || handler.isProcessTerminated) {
            if (continuation.isActive) continuation.resume(extractNativeResults(descriptor, testTarget))
        } else {
            handler.addProcessListener(object : ProcessAdapter() {
                override fun processTerminated(event: ProcessEvent) {
                    // Brief delay for async result population
                    java.util.Timer().schedule(object : java.util.TimerTask() {
                        override fun run() {
                            if (continuation.isActive) continuation.resume(extractNativeResults(descriptor, testTarget))
                        }
                    }, 2000)
                }
            })
        }
    }
}
```

- [ ] **Step 3: Replace collectLeafTests with root.allTests**

Replace the manual tree walk `collectTestResults`/`collectLeafTests` with the public `SMTestProxy.allTests` API. Add a helper `mapToTestResultEntry`:

```kotlin
private fun mapToTestResultEntry(proxy: SMTestProxy): TestResultEntry {
    val status = when {
        proxy.isDefect -> {
            if (proxy.stacktrace?.contains("AssertionError") == true ||
                proxy.stacktrace?.contains("AssertionFailedError") == true
            ) TestStatus.FAILED else TestStatus.ERROR
        }
        proxy.isIgnored -> TestStatus.SKIPPED
        else -> TestStatus.PASSED
    }
    val stackTrace = proxy.stacktrace
        ?.lines()
        ?.filter { it.trimStart().startsWith("at ") || it.contains("Exception") || it.contains("Error") }
        ?.take(MAX_STACK_FRAMES)
        ?: emptyList()

    return TestResultEntry(
        name = proxy.name,
        status = status,
        durationMs = proxy.duration?.toLong() ?: 0L,
        errorMessage = proxy.errorMessage,
        stackTrace = stackTrace
    )
}
```

Then update `collectTestResults` to use it:

```kotlin
private fun collectTestResults(root: SMTestProxy): List<TestResultEntry> {
    return root.allTests
        .filterIsInstance<SMTestProxy>()
        .filter { it.isLeaf }
        .map { mapToTestResultEntry(it) }
}
```

- [ ] **Step 4: Compile and verify**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew :agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/RunTestsTool.kt
git commit -m "fix(agent): use ProgramRunner.Callback with fallback, root.allTests API in RunTestsTool"
```

---

### Task 2: Fix GetTestResultsTool — stream progress during long waits

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/GetTestResultsTool.kt:67-100`

The tool waits up to 10 minutes for builds. During this time, the LLM gets no feedback. Stream periodic status updates.

- [ ] **Step 1: Add progress streaming to the process wait loop**

Replace the wait loop (lines 67-100) with one that streams progress:

```kotlin
// Wait for process to terminate first (covers build + compilation + test execution).
val handler = descriptor.processHandler
if (handler != null && !handler.isProcessTerminated) {
    val toolCallId = com.workflow.orchestrator.agent.tools.builtin.RunCommandTool.currentToolCallId.get()
    val streamCallback = com.workflow.orchestrator.agent.tools.builtin.RunCommandTool.streamCallback
    var waited = 0
    val maxWaitMs = MAX_PROCESS_WAIT_SECONDS * 1000
    while (!handler.isProcessTerminated && waited < maxWaitMs) {
        delay(2000)
        waited += 2000
        // Stream progress every 10 seconds
        if (waited % 10_000 == 0 && toolCallId != null) {
            streamCallback?.invoke(toolCallId, "[waiting for process... ${waited / 1000}s elapsed]\n")
        }
    }
    if (!handler.isProcessTerminated) {
        return ToolResult(
            "Process for '${descriptor.displayName}' is still running after ${MAX_PROCESS_WAIT_SECONDS}s " +
                "(may still be building/compiling). Try again later.",
            "Process still running",
            20,
            isError = true
        )
    }
}

// Process terminated — wait for test framework to finalize the tree
val console = descriptor.executionConsole
if (console is SMTRunnerConsoleView) {
    var waited = 0
    while (console.resultsViewer.isRunning && waited < 10_000) {
        delay(200)
        waited += 200
    }
} else {
    delay(1000)
}
```

- [ ] **Step 2: Use root.allTests in collectTestResults**

Same as RunTestsTool — replace the manual tree walk with the public API. Add the `mapToTestResultEntry` helper (same code as Task 1 Step 3) and update `collectTestResults`:

```kotlin
private fun collectTestResults(root: SMTestProxy): List<TestResultEntry> {
    return root.allTests
        .filterIsInstance<SMTestProxy>()
        .filter { it.isLeaf }
        .map { mapToTestResultEntry(it) }
}

private fun mapToTestResultEntry(proxy: SMTestProxy): TestResultEntry {
    val status = when {
        proxy.isDefect -> {
            if (proxy.stacktrace?.contains("AssertionError") == true ||
                proxy.stacktrace?.contains("AssertionFailedError") == true
            ) TestStatus.FAILED else TestStatus.ERROR
        }
        proxy.isIgnored -> TestStatus.SKIPPED
        else -> TestStatus.PASSED
    }
    val stackTrace = proxy.stacktrace
        ?.lines()
        ?.filter { it.trimStart().startsWith("at ") || it.contains("Exception") || it.contains("Error") }
        ?.take(MAX_STACK_FRAMES)
        ?: emptyList()

    return TestResultEntry(
        name = proxy.name,
        status = status,
        durationMs = proxy.duration?.toLong() ?: 0L,
        errorMessage = proxy.errorMessage,
        stackTrace = stackTrace
    )
}
```

Remove the old `collectLeafTests` method.

- [ ] **Step 3: Compile and verify**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew :agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/GetTestResultsTool.kt
git commit -m "fix(agent): stream progress during long waits, use root.allTests in GetTestResultsTool"
```

---

### Task 3: Fix GetRunOutputTool — EDT threading

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/GetRunOutputTool.kt:180-188`

The `invokeAndWaitIfNeeded` call blocks the thread. Replace with `withContext(Dispatchers.EDT)` for consistency.

- [ ] **Step 1: Add EDT import and make extractConsoleText suspend**

Add import:
```kotlin
import com.intellij.openapi.application.EDT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
```

Change `extractConsoleText` and `readConsoleViewText` to `suspend` functions:

```kotlin
private suspend fun extractConsoleText(descriptor: RunContentDescriptor): String? {
    val console = descriptor.executionConsole ?: return null

    if (console is com.intellij.execution.impl.ConsoleViewImpl) {
        return readConsoleViewText(console)
    }

    if (console is com.intellij.execution.testframework.ui.BaseTestsOutputConsoleView) {
        val innerConsole = console.console
        if (innerConsole is com.intellij.execution.impl.ConsoleViewImpl) {
            return readConsoleViewText(innerConsole)
        }
        return readViaEditor(innerConsole)
    }

    try {
        val getConsole = console.javaClass.getMethod("getConsole")
        val innerConsole = getConsole.invoke(console)
        if (innerConsole is com.intellij.execution.impl.ConsoleViewImpl) {
            return readConsoleViewText(innerConsole)
        }
        if (innerConsole != null) {
            return readViaEditor(innerConsole)
        }
    } catch (_: Exception) {}

    return readViaEditor(console)
}

private suspend fun readConsoleViewText(console: com.intellij.execution.impl.ConsoleViewImpl): String? {
    return try {
        withContext(Dispatchers.EDT) {
            console.flushDeferredText()
            console.editor?.document?.text
        }
    } catch (_: Exception) { null }
}
```

- [ ] **Step 2: Compile and verify**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew :agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/GetRunOutputTool.kt
git commit -m "fix(agent): use withContext(EDT) instead of invokeAndWaitIfNeeded in GetRunOutputTool"
```

---

### Task 4: Update existing tests

**Files:**
- Modify: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/runtime/GetTestResultsToolTest.kt`
- Modify: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/runtime/GetRunOutputToolTest.kt`

Existing tests are metadata-only. They still pass since the tool signatures haven't changed. Run them to confirm.

- [ ] **Step 1: Run existing tests**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew :agent:test --tests "com.workflow.orchestrator.agent.tools.runtime.GetTestResultsToolTest" --tests "com.workflow.orchestrator.agent.tools.runtime.GetRunOutputToolTest" -v`
Expected: All tests PASS

- [ ] **Step 2: Commit if tests pass (no changes needed)**

Tests should pass as-is. No commit needed unless fixes were required.

---

### Task 5: Final build and integration verify

- [ ] **Step 1: Full agent compilation**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew :agent:clean :agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run all agent tests**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew :agent:test`
Expected: All tests PASS

- [ ] **Step 3: Full plugin build**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew buildPlugin`
Expected: BUILD SUCCESSFUL, ZIP created in `build/distributions/`

- [ ] **Step 4: Commit any remaining changes**

```bash
git add -A
git commit -m "fix(agent): complete test tools API fix — proper IntelliJ execution callbacks"
```

---

## Summary of API Changes

| Tool | Before (broken) | After (fixed) |
|------|-----------------|---------------|
| **run_tests** | Poll `allDescriptors` by name → matches old runs | `ProgramRunner.Callback.processStarted()` → exact descriptor |
| **run_tests** | `processTerminated` + 5s poll for results | `TestResultsViewer.EventsListener.onTestingFinished()` → tree is fully populated |
| **run_tests** | Reflection: `getResultsViewer()`, `getTestsRootNode()` | Direct cast: `SMTRunnerConsoleView.resultsViewer.testsRootNode` |
| **run_tests** | Manual `collectLeafTests` tree walk | `root.allTests` public API |
| **get_test_results** | 60s wait (too short for builds) | Process wait (10 min) + `resultsViewer.isRunning` check (10s) |
| **get_test_results** | Reflection for `findTestRoot` | Direct `SMTRunnerConsoleView` cast + `getConsole()` wrapper |
| **get_run_output** | Only `ConsoleViewImpl` check | Handles `BaseTestsOutputConsoleView.getConsole()` → inner `ConsoleViewImpl` |
| **get_run_output** | `invokeAndWaitIfNeeded` | `withContext(Dispatchers.EDT)` |
