# Agent Observability — Three-Tier Logging & Debug Panel

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add structured observability to the AI agent: dedicated log file, inline error details in chat UI, and an opt-in debug log panel for power users.

**Architecture:** Three tiers — (1) a dedicated rolling JSONL agent log file separate from `idea.log`, (2) enhanced inline error display in the existing chat UI by fixing the tool error status push, and (3) an opt-in debug panel toggled via settings that shows real-time agent activity. Tier 1 is always on (zero UI cost). Tier 2 fixes an existing bug (tool errors show as COMPLETED). Tier 3 is behind a settings toggle — non-power users never see it.

**Tech Stack:** Kotlin (agent runtime), TypeScript/React (JCEF chat UI), JSONL (log format), IntelliJ PasswordSafe-adjacent settings

---

## File Map

| File | Changes |
|------|---------|
| `agent/src/main/kotlin/.../runtime/AgentFileLogger.kt` | **NEW:** Dedicated JSONL file logger with daily rotation |
| `agent/src/main/kotlin/.../runtime/SingleAgentSession.kt` | Wire AgentFileLogger, enhance tool error reporting |
| `agent/src/main/kotlin/.../ui/AgentCefPanel.kt` | Fix `updateLastToolCall` to pass error status |
| `agent/src/main/kotlin/.../ui/AgentController.kt` | Pass isError through to dashboard |
| `agent/src/main/kotlin/.../settings/AgentSettings.kt` | Add `showDebugLog` setting |
| `agent/src/main/kotlin/.../ui/AgentSettingsConfigurable.kt` | Add debug log checkbox to settings UI |
| `agent/webview/src/bridge/jcef-bridge.ts` | Fix `updateToolResult` to pass status, add debug log bridge functions |
| `agent/webview/src/stores/chatStore.ts` | Add debug log entries store, fix updateToolCall to use actual status |
| `agent/webview/src/components/chat/DebugPanel.tsx` | **NEW:** Opt-in collapsible debug log panel |
| `agent/webview/src/components/chat/TopBar.tsx` | Add debug toggle button (only visible when setting enabled) |
| `agent/webview/src/components/agent/ToolCallChain.tsx` | Show error styling for ERROR status tool calls |
| `agent/webview/src/App.tsx` | Wire DebugPanel between ChatView and InputBar |

---

## Part 1: Dedicated Agent Log File (Tier 1)

### Task 1: Create AgentFileLogger

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/AgentFileLogger.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/AgentFileLoggerTest.kt`

A structured JSONL logger that writes to `~/.workflow-orchestrator/agent/logs/agent-YYYY-MM-DD.jsonl`. Always on, no UI impact.

- [ ] **Step 1:** Create `AgentFileLogger.kt`:

```kotlin
package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.*
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
 * Writes JSONL to ~/.workflow-orchestrator/agent/logs/agent-YYYY-MM-DD.jsonl
 * Daily rotation, 7-day retention, async writes to avoid blocking EDT.
 * Always active — no setting to disable (zero performance impact).
 */
class AgentFileLogger(parentDisposable: Disposable) : Disposable {

