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
import com.workflow.orchestrator.core.http.AuthInterceptor
import com.workflow.orchestrator.core.http.AuthScheme
import com.workflow.orchestrator.core.http.RetryInterceptor
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.ConnectionSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class JiraGetTicketTool(
    private val urlProvider: () -> String = { ConnectionSettings.getInstance().state.jiraUrl },
    private val tokenProvider: () -> String? = { CredentialStore().getToken(ServiceType.JIRA) }
) : AgentTool {

    override val name = "jira_get_ticket"
    override val description = "Get Jira ticket details including summary, status, assignee, and description."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "key" to ParameterProperty(type = "string", description = "Jira ticket key, e.g. PROJ-123")
        ),
        required = listOf("key")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val key = params["key"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'key' parameter required", "Error: missing key", 5, isError = true)

        val baseUrl = urlProvider().trimEnd('/')
        if (baseUrl.isBlank()) {
            return ToolResult("Error: Jira URL not configured", "Error: Jira URL not configured", 5, isError = true)
        }

        val token = tokenProvider()
        if (token.isNullOrBlank()) {
            return ToolResult("Error: Jira token not configured", "Error: Jira token not configured", 5, isError = true)
        }

        return withContext(Dispatchers.IO) {
            try {
                val client = buildClient(token)
                val url = "$baseUrl/rest/api/2/issue/$key?expand=renderedFields"
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .header("Accept", "application/json")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext ToolResult(
                            content = "Error: Jira returned HTTP ${response.code} for $key",
                            summary = "Error: HTTP ${response.code}",
                            tokenEstimate = 10,
                            isError = true
                        )
                    }
                    val body = response.body?.string() ?: ""
                    val sanitized = InputSanitizer.sanitizeExternalData(body, "jira", key)
                    ToolResult(
                        content = sanitized,
                        summary = "Retrieved Jira ticket $key",
                        tokenEstimate = TokenEstimator.estimate(sanitized)
                    )
                }
            } catch (e: Exception) {
                ToolResult(
                    content = "Error: Failed to fetch Jira ticket $key: ${e.message}",
                    summary = "Error: ${e.message}",
                    tokenEstimate = 10,
                    isError = true
                )
            }
        }
    }

    private fun buildClient(token: String): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor({ token }, AuthScheme.BEARER))
            .addInterceptor(RetryInterceptor(maxRetries = 2))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
