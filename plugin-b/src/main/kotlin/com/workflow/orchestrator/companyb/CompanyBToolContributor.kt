package com.workflow.orchestrator.companyb

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.contribution.AgentToolContributor
import com.workflow.orchestrator.agent.tools.contribution.ToolRegistrationContext
import kotlinx.serialization.json.JsonObject

class CompanyBToolContributor : AgentToolContributor {
    override fun registerTools(context: ToolRegistrationContext) =
        context.registerCore(object : AgentTool {
            override val name = "companyb_noop"
            override val description = "Company B no-op demo tool"
            override val parameters = FunctionParameters(properties = emptyMap())
            override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR)
            override suspend fun execute(params: JsonObject, project: Project) =
                ToolResult(content = "ok", summary = "ok", tokenEstimate = 1)
        })
}
