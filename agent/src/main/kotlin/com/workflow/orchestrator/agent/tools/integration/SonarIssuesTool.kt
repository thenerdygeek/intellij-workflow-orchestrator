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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

class SonarIssuesTool(
    private val urlProvider: () -> String = { ConnectionSettings.getInstance().state.sonarUrl },
    private val tokenProvider: () -> String? = { CredentialStore().getToken(ServiceType.SONARQUBE) }
) : AgentTool {

    override val name = "sonar_issues"
    override val description = "Get open SonarQube issues for a project, optionally filtered by file."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "project_key" to ParameterProperty(type = "string", description = "SonarQube project key (e.g., 'com.example:my-service')"),
            "file" to ParameterProperty(type = "string", description = "Optional: filter by relative file path (e.g., 'src/main/java/com/example/MyService.java')")
        ),
        required = listOf("project_key")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ANALYZER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val projectKey = params["project_key"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'project_key' parameter required", "Error: missing project_key", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val file = params["file"]?.jsonPrimitive?.content

        val (baseUrl, token, client) = IntegrationToolSupport.resolveCredentials(urlProvider, tokenProvider, "SonarQube")
            ?: return if (urlProvider()?.trimEnd('/')?.isBlank() != false) {
                IntegrationToolSupport.credentialError("SonarQube", "URL")
            } else {
                IntegrationToolSupport.credentialError("SonarQube", "token")
            }

        return withContext(Dispatchers.IO) {
            try {
                val urlBuilder = "$baseUrl/api/issues/search".toHttpUrl().newBuilder()
                    .addQueryParameter("componentKeys", projectKey)
                    .addQueryParameter("resolved", "false")
                if (file != null) {
                    urlBuilder.addQueryParameter("files", file)
                }
                val request = Request.Builder()
                    .url(urlBuilder.build())
                    .get()
                    .header("Accept", "application/json")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext ToolResult(
                            content = "Error: SonarQube returned HTTP ${response.code} for project $projectKey",
                            summary = "Error: HTTP ${response.code}",
                            tokenEstimate = 10,
                            isError = true
                        )
                    }
                    val body = response.body?.string() ?: ""
                    val sanitized = InputSanitizer.sanitizeExternalData(body, "sonar", projectKey)
                    ToolResult(
                        content = sanitized,
                        summary = "Retrieved SonarQube issues for $projectKey${if (file != null) " (file: $file)" else ""}",
                        tokenEstimate = TokenEstimator.estimate(sanitized)
                    )
                }
            } catch (e: Exception) {
                ToolResult(
                    content = "Error: Failed to fetch SonarQube issues for $projectKey: ${e.message}",
                    summary = "Error: ${e.message}",
                    tokenEstimate = 10,
                    isError = true
                )
            }
        }
    }

}
