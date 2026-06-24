package com.workflow.orchestrator.agent.tools

import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import com.workflow.orchestrator.core.ai.dto.FunctionParameters

/**
 * A bare AgentTool that overrides only the required members inherits safe defaults
 * for every safety property — so a B-contributed tool that declares none of them is
 * treated as a non-mutating, hook-observed, no-approval-needed read tool.
 */
class AgentToolSafetyPropsDefaultTest {
    private val bareTool = object : AgentTool {
        override val name = "bare"
        override val description = "bare"
        override val parameters = FunctionParameters(properties = emptyMap())
        override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR)
        override suspend fun execute(params: JsonObject, project: Project) =
            ToolResult(content = "ok", summary = "ok", tokenEstimate = 1)
    }

    @Test
    fun `defaults are all false`() {
        assertFalse(bareTool.isMutating, "isMutating default")
        assertFalse(bareTool.isHookExempt, "isHookExempt default")
        assertFalse(bareTool.requiresApproval, "requiresApproval default")
        assertFalse(bareTool.allowSessionApproval, "allowSessionApproval default")
    }
}
