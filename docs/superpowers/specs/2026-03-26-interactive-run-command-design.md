# Interactive RunCommandTool — Idle Detection, Stdin, User Input

**Date:** 2026-03-26
**Status:** Approved for implementation
**Module:** `:agent` — `tools/builtin/RunCommandTool.kt`, `runtime/ProcessRegistry.kt`

## Problem

The `run_command` tool blocks the entire ReAct loop with `process.waitFor(timeout)`. Two failure modes:

1. **Stuck process** — Command hangs (network, deadlock, infinite loop). The agent is frozen for up to 120-600 seconds before timeout kills it. No way for the user to abort from the UI.
2. **Waiting for input** — Process prompts for stdin (confirmation, password, selection menu). Stdin is never wired, so the process hangs until timeout. The agent burns its iteration budget waiting on a process it could have answered in 1 second.

Both cases waste the user's time and the agent's token budget.

## Solution

Shift `run_command` from a **blocking wait** to an **event-driven monitor** with idle detection. When a process goes idle (no output), control returns to the LLM immediately. Three new tools let the LLM (or user) interact with the running process.

## Architecture

### Process Lifecycle

```
run_command("npm init")
  │
  ├─ Start process, wire stdin/stdout, register in ProcessRegistry
  ├─ Monitor loop (500ms poll):
  │    ├─ Process exited?      → return exit result
  │    ├─ Timeout exceeded?    → kill, return timeout result
  │    └─ Idle threshold hit?  → return [IDLE] result, process stays alive
  │
  ▼
LLM receives [IDLE] result with last output lines
  │
  ├─ Recognizes prompt → send_stdin(process_id, "y\n")
  ├─ Needs user input  → ask_user_input(process_id, description, prompt)
  ├─ Wants to abort    → kill_process(process_id)
  └─ (Does nothing for 60s → auto-reaper kills process)
```

### ManagedProcess (ProcessRegistry Extension)

```kotlin
data class ManagedProcess(
    val process: Process,
    val stdin: OutputStream,
    val lastOutputAt: AtomicLong,
    val outputLines: ConcurrentLinkedQueue<String>,
    val toolCallId: String,
    val command: String,
    val startedAt: Long,
    val idleSignaledAt: AtomicLong = AtomicLong(0)  // 0 = not idle
)
```

Key design choices:
- `ConcurrentLinkedQueue<String>` for thread-safe output collection (reader thread appends, monitor reads)
- `lastOutputAt` tracks when the last output line arrived for idle detection
- `idleSignaledAt` tracks when the idle signal was sent, for the 60s auto-reaper
- `stdin: OutputStream` retained for `send_stdin` / `ask_user_input` to write to

### Idle Detection

**Two-tier threshold:**

| Command type | Idle threshold | Detection |
|---|---|---|
| Build commands (gradle, mvn, npm, yarn, docker build, cargo build, go build) | 60 seconds | Prefix match on command string |
| All other commands | 15 seconds | Default |

The idle threshold is also configurable via:
- `idle_timeout` parameter on `run_command` (LLM can override per-call)
- Agent Settings > AI & Advanced > "Command idle detection threshold" (user default)

**Grace period:** The idle clock does not start until the first output line arrives. This prevents false idles during initial compilation/download phases where the process is working but hasn't produced output yet. If no output arrives at all, the regular `timeout` parameter handles the kill.

**Monitor loop pseudocode:**

```kotlin
while (true) {
    delay(500)
    val alive = process.isAlive
    val now = System.currentTimeMillis()

    // Priority 1: process exited — always return exit result
    if (!alive) return exitResult(managed)

    // Priority 2: total timeout exceeded
    if (now - managed.startedAt > timeoutMs) {
        kill(managed)
        return timeoutResult(managed)
    }

    // Priority 3: idle detection (only after first output)
    val lastOutput = managed.lastOutputAt.get()
    if (lastOutput > 0 && now - lastOutput >= idleThresholdMs) {
        return idleResult(managed)
    }
}
```

Order matters: check `isAlive` first to avoid signaling idle on a dead process (race condition fix from review).

### Output Reader Thread

Changed from `readLine()` to buffer-based reading to handle binary output:

```kotlin
val buffer = CharArray(4096)
var charsRead: Int
while (reader.read(buffer).also { charsRead = it } != -1) {
    val chunk = String(buffer, 0, charsRead)
    managed.outputLines.add(chunk)
    managed.lastOutputAt.set(System.currentTimeMillis())
    streamCallback?.invoke(toolCallId, chunk)
}
```

