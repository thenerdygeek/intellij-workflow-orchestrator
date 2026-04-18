package com.workflow.orchestrator.agent.session

import com.workflow.orchestrator.agent.tools.CompletionData
import com.workflow.orchestrator.agent.tools.CompletionKind
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class CompletionDataPersistenceTest {

    @TempDir
    lateinit var tempDir: Path

    private fun handler(sessionId: String = "test-session") = MessageStateHandler(
        baseDir = tempDir.toFile(),
        sessionId = sessionId,
        taskText = "Test task"
    )

    @Test
    fun `completionData is persisted and restored for done kind`() = runTest {
        val writeHandler = handler()
        writeHandler.addToClineMessages(
            UiMessage(
                ts = 1000L,
                type = UiMessageType.ASK,
                ask = UiAsk.COMPLETION_RESULT,
                text = "Task done",
                completionData = CompletionData(
                    kind = CompletionKind.DONE,
                    result = "Task done",
                    verifyHow = "./gradlew test",
                    discovery = null,
                ),
            )
        )

        // Load via static helper (simulates fresh handler reading from disk)
        val sessionDir = File(tempDir.toFile(), "sessions/test-session")
        val loaded = MessageStateHandler.loadUiMessages(sessionDir)

        assertEquals(1, loaded.size)
        val completionData = loaded[0].completionData
        assertNotNull(completionData)
        assertEquals(CompletionKind.DONE, completionData!!.kind)
        assertEquals("Task done", completionData.result)
        assertEquals("./gradlew test", completionData.verifyHow)
        assertNull(completionData.discovery)
    }

    @Test
    fun `completionData is persisted and restored for heads_up kind with discovery`() = runTest {
        val writeHandler = handler()
        writeHandler.addToClineMessages(
            UiMessage(
                ts = 2000L,
                type = UiMessageType.ASK,
                ask = UiAsk.COMPLETION_RESULT,
                text = "Done",
                completionData = CompletionData(
                    kind = CompletionKind.HEADS_UP,
                    result = "Done",
                    verifyHow = null,
                    discovery = "3 orphaned tables",
                ),
            )
        )

        // Load via second handler instance (same session ID, same base dir)
        val readHandler = handler()
        val sessionDir = File(tempDir.toFile(), "sessions/test-session")
        val loaded = MessageStateHandler.loadUiMessages(sessionDir)

        assertEquals(1, loaded.size)
        val completionData = loaded[0].completionData
        assertNotNull(completionData)
        assertEquals(CompletionKind.HEADS_UP, completionData!!.kind)
        assertEquals("Done", completionData.result)
        assertNull(completionData.verifyHow)
        assertEquals("3 orphaned tables", completionData.discovery)
    }

    @Test
    fun `UiMessage without completionData reads back as null (backward compat)`() = runTest {
        val writeHandler = handler()
        writeHandler.addToClineMessages(
            UiMessage(
                ts = 3000L,
                type = UiMessageType.ASK,
                ask = UiAsk.COMPLETION_RESULT,
                text = "Old-style completion with no completionData field",
            )
        )

        val sessionDir = File(tempDir.toFile(), "sessions/test-session")
        val loaded = MessageStateHandler.loadUiMessages(sessionDir)

        assertEquals(1, loaded.size)
        assertNull(loaded[0].completionData)
        assertEquals("Old-style completion with no completionData field", loaded[0].text)
    }
}
