# Process Streaming, Per-Tool Kill, and Session Stop — Research

**Date:** 2026-03-25
**Scope:** How enterprise AI agent tools handle live output streaming, per-tool cancellation, and session stop. Plus IntelliJ plugin best practices.

---

## 1. Live Output Streaming from Shell Commands

### Claude Code (TypeScript/Node.js)

**Current state:** Output is fully **buffered** — all stdout/stderr is collected and returned at once after command completion. Users see only a spinner with elapsed time (`Running Bash command... (45s)`) until the process finishes. This is a known limitation with active feature requests (issues #26983, #26243, #14280).

**Architecture:** Claude Code runs commands via Node.js `child_process.spawn()` with a persistent bash session. Commands are sent via stdin, output captured from stdout/stderr pipes. No PTY — the bash tool explicitly does not support interactive commands (vim, git rebase -i, npm init).

**PTY proposal (issue #9881):** A proposal exists to use `node-pty` for interactive subprocess management, rendering terminal UI in the REPL. Inspired by Gemini CLI v0.9.0 which shipped interactive shell support. Status: open, not implemented.

**Key takeaway:** Even Claude Code — the most mature agent tool — does not stream process output in real time as of early 2026. Output buffering is the norm, not the exception.

### Cline (TypeScript, VS Code Extension)

**Architecture:** Uses VS Code's **Shell Integration API** (v1.93+) for command execution and output capture.

**Concrete implementation in `TerminalProcess.ts`:**
```typescript
// Primary path: VS Code Shell Integration
const execution = terminal.shellIntegration.executeCommand(command);
const stream = execution.read();
for await (let data of stream) {
    // Process ANSI escape sequences
    // Strip VS Code markers (]633;C for command start, ]633;D for command end)
    this.emit('line', processedData);
}
```

**Key patterns:**
1. `TerminalProcess` extends `EventEmitter` + implements `Promise` interface
2. Output streams via `for await` async iteration over the shell integration read stream
3. First chunk requires special processing: removes VS Code escape sequences, fixes duplicate first character bug
4. **Fallback:** When shell integration unavailable (waits 4s), falls back to reading terminal buffer text directly
5. **Long-running processes:** "Proceed While Running" button lets the agent continue while a command (e.g., dev server) runs in background
6. Agent is **notified of new terminal output** as it appears, allowing it to react to compile errors etc.

**Known issues:** Intermittent output retrieval failures, corrupted data chunks from shell integration stream, race conditions when calling `execution.read()` too early.

### OpenAI Codex CLI (Rust)

**Architecture:** Spawns child processes via Tokio async `Command` with platform-specific sandboxing.

**Concrete implementation in `codex-rs/core/src/spawn.rs`:**
```rust
// Process spawning
let mut cmd = tokio::process::Command::new(program);
cmd.args(args)
   .current_dir(working_dir)
   .stdin(Stdio::null())        // Prevent command hangs
   .kill_on_drop(true);         // Auto-cleanup guarantee

// Output modes:
// 1. Piped: Stdio::piped() for stdout/stderr capture
// 2. Inherited: Stdio::inherit() for direct passthrough to parent TTY
```

**Streaming in `codex exec` mode:** Streams progress to stderr, prints only the final agent message to stdout. With `--json` flag, stdout becomes a JSONL stream of events (thread.started, turn.started, item.*, etc.).

**PTY handling:** Conditional — when `StdioPolicy::RedirectForShellTool` is active, the process is detached from the TTY via `codex_utils_pty::process_group::detach_from_tty()`. Stdin set to `Stdio::null()` to prevent TTY-related hangs.

**Configuration:** `stream_max_retries = 10`, `stream_idle_timeout_ms = 300000` for managing streaming connections.

### Continue.dev

**Architecture:** Each session operates independently with async operations. Streaming uses async iterators and Promises for token-by-token feedback. Terminal interaction allows real-time step-by-step approval.

### Summary Table: Output Streaming

| Tool | Method | Real-time? | PTY? | Fallback |
|------|--------|-----------|------|----------|
| Claude Code | Node.js spawn, pipe stdout | No (buffered) | No | N/A |
| Cline | VS Code Shell Integration API | Yes (async iter) | Via VS Code | Terminal buffer read |
| Codex CLI | Tokio Command, piped/inherited | Partial (JSONL events) | Optional detach | Direct TTY inherit |
| Continue | Async iterators + Promises | Yes (token-by-token) | N/A | N/A |

---

## 2. Per-Tool Kill/Cancel

### Claude Code

**Mechanism:** `AbortController` pattern from Node.js.

**Problems documented:**
- AbortController signals are **not immediately respected** during query execution — SDK continues processing all queued tool calls to completion before honoring abort (issue #2970)
- **Persistent abort state:** After manual Escape interrupt, abort state persists and auto-interrupts subsequent tool calls. Time between tool_use and tool_result drops to 5-12ms (vs ~500ms+ normal), indicating tools abort before running (issue #23350)
- **Subagent complications:** Shared AbortController architecture broke multi-subagent workflows — earlier per-subagent AbortController was more reliable (issue #6594)
- **Memory leak fix:** ChildProcess and AbortController references were retained after cleanup (since fixed)
- Running bash processes: killed via `process.kill()` or `process.destroy()` on the child process reference

**Background agent kill:** `Ctrl+F` to kill background agents (two-press confirmation within 3s).

### Cline

**Mechanism:** VS Code terminal integration — can send `Ctrl+C` to the terminal to interrupt a running command. The "Proceed While Running" button lets the agent move past a long-running command without killing it.

**Per-tool:** Each tool execution goes through an approval step. Users can click "Cancel" to reject a tool call before it executes. For already-running commands, the terminal itself provides the kill mechanism.

### Codex CLI (Rust)

**Mechanism:** Process group + signal-based killing.

**Implementation details from `spawn.rs`:**
```rust
// Auto-kill on drop
cmd.kill_on_drop(true);

// Linux: parent death signal via prctl(2)
set_parent_death_signal(parent_pid);
// When Codex process dies (even SIGKILL), child gets SIGTERM

// Process group management (documented gap):
// Missing setpgid(0, 0) means children not in own process group
// Signal propagation only reaches direct child PID, not entire group
```

**Known issues:** In sandbox mode, process group management gaps cause commands to hang indefinitely. Signals only reach the direct child PID, not the entire process group (issue #7852). Background tasks can spin independently with no way to kill them (issue #8656).

### Cursor

**Mechanism:** Stop button in the UI.

**Known issues:**
- MCP cancellation notifications are **not sent** when the cancel button is clicked — MCP tools continue running in background (forum report, October 2025)
- No way to resume from the point where execution was stopped (issue #3611)
- 25 tool call limit per interaction before requiring authorization

### Summary: Per-Tool Kill Patterns

| Tool | Kill Mechanism | Process Cleanup | Known Gaps |
|------|---------------|----------------|------------|
| Claude Code | AbortController + process.kill() | Manual | Abort state persists, not immediate |
| Cline | Terminal Ctrl+C / Cancel button | VS Code terminal | Limited to terminal-visible commands |
| Codex CLI | kill_on_drop + prctl SIGTERM | Automatic on parent death | Process group gaps, sandbox hangs |
| Cursor | Stop button (no MCP notification) | None documented | MCP tools keep running |

---

## 3. Session/Agent Stop Button

### Claude Code

**Escape key** is the primary interrupt:
- Single press: interrupt current turn, show prompt for new input
- In theory: cancels LLM streaming call + aborts running tool
- In practice: **unreliable** — Escape shows red feedback text but agent continues (issue #3455), inconsistent across sessions (issue #14526), terminal input blocked after cancel (issue #3475)

**Ctrl+C:** Exits the entire CLI application (not a graceful stop).

**Ctrl+F:** Kill background agents (two-press confirmation).

**TypeScript SDK:** Uses `AbortController.abort()` but it does not immediately stop — the SDK continues processing queued tool calls.

### Cline

**"Cancel" button** in the sidebar UI:
- Rejects the current pending tool call
- For running commands, relies on VS Code terminal's own kill mechanism
- Agent loop stops at the next approval gate

### Codex CLI

**Ctrl+C / SIGINT:**
- The Rust runtime catches the signal
- `kill_on_drop(true)` ensures child processes are terminated when the command handle is dropped
- On Linux: `prctl` ensures children get SIGTERM when parent dies

### What Actually Happens on "Stop"

Across all tools, the stop mechanism is a **two-phase pattern**:

1. **Set a cancellation flag** (AtomicBoolean, AbortController, CancellationToken)
2. **Check the flag at safe points** in the agent loop (before each LLM call, before each tool execution)

No tool does an immediate hard kill of the LLM streaming connection. The graceful pattern is:
- Cancel the HTTP streaming request (close the connection)
- Set the cancellation flag
- Let the current tool finish or send SIGTERM to the subprocess
- Return partial results to the user

---

## 4. IntelliJ Plugin Best Practices for Process Streaming

### Option A: `KillableProcessHandler` + `ProcessListener` (RECOMMENDED)

**This is the IntelliJ-native, production-grade approach.**

```kotlin
class AgentProcessRunner(private val project: Project) {

    fun executeWithStreaming(
        command: GeneralCommandLine,
        onOutput: (String) -> Unit,     // Called per line/chunk
        onComplete: (Int) -> Unit        // Called with exit code
    ): KillableProcessHandler {

        val handler = KillableProcessHandler(command)
        // Enable soft-kill: SIGINT first, then SIGKILL on second press
        handler.setShouldKillProcessSoftly(true)

        handler.addProcessListener(object : ProcessAdapter() {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                // Called for every line/chunk of output, in real time
                when (outputType) {
                    ProcessOutputTypes.STDOUT -> onOutput(event.text)
                    ProcessOutputTypes.STDERR -> onOutput("[stderr] ${event.text}")
                }
            }

            override fun processTerminated(event: ProcessEvent) {
                onComplete(event.exitCode)
            }
        })

        handler.startNotify()  // MUST call this to start capturing
        return handler  // Caller holds reference for kill/cancel
    }

    // Kill: handler.destroyProcess()   -> SIGINT (soft)
    // Force: handler.killProcess()     -> SIGKILL (hard)
}
```

**Hierarchy:**
```
ProcessHandler (abstract base)
  -> BaseProcessHandler
    -> BaseOSProcessHandler
      -> OSProcessHandler          (standard: captures output)
        -> ColoredProcessHandler   (ANSI color support)
          -> KillableProcessHandler (SIGINT -> SIGKILL escalation)
```

**Why `KillableProcessHandler`:**
- First stop attempt sends SIGINT (graceful)
- If process doesn't stop, user can click stop again for SIGKILL (force)
- Works on Unix natively, Windows via mediator process
- Integrates with IntelliJ's Run/Debug tool window stop button
- `canKillProcess()` / `killProcess()` API for programmatic control

### Option B: Coroutine Flow Bridge (for agent integration)

Wrap the ProcessListener pattern into Kotlin coroutines for the agent's async architecture:

```kotlin
fun executeCommand(
    command: GeneralCommandLine,
    timeoutMs: Long = 120_000
): Flow<ProcessOutput> = callbackFlow {
    val handler = KillableProcessHandler(command)
    handler.setShouldKillProcessSoftly(true)

    handler.addProcessListener(object : ProcessAdapter() {
        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
            trySend(ProcessOutput.Line(event.text, outputType == ProcessOutputTypes.STDERR))
        }
        override fun processTerminated(event: ProcessEvent) {
            trySend(ProcessOutput.Completed(event.exitCode))
            close()
        }
    })

    handler.startNotify()

    // Timeout handling
    val timeoutJob = launch {
        delay(timeoutMs)
        handler.destroyProcess()
    }

    awaitClose {
        timeoutJob.cancel()
        if (!handler.isProcessTerminated) {
            handler.destroyProcess()
        }
    }
}

sealed class ProcessOutput {
    data class Line(val text: String, val isError: Boolean) : ProcessOutput()
    data class Completed(val exitCode: Int) : ProcessOutput()
}
```

**Usage in agent tool:**
```kotlin
val lines = StringBuilder()
executeCommand(commandLine, timeoutMs).collect { output ->
    when (output) {
        is ProcessOutput.Line -> {
            lines.append(output.text)
            onProgress(AgentProgress(step = "Running: ${output.text.take(80)}"))
        }
        is ProcessOutput.Completed -> { /* handled after collect */ }
    }
}
```

### Option C: PTY via Pty4j (for interactive commands)

JetBrains' `pty4j` library is already bundled with IntelliJ. Use when you need:
- Interactive commands (prompts, TUI apps)
- ANSI escape sequence handling
- Terminal emulation

```kotlin
val pty = PtyProcess.exec(
    arrayOf("/bin/sh", "-c", command),
    mapOf("TERM" to "xterm-256color"),
    workingDir.absolutePath
)
// IMPORTANT: Must use blocking read for PTY
// InputStream.available() does NOT work with Pty4j
val reader = pty.inputStream.bufferedReader()
// Read in a loop on IO thread
```

**Caveats:**
- `InputStream.available()` does not work for Pty4j streams
- Must use blocking reads (set `BaseOutputReader.Options.BLOCKING`)
- Set blocking option BEFORE calling `startNotify()`
- More complex lifecycle management than piped ProcessHandler

### Option D: Manual Thread Reading (NOT recommended)

```kotlin
// Current RunCommandTool approach - blocks until complete
val process = processBuilder.start()
val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
val rawOutput = process.inputStream.bufferedReader().readText()  // BLOCKS
```

This is what the current `RunCommandTool` does. Problems:
- No streaming — `.readText()` blocks until process completes
- No integration with IntelliJ's process management
- No soft-kill (SIGINT) support
- No ConsoleView attachment possible
- Cannot be displayed in the Run tool window

### Comparison Matrix

| Approach | Streaming | Kill support | IDE integration | Complexity |
|----------|----------|-------------|----------------|------------|
| KillableProcessHandler | Real-time (callback) | SIGINT/SIGKILL | Full (ConsoleView, Run window) | Medium |
| Flow + KillableProcessHandler | Real-time (coroutine) | SIGINT/SIGKILL + coroutine cancel | Full | Medium-High |
| Pty4j | Real-time (blocking read) | Process destroy | Partial (needs JediTerm) | High |
| Manual ProcessBuilder | None (buffered) | destroyForcibly() only | None | Low |

### Recommendation for Agent Module

**Use Option B (Coroutine Flow + KillableProcessHandler)** for the `RunCommandTool`:

1. Replace `ProcessBuilder` with `GeneralCommandLine` + `KillableProcessHandler`
2. Bridge to `callbackFlow` for async streaming
3. Stream output lines to the chat UI via `onProgress` callback
4. Store accumulated output for the `ToolResult` response
5. On cancellation (`cancelled.get()`), call `handler.destroyProcess()` for SIGINT
6. On timeout, escalate to `handler.killProcess()` for SIGKILL
7. Cap accumulated output at `MAX_OUTPUT_CHARS` with middle-truncation (already implemented)

**For the chat UI streaming indicator:**
- Show partial output lines as they arrive (last N lines in a scrollable area)
- Show elapsed time + line count
- Provide a per-tool "Stop" button that calls `handler.destroyProcess()`

---

## 5. Recommended Architecture for Our Agent

### Per-Tool Cancel

```kotlin
// In RunCommandTool or a new ProcessExecutor service:
class ProcessExecutor {
    // Track running processes by tool call ID
    private val runningProcesses = ConcurrentHashMap<String, KillableProcessHandler>()

    fun execute(toolCallId: String, command: GeneralCommandLine, ...): Flow<ProcessOutput> {
        return callbackFlow {
            val handler = KillableProcessHandler(command)
            runningProcesses[toolCallId] = handler
            // ... setup listeners, start
            awaitClose {
                runningProcesses.remove(toolCallId)
                if (!handler.isProcessTerminated) handler.destroyProcess()
            }
        }
    }

    fun cancelTool(toolCallId: String) {
        runningProcesses[toolCallId]?.destroyProcess()  // SIGINT
    }

    fun killTool(toolCallId: String) {
        runningProcesses[toolCallId]?.killProcess()  // SIGKILL
    }
}
```

### Session Stop

The current `AtomicBoolean cancelled` pattern in `SingleAgentSession` is correct and matches industry practice. Enhancements needed:

1. **Cancel the LLM HTTP call:** Close the OkHttp call via `Call.cancel()` when stop is pressed
2. **Kill running processes:** Iterate `runningProcesses` and call `destroyProcess()` on each
3. **Cancel subagent Jobs:** Cancel the coroutine `Job` for any running WorkerSession
4. **Return partial results:** Collect whatever output was gathered before cancellation

```kotlin
fun stop() {
    cancelled.set(true)
    // 1. Cancel active LLM call
    activeLlmCall?.cancel()
    // 2. Kill running processes
    processExecutor.cancelAll()
    // 3. Cancel subagent jobs
    runningSubagentJobs.forEach { it.cancel() }
}
```

---

## Sources

- [Claude Code Bash Tool Docs](https://platform.claude.com/docs/en/agents-and-tools/tool-use/bash-tool)
- [Claude Code: Stream Bash output in real-time (issue #26983)](https://github.com/anthropics/claude-code/issues/26983)
- [Claude Code: PTY support proposal (issue #9881)](https://github.com/anthropics/claude-code/issues/9881)
- [Claude Code: Escape interrupt issues (issue #3455)](https://github.com/anthropics/claude-code/issues/3455)
- [Claude Code: AbortController not respected (issue #2970)](https://github.com/anthropics/claude-code/issues/2970)
- [Claude Code: Persistent abort state (issue #23350)](https://github.com/anthropics/claude-code/issues/23350)
- [Claude Code: Subagent termination bug (issue #6594)](https://github.com/anthropics/claude-code/issues/6594)
- [Claude Code: VS Code streaming bash output (issue #26243)](https://github.com/anthropics/claude-code/issues/26243)
- [Cline TerminalProcess.ts source](https://github.com/cline/cline/blob/main/src/integrations/terminal/TerminalProcess.ts)
- [Cline Terminal Integration Guide](https://docs.cline.bot/troubleshooting/terminal-integration-guide)
- [Cline: Output retrieval failures (issue #320)](https://github.com/cline/cline/issues/320)
- [Codex CLI spawn.rs source](https://github.com/openai/codex/blob/main/codex-rs/core/src/spawn.rs)
- [Codex CLI exec.md docs](https://github.com/openai/codex/blob/main/docs/exec.md)
- [Codex CLI Features](https://developers.openai.com/codex/cli/features)
- [Codex: Sandbox process hang (issue #7852)](https://github.com/openai/codex/issues/7852)
- [Codex: Background tasks unkillable (issue #8656)](https://github.com/openai/codex/issues/8656)
- [Cursor: MCP cancellation not sent (forum)](https://forum.cursor.com/t/cursor-doesnt-send-mcp-cancellation-notifications-when-user-clicks-cancel-button/138669)
- [Cursor: Resume after stop (issue #3611)](https://github.com/cursor/cursor/issues/3611)
- [IntelliJ Execution docs](https://plugins.jetbrains.com/docs/intellij/execution.html)
- [IntelliJ KillableProcessHandler API](https://dploeger.github.io/intellij-api-doc/com/intellij/execution/process/KillableProcessHandler.html)
- [IntelliJ ProcessHandler source](https://github.com/JetBrains/intellij-community/blob/master/platform/util/src/com/intellij/execution/process/ProcessHandler.java)
- [JetBrains Pty4j](https://github.com/JetBrains/pty4j)
- [IntelliJ Reworked Terminal API (2025.3)](https://platform.jetbrains.com/t/stream-terminal-output-and-process-exit-code-from-plugin/2027)
- [IntelliJ Coroutines Plugin SDK](https://plugins.jetbrains.com/docs/intellij/kotlin-coroutines.html)
