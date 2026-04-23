package com.workflow.orchestrator.agent.tools.builtin

import com.workflow.orchestrator.agent.tools.background.BackgroundPool
import com.workflow.orchestrator.agent.settings.AgentSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import com.intellij.openapi.project.Project

class RunCommandOnIdlePolicyTest {

    private lateinit var project: Project
    private lateinit var pool: BackgroundPool

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true) {
            every { basePath } returns "/tmp"
        }
        pool = mockk(relaxed = true)
        mockkObject(BackgroundPool.Companion)
        every { BackgroundPool.getInstance(project) } returns pool
        every { pool.list(any()) } returns emptyList()

        // Default settings state for idle thresholds.
        val settings = mockk<AgentSettings>(relaxed = true)
        val state = mockk<AgentSettings.State>(relaxed = true)
        every { state.commandIdleThresholdSeconds } returns 15
        every { state.buildCommandIdleThresholdSeconds } returns 60
        every { settings.state } returns state
        mockkObject(AgentSettings.Companion)
        every { AgentSettings.getInstance(project) } returns settings
    }

    @AfterEach
    fun tearDown() { unmockkAll() }

    @Test
    fun `on_idle notify emits inline stream note does not register in pool and does not return early`() = runBlocking {
        val notes = mutableListOf<String>()
        RunCommandTool.streamCallback = { _, chunk -> notes.add(chunk) }
        RunCommandTool.currentToolCallId.set("tc-notify")
        RunCommandTool.currentSessionId.set("sess-notify")

        val params = buildJsonObject {
            put("command", "sleep 3")    // produces no output
            put("description", "sleep no output")
            put("idle_timeout", 1)        // 1-second idle threshold
            put("on_idle", "notify")
            put("timeout", 5)
        }
        val result = RunCommandTool(allowedShells = listOf("bash")).execute(params, project)

        // Tool must return normal exit (not IDLE / not detach).
        assertTrue(result.content.contains("Exit code"),
            "tool must return normal exit; got:\n${result.content}")

        // Inline idle note must have been emitted via streamCallback.
        assertTrue(notes.any { it.contains("idle", ignoreCase = true) || it.contains("GENERIC_IDLE") },
            "expected inline idle note; got notes=$notes")

        RunCommandTool.streamCallback = null
        RunCommandTool.currentToolCallId.remove()
        RunCommandTool.currentSessionId.remove()
    }

    @Test
    fun `on_idle wait suppresses idle entirely and blocks until exit`() = runBlocking {
        val notes = mutableListOf<String>()
        RunCommandTool.streamCallback = { _, chunk -> notes.add(chunk) }
        RunCommandTool.currentToolCallId.set("tc-wait")
        RunCommandTool.currentSessionId.set("sess-wait")

        val params = buildJsonObject {
            put("command", "sleep 2")
            put("description", "wait mode")
            put("idle_timeout", 1)
            put("on_idle", "wait")
            put("timeout", 5)
        }
        val result = RunCommandTool(allowedShells = listOf("bash")).execute(params, project)

        assertTrue(notes.none { it.contains("idle", ignoreCase = true) },
            "on_idle=wait must emit no idle notes; got=$notes")
        assertTrue(result.content.contains("Exit code: 0"),
            "tool must return exit code 0; got:\n${result.content}")

        RunCommandTool.streamCallback = null
        RunCommandTool.currentToolCallId.remove()
        RunCommandTool.currentSessionId.remove()
    }

    @Test
    fun `foreground run never auto-registers in BackgroundPool (regression lock-in)`() = runBlocking {
        RunCommandTool.currentToolCallId.set("tc-noauto")
        RunCommandTool.currentSessionId.set("sess-noauto")

        val params = buildJsonObject {
            put("command", "sleep 2")
            put("description", "no auto detach")
            put("idle_timeout", 1)
            put("timeout", 5)   // default on_idle=notify
        }
        RunCommandTool(allowedShells = listOf("bash")).execute(params, project)

        coVerify(exactly = 0) {
            pool.register(any(), any())
        }

        RunCommandTool.currentToolCallId.remove()
        RunCommandTool.currentSessionId.remove()
    }
}
