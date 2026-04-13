package com.workflow.orchestrator.agent.session

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DataModelSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `UiMessage round-trips through JSON`() {
        val msg = UiMessage(
            ts = 1712000000000L,
            type = UiMessageType.SAY,
            say = UiSay.TEXT,
            text = "Hello world",
            partial = false,
            conversationHistoryIndex = 3
        )
        val encoded = json.encodeToString(msg)
        val decoded = json.decodeFromString<UiMessage>(encoded)
        assertEquals(msg, decoded)
    }

    @Test
    fun `UiMessage with extension data round-trips`() {
        val msg = UiMessage(
            ts = 1712000000000L,
            type = UiMessageType.ASK,
            ask = UiAsk.APPROVAL_GATE,
            approvalData = ApprovalGateData(
                toolName = "edit_file",
                toolInput = """{"path":"/a.kt","content":"x"}""",
                diffPreview = "- old\n+ new",
                status = ApprovalStatus.PENDING
            )
        )
        val encoded = json.encodeToString(msg)
        val decoded = json.decodeFromString<UiMessage>(encoded)
        assertEquals(msg.approvalData, decoded.approvalData)
    }

    @Test
    fun `ApiMessage with tool_use content round-trips`() {
        val msg = ApiMessage(
            role = ApiRole.ASSISTANT,
            content = listOf(
                ContentBlock.Text("Let me read the file"),
                ContentBlock.ToolUse(id = "tu_1", name = "read_file", input = """{"path":"/a.kt"}""")
            ),
            ts = 1712000000000L
        )
        val encoded = json.encodeToString(msg)
        val decoded = json.decodeFromString<ApiMessage>(encoded)
        assertEquals(2, decoded.content.size)
        assertTrue(decoded.content[1] is ContentBlock.ToolUse)
    }

    @Test
    fun `HistoryItem round-trips`() {
        val item = HistoryItem(
            id = "sess-123",
            ts = 1712000000000L,
            task = "Fix the login bug",
            tokensIn = 50000,
            tokensOut = 2000,
            totalCost = 0.15,
            modelId = "anthropic/claude-sonnet-4"
        )
        val encoded = json.encodeToString(item)
        val decoded = json.decodeFromString<HistoryItem>(encoded)
        assertEquals(item, decoded)
    }

    @Test
    fun `UiMessage ignores unknown fields for forward compatibility`() {
        val jsonStr = """{"ts":1712000000000,"type":"SAY","say":"TEXT","text":"hi","unknownField":42}"""
        val decoded = json.decodeFromString<UiMessage>(jsonStr)
        assertEquals("hi", decoded.text)
    }
}
