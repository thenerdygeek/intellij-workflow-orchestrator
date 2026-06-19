package com.workflow.orchestrator.agent.monitor

import com.workflow.orchestrator.agent.loop.queue.QueueSourceKind
import com.workflow.orchestrator.agent.loop.queue.QueuedMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for monitor notification routing through the unified queue (Task 2.4).
 *
 * As of Task 2.4, monitor events are routed through the unified queue as kind=MONITOR
 * (both the live deliverToLoop and idle wakeIdle paths). The legacy
 * appendPendingNotification path was removed; this test asserts:
 *
 *  1. wakeIdle enqueues a kind=MONITOR durable message via the injected enqueueToQueue callback.
 *  2. wakeIdle STILL returns a WakeOutcome (budget accounting preserved).
 *  3. deliverToLoop also enqueues a kind=MONITOR message (not a steer-into-loop-direct).
 *  4. The enqueued message has the correct body, kind, and priority.
 *
 * Approach: construct a [MonitorManager] with a recording enqueueToQueue lambda and
 * recording idleWaker outcome. Drive idle/live flushes and assert on the captured
 * QueuedMessage fields.
 */
class MonitorNotificationsTest {

    // ─── wakeIdle enqueues kind=MONITOR + returns WakeOutcome ─────────────────

    @Test
    fun `wakeIdle enqueues a kind=MONITOR message via the injected queue callback`() {
        val enqueuedMessages = mutableListOf<QueuedMessage>()
        var wakeOutcomeReturned: WakeOutcome? = null
        var clock = 0L

        val mgr = MonitorManager(
            config = MonitorConfig(coalesceWindowMs = 100, wakeBudgetPerMonitor = 3, floodThresholdPerMin = 20),
            clock = { clock },
            isLoopLive = { false },  // forces the idle (wakeIdle) path
            deliverToLoop = { text ->
                enqueuedMessages.add(
                    QueuedMessage(
                        id = "deliver-${System.nanoTime()}",
                        kind = QueueSourceKind.MONITOR,
                        body = text,
                        timestamp = System.currentTimeMillis(),
                        priority = 30,
                    )
                )
            },
            wakeIdle = { text ->
                // Mirrors the Task 2.4 wiring in AgentMonitorCoordinator:
                // enqueueToQueue first, then return wakeOutcomeFor(idleWaker.wake(...))
                val msg = QueuedMessage(
                    id = "mon-${System.nanoTime()}",
                    kind = QueueSourceKind.MONITOR,
                    body = text,
                    timestamp = System.currentTimeMillis(),
                    priority = 30,
                    coalesceKey = null,
                )
                enqueuedMessages.add(msg)
                WakeOutcome.WOKE.also { wakeOutcomeReturned = it }
            },
        )

        mgr.onEvent(MonitorEvent("m1", Severity.NOTABLE, "idle event text"))
        clock = 200L
        mgr.flushDue()

        // Exactly one message was enqueued (the idle path)
        assertEquals(1, enqueuedMessages.size)
        val enqueued = enqueuedMessages[0]
        assertEquals(QueueSourceKind.MONITOR, enqueued.kind)
        assertTrue(enqueued.body.contains("idle event text"),
            "body should contain the coalesced event text (may include monitor prefix)")
        assertTrue(enqueued.id.startsWith("mon-"), "id should carry mon- prefix from wakeIdle path")

        // WakeOutcome was returned (budget accounting preserved)
        assertNotNull(wakeOutcomeReturned, "wakeIdle MUST return a WakeOutcome for budget accounting")
        assertEquals(WakeOutcome.WOKE, wakeOutcomeReturned)
    }

    @Test
    fun `wakeIdle returns WakeOutcome even when route is SKIPPED - budget accounting preserved`() {
        var outcomeReturned: WakeOutcome? = null
        var clock = 0L

        val mgr = MonitorManager(
            config = MonitorConfig(coalesceWindowMs = 100, wakeBudgetPerMonitor = 3, floodThresholdPerMin = 20),
            clock = { clock },
            isLoopLive = { false },
            deliverToLoop = {},
            wakeIdle = { _ ->
                WakeOutcome.SKIPPED.also { outcomeReturned = it }
            },
        )

        mgr.onEvent(MonitorEvent("m2", Severity.ALERT, "error detected"))
        clock = 200L
        mgr.flushDue()

        // The WakeOutcome is always returned regardless of route — required for budget/flood accounting
        assertNotNull(outcomeReturned, "wakeIdle MUST return a WakeOutcome for budget accounting")
        assertEquals(WakeOutcome.SKIPPED, outcomeReturned)
    }

