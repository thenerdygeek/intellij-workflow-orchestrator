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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class JiraTransitionTool(
    private val urlProvider: () -> String = { ConnectionSettings.getInstance().state.jiraUrl },
    private val tokenProvider: () -> String? = { CredentialStore().getToken(ServiceType.JIRA) }
) : AgentTool {

    override val name = "jira_transition"
    override val description = "Transition a Jira ticket to a new status using the transition ID."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "key" to ParameterProperty(type = "string", description = "Jira ticket key, e.g. PROJ-123"),
            "transition_id" to ParameterProperty(type = "string", description = "Transition ID to execute")
        ),
        required = listOf("key", "transition_id")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val key = params["key"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'key' parameter required", "Error: missing key", 5, isError = true)
        val transitionId = params["transition_id"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'transition_id' parameter required", "Error: missing transition_id", 5, isError = true)

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
                val url = "$baseUrl/rest/api/2/issue/$key/transitions"
                val jsonBody = """{"transition":{"id":"$transitionId"}}"""
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
                            content = "Error: Jira returned HTTP ${response.code} for transition on $key: $sanitized",
                            summary = "Error: HTTP ${response.code}",
                            tokenEstimate = 10,
                            isError = true
                        )
                    }
                    ToolResult(
                        content = "Successfully transitioned $key with transition ID $transitionId",
                        summary = "Transitioned $key",
                        tokenEstimate = 10
                    )
                }
            } catch (e: Exception) {
                ToolResult(
                    content = "Error: Failed to transition $key: ${e.message}",
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
