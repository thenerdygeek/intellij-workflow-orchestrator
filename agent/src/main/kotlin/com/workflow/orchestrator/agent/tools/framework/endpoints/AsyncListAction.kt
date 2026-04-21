package com.workflow.orchestrator.agent.tools.framework.endpoints

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.psi.PsiToolUtils
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * `endpoints(action=list_async, filter?)` — lists async messaging
 * destinations (Kafka topics, RabbitMQ queues, JMS destinations)
 * discovered via com.intellij.microservices.jvm.mq.MQResolverManager.
 *
 * Self-gates when the MQ SPI or its framework-specific resolvers
 * (spring-messaging etc.) are absent — returns a friendly no-results
 * message rather than erroring.
 */
internal suspend fun executeListAsync(params: JsonObject, project: Project): ToolResult {
    if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

    val filter = params["filter"]?.jsonPrimitive?.contentOrNull

    val endpoints = ReadAction.nonBlocking<List<DiscoveredAsyncEndpoint>> {
        AsyncEndpointsDiscoverer.discover(project)
    }.inSmartMode(project).executeSynchronously()

    val filtered = if (filter.isNullOrBlank()) endpoints else endpoints.filter {
        it.destinationName.contains(filter, ignoreCase = true) ||
            it.mqType.contains(filter, ignoreCase = true) ||
            (it.handlerClass?.contains(filter, ignoreCase = true) == true)
    }

    if (filtered.isEmpty()) {
        return ToolResult(
            content = "No async messaging endpoints found${if (filter != null) " matching '$filter'" else ""}. " +
                "Requires spring-messaging / Spring Kafka / Spring RabbitMQ / Spring JMS plugins and a module " +
                "using @KafkaListener, @RabbitListener, @JmsListener, etc.",
            summary = "No async endpoints",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
        )
    }

    val content = buildString {
        appendLine("Async messaging endpoints (${filtered.size}):")
        appendLine()
        for (endpoint in filtered.sortedWith(compareBy({ it.mqType }, { it.destinationName }))) {
            val handler = listOfNotNull(
                endpoint.handlerClass?.substringAfterLast('.'),
                endpoint.handlerMethod,
            ).joinToString(".")
            val location = if (endpoint.filePath != null && endpoint.lineNumber != null) {
                " (${endpoint.filePath.substringAfterLast('/')}:${endpoint.lineNumber})"
            } else {
                ""
            }
            appendLine("${endpoint.mqType}  ${endpoint.accessType}  ${endpoint.destinationName}")
            if (handler.isNotEmpty()) appendLine("  handler: $handler$location")
        }
    }

    return ToolResult(
        content = content,
        summary = "Listed ${filtered.size} async endpoint(s)",
        tokenEstimate = TokenEstimator.estimate(content),
    )
}
