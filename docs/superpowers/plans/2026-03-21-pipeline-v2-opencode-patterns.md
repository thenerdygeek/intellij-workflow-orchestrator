# Pipeline V2 — OpenCode Architecture Patterns

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close all 9 remaining pipeline gaps by adopting OpenCode's proven patterns: full tool results in context with disk spillover, parallel tool execution, context overflow replay, structured compaction template, and doom loop detection.

**Architecture:** Remove `ToolResultCompressor` from the tool result insertion path — tool results go into context at full size (2000 lines / 50KB cap). A new `ToolOutputStore` saves full content to disk for re-reads after pruning. Phase 1 pruning (already implemented) handles aging. `SingleAgentSession` gains parallel tool execution, context overflow replay, and OpenCode-style doom loop detection. `ContextManager` gains structured compaction template and system message cleanup.

**Tech Stack:** Kotlin coroutines (async/await for parallel tools), file I/O for disk spillover, existing ContextManager/SingleAgentSession stack

---

## File Structure

### New Files

| File | Responsibility |
|------|---------------|
| `agent/src/main/kotlin/.../context/ToolOutputStore.kt` | Saves full tool results to disk, provides paths for re-reads |
| `agent/src/test/kotlin/.../context/ToolOutputStoreTest.kt` | Tests for disk spillover |

### Modified Files

| File | Changes |
|------|---------|
| `agent/src/main/kotlin/.../context/ContextManager.kt` | Remove ToolResultCompressor from addToolResult(). Add structured compaction template. Allow system message compression. |
| `agent/src/main/kotlin/.../context/ToolResultCompressor.kt` | Repurpose: only used for 2000-line / 50KB cap + disk hint (not 4K token compression) |
| `agent/src/main/kotlin/.../runtime/SingleAgentSession.kt` | Parallel tool execution. Context overflow replay. Streaming token estimate. |
| `agent/src/main/kotlin/.../runtime/LoopGuard.kt` | OpenCode doom loop detection (3 identical calls). File re-read tracking. |

---

## Task 1: ToolOutputStore + Full Results in Context

Remove the aggressive 4K token compression. Send full tool results to the LLM. Save to disk for re-reads after pruning.

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ToolOutputStore.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ContextManager.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ToolResultCompressor.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/context/ToolOutputStoreTest.kt`

- [ ] **Step 1: Create ToolOutputStore**

```kotlin
package com.workflow.orchestrator.agent.context

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Saves full tool output to disk for re-reads after context pruning.
 *
 * When a tool returns a large result (file content, search results, command output),
 * the full content is saved to disk. If the result gets pruned from context later,
 * the agent can re-read specific sections via read_file with offset/limit.
 *
 * Storage: {sessionDir}/tool-outputs/{toolCallId}.txt
 *
 * Follows OpenCode's pattern: full content on disk, summary in context after pruning.
 */
class ToolOutputStore(private val sessionDir: File?) {

    companion object {
        private val LOG = Logger.getInstance(ToolOutputStore::class.java)
        const val MAX_LINES = 2000
        const val MAX_BYTES = 50 * 1024  // 50KB
    }

    private val outputDir: File? get() = sessionDir?.let { File(it, "tool-outputs").also { d -> d.mkdirs() } }
    private val storedPaths = ConcurrentHashMap<String, String>()

    /**
     * Save tool output to disk. Returns the disk path.
     * Content is capped at MAX_LINES / MAX_BYTES (whichever first).
     */
    fun save(toolCallId: String, content: String): String? {
        val dir = outputDir ?: return null
        return try {
            val file = File(dir, "$toolCallId.txt")
            file.writeText(content.take(MAX_BYTES))
            val path = file.absolutePath
            storedPaths[toolCallId] = path
            path
        } catch (e: Exception) {
            LOG.debug("ToolOutputStore: failed to save $toolCallId: ${e.message}")
            null
        }
    }

    /**
     * Get the disk path for a previously saved tool output.
     */
    fun getPath(toolCallId: String): String? = storedPaths[toolCallId]

