package com.workflow.orchestrator.agent.monitor

import com.workflow.orchestrator.core.events.WorkflowEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EventBusSourceTest {

    /**
     * Creates a [FakeEventBusSource] whose [map] turns [WorkflowEvent.BuildFinished] into a
     * [MonitorEvent] (FAILED → ALERT, SUCCESS → NOTABLE) and ignores all other event types.
     * [hydrate] returns the supplied [hydrateResult] (null = no hydration event).
     */
    private fun makeSource(
        flow: MutableSharedFlow<WorkflowEvent>,
        cs: CoroutineScope,
        hydrateResult: MonitorEvent? = null,
    ): EventBusSource = object : EventBusSource(
        monitorId = "test-monitor",
        description = "Test EventBus source",
        cs = cs,
        flow = flow,
    ) {
        override fun map(event: WorkflowEvent): MonitorEvent? = when (event) {
            is WorkflowEvent.BuildFinished -> {
                val sev = if (event.status == WorkflowEvent.BuildEventStatus.FAILED)
                    Severity.ALERT else Severity.NOTABLE
                MonitorEvent(monitorId, sev, "build:${event.planKey}:${event.status.name}")
            }
            else -> null
        }

        override suspend fun hydrate(): MonitorEvent? = hydrateResult
    }

    // ------------------------------------------------------------------ test 1
    /**
     * Start-hydration: a non-null hydrateResult is emitted BEFORE any flow events,
     * and it appears FIRST in the sink.
     */
    @Test
    fun `hydrate result is emitted first before any flow events`() =
        runTest(UnconfinedTestDispatcher()) {
            val flow = MutableSharedFlow<WorkflowEvent>(extraBufferCapacity = 8)
            val hydrateEvent = MonitorEvent("test-monitor", Severity.NOTABLE, "hydrated")
            val sink = mutableListOf<MonitorEvent>()

            val source = makeSource(flow, cs = this, hydrateResult = hydrateEvent)
            source.start { sink.add(it) }

            // UnconfinedTestDispatcher runs the launched coroutine eagerly — hydration already ran.
            // Now emit a matching flow event so we can verify ordering.
            flow.emit(
                WorkflowEvent.BuildFinished("MY-PLAN", 42, WorkflowEvent.BuildEventStatus.SUCCESS)
            )

            source.stop()

            // Both the hydration event AND the mapped flow event must be present,
            // proving hydrate ran FIRST and the flow collector then delivered the emit.
            assertEquals(2, sink.size, "Sink must contain hydration event + the flow event")
            assertEquals(hydrateEvent, sink[0], "Hydration event must appear FIRST")
            assertEquals("build:MY-PLAN:SUCCESS", sink[1].line, "Flow event must follow hydration")
            assertEquals(Severity.NOTABLE, sink[1].severity)
        }

    // ------------------------------------------------------------------ test 2
    /**
     * A matching WorkflowEvent is mapped and forwarded to the sink.
     */
    @Test
    fun `matching WorkflowEvent is mapped and emitted`() =
        runTest(UnconfinedTestDispatcher()) {
            val flow = MutableSharedFlow<WorkflowEvent>(extraBufferCapacity = 8)
            val sink = mutableListOf<MonitorEvent>()

            val source = makeSource(flow, cs = this, hydrateResult = null)
            source.start { sink.add(it) }

            flow.emit(
                WorkflowEvent.BuildFinished("PROJ-KEY", 7, WorkflowEvent.BuildEventStatus.FAILED)
            )

            source.stop()

            assertEquals(1, sink.size)
            assertEquals(Severity.ALERT, sink[0].severity)
            assertEquals("build:PROJ-KEY:FAILED", sink[0].line)
            assertEquals("test-monitor", sink[0].monitorId)
        }

    // ------------------------------------------------------------------ test 3
    /**
     * A non-matching WorkflowEvent (map returns null) is ignored — sink remains empty.
     */
    @Test
    fun `non-matching WorkflowEvent is ignored`() =
        runTest(UnconfinedTestDispatcher()) {
            val flow = MutableSharedFlow<WorkflowEvent>(extraBufferCapacity = 8)
            val sink = mutableListOf<MonitorEvent>()

            val source = makeSource(flow, cs = this, hydrateResult = null)
            source.start { sink.add(it) }

            // TicketChanged is NOT handled by map — must be silently ignored
            flow.emit(WorkflowEvent.TicketChanged("PROJ-123", "Some ticket summary"))

            source.stop()

            assertTrue(sink.isEmpty(), "Non-matching event must not appear in the sink")
        }

    // ------------------------------------------------------------------ test 4
    /**
     * hydrate returning null does NOT emit anything on start (with no flow emits).
     */
    @Test
    fun `null hydrate result emits nothing on start`() =
        runTest(UnconfinedTestDispatcher()) {
            val flow = MutableSharedFlow<WorkflowEvent>(extraBufferCapacity = 8)
            val sink = mutableListOf<MonitorEvent>()

            val source = makeSource(flow, cs = this, hydrateResult = null)
            source.start { sink.add(it) }

            // No flow emits — only a non-null hydrate could have filled the sink
            source.stop()

            assertTrue(sink.isEmpty(), "Null hydration must not emit any event")
        }

    // ------------------------------------------------------------------ test 5
    /**
     * stop() cancels the subscription: events emitted AFTER stop() must not reach the sink.
     */
    @Test
    fun `stop cancels subscription and subsequent emits are not delivered`() =
        runTest(UnconfinedTestDispatcher()) {
            val flow = MutableSharedFlow<WorkflowEvent>(extraBufferCapacity = 8)
            val sink = mutableListOf<MonitorEvent>()

            val source = makeSource(flow, cs = this, hydrateResult = null)
            source.start { sink.add(it) }

            // Emit once BEFORE stop to confirm the subscription was active
            flow.emit(
                WorkflowEvent.BuildFinished("A", 1, WorkflowEvent.BuildEventStatus.SUCCESS)
            )
            val countAfterFirstEmit = sink.size

            source.stop()

            // Emit AFTER stop — must NOT reach the sink
            flow.emit(
                WorkflowEvent.BuildFinished("B", 2, WorkflowEvent.BuildEventStatus.FAILED)
            )

            assertEquals(
                countAfterFirstEmit,
                sink.size,
                "No new events must be delivered after stop()"
            )
        }
}
