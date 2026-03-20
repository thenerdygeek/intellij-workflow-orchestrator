# Final 8 Flaws Fix — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix 3 HIGH and 5 MEDIUM flaws found by the expert architecture review — all cause real failures in production usage.

**Architecture:** Surgical fixes — no new files. Each flaw is a focused change to 1-2 files.

**Tech Stack:** Kotlin, IntelliJ Platform, kotlinx.coroutines

---

## Task 1: Fix 3 HIGH Flaws (will break normal usage)

These three flaws would cause visible failures in the first user session. Fix together since they're each a few lines.

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/EditFileTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/CreatePlanTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/PlanManager.kt`

### Flaw 1: Double system prompt on multi-turn

**File:** `SingleAgentSession.kt`

Find lines ~121-124:
```kotlin
val resolvedSystemPrompt = systemPrompt ?: buildFallbackSystemPrompt()
contextManager.addMessage(ChatMessage(role = "system", content = resolvedSystemPrompt))
contextManager.addMessage(ChatMessage(role = "user", content = task))
```

Change to:
```kotlin
// Only add system prompt if explicitly provided (first message).
// On multi-turn, systemPrompt is null — the prompt is already in context from session.initialize().
if (systemPrompt != null) {
    contextManager.addMessage(ChatMessage(role = "system", content = systemPrompt))
}
contextManager.addMessage(ChatMessage(role = "user", content = task))
```

Delete or keep `buildFallbackSystemPrompt()` method — it's still useful for tests where no session is used. Just don't call it in the null case.

### Flaw 2: Double approval for file edits

**File:** `EditFileTool.kt`

Find the approval check block (lines ~63-79) that checks `settings.state.approvalRequiredForEdits` and returns `APPROVAL_REQUIRED`. Remove it entirely. The `ApprovalGate` in `SingleAgentSession` already handles approval before the tool executes — the tool should just do the edit.

```kotlin
// REMOVE this entire block:
// try {
//     val settings = AgentSettings.getInstance(project)
//     if (settings.state.approvalRequiredForEdits) {
//         ...return APPROVAL_REQUIRED...
//     }
// } catch ...
```

The approval flow is now solely handled by `ApprovalGate` + `AgentController.showApprovalDialog()` (which now uses `EditApprovalDialog` with DiffManager for MEDIUM-risk edits).

### Flaw 3: CompletableFuture.get() blocks IO thread forever

**File:** `CreatePlanTool.kt`

Replace `CompletableFuture.get()` with `suspendCancellableCoroutine`:

Find:
```kotlin
val result = planManager.submitPlan(plan).get()
```

Replace with:
```kotlin
val result = kotlinx.coroutines.suspendCancellableCoroutine<PlanApprovalResult> { cont ->
    val future = planManager.submitPlan(plan)
    cont.invokeOnCancellation { future.cancel(true) }
    future.whenComplete { value, error ->
        if (error != null) {
            cont.resumeWithException(error)
        } else {
            cont.resume(value)
        }
    }
}
```

Import: `import kotlinx.coroutines.suspendCancellableCoroutine` and `import kotlin.coroutines.resume` and `import kotlin.coroutines.resumeWithException`

Also add a timeout so users aren't stuck forever if the UI callback breaks:

```kotlin
val result = kotlinx.coroutines.withTimeoutOrNull(600_000L) { // 10 minute timeout
    suspendCancellableCoroutine<PlanApprovalResult> { cont ->
        val future = planManager.submitPlan(plan)
        cont.invokeOnCancellation { future.cancel(true) }
        future.whenComplete { value, error ->
            if (error != null) cont.resumeWithException(error)
            else cont.resume(value)
        }
    }
} ?: return ToolResult(
    "Plan approval timed out after 10 minutes. The plan was not approved.",
    "Plan timeout", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
)
```

- [ ] **Step 1:** Fix SingleAgentSession — skip system prompt when null (multi-turn)
- [ ] **Step 2:** Fix EditFileTool — remove internal approval check (ApprovalGate handles it)
- [ ] **Step 3:** Fix CreatePlanTool — replace CompletableFuture.get() with suspendCancellableCoroutine + timeout
- [ ] **Step 4:** Verify: `./gradlew :agent:compileKotlin && ./gradlew :agent:test --rerun --no-build-cache`
- [ ] **Step 5:** Commit: `fix(agent): 3 HIGH flaws — double prompt, double approval, blocking future`

---

## Task 2: Fix 5 MEDIUM Flaws (edge cases causing real problems)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RunCommandTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ContextManager.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/AgentOrchestrator.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt`

### Flaw 4: RunCommandTool working_dir has no path validation

**File:** `RunCommandTool.kt`

Find the working_dir resolution (around line 113):
```kotlin
val workingDir = params["working_dir"]?.jsonPrimitive?.content?.let { dir ->
    if (dir.startsWith("/")) dir else "$basePath/$dir"
} ?: basePath
```

