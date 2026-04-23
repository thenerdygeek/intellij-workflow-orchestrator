package com.workflow.orchestrator.agent.tools.builtin

import com.workflow.orchestrator.agent.loop.ApprovalResult
import com.workflow.orchestrator.agent.tools.background.BackgroundPool
import com.workflow.orchestrator.agent.tools.background.RunCommandBackgroundHandle
import com.workflow.orchestrator.agent.tools.process.ProcessRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import com.intellij.openapi.project.Project

/**
 * Verifies the per-action approval split in [BackgroundProcessTool]:
 * - READ actions (list, status, output) bypass [AgentTool.requestApproval] entirely.
 * - WRITE actions (kill, send_stdin, attach) invoke [AgentTool.requestApproval].
 *
 * Strategy: use spyk(BackgroundProcessTool()) so we can track calls to requestApproval.
 * Default coEvery returns APPROVED so tool execution proceeds normally.
 * For READ actions we assert requestApproval was NOT invoked (exactly 0 times).
 * For WRITE actions we assert requestApproval was invoked (exactly 1 time).
 */
class BackgroundProcessPerActionApprovalTest {

    private lateinit var project: Project
    private lateinit var pool: BackgroundPool

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)
        pool = mockk(relaxed = true)
        mockkObject(BackgroundPool.Companion)
        every { BackgroundPool.getInstance(project) } returns pool
        every { pool.list(any()) } returns emptyList()
        every { pool.get(any(), any()) } returns null
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
        ProcessRegistry.killAll()
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun makeSpyTool(): BackgroundProcessTool {
        val spy = spyk(BackgroundProcessTool())
        coEvery { spy.requestApproval(any(), any(), any(), any()) } returns ApprovalResult.APPROVED
        return spy
    }

    private fun registerRunningStub(sessionId: String, bgId: String): RunCommandBackgroundHandle {
        val proc = ProcessBuilder("sh", "-c", "sleep 60").start()
        val managed = ProcessRegistry.register(bgId, proc, "sleep 60")
        return RunCommandBackgroundHandle(bgId, sessionId, managed, "sleep 60")
    }

    // ── READ actions — requestApproval must NOT be called ───────────────────

    @Test
    fun `list action skips requestApproval`() = runBlocking {
        val tool = makeSpyTool()
        BackgroundProcessTool.currentSessionId.set("sess-aprv-list")
        try {
            tool.execute(buildJsonObject {}, project)
        } finally {
            BackgroundProcessTool.currentSessionId.remove()
        }
        coVerify(exactly = 0) { tool.requestApproval(any(), any(), any(), any()) }
    }

    @Test
    fun `status action skips requestApproval`() = runBlocking {
        val h = registerRunningStub("sess-aprv-stat", "bg_aprv_stat01")
        try {
            every { pool.get("sess-aprv-stat", "bg_aprv_stat01") } returns h
            val tool = makeSpyTool()
            BackgroundProcessTool.currentSessionId.set("sess-aprv-stat")
            try {
                tool.execute(buildJsonObject { put("id", "bg_aprv_stat01"); put("action", "status") }, project)
            } finally {
                BackgroundProcessTool.currentSessionId.remove()
            }
            coVerify(exactly = 0) { tool.requestApproval(any(), any(), any(), any()) }
        } finally {
            h.kill()
        }
    }

    @Test
    fun `output action skips requestApproval`() = runBlocking {
        val h = registerRunningStub("sess-aprv-out", "bg_aprv_out01")
        try {
            every { pool.get("sess-aprv-out", "bg_aprv_out01") } returns h
            val tool = makeSpyTool()
            BackgroundProcessTool.currentSessionId.set("sess-aprv-out")
            try {
                tool.execute(buildJsonObject { put("id", "bg_aprv_out01"); put("action", "output") }, project)
            } finally {
                BackgroundProcessTool.currentSessionId.remove()
            }
            coVerify(exactly = 0) { tool.requestApproval(any(), any(), any(), any()) }
        } finally {
            h.kill()
        }
    }

    // ── WRITE actions — requestApproval must be called exactly once ──────────

    @Test
    fun `kill action invokes requestApproval`() = runBlocking {
        val h = registerRunningStub("sess-aprv-kill", "bg_aprv_kil01")
        try {
            every { pool.get("sess-aprv-kill", "bg_aprv_kil01") } returns h
            val tool = makeSpyTool()
            BackgroundProcessTool.currentSessionId.set("sess-aprv-kill")
            try {
                tool.execute(buildJsonObject { put("id", "bg_aprv_kil01"); put("action", "kill") }, project)
            } finally {
                BackgroundProcessTool.currentSessionId.remove()
            }
            coVerify(exactly = 1) { tool.requestApproval(any(), any(), any(), any()) }
        } finally {
            h.kill()
        }
    }

    @Test
    fun `send_stdin action invokes requestApproval`() = runBlocking {
        val proc = ProcessBuilder("sh", "-c", "read LINE; echo ok").start()
        val managed = ProcessRegistry.register("bg_aprv_sin01", proc, "read-echo")
        Thread {
            proc.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { managed.outputLines.add(it + "\n") }
            }
            managed.readerDone.countDown()
        }.apply { isDaemon = true }.start()
        val h = RunCommandBackgroundHandle("bg_aprv_sin01", "sess-aprv-sin", managed, "read-echo")
        try {
            every { pool.get("sess-aprv-sin", "bg_aprv_sin01") } returns h
            val tool = makeSpyTool()
            BackgroundProcessTool.currentSessionId.set("sess-aprv-sin")
            try {
                tool.execute(
                    buildJsonObject { put("id", "bg_aprv_sin01"); put("action", "send_stdin"); put("input", "test\n") },
                    project
                )
            } finally {
                BackgroundProcessTool.currentSessionId.remove()
            }
            coVerify(exactly = 1) { tool.requestApproval(any(), any(), any(), any()) }
        } finally {
            h.kill()
        }
    }

    @Test
    fun `attach action invokes requestApproval`() = runBlocking {
        val proc = ProcessBuilder("sh", "-c", "sleep 60").start()
        val managed = ProcessRegistry.register("bg_aprv_att01", proc, "sleep 60")
        val h = RunCommandBackgroundHandle("bg_aprv_att01", "sess-aprv-att", managed, "sleep 60")
        // Kill after 300ms so attach returns quickly
        Thread {
            Thread.sleep(300); proc.destroyForcibly()
        }.apply { isDaemon = true }.start()
        try {
            every { pool.get("sess-aprv-att", "bg_aprv_att01") } returns h
            val tool = makeSpyTool()
            BackgroundProcessTool.currentSessionId.set("sess-aprv-att")
            try {
                tool.execute(
                    buildJsonObject { put("id", "bg_aprv_att01"); put("action", "attach"); put("timeout_seconds", 5) },
                    project
                )
            } finally {
                BackgroundProcessTool.currentSessionId.remove()
            }
            coVerify(exactly = 1) { tool.requestApproval(any(), any(), any(), any()) }
        } finally {
            h.kill()
        }
    }

    // ── Denial gate: WRITE action denied → isError=true ─────────────────────

    @Test
    fun `kill action denied by requestApproval returns error`() = runBlocking {
        val h = registerRunningStub("sess-aprv-deny", "bg_aprv_deny01")
        try {
            every { pool.get("sess-aprv-deny", "bg_aprv_deny01") } returns h
            val tool = spyk(BackgroundProcessTool())
            coEvery { tool.requestApproval(any(), any(), any(), any()) } returns ApprovalResult.DENIED
            BackgroundProcessTool.currentSessionId.set("sess-aprv-deny")
            val result = try {
                tool.execute(buildJsonObject { put("id", "bg_aprv_deny01"); put("action", "kill") }, project)
            } finally {
                BackgroundProcessTool.currentSessionId.remove()
            }
            assertEquals(true, result.isError, "Expected isError=true when kill denied; got: ${result.content}")
            assert(result.content.lowercase().contains("denied")) {
                "Expected 'denied' in content; got: ${result.content}"
            }
        } finally {
            h.kill()
        }
    }
}
