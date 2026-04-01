package com.workflow.orchestrator.agent.runtime

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class WorkerMessageBusTest {

    private lateinit var bus: WorkerMessageBus

    @BeforeEach
    fun setup() {
        bus = WorkerMessageBus()
    }

    @Test
    fun `createInbox and send message`() = runTest {
        bus.createInbox("agent-1")
        val msg = WorkerMessage(
            from = WorkerMessageBus.ORCHESTRATOR_ID,
            to = "agent-1",
            type = MessageType.INSTRUCTION,
            content = "Focus on service layer"
        )
        assertTrue(bus.send(msg))
        val drained = bus.drain("agent-1")
        assertEquals(1, drained.size)
        assertEquals("Focus on service layer", drained[0].content)
        assertEquals(MessageType.INSTRUCTION, drained[0].type)
    }

    @Test
    fun `drain returns empty after consuming`() = runTest {
        bus.createInbox("agent-1")
        bus.send(WorkerMessage("orch", "agent-1", MessageType.INSTRUCTION, "msg1"))
        bus.drain("agent-1")
        val second = bus.drain("agent-1")
        assertTrue(second.isEmpty())
    }

    @Test
    fun `send to nonexistent inbox returns false`() = runTest {
        assertFalse(bus.send(WorkerMessage("orch", "agent-999", MessageType.INSTRUCTION, "msg")))
    }

    @Test
    fun `hasPending returns true when messages exist`() = runTest {
        bus.createInbox("agent-1")
        assertFalse(bus.hasPending("agent-1"))
        bus.send(WorkerMessage("orch", "agent-1", MessageType.INSTRUCTION, "msg"))
        assertTrue(bus.hasPending("agent-1"))
    }

    @Test
    fun `closeInbox prevents further sends`() = runTest {
        bus.createInbox("agent-1")
        bus.closeInbox("agent-1")
        assertFalse(bus.send(WorkerMessage("orch", "agent-1", MessageType.INSTRUCTION, "msg")))
    }

    @Test
    fun `multiple messages drain in order`() = runTest {
        bus.createInbox("agent-1")
        bus.send(WorkerMessage("orch", "agent-1", MessageType.INSTRUCTION, "first"))
        bus.send(WorkerMessage("orch", "agent-1", MessageType.INSTRUCTION, "second"))
        bus.send(WorkerMessage("orch", "agent-1", MessageType.INSTRUCTION, "third"))
        val drained = bus.drain("agent-1")
        assertEquals(3, drained.size)
        assertEquals("first", drained[0].content)
        assertEquals("second", drained[1].content)
        assertEquals("third", drained[2].content)
    }

    @Test
    fun `orchestrator inbox works`() = runTest {
        bus.createInbox(WorkerMessageBus.ORCHESTRATOR_ID)
        val msg = WorkerMessage(
            from = "agent-1",
            to = WorkerMessageBus.ORCHESTRATOR_ID,
            type = MessageType.FINDING,
            content = "Found circular dependency"
        )
        assertTrue(bus.send(msg))
        val drained = bus.drain(WorkerMessageBus.ORCHESTRATOR_ID)
        assertEquals(1, drained.size)
        assertEquals(MessageType.FINDING, drained[0].type)
    }

    @Test
    fun `close shuts down all inboxes`() = runTest {
        bus.createInbox("agent-1")
        bus.createInbox("agent-2")
        bus.close()
        assertFalse(bus.send(WorkerMessage("orch", "agent-1", MessageType.INSTRUCTION, "msg")))
        assertFalse(bus.send(WorkerMessage("orch", "agent-2", MessageType.INSTRUCTION, "msg")))
    }

    @Test
    fun `drain on nonexistent inbox returns empty`() = runTest {
        val drained = bus.drain("agent-999")
        assertTrue(drained.isEmpty())
    }
}
