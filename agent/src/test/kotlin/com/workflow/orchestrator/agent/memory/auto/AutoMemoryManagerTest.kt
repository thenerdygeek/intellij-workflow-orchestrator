package com.workflow.orchestrator.agent.memory.auto

import com.workflow.orchestrator.agent.memory.ArchivalMemory
import com.workflow.orchestrator.agent.memory.CoreMemory
import com.workflow.orchestrator.core.ai.SourcegraphChatClient
import com.workflow.orchestrator.core.ai.dto.ChatCompletionResponse
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.ai.dto.Choice
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class AutoMemoryManagerTest {

    @TempDir lateinit var tempDir: Path
    private lateinit var coreMemory: CoreMemory
    private lateinit var archival: ArchivalMemory
    private val client = mockk<SourcegraphChatClient>()
    private lateinit var manager: AutoMemoryManager

    @BeforeEach
    fun setUp() {
        coreMemory = CoreMemory(tempDir.resolve("core-memory.json").toFile())
        archival = ArchivalMemory(tempDir.resolve("archival/store.json").toFile())
        manager = AutoMemoryManager(
            coreMemory = coreMemory,
            archivalMemory = archival,
            client = client
        )
    }

    @Nested
    inner class SessionEndExtraction {

        @Test
        fun `extracts and applies core memory update on session end`() = runTest {
            val extractionJson = """{"core_memory_updates":[{"block":"user","action":"append","content":"Senior Kotlin developer"}],"archival_inserts":[]}"""
            mockLlmResponse(extractionJson)

            val messages = listOf(
                ChatMessage(role = "user", content = "Fix the null pointer in UserService"),
                ChatMessage(role = "assistant", content = "I'll check the implementation..."),
                ChatMessage(role = "user", content = "Good, now run the tests"),
                ChatMessage(role = "assistant", content = "All tests pass")
            )

            manager.onSessionComplete("session-1", messages)

            assertEquals("Senior Kotlin developer", coreMemory.read("user"))
        }

        @Test
        fun `extracts and inserts archival entries on session end`() = runTest {
            val extractionJson = """{"core_memory_updates":[],"archival_inserts":[{"content":"NullPointerException in UserService caused by missing null check on Optional.get()","tags":["error","spring","npe"]}]}"""
            mockLlmResponse(extractionJson)

            val messages = listOf(
                ChatMessage(role = "user", content = "Fix the null pointer"),
                ChatMessage(role = "assistant", content = "Found it"),
                ChatMessage(role = "user", content = "Great"),
                ChatMessage(role = "assistant", content = "Fixed")
            )

            manager.onSessionComplete("session-1", messages)

            val results = archival.search("NullPointerException")
            assertEquals(1, results.size)
            assertTrue(results[0].entry.tags.contains("npe"))
        }

        @Test
        fun `handles extraction failure gracefully`() = runTest {
            coEvery {
                client.sendMessage(any(), any(), any(), any(), any())
            } returns ApiResult.Error(ErrorType.SERVER_ERROR, "500")

            val messages = (1..5).map { ChatMessage(role = if (it % 2 == 1) "user" else "assistant", content = "msg $it") }

            // Should not throw
            manager.onSessionComplete("session-1", messages)

            // Memory unchanged — user block stays empty (empty string after CoreMemory seeds default blocks)
            assertTrue(coreMemory.read("user").isNullOrBlank())
        }

        @Test
        fun `skips extraction for very short conversations`() = runTest {
            val messages = listOf(ChatMessage(role = "user", content = "hi"))

            manager.onSessionComplete("session-1", messages)

            // Client should NOT be called — conversation too short (< 4 messages)
            coVerify(exactly = 0) { client.sendMessage(any(), any(), any(), any(), any()) }
        }
    }

    @Nested
    inner class SessionStartRetrieval {

        @Test
        fun `retrieves relevant memories for first message`() {
            archival.insert("CORS fix: add allowedOrigins to SecurityConfig", listOf("error", "cors", "spring"))

            val recalled = manager.onSessionStart("Fix the CORS error in SecurityConfig")

            assertNotNull(recalled)
            assertTrue(recalled!!.contains("CORS"))
            assertTrue(recalled.contains("<recalled_memory>"))
        }

        @Test
        fun `returns null when nothing relevant`() {
            val recalled = manager.onSessionStart("Refactor the payment module")
            assertNull(recalled)
        }
    }

    @Nested
    inner class CoreMemoryReplace {

        @Test
        fun `applies replace action correctly`() = runTest {
            coreMemory.append("project", "Auth migration in progress")

            val extractionJson = """{"core_memory_updates":[{"block":"project","action":"replace","content":"Auth migration complete","old_content":"Auth migration in progress"}],"archival_inserts":[]}"""
            mockLlmResponse(extractionJson)

            val messages = (1..5).map { ChatMessage(role = if (it % 2 == 1) "user" else "assistant", content = "msg $it") }

            manager.onSessionComplete("session-1", messages)

            assertEquals("Auth migration complete", coreMemory.read("project"))
        }

        @Test
        fun `skips replace when old_content not found`() = runTest {
            coreMemory.append("project", "Some other content")

            val extractionJson = """{"core_memory_updates":[{"block":"project","action":"replace","content":"New content","old_content":"Nonexistent old content"}],"archival_inserts":[]}"""
            mockLlmResponse(extractionJson)

            val messages = (1..5).map { ChatMessage(role = if (it % 2 == 1) "user" else "assistant", content = "msg $it") }

            manager.onSessionComplete("session-1", messages)

            // Should not crash, original content preserved
            assertEquals("Some other content", coreMemory.read("project"))
        }
    }

    private fun mockLlmResponse(content: String) {
        val response = ChatCompletionResponse(
            id = "test-id",
            choices = listOf(
                Choice(
                    index = 0,
                    message = ChatMessage(role = "assistant", content = content),
                    finishReason = "stop"
                )
            )
        )
        coEvery {
            client.sendMessage(any(), any(), any(), any(), any())
        } returns ApiResult.Success(response)
    }
}
