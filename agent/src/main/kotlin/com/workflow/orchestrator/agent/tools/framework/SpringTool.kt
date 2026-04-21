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
import com.workflow.orchestrator.agent.tools.framework.spring.executeAnnotatedMethods
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
class SpringTool(
    private val includeEndpointActions: Boolean = true,
) : AgentTool {

    override val name = "spring"

    override val description: String
        get() = buildString {
            appendLine("Spring framework intelligence — beans, endpoints, configuration, JPA, security, actuator.")
            appendLine()
            appendLine("Actions and their parameters:")
            appendLine("- context(filter?) → Spring bean context")
            if (includeEndpointActions) appendLine("- endpoints(filter?, include_params?) → REST endpoint mappings")
            appendLine("- bean_graph(bean_name) → Bean dependency graph (accepts bean name, alias, class, or @Bean method name)")
            appendLine("- config(property) → Configuration property value")
            appendLine("- version_info(module) → Framework version info")
            appendLine("- profiles() → Active Spring profiles")
            appendLine("- repositories(filter?) → Spring Data repositories")
            appendLine("- security_config() → Security configuration")
            appendLine("- scheduled_tasks() → @Scheduled methods")
            appendLine("- event_listeners() → @EventListener methods")
            appendLine("- annotated_methods(annotation, filter?) → Generic scan for any Spring annotation (@Scheduled, @EventListener, @Transactional, @Cacheable, @Async, @PreAuthorize, @Secured, custom FQN)")
            if (includeEndpointActions) appendLine("- boot_endpoints(class_name?) → Boot endpoint mappings")
            appendLine("- boot_autoconfig(filter?, project_only?) → Auto-configuration classes (project_only default true)")
            appendLine("- boot_config_properties(class_name?, prefix?) → @ConfigurationProperties bindings with IDE metadata (descriptions, defaults, deprecations, source types)")
            appendLine("- boot_actuator() → Actuator endpoints")
            append("- jpa_entities(entity?) → JPA entity metadata via Persistence plugin — inheritance, relationship cardinality/fetch/cascade, named queries")
        }

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = buildList {
                    add("context")
                    if (includeEndpointActions) add("endpoints")
                    addAll(listOf(
                        "bean_graph", "config", "version_info", "profiles", "repositories",
                        "security_config", "scheduled_tasks", "event_listeners", "annotated_methods",
                    ))
                    if (includeEndpointActions) add("boot_endpoints")
                    addAll(listOf("boot_autoconfig", "boot_config_properties", "boot_actuator", "jpa_entities"))
                },
            ),
            "filter" to ParameterProperty(
                type = "string",
                description = "Filter results by name/pattern — for context, endpoints, boot_autoconfig"
            ),
            "bean_name" to ParameterProperty(
                type = "string",
                description = "Bean identifier — accepts canonical bean name, alias, simple or fully-qualified class name, or @Bean method name. Resolved via the Spring plugin's bean model. For bean_graph"
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
            ),
            "annotation" to ParameterProperty(
                type = "string",
                description = "Annotation name — short form (@Scheduled, Transactional, etc.) or fully-qualified (e.g. org.springframework.cache.annotation.Cacheable) — for annotated_methods"
            ),
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
            "endpoints" -> if (includeEndpointActions) executeEndpoints(params, project) else unsupportedAction(action)
            "bean_graph" -> executeBeanGraph(params, project)
            "config" -> executeConfig(params, project)
            "version_info" -> executeVersionInfo(params, project)
            "profiles" -> executeProfiles(params, project)
            "repositories" -> executeRepositories(params, project)
            "security_config" -> executeSecurityConfig(params, project)
            "scheduled_tasks" -> executeScheduledTasks(params, project)
            "event_listeners" -> executeEventListeners(params, project)
            "annotated_methods" -> executeAnnotatedMethods(params, project)
            "boot_endpoints" -> if (includeEndpointActions) executeBootEndpoints(params, project) else unsupportedAction(action)
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

    private fun unsupportedAction(action: String): ToolResult = ToolResult(
        content = "Action '$action' is served by the `endpoints` meta-tool in this IDE. " +
            "Use `endpoints(action=list)` or `endpoints(action=list, framework=\"Spring\")`.",
        summary = "Use endpoints tool",
        tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
        isError = true,
    )

    companion object {
        internal const val SPRING_PLUGIN_MISSING_MSG =
            "Error: Spring plugin is not available. Install 'Spring' plugin in IntelliJ Ultimate to use this tool."
    }
}
