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

    @Test
    fun `repeated task_create with same subject produces distinct ids`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "s1")
        val tool = TaskCreateTool { store }
        val project = mockk<Project>(relaxed = true)

        val params = buildJsonObject {
            put("subject", "Do thing")
            put("description", "same call twice")
        }
        tool.execute(params, project)
        tool.execute(params, project)

        val ids = store.listTasks().map { it.id }.toSet()
        assertEquals(2, ids.size, "distinct UUIDs expected, got: $ids")
    }

    // ── Issue: LLMs call task_update with id="1" after task_create returned a UUID.
    // Root cause: Claude Code's Task tool uses simple sequential string ids ("1","2",…)
    // which matches the LLM's training distribution. UUIDs make the tool result unusable
    // across turns (especially after compaction strips the original tool output).

    @Test
    fun `ids are sequential strings starting at 1`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "s1")
        val tool = TaskCreateTool { store }
        val project = mockk<Project>(relaxed = true)

        repeat(3) { i ->
            tool.execute(
                buildJsonObject {
                    put("subject", "task $i")
                    put("description", "d")
                },
                project,
            )
        }

        assertEquals(listOf("1", "2", "3"), store.listTasks().map { it.id })
    }

    @Test
    fun `task_create content includes the exact id to reuse for task_update`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "s1")
        val tool = TaskCreateTool { store }
        val project = mockk<Project>(relaxed = true)

        val result = tool.execute(
            buildJsonObject {
                put("subject", "Fix auth")
                put("description", "login broken")
            },
            project,
        )

        assertFalse(result.isError)
        // The returned content must name the id ("1") so the LLM can cite it verbatim
        // on the next task_update call. Embedding the id in a sentence like
        // "Created task 1:" is not enough — we make the correlation explicit.
        assertTrue(
            result.content.contains("id=\"1\""),
            "task_create result must surface id=\"1\" so the LLM reuses it; got: ${result.content}"
        )
    }
}