This ensures binary data still counts as "output" for idle detection even without newlines.

**ANSI stripping:** Output included in LLM-facing results (idle messages, final output) is stripped of ANSI escape codes:

```kotlin
fun stripAnsi(text: String): String =
    text.replace(Regex("\u001B\\[[;\\d]*[A-Za-z]"), "")
```

The UI rendering still gets raw ANSI (the webview already has `ansi_up` for rendering).

## New Tools

### 1. `send_stdin` — LLM Provides Input

```yaml
name: send_stdin
description: >
  Send input to a running process's stdin. Use when a command is waiting for
  input that you can determine from context (e.g., confirmation prompts,
  menu selections). NEVER use for passwords, tokens, or secrets — use
  ask_user_input instead. Max 10 sends per process.
parameters:
  process_id: (string, required) The process ID from the [IDLE] message
  input: (string, required) Text to send. Include \n for Enter key.
```

**Behavior:**
1. Look up process in `ProcessRegistry` by ID
2. If process not found or dead: return error
3. Write `input` bytes to `managed.stdin`, flush
4. Log to `AgentEventLog`: `STDIN_SENT` with process_id, command (truncated), input content
5. Resume the same monitor loop (wait for completion, next idle, or timeout)
6. Return new output produced after the input

**Safety constraints:**
- **Rate limit:** Max 10 `send_stdin` calls per process ID. After 10, return error: "stdin limit reached — kill the process and run a non-interactive command instead."
- **Password detection:** If the last output line matches password prompt patterns (`password:`, `passphrase:`, `enter.*token`, `secret:`), return error: "Last output appears to be a password prompt. Use ask_user_input instead of send_stdin."
- **Strict mode:** If `AgentSettings.strictInteractiveMode` is enabled, `send_stdin` is entirely disabled. Return error: "send_stdin disabled in strict interactive mode. Use ask_user_input."
- **Credential redaction:** Run `CredentialRedactor.redact()` on the input before logging (audit safety).

**Risk level:** MEDIUM (same as `edit_file`). Goes through `ApprovalGate` when approval is required.

### 2. `ask_user_input` — User Provides Input

```yaml
name: ask_user_input
description: >
  Ask the user to provide input for a running process. Use when the process
  needs credentials, user decisions, or information you cannot determine.
  Renders a text input in the chat UI with your description.
parameters:
  process_id: (string, required) The process ID from the [IDLE] message
  description: (string, required) Explain what the user needs to enter and why.
  prompt: (string) The terminal prompt shown by the process (for user reference).
```

**Behavior:**
1. Look up process in `ProcessRegistry`
2. Send to UI via bridge: renders an input component in the chat showing `description`, `prompt`, and a text field
3. Block via `suspendCancellableCoroutine` + `CompletableDeferred` (same pattern as `ApprovalGate`)
4. When user submits: write input to `managed.stdin`, flush, resume monitor loop
5. **Timeout: 5 minutes.** If user doesn't respond, auto-kill the process, return error: "User did not respond within 5 minutes. Process killed."
6. Audit log: record that user provided input, the process_id, and the command — but NOT the input content (may be a password)

**UI component:**

```
┌──────────────────────────────────────────────────────┐
│ ⚠ Process input requested                           │
│                                                      │
│ {description from LLM}                               │
│                                                      │
│ Terminal prompt: {prompt}                             │
│                                                      │
│ ⚠ This input will be sent to: {command}              │
│   Do not enter passwords unless you trust this       │
│   command.                                           │
│                                                      │
│ ┌──────────────────────────────────┐  ┌──────────┐  │
│ │ Enter input...                   │  │  Send    │  │
│ └──────────────────────────────────┘  └──────────┘  │
└──────────────────────────────────────────────────────┘
```

**Risk level:** NONE (user is explicitly providing the input, no auto-approval needed).

### 3. `kill_process` — LLM Aborts Process

```yaml
name: kill_process
description: >
  Kill a running process. Use when a process is stuck, unresponsive, or
  no longer needed.
parameters:
  process_id: (string, required) The process ID to kill.
```

**Behavior:**
1. Call `ProcessRegistry.kill(processId)`
2. Wait 1s for reader thread to drain
3. Return partial output: `[KILLED] Process terminated.\n\nPartial output:\n{output}`
4. Audit log: `PROCESS_KILLED` with process_id, command, reason="agent"

**Risk level:** NONE (killing an agent-spawned process is safe).

## What the LLM Sees

### Normal completion

```
Exit code: 0
<full output>
```

### Idle detection

