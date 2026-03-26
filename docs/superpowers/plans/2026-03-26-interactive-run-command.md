# Interactive RunCommandTool Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `run_command` interactive — detect idle processes, allow LLM/user to send stdin, kill from UI.

**Architecture:** Extend `ProcessRegistry` to hold stdin handles and track output timestamps. Replace `process.waitFor()` blocking with a coroutine monitor loop. Three new tools (`send_stdin`, `ask_user_input`, `kill_process`) interact with the running process. A periodic reaper kills abandoned processes.

**Tech Stack:** Kotlin coroutines, `ProcessBuilder` stdin/stdout, JCEF bridge, React/Zustand, JUnit 5 + MockK

**Spec:** `docs/superpowers/specs/2026-03-26-interactive-run-command-design.md`

---

### Task 1: Extend ProcessRegistry with ManagedProcess

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ProcessRegistry.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/ProcessRegistryTest.kt`

- [ ] **Step 1: Write failing tests for ManagedProcess and new ProcessRegistry methods**

```kotlin
// ProcessRegistryTest.kt
package com.workflow.orchestrator.agent.runtime

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

class ProcessRegistryTest {

    @AfterEach
    fun cleanup() {
        ProcessRegistry.killAll()
    }

    @Test
    fun `register and get managed process`() {
        val process = ProcessBuilder("sleep", "10").start()
        val managed = ProcessRegistry.register("test-1", process, "sleep 10")

        assertNotNull(managed)
        assertEquals("test-1", managed.toolCallId)
        assertEquals("sleep 10", managed.command)
        assertTrue(managed.process.isAlive)

        val retrieved = ProcessRegistry.get("test-1")
        assertSame(managed, retrieved)

        process.destroyForcibly()
    }

    @Test
    fun `get returns null for unknown ID`() {
        assertNull(ProcessRegistry.get("nonexistent"))
    }

    @Test
    fun `writeStdin sends data to process`() {
        // Start cat which echoes stdin to stdout
        val process = ProcessBuilder("cat").start()
        ProcessRegistry.register("stdin-test", process, "cat")

        val written = ProcessRegistry.writeStdin("stdin-test", "hello\n")
        assertTrue(written)

        // Read the echoed output
        Thread.sleep(200)
        val output = process.inputStream.bufferedReader().readLine()
        assertEquals("hello", output)

        process.destroyForcibly()
    }

    @Test
    fun `writeStdin returns false for dead process`() {
        val process = ProcessBuilder("echo", "done").start()
        ProcessRegistry.register("dead-test", process, "echo done")
        process.waitFor()
        Thread.sleep(100)

        val written = ProcessRegistry.writeStdin("dead-test", "input\n")
        assertFalse(written)
    }

    @Test
    fun `writeStdin returns false for unknown ID`() {
        assertFalse(ProcessRegistry.writeStdin("unknown", "data"))
    }

    @Test
    fun `kill removes and destroys process`() {
        val process = ProcessBuilder("sleep", "60").start()
        ProcessRegistry.register("kill-test", process, "sleep 60")

        assertTrue(ProcessRegistry.kill("kill-test"))
        assertNull(ProcessRegistry.get("kill-test"))

        Thread.sleep(200)
        assertFalse(process.isAlive)
    }

    @Test
    fun `reapIdleProcesses kills processes idle past threshold`() {
        val process = ProcessBuilder("sleep", "60").start()
        val managed = ProcessRegistry.register("reap-test", process, "sleep 60")

        // Simulate idle signal sent 70 seconds ago
        managed.idleSignaledAt.set(System.currentTimeMillis() - 70_000)

        ProcessRegistry.reapIdleProcesses(maxIdleSinceSignalMs = 60_000)

        assertNull(ProcessRegistry.get("reap-test"))
        Thread.sleep(200)
        assertFalse(process.isAlive)
    }

    @Test
    fun `reapIdleProcesses does not kill non-idle processes`() {
        val process = ProcessBuilder("sleep", "60").start()
        ProcessRegistry.register("no-reap-test", process, "sleep 60")
        // idleSignaledAt defaults to 0 (not idle)

        ProcessRegistry.reapIdleProcesses(maxIdleSinceSignalMs = 60_000)

        assertNotNull(ProcessRegistry.get("no-reap-test"))
        assertTrue(process.isAlive)

        process.destroyForcibly()
    }

