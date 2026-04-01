package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.runtime.*
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SendMessageToParentToolTest {

    private val project: Project = mockk(relaxed = true)
    private val tool = SendMessageToParentTool()

    @Test
    fun `tool name is send_message_to_parent`() {
        assertEquals("send_message_to_parent", tool.name)
    }

    @Test
    fun `allowed for all worker types`() {
        assertTrue(tool.allowedWorkers.containsAll(
            setOf(WorkerType.ORCHESTRATOR, WorkerType.ANALYZER, WorkerType.CODER,
                  WorkerType.REVIEWER, WorkerType.TOOLER)
        ))
    }

    @Test
    fun `sends finding to orchestrator inbox`() = runTest {
        val bus = WorkerMessageBus()
        bus.createInbox(WorkerMessageBus.ORCHESTRATOR_ID)

        val ctx = WorkerContext(
            agentId = "agent-1",
            workerType = WorkerType.CODER,
            messageBus = bus,
            fileOwnership = null
        )

        val params = buildJsonObject {
            put("type", "finding")
            put("content", "Found circular dependency in AuthService.kt")
        }

        val result = withContext(ctx) {
            tool.execute(params, project)
        }

        assertFalse(result.isError)
        val messages = bus.drain(WorkerMessageBus.ORCHESTRATOR_ID)
        assertEquals(1, messages.size)
        assertEquals(MessageType.FINDING, messages[0].type)
        assertEquals("agent-1", messages[0].from)
        assertEquals("Found circular dependency in AuthService.kt", messages[0].content)
    }

    @Test
    fun `sends status_update to orchestrator`() = runTest {
        val bus = WorkerMessageBus()
        bus.createInbox(WorkerMessageBus.ORCHESTRATOR_ID)

        val ctx = WorkerContext("agent-2", WorkerType.CODER, bus, null)
        val params = buildJsonObject {
            put("type", "status_update")
            put("content", "Completed 3/5 files")
        }

        val result = withContext(ctx) { tool.execute(params, project) }
        assertFalse(result.isError)
        val messages = bus.drain(WorkerMessageBus.ORCHESTRATOR_ID)
        assertEquals(MessageType.STATUS_UPDATE, messages[0].type)
    }

    @Test
    fun `returns error when no WorkerContext`() = runTest {
        val params = buildJsonObject {
            put("type", "finding")
            put("content", "test")
        }
        val result = tool.execute(params, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("not running in a worker context"))
    }

    @Test
    fun `returns error when no messageBus`() = runTest {
        val ctx = WorkerContext("agent-1", WorkerType.CODER, null, null)
        val params = buildJsonObject {
            put("type", "finding")
            put("content", "test")
        }
        val result = withContext(ctx) { tool.execute(params, project) }
        assertTrue(result.isError)
        assertTrue(result.content.contains("message bus not available"))
    }

    @Test
    fun `returns error for missing type`() = runTest {
        val bus = WorkerMessageBus()
        bus.createInbox(WorkerMessageBus.ORCHESTRATOR_ID)
        val ctx = WorkerContext("agent-1", WorkerType.CODER, bus, null)
        val params = buildJsonObject { put("content", "test") }
        val result = withContext(ctx) { tool.execute(params, project) }
        assertTrue(result.isError)
    }

    @Test
    fun `returns error for invalid type`() = runTest {
        val bus = WorkerMessageBus()
        bus.createInbox(WorkerMessageBus.ORCHESTRATOR_ID)
        val ctx = WorkerContext("agent-1", WorkerType.CODER, bus, null)
        val params = buildJsonObject {
            put("type", "invalid")
            put("content", "test")
        }
        val result = withContext(ctx) { tool.execute(params, project) }
        assertTrue(result.isError)
        assertTrue(result.content.contains("must be 'finding' or 'status_update'"))
    }
}