    /**
     * Cap content to MAX_LINES / MAX_BYTES, append truncation hint if needed.
     */
    fun capContent(content: String, diskPath: String?): String {
        val lines = content.lines()
        val cappedByLines = if (lines.size > MAX_LINES) {
            lines.take(MAX_LINES).joinToString("\n") +
                "\n\n[Truncated at $MAX_LINES lines — ${lines.size} total." +
                (if (diskPath != null) " Full output saved to: $diskPath. Use read_file with offset/limit to view more.]" else "]")
        } else content

        return if (cappedByLines.length > MAX_BYTES) {
            cappedByLines.take(MAX_BYTES) +
                "\n\n[Truncated at ${MAX_BYTES / 1024}KB." +
                (if (diskPath != null) " Full output at: $diskPath]" else "]")
        } else cappedByLines
    }
}
```

- [ ] **Step 2: Modify ContextManager.addToolResult() — remove ToolResultCompressor**

In `ContextManager.kt`, replace the `addToolResult()` method (lines 156-166):

```kotlin
/**
 * Add a tool result to context. Full content sent to LLM (capped at 2000 lines / 50KB).
 * Full content also saved to disk for re-reads after pruning.
 *
 * Following OpenCode's pattern: LLM sees everything on first read.
 * Phase 1 pruning (pruneOldToolResults) handles aging — no premature compression.
 */
fun addToolResult(toolCallId: String, content: String, summary: String) {
    // Save full content to disk (for re-reads after pruning)
    val diskPath = toolOutputStore?.save(toolCallId, content)

    // Cap at 2000 lines / 50KB (OpenCode's limits) — NOT the old 4K token compression
    val cappedContent = toolOutputStore?.capContent(content, diskPath) ?: content

    // Wrap in external_data tags for injection defense
    val wrapped = "<external_data>\n$cappedContent\n</external_data>"
    addMessage(ChatMessage(role = "tool", content = wrapped, toolCallId = toolCallId))
}
```

Add `toolOutputStore` field to ContextManager:
```kotlin
var toolOutputStore: ToolOutputStore? = null
```

- [ ] **Step 3: Wire ToolOutputStore in ConversationSession.create()**

After session creation, set the store:
```kotlin
session.contextManager.toolOutputStore = ToolOutputStore(session.store.sessionDirectory)
```

- [ ] **Step 4: Repurpose ToolResultCompressor**

`ToolResultCompressor.kt` is no longer called from `addToolResult()`. It can be kept for other uses (e.g., worker sessions with tighter budgets) but is no longer in the main agent's tool result path. Add a deprecation note:

```kotlin
/**
 * @deprecated Tool results are now stored at full size in context (2000 lines / 50KB cap)
 * with disk spillover via ToolOutputStore. This compressor is only used for worker sessions.
 */
```

- [ ] **Step 5: Write tests**

```kotlin
@Test
fun `save stores content to disk`() {
    val store = ToolOutputStore(tempDir)
    val path = store.save("call-1", "line 1\nline 2\nline 3")
    assertNotNull(path)
    assertTrue(File(path!!).exists())
    assertEquals("line 1\nline 2\nline 3", File(path).readText())
}

@Test
fun `capContent truncates at 2000 lines`() {
    val store = ToolOutputStore(tempDir)
    val longContent = (1..3000).joinToString("\n") { "line $it" }
    val capped = store.capContent(longContent, "/tmp/output.txt")
    assertTrue(capped.lines().size <= 2005) // 2000 + truncation message
    assertTrue(capped.contains("[Truncated at 2000 lines"))
    assertTrue(capped.contains("Full output saved to: /tmp/output.txt"))
}

@Test
fun `capContent passes through small content`() {
    val store = ToolOutputStore(tempDir)
    val small = "hello world"
    assertEquals(small, store.capContent(small, null))
}