    @Test
    fun `stdinCount tracks writes per process`() {
        val process = ProcessBuilder("cat").start()
        val managed = ProcessRegistry.register("count-test", process, "cat")

        assertEquals(0, managed.stdinCount.get())
        ProcessRegistry.writeStdin("count-test", "a\n")
        assertEquals(1, managed.stdinCount.get())
        ProcessRegistry.writeStdin("count-test", "b\n")
        assertEquals(2, managed.stdinCount.get())

        process.destroyForcibly()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.runtime.ProcessRegistryTest" -x instrumentCode`
Expected: Compilation errors — `ManagedProcess`, `register(id, process, command)`, `get()`, `writeStdin()`, `reapIdleProcesses()`, `stdinCount` don't exist yet.

- [ ] **Step 3: Implement ManagedProcess and extend ProcessRegistry**

```kotlin
// ProcessRegistry.kt
package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.diagnostic.Logger
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A process managed by the agent with stdin access and activity tracking.
 */
data class ManagedProcess(
    val process: Process,
    val stdin: OutputStream,
    val lastOutputAt: AtomicLong = AtomicLong(0),
    val outputLines: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue(),
    val toolCallId: String,
    val command: String,
    val startedAt: Long = System.currentTimeMillis(),
    val idleSignaledAt: AtomicLong = AtomicLong(0),
    val stdinCount: AtomicInteger = AtomicInteger(0)
)

/**
 * Tracks running processes by tool call ID for kill/stdin/reaper support.
 */
object ProcessRegistry {

    private val log = Logger.getInstance(ProcessRegistry::class.java)
    private val running = ConcurrentHashMap<String, ManagedProcess>()

    fun register(toolCallId: String, process: Process, command: String): ManagedProcess {
        val managed = ManagedProcess(
            process = process,
            stdin = process.outputStream,
            toolCallId = toolCallId,
            command = command
        )
        running[toolCallId] = managed
        log.info("[ProcessRegistry] Registered process: $toolCallId (${command.take(60)})")
        return managed
    }

    fun unregister(id: String) {
        running.remove(id)
    }

    fun get(id: String): ManagedProcess? = running[id]

    fun kill(id: String): Boolean {
        val managed = running.remove(id) ?: return false
        log.info("[ProcessRegistry] Killing process: $id")
        managed.process.destroyForcibly()
        return true
    }

    fun killAll() {
        log.info("[ProcessRegistry] Killing all ${running.size} running processes")
        running.forEach { (id, managed) ->
            managed.process.destroyForcibly()
            log.info("[ProcessRegistry] Killed: $id")
        }
        running.clear()
    }

    fun writeStdin(id: String, input: String): Boolean {
        val managed = running[id] ?: return false
        return try {
            if (!managed.process.isAlive) return false
            managed.stdin.write(input.toByteArray())
            managed.stdin.flush()
            managed.stdinCount.incrementAndGet()
            true
        } catch (e: IOException) {
            log.warn("[ProcessRegistry] writeStdin failed for $id: ${e.message}")
            false
        }
    }

    fun isRunning(id: String): Boolean = running[id]?.process?.isAlive == true

    fun runningCount(): Int = running.size

    /**
     * Kill processes that received an idle signal more than [maxIdleSinceSignalMs] ago
     * with no subsequent interaction (stdin write resets idleSignaledAt to 0).
     */
    fun reapIdleProcesses(maxIdleSinceSignalMs: Long = 60_000) {
        val now = System.currentTimeMillis()
        val toReap = running.entries.filter { (_, m) ->
            val signaled = m.idleSignaledAt.get()
            signaled > 0 && now - signaled > maxIdleSinceSignalMs
        }
        for ((id, managed) in toReap) {
            log.info("[ProcessRegistry] Reaping idle process: $id (idle for ${now - managed.idleSignaledAt.get()}ms)")
            managed.process.destroyForcibly()
            running.remove(id)
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.runtime.ProcessRegistryTest" -x instrumentCode`
Expected: All 7 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ProcessRegistry.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/ProcessRegistryTest.kt
git commit -m "feat(agent): extend ProcessRegistry with ManagedProcess, stdin, reaper"
```

---

### Task 2: Refactor RunCommandTool to Event-Driven Monitor

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RunCommandTool.kt`
- Modify: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/RunCommandToolTest.kt`

- [ ] **Step 1: Write failing test for idle detection**

Add to `RunCommandToolTest.kt`:

```kotlin
@Test
fun `execute returns IDLE for process waiting on stdin`() = runTest {
    val tool = RunCommandTool()
    val params = buildJsonObject {
        put("command", "read -p 'Enter name: ' name")
        put("description", "Read user input")
        put("idle_timeout", 2)  // 2 seconds for fast test
    }

    val result = tool.execute(params, project)

    assertFalse(result.isError)
    assertTrue(result.content.contains("[IDLE]"))
    assertTrue(result.content.contains("Enter name:"))
    assertTrue(result.content.contains("process_id"))
}

@Test
fun `execute returns exit result for fast-completing command`() = runTest {
    val tool = RunCommandTool()
    val params = buildJsonObject {
        put("command", "echo hello")
        put("description", "Echo test")
    }

    val result = tool.execute(params, project)

    assertFalse(result.isError)
    assertTrue(result.content.contains("Exit code: 0"))
    assertTrue(result.content.contains("hello"))
    assertFalse(result.content.contains("[IDLE]"))
}
```

- [ ] **Step 2: Run tests to verify the idle test fails**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.tools.builtin.RunCommandToolTest" -x instrumentCode`
Expected: `execute returns IDLE for process waiting on stdin` FAILS (no `[IDLE]` in output).

- [ ] **Step 3: Rewrite RunCommandTool.execute() with event-driven monitor**

Replace the blocking `process.waitFor(timeoutSeconds)` section (lines 249-324 approximately) with:

```kotlin
// In RunCommandTool.kt, inside execute() after process starts and reader thread is launched:

val process = processBuilder.start()
val toolCallId = currentToolCallId.get()
    ?: "run-cmd-${processIdCounter.incrementAndGet()}"

// Register with stdin handle
val managed = ProcessRegistry.register(toolCallId, process, command)

// Determine idle threshold
val idleTimeoutParam = params["idle_timeout"]?.jsonPrimitive?.intOrNull?.toLong()
val idleThresholdMs = when {
    idleTimeoutParam != null -> idleTimeoutParam * 1000
    isLikelyBuildCommand(command) -> BUILD_IDLE_THRESHOLD_MS
    else -> DEFAULT_IDLE_THRESHOLD_MS
}

// Buffer-based output reader thread
val activeStreamCallback = streamCallback
val readerThread = Thread {
    try {
        process.inputStream.bufferedReader().use { reader ->
            val buffer = CharArray(4096)
            var charsRead: Int
            while (reader.read(buffer).also { charsRead = it } != -1) {
                val chunk = String(buffer, 0, charsRead)
                managed.outputLines.add(chunk)
                managed.lastOutputAt.set(System.currentTimeMillis())
                activeStreamCallback?.invoke(toolCallId, chunk)
            }
        }
    } catch (_: Exception) {
        // Process killed or stream closed
    }
}.apply {
    isDaemon = true
    name = "RunCommand-Output-$toolCallId"
    start()
}

// Event-driven monitor loop
val timeoutMs = timeoutSeconds * 1000
val startMs = managed.startedAt

while (true) {
    kotlinx.coroutines.delay(500)
    val now = System.currentTimeMillis()

    // Priority 1: process exited
    if (!process.isAlive) {
        readerThread.join(2000)
        ProcessRegistry.unregister(toolCallId)
        return buildExitResult(managed, command, params)
    }

    // Priority 2: total timeout
    if (now - startMs > timeoutMs) {
        ProcessRegistry.kill(toolCallId)
        readerThread.join(1000)
        return buildTimeoutResult(managed, timeoutSeconds)
    }

    // Priority 3: idle detection (only after first output)
    val lastOutput = managed.lastOutputAt.get()
    if (lastOutput > 0 && now - lastOutput >= idleThresholdMs) {
        managed.idleSignaledAt.set(now)
        return buildIdleResult(managed, idleThresholdMs / 1000)
    }
}
```

Add the helper methods and constants:

```kotlin
companion object {
    // ... existing constants ...
    private const val DEFAULT_IDLE_THRESHOLD_MS = 15_000L
    private const val BUILD_IDLE_THRESHOLD_MS = 60_000L

    private val BUILD_COMMAND_PREFIXES = listOf(
        "gradle", "./gradlew", "gradlew", "mvn", "./mvnw", "mvnw",
        "npm", "yarn", "pnpm", "docker build", "cargo build", "go build",
        "dotnet build", "make", "cmake"
    )

    fun isLikelyBuildCommand(command: String): Boolean {
        val trimmed = command.trim()
        return BUILD_COMMAND_PREFIXES.any { trimmed.startsWith(it) }
    }

    private val ANSI_REGEX = Regex("\u001B\\[[;\\d]*[A-Za-z]")
    fun stripAnsi(text: String): String = text.replace(ANSI_REGEX, "")
}

private fun collectOutput(managed: ManagedProcess): String {
    return managed.outputLines.joinToString("")
}

private fun lastOutputLines(managed: ManagedProcess, lineCount: Int = 10): String {
    val full = collectOutput(managed)
    val lines = full.lines()
    return if (lines.size <= lineCount) full
    else lines.takeLast(lineCount).joinToString("\n")
}

private fun buildExitResult(managed: ManagedProcess, command: String, params: JsonObject): ToolResult {
    val rawOutput = collectOutput(managed)
    val truncated = if (rawOutput.length > MAX_OUTPUT_CHARS) {
        ToolOutputStore.middleTruncate(rawOutput, MAX_OUTPUT_CHARS) +
            "\n\n[Total output: ${rawOutput.length} chars.]"
    } else rawOutput

    val exitCode = managed.process.exitValue()
    val description = params["description"]?.jsonPrimitive?.content
    val summary = if (description != null) "$description — exit code $exitCode"
    else "Command exited with code $exitCode: ${command.take(80)}"

    return ToolResult(
        content = "Exit code: $exitCode\n$truncated",
        summary = summary,
        tokenEstimate = TokenEstimator.estimate(truncated),
        isError = exitCode != 0
    )
}

private fun buildTimeoutResult(managed: ManagedProcess, timeoutSeconds: Long): ToolResult {
    val rawOutput = collectOutput(managed)
    val truncated = if (rawOutput.length > MAX_OUTPUT_CHARS) {
        ToolOutputStore.middleTruncate(rawOutput, MAX_OUTPUT_CHARS)
    } else rawOutput

    return ToolResult(
        "[TIMEOUT] Command timed out after ${timeoutSeconds}s.\nPartial output:\n$truncated",
        "Error: command timed out",
        TokenEstimator.estimate(truncated),
        isError = true
    )
}

private fun buildIdleResult(managed: ManagedProcess, idleSeconds: Long): ToolResult {
    val lastLines = stripAnsi(lastOutputLines(managed))
    val processId = managed.toolCallId
    val cmd = managed.command.take(80)

    val passwordWarning = if (isLikelyPasswordPrompt(lastLines))
        "\nWARNING: Last output appears to be a password/credential prompt. Use ask_user_input, not send_stdin."
    else ""

    val content = """
        |[IDLE] Process idle for ${idleSeconds}s — no output since last line.
        |Process still running (ID: $processId, command: $cmd).
        |
        |Last output:
        |${lastLines.lines().joinToString("\n") { "  $it" }}
        |$passwordWarning
        |Options:
        |- send_stdin(process_id="$processId", input="<your input>\n") to provide input
        |- ask_user_input(process_id="$processId", description="...", prompt="...") for user input
        |- kill_process(process_id="$processId") to abort
    """.trimMargin()

    return ToolResult(
        content = content,
        summary = "Process idle — waiting for input (ID: $processId)",
        tokenEstimate = TokenEstimator.estimate(content),
        isError = false
    )
}

private val PASSWORD_PATTERNS = listOf(
    Regex("""(?i)password\s*:"""),
    Regex("""(?i)passphrase\s*:"""),
    Regex("""(?i)enter\s+.*token"""),
    Regex("""(?i)secret\s*:"""),
    Regex("""(?i)credentials?\s*:"""),
    Regex("""(?i)api.?key\s*:"""),
)

private fun isLikelyPasswordPrompt(lastOutput: String): Boolean =
    PASSWORD_PATTERNS.any { it.containsMatchIn(lastOutput.takeLast(300)) }
```

Update the `parameters` definition to include `idle_timeout`:

```kotlin
override val parameters = FunctionParameters(
    properties = mapOf(
        "command" to ParameterProperty(type = "string", description = "The shell command to execute."),
        "working_dir" to ParameterProperty(type = "string", description = "Working directory (absolute or relative to project root). Defaults to project root."),
        "description" to ParameterProperty(type = "string", description = "Brief description of what this command does (5-10 words)."),
        "timeout" to ParameterProperty(type = "integer", description = "Total timeout in seconds. Default: 120, max: 600."),
        "idle_timeout" to ParameterProperty(type = "integer", description = "Idle detection threshold in seconds. Default: 15 (60 for build commands). Process returns [IDLE] if no output for this many seconds.")
    ),
    required = listOf("command", "description")
)
```

Update the `description`:

```kotlin
override val description = "Execute a shell command in the project directory. If the process goes idle (no output), returns [IDLE] with the process ID — use send_stdin, ask_user_input, or kill_process to interact. Default timeout: 120s, output limit: 30000 chars. Dangerous commands are blocked."
```

- [ ] **Step 4: Run all RunCommandTool tests**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.tools.builtin.RunCommandToolTest" -x instrumentCode`
Expected: All tests PASS including the new idle detection test.

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RunCommandTool.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/RunCommandToolTest.kt
git commit -m "feat(agent): event-driven RunCommandTool with idle detection"
```

---

### Task 3: Implement send_stdin Tool

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SendStdinTool.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/SendStdinToolTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
// SendStdinToolTest.kt
package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.runtime.ProcessRegistry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SendStdinToolTest {

    private val project = mockk<Project> { every { basePath } returns "/tmp" }

    @AfterEach
    fun cleanup() {
        ProcessRegistry.killAll()
    }

    @Test
    fun `returns error for unknown process ID`() = runTest {
        val tool = SendStdinTool()
        val params = buildJsonObject {
            put("process_id", "nonexistent")
            put("input", "y\n")
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("not found"))
    }

    @Test
    fun `returns error when stdin limit exceeded`() = runTest {
        val process = ProcessBuilder("cat").start()
        val managed = ProcessRegistry.register("limit-test", process, "cat")
        managed.stdinCount.set(10) // Already at limit

        val tool = SendStdinTool()
        val params = buildJsonObject {
            put("process_id", "limit-test")
            put("input", "more\n")
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("stdin limit reached"))

        process.destroyForcibly()
    }

    @Test
    fun `sends input and returns new output`() = runTest {
        // Start a process that echoes stdin: read line, print it
        val process = ProcessBuilder("sh", "-c", "read line; echo \"Got: \$line\"").start()
        val managed = ProcessRegistry.register("echo-test", process, "sh -c read/echo")
        // Start a reader to populate lastOutputAt
        Thread {
            process.inputStream.bufferedReader().forEachLine { line ->
                managed.outputLines.add(line + "\n")
                managed.lastOutputAt.set(System.currentTimeMillis())
            }
        }.apply { isDaemon = true; start() }

        val tool = SendStdinTool()
        val params = buildJsonObject {
            put("process_id", "echo-test")
            put("input", "hello\n")
        }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("Got: hello"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.tools.builtin.SendStdinToolTest" -x instrumentCode`
Expected: Compilation error — `SendStdinTool` doesn't exist.

- [ ] **Step 3: Implement SendStdinTool**

```kotlin
// SendStdinTool.kt
package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.ProcessRegistry
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class SendStdinTool : AgentTool {
    override val name = "send_stdin"
    override val description = "Send input to a running process's stdin. Use when a command is waiting for " +
        "input that you can determine from context (e.g., confirmation prompts, menu selections). " +
        "NEVER use for passwords, tokens, or secrets — use ask_user_input instead. Max 10 sends per process."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "process_id" to ParameterProperty(type = "string", description = "The process ID from the [IDLE] message."),
            "input" to ParameterProperty(type = "string", description = "Text to send to stdin. Include \\n for Enter key.")
        ),
        required = listOf("process_id", "input")
    )
    override val allowedWorkers = setOf(WorkerType.CODER)

    companion object {
        private const val MAX_STDIN_PER_PROCESS = 10
        private const val MONITOR_POLL_MS = 500L
        private const val IDLE_AFTER_STDIN_MS = 10_000L // 10s idle after stdin before returning
        private const val MAX_WAIT_AFTER_STDIN_MS = 60_000L // 60s max wait after stdin
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val processId = params["process_id"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'process_id' parameter required", "Error: missing process_id", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val input = params["input"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'input' parameter required", "Error: missing input", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val managed = ProcessRegistry.get(processId)
            ?: return ToolResult("Error: Process '$processId' not found or already exited.", "Error: process not found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        if (!managed.process.isAlive) {
            return ToolResult("Error: Process '$processId' has already exited.", "Error: process exited", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        // Rate limit
        if (managed.stdinCount.get() >= MAX_STDIN_PER_PROCESS) {
            return ToolResult(
                "Error: stdin limit reached for this process (${MAX_STDIN_PER_PROCESS}/$MAX_STDIN_PER_PROCESS). " +
                    "Kill the process with kill_process(process_id=\"$processId\") and run a non-interactive command instead.",
                "Error: stdin limit reached",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        // Password prompt check
        val lastOutput = managed.outputLines.toList().joinToString("").takeLast(300)
        if (RunCommandTool.isLikelyPasswordPrompt(lastOutput)) {
            return ToolResult(
                "Error: Last output appears to be a password/credential prompt. " +
                    "Use ask_user_input(process_id=\"$processId\", ...) instead of send_stdin. " +
                    "Never send passwords, tokens, or secrets via send_stdin.",
                "Error: password prompt detected",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        // Send the input
        val written = ProcessRegistry.writeStdin(processId, input)
        if (!written) {
            return ToolResult("Error: Failed to write to process stdin. Process may have exited.", "Error: stdin write failed", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        // Reset idle signal since we just interacted
        managed.idleSignaledAt.set(0)

        // Snapshot output size before stdin to detect new output
        val outputSizeBefore = managed.outputLines.size

        // Monitor: wait for completion, new idle, or max wait
        val stdinSentAt = System.currentTimeMillis()
        while (true) {
            delay(MONITOR_POLL_MS)
            val now = System.currentTimeMillis()

            // Process exited
            if (!managed.process.isAlive) {
                ProcessRegistry.unregister(processId)
                val newOutput = managed.outputLines.toList().drop(outputSizeBefore).joinToString("")
                val exitCode = managed.process.exitValue()
                val stripped = RunCommandTool.stripAnsi(newOutput)
                return ToolResult(
                    content = "Process exited (code $exitCode) after stdin input.\nNew output:\n$stripped",
                    summary = "Process exited with code $exitCode after input",
                    tokenEstimate = TokenEstimator.estimate(stripped)
                )
            }

            // Max wait exceeded
            if (now - stdinSentAt > MAX_WAIT_AFTER_STDIN_MS) {
                managed.idleSignaledAt.set(now)
                val newOutput = managed.outputLines.toList().drop(outputSizeBefore).joinToString("")
                val stripped = RunCommandTool.stripAnsi(newOutput)
                return ToolResult(
                    content = "[IDLE] Process still running after ${MAX_WAIT_AFTER_STDIN_MS / 1000}s since stdin input (ID: $processId).\nNew output:\n$stripped",
                    summary = "Process still idle after stdin input",
                    tokenEstimate = TokenEstimator.estimate(stripped)
                )
            }

            // Idle after stdin: new output stopped
            val lastOutputTime = managed.lastOutputAt.get()
            if (lastOutputTime > stdinSentAt && now - lastOutputTime >= IDLE_AFTER_STDIN_MS) {
                managed.idleSignaledAt.set(now)
                val newOutput = managed.outputLines.toList().drop(outputSizeBefore).joinToString("")
                val stripped = RunCommandTool.stripAnsi(newOutput)
                return ToolResult(
                    content = "[IDLE] Process idle again after stdin input (ID: $processId).\nNew output:\n$stripped",
                    summary = "Process idle again after input",
                    tokenEstimate = TokenEstimator.estimate(stripped)
                )
            }
        }
    }
}
```

Also make `isLikelyPasswordPrompt` and `stripAnsi` public in `RunCommandTool.companion`:

```kotlin
// In RunCommandTool.kt companion object, change from private to:
fun isLikelyPasswordPrompt(lastOutput: String): Boolean = ...
fun stripAnsi(text: String): String = ...
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.tools.builtin.SendStdinToolTest" -x instrumentCode`
Expected: All 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SendStdinTool.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RunCommandTool.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/SendStdinToolTest.kt
git commit -m "feat(agent): add send_stdin tool for LLM-driven process input"
```

---

### Task 4: Implement kill_process Tool

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/KillProcessTool.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/KillProcessToolTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
// KillProcessToolTest.kt
package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.runtime.ProcessRegistry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class KillProcessToolTest {

    private val project = mockk<Project> { every { basePath } returns "/tmp" }

    @AfterEach
    fun cleanup() {
        ProcessRegistry.killAll()
    }

    @Test
    fun `kills running process and returns partial output`() = runTest {
        val process = ProcessBuilder("sleep", "60").start()
        val managed = ProcessRegistry.register("kill-test", process, "sleep 60")
        managed.outputLines.add("Starting sleep...\n")

        val tool = KillProcessTool()
        val params = buildJsonObject { put("process_id", "kill-test") }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("[KILLED]"))
        assertTrue(result.content.contains("Starting sleep..."))
        assertNull(ProcessRegistry.get("kill-test"))
    }

    @Test
    fun `returns error for unknown process`() = runTest {
        val tool = KillProcessTool()
        val params = buildJsonObject { put("process_id", "nonexistent") }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("not found"))
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.tools.builtin.KillProcessToolTest" -x instrumentCode`
Expected: Compilation error.

- [ ] **Step 3: Implement KillProcessTool**

```kotlin
// KillProcessTool.kt
package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.ProcessRegistry
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class KillProcessTool : AgentTool {
    override val name = "kill_process"
    override val description = "Kill a running process. Use when a process is stuck, unresponsive, or no longer needed."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "process_id" to ParameterProperty(type = "string", description = "The process ID to kill.")
        ),
        required = listOf("process_id")
    )
    override val allowedWorkers = setOf(WorkerType.CODER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val processId = params["process_id"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'process_id' parameter required", "Error: missing process_id", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val managed = ProcessRegistry.get(processId)
        if (managed == null) {
            return ToolResult(
                "Error: Process '$processId' not found or already exited.",
                "Error: process not found",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        val partialOutput = RunCommandTool.stripAnsi(managed.outputLines.joinToString(""))
        ProcessRegistry.kill(processId)

        // Brief wait for reader thread to drain
        kotlinx.coroutines.delay(300)

        val truncated = if (partialOutput.length > 5000) partialOutput.takeLast(5000) else partialOutput

        return ToolResult(
            content = "[KILLED] Process terminated (ID: $processId, command: ${managed.command.take(80)}).\n\nPartial output:\n$truncated",
            summary = "Process killed: ${managed.command.take(60)}",
            tokenEstimate = TokenEstimator.estimate(truncated)
        )
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.tools.builtin.KillProcessToolTest" -x instrumentCode`
Expected: Both tests PASS.

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/KillProcessTool.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/KillProcessToolTest.kt
git commit -m "feat(agent): add kill_process tool"
```

---

### Task 5: Implement ask_user_input Tool + UI Component

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/AskUserInputTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentDashboardPanel.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt`
- Modify: `agent/webview/src/bridge/jcef-bridge.ts`
- Modify: `agent/webview/src/bridge/types.ts`
- Modify: `agent/webview/src/stores/chatStore.ts`
- Create: `agent/webview/src/components/agent/ProcessInputView.tsx`
- Modify: `agent/webview/src/components/chat/ChatView.tsx`

- [ ] **Step 1: Implement AskUserInputTool (Kotlin)**

```kotlin
// AskUserInputTool.kt
package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.ProcessRegistry
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class AskUserInputTool : AgentTool {
    override val name = "ask_user_input"
    override val description = "Ask the user to provide input for a running process. Use when the process " +
        "needs credentials, user decisions, or information you cannot determine. Shows a text input in the chat UI."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "process_id" to ParameterProperty(type = "string", description = "The process ID from the [IDLE] message."),
            "description" to ParameterProperty(type = "string", description = "Explain what the user needs to enter and why."),
            "prompt" to ParameterProperty(type = "string", description = "The terminal prompt shown by the process (for user reference).")
        ),
        required = listOf("process_id", "description")
    )
    override val allowedWorkers = setOf(WorkerType.CODER)

    companion object {
        private const val USER_INPUT_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes
        private const val MONITOR_POLL_MS = 500L
        private const val IDLE_AFTER_INPUT_MS = 10_000L
        private const val MAX_WAIT_AFTER_INPUT_MS = 60_000L

        /** Callback set by AgentController to show the input UI. */
        var showInputCallback: ((processId: String, description: String, prompt: String, command: String) -> Unit)? = null

        /** Pending user input, completed by the UI when user submits. */
        @Volatile
        var pendingInput: CompletableDeferred<String>? = null

        fun resolveInput(input: String) {
            pendingInput?.complete(input)
        }
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val processId = params["process_id"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'process_id' required", "Error: missing process_id", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val description = params["description"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'description' required", "Error: missing description", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val prompt = params["prompt"]?.jsonPrimitive?.content ?: ""

        val managed = ProcessRegistry.get(processId)
            ?: return ToolResult("Error: Process '$processId' not found.", "Error: process not found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        if (!managed.process.isAlive) {
            return ToolResult("Error: Process '$processId' has already exited.", "Error: process exited", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        // Show UI
        val deferred = CompletableDeferred<String>()
        pendingInput = deferred
        showInputCallback?.invoke(processId, description, prompt, managed.command)

        // Wait for user response with timeout
        val userInput = withTimeoutOrNull(USER_INPUT_TIMEOUT_MS) { deferred.await() }
        pendingInput = null

        if (userInput == null) {
            // User didn't respond — kill process
            ProcessRegistry.kill(processId)
            return ToolResult(
                "User did not respond within 5 minutes. Process killed.",
                "User input timeout — process killed",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        // Send user's input to process stdin
        val written = ProcessRegistry.writeStdin(processId, userInput)
        if (!written) {
            return ToolResult("Error: Failed to write user input to process. It may have exited.", "Error: stdin write failed", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
        managed.idleSignaledAt.set(0)

        // Monitor for completion/idle after input
        val outputSizeBefore = managed.outputLines.size
        val inputSentAt = System.currentTimeMillis()

        while (true) {
            delay(MONITOR_POLL_MS)
            val now = System.currentTimeMillis()

            if (!managed.process.isAlive) {
                ProcessRegistry.unregister(processId)
                val newOutput = RunCommandTool.stripAnsi(managed.outputLines.toList().drop(outputSizeBefore).joinToString(""))
                val exitCode = managed.process.exitValue()
                return ToolResult(
                    content = "Process exited (code $exitCode) after user input.\nNew output:\n$newOutput",
                    summary = "Process exited with code $exitCode after user input",
                    tokenEstimate = TokenEstimator.estimate(newOutput)
                )
            }

            if (now - inputSentAt > MAX_WAIT_AFTER_INPUT_MS) {
                managed.idleSignaledAt.set(now)
                val newOutput = RunCommandTool.stripAnsi(managed.outputLines.toList().drop(outputSizeBefore).joinToString(""))
                return ToolResult(
                    content = "[IDLE] Process still running after user input (ID: $processId).\nNew output:\n$newOutput",
                    summary = "Process still idle after user input",
                    tokenEstimate = TokenEstimator.estimate(newOutput)
                )
            }

            val lastOutputTime = managed.lastOutputAt.get()
            if (lastOutputTime > inputSentAt && now - lastOutputTime >= IDLE_AFTER_INPUT_MS) {
                managed.idleSignaledAt.set(now)
                val newOutput = RunCommandTool.stripAnsi(managed.outputLines.toList().drop(outputSizeBefore).joinToString(""))
                return ToolResult(
                    content = "[IDLE] Process idle again after user input (ID: $processId).\nNew output:\n$newOutput",
                    summary = "Process idle again after user input",
                    tokenEstimate = TokenEstimator.estimate(newOutput)
                )
            }
        }
    }
}
```

- [ ] **Step 2: Add ProcessInputView React component**

```tsx
// agent/webview/src/components/agent/ProcessInputView.tsx
import { useState, useCallback } from 'react';
import { useChatStore } from '@/stores/chatStore';

interface ProcessInputViewProps {
  processId: string;
  description: string;
  prompt: string;
  command: string;
  onSubmit: (input: string) => void;
}

export function ProcessInputView({ processId: _processId, description, prompt, command, onSubmit }: ProcessInputViewProps) {
  const [input, setInput] = useState('');

  const handleSubmit = useCallback(() => {
    if (input.length === 0) return;
    const withNewline = input.endsWith('\n') ? input : input + '\n';
    onSubmit(withNewline);
  }, [input, onSubmit]);

  const handleKeyDown = useCallback((e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSubmit();
    }
  }, [handleSubmit]);

  return (
    <div
      className="flex flex-col gap-2 rounded-lg border px-3 py-2.5"
      style={{ backgroundColor: 'var(--tool-bg, rgba(0,0,0,0.1))', borderColor: 'var(--accent-edit, #f59e0b)' }}
    >
      <div className="flex items-center gap-2">
        <span
          className="inline-block size-2 rounded-full"
          style={{ background: 'var(--accent-edit, #f59e0b)', animation: 'approval-breathe 2s ease-in-out infinite' }}
        />
        <span className="text-[12px] font-semibold" style={{ color: 'var(--fg)' }}>
          Process input requested
        </span>
      </div>

      <p className="text-[12px]" style={{ color: 'var(--fg-secondary)' }}>
        {description}
      </p>

      {prompt && (
        <code
          className="block rounded px-2 py-1 text-[11px] font-mono"
          style={{ backgroundColor: 'var(--code-bg)', color: 'var(--fg-muted)' }}
        >
          {prompt}
        </code>
      )}

      <div
        className="flex items-center gap-1.5 rounded px-2 py-1 text-[10px]"
        style={{ backgroundColor: 'var(--diff-rem-bg, rgba(239,68,68,0.08))', color: 'var(--accent-edit, #f59e0b)' }}
      >
        <span>This input will be sent to:</span>
        <code className="font-mono">{command.length > 50 ? command.slice(0, 47) + '...' : command}</code>
      </div>

      <div className="flex items-center gap-1.5">
        <input
          type="text"
          value={input}
          onChange={e => setInput(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder="Enter input..."
          className="flex-1 rounded-md px-2 py-1.5 text-[12px] font-mono outline-none"
          style={{
            backgroundColor: 'var(--input-bg, #2d2d2d)',
            color: 'var(--fg)',
            border: '1px solid var(--border)',
          }}
          autoFocus
        />
        <button
          onClick={handleSubmit}
          disabled={input.length === 0}
          className="rounded-md px-3 py-1.5 text-[11px] font-medium transition-colors disabled:opacity-40"
          style={{ backgroundColor: 'var(--fg)', color: 'var(--bg)' }}
        >
          Send
        </button>
      </div>
    </div>
  );
}
```

- [ ] **Step 3: Add pendingProcessInput to chatStore**

In `agent/webview/src/stores/chatStore.ts`, add:

```typescript
// In PendingProcessInput interface (add near PendingApproval):
interface PendingProcessInput {
  processId: string;
  description: string;
  prompt: string;
  command: string;
}

// In ChatState, add:
pendingProcessInput: PendingProcessInput | null;

// In initial state:
pendingProcessInput: null,

// In actions interface:
showProcessInput(processId: string, description: string, prompt: string, command: string): void;
resolveProcessInput(input: string): void;

// In actions implementation:
showProcessInput(processId: string, description: string, prompt: string, command: string) {
  set({ pendingProcessInput: { processId, description, prompt, command } });
},

resolveProcessInput(input: string) {
  set({ pendingProcessInput: null });
  import('../bridge/jcef-bridge').then(({ kotlinBridge }) => {
    (kotlinBridge as any).resolveProcessInput(input);
  });
},
```

- [ ] **Step 4: Add bridge functions**

In `jcef-bridge.ts`:

```typescript
showProcessInput(processId: string, description: string, prompt: string, command: string) {
  stores?.getChatStore().showProcessInput(processId, description, prompt, command);
},
```

In `AgentCefPanel.kt`:

```kotlin
fun showProcessInput(processId: String, description: String, prompt: String, command: String) {
    callJs("showProcessInput(${jsonStr(processId)},${jsonStr(description)},${jsonStr(prompt)},${jsonStr(command)})")
}
```

Add JS→Kotlin bridge for resolveProcessInput:

```kotlin
// In AgentCefPanel, add JBCefJSQuery:
private val processInputQuery = JBCefJSQuery.create(browser).also { query ->
    query.addHandler { input ->
        onProcessInputResolved?.invoke(input)
        JBCefJSQuery.Response("")
    }
}
var onProcessInputResolved: ((String) -> Unit)? = null

// In injectBridgeJs():
// window._resolveProcessInput = function(input) { ${processInputQuery.inject("input")} }
```

In `AgentController.kt`, wire the callback:

```kotlin
dashboard.setCefProcessInputCallbacks(
    onInput = { input -> AskUserInputTool.resolveInput(input) }
)

// And wire showInputCallback:
AskUserInputTool.showInputCallback = { processId, description, prompt, command ->
    com.intellij.openapi.application.invokeLater {
        dashboard.showProcessInput(processId, description, prompt, command)
    }
}
```

- [ ] **Step 5: Add ProcessInputView to ChatView**

In `ChatView.tsx`, after the approval gate section:

```tsx
const pendingProcessInput = useChatStore(s => s.pendingProcessInput);
const resolveProcessInput = useChatStore(s => s.resolveProcessInput);

// ... in the JSX, before the scroll anchor:
{pendingProcessInput && (
  <ProcessInputView
    processId={pendingProcessInput.processId}
    description={pendingProcessInput.description}
    prompt={pendingProcessInput.prompt}
    command={pendingProcessInput.command}
    onSubmit={resolveProcessInput}
  />
)}
```

- [ ] **Step 6: Build and verify**

Run: `cd agent/webview && npm run build`
Run: `./gradlew :agent:compileKotlin`
Expected: Both succeed with no errors.

- [ ] **Step 7: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/AskUserInputTool.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentDashboardPanel.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt \
       agent/webview/src/components/agent/ProcessInputView.tsx \
       agent/webview/src/components/chat/ChatView.tsx \
       agent/webview/src/stores/chatStore.ts \
       agent/webview/src/bridge/jcef-bridge.ts \
       agent/src/main/resources/webview/dist/
git commit -m "feat(agent): add ask_user_input tool with chat UI component"
```

---

### Task 6: Register New Tools, Wire Reaper, Update ApprovalGate

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ApprovalGate.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/DynamicToolSelector.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt`

- [ ] **Step 1: Register tools in AgentService**

In `AgentService.kt`, after `register(RunCommandTool())`:

```kotlin
register(SendStdinTool())
register(KillProcessTool())
register(AskUserInputTool())
```

- [ ] **Step 2: Add to ALWAYS_INCLUDE in DynamicToolSelector**

In `DynamicToolSelector.kt`, add to the `ALWAYS_INCLUDE` set:

```kotlin
"send_stdin", "kill_process", "ask_user_input",
```

- [ ] **Step 3: Add risk classifications in ApprovalGate**

In `ApprovalGate.kt`:

```kotlin
// Add to NONE_RISK_TOOLS:
"kill_process", "ask_user_input",

// Add to MEDIUM_RISK_TOOLS:
"send_stdin",
```

- [ ] **Step 4: Wire reaper coroutine in AgentController**

In `AgentController.executeTask()`, after the orchestrator starts, launch the reaper:

```kotlin
// Start idle process reaper
val reaperJob = scope.launch {
    while (isActive) {
        delay(10_000) // Check every 10 seconds
        ProcessRegistry.reapIdleProcesses()
    }
}

// Cancel reaper when session ends (in the finally block):
reaperJob.cancel()
```

- [ ] **Step 5: Wire Disposable cleanup for IDE shutdown**

In `AgentController` init or project service registration:

```kotlin
com.intellij.openapi.Disposer.register(project) {
    ProcessRegistry.killAll()
}
```

- [ ] **Step 6: Build and run full test suite**

Run: `./gradlew :agent:compileKotlin`
Run: `./gradlew :agent:test -x instrumentCode`
Expected: All tests pass, compilation clean.

- [ ] **Step 7: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ApprovalGate.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/DynamicToolSelector.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt
git commit -m "feat(agent): register stdin/kill/ask tools, wire reaper and cleanup"
```

---

### Task 7: UI Kill Button on Terminal Component

**Files:**
- Modify: `agent/webview/src/components/agent/ToolCallChain.tsx`
- Modify: `agent/webview/src/components/ui/tool-ui/terminal.tsx`

- [ ] **Step 1: Add kill button to Terminal component**

In `terminal.tsx`, add `onKill` prop and render stop button:

```tsx
interface TerminalProps {
  command: string;
  stdout?: string;
  stderr?: string;
  exitCode?: number;
  durationMs?: number;
  maxCollapsedLines?: number;
  className?: string;
  isRunning?: boolean;
  onKill?: () => void;
}

// In the header div, after the copy button:
{isRunning && onKill && (
  <Button
    variant="ghost"
    size="sm"
    className="h-5 w-5 p-0 shrink-0"
    onClick={onKill}
    title="Stop process"
  >
    <Square className="h-3 w-3" style={{ color: 'var(--error)' }} />
  </Button>
)}
```

Add `Square` to the lucide imports: `import { Terminal as TerminalIcon, Copy, Check, ChevronDown, ChevronUp, Square } from 'lucide-react';`

- [ ] **Step 2: Pass isRunning and onKill from TerminalContent**

In `ToolCallChain.tsx`, update `TerminalContent`:

```tsx
function TerminalContent({ toolCall }: { toolCall: ToolCall }) {
  // ... existing code ...
  const isRunning = toolCall.status === 'RUNNING';

  const handleKill = useCallback(() => {
    useChatStore.getState().killToolCall(toolCall.id);
  }, [toolCall.id]);

  return (
    <Terminal
      command={command}
      stdout={isRunning ? streamOutput : (!isError ? completedOutput : streamOutput)}
      stderr={isError ? (toolCall.output || toolCall.result) : undefined}
      exitCode={isError ? 1 : toolCall.status === 'COMPLETED' ? 0 : undefined}
      durationMs={toolCall.durationMs}
      isRunning={isRunning}
      onKill={isRunning ? handleKill : undefined}
    />
  );
}
```

Add `useCallback` to the imports at the top of the file.

- [ ] **Step 3: Build webview**

Run: `cd agent/webview && npm run build`
Expected: Clean build.

- [ ] **Step 4: Commit**

```bash
git add agent/webview/src/components/agent/ToolCallChain.tsx \
       agent/webview/src/components/ui/tool-ui/terminal.tsx \
       agent/src/main/resources/webview/dist/
git commit -m "feat(agent): add kill button to Terminal component"
```

---

### Task 8: Settings + Agent CLAUDE.md + System Prompt Updates

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/AgentSettings.kt`
- Modify: `agent/CLAUDE.md`

- [ ] **Step 1: Add settings fields**

In `AgentSettings.State`:

```kotlin
var commandIdleThresholdSeconds by property(15)
var buildCommandIdleThresholdSeconds by property(60)
var strictInteractiveMode by property(false)
var maxStdinPerProcess by property(10)
var askUserInputTimeoutMinutes by property(5)
```

- [ ] **Step 2: Wire settings into tools**

In `RunCommandTool.execute()`, read settings for idle threshold:

```kotlin
val settings = try { AgentSettings.getInstance(project) } catch (_: Exception) { null }
val defaultIdleMs = (settings?.state?.commandIdleThresholdSeconds ?: 15) * 1000L
val buildIdleMs = (settings?.state?.buildCommandIdleThresholdSeconds ?: 60) * 1000L
```

In `SendStdinTool.execute()`, check strict mode and rate limit from settings:

```kotlin
val settings = try { AgentSettings.getInstance(project) } catch (_: Exception) { null }
if (settings?.state?.strictInteractiveMode == true) {
    return ToolResult("send_stdin is disabled in strict interactive mode. Use ask_user_input instead.", ...)
}
val maxStdin = settings?.state?.maxStdinPerProcess ?: 10
```

- [ ] **Step 3: Update agent/CLAUDE.md**

Add to the Tools table the 3 new tools (send_stdin, ask_user_input, kill_process) in the Core category. Update the tool count.

- [ ] **Step 4: Build and verify**

Run: `./gradlew :agent:compileKotlin`
Expected: Clean.

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/AgentSettings.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RunCommandTool.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SendStdinTool.kt \
       agent/CLAUDE.md
git commit -m "feat(agent): add interactive command settings, update CLAUDE.md"
```

---

### Task 9: Integration Test + Full Build Verification

**Files:**
- All previously modified/created files

- [ ] **Step 1: Run full agent test suite**

Run: `./gradlew :agent:clean :agent:test --rerun --no-build-cache -x instrumentCode`
Expected: All tests pass (including new ProcessRegistryTest, SendStdinToolTest, KillProcessToolTest, updated RunCommandToolTest).

- [ ] **Step 2: Run verifyPlugin**

Run: `./gradlew verifyPlugin`
Expected: No API compatibility issues.

- [ ] **Step 3: Build plugin**

Run: `./gradlew buildPlugin`
Expected: ZIP created in `build/distributions/`.

- [ ] **Step 4: Final commit if any fixups needed**

```bash
git add -A
git commit -m "fix(agent): integration test fixups for interactive run_command"
```
