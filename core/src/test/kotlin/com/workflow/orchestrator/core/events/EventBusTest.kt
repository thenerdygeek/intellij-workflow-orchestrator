package com.workflow.orchestrator.core.events

import app.cash.turbine.test
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EventBusTest {

    @Test
    fun `emit delivers event to subscriber`() = runTest {
        val bus = EventBus()
        val event = WorkflowEvent.BuildFinished("PROJ-BUILD", 42, WorkflowEvent.BuildEventStatus.SUCCESS)

        bus.events.test {
            bus.emit(event)
            assertEquals(event, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `emit delivers to multiple subscribers`() = runTest {
        val bus = EventBus()
        val event = WorkflowEvent.BuildFinished("PROJ-BUILD", 1, WorkflowEvent.BuildEventStatus.FAILED)

        val received1 = mutableListOf<WorkflowEvent>()
        val received2 = mutableListOf<WorkflowEvent>()

        val job1 = launch { bus.events.collect { received1.add(it) } }
        val job2 = launch { bus.events.collect { received2.add(it) } }

        // Let collectors subscribe before emitting
        testScheduler.advanceUntilIdle()

        bus.emit(event)
        testScheduler.advanceUntilIdle()

        assertEquals(1, received1.size)
        assertEquals(1, received2.size)
        assertEquals(event, received1[0])
        assertEquals(event, received2[0])

        job1.cancel()
        job2.cancel()
    }

    @Test
    fun `no replay — late subscriber misses past events`() = runTest {
        val bus = EventBus()
        val event = WorkflowEvent.BuildFinished("PROJ-BUILD", 1, WorkflowEvent.BuildEventStatus.SUCCESS)

        bus.emit(event)

        bus.events.test {
            // Should not receive the event emitted before subscription
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
