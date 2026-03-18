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
import okhttp3.Request

class BambooBuildTool(
    private val urlProvider: () -> String = { ConnectionSettings.getInstance().state.bambooUrl },
    private val tokenProvider: () -> String? = { CredentialStore().getToken(ServiceType.BAMBOO) }
) : AgentTool {

    override val name = "bamboo_build_status"
    override val description = "Get the latest build status for a Bamboo plan including state, stages, and duration."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "plan_key" to ParameterProperty(type = "string", description = "Bamboo plan key, e.g. PROJ-PLAN")
        ),
        required = listOf("plan_key")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val planKey = params["plan_key"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'plan_key' parameter required", "Error: missing plan_key", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val (baseUrl, token, client) = IntegrationToolSupport.resolveCredentials(urlProvider, tokenProvider, "Bamboo")
            ?: return if (urlProvider()?.trimEnd('/')?.isBlank() != false) {
                IntegrationToolSupport.credentialError("Bamboo", "URL")
            } else {
                IntegrationToolSupport.credentialError("Bamboo", "token")
            }

        return withContext(Dispatchers.IO) {
            try {
                val url = "$baseUrl/rest/api/latest/result/$planKey/latest"
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .header("Accept", "application/json")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext ToolResult(
                            content = "Error: Bamboo returned HTTP ${response.code} for plan $planKey",
                            summary = "Error: HTTP ${response.code}",
                            tokenEstimate = 10,
                            isError = true
                        )
                    }
                    val body = response.body?.string() ?: ""
                    val sanitized = InputSanitizer.sanitizeExternalData(body, "bamboo", planKey)
                    ToolResult(
                        content = sanitized,
                        summary = "Retrieved build status for $planKey",
                        tokenEstimate = TokenEstimator.estimate(sanitized)
                    )
                }
            } catch (e: Exception) {
                ToolResult(
                    content = "Error: Failed to fetch build status for $planKey: ${e.message}",
                    summary = "Error: ${e.message}",
                    tokenEstimate = 10,
                    isError = true
                )
            }
        }
    }

}