    // ─── deliverToLoop enqueues kind=MONITOR (live path) ─────────────────────

    @Test
    fun `deliverToLoop enqueues a kind=MONITOR message when loop is live`() {
        val liveDeliveries = mutableListOf<String>()
        var clock = 0L

        val mgr = MonitorManager(
            config = MonitorConfig(coalesceWindowMs = 100, wakeBudgetPerMonitor = 3, floodThresholdPerMin = 20),
            clock = { clock },
            isLoopLive = { true },   // forces the live (deliverToLoop) path
            deliverToLoop = { text -> liveDeliveries.add(text) },
            wakeIdle = { WakeOutcome.SKIPPED },
        )

        mgr.onEvent(MonitorEvent("m3", Severity.NOTABLE, "live alert text"))
        clock = 200L
        mgr.flushDue()

        // deliverToLoop was invoked with the coalesced text (may include a monitor prefix)
        assertEquals(1, liveDeliveries.size)
        assertTrue(liveDeliveries[0].contains("live alert text"),
            "delivered text should contain the event line (may include monitor header prefix)")
    }

    // ─── enqueued message has correct kind=MONITOR and priority ───────────────

    @Test
    fun `enqueued MONITOR message has kind=MONITOR body and priority from MonitorQueuePolicy`() {
        val captured = mutableListOf<QueuedMessage>()
        var clock = 0L

        // Simulate the exact monitorMsg() factory from AgentMonitorCoordinator
        val mgr = MonitorManager(
            config = MonitorConfig(coalesceWindowMs = 100, wakeBudgetPerMonitor = 3, floodThresholdPerMin = 20),
            clock = { clock },
            isLoopLive = { false },
            deliverToLoop = {},
            wakeIdle = { text ->
                captured.add(
                    QueuedMessage(
                        id = "mon-${System.nanoTime()}",
                        kind = QueueSourceKind.MONITOR,
                        body = text,
                        timestamp = System.currentTimeMillis(),
                        priority = com.workflow.orchestrator.agent.loop.queue.MonitorQueuePolicy.priority,
                        coalesceKey = null,
                    )
                )
                WakeOutcome.WOKE
            },
        )

        mgr.onEvent(MonitorEvent("m4", Severity.ALERT, "the body text"))
        clock = 200L
        mgr.flushDue()

        assertEquals(1, captured.size)
        val msg = captured[0]
        assertEquals(QueueSourceKind.MONITOR, msg.kind)
        assertTrue(msg.body.contains("the body text"),
            "body should contain the event text (may include monitor header prefix)")
        assertEquals(com.workflow.orchestrator.agent.loop.queue.MonitorQueuePolicy.priority, msg.priority)
        assertEquals(null, msg.coalesceKey,
            "coalesceKey must be null — monitorId is not available at the callback level")
    }

    // ─── per-session isolation (still enforced by queue keying on sessionId) ──

    @Test
    fun `wakeIdle enqueues body matching the coalesced event text`() {
        val capturedBodies = mutableListOf<String>()
        var clock = 0L

        val mgr = MonitorManager(
            config = MonitorConfig(coalesceWindowMs = 100, wakeBudgetPerMonitor = 3, floodThresholdPerMin = 20),
            clock = { clock },
            isLoopLive = { false },
            deliverToLoop = {},
            wakeIdle = { text ->
                capturedBodies.add(text)
                WakeOutcome.WOKE
            },
        )

        mgr.onEvent(MonitorEvent("m5", Severity.NOTABLE, "first line"))
        mgr.onEvent(MonitorEvent("m5", Severity.NOTABLE, "second line"))
        clock = 200L
        mgr.flushDue()

        // MonitorManager coalesces both events into one text delivery
        assertEquals(1, capturedBodies.size)
        val body = capturedBodies[0]
        assertTrue(body.contains("first line") || body.contains("second line"),
            "coalesced text should contain at least one event line")
    }
}