@Test
fun `getPath returns saved path`() {
    val store = ToolOutputStore(tempDir)
    store.save("call-1", "content")
    assertNotNull(store.getPath("call-1"))
    assertNull(store.getPath("nonexistent"))
}
```

- [ ] **Step 6: Compile and test**

```bash
./gradlew :agent:test --tests "*.ToolOutputStoreTest" --tests "*.ContextManagerTest" -x verifyPlugin
```

- [ ] **Step 7: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ToolOutputStore.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/context/ToolOutputStoreTest.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ContextManager.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ToolResultCompressor.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ConversationSession.kt
git commit -m "feat(agent): full tool results in context + disk spillover (OpenCode pattern)

Remove ToolResultCompressor from tool result insertion path. LLM now sees
full tool output (2000 lines / 50KB cap) on first read. Full content saved
to disk via ToolOutputStore for re-reads after Phase 1 pruning. Follows
OpenCode's proven pattern: full content → context aging → disk backup."
```

---

## Task 2: Parallel Tool Execution

Read-only tools run concurrently. Write tools run sequentially.

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt`

- [ ] **Step 1: Define read-only tool set**

Add a companion object constant:
```kotlin
private val READ_ONLY_TOOLS = setOf(
    "read_file", "search_code", "glob_files", "file_structure",
    "find_definition", "find_references", "type_hierarchy", "call_hierarchy",
    "diagnostics", "git_status", "git_blame", "find_implementations",
    "spring_context", "spring_endpoints", "spring_bean_graph",
    "jira_get_ticket", "jira_get_comments", "jira_get_transitions",
    "bamboo_build_status", "bamboo_get_build", "bamboo_get_test_results",
    "sonar_issues", "sonar_quality_gate", "sonar_coverage",
    "sonar_search_projects", "sonar_analysis_tasks", "sonar_project_health"
)
```

- [ ] **Step 2: Extract single tool execution into a method**

Extract the body of the tool execution from the `for (toolCall in toolCalls)` loop (lines 500-642) into:

```kotlin
private suspend fun executeSingleTool(
    toolCall: ToolCall,
    tools: Map<String, AgentTool>,
    project: Project,
    contextManager: ContextManager,
    approvalGate: ApprovalGate?,
    editedFiles: MutableSet<String>,
    toolOutputStore: ToolOutputStore?,
    sessionTrace: SessionTrace?,
    onProgress: ((AgentProgress) -> Unit)?
): ToolResult {
    // ... extracted tool execution body
}
```

- [ ] **Step 3: Split tool calls into parallel and sequential**

Replace the sequential `for (toolCall in toolCalls)` with:

```kotlin
// Split into read-only (parallel-safe) and write (sequential)
val (readOnlyCalls, writeCalls) = toolCalls.partition { tc ->
    tc.function.name in READ_ONLY_TOOLS
}

// Execute read-only tools in parallel
if (readOnlyCalls.isNotEmpty()) {
    kotlinx.coroutines.coroutineScope {
        readOnlyCalls.map { toolCall ->
            async {
                if (cancelled.get()) return@async
                executeSingleTool(toolCall, tools, project, contextManager, approvalGate, editedFiles, toolOutputStore, sessionTrace, onProgress)
            }
        }.awaitAll()
    }
}

// Execute write tools sequentially
for (toolCall in writeCalls) {
    if (cancelled.get()) break
    executeSingleTool(toolCall, tools, project, contextManager, approvalGate, editedFiles, toolOutputStore, sessionTrace, onProgress)
}
```

Note: The ContextManager is NOT thread-safe. The `executeSingleTool` for parallel calls needs to collect results and add them to context AFTER all parallel calls complete:

```kotlin
if (readOnlyCalls.isNotEmpty()) {
    val results = kotlinx.coroutines.coroutineScope {
        readOnlyCalls.map { toolCall ->
            async {
                if (cancelled.get()) return@async toolCall to ToolResult("Cancelled", "Cancelled", 0, isError = true)
                toolCall to executeSingleToolRaw(toolCall, tools, project, sessionTrace, onProgress)
            }
        }.awaitAll()
    }
    // Add results to context sequentially (ContextManager is not thread-safe)
    for ((toolCall, result) in results) {
        contextManager.addToolResult(toolCall.id, result.content, result.summary)
        // Track artifacts, edited files, etc.
    }
}
```

- [ ] **Step 4: Compile and test**

```bash
./gradlew :agent:test --tests "*.SingleAgentSessionTest" -x verifyPlugin
```

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt
git commit -m "feat(agent): parallel execution for read-only tools (OpenCode pattern)

Read-only tools (read_file, search_code, diagnostics, etc.) now execute
concurrently via coroutineScope+async. Write tools (edit_file, run_command)
still sequential. Results added to context sequentially after parallel
completion (ContextManager is not thread-safe)."
```

