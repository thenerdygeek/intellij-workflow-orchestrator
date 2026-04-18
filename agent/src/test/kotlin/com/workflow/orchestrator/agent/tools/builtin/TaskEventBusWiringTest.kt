package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.session.TaskStore
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Diagnostic for the reported symptom: "6 task_create succeed but UI shows 0/1".
 *
 * Isolates the Kotlin half of the pipeline — TaskCreateTool → EventBus. The vitest
 * suite already proved the React half handles 6 sequential applyTaskCreate calls
 * correctly. If this test passes too, then the bug lives in the JCEF browser
 * (page-load race or CEF JS execution) and not in the Kotlin or React code.
 */
class TaskEventBusWiringTest {

    @Test
    fun `six task_create calls emit six distinct TaskChanged events with isCreate=true`(
        @TempDir tmp: File,
    ) = runTest {
        // EventBus is `@Service(Service.Level.PROJECT)` — in a test we construct
        // it directly and stub `project.getService(EventBus::class.java)` to return it.
        val bus = EventBus()
        val project = mockk<Project>(relaxed = true)
        every { project.getService(EventBus::class.java) } returns bus

        val store = TaskStore(baseDir = tmp, sessionId = "s1")
        val tool = TaskCreateTool { store }

        // Subscribe BEFORE any emits — we want to assert every emit is observed.
        // SharedFlow(replay = 0) means late subscribers miss events, so this is
        // the same ordering AgentController uses (subscribe in init, emit later).
        val received = mutableListOf<WorkflowEvent.TaskChanged>()
        val collectJob = launch(Dispatchers.Unconfined) {
            bus.events.collect { event ->
                if (event is WorkflowEvent.TaskChanged) received.add(event)
            }
        }

        repeat(6) { i ->
            tool.execute(
                buildJsonObject {
                    put("subject", "task $i")
                    put("description", "d")
                },
                project,
            )
        }

        // Give the collector a tick on the shared test dispatcher to drain the buffer.
        testScheduler.advanceUntilIdle()

        assertEquals(6, received.size, "expected 6 TaskChanged events, got: $received")
        assertTrue(received.all { it.isCreate }, "all events must be isCreate=true")
        assertEquals(
            listOf("1", "2", "3", "4", "5", "6"),
            received.map { it.taskId },
            "events must arrive in creation order with sequential ids",
        )
        assertEquals(6, store.listTasks().size, "store must also hold 6 tasks")

        collectJob.cancel()
    }
}
