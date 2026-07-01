# JVM Shell Execution Libraries: Deep Source-Level Analysis

Research date: 2026-03-27
Sources: GitHub source code, JetBrains API docs, Koog docs, Maven Central

---

## 1. PTY4J (JetBrains)

**Repo:** https://github.com/JetBrains/pty4j
**Maven:** `org.jetbrains.pty4j:pty4j:0.13.4`
**License:** Eclipse Public License 1.0
**Platforms:** Linux, macOS, Windows (ConPTY), FreeBSD

### Is it on IntelliJ Plugin Classpath?

**Yes, but indirectly.** pty4j is bundled as `intellij.libraries.pty4j` module within the IntelliJ platform (referenced in `intellij.platform.eel.impl.xml`). The Terminal plugin (`org.jetbrains.plugins.terminal`) depends on it. To access it from a plugin, you would either:
1. Declare a dependency on the Terminal plugin in `plugin.xml`, OR
2. Add pty4j as a direct Gradle dependency (it's on Maven Central)

Option 2 is cleaner since we don't need the Terminal UI, just the PTY primitive.

### PtyProcessBuilder API (Complete)

```kotlin
val process: PtyProcess = PtyProcessBuilder()
    .setCommand(arrayOf("/bin/bash", "-l"))     // Shell command
    .setEnvironment(mapOf(                       // Full env map
        "TERM" to "xterm-256color",
        "PAGER" to "cat"
    ))
    .setDirectory("/path/to/workdir")            // Working directory
    .setInitialColumns(120)                      // Terminal width
    .setInitialRows(40)                          // Terminal height
    .setConsole(false)                           // true = console mode (Windows)
    .setRedirectErrorStream(true)                // Merge stderr into stdout
    .setLogFile(File("/tmp/pty.log"))            // Debug logging
    .setWindowsAnsiColorEnabled(true)            // Windows ANSI support
    .setUseWinConPty(true)                       // Windows: use ConPTY (modern)
    .setUnixOpenTtyToPreserveOutputAfterTermination(true) // Keep output after exit
    .start()                                     // Returns PtyProcess
```

### PtyProcess API (Complete)

PtyProcess extends `java.lang.Process`:

```kotlin
// I/O Streams
process.inputStream      // InputStream — read process stdout (blocking)
process.outputStream     // OutputStream — write to process stdin
process.errorStream      // InputStream — stderr (or dummy if redirected)

// Window size
process.setWinSize(WinSize(120, 40))  // Resize PTY
process.getWinSize()                   // Current WinSize(columns, rows)

// Process lifecycle
process.isAlive          // Boolean — still running?
process.pid()            // Long — process ID
process.waitFor()        // Int — blocks until exit, returns exit code
process.destroy()        // Send SIGTERM (Unix) / terminate (Windows)
process.destroyForcibly() // Send SIGKILL (Unix) / force terminate
// Unix only:
(process as? UnixPtyProcess)?.hangup()  // Send SIGHUP

// Exit
process.exitValue()      // Int — throws IllegalThreadStateException if still running

// Misc
process.enterKeyCode     // Byte — '\r' by default
process.isConsoleMode    // Boolean
```

### WinSize

```kotlin
data class WinSize(val columns: Int, val rows: Int)
// Also has: ws_xpixel, ws_ypixel (pixel dimensions, rarely used)
```

### Platform Implementations

**UnixPtyProcess:**
- Creates PTY via native JNA calls (fork + exec with pseudoterminal)
- Streams wrap native file descriptors via `Pty.getInputStream()/getOutputStream()`
- Reaper daemon thread waits for process exit and collects exit code
- `getInputStream()/getOutputStream()/getErrorStream()` are synchronized
- Termination signals: `destroy()` = SIGTERM(15), `destroyForcibly()` = SIGKILL(9), `hangup()` = SIGHUP(1)

**WinConPtyProcess (Windows ConPTY):**
- Uses Windows ConPTY API (modern, replaces WinPty)
- Creates paired pipes + PseudoConsole
- Custom `WinHandleInputStream`/`WinHandleOutputStream` wrap Windows handles
- `ReentrantLock` + `Condition` for thread-safe exit code collection
- `WaitForSingleObject` + `GetExitCodeProcess` for completion
- `getErrorStream()` returns null (stderr merged with stdout in ConPTY)
- `getWorkingDirectory()` and `getConsoleProcessCount()` available

### How IntelliJ Uses pty4j

In `LocalTerminalDirectRunner`:
1. `ShellStartupOptions` provides: command, env, initial terminal size, working directory
2. `LocalOptionsConfigurer.configureStartupOptions()` applies base env
3. `LocalTerminalCustomizer` extensions modify command/env per project
4. `PtyProcessBuilder` constructed with all options, `.start()` called
5. Process wrapped in `LocalTerminalTtyConnector` (UTF-8 charset)
6. `TtyConnector` attached to `ShellTerminalWidget` for UI rendering

### Reading Output: Blocking vs Non-blocking

pty4j streams are **blocking** only. There is no native non-blocking/async API.

**Recommended pattern (from pty4j tests):**
```kotlin
// Async reader on separate thread
val reader = thread(isDaemon = true, name = "pty-reader") {
    val buffer = ByteArray(8192)
    val input = process.inputStream
    while (true) {
        val len = input.read(buffer) // BLOCKS until data available or EOF
        if (len == -1) break
        val text = String(buffer, 0, len, Charsets.UTF_8)
        onOutput(text) // callback
    }
}

// Write to stdin
process.outputStream.write("ls -la\n".toByteArray())
process.outputStream.flush()
```

**For coroutine integration:**
```kotlin
val output = withContext(Dispatchers.IO) {
    val buffer = ByteArray(8192)
    val len = process.inputStream.read(buffer) // blocking, but on IO dispatcher
    String(buffer, 0, len, Charsets.UTF_8)
}
```

### Detecting Command Completion

**pty4j provides NO built-in completion detection.** You must implement your own:

1. **PS1 marker approach** (like OpenHands): Set a unique PS1 prompt, detect it in output
2. **Exit code marker** (practical): Run `command; echo "EXIT:$?"` and parse the marker
3. **Shell integration** (like IntelliJ terminal): Use OSC escape sequences for command tracking
4. **Process exit** (simple): For one-shot commands, use `process.waitFor()`

### Thread Safety

- `getInputStream()/getOutputStream()/getErrorStream()` are **synchronized** on UnixPtyProcess
- Concurrent read from InputStream by multiple threads: **not safe** (standard Java InputStream contract)
- Pattern: one reader thread, one writer thread, use synchronization for shared state
- `setWinSize()` and `getWinSize()` are synchronized

### Example: Spawn Shell, Run Command, Read Output, Kill

```kotlin
suspend fun executeCommand(
    command: String,
    workDir: String,
    timeoutMs: Long = 120_000
): ShellResult = withContext(Dispatchers.IO) {
    val env = HashMap(System.getenv()).apply {
        put("TERM", "xterm-256color")
        put("PAGER", "cat")
        put("GIT_PAGER", "cat")
    }

    // Use non-interactive shell for one-shot commands
    val process = PtyProcessBuilder()
        .setCommand(arrayOf("/bin/bash", "-c", command))
        .setEnvironment(env)
        .setDirectory(workDir)
        .setInitialColumns(200)
        .setInitialRows(50)
        .setRedirectErrorStream(true)
        .start()

    val output = StringBuilder()
    val readerJob = launch(Dispatchers.IO) {
        val buffer = ByteArray(8192)
        try {
            while (isActive) {
                val len = process.inputStream.read(buffer)
                if (len == -1) break
                output.append(String(buffer, 0, len, Charsets.UTF_8))
            }
        } catch (e: IOException) { /* stream closed */ }
    }

    val exitCode = try {
        withTimeout(timeoutMs) {
            val code = async(Dispatchers.IO) { process.waitFor() }
            code.await()
        }
    } catch (e: TimeoutCancellationException) {
        process.destroy() // SIGTERM
        delay(5000)
        if (process.isAlive) process.destroyForcibly() // SIGKILL
        null // timeout
    }

    readerJob.join()
    ShellResult(output.toString(), exitCode)
}
```

---

## 2. KOOG (JetBrains AI Agent SDK)

**Repo:** https://github.com/JetBrains/koog
**Website:** https://www.jetbrains.com/koog/
**Docs:** https://docs.koog.ai/
**API:** https://api.koog.ai/
**Current Version:** 0.7.3 (March 26, 2026)
**Status:** Kotlin Alpha (experimental, breaking changes expected)
**License:** Apache 2.0
**Released:** May 2025 (initial announcement), Java support March 2026

### What is Koog?

Koog is a JVM (Java and Kotlin) framework for building AI agents. It is Kotlin Multiplatform (JVM, JS, WasmJS, Android, iOS). It provides:
- Agent orchestration with ReAct/graph-based strategies
- Tool system with typed args/results
- Built-in persistence and state restoration
- History compression for token optimization
- LLM provider integrations
- Spring Boot and Ktor integrations

### Does it Have Shell Execution?

**Yes, but in an extension module (`agents-ext`), NOT in built-in tools.**

**Built-in tools (7 total, in `agents-tools`):**
1. `SayToUser` — send message to user
2. `AskUser` — ask user for input
3. `ExitTool` — end conversation
4. `ReadFileTool` — read file with line range
5. `EditFileTool` — targeted text replacement
6. `ListDirectoryTool` — hierarchical directory listing
7. `WriteFileTool` — write content to file

**Shell execution is in `agents-ext` module:**

#### ShellCommandExecutor Interface (`agents-ext/src/commonMain/`)

```kotlin
// ai.koog.agents.ext.tool.shell.ShellCommandExecutor
interface ShellCommandExecutor {
    suspend fun execute(
        command: String,           // e.g., "ls -la | grep txt"
        workingDirectory: String?, // Optional absolute path
        timeoutSeconds: Int        // Max execution time
    ): ExecutionResult

    data class ExecutionResult(
        val output: String,   // stdout + stderr combined
        val exitCode: Int?    // null if timed out/interrupted
    )
}
```

#### JvmShellCommandExecutor (`agents-ext/src/jvmMain/`)

Uses `java.lang.ProcessBuilder` (NOT pty4j):

```kotlin
// Platform detection
val shellCommand = if (isWindows) {
    listOf("cmd.exe", "/c", command)
} else {
    listOf("/bin/bash", "-c", command)
}

val process = ProcessBuilder(shellCommand)
    .apply { workingDirectory?.let { directory(File(it)) } }
    .start()
```

**Output capture:** Two coroutine jobs for stdout and stderr via `bufferedReader().useLines()`.
**Known issue (from source comments):** "The way output is collected potentially allows for race conditions" — collection starts after process startup, risking missed initial output.
**Timeout:** `withTimeoutOrNull(timeoutSeconds * 1000L) { process.onExit().await() }`
**Kill:** `destroyForcibly()` on timeout, including Windows descendant cleanup.
**Result:** Merged stdout + stderr + optional timeout message.

#### ExecuteShellCommandTool (`agents-ext/src/commonMain/`)

```kotlin
// ai.koog.agents.ext.tool.shell.ExecuteShellCommandTool
class ExecuteShellCommandTool(
    private val executor: ShellCommandExecutor,
    private val confirmationHandler: ShellCommandConfirmationHandler
) : Tool<Args, Result>() {

    // Tool name: "__execute_shell_command__"

    data class Args(
        val command: String,
        val timeoutSeconds: Int,
        val workingDirectory: String?
    ) : Tool.Args

    data class Result(
        val command: String,
        val exitCode: Int?,
        val output: String
    ) : Tool.Result

    override suspend fun execute(args: Args): Result {
        val confirmation = confirmationHandler.requestConfirmation(args)
        return when (confirmation) {
            is ShellCommandConfirmation.Approved -> {
                try {
                    val result = executor.execute(
                        args.command, args.workingDirectory, args.timeoutSeconds
                    )
                    Result(args.command, result.exitCode, result.output)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    Result(args.command, null, "Failed: ${e.message}")
                }
            }
            is ShellCommandConfirmation.Denied ->
                Result(args.command, null, "Denied: ${confirmation.userResponse}")
        }
    }
}
```

#### Confirmation Handlers

```kotlin
sealed class ShellCommandConfirmation {
    object Approved : ShellCommandConfirmation()
    data class Denied(val userResponse: String) : ShellCommandConfirmation()
}

fun interface ShellCommandConfirmationHandler {
    suspend fun requestConfirmation(args: ExecuteShellCommandTool.Args): ShellCommandConfirmation
}

// Two built-in handlers:
object BraveModeConfirmationHandler : ShellCommandConfirmationHandler {
    // Auto-approves everything
    override suspend fun requestConfirmation(args) = ShellCommandConfirmation.Approved
}

class PrintShellCommandConfirmationHandler : ShellCommandConfirmationHandler {
    // Console prompt: shows command, waits for y/yes input
}
```

### Can We Use Just the Shell Component?

**Technically yes, but it's not worth it.** The shell execution in Koog is:
- A simple `ProcessBuilder` wrapper (~100 lines of actual logic)
- Has known race conditions in output capture
- No PTY support (no interactive commands, no window sizing)
- No output streaming (waits for completion)
- No output capping
- No persistent shell sessions

The confirmation handler pattern is the only interesting piece we could adopt.

### Comparison: Koog Shell vs Building Our Own with pty4j

| Feature | Koog Shell | pty4j-based |
|---------|-----------|-------------|
| PTY support | No (ProcessBuilder) | Yes (real pseudoterminal) |
| Interactive commands | No | Yes |
| Window sizing | No | Yes (setWinSize) |
| ANSI colors | Partial | Full |
| Output streaming | No (waits for completion) | Yes (InputStream) |
| Persistent sessions | No (new process per command) | Yes (possible) |
| Output capping | No | Must implement |
| Timeout | Basic (coroutine timeout) | Must implement |
| Confirmation hooks | Yes (nice pattern) | Must implement |
| Production ready | Alpha | Stable (used by all JetBrains IDEs) |

**Verdict:** Koog's shell execution is too basic. Build our own with pty4j, borrow the confirmation handler pattern from Koog.

---

## 3. IntelliJ Platform Process APIs

### GeneralCommandLine

The standard way to construct a process command in IntelliJ.

```kotlin
val cmd = GeneralCommandLine()
    .withExePath("/bin/bash")
    .withParameters("-c", "ls -la")
    .withWorkDirectory("/path/to/dir")
    .withEnvironment("TERM", "xterm-256color")
    .withEnvironment(mapOf("PAGER" to "cat"))
    .withCharset(Charsets.UTF_8)
    .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE) // CONSOLE, SYSTEM, NONE

val process: Process = cmd.createProcess()  // throws ExecutionException
```

**Key features:**
- OS-independent quoting and escaping
- `getCommandLineString()` for display (NOT for execution)
- `getPreparedCommandLine(Platform)` for platform-specific output
- `ParentEnvironmentType.CONSOLE` inherits user's shell env

### OSProcessHandler

The core process monitoring class. Wraps a Process and captures output.

```kotlin
val handler = OSProcessHandler(cmd)  // or OSProcessHandler(process, cmdLine, charset)

handler.addProcessListener(object : ProcessListener {
    override fun startNotified(event: ProcessEvent) { /* process started */ }
    override fun onTextAvailable(event: ProcessEvent, outputType: Key) {
        val text = event.text
        val isStdout = outputType == ProcessOutputTypes.STDOUT
        val isStderr = outputType == ProcessOutputTypes.STDERR
        // STREAMING: called for each chunk of output
    }
    override fun processTerminated(event: ProcessEvent) {
        val exitCode = event.exitCode
    }
    override fun processWillTerminate(event: ProcessEvent, willBeDestroyed: Boolean) { }
})

handler.startNotify()  // BEGIN capturing output

// Control
handler.waitFor()                    // Block until process ends (true = ended, false = detached)
handler.waitFor(timeoutMs)           // With timeout
handler.destroyProcess()             // Terminate
handler.setShouldDestroyProcessRecursively(true)  // Kill child processes too

// PTY mode
handler.setHasPty(true)  // Must call BEFORE startNotify(). Switches to blocking reads.
```

**Threading:** `checkEdtAndReadAction()` validates that you're not waiting on EDT or inside ReadAction.

### KillableProcessHandler

Extends OSProcessHandler with two-phase kill.

```kotlin
val handler = KillableProcessHandler(cmd)
// Or with mediator (Windows soft-kill support):
val handler = KillableProcessHandler(cmd, withMediator = true)

handler.setShouldKillProcessSoftly(true)  // SIGINT first (default behavior)
handler.canKillProcess()  // true if killable
handler.killProcess()     // Hard kill (SIGKILL)

// Stop button behavior:
// 1st press: SIGINT (soft kill, allows graceful shutdown)
// 2nd press: SIGKILL (hard kill, immediate termination)
```

**Windows mediator:** When `withMediator=true`, spawns a mediator process that enables SIGINT-like behavior on Windows (which doesn't natively support it).

### CapturingProcessHandler

Runs a process and captures ALL output into a `ProcessOutput` object.

```kotlin
val handler = CapturingProcessHandler(cmd)

// Synchronous execution
val output: ProcessOutput = handler.runProcess()                    // No timeout
val output: ProcessOutput = handler.runProcess(timeoutMs = 30_000)  // With timeout
val output: ProcessOutput = handler.runProcess(
    timeoutInMilliseconds = 30_000,
    destroyOnTimeout = true  // Kill if timeout
)

// With progress indicator (shows in IDE status bar)
val output: ProcessOutput = handler.runProcessWithProgressIndicator(
    ProgressManager.getInstance().progressIndicator
)

// ProcessOutput API:
output.stdout          // String — all stdout
output.stderr          // String — all stderr
output.exitCode        // Int
output.isTimeout       // Boolean
output.isCancelled     // Boolean
output.stdoutLines     // List<String>
output.stderrLines     // List<String>
```

**Use case:** Simple fire-and-forget commands where you need all output at once. NOT suitable for streaming or interactive processes.

### ColoredProcessHandler

Extends KillableProcessHandler with ANSI escape sequence decoding.

```kotlin
val handler = ColoredProcessHandler(cmd)

// ANSI processing pipeline:
// 1. Raw text arrives via notifyTextAvailable()
// 2. AnsiEscapeDecoder processes escape sequences
// 3. coloredTextAvailable() delivers decoded colored text
// 4. Raw text listeners get unprocessed output

// Custom colored text handling:
class MyHandler(cmd: GeneralCommandLine) : ColoredProcessHandler(cmd) {
    override fun coloredTextAvailable(text: String, attributes: Key) {
        super.coloredTextAvailable(text, attributes)
        // Custom handling of ANSI-decoded text
    }
}

// Note: soft-kill is OFF by default for compatibility
```

### TerminalExecutionConsole

ConsoleView that wraps a PTY process for terminal-like output display.

```kotlin
val console = TerminalExecutionConsole(project, processHandler)

// Output control
console.print("text", ConsoleViewContentType.NORMAL_OUTPUT)
console.clear()
console.printHyperlink("click me", hyperlinkInfo)
console.setOutputPaused(true)
console.performWhenNoDeferredOutput { /* all output flushed */ }

// Filters
console.addMessageFilter(myFilter)  // Highlight/filter output
console.allowHeavyFilters()          // Enable expensive filters

// Configuration
console.withEnterKeyDefaultCodeEnabled(true)
```

**Key:** `TerminalExecutionConsole` implements `ConsoleView` and `ObservableConsoleView`. It can be used in Run/Debug tool windows. The `attachToProcessOutput` parameter controls whether it auto-prints output or expects external management.

### How IntelliJ's "Run" Tab Captures Output

```
GeneralCommandLine
    → OSProcessHandler(cmd)
    → handler.addProcessListener(consoleAdapter)
    → handler.startNotify()
    → ExecutionConsole displays output
    → RunContentManager shows in "Run" tool window
```

For PTY-based execution (e.g., "Run with terminal emulation"):
```
GeneralCommandLine
    → ProcessHandlerFactory.createKillableColoredHandler(cmd, /* PTY */ true)
    → TerminalExecutionConsole(project, handler)
    → Run tab shows terminal-like output with ANSI colors
```

### Embedded Terminal API

Requires dependency: `org.jetbrains.plugins.terminal` plugin.

```kotlin
// Get terminal manager
val manager = TerminalToolWindowTabsManager.getInstance(project)

// Create terminal tab
manager.createTabBuilder()

// Send commands
val terminalView = TerminalView.DATA_KEY.getData(dataContext)
terminalView?.sendText("ls -la")

// Enhanced sending
TerminalSendTextBuilder()
    .shouldExecute()           // Auto-press Enter
    .useBracketedPasteMode()   // Proper paste handling

// Shell integration (Bash, Zsh, PowerShell only)
terminalView?.shellIntegrationDeferred  // Access shell info, command listeners
```

---

## 4. Comparison Matrix for AI Agent run_command Tool

| Feature | pty4j | Koog Shell | GeneralCommandLine + OSProcessHandler | CapturingProcessHandler | TerminalExecutionConsole |
|---------|-------|------------|--------------------------------------|------------------------|------------------------|
| **Streaming output** | Yes (InputStream) | No | Yes (ProcessListener) | No (batch) | Yes (ConsoleView) |
| **Interactive input** | Yes (OutputStream) | No | Limited | No | Yes (sendText) |
| **PTY/ANSI colors** | Yes | No | No (unless ColoredProcessHandler) | No | Yes |
| **Timeout handling** | Manual | Basic (coroutine) | Manual (waitFor with timeout) | Built-in | No |
| **Output capping** | Manual | No | Manual | No | No |
| **Process tree kill** | Manual (SIGKILL) | destroyForcibly | setShouldDestroyProcessRecursively | destroyOnTimeout | No |
| **Window resize** | Yes (setWinSize) | No | No | No | No |
| **IDE integration** | None | None | Full (Run tab) | Full | Full (Terminal) |
| **Already on classpath** | Via Terminal plugin | No (external dep) | Yes (platform-api) | Yes (platform-api) | Via Terminal plugin |
| **Thread safety** | Synchronized streams | N/A | Built-in reader threads | Single-threaded | Built-in |
| **Suitable for agent** | Best for interactive | Basic only | Best for non-interactive | Simple commands only | Overkill (UI-heavy) |

---

## 5. Recommendation for Agent run_command Tool

### Primary: GeneralCommandLine + KillableProcessHandler (non-interactive)

For 90% of agent commands (git, grep, build, test), use the IntelliJ-native approach:

```kotlin
class AgentShellExecutor(private val project: Project) {
    suspend fun execute(
        command: String,
        workDir: String,
        timeoutMs: Long = 120_000,
        maxOutputBytes: Int = 1_048_576 // 1MB
    ): ToolResult<ShellOutput> = withContext(Dispatchers.IO) {
        val cmd = GeneralCommandLine("/bin/bash", "-c", command)
            .withWorkDirectory(workDir)
            .withEnvironment("PAGER", "cat")
            .withEnvironment("GIT_PAGER", "cat")
            .withCharset(Charsets.UTF_8)

        val handler = KillableProcessHandler(cmd)
        handler.setShouldDestroyProcessRecursively(true)
        handler.setShouldKillProcessSoftly(true)

        val output = StringBuilder()
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key) {
                if (output.length < maxOutputBytes) {
                    output.append(event.text)
                }
            }
        })
        handler.startNotify()

        val completed = handler.waitFor(timeoutMs)
        if (!completed) {
            handler.killProcess()
        }

        ToolResult(
            data = ShellOutput(output.toString(), handler.exitCode, !completed),
            summary = "Command ${if (completed) "completed" else "timed out"}"
        )
    }
}
```

### Secondary: pty4j (interactive/PTY-requiring commands)

For commands needing a real terminal (npm interactive prompts, docker attach, etc.), use pty4j directly. Keep it as an escape hatch, not the default.

### Avoid:
- **CapturingProcessHandler** for agent use — no streaming, blocks thread
- **TerminalExecutionConsole** for agent use — too much UI overhead
- **Koog's ShellCommandExecutor** — too basic, known race conditions, adds framework dependency

---

## Sources

### Source Code Analyzed
- [pty4j PtyProcessBuilder](https://github.com/JetBrains/pty4j/blob/master/src/com/pty4j/PtyProcessBuilder.java)
- [pty4j PtyProcess](https://github.com/JetBrains/pty4j/blob/master/src/com/pty4j/PtyProcess.java)
- [pty4j UnixPtyProcess](https://github.com/JetBrains/pty4j/blob/master/src/com/pty4j/unix/UnixPtyProcess.java)
- [pty4j WinConPtyProcess](https://github.com/JetBrains/pty4j/blob/master/src/com/pty4j/windows/conpty/WinConPtyProcess.java)
- [pty4j PtyTest](https://github.com/JetBrains/pty4j/blob/master/test/com/pty4j/PtyTest.java)
- [Koog ExecuteShellCommandTool](https://github.com/JetBrains/koog/blob/develop/agents/agents-ext/src/commonMain/kotlin/ai/koog/agents/ext/tool/shell/ExecuteShellCommandTool.kt)
- [Koog JvmShellCommandExecutor](https://github.com/JetBrains/koog/blob/develop/agents/agents-ext/src/jvmMain/kotlin/ai/koog/agents/ext/tool/shell/JvmShellCommandExecutor.kt)
- [Koog ShellCommandConfirmation](https://github.com/JetBrains/koog/blob/develop/agents/agents-ext/src/commonMain/kotlin/ai/koog/agents/ext/tool/shell/ShellCommandConfirmation.kt)
- [IntelliJ LocalTerminalDirectRunner](https://github.com/JetBrains/intellij-community/blob/master/plugins/terminal/src/org/jetbrains/plugins/terminal/LocalTerminalDirectRunner.java)
- [IntelliJ ColoredProcessHandler](https://github.com/JetBrains/intellij-community/blob/master/platform/platform-util-io/src/com/intellij/execution/process/ColoredProcessHandler.java)

### API Documentation
- [GeneralCommandLine API](https://dploeger.github.io/intellij-api-doc/com/intellij/execution/configurations/GeneralCommandLine.html)
- [OSProcessHandler API](https://dploeger.github.io/intellij-api-doc/com/intellij/execution/process/OSProcessHandler.html)
- [KillableProcessHandler API](https://dploeger.github.io/intellij-api-doc/com/intellij/execution/process/KillableProcessHandler.html)
- [CapturingProcessHandler API](https://dploeger.github.io/intellij-api-doc/com/intellij/execution/process/CapturingProcessHandler.html)
- [TerminalExecutionConsole API](https://dploeger.github.io/intellij-api-doc/com/intellij/terminal/TerminalExecutionConsole.html)
- [IntelliJ Embedded Terminal SDK](https://plugins.jetbrains.com/docs/intellij/embedded-terminal.html)
- [IntelliJ Execution SDK](https://plugins.jetbrains.com/docs/intellij/execution.html)

### Koog Documentation
- [Koog Built-in Tools](https://docs.koog.ai/built-in-tools/)
- [Koog Tools Overview](https://docs.koog.ai/tools-overview/)
- [Koog agents-tools API](https://api.koog.ai/agents/agents-tools/index.html)
- [JetBrains Blog: Building AI Agents in Kotlin Part 2](https://blog.jetbrains.com/ai/2025/11/building-ai-agents-in-kotlin-part-2-a-deeper-dive-into-tools/)
- [JetBrains Blog: Meet Koog](https://blog.jetbrains.com/ai/2025/05/meet-koog-empowering-kotlin-developers-to-build-ai-agents/)