---

## Task 3: Context Overflow Replay + Streaming Token Estimate

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt`

- [ ] **Step 1: Context overflow — compress then REPLAY the request**

In the main loop, find the `ContextExceededRetry` handling (lines ~277-281). Change to replay:

```kotlin
is LlmCallResult.ContextExceededRetry -> {
    LOG.info("Context exceeded — compressing and replaying")
    // Phase 1: Prune old tool results
    contextManager.pruneOldToolResults()
    // Phase 2: LLM or truncation compression
    if (brain != null) {
        try { contextManager.compressWithLlm(brain) } catch (_: Exception) { contextManager.compress() }
    } else {
        contextManager.compress()
    }
    // REPLAY: Retry the SAME request with compressed context
    val replayMessages = contextManager.getMessages()
    val replayToolDefs = result.reducedToolDefs ?: toolDefsForCall
    val replayResult = callLlmWithRetry(brain, replayMessages, replayToolDefs, maxOutputTokens, onStreamChunk, sessionTrace)
    when (replayResult) {
        is LlmCallResult.Success -> {
            val processed = processLlmSuccess(replayResult, brain, contextManager, ...)
            if (processed != null) return processed
            // Continue loop if null (more tool calls)
        }
        else -> {
            // If replay also fails, give up
            return SingleAgentResult.Failed("Context overflow persisted after compression", totalTokensUsed, editedFiles.toList())
        }
    }
}
```

- [ ] **Step 2: Streaming token estimate when usage is null**

In `processLlmSuccess()`, after the token reconciliation block, add a fallback for streaming:

```kotlin
// If streaming returned null usage, estimate from the response
if (usage == null || usage.promptTokens == 0) {
    // Heuristic estimate: current messages + tool defs + response
    val estimatedPrompt = TokenEstimator.estimate(contextManager.getMessages()) + reservedTokens
    val estimatedCompletion = TokenEstimator.estimate(message.content ?: "")
    totalTokensUsed += estimatedPrompt + estimatedCompletion
    // Don't reconcile — heuristic is better than nothing but not authoritative
}
```

- [ ] **Step 3: Compile and test**

```bash
./gradlew :agent:test --tests "*.SingleAgentSessionTest" -x verifyPlugin
```

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt
git commit -m "fix(agent): context overflow replay + streaming token estimate

On context overflow: compress then REPLAY the request (OpenCode pattern)
instead of just continuing. Streaming responses now get heuristic token
estimate when API returns usage: null."
```

---

## Task 4: Structured Compaction Template + System Message Cleanup

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ContextManager.kt`

- [ ] **Step 1: Structured compaction template for LLM summarization**

In `compressWithLlm()`, replace the current summarization prompt with OpenCode's structured template:

Find the LLM summarization prompt (around line 258) and replace with:

```kotlin
val summarizePrompt = """
Summarize the conversation so far into a structured continuation prompt.
Use this exact format:

## Goal
What is the user trying to accomplish?

## Instructions
Key instructions or constraints the user specified.

## Discoveries
Important findings from code exploration, tool results, and analysis.
Include specific file paths, line numbers, and code patterns found.

## Accomplished
What has been completed so far. List specific changes made.

## Relevant Files
Files that were read, edited, or referenced. Include paths.

Be concise but preserve ALL technical details — file paths, line numbers,
error messages, code snippets, and specific findings. These details are
critical for continuing the task.
""".trimIndent()
```

- [ ] **Step 2: Allow system messages to be compressed**

In `compress()`, the current code skips system messages (line ~197: `if (msg.role == "system") continue`). Change to only protect the FIRST system message (the original system prompt) and the anchors:

```kotlin
// Skip the very first system message (original prompt) and anchors
if (msg.role == "system" && i == 0) continue
// Allow other system messages (LoopGuard reminders, budget warnings) to be compressed
```

This prevents accumulation of nudge/warning messages that can never be removed.

- [ ] **Step 3: Compile and test**

```bash
./gradlew :agent:test --tests "*.ContextManagerTest" --tests "*.ContextManagerCompressionTest" -x verifyPlugin
```

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ContextManager.kt
git commit -m "feat(agent): structured compaction template + system message cleanup

LLM summarization uses OpenCode's structured template (Goal/Instructions/
Discoveries/Accomplished/Files). Old system messages (LoopGuard reminders,
budget warnings) can now be compressed — only the original system prompt
is protected."
```

