package com.workflow.orchestrator.cody.service

import com.workflow.orchestrator.cody.protocol.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

class CodyEditServiceTest {

    private val mockServer = mockk<com.workflow.orchestrator.cody.agent.CodyAgentServer>()
    private lateinit var service: TestCodyEditService

    @BeforeEach
    fun setUp() {
        every { mockServer.textDocumentDidFocus(any()) } just runs
        service = TestCodyEditService(mockServer)
    }

    @Test
    fun `requestFix sends didFocus then editCommands`() = runTest {
        val editTask = EditTask(id = "task-1", state = "Working")
        every { mockServer.editCommandsCode(any()) } returns CompletableFuture.completedFuture(editTask)

        val result = service.requestFix(
            filePath = "file:///src/main/Foo.kt",
            range = Range(start = Position(10, 0), end = Position(15, 0)),
            instruction = "Fix the NPE"
        )

        assertEquals("task-1", result.id)
        assertEquals("Working", result.state)

        verify { mockServer.textDocumentDidFocus(TextDocumentIdentifier("file:///src/main/Foo.kt")) }
        verify {
            mockServer.editCommandsCode(match {
                it.instruction == "Fix the NPE" && it.mode == "edit"
            })
        }
    }

    @Test
    fun `requestTestGeneration sends editCommands with test instruction`() = runTest {
        val editTask = EditTask(id = "task-2", state = "Working")
        every { mockServer.editCommandsCode(any()) } returns CompletableFuture.completedFuture(editTask)

        val result = service.requestTestGeneration(
            filePath = "file:///src/main/Foo.kt",
            targetRange = Range(start = Position(20, 0), end = Position(25, 0)),
            existingTestFile = "file:///src/test/FooTest.kt"
        )

        assertEquals("task-2", result.id)
        verify {
            mockServer.editCommandsCode(match {
                it.instruction.contains("test") || it.instruction.contains("Test")
            })
        }
    }

    @Test
    fun `acceptEdit sends editTask accept`() = runTest {
        every { mockServer.editTaskAccept(any()) } returns CompletableFuture.completedFuture(null)
        service.acceptEdit("task-1")
        verify { mockServer.editTaskAccept(EditTaskParams("task-1")) }
    }

    @Test
    fun `undoEdit sends editTask undo`() = runTest {
        every { mockServer.editTaskUndo(any()) } returns CompletableFuture.completedFuture(null)
        service.undoEdit("task-1")
        verify { mockServer.editTaskUndo(EditTaskParams("task-1")) }
    }

    @Test
    fun `cancelEdit sends editTask cancel`() = runTest {
        every { mockServer.editTaskCancel(any()) } returns CompletableFuture.completedFuture(null)
        service.cancelEdit("task-1")
        verify { mockServer.editTaskCancel(EditTaskParams("task-1")) }
    }
}

class TestCodyEditService(
    private val server: com.workflow.orchestrator.cody.agent.CodyAgentServer
) {
    suspend fun requestFix(
        filePath: String,
        range: Range,
        instruction: String,
        contextFiles: List<ContextFile> = emptyList()
    ): EditTask {
        server.textDocumentDidFocus(TextDocumentIdentifier(filePath))
        return server.editCommandsCode(
            EditCommandsCodeParams(instruction = instruction, mode = "edit", range = range)
        ).get()
    }

    suspend fun requestTestGeneration(
        filePath: String,
        targetRange: Range,
        existingTestFile: String? = null
    ): EditTask {
        server.textDocumentDidFocus(TextDocumentIdentifier(filePath))
        val instruction = buildString {
            append("Generate a unit test covering the code at lines ")
            append("${targetRange.start.line}-${targetRange.end.line}")
            if (existingTestFile != null) {
                append(". Add to existing test file: $existingTestFile")
            }
        }
        return server.editCommandsCode(
            EditCommandsCodeParams(instruction = instruction, mode = "edit", range = targetRange)
        ).get()
    }

    suspend fun acceptEdit(taskId: String) {
        server.editTaskAccept(EditTaskParams(taskId)).get()
    }

    suspend fun undoEdit(taskId: String) {
        server.editTaskUndo(EditTaskParams(taskId)).get()
    }

    suspend fun cancelEdit(taskId: String) {
        server.editTaskCancel(EditTaskParams(taskId)).get()
    }
}
