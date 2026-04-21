package com.workflow.orchestrator.agent.tools.framework.endpoints

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.psi.PsiToolUtils
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.serialization.json.JsonObject

/**
 * `endpoints(action=service_graph)` — emits a Mermaid directed graph
 * of discovered services. Services are identified by module name
 * (first approximation); edges between services are left as a TODO
 * for a follow-up iteration that parses handler bodies for
 * RestTemplate / WebClient / FeignClient calls.
 *
 * Renders via render_artifact compatibility (plain Mermaid code
 * fence — chat UI supports it natively).
 *
 * Node-only skeleton (A5.5): edge extraction from handler bodies
 * (RestTemplate/WebClient/Feign) is a planned follow-up.
 */
internal suspend fun executeServiceGraph(@Suppress("UNUSED_PARAMETER") params: JsonObject, project: Project): ToolResult {
    if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

    val endpoints = ReadAction.nonBlocking<List<DiscoveredEndpoint>> {
        EndpointsDiscoverer.discover(project)
    }.inSmartMode(project).executeSynchronously()

    if (endpoints.isEmpty()) {
        return ToolResult(
            content = "No endpoints discovered — cannot render service graph. " +
                "Ensure the Microservices plugin is loaded and the project has REST/gRPC handlers.",
            summary = "No endpoints for service graph",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true,
        )
    }

    val modules = ReadAction.nonBlocking<Map<String, String>> {
        ModuleManager.getInstance(project).modules.associate { module ->
            val modFileDir = module.moduleFile?.path?.substringBeforeLast('/')
            module.name to (modFileDir ?: "")
        }
    }.inSmartMode(project).executeSynchronously()

    val serviceNodes = mutableSetOf<String>()

    for (endpoint in endpoints) {
        // Identify service = module whose directory covers the endpoint's file path.
        val filePath = endpoint.filePath
        val node = when {
            filePath != null -> {
                modules.entries
                    .firstOrNull { (_, modDir) -> modDir.isNotEmpty() && filePath.startsWith(modDir) }
                    ?.key
                    ?: endpoint.handlerClass?.substringBefore('.').takeIf { !it.isNullOrEmpty() }
                    ?: "unknown"
            }
            endpoint.handlerClass != null -> {
                // Try module-name match via path segment heuristic
                modules.keys.firstOrNull { modName ->
                    endpoint.handlerClass.contains(".$modName.") ||
                        endpoint.handlerClass.startsWith("$modName.")
                } ?: endpoint.handlerClass.substringBefore('.')
            }
            else -> "unknown"
        }
        serviceNodes.add(node)
    }

    val mermaid = buildString {
        appendLine("```mermaid")
        appendLine("graph LR")
        for (node in serviceNodes.sorted()) {
            val safeName = node.replace(Regex("[^a-zA-Z0-9_]"), "_").ifEmpty { "service" }
            appendLine("  $safeName[\"$node\"]")
        }
        // TODO (follow-up): parse handler method bodies for service-to-service calls
        // (RestTemplate, WebClient, FeignClient, RestClient) and emit edges accordingly.
        appendLine("```")
    }

    val summary = "Service graph: ${serviceNodes.size} service node(s), 0 edge(s) [node-only skeleton]"
    return ToolResult(
        content = "$summary\n\n$mermaid",
        summary = summary,
        tokenEstimate = TokenEstimator.estimate(mermaid),
    )
}
