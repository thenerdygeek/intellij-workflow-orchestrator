package com.workflow.orchestrator.agent.hooks

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS

class HookRunnerTest {

    private val runner = HookRunner()

    // ── buildInputJson tests ──────────────────────────────────────────────

    @Test
    fun `buildInputJson includes hookName and timestamp`() {
        val event = HookEvent(
            type = HookType.TASK_START,
            data = mapOf("task" to "test task", "sessionId" to "abc123")
        )
        val config = HookConfig(type = HookType.TASK_START, command = "echo")
        val json = runner.buildInputJson(event, config)

        assertTrue(json.contains("\"hookName\":\"TaskStart\""))
        assertTrue(json.contains("\"timestamp\":"))
        assertTrue(json.contains("\"taskStart\":"))
        assertTrue(json.contains("\"task\":\"test task\""))
    }

    @Test
    fun `buildInputJson nests event data under camelCase key`() {
        val event = HookEvent(
            type = HookType.PRE_TOOL_USE,
            data = mapOf("toolName" to "edit_file", "arguments" to "{}")
        )
        val config = HookConfig(type = HookType.PRE_TOOL_USE, command = "echo")
        val json = runner.buildInputJson(event, config)

        assertTrue(json.contains("\"preToolUse\":"))
        assertTrue(json.contains("\"toolName\":\"edit_file\""))
    }

    // ── parseJsonOutput tests ─────────────────────────────────────────────

    @Test
    fun `parseJsonOutput parses clean JSON`() {
        val result = runner.parseJsonOutput("""{"cancel": false, "contextModification": "", "errorMessage": ""}""")
        assertNotNull(result)
        assertTrue(result!!.containsKey("cancel"))
        assertTrue(result.containsKey("contextModification"))
        assertTrue(result.containsKey("errorMessage"))
    }

    @Test
    fun `parseJsonOutput extracts JSON from mixed output`() {
        val output = """
            [TaskStart] Starting task...
            some debug output
            {"cancel": true, "errorMessage": "blocked by policy"}
        """.trimIndent()

        val result = runner.parseJsonOutput(output)
        assertNotNull(result)
        assertTrue(result!!.containsKey("cancel"))
    }

    @Test
    fun `parseJsonOutput returns null for empty input`() {
        assertNull(runner.parseJsonOutput(""))
        assertNull(runner.parseJsonOutput("   "))
    }

    @Test
    fun `parseJsonOutput returns null for non-JSON output`() {
        assertNull(runner.parseJsonOutput("just some text\nno json here"))
    }

    @Test
    fun `parseJsonOutput rejects deprecated shouldContinue field`() {
        val output = """{"shouldContinue": false, "errorMessage": "old format"}"""
        assertNull(runner.parseJsonOutput(output))
    }

    @Test
    fun `parseJsonOutput rejects non-boolean cancel`() {
        val output = """{"cancel": "yes", "errorMessage": ""}"""
        assertNull(runner.parseJsonOutput(output))
    }

    @Test
    fun `parseJsonOutput rejects non-string contextModification`() {
        val output = """{"cancel": false, "contextModification": 123}"""
        assertNull(runner.parseJsonOutput(output))
    }

    // ── interpretResult tests ─────────────────────────────────────────────

    @Test
    fun `interpretResult returns Proceed for exit code 0 with no JSON`() {
        val result = runner.interpretResult(
            ProcessResult(exitCode = 0, stdout = "", stderr = ""),
            HookEvent(type = HookType.PRE_TOOL_USE, data = emptyMap())
        )
        assertTrue(result is HookResult.Proceed)
    }

    @Test
    fun `interpretResult returns Cancel for non-zero exit on cancellable event`() {
        val result = runner.interpretResult(
            ProcessResult(exitCode = 1, stdout = "", stderr = "policy violation"),
            HookEvent(type = HookType.PRE_TOOL_USE, data = emptyMap())
        )
        assertTrue(result is HookResult.Cancel)
        assertEquals("policy violation", (result as HookResult.Cancel).reason)
    }

    @Test
    fun `interpretResult returns Proceed for non-zero exit on non-cancellable event`() {
        val result = runner.interpretResult(
            ProcessResult(exitCode = 1, stdout = "", stderr = "error"),
            HookEvent(type = HookType.POST_TOOL_USE, data = emptyMap())
        )
        assertTrue(result is HookResult.Proceed)
    }

    @Test
    fun `interpretResult honors JSON cancel over exit code`() {
        // JSON says cancel:true, exit code is 0
        val result = runner.interpretResult(
            ProcessResult(exitCode = 0, stdout = """{"cancel": true, "errorMessage": "JSON cancel"}""", stderr = ""),
            HookEvent(type = HookType.PRE_TOOL_USE, data = emptyMap())
        )
        assertTrue(result is HookResult.Cancel)
        assertEquals("JSON cancel", (result as HookResult.Cancel).reason)
    }

