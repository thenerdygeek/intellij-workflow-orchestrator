package com.workflow.orchestrator.agent.tools.builtin

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TaskToolsRegistrationTest {

    @Test
    fun `task tool names are stable`() {
        assertEquals("task_create", TaskCreateTool { null }.name)
        assertEquals("task_update", TaskUpdateTool { null }.name)
        assertEquals("task_list", TaskListTool { null }.name)
        assertEquals("task_get", TaskGetTool { null }.name)
    }

    @Test
    fun `task tools available to all worker types`() {
        val allWorkers = com.workflow.orchestrator.agent.tools.WorkerType.entries.toSet()
        assertEquals(allWorkers, TaskCreateTool { null }.allowedWorkers)
        assertEquals(allWorkers, TaskUpdateTool { null }.allowedWorkers)
        assertEquals(allWorkers, TaskListTool { null }.allowedWorkers)
        assertEquals(allWorkers, TaskGetTool { null }.allowedWorkers)
    }
}
