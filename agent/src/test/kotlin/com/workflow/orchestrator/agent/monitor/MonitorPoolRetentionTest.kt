package com.workflow.orchestrator.agent.monitor

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.background.BackgroundState
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MonitorPoolRetentionTest {

    private val project = mockk<Project>(relaxed = true)
    private lateinit var poolScope: CoroutineScope
    private lateinit var pool: MonitorPool

    private fun fakeHandle(id: String, sessionId: String, startedAt: Long = System.currentTimeMillis()): MonitorHandle {
        val src = object : MonitorSource {
            override val monitorId = id
            override val description = "fake-$id"
            override fun start(emit: (MonitorEvent) -> Unit) {}
            override fun stop() {}
        }
        return MonitorHandle(src, sessionId, startedAt)
    }

    @BeforeEach
    fun setup() {
        poolScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        pool = MonitorPool(project, poolScope)
    }

    @AfterEach
    fun teardown() {
        pool.dispose()
        poolScope.cancel()
    }

    /**
     * EXITED handles do NOT count toward the RUNNING concurrency cap.
     * Register 5 monitors, mark 2 EXITED, then a 6th register must SUCCEED (only 3 running).
     */
    @Test
    fun `exited handles do not count toward running concurrency cap`() = runBlocking {
        val sid = "s1"
        for (i in 1..5) {
            pool.register(sid, fakeHandle("m$i", sid, startedAt = i.toLong()))
        }
        // Mark 2 as exited
        pool.markExited(sid, "m1", 0)
        pool.markExited(sid, "m2", 0)
        // Now only 3 are RUNNING — registering a 6th should succeed
        pool.register(sid, fakeHandle("m6", sid, startedAt = 6L))
        val all = pool.list(sid)
        assertEquals(6, all.size, "should have 6 handles total (2 EXITED + 4 RUNNING): $all")
        val running = all.count { it.state() == BackgroundState.RUNNING }
        assertEquals(4, running, "should have 4 RUNNING handles")
    }

    /**
     * markExited keeps the handle in the pool — get(...) still returns it with state EXITED.
     */
    @Test
    fun `markExited retains the handle in the pool`() = runBlocking {
        val sid = "s1"
        pool.register(sid, fakeHandle("mx", sid))
        pool.markExited(sid, "mx", 42)
        val h = pool.get(sid, "mx")
        assertNotNull(h, "handle should still be in the pool after markExited")
        assertEquals(BackgroundState.EXITED, h!!.state())
        assertEquals(42, h.exitCode())
    }

    /**
     * Exited handles are pruned to MAX_EXITED_RETAINED.
     * After marking > MAX_EXITED_RETAINED handles exited, the oldest (by startedAt) are dropped.
     * RUNNING handles are never pruned.
     */
    @Test
    fun `excess exited handles are pruned oldest first, running handles are never pruned`() = runBlocking {
        val sid = "s1"
        val maxExited = MonitorPool.MAX_EXITED_RETAINED

        // Register running + exited handles. Use very small startedAt values to be "oldest".
        // We'll register MAX_EXITED_RETAINED + 2 handles and mark them all exited,
        // then add one more RUNNING handle to confirm it is not pruned.
        // Total needed: maxExited + 2 exited + 1 running
        // But cap is MAX_PER_SESSION=5 (running). We test pruning by directly using
        // markExited on handles already registered when the pool was smaller.
        // Strategy: register handles one at a time, marking each exited before registering next
        // so running count never exceeds MAX_PER_SESSION.

        // Register and immediately mark exited: oldest ones get pruned when we exceed MAX_EXITED_RETAINED.
        for (i in 1..(maxExited + 2)) {
            // Only register if running slot available (there should always be one since we mark each exited first)
            pool.register(sid, fakeHandle("e$i", sid, startedAt = i.toLong()))
            pool.markExited(sid, "e$i", i)
        }

        // Now add one RUNNING handle
        pool.register(sid, fakeHandle("running-keep", sid, startedAt = 999L))

        val all = pool.list(sid)
        val exitedHandles = all.filter { it.state() == BackgroundState.EXITED }
        val runningHandles = all.filter { it.state() == BackgroundState.RUNNING }

        // Running handles should be preserved
        assertEquals(1, runningHandles.size, "RUNNING handle must not be pruned")
        assertEquals("running-keep", runningHandles[0].bgId)

        // Exited handles capped at MAX_EXITED_RETAINED
        assertEquals(maxExited, exitedHandles.size,
            "exited handles should be pruned to MAX_EXITED_RETAINED=$maxExited, found ${exitedHandles.size}")

        // The oldest exited handles (e1, e2 — smallest startedAt) should have been pruned
        assertNull(pool.get(sid, "e1"), "oldest exited handle e1 should have been pruned")
        assertNull(pool.get(sid, "e2"), "second-oldest exited handle e2 should have been pruned")

        // The newest exited handles should still be present
        val newestId = "e${maxExited + 2}"
        assertNotNull(pool.get(sid, newestId), "newest exited handle $newestId should be retained")
    }
}
