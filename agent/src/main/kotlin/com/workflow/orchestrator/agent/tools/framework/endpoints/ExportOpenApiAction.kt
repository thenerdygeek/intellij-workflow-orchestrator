@file:Suppress("DEPRECATION", "UnstableApiUsage")

package com.workflow.orchestrator.agent.tools.framework.endpoints

import com.intellij.microservices.oas.getSpecificationByUrls
import com.intellij.microservices.oas.serialization.generateOasDraft
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.psi.PsiToolUtils
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * `endpoints.export_openapi` — emit an OpenAPI 3 specification for every
 * HTTP-Server endpoint the microservices framework discovered.
 *
 * Uses the platform's `getSpecificationByUrls` to synthesize the spec DTO
 * from UrlTargetInfos, then `generateOasDraft` (requires the bundled Swagger
 * plugin) to serialize to YAML text. If Swagger is disabled, returns a
 * diagnostic message directing the user to enable it.
 */
internal suspend fun executeExportOpenApi(params: JsonObject, project: Project): ToolResult {
    val frameworkFilter = params["framework"]?.jsonPrimitive?.contentOrNull

    if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

    val content = ReadAction.nonBlocking<String> {
        val rows = EndpointsDiscoverer.discover(project)
            .filter { it.endpointType.equals("HTTP-Server", ignoreCase = true) }
            .filter { frameworkFilter == null || it.framework.contains(frameworkFilter, ignoreCase = true) }

        val targets = rows.mapNotNull { it.urlTargetInfo }
        if (targets.isEmpty()) {
            val qualifier = frameworkFilter?.let { " for framework '$it'" }.orEmpty()
            "No HTTP-Server endpoints found$qualifier. Nothing to export."
        } else {
            val spec = getSpecificationByUrls(targets)
            val yaml = generateOasDraft(project.name, spec)
            if (yaml.isBlank()) {
                "OpenAPI export returned empty output. This typically means the bundled " +
                    "'OpenAPI Specifications' (Swagger) plugin is disabled. Enable it under " +
                    "Settings → Plugins and retry. Discovered ${targets.size} HTTP-Server endpoint(s)."
            } else {
                yaml
            }
        }
    }.inSmartMode(project).executeSynchronously()

    return ToolResult(
        content = content,
        summary = "OpenAPI export (${content.lines().size} lines)",
        tokenEstimate = TokenEstimator.estimate(content),
    )
}
