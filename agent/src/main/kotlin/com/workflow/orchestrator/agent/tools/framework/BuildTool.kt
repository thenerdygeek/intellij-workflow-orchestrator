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
import com.workflow.orchestrator.agent.tools.framework.build.executePipDependencies
import com.workflow.orchestrator.agent.tools.framework.build.executePipList
import com.workflow.orchestrator.agent.tools.framework.build.executePipOutdated
import com.workflow.orchestrator.agent.tools.framework.build.executePipShow
import com.workflow.orchestrator.agent.tools.framework.build.executePoetryLockStatus
import com.workflow.orchestrator.agent.tools.framework.build.executePoetryList
import com.workflow.orchestrator.agent.tools.framework.build.executePoetryOutdated
import com.workflow.orchestrator.agent.tools.framework.build.executePoetryScripts
import com.workflow.orchestrator.agent.tools.framework.build.executePoetryShow
import com.workflow.orchestrator.agent.tools.framework.build.executeProjectModules
import com.workflow.orchestrator.agent.tools.framework.build.executePytestDiscover
import com.workflow.orchestrator.agent.tools.framework.build.executePytestFixtures
import com.workflow.orchestrator.agent.tools.framework.build.executePytestRun
import com.workflow.orchestrator.agent.tools.framework.build.executeUvList
import com.workflow.orchestrator.agent.tools.framework.build.executeUvLockStatus
import com.workflow.orchestrator.agent.tools.framework.build.executeUvOutdated
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.coroutineContext

/**
 * Consolidated build system meta-tool for Maven, Gradle, pip, Poetry, uv, and pytest.
 *
 * Saves token budget per API call by collapsing all build system operations into
 * a single tool definition with an `action` discriminator parameter.
 *
 * Actions: maven_dependencies, maven_properties, maven_plugins, maven_profiles,
 *          maven_dependency_tree, maven_effective_pom,
 *          gradle_dependencies, gradle_tasks, gradle_properties,
 *          project_modules, module_dependency_graph,
 *          pip_list, pip_outdated, pip_show, pip_dependencies,
 *          poetry_list, poetry_outdated, poetry_show, poetry_lock_status, poetry_scripts,
 *          uv_list, uv_outdated, uv_lock_status,
 *          pytest_discover, pytest_run, pytest_fixtures
 *
 * Each action is implemented in its own file under the `build/` subpackage.
 */
class BuildTool : AgentTool {

    override val name = "build"

    override val description = """
Build system intelligence — Maven, Gradle, pip, Poetry, uv, and pytest.

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
- pip_list(search?) → Installed pip packages
- pip_outdated() → Outdated pip packages with available updates
- pip_show(package) → Detailed info for a specific pip package
- pip_dependencies(search?) → Declared dependencies from requirements.txt/setup.cfg/setup.py/pyproject.toml
- poetry_list(search?) → Installed Poetry packages
- poetry_outdated() → Outdated Poetry packages
- poetry_show(package) → Detailed info for a specific Poetry package
- poetry_lock_status() → Poetry lock file status and stats
- poetry_scripts() → Scripts defined in pyproject.toml [tool.poetry.scripts]
- uv_list(search?) → Installed uv packages
- uv_outdated() → Outdated uv packages
- uv_lock_status() → uv lock file status and stats
- pytest_discover(path?) → Discover tests via pytest --collect-only
- pytest_run(path?, pattern?, markers?) → Run pytest with optional filters
- pytest_fixtures(path?) → List available pytest fixtures
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
                    "project_modules", "module_dependency_graph",
                    "pip_list", "pip_outdated", "pip_show", "pip_dependencies",
                    "poetry_list", "poetry_outdated", "poetry_show", "poetry_lock_status", "poetry_scripts",
                    "uv_list", "uv_outdated", "uv_lock_status",
                    "pytest_discover", "pytest_run", "pytest_fixtures"
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
            ),
            "package" to ParameterProperty(
                type = "string",
                description = "Package name to inspect — for pip_show, poetry_show"
            ),
            "path" to ParameterProperty(
                type = "string",
                description = "Test path to scope discovery/execution — for pytest_discover, pytest_run, pytest_fixtures"
            ),
            "pattern" to ParameterProperty(
                type = "string",
                description = "Test name pattern filter (pytest -k) — for pytest_run"
            ),
            "markers" to ParameterProperty(
                type = "string",
                description = "Marker expression filter (pytest -m) — for pytest_run"
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
            "pip_list" -> executePipList(params, project)
            "pip_outdated" -> executePipOutdated(params, project)
            "pip_show" -> executePipShow(params, project)
            "pip_dependencies" -> executePipDependencies(params, project)
            "poetry_list" -> executePoetryList(params, project)
            "poetry_outdated" -> executePoetryOutdated(params, project)
            "poetry_show" -> executePoetryShow(params, project)
            "poetry_lock_status" -> executePoetryLockStatus(params, project)
            "poetry_scripts" -> executePoetryScripts(params, project)
            "uv_list" -> executeUvList(params, project)
            "uv_outdated" -> executeUvOutdated(params, project)
            "uv_lock_status" -> executeUvLockStatus(params, project)
            "pytest_discover" -> executePytestDiscover(params, project)
            "pytest_run" -> executePytestRun(params, project)
            "pytest_fixtures" -> executePytestFixtures(params, project)
            else -> ToolResult(
                content = "Unknown action '$action'. See tool description for valid actions.",
                summary = "Unknown action '$action'",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }
}
