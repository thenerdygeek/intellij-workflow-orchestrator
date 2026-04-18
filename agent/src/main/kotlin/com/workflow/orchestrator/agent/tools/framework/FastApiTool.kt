package com.workflow.orchestrator.agent.tools.framework

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.framework.fastapi.executeBackgroundTasks
import com.workflow.orchestrator.agent.tools.framework.fastapi.executeConfig
import com.workflow.orchestrator.agent.tools.framework.fastapi.executeDatabase
import com.workflow.orchestrator.agent.tools.framework.fastapi.executeDependencies
import com.workflow.orchestrator.agent.tools.framework.fastapi.executeEvents
import com.workflow.orchestrator.agent.tools.framework.fastapi.executeMiddleware
import com.workflow.orchestrator.agent.tools.framework.fastapi.executeModels
import com.workflow.orchestrator.agent.tools.framework.fastapi.executeRoutes
import com.workflow.orchestrator.agent.tools.framework.fastapi.executeSecurity
import com.workflow.orchestrator.agent.tools.framework.fastapi.executeVersionInfo
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.coroutineContext

/**
 * Consolidated FastAPI meta-tool replacing individual FastAPI analysis tools.
 *
 * Saves token budget per API call by collapsing all FastAPI-related operations into
 * a single tool definition with an `action` discriminator parameter.
 *
 * Actions: routes, dependencies, models, middleware, security, background_tasks,
 *          events, config, database, version_info
 *
 * Each action is implemented in its own file under
 * `com.workflow.orchestrator.agent.tools.framework.fastapi`. This class is a thin
 * dispatcher that exposes the tool surface (name, description, parameter schema)
 * and routes the `action` parameter to the corresponding handler.
 *
 * Only registered when FastAPI is detected in the project (fastapi dependency).
 */
class FastApiTool : AgentTool {

    override val name = "fastapi"

    override val description = """
FastAPI framework intelligence — routes, dependencies, Pydantic models, middleware, security.

Actions and their parameters:
- routes(path?) -> API route decorators (@app.get, @router.post, etc.)
- dependencies(class_name?) -> Depends() chains in function signatures
- models(model?) -> Pydantic BaseModel subclasses with fields
- middleware() -> app.add_middleware() registrations
- security() -> OAuth2, API key, HTTP bearer security schemes
- background_tasks() -> Functions using BackgroundTasks parameter
- events() -> Startup/shutdown event handlers and lifespan
- config(class_name?) -> BaseSettings configuration classes
- database(model?) -> SQLAlchemy/Tortoise ORM model classes
- version_info() -> FastAPI, uvicorn, pydantic versions from deps
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf(
                    "routes", "dependencies", "models", "middleware", "security",
                    "background_tasks", "events", "config", "database", "version_info"
                )
            ),
            "path" to ParameterProperty(
                type = "string",
                description = "Filter routes by URL path pattern"
            ),
            "model" to ParameterProperty(
                type = "string",
                description = "Filter by Pydantic model name or database model name"
            ),
            "class_name" to ParameterProperty(
                type = "string",
                description = "Filter by dependency or config class name"
            )
        ),
        required = listOf("action")
    )

    override val allowedWorkers = setOf(
        WorkerType.TOOLER, WorkerType.ANALYZER, WorkerType.REVIEWER,
        WorkerType.ORCHESTRATOR, WorkerType.CODER
    )

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        coroutineContext.ensureActive()
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Error: 'action' parameter required",
                "Error: missing action",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        return when (action) {
            "routes" -> executeRoutes(params, project)
            "dependencies" -> executeDependencies(params, project)
            "models" -> executeModels(params, project)
            "middleware" -> executeMiddleware(params, project)
            "security" -> executeSecurity(params, project)
            "background_tasks" -> executeBackgroundTasks(params, project)
            "events" -> executeEvents(params, project)
            "config" -> executeConfig(params, project)
            "database" -> executeDatabase(params, project)
            "version_info" -> executeVersionInfo(params, project)
            else -> ToolResult(
                content = "Unknown action '$action'. See tool description for valid actions.",
                summary = "Unknown action '$action'",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }
}
