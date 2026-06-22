package com.workflow.orchestrator.agent.tools.contribution

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolRegistry
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import io.mockk.mockk
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class ToolRegistrationContextTest {
    private class NoopTool : AgentTool {
        override val name = "companyb_noop"
        override val description = "no-op contributed tool"
        override val parameters = FunctionParameters(properties = emptyMap())
        override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR)
        override suspend fun execute(params: JsonObject, project: Project) =
            ToolResult(content = "ok", summary = "ok", tokenEstimate = 1)
    }

    @Test fun `contributor registers a tool into the registry via the context`() {
        val registry = ToolRegistry()
        val context = ToolRegistrationContext(mockk(relaxed = true), registry)
        object : AgentToolContributor {
            override fun registerTools(ctx: ToolRegistrationContext) = ctx.registerCore(NoopTool())
        }.registerTools(context)
        assertNotNull(registry.get("companyb_noop"))
    }
}