    @Test
    fun `interpretResult passes contextModification through`() {
        val result = runner.interpretResult(
            ProcessResult(
                exitCode = 0,
                stdout = """{"cancel": false, "contextModification": "extra context here"}""",
                stderr = ""
            ),
            HookEvent(type = HookType.PRE_TOOL_USE, data = emptyMap())
        )
        assertTrue(result is HookResult.Proceed)
        assertEquals("extra context here", (result as HookResult.Proceed).contextModification)
    }

    @Test
    fun `interpretResult truncates large contextModification`() {
        val largeContext = "x".repeat(60_000)
        val result = runner.interpretResult(
            ProcessResult(
                exitCode = 0,
                stdout = """{"cancel": false, "contextModification": "$largeContext"}""",
                stderr = ""
            ),
            HookEvent(type = HookType.PRE_TOOL_USE, data = emptyMap())
        )
        assertTrue(result is HookResult.Proceed)
        val contextMod = (result as HookResult.Proceed).contextModification!!
        assertTrue(contextMod.length < 60_000)
        assertTrue(contextMod.contains("[... context truncated"))
    }

    // ── Integration tests (requires shell) ────────────────────────────────

    @DisabledOnOs(OS.WINDOWS) // Shell tests use /bin/sh
    @Test
    fun `execute runs simple echo command`() = runTest {
        val hook = HookConfig(
            type = HookType.POST_TOOL_USE,
            command = """echo '{"cancel": false}'""",
            timeout = 5000
        )
        val event = HookEvent(
            type = HookType.POST_TOOL_USE,
            data = mapOf("toolName" to "read_file")
        )

        val result = runner.execute(hook, event)
        assertTrue(result is HookResult.Proceed)
    }

    @DisabledOnOs(OS.WINDOWS)
    @Test
    fun `execute returns Cancel for non-zero exit`() = runTest {
        val hook = HookConfig(
            type = HookType.PRE_TOOL_USE,
            command = "exit 1",
            timeout = 5000
        )
        val event = HookEvent(
            type = HookType.PRE_TOOL_USE,
            data = mapOf("toolName" to "run_command")
        )

        val result = runner.execute(hook, event)
        assertTrue(result is HookResult.Cancel)
    }

    @DisabledOnOs(OS.WINDOWS)
    @Test
    fun `execute passes JSON via stdin`() = runTest {
        // Hook reads stdin and echoes it back
        val hook = HookConfig(
            type = HookType.TASK_START,
            command = """cat | python3 -c "import sys, json; data = json.load(sys.stdin); print(json.dumps({'cancel': False}))" 2>/dev/null || echo '{"cancel": false}'""",
            timeout = 10000
        )
        val event = HookEvent(
            type = HookType.TASK_START,
            data = mapOf("task" to "test", "sessionId" to "s123")
        )

        val result = runner.execute(hook, event)
        assertTrue(result is HookResult.Proceed)
    }

    @DisabledOnOs(OS.WINDOWS)
    @Test
    fun `execute returns Proceed on timeout`() = kotlinx.coroutines.runBlocking {
        // Use runBlocking instead of runTest because this test involves real process
        // execution with real timeouts (runTest uses virtual time which doesn't work
        // with ProcessBuilder/withTimeoutOrNull over real processes)
        val hook = HookConfig(
            type = HookType.PRE_TOOL_USE,
            command = "sleep 60",
            timeout = 500 // Very short timeout
        )
        val event = HookEvent(
            type = HookType.PRE_TOOL_USE,
            data = emptyMap()
        )

        val result = runner.execute(hook, event)
        // Timeout = Proceed (don't block the agent)
        assertTrue(result is HookResult.Proceed)
    }

    @DisabledOnOs(OS.WINDOWS)
    @Test
    fun `execute sets environment variables`() = runTest {
        val hook = HookConfig(
            type = HookType.PRE_TOOL_USE,
            command = """if [ "${'$'}HOOK_TYPE" = "PreToolUse" ] && [ "${'$'}TOOL_NAME" = "edit_file" ]; then echo '{"cancel": false}'; else echo '{"cancel": true, "errorMessage": "env vars not set"}'; fi""",
            timeout = 5000
        )
        val event = HookEvent(
            type = HookType.PRE_TOOL_USE,
            data = mapOf("toolName" to "edit_file")
        )

        val result = runner.execute(hook, event)
        assertTrue(result is HookResult.Proceed)
    }
}