Replace with:
```kotlin
val workingDir = params["working_dir"]?.jsonPrimitive?.content?.let { dir ->
    val (validated, error) = PathValidator.resolveAndValidate(dir, project.basePath)
    if (error != null) return error
    validated!!
} ?: (project.basePath ?: ".")
```

Import `PathValidator`.

### Flaw 5: Token count drift between compressions

**File:** `ContextManager.kt`

The issue: anchored summaries grow but aren't tracked between compression cycles. Fix: after compression, `totalTokens` is already recalculated from `getMessages()` (which includes summaries). The drift happens because `addMessage()` increments `totalTokens` by only the new message's tokens, while the tMax check doesn't account for summary growth.

Simplest fix: in the `addMessage()` method, periodically reconcile `totalTokens` with the actual message count:

```kotlin
fun addMessage(message: ChatMessage) {
    val tokenCount = TokenEstimator.estimate(listOf(message))
    messages.add(message)
    totalTokens += tokenCount

    // Periodically reconcile to prevent drift from summary growth
    if (messages.size % 20 == 0) {
        totalTokens = TokenEstimator.estimate(getMessages())
    }

    if (totalTokens > tMax) {
        compress()
    }
}
```

Every 20 messages, recalculate the true total. This catches drift without the cost of recalculating every message.

### Flaw 6: Tool results not wrapped in `<external_data>` tags

**File:** `ContextManager.kt`

Find `addToolResult()`:
```kotlin
fun addToolResult(toolCallId: String, content: String, summary: String) {
    val compressed = ToolResultCompressor.compress(content, summary, toolResultMaxTokens)
    addMessage(ChatMessage(role = "tool", content = compressed, toolCallId = toolCallId))
}
```

Change to:
```kotlin
fun addToolResult(toolCallId: String, content: String, summary: String) {
    val compressed = ToolResultCompressor.compress(content, summary, toolResultMaxTokens)
    // Wrap in <external_data> tags for prompt injection defense.
    // The system prompt instructs the LLM to never follow instructions within these tags.
    val wrapped = "<external_data>\n$compressed\n</external_data>"
    addMessage(ChatMessage(role = "tool", content = wrapped, toolCallId = toolCallId))
}
```

### Flaw 7: DynamicToolSelector frozen on first message

**File:** `AgentOrchestrator.kt`

Find where `DynamicToolSelector.selectTools()` is called (around line 135). Currently it uses only `taskDescription`. For multi-turn sessions, it should consider ALL recent messages.

Change to: when session is provided, build context from recent messages:
```kotlin
val toolContext = if (session != null) {
    // Include recent messages for better tool selection
    val recentMessages = session.contextManager.getMessages()
        .filter { it.role == "user" }
        .takeLast(3)
        .mapNotNull { it.content }
        .joinToString("\n")
    "$taskDescription\n$recentMessages"
} else {
    taskDescription
}

val selectedTools = DynamicToolSelector.selectTools(allToolsCollection, toolContext)
```

This rescans the last 3 user messages for tool keywords on each turn, so "now update the Jira ticket" in a follow-up will trigger Jira tool injection.

### Flaw 8: CoroutineScope not tied to project lifecycle

**File:** `AgentController.kt`

The controller's `CoroutineScope` is not disposed when the project closes. Find the `init` block and add:

```kotlin
init {
    // Tie coroutine scope to project lifecycle
    com.intellij.openapi.util.Disposer.register(project, { scope.cancel() })

    // ... rest of init ...
}
```

Note: This requires `project` to be a `Disposable`. In IntelliJ, `Project` implements `Disposable`, so this works. If the cast fails for some reason, use a try-catch.

- [ ] **Step 1:** Fix RunCommandTool — add PathValidator to working_dir
- [ ] **Step 2:** Fix ContextManager — periodic token reconciliation every 20 messages
- [ ] **Step 3:** Fix ContextManager — wrap tool results in `<external_data>` tags
- [ ] **Step 4:** Fix AgentOrchestrator — rescan recent messages for DynamicToolSelector
- [ ] **Step 5:** Fix AgentController — register scope cancellation with project Disposer
- [ ] **Step 6:** Verify: `./gradlew :agent:compileKotlin && ./gradlew :agent:test --rerun --no-build-cache`
- [ ] **Step 7:** Commit: `fix(agent): 5 MEDIUM flaws — working_dir path, token drift, injection tags, tool selector, scope lifecycle`

---

## Verification

```bash
./gradlew :agent:compileKotlin
./gradlew :agent:test --rerun --no-build-cache
./gradlew compileKotlin  # all modules
```

Manual verification scenarios:
1. Multi-turn: send 2 messages → check context doesn't have 2 system prompts
2. File edit with approval on: approve in dialog → file is actually modified
3. Plan mode: create plan → click Approve → agent proceeds (no thread stuck)
4. Run command with `working_dir: "../../"` → rejected by PathValidator
5. Follow-up "update the Jira ticket" → Jira tools available (not filtered out)
