package com.workflow.orchestrator.agent.session

import com.workflow.orchestrator.core.ai.dto.ChatMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ApiMessageTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `thinking content block round-trips through toChatMessage`() {
        val apiMsg = ApiMessage(
            role = ApiRole.ASSISTANT,
            content = listOf(
                ContentBlock.Thinking(thinking = "Let me analyze this step by step...", summary = "Analysis"),
                ContentBlock.Text("Here is my answer.")
            )
        )
        val chatMsg = apiMsg.toChatMessage()
        assertEquals("assistant", chatMsg.role)
        assertEquals("Here is my answer.", chatMsg.content)
        assertEquals("Let me analyze this step by step...", chatMsg.reasoning)
    }

    @Test
    fun `thinking content block serializes and deserializes`() {
        val block: ContentBlock = ContentBlock.Thinking(thinking = "reasoning here", summary = "brief")
        val encoded = json.encodeToString(ContentBlock.serializer(), block)
        val deserialized = json.decodeFromString(ContentBlock.serializer(), encoded)
        assertEquals(block, deserialized)
    }

    @Test
    fun `redacted thinking content block serializes and deserializes`() {
        val block: ContentBlock = ContentBlock.RedactedThinking(data = "base64encodeddata")
        val encoded = json.encodeToString(ContentBlock.serializer(), block)
        val deserialized = json.decodeFromString(ContentBlock.serializer(), encoded)
        assertEquals(block, deserialized)
    }

    @Test
    fun `toApiMessage preserves reasoning as Thinking block`() {
        val chatMsg = ChatMessage(role = "assistant", content = "answer", reasoning = "thinking...")
        val apiMsg = chatMsg.toApiMessage()
        val thinkingBlocks = apiMsg.content.filterIsInstance<ContentBlock.Thinking>()
        assertEquals(1, thinkingBlocks.size)
        assertEquals("thinking...", thinkingBlocks[0].thinking)
    }

    @Test
    fun `toApiMessage omits Thinking block when reasoning is null`() {
        val chatMsg = ChatMessage(role = "assistant", content = "answer")
        val apiMsg = chatMsg.toApiMessage()
        val thinkingBlocks = apiMsg.content.filterIsInstance<ContentBlock.Thinking>()
        assertTrue(thinkingBlocks.isEmpty())
    }

    @Test
    fun `thinking round-trips through toApiMessage and toChatMessage`() {
        val original = ChatMessage(role = "assistant", content = "result", reasoning = "deep thought")
        val apiMsg = original.toApiMessage()
        val restored = apiMsg.toChatMessage()
        assertEquals(original.role, restored.role)
        assertEquals(original.content, restored.content)
        assertEquals(original.reasoning, restored.reasoning)
    }

    @Test
    fun `ApiMessage with thinking and tool use preserves both`() {
        val apiMsg = ApiMessage(
            role = ApiRole.ASSISTANT,
            content = listOf(
                ContentBlock.Thinking(thinking = "I should read the file first"),
                ContentBlock.Text("Let me check."),
                ContentBlock.ToolUse(id = "tc1", name = "read_file", input = """{"path":"/a.kt"}""")
            )
        )
        val chatMsg = apiMsg.toChatMessage()
        assertEquals("assistant", chatMsg.role)
        assertEquals("Let me check.", chatMsg.content)
        assertEquals("I should read the file first", chatMsg.reasoning)
        assertNotNull(chatMsg.toolCalls)
        assertEquals(1, chatMsg.toolCalls!!.size)
        assertEquals("read_file", chatMsg.toolCalls!![0].function.name)
    }

    @Test
    fun `multiple thinking blocks joined with newline`() {
        val apiMsg = ApiMessage(
            role = ApiRole.ASSISTANT,
            content = listOf(
                ContentBlock.Thinking(thinking = "First thought"),
                ContentBlock.Thinking(thinking = "Second thought"),
                ContentBlock.Text("Final answer.")
            )
        )
        val chatMsg = apiMsg.toChatMessage()
        assertEquals("First thought\nSecond thought", chatMsg.reasoning)
        assertEquals("Final answer.", chatMsg.content)
    }

    @Test
    fun `ApiMessage with thinking serializes and deserializes`() {
        val apiMsg = ApiMessage(
            role = ApiRole.ASSISTANT,
            content = listOf(
                ContentBlock.Thinking(thinking = "reasoning", summary = "brief"),
                ContentBlock.Text("visible text")
            )
        )
        val encoded = json.encodeToString(apiMsg)
        val decoded = json.decodeFromString<ApiMessage>(encoded)
        assertEquals(apiMsg.content.size, decoded.content.size)
        val thinking = decoded.content[0] as ContentBlock.Thinking
        assertEquals("reasoning", thinking.thinking)
        assertEquals("brief", thinking.summary)
    }
}
