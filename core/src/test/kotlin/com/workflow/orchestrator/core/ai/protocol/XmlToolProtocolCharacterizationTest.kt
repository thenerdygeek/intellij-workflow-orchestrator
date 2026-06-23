package com.workflow.orchestrator.core.ai.protocol

import com.workflow.orchestrator.core.ai.AssistantMessageParser
import com.workflow.orchestrator.core.ai.GatewayErrorDetector
import com.workflow.orchestrator.core.ai.ToolPromptBuilder
import com.workflow.orchestrator.core.ai.dto.FunctionDefinition
import com.workflow.orchestrator.core.ai.dto.FunctionParameters
import com.workflow.orchestrator.core.ai.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.dto.ToolDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class XmlToolProtocolCharacterizationTest {

    // DTO ctor shapes VERIFIED against core/.../ai/dto/ToolCallModels.kt:
    //   ParameterProperty(type: String /* no default */, description: String, enumValues?, items?)
    //   FunctionParameters(type="object" default, properties, required=emptyList() default)
    // `type` on ParameterProperty has NO default → it MUST be supplied (I1).
    private fun sampleTools(): List<ToolDefinition> = listOf(
        ToolDefinition(
            function = FunctionDefinition(
                name = "read_file",
                description = "Read a file.",
                parameters = FunctionParameters(
                    properties = mapOf("path" to ParameterProperty(type = "string", description = "the path")),
                    required = listOf("path"),
                ),
            ),
        ),
    )

    @Test fun `presentTools is byte-identical to ToolPromptBuilder build`() {
        val tools = sampleTools()
        assertEquals(ToolPromptBuilder.build(tools), XmlToolProtocol().presentTools(tools))
    }

    @Test fun `parseToolCalls is identical to AssistantMessageParser parse`() {
        val text = "thinking...\n<read_file>\n<path>/foo.kt</path>\n</read_file>"
        val toolNames = setOf("read_file")
        val paramNames = setOf("path")
        assertEquals(
            AssistantMessageParser.parse(text, toolNames, paramNames),
            XmlToolProtocol().parseToolCalls(text, toolNames, paramNames),
        )
    }

    @Test fun `stripPartialTag is identical to AssistantMessageParser stripPartialTag (GAP1)`() {
        val p = XmlToolProtocol()
        // A trailing incomplete tag must be stripped exactly as the parser does today.
        for (s in listOf("hello <read", "hello <read_file>\n<pa", "plain text, no tag", "<")) {
            assertEquals(
                AssistantMessageParser.stripPartialTag(s),
                p.stripPartialTag(s),
                "stripPartialTag mismatch on: $s",
            )
        }
    }

    @Test fun `endsWithIncompleteTag is identical to AssistantMessageParser endsWithIncompleteTag (GAP1)`() {
        val p = XmlToolProtocol()
        // CharSequence inputs (StringBuilder + String) must classify identically to the parser.
        val cases: List<CharSequence> = listOf(
            StringBuilder("foo <read"),
            "complete <a>b</a>",
            "no tag here",
            StringBuilder("trailing <"),
        )
        for (c in cases) {
            assertEquals(
                AssistantMessageParser.endsWithIncompleteTag(c),
                p.endsWithIncompleteTag(c),
                "endsWithIncompleteTag mismatch on: $c",
            )
        }
    }

    @Test fun `tool result wire prefix is the current literal`() {
        assertEquals("TOOL RESULT:\n", XmlToolProtocol().toolResultWirePrefix)
    }

    @Test fun `xml protocol requires the dialect guard`() {
        assertTrue(XmlToolProtocol().requiresDialectGuard)
    }

    @Test fun `classifyStreamLine matches GatewayErrorDetector`() {
        val frame = """data: {"type":"completion.process_completion","error":"context deadline exceeded"}"""
        assertTrue(GatewayErrorDetector.isUpstreamTimeoutFrame(frame))
        assertEquals("upstream_timeout", XmlToolProtocol().classifyStreamLine(frame))
        assertNull(XmlToolProtocol().classifyStreamLine("""data: {"choices":[]}"""))
    }
}
