package com.workflow.orchestrator.agent.tools.framework.endpoints

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Multi-framework endpoints meta-tool backed by IntelliJ's Microservices
 * Framework. Registered only on editions that bundle
 * `com.intellij.modules.microservices` (Ultimate / PyCharm Pro / WebStorm /
 * Rider / GoLand / RubyMine).
 *
 * Supersedes `spring.endpoints` and `spring.boot_endpoints`: covers every
 * framework that registered an `EndpointsProvider` — Spring MVC / WebFlux /
 * Actuator / Feign, JAX-RS / Jakarta RS, Micronaut (HTTP + management +
 * WebSocket), Quarkus, Helidon, Ktor, gRPC/Protobuf, OpenAPI/Swagger,
 * Retrofit, OkHttp, HTTP Client `.http` files, Django/FastAPI/Flask (PyCharm
 * Pro).
 *
 * The three actions share `EndpointsDiscoverer` as their data source.
 */
class EndpointsTool : AgentTool {

    override val name = "endpoints"

    override val description = """
Multi-framework HTTP endpoint intelligence — backed by IntelliJ's Endpoints view.

Discovers HTTP-server and HTTP-client endpoints across every framework the
IDE supports: Spring MVC/WebFlux/Actuator/Feign, JAX-RS, Micronaut, Ktor,
gRPC, OpenAPI, Retrofit, OkHttp, HTTP Client files, and more.

Actions and their parameters:
- list(filter?, framework?, endpoint_type?) → list every discovered endpoint
- find_usages(url) → find all call sites of a URL (handler + client string literals)
- export_openapi(framework?) → render discovered endpoints as an OpenAPI 3 spec
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf("list", "find_usages", "export_openapi"),
            ),
            "filter" to ParameterProperty(
                type = "string",
                description = "Free-text filter on URL, HTTP method, or handler class — for list",
            ),
            "framework" to ParameterProperty(
                type = "string",
                description = "Framework name substring (e.g. 'Spring', 'Micronaut', 'JAX-RS') — for list, export_openapi",
            ),
            "endpoint_type" to ParameterProperty(
                type = "string",
                description = "Endpoint category: HTTP-Server, HTTP-Client, WebSocket-Server, API-Definition — for list",
            ),
            "url" to ParameterProperty(
                type = "string",
                description = "Full URL path to resolve (e.g. '/api/users/{id}') — for find_usages",
            ),
            "method" to ParameterProperty(
                type = "string",
                description = "Optional HTTP method hint (GET/POST/…) to narrow resolution — for find_usages",
            ),
        ),
        required = listOf("action"),
    )

    override val allowedWorkers = setOf(
        WorkerType.TOOLER,
        WorkerType.ANALYZER,
        WorkerType.REVIEWER,
        WorkerType.ORCHESTRATOR,
        WorkerType.CODER,
    )

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        coroutineContext.ensureActive()
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult(
                content = "Error: 'action' parameter required",
                summary = "Error: missing action",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true,
            )

        return when (action) {
            "list" -> executeListEndpoints(params, project)
            "find_usages" -> executeFindUsages(params, project)
            "export_openapi" -> executeExportOpenApiStub(params, project)
            else -> ToolResult(
                content = "Unknown action '$action'. Valid actions: list, find_usages, export_openapi.",
                summary = "Unknown action '$action'",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true,
            )
        }
    }
}

// Stub — replaced by a dedicated action file in Task 13.
internal suspend fun executeExportOpenApiStub(
    @Suppress("UNUSED_PARAMETER") params: JsonObject,
    @Suppress("UNUSED_PARAMETER") project: Project,
): ToolResult {
    return ToolResult(
        content = "export_openapi not yet implemented",
        summary = "Stub",
        tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
        isError = true,
    )
}
