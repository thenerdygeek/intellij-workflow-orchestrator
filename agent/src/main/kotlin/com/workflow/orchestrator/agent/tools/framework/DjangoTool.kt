package com.workflow.orchestrator.agent.tools.framework

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.framework.django.executeAdmin
import com.workflow.orchestrator.agent.tools.framework.django.executeCeleryTasks
import com.workflow.orchestrator.agent.tools.framework.django.executeFixtures
import com.workflow.orchestrator.agent.tools.framework.django.executeForms
import com.workflow.orchestrator.agent.tools.framework.django.executeManagementCommands
import com.workflow.orchestrator.agent.tools.framework.django.executeMiddleware
import com.workflow.orchestrator.agent.tools.framework.django.executeModels
import com.workflow.orchestrator.agent.tools.framework.django.executeSerializers
import com.workflow.orchestrator.agent.tools.framework.django.executeSettings
import com.workflow.orchestrator.agent.tools.framework.django.executeSignals
import com.workflow.orchestrator.agent.tools.framework.django.executeTemplates
import com.workflow.orchestrator.agent.tools.framework.django.executeUrls
import com.workflow.orchestrator.agent.tools.framework.django.executeVersionInfo
import com.workflow.orchestrator.agent.tools.framework.django.executeViews
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.coroutineContext

/**
 * Consolidated Django meta-tool replacing 13 individual Django analysis tools.
 *
 * Saves token budget per API call by collapsing all Django-related operations into
 * a single tool definition with an `action` discriminator parameter.
 *
 * Actions: models, views, urls, settings, admin, management_commands, celery_tasks,
 *          middleware, signals, serializers, forms, fixtures, templates, version_info
 *
 * Each action is implemented in its own file under
 * `com.workflow.orchestrator.agent.tools.framework.django`. This class is a thin
 * dispatcher that exposes the tool surface (name, description, parameter schema)
 * and routes the `action` parameter to the corresponding handler.
 *
 * Only registered when Django is detected in the project (manage.py + django dependency).
 */
class DjangoTool : AgentTool {

    override val name = "django"

    override val description = """
Django framework intelligence — models, views, URLs, settings, admin, management commands.

Actions and their parameters:
- models(filter?) → Django model classes with fields
- views(filter?) → Function-based and class-based views, viewsets
- urls(filter?) → URL patterns from urls.py files
- settings(filter?) → Django settings values
- admin(filter?) → Admin site registrations
- management_commands(filter?) → Custom management commands
- celery_tasks(filter?) → Celery task definitions
- middleware() → MIDDLEWARE stack from settings
- signals(filter?) → Signal handler registrations
- serializers(filter?) → DRF serializer classes
- forms(filter?) → Form and ModelForm classes
- fixtures(filter?) → Fixture files in fixtures/ directories
- templates(filter?) → Template files in templates/ directories
- version_info() → Django version and installed apps
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf(
                    "models", "views", "urls", "settings", "admin",
                    "management_commands", "celery_tasks", "middleware",
                    "signals", "serializers", "forms", "fixtures",
                    "templates", "version_info"
                )
            ),
            "filter" to ParameterProperty(
                type = "string",
                description = "Filter results by name/pattern — for models, views, urls, settings, admin, management_commands, celery_tasks, signals, serializers, forms, fixtures, templates"
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
            "models" -> executeModels(params, project)
            "views" -> executeViews(params, project)
            "urls" -> executeUrls(params, project)
            "settings" -> executeSettings(params, project)
            "admin" -> executeAdmin(params, project)
            "management_commands" -> executeManagementCommands(params, project)
            "celery_tasks" -> executeCeleryTasks(params, project)
            "middleware" -> executeMiddleware(params, project)
            "signals" -> executeSignals(params, project)
            "serializers" -> executeSerializers(params, project)
            "forms" -> executeForms(params, project)
            "fixtures" -> executeFixtures(params, project)
            "templates" -> executeTemplates(params, project)
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
