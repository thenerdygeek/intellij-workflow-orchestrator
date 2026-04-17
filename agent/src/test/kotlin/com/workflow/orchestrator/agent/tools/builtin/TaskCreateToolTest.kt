package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.loop.TaskStatus
import com.workflow.orchestrator.agent.session.TaskStore
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TaskCreateToolTest {

    @Test
    fun `creates a pending task with generated id`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "s1")
        val tool = TaskCreateTool { store }
        val project = mockk<Project>(relaxed = true)

        val result = tool.execute(
            params = buildJsonObject {
                put("subject", "Fix login bug")
                put("description", "Users see 500 on /login")
            },
            project = project,
        )

        assertFalse(result.isError, "expected success, got: ${result.content}")
        val task = store.listTasks().single()
        assertEquals("Fix login bug", task.subject)
        assertEquals(TaskStatus.PENDING, task.status)
        assertTrue(task.id.isNotBlank())
    }

    @Test
    fun `missing subject returns error`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "s1")
        val tool = TaskCreateTool { store }
        val project = mockk<Project>(relaxed = true)

        val result = tool.execute(
            params = buildJsonObject { put("description", "no subject") },
            project = project,
        )

        assertTrue(result.isError)
        assertEquals(0, store.listTasks().size)
    }

    @Test
    fun `returns error when task store is unavailable`() = runTest {
        val tool = TaskCreateTool { null }
        val project = mockk<Project>(relaxed = true)
        val result = tool.execute(
            buildJsonObject {
                put("subject", "s")
                put("description", "d")
            },
            project,
        )
        assertTrue(result.isError)
        assertTrue(result.content.contains("store", ignoreCase = true))
    }

    @Test
    fun `activeForm is optional`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "s1")
        val tool = TaskCreateTool { store }
        val project = mockk<Project>(relaxed = true)

        val result = tool.execute(
            params = buildJsonObject {
                put("subject", "Implement OAuth2 flow")
                put("description", "Add OAuth2 to auth middleware")
                put("activeForm", "Implementing OAuth2 flow")
            },
            project = project,
        )

        assertFalse(result.isError)
        val task = store.listTasks().single()
        assertEquals("Implementing OAuth2 flow", task.activeForm)
    }
}
