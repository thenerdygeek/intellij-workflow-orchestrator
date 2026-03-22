package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class BitbucketGetFileContentTool : AgentTool {
    override val name = "bitbucket_get_file_content"
    override val description = "Get raw file content from the Bitbucket repository at a specific ref (branch, tag, or commit)."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "file_path" to ParameterProperty(type = "string", description = "File path relative to repository root"),
            "at_ref" to ParameterProperty(type = "string", description = "Git ref to read from (branch name, tag, or commit hash)"),
            "repo_name" to ParameterProperty(type = "string", description = "Repository name for multi-repo projects. Omit for single-repo or to use the primary repository.")
        ),
        required = listOf("file_path", "at_ref")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ORCHESTRATOR, WorkerType.REVIEWER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val filePath = params["file_path"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'file_path' parameter required", "Error: missing file_path", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val atRef = params["at_ref"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'at_ref' parameter required", "Error: missing at_ref", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        ToolValidation.validateNotBlank(filePath, "file_path")?.let { return it }
        ToolValidation.validateNotBlank(atRef, "at_ref")?.let { return it }

        val service = ServiceLookup.bitbucket(project) ?: return ServiceLookup.notConfigured("Bitbucket")

        val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull

        return service.getFileContent(filePath, atRef, repoName = repoName).toAgentToolResult()
    }
}
