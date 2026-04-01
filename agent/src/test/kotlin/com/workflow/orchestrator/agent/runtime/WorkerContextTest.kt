package com.workflow.orchestrator.agent.runtime

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.coroutines.coroutineContext

class WorkerContextTest {

    @Test
    fun `WorkerContext is accessible from coroutine context`() = runTest {
        val ctx = WorkerContext(
            agentId = "agent-1",
            workerType = WorkerType.CODER,
            messageBus = null,
            fileOwnership = null
        )
        withContext(ctx) {
            val retrieved = coroutineContext[WorkerContext]
            assertNotNull(retrieved)
            assertEquals("agent-1", retrieved!!.agentId)
            assertEquals(WorkerType.CODER, retrieved.workerType)
            assertFalse(retrieved.isOrchestrator)
        }
    }

    @Test
    fun `orchestrator context has null agentId`() = runTest {
        val ctx = WorkerContext(
            agentId = null,
            workerType = WorkerType.ORCHESTRATOR,
            messageBus = null,
            fileOwnership = null
        )
        withContext(ctx) {
            val retrieved = coroutineContext[WorkerContext]
            assertNotNull(retrieved)
            assertNull(retrieved!!.agentId)
            assertTrue(retrieved.isOrchestrator)
        }
    }

    @Test
    fun `WorkerContext carries messageBus and fileOwnership`() = runTest {
        val bus = WorkerMessageBus()
        val registry = FileOwnershipRegistry()
        val ctx = WorkerContext(
            agentId = "agent-2",
            workerType = WorkerType.ANALYZER,
            messageBus = bus,
            fileOwnership = registry
        )
        withContext(ctx) {
            val retrieved = coroutineContext[WorkerContext]
            assertNotNull(retrieved)
            assertSame(bus, retrieved!!.messageBus)
            assertSame(registry, retrieved.fileOwnership)
        }
    }
}