```
[IDLE] Process idle for 15s — no output since last line.
Process still running (ID: run-cmd-7, command: npm init).

Last 10 lines of output:
  package name: (my-project)
  version: (1.0.0)
  description:

The process may be waiting for input. Your options:
- send_stdin(process_id="run-cmd-7", input="my-app\n") to provide input
- ask_user_input(process_id="run-cmd-7", description="...", prompt="...") for user input
- kill_process(process_id="run-cmd-7") to abort
```

If password prompt detected, additional line:
```
WARNING: Last output appears to be a password/credential prompt. Use ask_user_input, not send_stdin.
```

### Timeout

```
[TIMEOUT] Command timed out after 300s.
Partial output:
<output collected so far>
```

### Killed by user (UI button)

```
[KILLED] Process terminated by user.
Partial output:
<output collected so far>
```

### Killed by auto-reaper (60s after idle signal, no LLM action)

```
[REAPED] Process auto-killed after 60s idle with no interaction.
Partial output:
<output collected so far>
```

## ProcessRegistry Changes

```kotlin
object ProcessRegistry {
    private val running = ConcurrentHashMap<String, ManagedProcess>()

    fun register(id: String, process: Process, command: String): ManagedProcess
    fun unregister(id: String)
    fun kill(id: String): Boolean
    fun killAll()
    fun get(id: String): ManagedProcess?
    fun writeStdin(id: String, input: String): Boolean
    fun isRunning(id: String): Boolean
    fun runningCount(): Int
    fun getIdleProcesses(thresholdMs: Long): List<ManagedProcess>

    // Auto-reaper: called periodically (every 10s) from a coroutine
    fun reapIdleProcesses(maxIdleSinceSignalMs: Long = 60_000)
}
```

The reaper runs as a periodic coroutine in the agent session scope, checking every 10 seconds for processes that received an idle signal more than 60 seconds ago with no subsequent `send_stdin` / `ask_user_input` interaction.

## UI Changes

### Terminal Component — Kill Button

Add a stop button to the `Terminal` component header while the tool call status is `RUNNING`:

```tsx
{isRunning && (
  <Button variant="ghost" size="sm" onClick={() => killToolCall(toolCall.id)}
    title="Stop process" className="h-5 w-5 p-0 shrink-0">
    <Square className="h-3 w-3" style={{ color: 'var(--error)' }} />
  </Button>
)}
```

The `killToolCall` bridge function calls `ProcessRegistry.kill()` on the Kotlin side. The monitor loop detects the killed process and returns the `[KILLED]` result.

### User Input Component

New component `ProcessInputView` rendered in `ChatView` when `ask_user_input` is pending:

- Shows LLM's `description` text
- Shows the terminal `prompt` in a code block
- Shows security warning: "This input will be sent to: `<command>`. Do not enter passwords unless you trust this command."
- Text input field + Send button
- On submit: sends input through bridge to `ProcessRegistry.writeStdin()`
- Completes the `CompletableDeferred` so the tool resumes monitoring

### JCEF Bridge

New bridge functions:
- `showProcessInput(processId, description, prompt, command)` — JS→Kotlin→UI
- `resolveProcessInput(processId, input)` — Kotlin→JS (user submitted)
- `killRunningProcess(processId)` — JS→Kotlin (kill button)

## RunCommandTool Changes

### Parameter additions

```kotlin
"idle_timeout" to ParameterProperty(
    type = "integer",
    description = "Idle detection threshold in seconds. Default: 15 (60 for build commands). " +
        "Process returns [IDLE] if no output for this many seconds."
)
```

### Description update

```
Execute a shell command in the project directory. Default timeout: 120s (max 600s),
output limit: 30000 chars. Dangerous commands are blocked.

If the process goes idle (no output), you will receive an [IDLE] result with the
process ID and last output. Use send_stdin to provide input, ask_user_input to
let the user respond, or kill_process to abort. Never send passwords via send_stdin.
```

### Tool availability

| Tool | Category | Risk | Always available |
|---|---|---|---|
| `send_stdin` | Core | MEDIUM | Yes (disabled in strict mode) |
| `ask_user_input` | Core | NONE | Yes |
| `kill_process` | Core | NONE | Yes |

All three tools are in the Core (always active) category — they don't need dynamic tool selection since they're only useful when a process is idle.

## Security

### Audit Trail

| Event | Logged fields | Content logged? |
|---|---|---|
| `send_stdin` | process_id, command, input (redacted) | Yes (after `CredentialRedactor`) |
| `ask_user_input` shown | process_id, command, description | Yes |
| `ask_user_input` submitted | process_id, command | NO (may be password) |
| `kill_process` | process_id, command, source (agent/user/reaper) | N/A |

