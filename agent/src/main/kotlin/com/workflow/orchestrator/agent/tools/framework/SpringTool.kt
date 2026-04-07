package com.workflow.orchestrator.agent.tools.framework

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.framework.spring.executeBeanGraph
import com.workflow.orchestrator.agent.tools.framework.spring.executeBootActuator
import com.workflow.orchestrator.agent.tools.framework.spring.executeBootAutoConfig
import com.workflow.orchestrator.agent.tools.framework.spring.executeBootConfigProperties
import com.workflow.orchestrator.agent.tools.framework.spring.executeBootEndpoints
import com.workflow.orchestrator.agent.tools.framework.spring.executeConfig
import com.workflow.orchestrator.agent.tools.framework.spring.executeContext
import com.workflow.orchestrator.agent.tools.framework.spring.executeEndpoints
import com.workflow.orchestrator.agent.tools.framework.spring.executeEventListeners
import com.workflow.orchestrator.agent.tools.framework.spring.executeJpaEntities
import com.workflow.orchestrator.agent.tools.framework.spring.executeProfiles
import com.workflow.orchestrator.agent.tools.framework.spring.executeRepositories
import com.workflow.orchestrator.agent.tools.framework.spring.executeScheduledTasks
import com.workflow.orchestrator.agent.tools.framework.spring.executeSecurityConfig
import com.workflow.orchestrator.agent.tools.framework.spring.executeVersionInfo
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Consolidated Spring meta-tool replacing 15 individual Spring/Spring Boot/JPA tools.
 *
 * Saves token budget per API call by collapsing all Spring-related operations into
 * a single tool definition with an `action` discriminator parameter.
 *
 * Actions: context, endpoints, bean_graph, config, version_info, profiles, repositories,
 *          security_config, scheduled_tasks, event_listeners, boot_endpoints, boot_autoconfig,
 *          boot_config_properties, boot_actuator, jpa_entities
 *
 * Each action is implemented in its own file under
 * `com.workflow.orchestrator.agent.tools.framework.spring`. This class is a thin
 * dispatcher that exposes the tool surface (name, description, parameter schema)
 * and routes the `action` parameter to the corresponding handler.
 */
class SpringTool : AgentTool {

    override val name = "spring"

    override val description = """
Spring framework intelligence — beans, endpoints, configuration, JPA, security, actuator.

Actions and their parameters:
- context(filter?) → Spring bean context
- endpoints(filter?, include_params?) → REST endpoint mappings
- bean_graph(bean_name) → Bean dependency graph
- config(property) → Configuration property value
- version_info(module) → Framework version info
- profiles() → Active Spring profiles
- repositories(filter?) → Spring Data repositories
- security_config() → Security configuration
- scheduled_tasks() → @Scheduled methods
- event_listeners() → @EventListener methods
- boot_endpoints(class_name?) → Boot endpoint mappings
- boot_autoconfig(filter?, project_only?) → Auto-configuration classes (project_only default true)
- boot_config_properties(class_name?, prefix?) → @ConfigurationProperties bindings
- boot_actuator() → Actuator endpoints
- jpa_entities(entity?) → JPA entity analysis
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf(
                    "context", "endpoints", "bean_graph", "config", "version_info",
                    "profiles", "repositories", "security_config", "scheduled_tasks",
                    "event_listeners", "boot_endpoints", "boot_autoconfig",
                    "boot_config_properties", "boot_actuator", "jpa_entities"
                )
            ),
            "filter" to ParameterProperty(
                type = "string",
                description = "Filter results by name/pattern — for context, endpoints, boot_autoconfig"
            ),
            "bean_name" to ParameterProperty(
                type = "string",
                description = "Bean class name (simple or fully qualified) — for bean_graph"
            ),
            "property" to ParameterProperty(
                type = "string",
                description = "Specific property name to look up (e.g., 'spring.datasource.url') — for config"
            ),
            "module" to ParameterProperty(
                type = "string",
                description = "Module name to inspect — for version_info"
            ),
            "include_params" to ParameterProperty(
                type = "boolean",
                description = "If true, show handler method parameters with annotations (default: false) — for endpoints"
            ),
            "class_name" to ParameterProperty(
                type = "string",
                description = "Filter by controller or config properties class name — for boot_endpoints, boot_config_properties"
            ),
            "prefix" to ParameterProperty(
                type = "string",
                description = "Filter by configuration properties prefix — for boot_config_properties"
            ),
            "project_only" to ParameterProperty(
                type = "boolean",
                description = "If true (default), only scan project-scope classes; if false, includes library classes — for boot_autoconfig"
            ),
            "entity" to ParameterProperty(
                type = "string",
                description = "Specific entity class name — for jpa_entities"
            )
        ),
        required = listOf("action")
    )

    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ANALYZER, WorkerType.REVIEWER, WorkerType.ORCHESTRATOR, WorkerType.CODER)

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
            "context" -> executeContext(params, project)
            "endpoints" -> executeEndpoints(params, project)
            "bean_graph" -> executeBeanGraph(params, project)
            "config" -> executeConfig(params, project)
            "version_info" -> executeVersionInfo(params, project)
            "profiles" -> executeProfiles(params, project)
            "repositories" -> executeRepositories(params, project)
            "security_config" -> executeSecurityConfig(params, project)
            "scheduled_tasks" -> executeScheduledTasks(params, project)
            "event_listeners" -> executeEventListeners(params, project)
            "boot_endpoints" -> executeBootEndpoints(params, project)
            "boot_autoconfig" -> executeBootAutoConfig(params, project)
            "boot_config_properties" -> executeBootConfigProperties(params, project)
            "boot_actuator" -> executeBootActuator(params, project)
            "jpa_entities" -> executeJpaEntities(params, project)
            else -> ToolResult(
                content = "Unknown action '$action'. See tool description for valid actions.",
                summary = "Unknown action '$action'",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    companion object {
        internal const val SPRING_PLUGIN_MISSING_MSG =
            "Error: Spring plugin is not available. Install 'Spring' plugin in IntelliJ Ultimate to use this tool."
    }
}
