package com.workflow.orchestrator.agent.memory.auto

import com.workflow.orchestrator.core.ai.SourcegraphChatClient
import com.workflow.orchestrator.core.ai.dto.ChatCompletionResponse
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.ai.dto.Choice
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class MemoryExtractorTest {

    private val client = mockk<SourcegraphChatClient>()
    private lateinit var extractor: MemoryExtractor

    @BeforeEach
    fun setUp() {
        extractor = MemoryExtractor(client)
    }

    @Nested
    inner class ParseResponse {

        @Test
        fun `parses valid extraction JSON`() {
            val json = """{"core_memory_updates":[{"block":"patterns","action":"append","content":"Always return ToolResult"}],"archival_inserts":[{"content":"CORS fix: add origins","tags":["error","cors"]}]}"""

            val result = MemoryExtractor.parseExtractionResponse(json)

            assertNotNull(result)
            assertEquals(1, result!!.coreMemoryUpdates.size)
            assertEquals("patterns", result.coreMemoryUpdates[0].block)
            assertEquals(UpdateAction.APPEND, result.coreMemoryUpdates[0].action)
            assertEquals(1, result.archivalInserts.size)
            assertEquals(2, result.archivalInserts[0].tags.size)
        }

        @Test
        fun `parses empty result`() {
            val json = """{"core_memory_updates":[],"archival_inserts":[]}"""
            val result = MemoryExtractor.parseExtractionResponse(json)
            assertNotNull(result)
            assertTrue(result!!.coreMemoryUpdates.isEmpty())
        }

        @Test
        fun `returns null for invalid JSON`() {
            assertNull(MemoryExtractor.parseExtractionResponse("not json"))
        }

        @Test
        fun `extracts JSON from markdown code fence`() {
            val wrapped = "```json\n{\"core_memory_updates\":[],\"archival_inserts\":[{\"content\":\"test\",\"tags\":[\"a\"]}]}\n```"
            val result = MemoryExtractor.parseExtractionResponse(wrapped)
            assertNotNull(result)
            assertEquals(1, result!!.archivalInserts.size)
        }

        @Test
        fun `handles replace action with old_content`() {
            val json = """{"core_memory_updates":[{"block":"project","action":"replace","content":"Auth done","old_content":"Auth in progress"}],"archival_inserts":[]}"""
            val result = MemoryExtractor.parseExtractionResponse(json)
            assertNotNull(result)
            assertEquals(UpdateAction.REPLACE, result!!.coreMemoryUpdates[0].action)
            assertEquals("Auth in progress", result.coreMemoryUpdates[0].oldContent)
        }
    }

    @Nested
    inner class ExtractFromSession {

        @Test
        fun `calls LLM and returns parsed result`() = runTest {
            val responseJson = """{"core_memory_updates":[{"block":"user","action":"append","content":"Backend dev"}],"archival_inserts":[]}"""
            mockLlmResponse(responseJson)

            val messages = listOf(
                ChatMessage(role = "user", content = "Fix the auth bug"),
                ChatMessage(role = "assistant", content = "I'll check the SecurityConfig...")
            )
            val result = extractor.extractFromSession(messages, emptyMap())

            assertNotNull(result)
            assertEquals(1, result!!.coreMemoryUpdates.size)
        }

        @Test
        fun `returns null on API error`() = runTest {
            coEvery {
                client.sendMessage(any(), any(), any(), any(), any())
            } returns ApiResult.Error(ErrorType.SERVER_ERROR, "500")

            val result = extractor.extractFromSession(
                listOf(ChatMessage(role = "user", content = "test")),
                emptyMap()
            )
            assertNull(result)
        }

        @Test
        fun `returns null on empty conversation`() = runTest {
            val result = extractor.extractFromSession(emptyList(), emptyMap())
            assertNull(result)

            // Verify no LLM call was made
            coVerify(exactly = 0) { client.sendMessage(any(), any(), any(), any(), any()) }
        }

        @Test
        fun `redacts credentials before sending to extraction LLM`() = runTest {
            // I3 fix: capture the outgoing messages so we can assert on their content
            val capturedSlot = slot<List<ChatMessage>>()
            coEvery {
                client.sendMessage(capture(capturedSlot), any(), any(), any(), any())
            } returns ApiResult.Success(
                ChatCompletionResponse(
                    id = "test",
                    choices = listOf(
                        Choice(
                            index = 0,
                            message = ChatMessage(
                                role = "assistant",
                                content = "{\"core_memory_updates\":[],\"archival_inserts\":[]}"
                            ),
                            finishReason = "stop"
                        )
                    )
                )
            )

            val messages = listOf(
                ChatMessage(
                    role = "user",
                    content = "Here's the key:\n-----BEGIN RSA PRIVATE KEY-----\nMIIEpAIBAAKCAQEA1234567890abcdef\n-----END RSA PRIVATE KEY-----"
                ),
                ChatMessage(role = "assistant", content = "got it")
            )

            extractor.extractFromSession(messages, emptyMap())

            val promptContent = capturedSlot.captured.firstOrNull()?.content ?: ""
            assertFalse(
                promptContent.contains("-----BEGIN RSA PRIVATE KEY-----"),
                "Raw PEM private key should not be sent to extraction LLM"
            )
            assertFalse(
                promptContent.contains("MIIEpAIBAAKCAQEA1234567890abcdef"),
                "Raw PEM body should not be sent to extraction LLM"
            )
            assertTrue(
                promptContent.contains("REDACTED"),
                "Credential should be replaced with a REDACTED marker"
            )
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
