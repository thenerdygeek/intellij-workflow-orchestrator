package com.workflow.orchestrator.agent.loop

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TaskSerializationTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `task with all fields round-trips through JSON`() {
        val original = Task(
            id = "task-1",
            subject = "Implement OAuth2 flow",
            description = "Add OAuth2 support to login middleware",
            activeForm = "Implementing OAuth2 flow",
            status = TaskStatus.IN_PROGRESS,
            owner = "coder-agent",
            blocks = listOf("task-2", "task-3"),
            blockedBy = listOf("task-0"),
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<Task>(encoded)
        assertEquals(original.id, decoded.id)
        assertEquals(original.subject, decoded.subject)
        assertEquals(original.status, decoded.status)
        assertEquals(original.blocks, decoded.blocks)
        assertEquals(original.blockedBy, decoded.blockedBy)
    }

    @Test
    fun `task with only required fields defaults correctly`() {
        val t = Task(id = "t1", subject = "Do thing", description = "details")
        assertEquals(TaskStatus.PENDING, t.status)
        assertEquals(null, t.owner)
        assertEquals(null, t.activeForm)
        assertEquals(emptyList<String>(), t.blocks)
        assertEquals(emptyList<String>(), t.blockedBy)
    }

    @Test
    fun `TaskStatus enum has four values`() {
        val values = TaskStatus.values().map { it.name }.toSet()
        assertEquals(setOf("PENDING", "IN_PROGRESS", "COMPLETED", "DELETED"), values)
    }
}
