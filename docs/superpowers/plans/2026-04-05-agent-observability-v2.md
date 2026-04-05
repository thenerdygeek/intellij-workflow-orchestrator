# Agent Observability v2 — Structured Logging, Metrics, and Debug Wiring

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fill the observability gaps: dedicated structured JSONL agent log file, per-tool execution metrics, session-level metrics aggregation, API latency tracking, credential sanitization in debug dumps, and wiring the existing debug panel to live agent events.

**Architecture:** Two layers — (1) `AgentFileLogger`: a dedicated JSONL file logger that writes structured events to `~/.workflow-orchestrator/{project}/logs/agent-YYYY-MM-DD.jsonl` with daily rotation and 7-day retention, always on. (2) `SessionMetrics`: a lightweight in-memory metrics accumulator per session that tracks tool durations, API latencies, success/failure counts, and compaction events — persisted alongside Session metadata. The existing debug panel UI (already built) gets wired to receive live events from the AgentLoop via a new `onDebugLog` callback.

**Tech Stack:** Kotlin, kotlinx-serialization, IntelliJ Platform SDK, ProjectIdentifier for storage paths

---

## File Map

| File | Action | Purpose |
|------|--------|---------|
| `agent/.../observability/AgentFileLogger.kt` | **CREATE** | Structured JSONL file logger with rotation + retention |
| `agent/.../observability/SessionMetrics.kt` | **CREATE** | Per-session metrics accumulator (tool times, API latency, counts) |
| `agent/.../loop/AgentLoop.kt` | MODIFY | Add `onDebugLog` callback, emit events at key points |
| `agent/.../AgentService.kt` | MODIFY | Instantiate logger + metrics, wire callbacks, persist metrics |
| `agent/.../session/Session.kt` | MODIFY | Add `metrics` field for persisted session metrics |
| `agent/.../session/SessionStore.kt` | MODIFY | Persist metrics alongside session |
| `core/.../ai/SourcegraphChatClient.kt` | MODIFY | Sanitize tool arguments in API debug dumps |
| `agent/test/.../observability/AgentFileLoggerTest.kt` | **CREATE** | Tests for log writing, rotation, retention, format |
| `agent/test/.../observability/SessionMetricsTest.kt` | **CREATE** | Tests for metrics accumulation and serialization |

---

