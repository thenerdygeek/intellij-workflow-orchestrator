package com.workflow.orchestrator.core.ai

import com.workflow.orchestrator.core.ai.dto.FunctionDefinition
import com.workflow.orchestrator.core.ai.dto.FunctionParameters
import com.workflow.orchestrator.core.ai.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.dto.ToolDefinition
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class XmlToolDefinitionBuilderTest {

    @Test
    fun `builds XML for simple tool with no params`() {
        val tools = listOf(
            ToolDefinition(
                type = "function",
                function = FunctionDefinition(
                    name = "think",
                    description = "Pause and reason about the problem",
                    parameters = FunctionParameters(properties = emptyMap())
                )
            )
        )

        val xml = XmlToolDefinitionBuilder.build(tools)

        assertTrue(xml.contains("<tool_definitions>"))
        assertTrue(xml.contains("<tool_name>think</tool_name>"))
        assertTrue(xml.contains("<description>Pause and reason about the problem</description>"))
        assertTrue(xml.contains("</tool_definitions>"))
    }

    @Test
    fun `builds XML with parameters`() {
        val tools = listOf(
            ToolDefinition(
                type = "function",
                function = FunctionDefinition(
                    name = "read_file",
                    description = "Read a file's contents",
                    parameters = FunctionParameters(
                        properties = mapOf(
                            "path" to ParameterProperty(
                                type = "string",
                                description = "The file path to read"
                            )
                        ),
                        required = listOf("path")
                    )
                )
            )
        )

        val xml = XmlToolDefinitionBuilder.build(tools)

        assertTrue(xml.contains("<tool_name>read_file</tool_name>"))
        assertTrue(xml.contains("""<parameter name="path" type="string" required="true">"""))
        assertTrue(xml.contains("The file path to read"))
    }

    @Test
    fun `includes usage instruction block`() {
        val tools = listOf(
            ToolDefinition(
                type = "function",
                function = FunctionDefinition(
                    name = "read_file",
                    description = "Read",
                    parameters = FunctionParameters(properties = emptyMap())
                )
            )
        )

        val xml = XmlToolDefinitionBuilder.build(tools)

        assertTrue(xml.contains("<tool>"))
        assertTrue(xml.contains("<name>"))
        assertTrue(xml.contains("<args>"))
        // Should include usage example
        assertTrue(xml.contains("tool_usage_instructions"))
    }

    @Test
    fun `escapes XML special characters in descriptions`() {
        val tools = listOf(
            ToolDefinition(
                type = "function",
                function = FunctionDefinition(
                    name = "edit_file",
                    description = "Replace old_string -> new_string in a file (use < and > for generics)",
                    parameters = FunctionParameters(
                        properties = mapOf(
                            "path" to ParameterProperty(
                                type = "string",
                                description = "File path (e.g., src/List<String>.kt)"
                            )
                        ),
                        required = listOf("path")
                    )
                )
            )
        )

        val xml = XmlToolDefinitionBuilder.build(tools)

        // < and > in descriptions must be escaped to &lt; and &gt;
        assertTrue(xml.contains("&lt;"), "< should be escaped in tool description, got: $xml")
        assertTrue(xml.contains("&gt;"), "> should be escaped in tool description, got: $xml")
        assertFalse(
            xml.contains("old_string -> new_string in a file (use < and"),
            "Raw < should not appear in description XML"
        )
        // Parameter descriptions should also be escaped
        assertTrue(xml.contains("List&lt;String&gt;"), "Param description should escape generics")
    }
}
