package com.workflow.orchestrator.core.ai

import com.workflow.orchestrator.core.ai.dto.FunctionDefinition
import com.workflow.orchestrator.core.ai.dto.FunctionParameters
import com.workflow.orchestrator.core.ai.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.dto.ToolDefinition
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ToolPromptBuilderTest {

    @Test
    fun `builds markdown with XML usage example`() {
        val tools = listOf(
            ToolDefinition(
                function = FunctionDefinition(
                    name = "read_file",
                    description = "Read a file's contents",
                    parameters = FunctionParameters(
                        properties = mapOf(
                            "path" to ParameterProperty(type = "string", description = "The file path")
                        ),
                        required = listOf("path")
                    )
                )
            )
        )

        val markdown = ToolPromptBuilder.build(tools)

        assertTrue(markdown.contains("## read_file"))
        assertTrue(markdown.contains("Read a file's contents"))
        assertTrue(markdown.contains("- path: (required)"))
        assertTrue(markdown.contains("<read_file>"))
        assertTrue(markdown.contains("<path>"))
        assertTrue(markdown.contains("</read_file>"))
    }

    @Test
    fun `includes format instructions`() {
        val tools = listOf(
            ToolDefinition(
                function = FunctionDefinition(
                    name = "think",
                    description = "Pause and reason",
                    parameters = FunctionParameters(properties = emptyMap())
                )
            )
        )

        val markdown = ToolPromptBuilder.build(tools)

        assertTrue(markdown.contains("Tool Use Format"))
        assertTrue(markdown.contains("<tool_name>"))
        assertTrue(markdown.contains("<parameter_name>"))
    }

    @Test
    fun `escapes XML special characters in descriptions`() {
        val tools = listOf(
            ToolDefinition(
                function = FunctionDefinition(
                    name = "edit_file",
                    description = "Replace old_string -> new_string (use < and > for generics)",
                    parameters = FunctionParameters(
                        properties = mapOf(
                            "path" to ParameterProperty(type = "string", description = "Path to List<String>")
                        ),
                        required = listOf("path")
                    )
                )
            )
        )

        val markdown = ToolPromptBuilder.build(tools)

        assertTrue(markdown.contains("&lt;"), "< should be escaped")
        assertTrue(markdown.contains("&gt;"), "> should be escaped")
    }

    @Test
    fun `marks optional params`() {
        val tools = listOf(
            ToolDefinition(
                function = FunctionDefinition(
                    name = "search_code",
                    description = "Search code",
                    parameters = FunctionParameters(
                        properties = mapOf(
                            "pattern" to ParameterProperty(type = "string", description = "Regex pattern"),
                            "directory" to ParameterProperty(type = "string", description = "Search directory")
                        ),
                        required = listOf("pattern")
                    )
                )
            )
        )

        val markdown = ToolPromptBuilder.build(tools)

        assertTrue(markdown.contains("- pattern: (required)"))
        assertTrue(markdown.contains("- directory: (optional)"))
    }
}
