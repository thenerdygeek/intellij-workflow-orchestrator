# Process Execution Overhaul — Design Spec

**Date:** 2026-03-25
**Module:** `:agent` (Kotlin runtime + React webview)
**Branch:** `feature/phase-3-agentic-ai`

## Problems

1. `run_command` blocks until completion — no live output visible while running
2. The `Terminal` tool-ui component exists but isn't used — CMD tools show raw JSON
3. No per-tool kill button — can't cancel a running command
4. Stop button just shows "cancellation requested" — doesn't kill running processes
5. Live timer shows elapsed time but not the timeout limit

## Fix 1: Streaming Process Output

### Current
```
RunCommandTool.execute()
  → ProcessBuilder.start()
  → process.waitFor(timeout)          // blocks for up to 120s
  → process.inputStream.readText()    // reads all output at once
  → return ToolResult(output)         // UI gets full output on completion
```

### New
```
RunCommandTool.execute()
  → ProcessBuilder.start()
  → register process in ProcessRegistry (keyed by toolCallId)
  → launch reader coroutine: reads inputStream line-by-line
    → each line: calls streamCallback(toolCallId, line)
    → streamCallback bridges to JCEF: appendToolOutput(toolCallId, chunk)
  → process.waitFor(timeout)
  → unregister from ProcessRegistry
  → return ToolResult(fullOutput)     // final output for LLM context
```

### ProcessRegistry (new class)

```kotlin
object ProcessRegistry {
    private val running = ConcurrentHashMap<String, Process>()

    fun register(toolCallId: String, process: Process)
    fun unregister(toolCallId: String)
    fun kill(toolCallId: String): Boolean   // destroyForcibly + unregister
    fun killAll()                            // for session stop
    fun isRunning(toolCallId: String): Boolean
}
```

### RunCommandTool Changes

- Add `streamCallback: ((String, String) -> Unit)?` parameter (toolCallId, chunk)
- Start a reader thread that reads process output line-by-line and calls streamCallback
- Register process in ProcessRegistry before waitFor
- Unregister after completion/timeout/kill
- On timeout: destroyForcibly via ProcessRegistry

### Bridge: Kotlin → JS

New function: `appendToolOutput(toolCallId: String, chunk: String)`
- Appends output incrementally to the tool call's display
- Called per line during execution

## Fix 2: Terminal Component for CMD Tools

### ToolCallView.tsx Changes

For tools with category `CMD` (run_command, Bash, run_tests, compile_module):
- Render `Terminal` component instead of generic JSON output
- Pass accumulated streaming output from `chatStore.toolOutputStreams`
- Parse command from tool args for the Terminal header
- Show exit code and duration on completion

### chatStore.ts Changes

New state:
```typescript
toolOutputStreams: Record<string, string>;  // toolCallId → accumulated output
```

New actions:
```typescript
appendToolOutput(toolCallId: string, chunk: string): void;  // append to stream
clearToolOutputStream(toolCallId: string): void;             // cleanup on completion
```

## Fix 3: Per-Tool Kill Button

### React — ToolCallView.tsx

When `status === 'RUNNING'`:
- Show a kill button (X icon or Square icon) in the header
- On click: calls `kotlinBridge.killToolCall(toolCallId)`

### Bridge: JS → Kotlin

New function: `_killToolCall(toolCallId: String)`
- Calls `ProcessRegistry.kill(toolCallId)`
- Process gets destroyForcibly()
- Tool execution returns with error: "Process killed by user"

### AgentCefPanel.kt

New JBCefJSQuery bridge for `_killToolCall`
- Receives toolCallId string
- Calls `ProcessRegistry.kill(toolCallId)`
- Callback field: `var onKillToolCall: ((String) -> Unit)?`

## Fix 4: Stop Button Actually Stopping

### Current Problem

`SingleAgentSession` sets `cancelled = true` (AtomicBoolean) but:
- Running process continues until its timeout
- LLM HTTP call continues until response completes
- UI just shows "cancellation requested"

### Fix

In `AgentController` or `SingleAgentSession`, when stop is triggered:
1. Set `cancelled = true` (existing)
2. Call `ProcessRegistry.killAll()` — immediately kills all running processes
3. Cancel the coroutine job — `sessionJob?.cancel()` triggers CancellationException
4. Cancel HTTP client call if streaming — `currentCall?.cancel()` on the OkHttp Call object

### Bridge Wiring

The existing `_stopAgent` bridge in AgentCefPanel should trigger the enhanced stop:
- Kill all running processes
- Cancel the session coroutine
- Update UI to show "Stopped" (not just "cancellation requested")

## Fix 5: Timeout in Timer Display

### ToolCallView.tsx

When a CMD tool is running, extract the timeout from tool args:
```typescript
const timeoutArg = JSON.parse(toolCall.args)?.timeout;
const timeout = timeoutArg ? Number(timeoutArg) : 120; // default 120s
```

Display as: `3.2s / 120s` instead of just `3.2s`

### Alternatively

Pass the timeout from Kotlin when starting the tool call via the existing `appendToolCall` bridge. Add an optional `timeoutSeconds` parameter.

## Files Changed

| File | Change |
|------|--------|
| **Kotlin** | |
| `agent/runtime/ProcessRegistry.kt` | New: running process tracker with kill/killAll |
| `agent/tools/builtin/RunCommandTool.kt` | Streaming output, ProcessRegistry integration |
| `agent/ui/AgentCefPanel.kt` | New bridges: appendToolOutput, killToolCall |
| `agent/ui/AgentDashboardPanel.kt` | Passthrough for new bridges |
| `agent/ui/AgentController.kt` | Wire killToolCall, enhance stop to killAll |
| `agent/runtime/SingleAgentSession.kt` | Pass streamCallback to tool execution |
| **React** | |
| `stores/chatStore.ts` | toolOutputStreams state, appendToolOutput/killToolCall actions |
| `bridge/jcef-bridge.ts` | appendToolOutput handler, killToolCall in kotlinBridge |
| `bridge/globals.d.ts` | _killToolCall declaration |
| `components/agent/ToolCallView.tsx` | Terminal for CMD, kill button, timeout in timer |
