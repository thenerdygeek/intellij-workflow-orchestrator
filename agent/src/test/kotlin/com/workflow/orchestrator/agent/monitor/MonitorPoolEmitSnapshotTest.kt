package com.workflow.orchestrator.agent.monitor

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Verifies that [MonitorPool] emits [WorkflowEvent.MonitorChanged] with correct
 * [com.workflow.orchestrator.core.events.MonitorSnapshotDto] payloads on register,
 * stop, markExited, and killAll.
 *
 * Uses `runBlocking` (not `runTest`) because `MonitorPool.emitSnapshot` launches on a
 * real [Dispatchers.IO] scope, not the test scheduler. A short [delay] after each pool
 * mutation is sufficient for the IO coroutine to reach the EventBus.
 */
class MonitorPoolEmitSnapshotTest {

    private val bus = EventBus()
    private val project = mockk<Project>(relaxed = true)
    private lateinit var poolScope: CoroutineScope
    private lateinit var pool: MonitorPool

    private fun fakeHandle(id: String, sessionId: String): MonitorHandle {
        val src = object : MonitorSource {
            override val monitorId = id
            override val description = "fake-$id"
            override fun start(emit: (MonitorEvent) -> Unit) {}
            override fun stop() {}
        }
        return MonitorHandle(src, sessionId, startedAt = System.currentTimeMillis())
    }

    @BeforeEach
    fun setup() {
        every { project.getService(EventBus::class.java) } returns bus
        poolScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        pool = MonitorPool(project, poolScope)
    }

    @AfterEach
    fun teardown() {
        pool.dispose()
        poolScope.cancel()
    }

    /** Collect [WorkflowEvent.MonitorChanged] events into a list for [timeoutMs], then cancel. */
    private fun collectMonitorChangedFor(timeoutMs: Long): List<WorkflowEvent.MonitorChanged> {
        val received = mutableListOf<WorkflowEvent.MonitorChanged>()
        runBlocking {
            val job = launch(Dispatchers.Unconfined) {
                bus.events.collect { if (it is WorkflowEvent.MonitorChanged) received.add(it) }
            }
            delay(timeoutMs)
            job.cancel()
        }
        return received
    }

    @Test
    fun `register emits MonitorChanged with the registered handle`() = runBlocking {
        val sid = "session-reg"
        val received = mutableListOf<WorkflowEvent.MonitorChanged>()
        val collectJob = launch(Dispatchers.Unconfined) {
            bus.events.collect { if (it is WorkflowEvent.MonitorChanged) received.add(it) }
        }

        pool.register(sid, fakeHandle("mon-1", sid))
        // Give the IO-dispatched emit time to reach the bus
        withTimeoutOrNull(2_000) { while (received.isEmpty()) delay(20) }

        assertTrue(received.isNotEmpty(), "expected at least one MonitorChanged event")
        val last = received.last()
        assertEquals(sid, last.sessionId)
        assertEquals(1, last.snapshot.size)
        assertEquals("mon-1", last.snapshot[0].id)
        assertEquals("RUNNING", last.snapshot[0].state)

        collectJob.cancel()
    }

    @Test
    fun `stop emits MonitorChanged with empty snapshot after removing the handle`() = runBlocking {
        val sid = "session-stop"
        pool.register(sid, fakeHandle("mon-s", sid))
        // wait for the register emit to land first
        withTimeoutOrNull(2_000) {
            val probe = mutableListOf<WorkflowEvent.MonitorChanged>()
            val j = launch(Dispatchers.Unconfined) {
                bus.events.collect { if (it is WorkflowEvent.MonitorChanged) probe.add(it) }
            }
            while (probe.isEmpty()) delay(20)
            j.cancel()
        }

        val received = mutableListOf<WorkflowEvent.MonitorChanged>()
        val collectJob = launch(Dispatchers.Unconfined) {
            bus.events.collect { if (it is WorkflowEvent.MonitorChanged) received.add(it) }
        }

        pool.stop(sid, "mon-s")
        withTimeoutOrNull(2_000) { while (received.isEmpty()) delay(20) }

        assertTrue(received.isNotEmpty(), "expected a MonitorChanged event after stop")
        val last = received.last()
        assertEquals(sid, last.sessionId)
        // handle removed by stop → snapshot is empty
        assertTrue(last.snapshot.isEmpty(), "snapshot should be empty after stop: ${last.snapshot}")

        collectJob.cancel()
    }

    @Test
    fun `markExited emits MonitorChanged with EXITED handle in snapshot`() = runBlocking {
        val sid = "session-exit"
        pool.register(sid, fakeHandle("mon-e", sid))

        val received = mutableListOf<WorkflowEvent.MonitorChanged>()
        val collectJob = launch(Dispatchers.Unconfined) {
            bus.events.collect { if (it is WorkflowEvent.MonitorChanged) received.add(it) }
        }

        // Wait for the register emit before calling markExited
        withTimeoutOrNull(2_000) { while (received.isEmpty()) delay(20) }
        received.clear()

        pool.markExited(sid, "mon-e", 0)
        withTimeoutOrNull(2_000) { while (received.isEmpty()) delay(20) }

        assertTrue(received.isNotEmpty(), "expected a MonitorChanged event after markExited")
        val last = received.last()
        assertEquals(sid, last.sessionId)
        val exited = last.snapshot.find { it.id == "mon-e" }
        assertNotNull(exited, "snapshot should contain mon-e: ${last.snapshot}")
        assertEquals("EXITED", exited?.state, "handle state should be EXITED in snapshot")

        collectJob.cancel()
    }

    @Test
    fun `killAll emits MonitorChanged with empty snapshot`() = runBlocking {
        val sid = "session-kill"
        pool.register(sid, fakeHandle("mon-k", sid))

        val received = mutableListOf<WorkflowEvent.MonitorChanged>()
        val collectJob = launch(Dispatchers.Unconfined) {
            bus.events.collect { if (it is WorkflowEvent.MonitorChanged) received.add(it) }
        }

        // drain the register emit
        withTimeoutOrNull(2_000) { while (received.isEmpty()) delay(20) }
        received.clear()

        pool.killAll(sid)
        withTimeoutOrNull(2_000) { while (received.isEmpty()) delay(20) }

        assertTrue(received.isNotEmpty(), "expected a MonitorChanged event after killAll")
        val last = received.last()
        assertEquals(sid, last.sessionId)
        assertTrue(last.snapshot.isEmpty(), "snapshot should be empty after killAll: ${last.snapshot}")

        collectJob.cancel()
    }

    @Test
    fun `snapshot dto contains correct id, label, and state`() = runBlocking {
        val sid = "session-dto"
        val handle = fakeHandle("mon-dto", sid)

        val received = mutableListOf<WorkflowEvent.MonitorChanged>()
        val collectJob = launch(Dispatchers.Unconfined) {
            bus.events.collect { if (it is WorkflowEvent.MonitorChanged) received.add(it) }
        }

        pool.register(sid, handle)
        withTimeoutOrNull(2_000) { while (received.isEmpty()) delay(20) }

        assertTrue(received.isNotEmpty())
        val dto = received.last().snapshot.firstOrNull()
        assertNotNull(dto, "snapshot should be non-empty")
        assertEquals("mon-dto", dto!!.id)
        assertEquals(handle.label, dto.label)
        assertEquals("RUNNING", dto.state)

        collectJob.cancel()
    }
}
