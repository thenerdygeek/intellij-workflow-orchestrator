package com.workflow.orchestrator.agent.monitor

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.background.BackgroundState
import com.workflow.orchestrator.core.events.EventBus
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * P1-8 (2026-06-10 perf audit): polling monitor sources had no terminal-state auto-stop —
 * a monitor on a finished Bamboo build kept HTTP-polling until session end.
 *
 * Contract pinned here:
 * - A source that reports `isTerminal(current) == true` AFTER an observed non-terminal →
 *   terminal transition stops itself and emits a final NOTABLE "monitor auto-stopped" event.
 * - A FIRST poll that is already terminal does NOT auto-stop (no observed transition) — a
 *   monitor on a plan whose last build finished long ago must keep waiting for the next build.
 * - The default `isTerminal` is false: sources that can't know terminality never auto-stop.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PollingSourceTerminalAutoStopTest {

    /** Scripted fake: terminal when the fetched value equals "finished". */
    private class FakeTerminalSource(
        private val fetchResults: MutableList<String?>,
        cs: CoroutineScope,
        private val countFetches: AtomicInteger? = null,
    ) : PollingSource<String>("term-id", "terminal test", cs, 30_000) {

        override suspend fun fetch(): String? {
            countFetches?.incrementAndGet()
            return if (fetchResults.isEmpty()) "finished" else fetchResults.removeAt(0)
        }

        override fun diff(previous: String?, current: String): List<MonitorEvent> {
            if (previous == current) return emptyList()
            return listOf(MonitorEvent(monitorId, Severity.NOTABLE, "changed:$current"))
        }

        override fun isTerminal(current: String): Boolean = current == "finished"
    }

    @Test
    fun `observed transition to terminal emits final auto-stop event and returns true`() = runTest {
        val src = FakeTerminalSource(mutableListOf("running", "finished"), this)
        val emitted = mutableListOf<MonitorEvent>()

        src.pollOnce { emitted += it } // baseline "running" (non-terminal)
        emitted.clear()

        val result = src.pollOnce { emitted += it } // running → finished

        assertTrue(result, "pollOnce should return true when the terminal transition fires events")
        assertEquals(2, emitted.size, "diff event + final auto-stop event expected")
        assertEquals("changed:finished", emitted[0].line)
        assertTrue(
            emitted[1].line.contains("monitor auto-stopped"),
            "final event must announce the auto-stop (got '${emitted[1].line}')",
        )
        assertEquals(Severity.NOTABLE, emitted[1].severity)
        assertEquals("term-id", emitted[1].monitorId)
    }

    @Test
    fun `first poll already terminal does NOT auto-stop`() = runTest {
        val src = FakeTerminalSource(mutableListOf("finished", "finished"), this)
        val emitted = mutableListOf<MonitorEvent>()

        src.pollOnce { emitted += it } // first poll, already terminal — no transition observed
        src.pollOnce { emitted += it } // still terminal — still no transition

        assertFalse(
            emitted.any { it.line.contains("monitor auto-stopped") },
            "already-terminal snapshots must not auto-stop (monitor may be waiting for the NEXT build)",
        )
    }

    @Test
    fun `default isTerminal is false — sources without terminality never auto-stop`() = runTest {
        val src = object : PollingSource<String>("plain-id", "no terminality", this, 30_000) {
            private val values = mutableListOf("a", "b")
            override suspend fun fetch(): String? = if (values.isEmpty()) "b" else values.removeAt(0)
            override fun diff(previous: String?, current: String): List<MonitorEvent> {
                if (previous == current) return emptyList()
                return listOf(MonitorEvent(monitorId, Severity.NOTABLE, "ev:$current"))
            }
        }
        val emitted = mutableListOf<MonitorEvent>()
        src.pollOnce { emitted += it }
        src.pollOnce { emitted += it }
        assertFalse(emitted.any { it.line.contains("monitor auto-stopped") })
    }

    @Test
    fun `live poller stops polling after the terminal transition`() = runTest {
        val fetches = AtomicInteger(0)
        val src = FakeTerminalSource(mutableListOf("running", "finished"), this, fetches)
        val emitted = mutableListOf<MonitorEvent>()

        src.start { emitted += it }
        runCurrent() // poll 1 at t=0 → "running"
        assertEquals(1, fetches.get())

        // Headless tests have no IDE focus → SmartPoller runs at the 4× background
        // interval (~120s for base 30s), so advance well past one background tick.
        advanceTimeBy(150_000) // poll 2 → "finished" → auto-stop
        runCurrent()
        assertEquals(2, fetches.get())
        assertTrue(emitted.any { it.line.contains("monitor auto-stopped") })

        advanceTimeBy(900_000) // would be several more background polls if still running
        runCurrent()
        assertEquals(2, fetches.get(), "source must not poll again after terminal auto-stop")
    }

    // ------------------------------------------------------------------ P1-8 review: pool handle

    @Test
    fun `self-stop invokes onSelfStop after the final auto-stop event`() = runTest {
        val src = FakeTerminalSource(mutableListOf("running", "finished"), this)
        val emitted = mutableListOf<MonitorEvent>()
        var autoStopEventSeenAtCallback = false
        src.onSelfStop = {
            autoStopEventSeenAtCallback = emitted.any { it.line.contains("monitor auto-stopped") }
        }

        src.pollOnce { emitted += it } // baseline (non-terminal) — callback must NOT fire yet
        assertFalse(autoStopEventSeenAtCallback)

        src.pollOnce { emitted += it } // terminal transition → final event, stop(), then callback
        assertTrue(
            autoStopEventSeenAtCallback,
            "onSelfStop must run AFTER the final NOTABLE auto-stop event was emitted",
        )
    }

    @Test
    fun `self-stop marks the pool handle EXITED — no zombie RUNNING slot`() = runTest {
        val project = mockk<Project>(relaxed = true)
        every { project.getService(EventBus::class.java) } returns EventBus()
        val poolScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val pool = MonitorPool(project, poolScope)
        try {
            val src = FakeTerminalSource(mutableListOf("running", "finished"), this)
            val handle = MonitorHandle(src, "s1", System.currentTimeMillis())
            pool.register("s1", handle)
            // Mirrors the registration-time wiring in MonitorTool.startBamboo /
            // AgentMonitorCoordinator.reArmMonitors.
            src.onSelfStop = { pool.markExited("s1", src.monitorId, null) }

            src.pollOnce {} // baseline
            assertEquals(BackgroundState.RUNNING, pool.get("s1", "term-id")!!.state())

            src.pollOnce {} // terminal transition → self-stop → pool handle marked EXITED
            assertEquals(
                BackgroundState.EXITED,
                pool.get("s1", "term-id")!!.state(),
                "a self-stopped source must not leave its handle RUNNING (untruthful status + " +
                    "permanently consumed per-session slot)",
            )
        } finally {
            pool.dispose()
            poolScope.cancel()
        }
    }

    @Test
    fun `startBamboo and reArmMonitors wire onSelfStop to pool markExited`() {
        // Source-text pin: both registration sites must install the pool-exit callback.
        // (Behavioral coverage of the callback itself is above; these paths need live
        // IntelliJ DI, so the wiring is pinned structurally.)
        val toolSrc = java.io.File(
            "src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/MonitorTool.kt"
        ).readText()
        val bambooStart = toolSrc.indexOf("private suspend fun startBamboo")
        assertTrue(bambooStart >= 0)
        val bambooBody = toolSrc.substring(bambooStart, toolSrc.indexOf("private suspend fun", bambooStart + 1))
        assertTrue(
            bambooBody.contains("onSelfStop") && bambooBody.contains("markExited"),
            "MonitorTool.startBamboo must wire onSelfStop → pool.markExited",
        )

        val coordSrc = java.io.File(
            "src/main/kotlin/com/workflow/orchestrator/agent/monitor/AgentMonitorCoordinator.kt"
        ).readText()
        val reArm = coordSrc.indexOf("suspend fun reArmMonitors")
        assertTrue(reArm >= 0)
        val reArmEnd = coordSrc.indexOf("\n    fun ", reArm).let { if (it < 0) coordSrc.length else it }
        val reArmBody = coordSrc.substring(reArm, reArmEnd)
        assertTrue(
            reArmBody.contains("onSelfStop") && reArmBody.contains("markExited"),
            "AgentMonitorCoordinator.reArmMonitors must wire onSelfStop → pool.markExited",
        )
    }
}
