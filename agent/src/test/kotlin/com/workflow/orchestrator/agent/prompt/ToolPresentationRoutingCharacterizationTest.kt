package com.workflow.orchestrator.agent.prompt

import com.workflow.orchestrator.core.ai.ToolPromptBuilder
import com.workflow.orchestrator.core.ai.dto.FunctionDefinition
import com.workflow.orchestrator.core.ai.dto.FunctionParameters
import com.workflow.orchestrator.core.ai.dto.ToolDefinition
import com.workflow.orchestrator.core.ai.protocol.XmlToolProtocol
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ToolPresentationRoutingCharacterizationTest {
    @Test fun `routing tool presentation through XmlToolProtocol equals direct ToolPromptBuilder`() {
        val tools = listOf(
            ToolDefinition(function = FunctionDefinition(
                name = "list_dir", description = "List a dir.",
                parameters = FunctionParameters(properties = emptyMap()),
            )),
        )
        // This is the exact substitution applied at AgentService:2205 and SubagentRunner:625.
        assertEquals(ToolPromptBuilder.build(tools), XmlToolProtocol().presentTools(tools))
    }
}
