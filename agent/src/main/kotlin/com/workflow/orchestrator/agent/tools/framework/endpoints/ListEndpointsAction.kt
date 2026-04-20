package com.workflow.orchestrator.agent.tools.framework.endpoints

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.psi.PsiToolUtils
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal suspend fun executeListEndpoints(params: JsonObject, project: Project): ToolResult {
    if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

    val filter = params["filter"]?.jsonPrimitive?.contentOrNull
    val framework = params["framework"]?.jsonPrimitive?.contentOrNull
    val endpointType = params["endpoint_type"]?.jsonPrimitive?.contentOrNull

    val content = ReadAction.nonBlocking<String> {
        val all = EndpointsDiscoverer.discover(project)
        val filtered = all.applyFilters(filter, framework, endpointType)
        renderEndpointsList(filtered, all.size)
    }.inSmartMode(project).executeSynchronously()

    return ToolResult(
        content = content,
        summary = "Listed endpoints (${content.lines().size} lines)",
        tokenEstimate = TokenEstimator.estimate(content),
    )
}

private fun List<DiscoveredEndpoint>.applyFilters(
    filter: String?,
    framework: String?,
    endpointType: String?,
): List<DiscoveredEndpoint> {
    var list = this
    if (!framework.isNullOrBlank()) {
        list = list.filter { it.framework.contains(framework, ignoreCase = true) }
    }
    if (!endpointType.isNullOrBlank()) {
        list = list.filter { it.endpointType.equals(endpointType, ignoreCase = true) }
    }
    if (!filter.isNullOrBlank()) {
        list = list.filter {
            it.url.contains(filter, ignoreCase = true) ||
                it.httpMethods.any { m -> m.contains(filter, ignoreCase = true) } ||
                (it.handlerClass?.contains(filter, ignoreCase = true) == true)
        }
    }
    return list
}

private fun renderEndpointsList(rows: List<DiscoveredEndpoint>, totalBeforeFilter: Int): String {
    if (rows.isEmpty()) {
        if (totalBeforeFilter == 0) {
            return "No endpoints discovered. (Microservices framework is available but no registered provider returned any endpoints for this project.)"
        }
        return "No endpoints matching the given filters. Removed ${totalBeforeFilter - rows.size} rows; 0 remain."
    }

    val sorted = rows.sortedWith(compareBy({ it.url }, { it.httpMethods.firstOrNull().orEmpty() }))
    val sb = StringBuilder()
    sb.appendLine("Endpoints (${sorted.size}):")
    sb.appendLine()

    val displayed = sorted.take(100)
    for (ep in displayed) {
        val method = ep.httpMethods.joinToString(",").ifBlank { "ANY" }.padEnd(8)
        sb.appendLine("$method${ep.url}    [${ep.framework}] (${ep.endpointType})")
        if (!ep.handlerClass.isNullOrBlank() || !ep.handlerMethod.isNullOrBlank()) {
            sb.appendLine("  Handler: ${ep.handlerClass ?: "?"}.${ep.handlerMethod ?: "?"}()")
        }
        if (!ep.filePath.isNullOrBlank()) {
            val line = ep.lineNumber?.let { ":$it" } ?: ""
            sb.appendLine("  File:    ${ep.filePath}$line")
        }
        sb.appendLine()
    }
    if (sorted.size > 100) {
        sb.appendLine("... (${sorted.size - 100} more endpoints not shown — narrow with filter/framework/endpoint_type)")
    }
    return sb.toString().trimEnd()
}
