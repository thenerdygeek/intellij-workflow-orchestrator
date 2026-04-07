package com.workflow.orchestrator.agent.tools.framework

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.framework.build.executeGradleDependencies
import com.workflow.orchestrator.agent.tools.framework.build.executeGradleProperties
import com.workflow.orchestrator.agent.tools.framework.build.executeGradleTasks
import com.workflow.orchestrator.agent.tools.framework.build.executeMavenDependencies
import com.workflow.orchestrator.agent.tools.framework.build.executeMavenDependencyTree
import com.workflow.orchestrator.agent.tools.framework.build.executeMavenEffectivePom
import com.workflow.orchestrator.agent.tools.framework.build.executeMavenPlugins
import com.workflow.orchestrator.agent.tools.framework.build.executeMavenProfiles
import com.workflow.orchestrator.agent.tools.framework.build.executeMavenProperties
import com.workflow.orchestrator.agent.tools.framework.build.executeModuleDependencyGraph
import com.workflow.orchestrator.agent.tools.framework.build.executeProjectModules
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.coroutineContext

/**
 * Consolidated build system meta-tool replacing 11 individual Maven/Gradle/module tools.
 *
 * Saves token budget per API call by collapsing all build system operations into
 * a single tool definition with an `action` discriminator parameter.
 *
 * Actions: maven_dependencies, maven_properties, maven_plugins, maven_profiles,
 *          maven_dependency_tree, maven_effective_pom,
 *          gradle_dependencies, gradle_tasks, gradle_properties,
 *          project_modules, module_dependency_graph
 *
 * Each action is implemented in its own file under the `build/` subpackage.
 */
class BuildTool : AgentTool {

    override val name = "build"

    override val description = """
Build system intelligence — Maven and Gradle dependencies, plugins, properties, modules.

Actions and their parameters:
- maven_dependencies(module?, scope?, search?) → Maven dependencies (scope: compile|test|runtime|provided)
- maven_properties(module?, search?) → POM properties
- maven_plugins(module?) → Build plugins
- maven_profiles(module?) → Build profiles
- maven_dependency_tree(module?, artifact?) → Transitive dependency tree (artifact to filter paths)
- maven_effective_pom(module?, plugin?) → Effective POM (plugin to filter by artifactId)
- gradle_dependencies(module?, configuration?, search?) → Gradle deps (configuration: implementation|api|testImplementation|...)
- gradle_tasks(module?, search?) → Gradle tasks
- gradle_properties(module?, search?) → Gradle properties
- project_modules() → List all IntelliJ modules
- module_dependency_graph(module?, transitive?, include_libraries?, detect_cycles?) → Module dependency graph
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf(
                    "maven_dependencies", "maven_properties", "maven_plugins", "maven_profiles",
                    "maven_dependency_tree", "maven_effective_pom",
                    "gradle_dependencies", "gradle_tasks", "gradle_properties",
                    "project_modules", "module_dependency_graph"
                )
            ),
            "module" to ParameterProperty(
                type = "string",
                description = "Module name to inspect. For Maven: module/artifactId name. For Gradle: ':core', 'jira', etc. If omitted, uses root/all modules depending on action."
            ),
            "scope" to ParameterProperty(
                type = "string",
                description = "Filter by dependency scope (compile, test, runtime, provided) — for maven_dependencies"
            ),
            "search" to ParameterProperty(
                type = "string",
                description = "Filter by name/value substring — for maven_dependencies, maven_properties, gradle_dependencies, gradle_tasks, gradle_properties"
            ),
            "artifact" to ParameterProperty(
                type = "string",
                description = "Filter dependency tree to paths containing this artifact — for maven_dependency_tree"
            ),
            "plugin" to ParameterProperty(
                type = "string",
                description = "Filter by plugin artifactId — for maven_effective_pom"
            ),
            "configuration" to ParameterProperty(
                type = "string",
                description = "Filter by Gradle configuration (implementation, api, testImplementation, etc.) — for gradle_dependencies"
            ),
            "transitive" to ParameterProperty(
                type = "boolean",
                description = "Include transitive (indirect) dependencies — for module_dependency_graph. Default: false."
            ),
            "include_libraries" to ParameterProperty(
                type = "boolean",
                description = "Include library dependencies in output — for module_dependency_graph. Default: false."
            ),
            "detect_cycles" to ParameterProperty(
                type = "boolean",
                description = "Run circular dependency detection — for module_dependency_graph. Default: true."
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
            "maven_dependencies" -> executeMavenDependencies(params, project)
            "maven_properties" -> executeMavenProperties(params, project)
            "maven_plugins" -> executeMavenPlugins(params, project)
            "maven_profiles" -> executeMavenProfiles(params, project)
            "maven_dependency_tree" -> executeMavenDependencyTree(params, project)
            "maven_effective_pom" -> executeMavenEffectivePom(params, project)
            "gradle_dependencies" -> executeGradleDependencies(params, project)
            "gradle_tasks" -> executeGradleTasks(params, project)
            "gradle_properties" -> executeGradleProperties(params, project)
            "project_modules" -> executeProjectModules(params, project)
            "module_dependency_graph" -> executeModuleDependencyGraph(params, project)
            else -> ToolResult(
                content = "Unknown action '$action'. See tool description for valid actions.",
                summary = "Unknown action '$action'",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }
}
