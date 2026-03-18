package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.security.InputSanitizer
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.ConnectionSettings
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
class BitbucketPrTool(
    private val urlProvider: () -> String = { ConnectionSettings.getInstance().state.bitbucketUrl },
    private val tokenProvider: () -> String? = { CredentialStore().getToken(ServiceType.BITBUCKET) },
    private val projectKeyProvider: () -> String = { "" },
    private val repoSlugProvider: () -> String = { "" }
) : AgentTool {

    override val name = "bitbucket_create_pr"
    override val description = "Create a pull request on Bitbucket Server."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "title" to ParameterProperty(type = "string", description = "Pull request title"),
            "description" to ParameterProperty(type = "string", description = "Pull request description"),
            "from_branch" to ParameterProperty(type = "string", description = "Source branch name"),
            "to_branch" to ParameterProperty(type = "string", description = "Target branch name (default: master)")
        ),
        required = listOf("title", "description", "from_branch")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val title = params["title"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'title' parameter required", "Error: missing title", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val description = params["description"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'description' parameter required", "Error: missing description", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val fromBranch = params["from_branch"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'from_branch' parameter required", "Error: missing from_branch", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val toBranch = params["to_branch"]?.jsonPrimitive?.content ?: "master"

        val (baseUrl, token, client) = IntegrationToolSupport.resolveCredentials(urlProvider, tokenProvider, "Bitbucket")
            ?: return if (urlProvider()?.trimEnd('/')?.isBlank() != false) {
                IntegrationToolSupport.credentialError("Bitbucket", "URL")
            } else {
                IntegrationToolSupport.credentialError("Bitbucket", "token")
            }

        val projectKey = projectKeyProvider().ifBlank {
            try { PluginSettings.getInstance(project).state.bitbucketProjectKey ?: "" } catch (_: Exception) { "" }
        }
        val repoSlug = repoSlugProvider().ifBlank {
            try { PluginSettings.getInstance(project).state.bitbucketRepoSlug ?: "" } catch (_: Exception) { "" }
        }

        if (projectKey.isBlank() || repoSlug.isBlank()) {
            return ToolResult(
                "Error: Bitbucket project key or repo slug not configured",
                "Error: Bitbucket project/repo not configured", 5, isError = true
            )
        }

        return withContext(Dispatchers.IO) {
            try {
                val url = "$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests"
                val escapedTitle = title.replace("\\", "\\\\").replace("\"", "\\\"")
                val escapedDesc = description.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                val jsonBody = """{
                    "title": "$escapedTitle",
                    "description": "$escapedDesc",
                    "fromRef": {"id": "refs/heads/$fromBranch"},
                    "toRef": {"id": "refs/heads/$toBranch"}
                }""".trimIndent()
                val request = Request.Builder()
                    .url(url)
                    .post(jsonBody.toRequestBody("application/json".toMediaType()))
                    .header("Accept", "application/json")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string() ?: ""
                        val sanitized = InputSanitizer.sanitizeExternalData(errorBody, "bitbucket", "$projectKey/$repoSlug")
                        return@withContext ToolResult(
                            content = "Error: Bitbucket returned HTTP ${response.code}: $sanitized",
                            summary = "Error: HTTP ${response.code}",
                            tokenEstimate = 10,
                            isError = true
                        )
                    }
                    val body = response.body?.string() ?: ""
                    val sanitized = InputSanitizer.sanitizeExternalData(body, "bitbucket", "$projectKey/$repoSlug")
                    ToolResult(
                        content = "Pull request created: $sanitized",
                        summary = "Created PR: $title ($fromBranch -> $toBranch)",
                        tokenEstimate = TokenEstimator.estimate(sanitized)
                    )
                }
            } catch (e: Exception) {
                ToolResult(
                    content = "Error: Failed to create PR: ${e.message}",
                    summary = "Error: ${e.message}",
                    tokenEstimate = 10,
                    isError = true
                )
            }
        }
    }

}