    private val log = Logger.getInstance(AgentFileLogger::class.java)
    private val json = Json { encodeDefaults = false; explicitNulls = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logDir = File(System.getProperty("user.home"), ".workflow-orchestrator/agent/logs")

    @Volatile private var currentDate: LocalDate = LocalDate.now()
    @Volatile private var writer: PrintWriter? = null

    init {
        com.intellij.openapi.util.Disposer.register(parentDisposable, this)
        logDir.mkdirs()
        cleanOldLogs()
    }

    fun logToolCall(
        sessionId: String,
        toolName: String,
        args: String?,
        status: String, // "success", "error", "skipped"
        result: String?,
        errorMessage: String?,
        durationMs: Long,
        tokenEstimate: Int
    ) {
        write(AgentLogEntry(
            ts = Instant.now().toString(),
            session = sessionId.take(11),
            event = "tool_call",
            tool = toolName,
            status = status,
            args = args?.take(500),
            result = result?.take(300),
            error = errorMessage?.take(500),
            durationMs = durationMs,
            tokens = tokenEstimate
        ))
    }

    fun logIteration(
        sessionId: String,
        iteration: Int,
        promptTokens: Int,
        completionTokens: Int,
        finishReason: String?,
        toolsCalled: List<String>,
        durationMs: Long
    ) {
        write(AgentLogEntry(
            ts = Instant.now().toString(),
            session = sessionId.take(11),
            event = "iteration",
            iteration = iteration,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            finishReason = finishReason,
            toolsCalled = toolsCalled.ifEmpty { null },
            durationMs = durationMs
        ))
    }

    fun logRetry(sessionId: String, reason: String, iteration: Int) {
        write(AgentLogEntry(
            ts = Instant.now().toString(),
            session = sessionId.take(11),
            event = "retry",
            error = reason,
            iteration = iteration
        ))
    }

    fun logMalformedToolCall(sessionId: String, toolName: String, rawArgs: String, reason: String) {
        write(AgentLogEntry(
            ts = Instant.now().toString(),
            session = sessionId.take(11),
            event = "malformed_tool_call",
            tool = toolName,
            args = rawArgs.take(1000),
            error = reason
        ))
    }

    fun logSessionStart(sessionId: String, task: String, toolCount: Int) {
        write(AgentLogEntry(
            ts = Instant.now().toString(),
            session = sessionId.take(11),
            event = "session_start",
            result = task.take(200),
            tokens = toolCount
        ))
    }

    fun logSessionEnd(sessionId: String, iterations: Int, totalTokens: Int, durationMs: Long, error: String? = null) {
        write(AgentLogEntry(
            ts = Instant.now().toString(),
            session = sessionId.take(11),
            event = if (error != null) "session_failed" else "session_end",
            iteration = iterations,
            tokens = totalTokens,
            durationMs = durationMs,
            error = error?.take(500)
        ))
    }

    fun logCompression(sessionId: String, trigger: String, tokensBefore: Int, tokensAfter: Int) {
        write(AgentLogEntry(
            ts = Instant.now().toString(),
            session = sessionId.take(11),
            event = "compression",
            result = trigger,
            promptTokens = tokensBefore,
            completionTokens = tokensAfter
        ))
    }

    private fun write(entry: AgentLogEntry) {
        scope.launch {
            try {
                val line = json.encodeToString(entry)
                getWriter().println(line)
            } catch (e: Exception) {
                log.debug("AgentFileLogger write failed: ${e.message}")
            }
        }
    }

    @Synchronized
    private fun getWriter(): PrintWriter {
        val today = LocalDate.now()
        if (today != currentDate || writer == null) {
            writer?.close()
            currentDate = today
            val fileName = "agent-${today.format(DateTimeFormatter.ISO_LOCAL_DATE)}.jsonl"
            writer = PrintWriter(FileWriter(File(logDir, fileName), true), true) // append + autoflush
        }
        return writer!!
    }

    private fun cleanOldLogs() {
        scope.launch {
            try {
                val cutoff = LocalDate.now().minusDays(7)
                logDir.listFiles()?.filter { it.name.startsWith("agent-") && it.extension == "jsonl" }?.forEach { file ->
                    val dateStr = file.nameWithoutExtension.removePrefix("agent-")
                    try {
                        val fileDate = LocalDate.parse(dateStr)
                        if (fileDate.isBefore(cutoff)) file.delete()
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
    }

    override fun dispose() {
        scope.cancel()
        writer?.close()
    }

    @Serializable
    private data class AgentLogEntry(
        val ts: String,
        val session: String,
        val event: String,
        val tool: String? = null,
        val status: String? = null,
        val args: String? = null,
        val result: String? = null,
        val error: String? = null,
        val durationMs: Long? = null,
        val tokens: Int? = null,
        val iteration: Int? = null,
        val promptTokens: Int? = null,
        val completionTokens: Int? = null,
        val finishReason: String? = null,
        val toolsCalled: List<String>? = null
    )
}
```

- [ ] **Step 2:** Write test `AgentFileLoggerTest.kt` — verify log file creation, JSONL format, rotation, and cleanup.

- [ ] **Step 3:** Compile: `./gradlew :agent:compileKotlin`

- [ ] **Step 4:** Commit: `feat(observability): add AgentFileLogger — dedicated JSONL agent log file`

---

### Task 2: Wire AgentFileLogger into SingleAgentSession

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt`

- [ ] **Step 1:** Add `agentFileLogger: AgentFileLogger?` parameter to `SingleAgentSession` constructor. In `AgentService`, instantiate `AgentFileLogger` as a lazy service-level singleton (one per project, not per session), pass it to each session.

- [ ] **Step 2:** In `SingleAgentSession`, add logging calls at key points:

**Session start** (in `run()` before main loop):
```kotlin
agentFileLogger?.logSessionStart(sessionId, task, tools.size)
```

**Tool execution** (after `executeTool()` returns, around line ~647):
```kotlin
agentFileLogger?.logToolCall(
    sessionId = sessionId,
    toolName = toolName,
    args = tc.function.arguments.take(500),
    status = if (tr.isError) "error" else "success",
    result = tr.summary.take(300),
    errorMessage = if (tr.isError) tr.content.take(500) else null,
    durationMs = durMs,
    tokenEstimate = tr.tokenEstimate
)
```

**Note:** For tool errors, log `tr.content` (the full error message), not just `tr.summary` (truncated). This is the key information that's currently lost.

**Iteration completion** (after tool execution loop):
```kotlin
agentFileLogger?.logIteration(sessionId, iteration, usage?.promptTokens ?: 0, usage?.completionTokens ?: 0, choice.finishReason, toolNames, iterationDuration)
```

**Malformed tool call retry** (at the new `finishReason == "tool_calls"` retry block we added):
```kotlin
agentFileLogger?.logRetry(sessionId, "finishReason=tool_calls but no valid tool calls", iteration)
```

**Compression** (wherever compression is triggered):
```kotlin
agentFileLogger?.logCompression(sessionId, trigger, tokensBefore, tokensAfter)
```

**Session end** (at completion/failure):
```kotlin
agentFileLogger?.logSessionEnd(sessionId, iteration, totalTokensUsed, durationMs, error = errorMessage)
```

- [ ] **Step 3:** Compile: `./gradlew :agent:compileKotlin`

- [ ] **Step 4:** Run tests: `./gradlew :agent:test`

- [ ] **Step 5:** Commit: `feat(observability): wire AgentFileLogger into SingleAgentSession for all key events`

---

## Part 2: Fix Tool Error Display in Chat UI (Tier 2)

### Task 3: Fix tool error status push from Kotlin to JCEF

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt`

Currently `updateLastToolCall` always pushes `COMPLETED` status to the JCEF bridge. The `ToolCallStatus.ERROR` exists in TypeScript but is never used.

- [ ] **Step 1:** In `AgentCefPanel.kt`, find `updateLastToolCall` (around line ~607). Change the JS call to pass the actual status:

```kotlin
fun updateLastToolCall(status: ToolCallStatus, result: String, durationMs: Long, toolName: String) {
    val statusStr = when (status) {
        ToolCallStatus.SUCCESS -> "COMPLETED"
        ToolCallStatus.FAILED -> "ERROR"
        else -> "COMPLETED"
    }
    callJs("updateToolResult(${jsonStr(result)},$durationMs,${jsonStr(toolName)},${jsonStr(statusStr)})")
}
```

- [ ] **Step 2:** In `AgentController.kt`, find where `updateLastToolCall` is called from the progress handler. Ensure the `isError` flag from `ToolResult` is mapped to the correct `ToolCallStatus`:

```kotlin
// In the progress/tool-result handler:
val status = if (toolResult.isError) ToolCallStatus.FAILED else ToolCallStatus.SUCCESS
dashboard.updateLastToolCall(status, toolResult.summary, durationMs, toolName)
```

- [ ] **Step 3:** Compile: `./gradlew :agent:compileKotlin`

- [ ] **Step 4:** Commit: `fix(ui): push ERROR status for failed tool calls to JCEF chat UI`

---

### Task 4: Fix JCEF bridge + chat store to handle ERROR status

**Files:**
- Modify: `agent/webview/src/bridge/jcef-bridge.ts`
- Modify: `agent/webview/src/stores/chatStore.ts`

- [ ] **Step 1:** In `jcef-bridge.ts`, update `updateToolResult` to accept and pass the status parameter:

```typescript
// Change from:
updateToolResult(result: string, durationMs: number, toolName: string) {
    stores?.getChatStore().updateToolCall(toolName, 'COMPLETED', result, durationMs);
}

// To:
updateToolResult(result: string, durationMs: number, toolName: string, status?: string) {
    const resolvedStatus = (status === 'ERROR' ? 'ERROR' : 'COMPLETED') as ToolCallStatus;
    stores?.getChatStore().updateToolCall(toolName, resolvedStatus, result, durationMs);
}
```

Also update the global bridge function signature that Kotlin calls:
```typescript
// In the window globals:
window.updateToolResult = (result: string, durationMs: number, toolName: string, status?: string) => {
    stores?.getChatStore().updateToolCall(toolName, (status === 'ERROR' ? 'ERROR' : 'COMPLETED') as ToolCallStatus, result, durationMs);
};
```

- [ ] **Step 2:** In `chatStore.ts`, verify `updateToolCall` already handles `ERROR` status correctly. It should — the type is `ToolCallStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'ERROR'`.

- [ ] **Step 3:** TypeScript check: `cd agent/webview && npx tsc --noEmit`

- [ ] **Step 4:** Commit: `fix(ui): JCEF bridge passes tool error status through to chat store`

---

### Task 5: Style ERROR tool calls in the UI

**Files:**
- Modify: `agent/webview/src/components/agent/ToolCallChain.tsx`

- [ ] **Step 1:** In `ToolCallChain.tsx`, find where tool call status colors are set. Add ERROR styling — red tint, error icon:

```tsx
// For the tool call status indicator:
const statusColor = tc.status === 'ERROR'
  ? 'var(--error, #ef4444)'
  : tc.status === 'COMPLETED'
  ? 'var(--success, #22c55e)'
  : 'var(--fg-muted)';

// For ERROR status, show the error message prominently:
{tc.status === 'ERROR' && tc.result && (
  <div className="mt-1 text-[11px] px-2 py-1 rounded"
    style={{ color: 'var(--error, #ef4444)', background: 'var(--diff-rem-bg, rgba(239,68,68,0.1))' }}>
    {tc.result}
  </div>
)}
```

- [ ] **Step 2:** TypeScript check: `cd agent/webview && npx tsc --noEmit`

- [ ] **Step 3:** Commit: `fix(ui): show error styling and message for failed tool calls in chat`

---

## Part 3: Opt-in Debug Panel (Tier 3)

### Task 6: Add `showDebugLog` setting

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/AgentSettings.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentSettingsConfigurable.kt` (or equivalent settings UI file)

- [ ] **Step 1:** In `AgentSettings.kt`, add:
```kotlin
var showDebugLog: Boolean = false
```

- [ ] **Step 2:** In the settings UI, add a checkbox under "Advanced" section:
```
[x] Show debug log panel (displays real-time agent activity in chat)
```

- [ ] **Step 3:** Wire the setting to the JCEF bridge — when the setting changes, push it to the webview:
```kotlin
// In AgentCefPanel or AgentController:
fun updateDebugLogVisibility(show: Boolean) {
    callJs("setDebugLogVisible($show)")
}
```

- [ ] **Step 4:** Compile: `./gradlew :agent:compileKotlin`

- [ ] **Step 5:** Commit: `feat(settings): add showDebugLog setting for opt-in debug panel`

---

### Task 7: Add debug log entries to chat store + bridge

**Files:**
- Modify: `agent/webview/src/stores/chatStore.ts`
- Modify: `agent/webview/src/bridge/jcef-bridge.ts`
- Modify: `agent/webview/src/bridge/mock-bridge.ts`

- [ ] **Step 1:** In `chatStore.ts`, add debug log state:

```typescript
// State:
debugLogVisible: boolean;
debugLogEntries: DebugLogEntry[];

// Types:
interface DebugLogEntry {
  ts: number;
  level: 'info' | 'warn' | 'error';
  event: string; // "tool_call", "iteration", "retry", "compression", "api_call"
  detail: string;
  meta?: Record<string, any>; // tool name, duration, tokens, etc.
}

// Actions:
setDebugLogVisible(visible: boolean): void;
addDebugLogEntry(entry: DebugLogEntry): void;
clearDebugLog(): void;
```

- [ ] **Step 2:** Initialize: `debugLogVisible: false`, `debugLogEntries: []`, cap at 200 entries (FIFO).

- [ ] **Step 3:** In `jcef-bridge.ts`, add bridge functions:
```typescript
window.setDebugLogVisible = (visible: boolean) => {
    stores?.getChatStore().setDebugLogVisible(visible);
};
window.addDebugLogEntry = (entryJson: string) => {
    try {
        const entry = JSON.parse(entryJson);
        stores?.getChatStore().addDebugLogEntry(entry);
    } catch {}
};
```

- [ ] **Step 4:** In `mock-bridge.ts`, add mock debug log entries for dev testing.

- [ ] **Step 5:** TypeScript check: `cd agent/webview && npx tsc --noEmit`

- [ ] **Step 6:** Commit: `feat(ui): add debug log state and bridge functions to chat store`

---

### Task 8: Push debug log entries from Kotlin

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt`

- [ ] **Step 1:** In `AgentCefPanel.kt`, add:
```kotlin
fun pushDebugLogEntry(level: String, event: String, detail: String, meta: Map<String, Any?>? = null) {
    val metaJson = if (meta != null) kotlinx.serialization.json.Json.encodeToString(
        kotlinx.serialization.json.JsonObject.serializer(),
        // build JsonObject from meta
    ) else "null"
    val entry = """{"ts":${System.currentTimeMillis()},"level":"$level","event":"$event","detail":"${detail.replace("\"", "\\\"").take(300)}","meta":$metaJson}"""
    callJs("addDebugLogEntry('${entry.replace("'", "\\'")}')")
}
```

- [ ] **Step 2:** In `SingleAgentSession.kt`, alongside the `agentFileLogger` calls from Task 2, also push to the debug panel via the progress callback or a new debug callback:

```kotlin
// After each tool call:
onDebugLog?.invoke("info", "tool_call", "$toolName ${if (tr.isError) "ERROR" else "OK"} (${durMs}ms)",
    mapOf("tool" to toolName, "duration" to durMs, "tokens" to tr.tokenEstimate, "error" to if (tr.isError) tr.content.take(200) else null))

// After each iteration:
onDebugLog?.invoke("info", "iteration", "Iteration $iteration: ${toolNames.size} tools, ${usage?.totalTokens ?: 0} tokens, finish=$finishReason",
    mapOf("iteration" to iteration, "tokens" to (usage?.totalTokens ?: 0), "finishReason" to finishReason))

// On retry:
onDebugLog?.invoke("warn", "retry", "Retrying: $reason", mapOf("reason" to reason))

// On compression:
onDebugLog?.invoke("warn", "compression", "Compressed: $tokensBefore → $tokensAfter tokens", mapOf("before" to tokensBefore, "after" to tokensAfter))
```

- [ ] **Step 3:** In `AgentController.kt`, wire the `onDebugLog` callback to `AgentCefPanel.pushDebugLogEntry()`, but only when `AgentSettings.showDebugLog` is true:

```kotlin
val onDebugLog: ((String, String, String, Map<String, Any?>?) -> Unit)? =
    if (AgentSettings.getInstance(project).showDebugLog) { level, event, detail, meta ->
        cefPanel.pushDebugLogEntry(level, event, detail, meta)
    } else null
```

- [ ] **Step 4:** Compile: `./gradlew :agent:compileKotlin`

- [ ] **Step 5:** Commit: `feat(observability): push debug log entries from agent session to JCEF`

---

### Task 9: Create DebugPanel React component

**Files:**
- Create: `agent/webview/src/components/chat/DebugPanel.tsx`
- Modify: `agent/webview/src/App.tsx`

- [ ] **Step 1:** Create `DebugPanel.tsx` — a collapsible log viewer:

```tsx
import { memo, useRef, useEffect } from 'react';
import { useChatStore } from '@/stores/chatStore';

export const DebugPanel = memo(function DebugPanel() {
  const visible = useChatStore(s => s.debugLogVisible);
  const entries = useChatStore(s => s.debugLogEntries);
  const scrollRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    // Auto-scroll to bottom
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [entries.length]);

  if (!visible) return null;

  const levelColor = (level: string) => {
    switch (level) {
      case 'error': return 'var(--error, #ef4444)';
      case 'warn': return 'var(--accent-edit, #f59e0b)';
      default: return 'var(--fg-muted, #6b7280)';
    }
  };

  const eventIcon = (event: string) => {
    switch (event) {
      case 'tool_call': return '🔧';
      case 'iteration': return '🔄';
      case 'retry': return '⚠️';
      case 'compression': return '📦';
      case 'session_start': return '▶️';
      case 'session_end': return '⏹️';
      default: return '•';
    }
  };

  return (
    <div
      className="shrink-0 overflow-hidden border-t"
      style={{
        borderColor: 'var(--border, #333)',
        maxHeight: '180px',
        background: 'var(--tool-bg, rgba(0,0,0,0.2))',
      }}
    >
      {/* Header */}
      <div className="flex items-center justify-between px-3 py-1"
        style={{ borderBottom: '1px solid var(--border, #333)' }}>
        <span className="text-[10px] font-medium uppercase tracking-wider"
          style={{ color: 'var(--fg-muted)' }}>
          Debug Log ({entries.length})
        </span>
        <button
          onClick={() => useChatStore.getState().clearDebugLog()}
          className="text-[9px] px-1.5 py-0.5 rounded hover:bg-[var(--hover-overlay)]"
          style={{ color: 'var(--fg-muted)' }}>
          Clear
        </button>
      </div>

      {/* Log entries */}
      <div ref={scrollRef} className="overflow-y-auto px-2 py-1" style={{ maxHeight: '150px' }}>
        {entries.length === 0 ? (
          <div className="text-[10px] py-2 text-center" style={{ color: 'var(--fg-muted)' }}>
            No activity yet. Send a message to see agent debug logs.
          </div>
        ) : (
          entries.map((entry, i) => (
            <div key={i} className="flex items-start gap-1.5 py-0.5 text-[10px] font-mono leading-tight">
              <span className="shrink-0 w-[16px]">{eventIcon(entry.event)}</span>
              <span className="shrink-0 tabular-nums" style={{ color: 'var(--fg-muted)', width: '52px' }}>
                {new Date(entry.ts).toLocaleTimeString('en-US', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' })}
              </span>
              <span style={{ color: levelColor(entry.level) }}>
                {entry.detail}
              </span>
              {entry.meta?.duration && (
                <span className="shrink-0 tabular-nums" style={{ color: 'var(--fg-muted)' }}>
                  {entry.meta.duration}ms
                </span>
              )}
            </div>
          ))
        )}
      </div>
    </div>
  );
});
```

- [ ] **Step 2:** In `App.tsx`, add DebugPanel between ChatView and InputBar:

```tsx
<TopBar />
<ChatView />
<DebugPanel />
<InputBar />
```

- [ ] **Step 3:** TypeScript check: `cd agent/webview && npx tsc --noEmit`

- [ ] **Step 4:** Commit: `feat(ui): add collapsible DebugPanel for real-time agent activity log`

---

### Task 10: Add debug toggle to TopBar

**Files:**
- Modify: `agent/webview/src/components/chat/TopBar.tsx`

- [ ] **Step 1:** Add a small bug icon toggle button next to the "New" button. Only visible when `debugLogVisible` state has been initialized (i.e., the setting was pushed from Kotlin):

```tsx
// In TopBar, after the token display, before the New button:
const debugVisible = useChatStore(s => s.debugLogVisible);
const debugEntries = useChatStore(s => s.debugLogEntries);
const hasErrors = debugEntries.some(e => e.level === 'error');

// Only show toggle when debug mode is enabled from settings
// The button toggles the panel open/closed
{debugVisible !== undefined && (
  <button
    onClick={() => useChatStore.getState().setDebugLogVisible(!debugVisible)}
    className="..."
    style={{ color: hasErrors ? 'var(--error)' : 'var(--fg-muted)' }}
    title="Toggle debug log"
  >
    {/* Bug/terminal SVG icon */}
  </button>
)}
```

- [ ] **Step 2:** TypeScript check: `cd agent/webview && npx tsc --noEmit`

- [ ] **Step 3:** Commit: `feat(ui): add debug log toggle button to TopBar (visible when debug setting enabled)`

---

### Task 11: Final verification + tests

- [ ] **Step 1:** Full Kotlin compile: `./gradlew :agent:compileKotlin :core:compileKotlin`
- [ ] **Step 2:** TypeScript check: `cd agent/webview && npx tsc --noEmit`
- [ ] **Step 3:** Run agent tests: `./gradlew :agent:test`
- [ ] **Step 4:** Build plugin ZIP: `./gradlew clean buildPlugin`
- [ ] **Step 5:** Verify JSONL log file is created at `~/.workflow-orchestrator/agent/logs/` after running the agent
- [ ] **Step 6:** Verify tool errors show as red in the chat UI tool chain
- [ ] **Step 7:** Verify debug panel is hidden by default, visible when setting enabled
- [ ] **Step 8:** Commit: `feat(observability): complete three-tier agent observability system`
