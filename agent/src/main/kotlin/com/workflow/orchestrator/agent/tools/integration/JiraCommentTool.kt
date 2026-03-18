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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
class JiraCommentTool(
    private val urlProvider: () -> String = { ConnectionSettings.getInstance().state.jiraUrl },
    private val tokenProvider: () -> String? = { CredentialStore().getToken(ServiceType.JIRA) }
) : AgentTool {

    override val name = "jira_comment"
    override val description = "Add a comment to a Jira ticket."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "key" to ParameterProperty(type = "string", description = "Jira ticket key, e.g. PROJ-123"),
            "body" to ParameterProperty(type = "string", description = "Comment body text (wiki markup supported)")
        ),
        required = listOf("key", "body")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val key = params["key"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'key' parameter required", "Error: missing key", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val body = params["body"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'body' parameter required", "Error: missing body", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val (baseUrl, token, client) = IntegrationToolSupport.resolveCredentials(urlProvider, tokenProvider, "Jira")
            ?: return if (urlProvider()?.trimEnd('/')?.isBlank() != false) {
                IntegrationToolSupport.credentialError("Jira", "URL")
            } else {
                IntegrationToolSupport.credentialError("Jira", "token")
            }

        return withContext(Dispatchers.IO) {
            try {
                val url = "$baseUrl/rest/api/2/issue/$key/comment"
                // Escape the body for JSON embedding
                val escapedBody = body.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                val jsonBody = """{"body":"$escapedBody"}"""
                val request = Request.Builder()
                    .url(url)
                    .post(jsonBody.toRequestBody("application/json".toMediaType()))
                    .header("Accept", "application/json")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string() ?: ""
                        val sanitized = InputSanitizer.sanitizeExternalData(errorBody, "jira", key)
                        return@withContext ToolResult(
                            content = "Error: Jira returned HTTP ${response.code} for comment on $key: $sanitized",
                            summary = "Error: HTTP ${response.code}",
                            tokenEstimate = 10,
                            isError = true
                        )
                    }
                    val responseBody = response.body?.string() ?: ""
                    val sanitized = InputSanitizer.sanitizeExternalData(responseBody, "jira", key)
                    ToolResult(
                        content = "Comment added to $key. Response: $sanitized",
                        summary = "Added comment to $key",
                        tokenEstimate = TokenEstimator.estimate(sanitized)
                    )
                }
            } catch (e: Exception) {
                ToolResult(
                    content = "Error: Failed to add comment to $key: ${e.message}",
                    summary = "Error: ${e.message}",
                    tokenEstimate = 10,
                    isError = true
                )
            }
        }
    }

}
