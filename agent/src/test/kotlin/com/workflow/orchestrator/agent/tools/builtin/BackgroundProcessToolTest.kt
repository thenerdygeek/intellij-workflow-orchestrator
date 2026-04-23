package com.workflow.orchestrator.agent.tools.builtin

import com.workflow.orchestrator.agent.tools.background.BackgroundPool
import com.workflow.orchestrator.agent.tools.background.RunCommandBackgroundHandle
import com.workflow.orchestrator.agent.tools.process.ProcessRegistry
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
import com.workflow.orchestrator.agent.tools.background.BackgroundHandle

class BackgroundProcessToolTest {

    private lateinit var project: Project
    private lateinit var pool: BackgroundPool

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)
        pool = mockk(relaxed = true)
        mockkObject(BackgroundPool.Companion)
        every { BackgroundPool.getInstance(project) } returns pool
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
        // Ensure no stray processes linger.
        ProcessRegistry.killAll()
    }

    private fun registerRunningStub(sessionId: String, bgId: String): RunCommandBackgroundHandle {
        val proc = ProcessBuilder("sh", "-c", "sleep 60").start()
        val managed = ProcessRegistry.register(bgId, proc, "sleep 60")
        return RunCommandBackgroundHandle(bgId, sessionId, managed, "sleep 60")
    }

    @Test
    fun `empty call returns empty message when no processes`() = runBlocking {
        every { pool.list("sess-empty") } returns emptyList()
        BackgroundProcessTool.currentSessionId.set("sess-empty")
        try {
            val r = BackgroundProcessTool().execute(buildJsonObject {}, project)
            assertTrue(r.content.contains("No background processes"), "got:\n${r.content}")
        } finally {
            BackgroundProcessTool.currentSessionId.remove()
        }
    }

    @Test
    fun `empty call lists all processes for current session`() = runBlocking {
        val h1 = registerRunningStub("sess-list", "bg_a1a1a1a1")
        val h2 = registerRunningStub("sess-list", "bg_b2b2b2b2")
        try {
            every { pool.list("sess-list") } returns listOf<BackgroundHandle>(h1, h2)
            BackgroundProcessTool.currentSessionId.set("sess-list")
            val r = BackgroundProcessTool().execute(buildJsonObject {}, project)
            assertTrue(r.content.contains("bg_a1a1a1a1"))
            assertTrue(r.content.contains("bg_b2b2b2b2"))
            assertTrue(r.content.contains("RUNNING"))
        } finally {
            h1.kill(); h2.kill()
            BackgroundProcessTool.currentSessionId.remove()
        }
    }

    @Test
    fun `id only returns status shortcut`() = runBlocking {
        val h = registerRunningStub("sess-stat", "bg_status01")
        try {
            every { pool.get("sess-stat", "bg_status01") } returns h
            BackgroundProcessTool.currentSessionId.set("sess-stat")
            val r = BackgroundProcessTool().execute(
                buildJsonObject { put("id", "bg_status01") }, project
            )
            assertTrue(r.content.contains("bg_status01"))
            assertTrue(r.content.contains("RUNNING"))
        } finally {
            h.kill()
            BackgroundProcessTool.currentSessionId.remove()
        }
    }

    @Test
    fun `id not in session returns NO_SUCH_ID_IN_SESSION`() = runBlocking {
        every { pool.get("sess-other", "bg_notexist") } returns null
        BackgroundProcessTool.currentSessionId.set("sess-other")
        try {
            val r = BackgroundProcessTool().execute(
                buildJsonObject { put("id", "bg_notexist") }, project
            )
            assertTrue(r.isError)
            assertTrue(r.content.contains("NO_SUCH_ID_IN_SESSION"), "got:\n${r.content}")
        } finally {
            BackgroundProcessTool.currentSessionId.remove()
        }
    }

    @Test
    fun `output action returns tail_lines`() = runBlocking {
        val proc = ProcessBuilder("sh", "-c", "for i in 1 2 3 4 5; do echo line\$i; done").start()
        val managed = ProcessRegistry.register("bg_out01", proc, "echo loop")
        Thread {
            proc.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { managed.outputLines.add(it + "\n") }
            }
            managed.readerDone.countDown()
        }.apply { isDaemon = true }.start()
        proc.waitFor()
        val h = RunCommandBackgroundHandle("bg_out01", "sess-out", managed, "echo loop")
        try {
            every { pool.get("sess-out", "bg_out01") } returns h
            BackgroundProcessTool.currentSessionId.set("sess-out")
            val r = BackgroundProcessTool().execute(
                buildJsonObject {
                    put("id", "bg_out01"); put("action", "output"); put("tail_lines", 2)
                }, project
            )
            assertTrue(r.content.contains("line4"), "missing line4; got:\n${r.content}")
            assertTrue(r.content.contains("line5"))
            assertTrue(!r.content.contains("line1"))
        } finally {
            h.kill()
            BackgroundProcessTool.currentSessionId.remove()
        }
    }

    @Test
    fun `output action filters with grep_pattern`() = runBlocking {
        val proc = ProcessBuilder("sh", "-c", "echo hello; echo world; echo HELLO again").start()
        val managed = ProcessRegistry.register("bg_grep01", proc, "grep test")
        Thread {
            proc.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { managed.outputLines.add(it + "\n") }
            }
            managed.readerDone.countDown()
        }.apply { isDaemon = true }.start()
        proc.waitFor()
        val h = RunCommandBackgroundHandle("bg_grep01", "sess-grep", managed, "grep test")
        try {
            every { pool.get("sess-grep", "bg_grep01") } returns h
            BackgroundProcessTool.currentSessionId.set("sess-grep")
            val r = BackgroundProcessTool().execute(
                buildJsonObject {
                    put("id", "bg_grep01"); put("action", "output"); put("grep_pattern", "hello")
                }, project
            )
            // grep is case-sensitive by default; only matches "hello" (lowercase).
            assertTrue(r.content.contains("hello"), "missing hello; got:\n${r.content}")
            assertTrue(!r.content.contains("world"))
        } finally {
            h.kill()
            BackgroundProcessTool.currentSessionId.remove()
        }
    }
}
