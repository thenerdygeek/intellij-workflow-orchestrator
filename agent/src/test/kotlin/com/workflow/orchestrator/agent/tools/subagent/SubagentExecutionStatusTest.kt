package com.workflow.orchestrator.agent.tools.subagent

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SubagentExecutionStatusTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `serializes COMPLETED to lowercase completed`() {
        val item = SubagentStatusItem(index = 0, prompt = "test", status = SubagentExecutionStatus.COMPLETED)
        val serialized = json.encodeToString(item)
        assertTrue(serialized.contains(""""status":"completed""""), "Expected lowercase 'completed', got: $serialized")
    }

    @Test
    fun `deserializes lowercase completed to COMPLETED`() {
        val jsonStr = """{"index":0,"prompt":"test","status":"completed"}"""
        val item = json.decodeFromString<SubagentStatusItem>(jsonStr)
        assertEquals(SubagentExecutionStatus.COMPLETED, item.status)
    }

    @Test
    fun `serialization round-trip for all enum values`() {
        for (status in SubagentExecutionStatus.entries) {
            val item = SubagentStatusItem(index = 0, prompt = "test", status = status)
            val serialized = json.encodeToString(item)
            val deserialized = json.decodeFromString<SubagentStatusItem>(serialized)
            assertEquals(status, deserialized.status, "Round-trip failed for $status")
        }
    }

    @Test
    fun `SubagentProgressUpdate accepts enum status`() {
        val update = SubagentProgressUpdate(status = SubagentExecutionStatus.RUNNING)
        assertEquals(SubagentExecutionStatus.RUNNING, update.status)
    }

    @Test
    fun `SubagentProgressUpdate null status is valid`() {
        val update = SubagentProgressUpdate(status = null)
        assertNull(update.status)
    }

    @Test
    fun `SubagentStatusItem default status is PENDING`() {
        val item = SubagentStatusItem(index = 0, prompt = "test")
        assertEquals(SubagentExecutionStatus.PENDING, item.status)
    }

    @Test
    fun `dispatch maps each status to correct action`() {
        data class DispatchResult(val method: String, val isError: Boolean?)

        fun dispatch(status: SubagentExecutionStatus?): DispatchResult = when (status) {
            SubagentExecutionStatus.RUNNING -> DispatchResult("spawnSubAgent", null)
            SubagentExecutionStatus.COMPLETED -> DispatchResult("completeSubAgent", false)
            SubagentExecutionStatus.FAILED -> DispatchResult("completeSubAgent", true)
            SubagentExecutionStatus.PENDING, null -> DispatchResult("noop", null)
        }

        assertEquals("spawnSubAgent", dispatch(SubagentExecutionStatus.RUNNING).method)
        assertEquals("completeSubAgent", dispatch(SubagentExecutionStatus.COMPLETED).method)
        assertEquals(false, dispatch(SubagentExecutionStatus.COMPLETED).isError)
        assertEquals("completeSubAgent", dispatch(SubagentExecutionStatus.FAILED).method)
        assertEquals(true, dispatch(SubagentExecutionStatus.FAILED).isError)
        assertEquals("noop", dispatch(SubagentExecutionStatus.PENDING).method)
        assertEquals("noop", dispatch(null).method)
    }
}
