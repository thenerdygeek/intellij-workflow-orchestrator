package com.workflow.orchestrator.agent.monitor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class MonitorSourceManagerWiringTest {
    @Test
    fun `real source events flow through manager and coalesce to the live-loop sink`() {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val now = AtomicLong(0)
        val delivered = mutableListOf<String>()
        val latch = CountDownLatch(2)

        val mgr = MonitorManager(
            config = MonitorConfig(coalesceWindowMs = 100, wakeBudgetPerMonitor = 3, floodThresholdPerMin = 100),
            clock = { now.get() },
            isLoopLive = { true },                       // simulate a live loop
            deliverToLoop = { text -> synchronized(delivered) { delivered.add(text) } },
            wakeIdle = { WakeOutcome.WOKE },
        )
        val src = ShellCommandSource(
            monitorId = "wire-1",
            description = "wiring",
            command = "printf 'step one done\\nstep two done\\n'",
            filter = Regex("done"),
            workingDir = null, cs = scope, project = null,
        )
        try {
            src.start { e -> mgr.onEvent(e); latch.countDown() }
            assertTrue(latch.await(10, TimeUnit.SECONDS), "source did not feed 2 events into the manager in time")
            // advance clock past the coalesce window and flush
            now.set(1_000)
            mgr.flushDue()
        } finally {
            src.stop(); scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
        val batch = synchronized(delivered) { delivered.toList() }
        assertEquals(1, batch.size, "two events in one window should coalesce to a single delivery; got: $batch")
        assertTrue(batch[0].contains("step one done") && batch[0].contains("step two done"),
            "coalesced batch should contain both lines; got: ${batch[0]}")
    }
}
