package com.workflow.orchestrator.agent.tools.builtin

import com.workflow.orchestrator.agent.settings.AgentSettings
import com.workflow.orchestrator.agent.tools.background.BackgroundPool
import com.workflow.orchestrator.agent.tools.background.BackgroundState
import com.workflow.orchestrator.agent.tools.process.ProcessRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

class RunCommandBackgroundParamTest {

    private val project = mockk<com.intellij.openapi.project.Project>(relaxed = true) {
        every { basePath } returns "/tmp"
    }

    private lateinit var pool: BackgroundPool

    @BeforeEach
    fun setUp() {
        mockkObject(AgentSettings.Companion)
        every { AgentSettings.getInstance(any()) } returns mockk {
            every { state } returns mockk {
                every { commandIdleThresholdSeconds } returns 15
                every { buildCommandIdleThresholdSeconds } returns 60
                every { concurrentBackgroundProcessesPerSession } returns 5
            }
        }

        pool = mockk<BackgroundPool>(relaxed = true)
        mockkObject(BackgroundPool.Companion)
        every { BackgroundPool.getInstance(any()) } returns pool

        // register() must actually register so .get() works — delegate to a real in-memory store.
        // register() is suspend, so we need coAnswers here.
        val realPool = RealBackgroundPool()
        io.mockk.coEvery { pool.register(any(), any()) } coAnswers {
            val sessionId = firstArg<String>()
            val handle = secondArg<com.workflow.orchestrator.agent.tools.background.BackgroundHandle>()
            realPool.put(sessionId, handle)
        }
        every { pool.get(any(), any()) } answers {
            val sessionId = firstArg<String>()
            val bgId = secondArg<String>()
            realPool.get(sessionId, bgId)
        }
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(AgentSettings.Companion)
        unmockkObject(BackgroundPool.Companion)
        ProcessRegistry.killAll()
        RunCommandTool.currentToolCallId.remove()
        RunCommandTool.currentSessionId.remove()
        Thread.sleep(100)
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `background true returns bgId and RUNNING state and does not block`() = runTest {
        RunCommandTool.currentToolCallId.set("tc-bg-launch")
        RunCommandTool.currentSessionId.set("sess-1")

        val params = buildJsonObject {
            put("command", "sleep 2")
            put("description", "background sleep")
            put("background", true)
        }
        val tool = RunCommandTool(allowedShells = listOf("bash"))

        val start = System.currentTimeMillis()
        val result = tool.execute(params, project)
        val elapsed = System.currentTimeMillis() - start

        assertTrue(elapsed < 1500, "background launch must return quickly, was ${elapsed}ms")
        assertTrue(
            result.content.contains(Regex("bg_[a-f0-9]{8}")),
            "result must include bgId; got:\n${result.content}"
        )

        val bgId = Regex("bg_[a-f0-9]{8}").find(result.content)!!.value
        val handle = pool.get("sess-1", bgId)!!
        assertTrue(handle.state() == BackgroundState.RUNNING)

        // Clean up.
        handle.kill()
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `background false runs synchronously`() = runTest {
        RunCommandTool.currentToolCallId.set("tc-fg")
        RunCommandTool.currentSessionId.set("sess-fg")

        val params = buildJsonObject {
            put("command", "echo foreground")
            put("description", "foreground echo")
            put("background", false)
        }
        val tool = RunCommandTool(allowedShells = listOf("bash"))
        val result = tool.execute(params, project)

        assertTrue(result.content.contains("foreground"), "Expected 'foreground' in output: ${result.content}")
        assertTrue(result.content.contains("Exit code: 0"), "Expected exit code 0: ${result.content}")
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `background true returns error when sessionId not set`() = runTest {
        RunCommandTool.currentToolCallId.set("tc-no-session")
        RunCommandTool.currentSessionId.remove() // explicitly unset

        val params = buildJsonObject {
            put("command", "echo hello")
            put("description", "no session id")
            put("background", true)
        }
        val tool = RunCommandTool(allowedShells = listOf("bash"))
        val result = tool.execute(params, project)

        assertTrue(result.isError, "Expected error when sessionId is absent")
        assertTrue(
            result.content.contains("sessionId") || result.content.contains("session"),
            "Expected session error, got: ${result.content}"
        )
    }

    // ── Minimal in-memory store used by the BackgroundPool mock ──────────────

    private class RealBackgroundPool {
        private val data = java.util.concurrent.ConcurrentHashMap<String, MutableMap<String, com.workflow.orchestrator.agent.tools.background.BackgroundHandle>>()

        fun put(sessionId: String, handle: com.workflow.orchestrator.agent.tools.background.BackgroundHandle) {
            data.getOrPut(sessionId) { java.util.concurrent.ConcurrentHashMap() }[handle.bgId] = handle
        }

        fun get(sessionId: String, bgId: String): com.workflow.orchestrator.agent.tools.background.BackgroundHandle? =
            data[sessionId]?.get(bgId)
    }
}
