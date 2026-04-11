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
import org.junit.jupiter.api.Assertions.assertFalse
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
    private lateinit var extractionLog: ExtractionLog
    private val client = mockk<SourcegraphChatClient>()
    private lateinit var manager: AutoMemoryManager

    @BeforeEach
    fun setUp() {
        coreMemory = CoreMemory(tempDir.resolve("core-memory.json").toFile())
        archival = ArchivalMemory(tempDir.resolve("archival/store.json").toFile())
        extractionLog = ExtractionLog(tempDir.resolve("extraction-log.jsonl").toFile())
        manager = AutoMemoryManager(
            coreMemory = coreMemory,
            archivalMemory = archival,
            client = client,
            extractionLog = extractionLog
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
                ChatMessage(role = "user", content = "Fix the null pointer in UserService"),
                ChatMessage(role = "assistant", content = "Found it"),
                ChatMessage(role = "user", content = "The issue is in getEmail when Optional is empty"),
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

            val messages = listOf(
                ChatMessage(role = "user", content = "Fix the authentication bug in UserService"),
                ChatMessage(role = "assistant", content = "I'll investigate"),
                ChatMessage(role = "user", content = "The issue is with Optional unwrapping"),
                ChatMessage(role = "assistant", content = "Fixed")
            )

            // Should not throw
            manager.onSessionComplete("session-1", messages)

            // Memory unchanged — user block stays empty (empty string after CoreMemory seeds default blocks)
            assertTrue(coreMemory.read("user").isNullOrBlank())
        }

        @Test
        fun `skips extraction for very short conversations`() = runTest {
            val messages = listOf(ChatMessage(role = "user", content = "hi"))

            manager.onSessionComplete("session-1", messages)

            // Client should NOT be called — trivial single-turn session
            coVerify(exactly = 0) { client.sendMessage(any(), any(), any(), any(), any()) }
        }
    }

    @Nested
    inner class SessionQualityGate {

        @Test
        fun `skips extraction when fewer than 2 user turns`() = runTest {
            // 10 total messages but only 1 from the user
            val messages = buildList {
                add(ChatMessage(role = "user", content = "fix the null pointer in UserService"))
                repeat(9) { add(ChatMessage(role = "assistant", content = "tool call $it")) }
            }

            manager.onSessionComplete("session-1", messages)

            // No LLM call should fire
            coVerify(exactly = 0) { client.sendMessage(any(), any(), any(), any(), any()) }
        }

        @Test
        fun `skips extraction when user turns are trivial greetings`() = runTest {
            val messages = listOf(
                ChatMessage(role = "user", content = "hi"),
                ChatMessage(role = "assistant", content = "Hello! How can I help?"),
                ChatMessage(role = "user", content = "ok thanks"),
                ChatMessage(role = "assistant", content = "You're welcome"),
                ChatMessage(role = "user", content = "bye"),
                ChatMessage(role = "assistant", content = "Goodbye")
            )

            manager.onSessionComplete("session-1", messages)

            // No LLM call — all user turns are trivial
            coVerify(exactly = 0) { client.sendMessage(any(), any(), any(), any(), any()) }
        }

        @Test
        fun `extracts when 2+ user turns have substantive content`() = runTest {
            mockLlmResponse("""{"core_memory_updates":[],"archival_inserts":[]}""")

            val messages = listOf(
                ChatMessage(role = "user", content = "Fix the null pointer in UserService"),
                ChatMessage(role = "assistant", content = "I'll check it"),
                ChatMessage(role = "user", content = "The issue is in getEmail — it returns null when Optional is empty"),
                ChatMessage(role = "assistant", content = "Fixed")
            )

            manager.onSessionComplete("session-1", messages)

            coVerify(exactly = 1) { client.sendMessage(any(), any(), any(), any(), any()) }
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

            val messages = substantiveConversation()

            manager.onSessionComplete("session-1", messages)

            assertEquals("Auth migration complete", coreMemory.read("project"))
        }

        @Test
        fun `skips replace when old_content not found`() = runTest {
            coreMemory.append("project", "Some other content")

            val extractionJson = """{"core_memory_updates":[{"block":"project","action":"replace","content":"New content","old_content":"Nonexistent old content"}],"archival_inserts":[]}"""
            mockLlmResponse(extractionJson)

            val messages = substantiveConversation()

            manager.onSessionComplete("session-1", messages)

            // Should not crash, original content preserved
            assertEquals("Some other content", coreMemory.read("project"))
        }
    }

    @Nested
    inner class FuzzyReplace {

        @Test
        fun `applies replace with whitespace-tolerant matching`() = runTest {
            coreMemory.append("project", "Auth migration in progress")

            val extractionJson = """{"core_memory_updates":[{"block":"project","action":"replace","content":"Auth migration complete","old_content":"auth   migration in progress"}],"archival_inserts":[]}"""
            mockLlmResponse(extractionJson)

            val messages = (1..5).map { ChatMessage(role = if (it % 2 == 1) "user" else "assistant", content = "substantive message content number $it for this integration test") }
            manager.onSessionComplete("session-1", messages)

            assertEquals("Auth migration complete", coreMemory.read("project"))
        }
    }

    @Nested
    inner class BlockWhitelist {

        @Test
        fun `ignores updates for unknown block names`() = runTest {
            val extractionJson = """{"core_memory_updates":[{"block":"goals","action":"append","content":"Ship by Q2"}],"archival_inserts":[]}"""
            mockLlmResponse(extractionJson)

            val messages = substantiveConversation()
            manager.onSessionComplete("session-1", messages)

            // "goals" is not in the whitelist, so nothing should be saved
            assertFalse(coreMemory.readAll().containsKey("goals"))
        }

        @Test
        fun `still accepts valid blocks user project patterns`() = runTest {
            val extractionJson = """{"core_memory_updates":[{"block":"patterns","action":"append","content":"TDD always"}],"archival_inserts":[]}"""
            mockLlmResponse(extractionJson)

            val messages = substantiveConversation()
            manager.onSessionComplete("session-1", messages)

            assertEquals("TDD always", coreMemory.read("patterns"))
        }
    }

    @Nested
    inner class DefaultTags {

        @Test
        fun `tagless archival inserts get auto tag`() = runTest {
            val extractionJson = """{"core_memory_updates":[],"archival_inserts":[{"content":"Some insight","tags":[]}]}"""
            mockLlmResponse(extractionJson)

            val messages = substantiveConversation()
            manager.onSessionComplete("session-1", messages)

            val results = archival.search("insight")
            assertEquals(1, results.size)
            assertTrue(results[0].entry.tags.contains("auto"))
        }
    }

    @Nested
    inner class AuditLog {

        @Test
        fun `records successful extractions to log`() = runTest {
            val extractionJson = """{"core_memory_updates":[{"block":"user","action":"append","content":"Backend dev"}],"archival_inserts":[]}"""
            mockLlmResponse(extractionJson)

            val messages = listOf(
                ChatMessage(role = "user", content = "Fix the authentication bug in UserService"),
                ChatMessage(role = "assistant", content = "I'll investigate"),
                ChatMessage(role = "user", content = "The issue is with Optional unwrapping"),
                ChatMessage(role = "assistant", content = "Fixed")
            )

            manager.onSessionComplete("session-1", messages)

            val entries = extractionLog.loadRecent(10)
            assertEquals(1, entries.size)
            assertEquals("session-1", entries[0].sessionId)
            assertEquals("Backend dev", entries[0].coreUpdates[0].content)
        }

        @Test
        fun `does not record when extraction produces no updates`() = runTest {
            val extractionJson = """{"core_memory_updates":[],"archival_inserts":[]}"""
            mockLlmResponse(extractionJson)

            val messages = listOf(
                ChatMessage(role = "user", content = "Fix the authentication bug in UserService"),
                ChatMessage(role = "assistant", content = "I'll investigate"),
                ChatMessage(role = "user", content = "The issue is with Optional unwrapping"),
                ChatMessage(role = "assistant", content = "Fixed")
            )

            manager.onSessionComplete("session-1", messages)

            assertTrue(extractionLog.loadRecent(10).isEmpty())
        }
    }

    /**
     * Build a conversation that passes the session-quality gate (2+ substantive user turns).
     * Use in tests where the LLM extraction path is expected to fire.
     */
    private fun substantiveConversation(): List<ChatMessage> = listOf(
        ChatMessage(role = "user", content = "Refactor the authentication module to use Spring Security"),
        ChatMessage(role = "assistant", content = "I'll start by reviewing the current auth code"),
        ChatMessage(role = "user", content = "Make sure to preserve the existing session cookie behavior"),
        ChatMessage(role = "assistant", content = "Understood, keeping cookies intact")
    )

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
