package com.workflow.orchestrator.agent.tools.framework

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.framework.flask.executeBlueprints
import com.workflow.orchestrator.agent.tools.framework.flask.executeCliCommands
import com.workflow.orchestrator.agent.tools.framework.flask.executeConfig
import com.workflow.orchestrator.agent.tools.framework.flask.executeExtensions
import com.workflow.orchestrator.agent.tools.framework.flask.executeForms
import com.workflow.orchestrator.agent.tools.framework.flask.executeMiddleware
import com.workflow.orchestrator.agent.tools.framework.flask.executeModels
import com.workflow.orchestrator.agent.tools.framework.flask.executeRoutes
import com.workflow.orchestrator.agent.tools.framework.flask.executeTemplates
import com.workflow.orchestrator.agent.tools.framework.flask.executeVersionInfo
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.coroutineContext

/**
 * Consolidated Flask meta-tool replacing individual Flask analysis tools.
 *
 * Saves token budget per API call by collapsing all Flask-related operations into
 * a single tool definition with an `action` discriminator parameter.
 *
 * Actions: routes, blueprints, config, extensions, models, templates, middleware,
 *          cli_commands, forms, version_info
 *
 * Each action is implemented in its own file under
 * `com.workflow.orchestrator.agent.tools.framework.flask`. This class is a thin
 * dispatcher that exposes the tool surface (name, description, parameter schema)
 * and routes the `action` parameter to the corresponding handler.
 *
 * Only registered when Flask is detected in the project (flask dependency in requirements).
 */
class FlaskTool : AgentTool {

    override val name = "flask"

    override val description = """
Flask framework intelligence — routes, blueprints, config, extensions, SQLAlchemy models, templates.

Actions and their parameters:
- routes(blueprint?) → @app.route / @blueprint.route decorators with methods and handlers
- blueprints(blueprint?) → Blueprint() definitions and register_blueprint() calls
- config() → Class-based and key-value config from config.py / settings.py
- extensions(extension?) → Flask extensions (SQLAlchemy, Migrate, etc.) and init_app() calls
- models(model?) → Flask-SQLAlchemy db.Model classes with Column definitions
- templates() → Jinja2 template files in templates/ directories
- middleware() → @app.before_request, @app.after_request, @app.errorhandler hooks
- cli_commands() → @app.cli.command() and @click.command() CLI commands
- forms() → Flask-WTF FlaskForm/Form classes with field definitions
- version_info() → Flask, Werkzeug, Jinja2, SQLAlchemy versions from deps
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf(
                    "routes", "blueprints", "config", "extensions", "models",
                    "templates", "middleware", "cli_commands", "forms", "version_info"
                )
            ),
            "blueprint" to ParameterProperty(
                type = "string",
                description = "Filter by blueprint name — for routes, blueprints"
            ),
            "extension" to ParameterProperty(
                type = "string",
                description = "Filter by extension name — for extensions"
            ),
            "model" to ParameterProperty(
                type = "string",
                description = "Filter by SQLAlchemy model name — for models"
            ),
            "filter" to ParameterProperty(
                type = "string",
                description = "General filter by name/pattern — for any action"
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
            "blueprints" -> executeBlueprints(params, project)
            "config" -> executeConfig(params, project)
            "extensions" -> executeExtensions(params, project)
            "models" -> executeModels(params, project)
            "templates" -> executeTemplates(params, project)
            "middleware" -> executeMiddleware(params, project)
            "cli_commands" -> executeCliCommands(params, project)
            "forms" -> executeForms(params, project)
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
