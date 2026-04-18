package com.workflow.orchestrator.agent.session

import com.workflow.orchestrator.agent.loop.Task
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TaskStoreCycleDetectionTest {

    @Test
    fun `direct cycle is rejected`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "sess-1")
        store.addTask(Task(id = "A", subject = "A", description = "a"))
        store.addTask(Task(id = "B", subject = "B", description = "b", blockedBy = listOf("A")))

        var caught: TaskStore.CycleException? = null
        try {
            store.updateTask("A") { it.copy(blockedBy = listOf("B")) }
            fail("Expected CycleException to be thrown")
        } catch (ex: TaskStore.CycleException) {
            caught = ex
        }
        assertTrue(caught!!.message!!.contains("cycle", ignoreCase = true))
    }

    @Test
    fun `transitive cycle is rejected`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "sess-1")
        store.addTask(Task(id = "A", subject = "A", description = "a"))
        store.addTask(Task(id = "B", subject = "B", description = "b", blockedBy = listOf("A")))
        store.addTask(Task(id = "C", subject = "C", description = "c", blockedBy = listOf("B")))

        var threw = false
        try {
            store.updateTask("A") { it.copy(blockedBy = listOf("C")) }
        } catch (ex: TaskStore.CycleException) {
            threw = true
        }
        assertTrue(threw, "Expected CycleException for transitive cycle A→C→B→A")
    }

    @Test
    fun `acyclic updateTask is accepted`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "sess-1")
        store.addTask(Task(id = "A", subject = "A", description = "a"))
        store.addTask(Task(id = "B", subject = "B", description = "b"))
        store.addTask(Task(id = "C", subject = "C", description = "c"))

        // This should NOT throw — C.blockedBy = [A, B] is acyclic
        val updated = store.updateTask("C") { it.copy(blockedBy = listOf("A", "B")) }
        assertEquals(listOf("A", "B"), updated?.blockedBy)
    }

    @Test
    fun `cycle via blocks edges is rejected`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "sess-1")
        store.addTask(Task(id = "A", subject = "A", description = "a"))
        store.addTask(Task(id = "B", subject = "B", description = "b", blocks = listOf("A")))

        var threw = false
        try {
            store.updateTask("A") { it.copy(blocks = listOf("B")) }
        } catch (ex: TaskStore.CycleException) {
            threw = true
            assertTrue(ex.message!!.contains("cycle", ignoreCase = true))
        }
        assertTrue(threw, "Expected CycleException for blocks cycle")
    }
}
