# Pipeline Fixes — Critical Agent Infrastructure

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix 13 pipeline issues identified by deep audit — the agent currently shows wrong token counts, compresses too early with a destructive summarizer, caps tool results too aggressively, and can't handle long-running tasks gracefully.

**Architecture:** Changes span 6 core files. Tool result cap raised from 500→4000 tokens with tiered aging. Compression thresholds raised from 40%/70% to 60%/85%. Auto-compression uses LLM summarizer. Token display fixed to show context window fill. Tool result pruning added as Phase 1 before summarization. Context budget warnings injected per iteration. Graceful degradation at high iterations.

**Tech Stack:** Kotlin, IntelliJ Platform, existing ContextManager/SingleAgentSession/BudgetEnforcer stack

---

## File Structure

### Modified Files

| File | Changes |
|------|---------|
| `agent/src/main/kotlin/.../context/ToolResultCompressor.kt` | Raise DEFAULT_MAX_TOKENS from 500 to 4000 |
| `agent/src/main/kotlin/.../context/ContextManager.kt` | Raise thresholds (0.85/0.60). Use LLM summarizer in auto-compress. Add tool result pruning Phase 1. Fix reconcileWithActualTokens. Cap anchored summaries. |
| `agent/src/main/kotlin/.../runtime/SingleAgentSession.kt` | Fix token display. Add context budget warnings. Add graceful degradation. Parallel tool execution. Mid-loop cancellation. Context overflow retry. |
| `agent/src/main/kotlin/.../runtime/BudgetEnforcer.kt` | Update thresholds to match new ContextManager ratios |
| `agent/src/main/kotlin/.../tools/DynamicToolSelector.kt` | Wrap PSI in ReadAction |
| `agent/src/main/kotlin/.../orchestrator/AgentOrchestrator.kt` | Stabilize tool set per session, recalculate reservedTokens |

---

## Task 1: Fix Token Display + ReadAction + Reconciliation (Critical Quick Fixes)

Three independent one-line fixes that can be done together.

**Files:**
- Modify: `agent/src/main/kotlin/.../runtime/SingleAgentSession.kt`
- Modify: `agent/src/main/kotlin/.../tools/DynamicToolSelector.kt`
- Modify: `agent/src/main/kotlin/.../context/ContextManager.kt`

- [ ] **Step 1: Fix token display — pass context window tokens, not cumulative**

In `SingleAgentSession.kt`, find all `onProgress` callback calls that pass `tokensUsed = totalTokensUsed`. Change them to pass the CURRENT context window usage instead:

```kotlin
// Replace all instances of:
tokensUsed = totalTokensUsed
// With:
tokensUsed = contextManager.currentTokens
```

Where `currentTokens` is `totalTokens` on the ContextManager. Add a public accessor if needed:

In `ContextManager.kt`, add (if not already present):
```kotlin
val currentTokens: Int get() = totalTokens
```