---

## Task 5: OpenCode Doom Loop Detection + File Re-Read Tracking

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/LoopGuard.kt`

- [ ] **Step 1: Add OpenCode-style doom loop detection**

Replace the existing loop detection in LoopGuard with a cleaner implementation:

```kotlin
companion object {
    const val DOOM_LOOP_THRESHOLD = 3
}

// Track last N tool calls for doom loop detection
private val recentToolCalls = mutableListOf<String>() // "toolName:argsHash"
private val readFiles = mutableSetOf<String>() // Files already read

/**
 * Check for doom loop: 3 identical tool calls in a row.
 * Also tracks files read for re-read detection.
 */
fun checkDoomLoop(toolName: String, args: String): String? {
    val callKey = "$toolName:${args.hashCode()}"
    recentToolCalls.add(callKey)

    // Track file reads
    if (toolName == "read_file") {
        val pathMatch = Regex(""""path"\s*:\s*"([^"]+)"""").find(args)
        val filePath = pathMatch?.groupValues?.get(1)
        if (filePath != null) {
            if (filePath in readFiles) {
                return "You already read '$filePath' earlier in this conversation. The content is in your history — check previous tool results instead of re-reading."
            }
            readFiles.add(filePath)
        }
    }

    // Doom loop: last 3 calls identical
    if (recentToolCalls.size >= DOOM_LOOP_THRESHOLD) {
        val lastN = recentToolCalls.takeLast(DOOM_LOOP_THRESHOLD)
        if (lastN.distinct().size == 1) {
            recentToolCalls.clear()
            return "You have called $toolName with the same arguments $DOOM_LOOP_THRESHOLD times in a row. Try a different approach or summarize your findings."
        }
    }

    // Keep list bounded
    if (recentToolCalls.size > 20) {
        recentToolCalls.removeAt(0)
    }

    return null
}

/**
 * Clear file read tracking when a file is edited (agent may need to re-read after edit).
 */
fun clearFileRead(filePath: String) {
    readFiles.remove(filePath)
}
```

- [ ] **Step 2: Wire doom loop check in SingleAgentSession tool execution**

In the tool execution section of `SingleAgentSession`, BEFORE executing each tool:

```kotlin
// Doom loop detection
val doomMessage = loopGuard.checkDoomLoop(toolName, toolCall.function.arguments)
if (doomMessage != null) {
    contextManager.addMessage(ChatMessage(role = "system", content = "<system_warning>$doomMessage</system_warning>"))
}
```

And AFTER an edit_file succeeds:
```kotlin
if (toolName == "edit_file") {
    val pathMatch = Regex(""""path"\s*:\s*"([^"]+)"""").find(toolCall.function.arguments)
    pathMatch?.groupValues?.get(1)?.let { loopGuard.clearFileRead(it) }
}
```

- [ ] **Step 3: Compile and test**

```bash
./gradlew :agent:test --tests "*.LoopGuardTest" --tests "*.SingleAgentSessionTest" -x verifyPlugin
```

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/LoopGuard.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt
git commit -m "feat(agent): OpenCode doom loop detection + file re-read tracking

3 identical tool calls = doom loop warning injected. File re-read detection
warns agent when reading a file it already has in context. Edit clears the
read tracking for that file (may need re-read after modification)."
```

---

## Task 6: Retry Robustness

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt`

- [ ] **Step 1: Add 5xx retry + exponential backoff with jitter**

In `callLlmWithRetry()`, replace the fixed backoff array:

```kotlin
// OLD:
private val RATE_LIMIT_BACKOFF_MS = longArrayOf(2000, 4000, 8000)
private const val MAX_RATE_LIMIT_RETRIES = 3

// NEW: Exponential backoff with jitter, more retries, 5xx support
private const val MAX_RETRIES = 5
private const val BASE_BACKOFF_MS = 1000L
private const val MAX_BACKOFF_MS = 30000L
```

In the retry loop:
```kotlin
for (attempt in 1..MAX_RETRIES) {
    // ... make API call ...

    when {
        result.isRateLimited() || result.isServerError() -> {
            val backoff = minOf(BASE_BACKOFF_MS * (1L shl (attempt - 1)), MAX_BACKOFF_MS)
            val jitter = (backoff * 0.5 * kotlin.random.Random.nextDouble()).toLong()
            val delay = backoff + jitter
            LOG.info("Retry $attempt/$MAX_RETRIES after ${delay}ms (${if (result.isRateLimited()) "rate limited" else "server error"})")
            kotlinx.coroutines.delay(delay)
            continue
        }
        result.isContextExceeded() -> {
            return LlmCallResult.ContextExceededRetry(...)
        }
        else -> break // success or non-retryable error
    }
}
```

Add `isServerError()` check to the error mapping in SourcegraphChatClient or handle 5xx status codes.

- [ ] **Step 2: Compile and test**

```bash
./gradlew :agent:test --tests "*.SingleAgentSessionTest" -x verifyPlugin
```

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt
git commit -m "fix(agent): exponential backoff with jitter + 5xx retry

5 retries (was 3), exponential backoff with random jitter (was fixed 2/4/8s),
5xx server errors now retried (was only 429). Max backoff 30s."
```

---

## Task 7: Update Tests + Documentation

**Files:**
- Modify: `agent/CLAUDE.md`
- Various test files

- [ ] **Step 1: Run ALL agent tests**

```bash
./gradlew :agent:test --rerun --no-build-cache -x verifyPlugin
```

Fix any failures.

- [ ] **Step 2: Update CLAUDE.md**

Update key sections to reflect the new architecture:

```markdown
## Context Management
- **Tool results**: Full content in context (2000 lines / 50KB cap). Full saved to disk via ToolOutputStore.
  No premature compression — LLM sees everything on first read.
- **Phase 1 compression**: Prune old tool results (protect last 30K tokens)
- **Phase 2 compression**: Structured LLM summary (Goal/Discoveries/Accomplished/Files template)
- **Compression trigger**: 85% of effective budget
- **System messages**: Old LoopGuard/budget warnings compressible (only original system prompt protected)

## Tool Execution
- **Read-only tools**: Execute in parallel (read_file, search_code, diagnostics, etc.)
- **Write tools**: Execute sequentially (edit_file, run_command)
- **Doom loop detection**: 3 identical tool calls = warning injected
- **File re-read detection**: Warns when reading a file already in context
- **Context overflow**: Compress + REPLAY the failed request (OpenCode pattern)

## Error Handling
- **API retry**: 5 attempts, exponential backoff with jitter, retries on 429 AND 5xx
- **Context overflow**: Phase 1 prune + Phase 2 compress + replay
- **Streaming**: Heuristic token estimate when usage is null
```

- [ ] **Step 3: Commit**

```bash
git add agent/CLAUDE.md agent/src/test/
git commit -m "test+docs: update for pipeline v2 — OpenCode patterns throughout"
```

---

## Verification

```bash
./gradlew :agent:test --rerun --no-daemon
./gradlew verifyPlugin
```

Manual testing (the SAME scenario that exposed the bugs):
1. "How does authentication work?" → verify LLM sees FULL file content, not truncated
2. "Can you explain via a graph?" → verify multi-turn works, context preserved
3. "Disable authentication temporarily" → verify completes in <10 iterations (was 17)
4. "Re-enable and disable for specific SP" → verify no re-exploration, uses prior context
5. Token widget → verify shows reasonable context fill (15-30K), not cumulative (369K)
6. Kill a running operation → verify mid-loop cancellation works
7. Long task with many iterations → verify doom loop detection kicks in
