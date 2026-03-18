package com.workflow.orchestrator.agent.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TaskGraphTest {

    private lateinit var graph: TaskGraph

    @BeforeEach
    fun setup() {
        graph = TaskGraph()
    }

    @Test
    fun `addTask adds a task successfully`() {
        val task = createTask("t1")
        graph.addTask(task)

        assertEquals(1, graph.getAllTasks().size)
        assertEquals("t1", graph.getTask("t1")?.id)
    }

    @Test
    fun `addTask with dependencies`() {
        graph.addTask(createTask("t1"))
        graph.addTask(createTask("t2", dependsOn = listOf("t1")))

        assertEquals(2, graph.getAllTasks().size)
        assertEquals(listOf("t1"), graph.getTask("t2")?.dependsOn)
    }

    @Test
    fun `cycle detection - self dependency`() {
        assertThrows<IllegalArgumentException> {
            graph.addTask(createTask("t1", dependsOn = listOf("t1")))
        }
    }

    @Test
    fun `cycle detection - circular dependency via self-reference in dependsOn`() {
        assertThrows<IllegalArgumentException> {
            graph.addTask(AgentTask(
                id = "a_cycle",
                description = "cycle maker",
                action = TaskAction.ANALYZE,
                target = "test",
                workerType = WorkerType.ANALYZER,
                dependsOn = listOf("a_cycle")
            ))
        }
    }

    @Test
    fun `cycle detection - two node cycle via re-dependency`() {
        // Create a graph where a new node would form a cycle
        val g = TaskGraph()
        g.addTask(createTask("a", dependsOn = listOf("b")))
        // Now adding b with dep on a creates: a->b->a (cycle)
        assertThrows<IllegalArgumentException> {
            g.addTask(createTask("b", dependsOn = listOf("a")))
        }
    }

    @Test
    fun `getNextExecutable returns tasks with no dependencies`() {
        graph.addTask(createTask("t1"))
        graph.addTask(createTask("t2"))

        val next = graph.getNextExecutable()
        assertEquals(2, next.size)
        assertTrue(next.any { it.id == "t1" })
        assertTrue(next.any { it.id == "t2" })
    }

    @Test
    fun `getNextExecutable returns tasks with completed dependencies`() {
        graph.addTask(createTask("t1"))
        graph.addTask(createTask("t2", dependsOn = listOf("t1")))

        // t2 should not be executable yet
        val nextBefore = graph.getNextExecutable()
        assertEquals(1, nextBefore.size)
        assertEquals("t1", nextBefore[0].id)

        // Complete t1
        graph.markComplete("t1", "done")

        // Now t2 should be executable
        val nextAfter = graph.getNextExecutable()
        assertEquals(1, nextAfter.size)
        assertEquals("t2", nextAfter[0].id)
    }

    @Test
    fun `getNextExecutable excludes running and completed tasks`() {
        graph.addTask(createTask("t1"))
        graph.addTask(createTask("t2"))

        graph.markRunning("t1")
        graph.markComplete("t2", "done")

        val next = graph.getNextExecutable()
        assertTrue(next.isEmpty())
    }

    @Test
    fun `markComplete updates task status and summary`() {
        graph.addTask(createTask("t1"))
        graph.markComplete("t1", "Analysis complete")

        val task = graph.getTask("t1")!!
        assertEquals(TaskStatus.COMPLETED, task.status)
        assertEquals("Analysis complete", task.resultSummary)
    }

    @Test
    fun `markFailed updates task status and error`() {
        graph.addTask(createTask("t1"))
        graph.markFailed("t1", "Connection timeout")

        val task = graph.getTask("t1")!!
        assertEquals(TaskStatus.FAILED, task.status)
        assertEquals("Connection timeout", task.resultSummary)
    }

    @Test
    fun `markComplete throws for unknown task`() {
        assertThrows<IllegalArgumentException> {
            graph.markComplete("nonexistent", "done")
        }
    }

    @Test
    fun `markFailed throws for unknown task`() {
        assertThrows<IllegalArgumentException> {
            graph.markFailed("nonexistent", "error")
        }
    }

    @Test
    fun `isComplete returns true when all tasks are done`() {
        graph.addTask(createTask("t1"))
        graph.addTask(createTask("t2"))

        assertFalse(graph.isComplete())

        graph.markComplete("t1", "done")
        assertFalse(graph.isComplete())

        graph.markComplete("t2", "done")
        assertTrue(graph.isComplete())
    }

    @Test
    fun `isComplete returns true with mix of completed and failed`() {
        graph.addTask(createTask("t1"))
        graph.addTask(createTask("t2"))

        graph.markComplete("t1", "done")
        graph.markFailed("t2", "error")

        assertTrue(graph.isComplete())
    }

    @Test
    fun `isComplete returns true for empty graph`() {
        assertTrue(graph.isComplete())
    }

    @Test
    fun `toSerializableState and restoreFromState roundtrip`() {
        graph.addTask(createTask("t1"))
        graph.addTask(createTask("t2", dependsOn = listOf("t1")))
        graph.markComplete("t1", "done")

        val state = graph.toSerializableState()

        val newGraph = TaskGraph()
        newGraph.restoreFromState(state)

        assertEquals(2, newGraph.getAllTasks().size)
        assertEquals(TaskStatus.COMPLETED, newGraph.getTask("t1")?.status)
        assertEquals(TaskStatus.PENDING, newGraph.getTask("t2")?.status)
    }

    @Test
    fun `diamond dependency works correctly`() {
        // t1 -> t2, t1 -> t3, t2+t3 -> t4
        graph.addTask(createTask("t1"))
        graph.addTask(createTask("t2", dependsOn = listOf("t1")))
        graph.addTask(createTask("t3", dependsOn = listOf("t1")))
        graph.addTask(createTask("t4", dependsOn = listOf("t2", "t3")))

        // Only t1 executable initially
        assertEquals(listOf("t1"), graph.getNextExecutable().map { it.id })

        graph.markComplete("t1", "done")
        // t2 and t3 both executable
        assertEquals(setOf("t2", "t3"), graph.getNextExecutable().map { it.id }.toSet())

        graph.markComplete("t2", "done")
        // t4 not yet executable (t3 still pending)
        assertTrue(graph.getNextExecutable().map { it.id }.contains("t3"))
        assertFalse(graph.getNextExecutable().map { it.id }.contains("t4"))

        graph.markComplete("t3", "done")
        // Now t4 is executable
        assertEquals(listOf("t4"), graph.getNextExecutable().map { it.id })
    }

    private fun createTask(
        id: String,
        dependsOn: List<String> = emptyList()
    ): AgentTask = AgentTask(
        id = id,
        description = "Test task $id",
        action = TaskAction.ANALYZE,
        target = "TestFile.kt",
        workerType = WorkerType.ANALYZER,
        dependsOn = dependsOn
    )
}