### Password Prompt Detection

```kotlin
private val PASSWORD_PATTERNS = listOf(
    Regex("""(?i)password\s*:"""),
    Regex("""(?i)passphrase\s*:"""),
    Regex("""(?i)enter\s+.*token"""),
    Regex("""(?i)secret\s*:"""),
    Regex("""(?i)credentials?\s*:"""),
    Regex("""(?i)api.?key\s*:"""),
)

fun isLikelyPasswordPrompt(lastOutput: String): Boolean =
    PASSWORD_PATTERNS.any { it.containsMatchIn(lastOutput.takeLast(300)) }
```

When detected:
- `send_stdin` returns error: "This appears to be a password prompt. Use ask_user_input instead."
- Idle result includes: `"WARNING: Last output appears to be a password/credential prompt."`

### Strict Interactive Mode

Setting: `AgentSettings.strictInteractiveMode` (default: false)

When enabled:
- `send_stdin` is entirely disabled — all process input must come from the user via `ask_user_input`
- Useful for regulated environments (finance, healthcare) where LLM auto-responding to prompts has compliance implications

### `send_stdin` Rate Limit

Max 10 `send_stdin` calls per process ID. Tracked in `ManagedProcess.stdinCount: AtomicInteger`.

After limit: "stdin limit reached for this process (10/10). Kill the process and run a non-interactive command instead."

Prevents REPL abuse (LLM opening `node -i` or `python` and burning iterations).

## Settings

New fields in `AgentSettings`:

| Setting | Type | Default | Location |
|---|---|---|---|
| `commandIdleThresholdSeconds` | Int | 15 | AI & Advanced |
| `buildCommandIdleThresholdSeconds` | Int | 60 | AI & Advanced |
| `strictInteractiveMode` | Boolean | false | AI & Advanced |
| `maxStdinPerProcess` | Int | 10 | AI & Advanced |
| `askUserInputTimeoutMinutes` | Int | 5 | AI & Advanced |

## System Prompt Addition

Add to the `run_command` section of the system prompt:

```
When run_command returns [IDLE], the process is still running and may need input.
Examine the last output lines to determine what the process is asking for.
- If you can answer (e.g., "Continue? [y/N]" → send_stdin with "y\n")
- If it needs credentials or a user decision → ask_user_input with a clear description
- If the process is stuck → kill_process

NEVER use send_stdin to provide passwords, tokens, API keys, or secrets.
Always use ask_user_input for credential prompts.
```

## Zombie Cleanup

- **Session cancellation:** `AgentController` already calls `ProcessRegistry.killAll()` on cancel
- **IDE shutdown:** Register `ProcessRegistry` cleanup in a `Disposable` tied to the project:
  ```kotlin
  Disposer.register(project) { ProcessRegistry.killAll() }
  ```
- **Dead process write:** `ProcessRegistry.writeStdin()` catches `IOException` from writing to a closed stream and returns false

## Files Changed

| File | Change |
|---|---|
| `runtime/ProcessRegistry.kt` | Extend to `ManagedProcess`, add `writeStdin`, `get`, `reapIdleProcesses` |
| `tools/builtin/RunCommandTool.kt` | Event-driven monitor, idle detection, parameter additions |
| `tools/builtin/SendStdinTool.kt` | **New** — `send_stdin` tool |
| `tools/builtin/AskUserInputTool.kt` | **New** — `ask_user_input` tool |
| `tools/builtin/KillProcessTool.kt` | **New** — `kill_process` tool |
| `ui/AgentController.kt` | Wire `ask_user_input` UI callbacks, reaper coroutine |
| `ui/AgentCefPanel.kt` | Bridge functions for process input UI + kill |
| `ui/AgentDashboardPanel.kt` | Passthrough for new bridge functions |
| `settings/AgentSettings.kt` | New settings fields |
| `webview/src/components/agent/ProcessInputView.tsx` | **New** — user input UI component |
| `webview/src/components/agent/ToolCallChain.tsx` | Kill button on Terminal component |
| `webview/src/stores/chatStore.ts` | `pendingProcessInput` state + actions |
| `webview/src/bridge/jcef-bridge.ts` | New bridge functions |
| `webview/src/bridge/types.ts` | `ProcessInputRequest` type |
| `runtime/SingleAgentSession.kt` | Register new tools, start reaper coroutine |
| Tests | `RunCommandToolTest`, `ProcessRegistryTest`, `SendStdinToolTest` updates |