## Task 1: Create AgentFileLogger

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/observability/AgentFileLogger.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/observability/AgentFileLoggerTest.kt`

Structured JSONL logger writing to `~/.workflow-orchestrator/{project}/logs/agent-YYYY-MM-DD.jsonl`. Uses `ProjectIdentifier.logsDir()` for the correct per-project path. Always on, async writes via coroutine scope, zero UI impact.

- [ ] **Step 1: Write failing test**

```kotlin
package com.workflow.orchestrator.agent.observability

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentFileLoggerTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `log file created with correct name on first write`() {
        val logger = AgentFileLogger(logDir = tempDir)
        logger.logToolCall(
            sessionId = "test-session-123",
            toolName = "edit_file",
            durationMs = 42,
            isError = false
        )
        logger.flush()

        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val logFile = File(tempDir, "agent-$today.jsonl")
        assertTrue(logFile.exists(), "Log file should be created")

        val line = logFile.readLines().single()
        assertTrue(line.contains("\"event\":\"tool_call\""))
        assertTrue(line.contains("\"tool\":\"edit_file\""))
        assertTrue(line.contains("\"durationMs\":42"))
    }

    @Test
    fun `session start and end events round-trip`() {
        val logger = AgentFileLogger(logDir = tempDir)
        logger.logSessionStart("s1", "Fix the bug", toolCount = 15)
        logger.logSessionEnd("s1", iterations = 5, totalTokens = 12000, durationMs = 30000)
        logger.flush()

        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val lines = File(tempDir, "agent-$today.jsonl").readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("\"event\":\"session_start\""))
        assertTrue(lines[1].contains("\"event\":\"session_end\""))
    }

    @Test
    fun `old logs beyond 7 days are cleaned`() {
        // Create a fake old log file
        val oldDate = LocalDate.now().minusDays(10).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val oldFile = File(tempDir, "agent-$oldDate.jsonl")
        oldFile.writeText("{}")

        val recentDate = LocalDate.now().minusDays(3).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val recentFile = File(tempDir, "agent-$recentDate.jsonl")
        recentFile.writeText("{}")

        val logger = AgentFileLogger(logDir = tempDir)
        logger.cleanOldLogs()

        assertTrue(!oldFile.exists(), "Old file should be deleted")
        assertTrue(recentFile.exists(), "Recent file should be kept")
    }

    @Test
    fun `compression event includes token counts`() {
        val logger = AgentFileLogger(logDir = tempDir)
        logger.logCompaction("s1", trigger = "utilization_85pct", tokensBefore = 150000, tokensAfter = 80000)
        logger.flush()

        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val line = File(tempDir, "agent-$today.jsonl").readLines().single()
        assertTrue(line.contains("\"event\":\"compaction\""))
        assertTrue(line.contains("\"tokensBefore\":150000"))
        assertTrue(line.contains("\"tokensAfter\":80000"))
    }

    @Test
    fun `api call event includes latency`() {
        val logger = AgentFileLogger(logDir = tempDir)
        logger.logApiCall("s1", latencyMs = 2500, promptTokens = 50000, completionTokens = 1200, finishReason = "stop")
        logger.flush()

        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val line = File(tempDir, "agent-$today.jsonl").readLines().single()
        assertTrue(line.contains("\"event\":\"api_call\""))
        assertTrue(line.contains("\"latencyMs\":2500"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin-agent-rewrite && ./gradlew :agent:test --tests "com.workflow.orchestrator.agent.observability.AgentFileLoggerTest" --no-build-cache`
Expected: FAIL — class not found

- [ ] **Step 3: Write implementation**

```kotlin
package com.workflow.orchestrator.agent.observability

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Dedicated structured agent log file — separate from idea.log.
 *
 * Writes JSONL to {logDir}/agent-YYYY-MM-DD.jsonl.
 * Daily rotation by filename, 7-day retention on startup, synchronous writes
 * (callers are already on IO dispatcher via AgentLoop's coroutine scope).
 *
 * Always active — no setting to disable. Zero performance impact
 * (one file append per event, autoflush).
 */
class AgentFileLogger(private val logDir: File) {

    private val log = Logger.getInstance(AgentFileLogger::class.java)
    private val json = Json { encodeDefaults = false; explicitNulls = false }

    @Volatile private var currentDate: LocalDate = LocalDate.now()
    @Volatile private var writer: PrintWriter? = null

    init {
        logDir.mkdirs()
        cleanOldLogs()
    }

    fun logToolCall(
        sessionId: String,
        toolName: String,
        durationMs: Long,
        isError: Boolean,
        args: String? = null,
        errorMessage: String? = null,
        tokenEstimate: Int? = null
    ) {
        write(LogEntry(
            ts = Instant.now().toString(),
            session = sessionId.take(11),
            event = "tool_call",
            tool = toolName,
            status = if (isError) "error" else "ok",
            args = args?.take(500),
            error = errorMessage?.take(500),
            durationMs = durationMs,
            tokens = tokenEstimate
        ))
    }

    fun logApiCall(
        sessionId: String,
        latencyMs: Long,
        promptTokens: Int,
        completionTokens: Int,
        finishReason: String?
    ) {
        write(LogEntry(
            ts = Instant.now().toString(),
            session = sessionId.take(11),
            event = "api_call",
            latencyMs = latencyMs,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            finishReason = finishReason
        ))
    }

    fun logIteration(
        sessionId: String,
        iteration: Int,
        toolsCalled: List<String>,
        durationMs: Long
    ) {
        write(LogEntry(
            ts = Instant.now().toString(),
            session = sessionId.take(11),
            event = "iteration",
            iteration = iteration,
            toolsCalled = toolsCalled.ifEmpty { null },
            durationMs = durationMs
        ))
    }

    fun logCompaction(
        sessionId: String,
        trigger: String,
        tokensBefore: Int,
        tokensAfter: Int
    ) {
        write(LogEntry(
            ts = Instant.now().toString(),
            session = sessionId.take(11),
            event = "compaction",
            result = trigger,
            tokensBefore = tokensBefore,
            tokensAfter = tokensAfter
        ))
    }

    fun logRetry(sessionId: String, reason: String, iteration: Int) {
        write(LogEntry(
            ts = Instant.now().toString(),
            session = sessionId.take(11),
            event = "retry",
            error = reason,
            iteration = iteration
        ))
    }

    fun logSessionStart(sessionId: String, task: String, toolCount: Int) {
        write(LogEntry(
            ts = Instant.now().toString(),
            session = sessionId.take(11),
            event = "session_start",
            result = task.take(200),
            tokens = toolCount
        ))
    }

    fun logSessionEnd(
        sessionId: String,
        iterations: Int,
        totalTokens: Int,
        durationMs: Long,
        error: String? = null
    ) {
        write(LogEntry(
            ts = Instant.now().toString(),
            session = sessionId.take(11),
            event = if (error != null) "session_failed" else "session_end",
            iteration = iterations,
            tokens = totalTokens,
            durationMs = durationMs,
            error = error?.take(500)
        ))
    }

    fun logLoopDetection(sessionId: String, toolName: String, count: Int, isHard: Boolean) {
        write(LogEntry(
            ts = Instant.now().toString(),
            session = sessionId.take(11),
            event = if (isHard) "loop_hard" else "loop_soft",
            tool = toolName,
            iteration = count
        ))
    }

    private fun write(entry: LogEntry) {
        try {
            val line = json.encodeToString(entry)
            getWriter().println(line)
        } catch (e: Exception) {
            log.debug("AgentFileLogger write failed: ${e.message}")
        }
    }

    /** Flush the underlying writer. Useful for tests and shutdown. */
    fun flush() {
        writer?.flush()
    }

    @Synchronized
    private fun getWriter(): PrintWriter {
        val today = LocalDate.now()
        if (today != currentDate || writer == null) {
            writer?.close()
            currentDate = today
            val fileName = "agent-${today.format(DateTimeFormatter.ISO_LOCAL_DATE)}.jsonl"
            writer = PrintWriter(FileWriter(File(logDir, fileName), true), true)
        }
        return writer!!
    }

    fun cleanOldLogs() {
        try {
            val cutoff = LocalDate.now().minusDays(7)
            logDir.listFiles()
                ?.filter { it.name.startsWith("agent-") && it.extension == "jsonl" }
                ?.forEach { file ->
                    val dateStr = file.nameWithoutExtension.removePrefix("agent-")
                    try {
                        if (LocalDate.parse(dateStr).isBefore(cutoff)) file.delete()
                    } catch (_: Exception) { /* skip unparseable filenames */ }
                }
        } catch (_: Exception) { /* non-fatal */ }
    }

    fun close() {
        writer?.close()
        writer = null
    }

    @Serializable
    private data class LogEntry(
        val ts: String,
        val session: String,
        val event: String,
        val tool: String? = null,
        val status: String? = null,
        val args: String? = null,
        val result: String? = null,
        val error: String? = null,
        val durationMs: Long? = null,
        val latencyMs: Long? = null,
        val tokens: Int? = null,
        val iteration: Int? = null,
        val promptTokens: Int? = null,
        val completionTokens: Int? = null,
        val tokensBefore: Int? = null,
        val tokensAfter: Int? = null,
        val finishReason: String? = null,
        val toolsCalled: List<String>? = null
    )
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin-agent-rewrite && ./gradlew :agent:test --tests "com.workflow.orchestrator.agent.observability.AgentFileLoggerTest" --no-build-cache`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/observability/AgentFileLogger.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/observability/AgentFileLoggerTest.kt
git commit -m "feat(observability): add AgentFileLogger — dedicated JSONL agent log with rotation"
```

---

## Task 2: Create SessionMetrics accumulator

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/observability/SessionMetrics.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/observability/SessionMetricsTest.kt`

Lightweight in-memory accumulator that tracks per-tool execution times, API latencies, success/failure counts, and compaction events for a single session. Serializable so it can be persisted alongside Session metadata.

- [ ] **Step 1: Write failing test**

```kotlin
package com.workflow.orchestrator.agent.observability

import org.junit.jupiter.api.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionMetricsTest {

    @Test
    fun `records tool execution and computes stats`() {
        val metrics = SessionMetrics()
        metrics.recordToolCall("edit_file", durationMs = 100, isError = false)
        metrics.recordToolCall("edit_file", durationMs = 200, isError = false)
        metrics.recordToolCall("edit_file", durationMs = 300, isError = true)
        metrics.recordToolCall("run_command", durationMs = 5000, isError = false)

        val snapshot = metrics.snapshot()
        assertEquals(4, snapshot.totalToolCalls)
        assertEquals(1, snapshot.failedToolCalls)
        assertEquals(3, snapshot.toolStats.size) // edit_file(ok), edit_file(error), run_command(ok) — or 2 tools
        // edit_file: 3 calls, avg 200ms
        val editStats = snapshot.toolStats["edit_file"]!!
        assertEquals(3, editStats.count)
        assertEquals(200, editStats.avgMs)
        assertEquals(100, editStats.minMs)
        assertEquals(300, editStats.maxMs)
    }

    @Test
    fun `records API latency`() {
        val metrics = SessionMetrics()
        metrics.recordApiCall(latencyMs = 2000, promptTokens = 50000, completionTokens = 1000)
        metrics.recordApiCall(latencyMs = 3000, promptTokens = 60000, completionTokens = 1500)

        val snapshot = metrics.snapshot()
        assertEquals(2, snapshot.apiCalls)
        assertEquals(2500, snapshot.avgApiLatencyMs)
        assertEquals(110000, snapshot.totalPromptTokens)
        assertEquals(2500, snapshot.totalCompletionTokens)
    }

    @Test
    fun `records compaction events`() {
        val metrics = SessionMetrics()
        metrics.recordCompaction(tokensBefore = 150000, tokensAfter = 80000)

        val snapshot = metrics.snapshot()
        assertEquals(1, snapshot.compactionCount)
    }

    @Test
    fun `snapshot is serializable`() {
        val metrics = SessionMetrics()
        metrics.recordToolCall("edit_file", 100, false)
        val snapshot = metrics.snapshot()
        val json = Json.encodeToString(snapshot)
        assertTrue(json.contains("totalToolCalls"))
    }

    @Test
    fun `empty metrics produce zero snapshot`() {
        val snapshot = SessionMetrics().snapshot()
        assertEquals(0, snapshot.totalToolCalls)
        assertEquals(0, snapshot.apiCalls)
        assertEquals(0, snapshot.compactionCount)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin-agent-rewrite && ./gradlew :agent:test --tests "com.workflow.orchestrator.agent.observability.SessionMetricsTest" --no-build-cache`
Expected: FAIL — class not found

- [ ] **Step 3: Write implementation**

```kotlin
package com.workflow.orchestrator.agent.observability

import kotlinx.serialization.Serializable

/**
 * Per-session metrics accumulator. Thread-safe via @Synchronized.
 * Tracks tool execution times, API latencies, token usage, and compaction events.
 * Call [snapshot] to get a serializable summary for persistence.
 */
class SessionMetrics {

    private val toolCalls = mutableListOf<ToolCallRecord>()
    private val apiCalls = mutableListOf<ApiCallRecord>()
    private var compactions = 0
    private var totalPrompt = 0
    private var totalCompletion = 0

    @Synchronized
    fun recordToolCall(toolName: String, durationMs: Long, isError: Boolean) {
        toolCalls.add(ToolCallRecord(toolName, durationMs, isError))
    }

    @Synchronized
    fun recordApiCall(latencyMs: Long, promptTokens: Int, completionTokens: Int) {
        apiCalls.add(ApiCallRecord(latencyMs))
        totalPrompt += promptTokens
        totalCompletion += completionTokens
    }

    @Synchronized
    fun recordCompaction(tokensBefore: Int, tokensAfter: Int) {
        compactions++
    }

    @Synchronized
    fun snapshot(): MetricsSnapshot {
        val toolStats = toolCalls.groupBy { it.toolName }.mapValues { (_, calls) ->
            ToolStats(
                count = calls.size,
                avgMs = if (calls.isEmpty()) 0 else (calls.sumOf { it.durationMs } / calls.size),
                minMs = calls.minOfOrNull { it.durationMs } ?: 0,
                maxMs = calls.maxOfOrNull { it.durationMs } ?: 0,
                errors = calls.count { it.isError }
            )
        }
        val avgApiLatency = if (apiCalls.isEmpty()) 0 else (apiCalls.sumOf { it.latencyMs } / apiCalls.size)

        return MetricsSnapshot(
            totalToolCalls = toolCalls.size,
            failedToolCalls = toolCalls.count { it.isError },
            toolStats = toolStats,
            apiCalls = apiCalls.size,
            avgApiLatencyMs = avgApiLatency,
            totalPromptTokens = totalPrompt,
            totalCompletionTokens = totalCompletion,
            compactionCount = compactions
        )
    }

    private data class ToolCallRecord(val toolName: String, val durationMs: Long, val isError: Boolean)
    private data class ApiCallRecord(val latencyMs: Long)

    @Serializable
    data class ToolStats(
        val count: Int,
        val avgMs: Long,
        val minMs: Long,
        val maxMs: Long,
        val errors: Int
    )

    @Serializable
    data class MetricsSnapshot(
        val totalToolCalls: Int = 0,
        val failedToolCalls: Int = 0,
        val toolStats: Map<String, ToolStats> = emptyMap(),
        val apiCalls: Int = 0,
        val avgApiLatencyMs: Long = 0,
        val totalPromptTokens: Int = 0,
        val totalCompletionTokens: Int = 0,
        val compactionCount: Int = 0
    )
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin-agent-rewrite && ./gradlew :agent:test --tests "com.workflow.orchestrator.agent.observability.SessionMetricsTest" --no-build-cache`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/observability/SessionMetrics.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/observability/SessionMetricsTest.kt
git commit -m "feat(observability): add SessionMetrics accumulator for per-session tool and API stats"
```

---

## Task 3: Add `onDebugLog` callback to AgentLoop

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt`

Add a new callback parameter and emit debug events at all key points in the loop. This bridges the existing debug panel UI (already built in `DebugPanel.tsx`) with live agent events.

- [ ] **Step 1: Add `onDebugLog` and `fileLogger` and `sessionMetrics` parameters to AgentLoop constructor**

In `AgentLoop.kt`, add after the `userInputChannel` parameter (line ~149):

```kotlin
    /**
     * Optional callback for real-time debug log entries.
     * Pushed to the JCEF debug panel when showDebugLog setting is enabled.
     * Parameters: level ("info"/"warn"/"error"), event tag, detail string, optional metadata map.
     */
    private val onDebugLog: ((level: String, event: String, detail: String, meta: Map<String, Any?>?) -> Unit)? = null,
    /**
     * Optional file logger for structured JSONL agent logs.
     * Always active when provided (instantiated by AgentService per project).
     */
    private val fileLogger: AgentFileLogger? = null,
    /**
     * Optional per-session metrics accumulator.
     * Records tool durations, API latencies, success/failure counts.
     */
    private val sessionMetrics: SessionMetrics? = null
```

Add the import at the top of the file:
```kotlin
import com.workflow.orchestrator.agent.observability.AgentFileLogger
import com.workflow.orchestrator.agent.observability.SessionMetrics
```

- [ ] **Step 2: Emit events in the main `run()` method**

In `run()`, after the API call succeeds (after line ~351 where `onTokenUpdate` is called), add API call logging:

```kotlin
// After onTokenUpdate?.invoke(totalInputTokens, totalOutputTokens)
val apiLatencyMs = System.currentTimeMillis() - iterationStartTime
fileLogger?.logApiCall(sessionId ?: "", apiLatencyMs, usage.promptTokens, usage.completionTokens, choice?.finishReason)
sessionMetrics?.recordApiCall(apiLatencyMs, usage.promptTokens, usage.completionTokens)
onDebugLog?.invoke("info", "api_call", "API: ${usage.promptTokens}p + ${usage.completionTokens}c tokens, ${apiLatencyMs}ms",
    mapOf("latencyMs" to apiLatencyMs, "promptTokens" to usage.promptTokens, "completionTokens" to usage.completionTokens))
```

Add `val iterationStartTime = System.currentTimeMillis()` at the start of each iteration (right after `iteration++` on line ~279).

In the compaction block (line ~283-286), add:

```kotlin
// After contextManager.compact(brain)
val utilBefore = contextManager.utilizationPercent()  // capture before compact
// ... existing compact call ...
val utilAfter = contextManager.utilizationPercent()
fileLogger?.logCompaction(sessionId ?: "", "utilization_threshold", (utilBefore * 1000).toInt(), (utilAfter * 1000).toInt())
sessionMetrics?.recordCompaction((utilBefore * 1000).toInt(), (utilAfter * 1000).toInt())
onDebugLog?.invoke("warn", "compaction", "Compacted: ${"%.1f".format(utilBefore)}% → ${"%.1f".format(utilAfter)}%",
    mapOf("before" to utilBefore, "after" to utilAfter))
```

Note: The token counts for compaction need to be captured before/after. Since `compact()` internally calls `invalidateTokens()`, capture `tokenEstimate()` before and after.

In the context overflow retry block (line ~303-309), add:

```kotlin
fileLogger?.logRetry(sessionId ?: "", "context_overflow", iteration)
onDebugLog?.invoke("warn", "retry", "Context overflow, compacting and retrying ($contextOverflowRetries/$MAX_CONTEXT_OVERFLOW_RETRIES)", null)
```

In the API retry block (line ~312-318), add:

```kotlin
fileLogger?.logRetry(sessionId ?: "", "api_${apiResult.type.name.lowercase()}", iteration)
onDebugLog?.invoke("warn", "retry", "API retry $apiRetryCount/$MAX_API_RETRIES: ${apiResult.type}", mapOf("errorType" to apiResult.type.name))
```

- [ ] **Step 3: Emit events in `executeToolCalls()`**

After the existing tool completion logging (line ~741), add:

```kotlin
// After LOG.info/LOG.warn for tool completion
fileLogger?.logToolCall(
    sessionId = sessionId ?: "",
    toolName = toolName,
    durationMs = durationMs,
    isError = toolResult.isError,
    args = call.function.arguments.take(500),
    errorMessage = if (toolResult.isError) toolResult.content.take(500) else null,
    tokenEstimate = toolResult.tokenEstimate
)
sessionMetrics?.recordToolCall(toolName, durationMs, toolResult.isError)
onDebugLog?.invoke(
    if (toolResult.isError) "error" else "info",
    "tool_call",
    "$toolName ${if (toolResult.isError) "ERROR" else "OK"} (${durationMs}ms)",
    mapOf("tool" to toolName, "duration" to durationMs, "tokens" to toolResult.tokenEstimate,
          "error" to if (toolResult.isError) toolResult.content.take(200) else null)
)
```

In the loop detection blocks (SOFT_WARNING at ~556, HARD_LIMIT at ~526), add:

```kotlin
// SOFT_WARNING:
fileLogger?.logLoopDetection(sessionId ?: "", toolName, loopDetector.currentCount, isHard = false)
onDebugLog?.invoke("warn", "loop", "Loop warning: $toolName called ${loopDetector.currentCount}x consecutively", null)

// HARD_LIMIT:
fileLogger?.logLoopDetection(sessionId ?: "", toolName, loopDetector.currentCount, isHard = true)
onDebugLog?.invoke("error", "loop", "Loop HARD limit: $toolName called ${loopDetector.currentCount}x — aborting", null)
```

- [ ] **Step 4: Compile**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin-agent-rewrite && ./gradlew :agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Run existing tests**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin-agent-rewrite && ./gradlew :agent:test --no-build-cache`
Expected: ALL PASS (new params have defaults, no existing tests break)

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt
git commit -m "feat(observability): emit structured events from AgentLoop — file logger, metrics, debug panel"
```

---

## Task 4: Wire logger, metrics, and debug callback in AgentService

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt`

Instantiate `AgentFileLogger` once per project (lazy singleton), create `SessionMetrics` per task execution, wire the `onDebugLog` callback to the dashboard's `pushDebugLogEntry`, and log session start/end.

- [ ] **Step 1: Add logger as a lazy property and import observability classes**

At the top of `AgentService`, add imports:

```kotlin
import com.workflow.orchestrator.agent.observability.AgentFileLogger
import com.workflow.orchestrator.agent.observability.SessionMetrics
import com.workflow.orchestrator.core.util.ProjectIdentifier
```

Add a lazy property after the existing service-level fields:

```kotlin
/** Dedicated structured agent log file — one per project, lives for plugin lifetime. */
private val fileLogger: AgentFileLogger by lazy {
    AgentFileLogger(logDir = ProjectIdentifier.logsDir(project.basePath ?: ""))
}
```

- [ ] **Step 2: Wire into `executeTask()`**

Inside the `executeTask` coroutine (around line ~462), after session creation (line ~460):

```kotlin
val sessionMetrics = SessionMetrics()
val sessionStartTime = System.currentTimeMillis()
fileLogger.logSessionStart(sid, task, toolDefs.size)
```

When constructing `AgentLoop` (line ~590), add the new parameters:

```kotlin
val loop = AgentLoop(
    // ... existing parameters ...
    onDebugLog = if (agentSettings.state.showDebugLog) { level, event, detail, meta ->
        dashboard?.pushDebugLogEntry(level, event, detail, meta)
    } else null,
    fileLogger = fileLogger,
    sessionMetrics = sessionMetrics
)
```

Note: `dashboard` is the `AgentDashboardPanel` reference. Find how the current code references it — it's likely accessed via the `onToolCall` or `onStreamChunk` callback closures. The `AgentService` creates callbacks that reference the UI panel. Look for how `AgentDashboardPanel` is accessed and use that same reference.

After the loop completes and session is saved (around line ~713), add session end logging:

```kotlin
val sessionDurationMs = System.currentTimeMillis() - sessionStartTime
fileLogger.logSessionEnd(
    sessionId = sid,
    iterations = when (result) {
        is LoopResult.Completed -> result.iterations
        is LoopResult.Failed -> result.iterations
        is LoopResult.Cancelled -> result.iterations
        is LoopResult.SessionHandoff -> result.iterations
    },
    totalTokens = tokensUsed,
    durationMs = sessionDurationMs,
    error = if (result is LoopResult.Failed) result.error else null
)
```

In the `catch (e: Exception)` block (line ~744), add:

```kotlin
fileLogger.logSessionEnd(sid, iterations = 0, totalTokens = 0,
    durationMs = System.currentTimeMillis() - sessionStartTime, error = e.message)
```

- [ ] **Step 3: Find dashboard reference**

The `AgentService` likely doesn't hold a direct reference to `AgentDashboardPanel`. The UI wiring happens in whoever calls `executeTask()` — typically `AgentController`. Check how `onToolCall` callback reaches the dashboard. The `onDebugLog` callback should be passed as a parameter to `executeTask()` rather than wired internally.

**Alternative approach** — add `onDebugLog` as a parameter to `executeTask()`:

```kotlin
fun executeTask(
    // ... existing params ...
    onDebugLog: ((level: String, event: String, detail: String, meta: Map<String, Any?>?) -> Unit)? = null
): Job {
```

Then pass it through to `AgentLoop`. The caller (typically `AgentController`) wires it to `dashboard.pushDebugLogEntry()`.

- [ ] **Step 4: Compile**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin-agent-rewrite && ./gradlew :agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Run tests**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin-agent-rewrite && ./gradlew :agent:test --no-build-cache`
Expected: ALL PASS

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt
git commit -m "feat(observability): wire AgentFileLogger and SessionMetrics into AgentService"
```

---

## Task 5: Wire `onDebugLog` from AgentController to dashboard

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt` (or wherever `executeTask` is called from)

The existing `AgentDashboardPanel.pushDebugLogEntry()` and `AgentCefPanel.pushDebugLogEntry()` are already implemented but never called from the agent runtime. Wire them.

- [ ] **Step 1: Find where `executeTask` is called**

Search for `executeTask(` in the agent UI code. This is likely in `AgentController.kt` or similar. The caller constructs all the callbacks (`onStreamChunk`, `onToolCall`, etc.) and needs to add:

```kotlin
onDebugLog = if (AgentSettings.getInstance(project).state.showDebugLog) { level, event, detail, meta ->
    dashboard.pushDebugLogEntry(level, event, detail, meta)
} else null
```

- [ ] **Step 2: Also push session start/end events to the debug panel**

In the same caller, before calling `executeTask`:

```kotlin
if (AgentSettings.getInstance(project).state.showDebugLog) {
    dashboard.pushDebugLogEntry("info", "session", "Starting task: ${task.take(100)}", null)
}
```

And in the `onComplete` callback:

```kotlin
if (AgentSettings.getInstance(project).state.showDebugLog) {
    val statusStr = when (result) {
        is LoopResult.Completed -> "completed"
        is LoopResult.Failed -> "failed: ${result.error.take(100)}"
        is LoopResult.Cancelled -> "cancelled"
        is LoopResult.SessionHandoff -> "handoff"
    }
    dashboard.pushDebugLogEntry(
        if (result is LoopResult.Failed) "error" else "info",
        "session", "Session $statusStr", null
    )
}
```

- [ ] **Step 3: Compile and test**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin-agent-rewrite && ./gradlew :agent:compileKotlin && ./gradlew :agent:test --no-build-cache`
Expected: BUILD SUCCESSFUL, ALL PASS

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt
git commit -m "feat(observability): wire debug panel to live agent events via onDebugLog callback"
```

---

## Task 6: Persist SessionMetrics in Session metadata

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/session/Session.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt`

Add a `metrics` field to `Session` so tool stats and API latencies are persisted and available in the history panel.

- [ ] **Step 1: Add metrics field to Session**

In `Session.kt`, add after `lastToolCallId`:

```kotlin
    /** Per-session execution metrics (tool times, API latency, compaction count). Null for pre-v2 sessions. */
    val metrics: com.workflow.orchestrator.agent.observability.SessionMetrics.MetricsSnapshot? = null
```

Add the import at the top if needed. Since `MetricsSnapshot` is `@Serializable` and `Session` is `@Serializable`, kotlinx-serialization handles this automatically.

- [ ] **Step 2: Persist metrics snapshot when session ends**

In `AgentService.executeTask()`, where the final session is saved (around line ~700-713), add:

```kotlin
session = session.copy(
    // ... existing fields ...
    metrics = sessionMetrics.snapshot()
)
```

- [ ] **Step 3: Run tests**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin-agent-rewrite && ./gradlew :agent:test --no-build-cache`
Expected: ALL PASS (Session is @Serializable, new field has default null, backward compatible)

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/session/Session.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt
git commit -m "feat(observability): persist SessionMetrics snapshot in session metadata"
```

---

## Task 7: Sanitize credentials in API debug dumps

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/SourcegraphChatClient.kt`

The `dumpApiRequest()` method writes full tool arguments to disk. If a tool argument contains a password or token, it gets logged in plaintext. Add a sanitization pass.

- [ ] **Step 1: Add sanitization function**

In `SourcegraphChatClient.kt`, add a private method near the dump methods (around line ~605):

```kotlin
/**
 * Scrub likely credentials from tool argument strings before writing to debug files.
 * Matches common patterns: password, token, secret, api_key, auth, bearer.
 */
private fun sanitizeForDebug(text: String): String {
    return text.replace(
        Regex("""("(?:password|token|secret|api_key|apiKey|auth|bearer|credential)["\s]*[:=]\s*")([^"]{4,})""", RegexOption.IGNORE_CASE)
    ) { match ->
        "${match.groupValues[1]}***REDACTED***"
    }
}
```

- [ ] **Step 2: Apply sanitization in dumpApiRequest**

In `dumpApiRequest()` (line ~626), wrap the tool call arguments output:

Find the line that writes tool call arguments (likely `tc.function.arguments`) and wrap it:

```kotlin
// Change from:
sb.appendLine("    Arguments: ${tc.function.arguments}")
// To:
sb.appendLine("    Arguments: ${sanitizeForDebug(tc.function.arguments)}")
```

- [ ] **Step 3: Apply sanitization in dumpApiResponse**

In `dumpApiResponse()` (line ~668), do the same for response tool call arguments:

```kotlin
// Change from:
sb.appendLine("    Arguments: ${tc.function.arguments}")
// To:
sb.appendLine("    Arguments: ${sanitizeForDebug(tc.function.arguments)}")
```

- [ ] **Step 4: Compile and test**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin-agent-rewrite && ./gradlew :core:compileKotlin && ./gradlew :core:test --no-build-cache`
Expected: BUILD SUCCESSFUL, ALL PASS

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/ai/SourcegraphChatClient.kt
git commit -m "fix(security): sanitize credentials in API debug dump files"
```

---

## Task 8: Full verification

- [ ] **Step 1: Full compile**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin-agent-rewrite && ./gradlew :agent:compileKotlin :core:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run all agent + core tests**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin-agent-rewrite && ./gradlew :agent:test :core:test --no-build-cache`
Expected: ALL PASS

- [ ] **Step 3: Build plugin**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin-agent-rewrite && ./gradlew clean buildPlugin`
Expected: BUILD SUCCESSFUL, ZIP created

- [ ] **Step 4: Verify log directory exists after agent run**

After running the plugin in sandbox (`./gradlew runIde`) and executing a task:
- Check `~/.workflow-orchestrator/*/logs/agent-*.jsonl` exists
- Verify JSONL lines are valid JSON with expected fields
- Verify debug panel shows entries when `showDebugLog` is enabled in settings

- [ ] **Step 5: Final commit**

```bash
git commit -m "feat(observability): complete agent observability v2 — structured logging, metrics, debug wiring, credential sanitization"
```

---

## Summary of what this plan covers vs the original audit gaps

| Gap | Severity | Covered By |
|-----|----------|------------|
| No dedicated agent log file | HIGH | Task 1 (AgentFileLogger) |
| No structured logging | HIGH | Task 1 (JSONL with typed fields) |
| No tool execution timing | HIGH | Task 2 (SessionMetrics) + Task 3 (events) |
| No metrics aggregation | MEDIUM | Task 2 (SessionMetrics) + Task 6 (persistence) |
| No API latency tracking | MEDIUM | Task 2 + Task 3 (api_call event) |
| No success/failure rates | MEDIUM | Task 2 (SessionMetrics.toolStats.errors) |
| Weak credential sanitization | MEDIUM | Task 7 (regex scrubbing) |
| Debug panel unwired | MEDIUM | Task 3 + Task 5 (onDebugLog callback) |
| No diagnostic UI | LOW | Already built (DebugPanel.tsx), wired in Task 5 |

**Not in scope (deferred):** Distributed tracing (overkill for a plugin), OpenTelemetry integration, cross-session metrics rollup dashboard. These can be added later if needed.
