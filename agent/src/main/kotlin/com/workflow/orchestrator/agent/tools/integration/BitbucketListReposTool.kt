package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject

class BitbucketListReposTool : AgentTool {
    override val name = "bitbucket_list_repos"
    override val description = "List all configured Bitbucket repositories for the current project. Shows repo name, project key, slug, and which is the primary repository."
    override val parameters = FunctionParameters(properties = emptyMap(), required = emptyList())
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER, WorkerType.TOOLER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val service = ServiceLookup.bitbucket(project) ?: return ServiceLookup.notConfigured("Bitbucket")
        return service.listRepos().toAgentToolResult()
    }
}
