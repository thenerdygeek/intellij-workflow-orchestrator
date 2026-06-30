package com.workflow.orchestrator.core.ai.anthropic

import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.ai.dto.ContentPart
import com.workflow.orchestrator.core.ai.dto.FunctionDefinition
import com.workflow.orchestrator.core.ai.dto.FunctionParameters
import com.workflow.orchestrator.core.ai.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.dto.ToolDefinition
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AnthropicRequestMapperTest {

    private fun toolDef() = ToolDefinition(
        function = FunctionDefinition(
            "read_file",
            "Read a file",
            FunctionParameters(
                properties = mapOf("path" to ParameterProperty("string", "the path")),
                required = listOf("path"),
            ),
        ),
    )

    private val noImg: (String) -> Pair<String, String>? = { null }

    @Test
    fun `system role maps to top-level system field not a message`() {
        val req = AnthropicRequestMapper.build(
            listOf(ChatMessage("system", "SYS"), ChatMessage("user", "hi")),
            tools = null,
            model = "claude-opus-4-8",
            maxTokens = 4096,
            thinkingEnabled = false,
            effort = "high",
            imageBytes = noImg,
        )
        assertEquals("SYS", req.system.first().text)
        assertTrue(req.messages.none { it.role == "system" })
        assertEquals("user", req.messages.single().role)
    }

    @Test
    fun `never emits sampling params`() {
        val json = Json.encodeToString(
            AnthropicRequestMapper.build(
                listOf(ChatMessage("user", "hi")),
                null,
                "claude-opus-4-8",
                4096,
                true,
                "high",
                noImg,
            ),
        )
        listOf("temperature", "top_p", "top_k", "budget_tokens").forEach {
            assertFalse(json.contains(it))
        }
    }

    @Test
    fun `system block carries one ephemeral cache_control`() {
        val req = AnthropicRequestMapper.build(
            listOf(ChatMessage("system", "S"), ChatMessage("user", "x")),
            null,
            "claude-opus-4-8",
            4096,
            false,
            "high",
            noImg,
        )
        assertEquals("ephemeral", req.system.single().cacheControl?.type)
    }

    @Test
    fun `tool def unwraps openai compat to input_schema`() {
        val req = AnthropicRequestMapper.build(
            listOf(ChatMessage("user", "x")),
            listOf(toolDef()),
            "claude-opus-4-8",
            4096,
            false,
            "high",
            noImg,
        )
        val t = req.tools!!.single()
        assertEquals("read_file", t.name)
        assertEquals("object", t.inputSchema.type)
        assertEquals(listOf("path"), t.inputSchema.required)
    }

    @Test
    fun `tool result message maps to tool_result block`() {
        val req = AnthropicRequestMapper.build(
            listOf(ChatMessage("tool", content = "OUT", toolCallId = "tu_1")),
            null,
            "claude-opus-4-8",
            4096,
            false,
            "high",
            noImg,
        )
        val block = req.messages.single().content.first()
        assertEquals("tool_result", block.type)
        assertEquals("tu_1", block.toolUseId)
    }

    @Test
    fun `image part hydrates to base64 image block`() {
        val msg = ChatMessage("user", parts = listOf(ContentPart.Image("sha", "image/png")))
        val req = AnthropicRequestMapper.build(
            listOf(msg),
            null,
            "claude-opus-4-8",
            4096,
            false,
            "high",
        ) { "image/png" to "BASE64" }
        val block = req.messages.single().content.first()
        assertEquals("image", block.type)
        assertEquals("BASE64", block.source!!.data)
    }

    @Test
    fun `thinking block present only when enabled, display summarized`() {
        val on = AnthropicRequestMapper.build(
            listOf(ChatMessage("user", "x")),
            null,
            "claude-opus-4-8",
            4096,
            true,
            "high",
            noImg,
        )
        assertEquals("adaptive", on.thinking!!.type)
        assertEquals("summarized", on.thinking!!.display)
        assertEquals("high", on.outputConfig!!.effort)

        val off = AnthropicRequestMapper.build(
            listOf(ChatMessage("user", "x")),
            null,
            "claude-opus-4-8",
            4096,
            false,
            "high",
            noImg,
        )
        assertNull(off.thinking)
    }

    @Test
    fun `array param maps to items schema`() {
        val arr = ToolDefinition(
            function = FunctionDefinition(
                "grep",
                "g",
                FunctionParameters(
                    properties = mapOf(
                        "globs" to ParameterProperty(
                            "array",
                            "globs",
                            items = ParameterProperty("string", "one"),
                        ),
                    ),
                ),
            ),
        )
        val schema = AnthropicRequestMapper.build(
            listOf(ChatMessage("user", "x")),
            listOf(arr),
            "claude-opus-4-8",
            4096,
            false,
            "high",
            noImg,
        ).tools!!.single().inputSchema
        val globs = schema.properties["globs"]!!
        assertEquals("array", globs.type)
        assertEquals("string", globs.items!!.type)
    }

    @Test
    fun `enum param carries enum constraint into input_schema`() {
        val enumTool = ToolDefinition(
            function = FunctionDefinition(
                "set_status",
                "s",
                FunctionParameters(
                    properties = mapOf(
                        "status" to ParameterProperty(
                            "string",
                            "the status",
                            enumValues = listOf("open", "done"),
                        ),
                    ),
                ),
            ),
        )
        val prop = AnthropicRequestMapper.build(
            listOf(ChatMessage("user", "x")),
            listOf(enumTool),
            "claude-opus-4-8",
            4096,
            false,
            "high",
            noImg,
        ).tools!!.single().inputSchema.properties["status"]!!
        assertEquals(listOf("open", "done"), prop.enumValues)
    }
}
