package com.workflow.orchestrator.agent.tools.background

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.settings.AgentSettings
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BackgroundPoolTest {

    private val project = mockk<Project>(relaxed = true)
    private lateinit var pool: BackgroundPool

    @BeforeEach
    fun setup() {
        mockkObject(AgentSettings.Companion)
        every { AgentSettings.getInstance(any()) } returns mockk {
            every { state } returns mockk {
                every { concurrentBackgroundProcessesPerSession } returns 5
            }
        }
        pool = BackgroundPool(project)
    }

    @AfterEach
    fun teardown() {
        unmockkObject(AgentSettings.Companion)
    }

    @Test
    fun `register and lookup by bgId scoped to session`() = runTest {
        val handle = FakeBackgroundHandle(bgId = "bg_test1", sessionId = "s1")

        pool.register("s1", handle)

        assertEquals(handle, pool.get("s1", "bg_test1"))
        assertNull(pool.get("s2", "bg_test1"), "different session must not see the handle")
    }

    @Test
    fun `list returns only session's handles`() = runTest {
        pool.register("s1", FakeBackgroundHandle("bg_a", "s1"))
        pool.register("s1", FakeBackgroundHandle("bg_b", "s1"))
        pool.register("s2", FakeBackgroundHandle("bg_c", "s2"))

        assertEquals(setOf("bg_a", "bg_b"), pool.list("s1").map { it.bgId }.toSet())
        assertEquals(setOf("bg_c"), pool.list("s2").map { it.bgId }.toSet())
    }

    @Test
    fun `killAll removes all handles for a session only`() = runTest {
        val ha = FakeBackgroundHandle("bg_a", "s1")
        val hb = FakeBackgroundHandle("bg_b", "s1")
        val hc = FakeBackgroundHandle("bg_c", "s2")
        pool.register("s1", ha)
        pool.register("s1", hb)
        pool.register("s2", hc)

        pool.killAll("s1")

        assertTrue(ha.killed && hb.killed && !hc.killed)
        assertEquals(emptyList<BackgroundHandle>(), pool.list("s1"))
        assertEquals(1, pool.list("s2").size)
    }

    @Test
    fun `concurrent cap enforced per session`() = runTest {
        // Default cap is 5 per AgentSettings.
        repeat(5) { i -> pool.register("s1", FakeBackgroundHandle("bg_$i", "s1")) }
        val exc = runCatching { pool.register("s1", FakeBackgroundHandle("bg_over", "s1")) }
            .exceptionOrNull()
        assertTrue(exc is BackgroundPool.MaxConcurrentReached,
            "expected MaxConcurrentReached, got: $exc")
    }
}

/** Minimal test double. */
private class FakeBackgroundHandle(
    override val bgId: String,
    override val sessionId: String,
    override val kind: String = "test",
    override val label: String = "fake",
    override val startedAt: Long = System.currentTimeMillis(),
) : BackgroundHandle {
    var killed = false
    override fun state() = if (killed) BackgroundState.KILLED else BackgroundState.RUNNING
    override fun exitCode(): Int? = if (killed) -1 else null
    override fun runtimeMs(): Long = 0
    override fun outputBytes(): Long = 0
    override fun readOutput(sinceOffset: Long, tailLines: Int?) = OutputChunk("", 0, false, null)
    override suspend fun attach(timeoutMs: Long) = AttachResult.AttachTimeout(0, "")
    override fun kill(): Boolean { killed = true; return true }
    override fun onComplete(callback: (event: BackgroundCompletionEvent) -> Unit) {}
}
