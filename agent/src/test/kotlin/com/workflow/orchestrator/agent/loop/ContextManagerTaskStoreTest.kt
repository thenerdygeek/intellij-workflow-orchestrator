package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.agent.session.TaskStore
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ContextManagerTaskStoreTest {

    @Test
    fun `renderTaskProgressMarkdown renders tasks from attached store`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "s1")
        store.addTask(Task(id = "t-1", subject = "Write tests", description = "...", status = TaskStatus.COMPLETED))
        store.addTask(Task(id = "t-2", subject = "Implement feature", description = "...", status = TaskStatus.IN_PROGRESS))
        store.addTask(Task(id = "t-3", subject = "Deploy", description = "..."))

        val cm = ContextManager(maxInputTokens = 150_000).also { it.attachTaskStore(store) }

        val md = cm.renderTaskProgressMarkdown()
        assertNotNull(md)
        assertTrue(md!!.contains("[x] Write tests"))
        assertTrue(md.contains("[ ] Implement feature"))
        assertTrue(md.contains("[ ] Deploy"))
    }

    @Test
    fun `renderTaskProgressMarkdown returns null when no store or no tasks`(@TempDir tmp: File) = runTest {
        val cmNoStore = ContextManager(maxInputTokens = 150_000)
        assertNull(cmNoStore.renderTaskProgressMarkdown())

        val cmEmpty = ContextManager(maxInputTokens = 150_000).also {
            it.attachTaskStore(TaskStore(baseDir = tmp, sessionId = "empty"))
        }
        assertNull(cmEmpty.renderTaskProgressMarkdown())
    }

    @Test
    fun `renderTaskProgressMarkdown filters out DELETED tasks`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "s1")
        store.addTask(Task(id = "t-1", subject = "Keep", description = "..."))
        store.addTask(Task(id = "t-2", subject = "Remove", description = "..."))
        store.updateTask("t-2") { it.copy(status = TaskStatus.DELETED) }

        val cm = ContextManager(maxInputTokens = 150_000).also { it.attachTaskStore(store) }
        val md = cm.renderTaskProgressMarkdown()

        assertNotNull(md)
        assertTrue(md!!.contains("Keep"))
        assertFalse(md.contains("Remove"), "DELETED tasks must not appear")
    }
}