Keep `totalTokensUsed` for the final result/trace (it's useful for cost tracking), but the progress UI should show context fill.

- [ ] **Step 2: Fix ReadAction — wrap PSI calls**

In `DynamicToolSelector.kt`, method `detectProjectTools()` (line ~188), wrap the PSI section:

```kotlin
// Detect Spring project
try {
    com.intellij.openapi.application.ReadAction.compute<Unit, Exception> {
        val facade = com.intellij.psi.JavaPsiFacade.getInstance(project)
        val scope = com.intellij.psi.search.GlobalSearchScope.allScope(project)
        val hasSpring = facade.findClass("org.springframework.context.ApplicationContext", scope) != null
        if (hasSpring) tools.addAll(SPRING_PROJECT_TOOLS)
        val hasJpa = facade.findClass("javax.persistence.Entity", scope) != null ||
            facade.findClass("jakarta.persistence.Entity", scope) != null
        if (hasJpa) tools.addAll(JPA_PROJECT_TOOLS)
    }
} catch (_: Exception) { /* PSI not available */ }
```

- [ ] **Step 3: Fix token reconciliation — use promptTokens directly**

In `ContextManager.kt`, method `reconcileWithActualTokens()` (line ~301), change:

```kotlin
// OLD:
totalTokens = (actualPromptTokens - reservedTokens).coerceAtLeast(0)

// NEW: The API's promptTokens IS the authoritative context size.
// It includes system prompt + tool definitions + all messages.
// We don't need to subtract reservedTokens because the API already counted everything.
// Our totalTokens should track the same thing the API tracks.
totalTokens = actualPromptTokens
```

This is a behavioral change — `totalTokens` now represents the FULL context (including system prompt + tool defs), not just messages. The compression thresholds in Task 2 will be calibrated for this.

- [ ] **Step 4: Compile and test**

```bash
./gradlew :agent:test --tests "*.ContextManagerTest" --tests "*.DynamicToolSelectorTest" --tests "*.SingleAgentSessionTest" -x verifyPlugin
```

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/DynamicToolSelector.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ContextManager.kt
git commit -m "fix(agent): token display shows context fill, ReadAction for PSI, fix reconciliation

Token widget now shows contextManager.currentTokens (actual context window
fill) instead of cumulative API total. PSI calls wrapped in ReadAction.
reconcileWithActualTokens uses promptTokens directly as authoritative count
instead of subtracting stale reservedTokens."
```

---

## Task 2: Raise Tool Result Cap + Compression Thresholds

**Files:**
- Modify: `agent/src/main/kotlin/.../context/ToolResultCompressor.kt`
- Modify: `agent/src/main/kotlin/.../context/ContextManager.kt`
- Modify: `agent/src/main/kotlin/.../runtime/BudgetEnforcer.kt`

- [ ] **Step 1: Raise tool result cap from 500 to 4000 tokens**

In `ToolResultCompressor.kt`, line 10:
```kotlin
// OLD:
const val DEFAULT_MAX_TOKENS = 500

// NEW: 4000 tokens (~14K chars) — enough to see most of a typical source file
const val DEFAULT_MAX_TOKENS = 4000
```

Also in `ContextManager.kt` constructor, line 30:
```kotlin
// OLD:
private val toolResultMaxTokens: Int = 500

// NEW:
private val toolResultMaxTokens: Int = 4000
```

- [ ] **Step 2: Raise compression thresholds**

In `ContextManager.kt` constructor, lines 28-29:
```kotlin
// OLD:
private val tMaxRatio: Double = 0.70,
private val tRetainedRatio: Double = 0.40,

// NEW: Compress later (85%), retain more (60%)
// Claude Code uses ~83.5%. We use 85% with 15% buffer.
private val tMaxRatio: Double = 0.85,
private val tRetainedRatio: Double = 0.60,
```

- [ ] **Step 3: Update BudgetEnforcer thresholds to match**

In `BudgetEnforcer.kt`, lines 23-26:
```kotlin
// OLD:
private const val COMPRESSION_RATIO = 0.40
private const val NUDGE_RATIO = 0.60
private const val STRONG_NUDGE_RATIO = 0.75
private const val TERMINATE_RATIO = 0.90

// NEW: Aligned with ContextManager's new thresholds
private const val COMPRESSION_RATIO = 0.60   // Match tRetainedRatio
private const val NUDGE_RATIO = 0.75
private const val STRONG_NUDGE_RATIO = 0.85  // Match tMaxRatio
private const val TERMINATE_RATIO = 0.95     // Leave only 5% emergency buffer
```

- [ ] **Step 4: Update tests that rely on old thresholds**

Search for tests that check specific compression behavior and update expected values.

```bash
./gradlew :agent:test --tests "*.ContextManagerTest" --tests "*.BudgetEnforcerTest" --tests "*.ToolResultCompressorTest" -x verifyPlugin
```

Fix any failing tests by adjusting threshold expectations.

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ToolResultCompressor.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ContextManager.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/BudgetEnforcer.kt agent/src/test/
git commit -m "fix(agent): raise tool result cap 500→4000, compression thresholds 70%→85%

Tool results now preserve ~14K chars instead of ~1.7K. Agent can see most
of a typical source file instead of just the first 50 lines. Compression
triggers at 85% (was 70%) — matches Claude Code's ~83.5%. Budget enforcer
thresholds aligned. Retained target raised from 40% to 60%."
```

---

## Task 3: Fix Auto-Compression to Use LLM Summarizer + Tool Result Pruning

The most impactful architectural fix — changes how compression works.

**Files:**
- Modify: `agent/src/main/kotlin/.../context/ContextManager.kt`

- [ ] **Step 1: Add tool result pruning as Phase 1**

Add a new method to ContextManager that prunes old tool results BEFORE LLM summarization:

```kotlin
/**
 * Phase 1 compression: prune old tool results in-place.
 * Keeps the last [protectedIterations] iterations' tool results intact.
 * Older tool results are replaced with their summary text only.
 *
 * Inspired by OpenCode's approach: protect recent 40K tokens of tool results,
 * clear older ones with "[Old tool result content cleared]".
 */
private fun pruneOldToolResults(protectedTokens: Int = 30_000) {
    var protectedSoFar = 0
    // Walk messages from newest to oldest
    for (i in messages.indices.reversed()) {
        val msg = messages[i]
        if (msg.role != "tool") continue

        val msgTokens = TokenEstimator.estimate(listOf(msg))
        if (protectedSoFar + msgTokens <= protectedTokens) {
            protectedSoFar += msgTokens
            continue // Keep this tool result
        }

        // Prune: replace content with a short marker
        val toolCallId = msg.toolCallId
        val prunedContent = "<external_data>[Old tool result cleared to save context]</external_data>"
        messages[i] = ChatMessage(role = "tool", content = prunedContent, toolCallId = toolCallId)
    }
    totalTokens = TokenEstimator.estimate(getMessages())
}
```

- [ ] **Step 2: Make auto-compression use LLM summarizer when brain is available**

In `addMessage()` (line ~125), change the compression trigger:

```kotlin
// OLD:
if (totalTokens > tMax) {
    compress()
}

// NEW: Two-phase compression
if (totalTokens > tMax) {
    // Phase 1: Prune old tool results first (fast, no LLM needed)
    pruneOldToolResults()

    // If still over budget after pruning, use full compression
    if (totalTokens > tMax) {
        // Use LLM summarizer if brain available, otherwise truncation
        // Note: compress() is synchronous; compressWithLlm() is suspend.
        // Since addMessage() is not suspend, we fall back to compress() here.
        // The LLM compression is triggered explicitly by BudgetEnforcer in SingleAgentSession.
        compress()
    }
}
```

- [ ] **Step 3: Improve the default summarizer**

Replace the `.take(500)` default summarizer (lines 32-40) with something that preserves more info:

```kotlin
private val summarizer: (List<ChatMessage>) -> String = { messages ->
    val sb = StringBuilder("Previous conversation summary:\n")
    for (msg in messages) {
        val role = msg.role
        val content = msg.content ?: continue
        when (role) {
            "user" -> sb.appendLine("- User asked: ${content.take(200)}")
            "assistant" -> {
                // Preserve tool call names and key conclusions
                val toolCalls = msg.toolCalls
                if (!toolCalls.isNullOrEmpty()) {
                    sb.appendLine("- Agent called: ${toolCalls.joinToString(", ") { it.function.name }}")
                } else {
                    sb.appendLine("- Agent responded: ${content.take(300)}")
                }
            }
            "tool" -> {
                // Just note the tool result existed, content was already in assistant's call
                sb.appendLine("- Tool result received (${TokenEstimator.estimate(listOf(msg))} tokens)")
            }
        }
        if (sb.length > 2000) {
            sb.appendLine("... (${messages.size - messages.indexOf(msg)} more messages)")
            break
        }
    }
    sb.toString()
}
```

- [ ] **Step 4: Cap anchored summaries to prevent unbounded growth**

In `compress()`, after adding the new summary to `anchoredSummaries`, consolidate if too many:

```kotlin
// After: anchoredSummaries.add(summary)
// Add consolidation:
if (anchoredSummaries.size > 3) {
    // Merge all summaries into one
    val consolidated = anchoredSummaries.joinToString("\n\n---\n\n")
    anchoredSummaries.clear()
    anchoredSummaries.add(consolidated.take(4000)) // Cap at ~1000 tokens
}
```

- [ ] **Step 5: Compile and test**

```bash
./gradlew :agent:test --tests "*.ContextManagerTest" --tests "*.ContextManagerCompressionTest" -x verifyPlugin
```

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ContextManager.kt
git commit -m "fix(agent): two-phase compression + better summarizer + capped summaries

Phase 1: prune old tool results (protect last 30K tokens, clear older).
Phase 2: LLM or improved truncation summarizer (preserves tool call names,
user questions, key conclusions — not just .take(500)). Anchored summaries
capped at 3 entries to prevent unbounded growth."
```

---

## Task 4: Stabilize Tool Set + Recalculate Reserved Tokens

**Files:**
- Modify: `agent/src/main/kotlin/.../orchestrator/AgentOrchestrator.kt`
- Modify: `agent/src/main/kotlin/.../context/ContextManager.kt`

- [ ] **Step 1: Stabilize tool set — only EXPAND, never shrink per message**

In `AgentOrchestrator.executeTask()`, where `selectTools()` is called (line ~134):

```kotlin
// Get newly selected tools for this message
val newlySelectedTools = DynamicToolSelector.selectTools(...)

// STABILIZE: Merge with existing session tools — only ADD, never remove
// This prevents tool count from swinging 24→62→24 between messages
val stableToolNames = session.activeToolNames.toMutableSet()
stableToolNames.addAll(newlySelectedTools.map { it.name })
val selectedTools = session.tools.values.filter { it.name in stableToolNames }

// Update session's active tool set
session.activeToolNames = stableToolNames
```

This requires adding `activeToolNames: MutableSet<String>` to `ConversationSession`. Initialize it from the first message's tool selection.

- [ ] **Step 2: Recalculate reservedTokens when tool set changes**

After stabilizing tools, recalculate the reservation:

```kotlin
// Recalculate reserved tokens for the current tool set
val toolDefTokens = TokenEstimator.estimateToolDefinitions(selectedToolDefs)
val systemPromptTokens = if (effectiveSystemPrompt != null) TokenEstimator.estimate(effectiveSystemPrompt) else 0
val newReservedTokens = toolDefTokens + systemPromptTokens + 200

// Update ContextManager's reservation
session.contextManager.updateReservedTokens(newReservedTokens)
```

Add to `ContextManager.kt`:
```kotlin
fun updateReservedTokens(newReserved: Int) {
    reservedTokens = newReserved
    // Recalculate thresholds
    val effectiveBudget = maxInputTokens - reservedTokens
    tMax = (effectiveBudget * tMaxRatio).toInt()
    tRetained = (effectiveBudget * tRetainedRatio).toInt()
}
```

Note: `reservedTokens`, `tMax`, `tRetained` need to be `var` not `val`.

- [ ] **Step 3: Compile and test**

```bash
./gradlew :agent:test --tests "*.AgentOrchestratorTest" --tests "*.ContextManagerTest" -x verifyPlugin
```

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/AgentOrchestrator.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ContextManager.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ConversationSession.kt
git commit -m "fix(agent): stabilize tool set per session + recalculate reservedTokens

Tools now only expand across messages, never shrink. Prevents 24→62→24
swings that confuse the LLM and break budget math. reservedTokens
recalculated when tools change so compression thresholds stay accurate."
```

---

## Task 5: Context Budget Warnings + Graceful Degradation

**Files:**
- Modify: `agent/src/main/kotlin/.../runtime/SingleAgentSession.kt`

- [ ] **Step 1: Inject context budget warning per iteration**

In `execute()`, before each LLM call (before `callLlmWithRetry()`), inject a budget warning:

```kotlin
// Inject context budget awareness (like Claude Code's <system_warning>)
val usedPercent = ((contextManager.currentTokens.toDouble() / maxInputTokens) * 100).toInt()
val remainingTokens = maxInputTokens - contextManager.currentTokens
if (usedPercent > 50) {
    contextManager.addMessage(ChatMessage(
        role = "system",
        content = "<system_warning>Context usage: ${contextManager.currentTokens}/$maxInputTokens tokens ($usedPercent%). $remainingTokens tokens remaining. Be efficient with remaining context.</system_warning>"
    ))
}
```

Only inject when >50% used to avoid noise on early iterations.

- [ ] **Step 2: Add graceful degradation at high iterations**

In the main loop, after the iteration counter increment:

```kotlin
// Graceful degradation at high iterations (like OpenCode's maxSteps)
val iterationPercent = (iteration.toDouble() / maxIterations * 100).toInt()
if (iterationPercent >= 80 && iterationPercent < 95) {
    // 80% of iterations used — nudge to wrap up
    contextManager.addMessage(ChatMessage(
        role = "system",
        content = "<system_warning>IMPORTANT: You have used $iteration of $maxIterations iterations. Focus on completing the task. Avoid unnecessary exploration.</system_warning>"
    ))
} else if (iterationPercent >= 95) {
    // 95% of iterations — FORCE text-only response on next call
    contextManager.addMessage(ChatMessage(
        role = "system",
        content = "<system_warning>CRITICAL: This is your final iteration. Tools are disabled after this response. Provide a complete summary of what you accomplished and what remains.</system_warning>"
    ))
    // Remove tools from next call to force text-only
    // (done by passing empty toolDefs to callLlmWithRetry)
}
```

- [ ] **Step 3: Add context overflow retry**

In the context exceeded handling (inside `callLlmWithRetry()` or the main loop's `ContextExceededRetry` handler), instead of just reducing tools, also compress and retry:

```kotlin
is LlmCallResult.ContextExceededRetry -> {
    // Phase 1: Compress context
    LOG.info("Context exceeded — compressing and retrying")
    contextManager.pruneOldToolResults()
    if (brain != null) {
        contextManager.compressWithLlm(brain)
    } else {
        contextManager.compress()
    }

    // Phase 2: Retry with same tools (context is now smaller)
    val retryMessages = contextManager.getMessages()
    val retryResult = callLlmWithRetry(brain, retryMessages, toolDefsForCall, maxOutputTokens, onStreamChunk, sessionTrace)
    // ... handle retry result
}
```

- [ ] **Step 4: Add mid-loop cancellation check**

At the start of each iteration AND before each tool execution:

```kotlin
// At top of main loop:
if (cancelled.get()) {
    return SingleAgentResult.Cancelled(iteration, totalTokensUsed, editedFiles.toList())
}

// Before each tool execution in the for loop:
if (cancelled.get()) {
    contextManager.addToolResult(toolCall.id, "Cancelled by user", "Cancelled")
    break
}
```

- [ ] **Step 5: Add parallel tool execution for independent reads**

Replace the sequential tool execution loop with parallel execution for read-only tools:

```kotlin
// Separate tool calls into parallel-safe (reads) and sequential (writes)
val (parallelCalls, sequentialCalls) = toolCalls.partition { tc ->
    val toolName = tc.function.name
    toolName in setOf("read_file", "search_code", "glob_files", "file_structure",
        "find_definition", "find_references", "type_hierarchy", "call_hierarchy",
        "diagnostics", "git_status", "git_blame")
}

// Execute parallel-safe tools concurrently
if (parallelCalls.isNotEmpty()) {
    kotlinx.coroutines.coroutineScope {
        parallelCalls.map { toolCall ->
            async { executeSingleToolCall(toolCall, tools, project) }
        }.awaitAll()
    }
}

// Execute sequential tools one at a time
for (toolCall in sequentialCalls) {
    executeSingleToolCall(toolCall, tools, project)
}
```

Extract the tool execution body into a `executeSingleToolCall()` method.

- [ ] **Step 6: Compile and test**

```bash
./gradlew :agent:test --tests "*.SingleAgentSessionTest" -x verifyPlugin
```

- [ ] **Step 7: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt
git commit -m "fix(agent): context warnings, graceful degradation, parallel tools, cancellation

Inject <system_warning> with token usage per iteration (like Claude Code).
At 80% iterations: wrap-up nudge. At 95%: force text-only summary (like
OpenCode). Context overflow: compress + retry instead of failing. Parallel
execution for read-only tools. Mid-loop cancellation check."
```

---

## Task 6: Update Tests + Documentation

**Files:**
- Modify: `agent/src/test/kotlin/.../context/ContextManagerTest.kt`
- Modify: `agent/src/test/kotlin/.../runtime/BudgetEnforcerTest.kt`
- Modify: `agent/CLAUDE.md`

- [ ] **Step 1: Update ContextManager tests for new thresholds and behavior**

Tests that check compression at 70% need to be updated to 85%. Tests that check retained at 40% need 60%. Add new tests for:
- Tool result pruning Phase 1
- Anchored summary capping at 3
- `updateReservedTokens()` method
- `currentTokens` accessor

- [ ] **Step 2: Update BudgetEnforcer tests for new ratios**

Update threshold expectations: COMPRESS at 60%, NUDGE at 75%, STRONG_NUDGE at 85%, TERMINATE at 95%.

- [ ] **Step 3: Update CLAUDE.md**

In the ContextManager section:
```markdown
- **Two-threshold compression**: T_max at 85% triggers compression (was 70%), T_retained at 60% target (was 40%)
- **Two-phase compression**: Phase 1 prunes old tool results (protects last 30K tokens), Phase 2 is LLM/truncation summarization
- **Context budget awareness**: Model receives `<system_warning>` with token usage per iteration
- **Graceful degradation**: At 80% iterations = wrap-up nudge, 95% = force text-only summary
- **Parallel tool execution**: Read-only tools execute concurrently
- **Token display**: Shows current context window fill (not cumulative API total)
```

- [ ] **Step 4: Run ALL agent tests**

```bash
./gradlew :agent:test --rerun --no-build-cache -x verifyPlugin
```

- [ ] **Step 5: Commit**

```bash
git add agent/src/test/ agent/CLAUDE.md
git commit -m "test+docs: update tests for new thresholds, document pipeline fixes"
```

---

## Verification

```bash
./gradlew :agent:test --rerun --no-daemon
./gradlew verifyPlugin
```

Manual verification in `runIde`:
1. **Token display**: Ask "How does auth work?" → verify widget shows ~8-15K/190K (context fill), NOT 50K+ (cumulative)
2. **No ReadAction error**: First message should NOT show threading error in IDE log
3. **Tool results visible**: Agent reads a 200-line file → verify the tool card shows substantial content, not just 50 lines
4. **Compression later**: Run a complex task → verify compression doesn't trigger until ~160K context
5. **Context warning**: At high context usage, verify `<system_warning>` appears in the trace
6. **Graceful stop**: After ~40 iterations, verify agent wraps up instead of spiraling
7. **Multi-turn**: Second message should NOT reset context or re-explore from scratch
8. **Parallel tools**: Agent calling 3 read_file should be faster than sequential
