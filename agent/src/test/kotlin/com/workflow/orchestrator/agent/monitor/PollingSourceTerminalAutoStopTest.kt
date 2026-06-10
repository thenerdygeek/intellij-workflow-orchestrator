package com.workflow.orchestrator.agent.monitor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
}
