package com.workflow.orchestrator.agent.tools.framework

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Properties

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

    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ANALYZER, WorkerType.ORCHESTRATOR, WorkerType.CODER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
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

    // ==================== Maven Dependencies ====================

    private fun executeMavenDependencies(params: JsonObject, project: Project): ToolResult {
        return try {
            val moduleFilter = params["module"]?.jsonPrimitive?.content
            val scopeFilter = params["scope"]?.jsonPrimitive?.content?.lowercase()
            val searchFilter = params["search"]?.jsonPrimitive?.content?.lowercase()

            val manager = MavenUtils.getMavenManager(project)
                ?: return MavenUtils.noMavenError()

            val mavenProjects = MavenUtils.getMavenProjects(manager)
            if (mavenProjects.isEmpty()) {
                return ToolResult("No Maven projects found.", "No Maven projects", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
            }

            val targetProject = MavenUtils.findMavenProject(mavenProjects, manager, moduleFilter)
                ?: return ToolResult(
                    "Module '${moduleFilter}' not found. Available: ${MavenUtils.getProjectNames(mavenProjects)}",
                    "Module not found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                )

            val dependencies = MavenUtils.getDependencies(targetProject)

            val filtered = dependencies.filter { dep ->
                val matchesScope = scopeFilter == null || dep.scope.lowercase() == scopeFilter
                val matchesSearch = searchFilter == null ||
                    dep.groupId.lowercase().contains(searchFilter) ||
                    dep.artifactId.lowercase().contains(searchFilter)
                matchesScope && matchesSearch
            }

            if (filtered.isEmpty()) {
                val filterDesc = buildString {
                    if (scopeFilter != null) append(" scope=$scopeFilter")
                    if (searchFilter != null) append(" search=$searchFilter")
                }
                return ToolResult("No dependencies found matching:$filterDesc", "No matches", 5)
            }

            val grouped = filtered.groupBy { it.scope.ifBlank { "compile" } }
                .toSortedMap(compareBy { mavenScopeOrder(it) })

            val content = buildString {
                val projectName = MavenUtils.getDisplayName(targetProject)
                appendLine("Dependencies for $projectName (${filtered.size} total):")
                appendLine()
                for ((scope, deps) in grouped) {
                    appendLine("$scope (${deps.size}):")
                    for (dep in deps.sortedBy { "${it.groupId}:${it.artifactId}" }) {
                        val version = if (dep.version.isNotBlank()) ":${dep.version}" else ""
                        appendLine("  ${dep.groupId}:${dep.artifactId}$version")
                    }
                    appendLine()
                }
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "${filtered.size} dependencies",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        } catch (e: Exception) {
            ToolResult("Error listing dependencies: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    private fun mavenScopeOrder(scope: String): Int = when (scope.lowercase()) {
        "compile" -> 0; "provided" -> 1; "runtime" -> 2; "test" -> 3; "system" -> 4; else -> 5
    }

    // ==================== Maven Properties ====================

    private fun executeMavenProperties(params: JsonObject, project: Project): ToolResult {
        return try {
            val moduleFilter = params["module"]?.jsonPrimitive?.content
            val searchFilter = params["search"]?.jsonPrimitive?.content?.lowercase()

            val manager = MavenUtils.getMavenManager(project)
                ?: return MavenUtils.noMavenError()

            val mavenProjects = MavenUtils.getMavenProjects(manager)
            if (mavenProjects.isEmpty()) {
                return ToolResult("No Maven projects found.", "No Maven projects", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
            }

            val targetProject = MavenUtils.findMavenProject(mavenProjects, manager, moduleFilter)
                ?: return ToolResult(
                    "Module '${moduleFilter}' not found. Available: ${MavenUtils.getProjectNames(mavenProjects)}",
                    "Module not found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                )

            val properties = MavenUtils.getProperties(targetProject)

            val filtered = if (searchFilter != null) {
                properties.filter { (key, _) -> key.lowercase().contains(searchFilter) }
            } else {
                properties
            }

            if (filtered.isEmpty()) {
                return ToolResult(
                    if (searchFilter != null) "No properties matching '$searchFilter'." else "No properties found.",
                    "No properties", 5
                )
            }

            val content = buildString {
                val projectName = MavenUtils.getDisplayName(targetProject)
                appendLine("Maven properties for $projectName (${filtered.size}):")
                appendLine()
                for ((key, value) in filtered.toSortedMap()) {
                    appendLine("$key = $value")
                }
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "${filtered.size} properties",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        } catch (e: Exception) {
            ToolResult("Error reading properties: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ==================== Maven Plugins ====================

    private data class PluginInfo(
        val groupId: String,
        val artifactId: String,
        val version: String
    )

    private fun executeMavenPlugins(params: JsonObject, project: Project): ToolResult {
        return try {
            val moduleFilter = params["module"]?.jsonPrimitive?.content

            val manager = MavenUtils.getMavenManager(project)
                ?: return MavenUtils.noMavenError()

            val mavenProjects = MavenUtils.getMavenProjects(manager)
            if (mavenProjects.isEmpty()) {
                return ToolResult("No Maven projects found.", "No Maven projects", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
            }

            val targetProject = MavenUtils.findMavenProject(mavenProjects, manager, moduleFilter)
                ?: return ToolResult(
                    "Module '${moduleFilter}' not found. Available: ${MavenUtils.getProjectNames(mavenProjects)}",
                    "Module not found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                )

            val plugins = getPlugins(targetProject)

            if (plugins.isEmpty()) {
                return ToolResult("No build plugins declared.", "No plugins", 5)
            }

            val content = buildString {
                val projectName = MavenUtils.getDisplayName(targetProject)
                appendLine("Build Plugins for $projectName (${plugins.size}):")
                appendLine()
                for (plugin in plugins.sortedBy { "${it.groupId}:${it.artifactId}" }) {
                    val version = if (plugin.version.isNotBlank()) ":${plugin.version}" else ""
                    appendLine("  ${plugin.groupId}:${plugin.artifactId}$version")
                }
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "${plugins.size} plugins",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        } catch (e: Exception) {
            ToolResult("Error listing plugins: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    private fun getPlugins(mavenProject: Any): List<PluginInfo> {
        return try {
            @Suppress("UNCHECKED_CAST")
            val plugins = mavenProject.javaClass.getMethod("getDeclaredPlugins").invoke(mavenProject) as List<Any>
            plugins.mapNotNull { plugin ->
                try {
                    val groupId = plugin.javaClass.getMethod("getGroupId").invoke(plugin) as? String ?: return@mapNotNull null
                    val artifactId = plugin.javaClass.getMethod("getArtifactId").invoke(plugin) as? String ?: return@mapNotNull null
                    val version = plugin.javaClass.getMethod("getVersion").invoke(plugin) as? String ?: ""
                    PluginInfo(groupId, artifactId, version)
                } catch (_: Exception) { null }
            }
        } catch (_: Exception) { emptyList() }
    }

    // ==================== Maven Profiles ====================

    private fun executeMavenProfiles(params: JsonObject, project: Project): ToolResult {
        return try {
            val manager = MavenUtils.getMavenManager(project)
                ?: return MavenUtils.noMavenError()

            val mavenProjects = MavenUtils.getMavenProjects(manager)
            if (mavenProjects.isEmpty()) {
                return ToolResult("No Maven projects found.", "No Maven projects", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
            }

            val activeProfiles = getExplicitProfiles(manager, enabled = true)
            val disabledProfiles = getExplicitProfiles(manager, enabled = false)

            val availableProfiles = mutableSetOf<String>()
            for (mavenProject in mavenProjects) {
                availableProfiles.addAll(getProfilesFromModel(mavenProject))
            }

            val content = buildString {
                appendLine("Maven Profiles:")
                appendLine()

                if (activeProfiles.isNotEmpty()) {
                    appendLine("Active profiles: ${activeProfiles.joinToString(", ")}")
                } else {
                    appendLine("Active profiles: (none explicitly activated)")
                }

                if (disabledProfiles.isNotEmpty()) {
                    appendLine("Disabled profiles: ${disabledProfiles.joinToString(", ")}")
                }

                if (availableProfiles.isNotEmpty()) {
                    appendLine("Available profiles (from POMs): ${availableProfiles.sorted().joinToString(", ")}")
                } else {
                    appendLine("Available profiles: (none defined in POMs)")
                }
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "${availableProfiles.size} profiles available",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        } catch (e: Exception) {
            ToolResult("Error reading profiles: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    private fun getExplicitProfiles(manager: Any, enabled: Boolean): List<String> {
        return try {
            val explicitProfiles = manager.javaClass.getMethod("getExplicitProfiles").invoke(manager)
            val methodName = if (enabled) "getEnabledProfiles" else "getDisabledProfiles"
            @Suppress("UNCHECKED_CAST")
            val profiles = explicitProfiles.javaClass.getMethod(methodName).invoke(explicitProfiles) as Collection<String>
            profiles.toList().sorted()
        } catch (_: Exception) { emptyList() }
    }

    private fun getProfilesFromModel(mavenProject: Any): List<String> {
        return try {
            val model = try {
                mavenProject.javaClass.getMethod("getMavenModel").invoke(mavenProject)
            } catch (_: Exception) {
                mavenProject.javaClass.getMethod("getModel").invoke(mavenProject)
            }
            @Suppress("UNCHECKED_CAST")
            val profiles = model.javaClass.getMethod("getProfiles").invoke(model) as List<Any>
            profiles.mapNotNull { profile ->
                try {
                    profile.javaClass.getMethod("getId").invoke(profile) as? String
                } catch (_: Exception) { null }
            }
        } catch (_: Exception) { emptyList() }
    }

    // ==================== Maven Dependency Tree ====================

    private val maxTreeDepth = 5

    private fun executeMavenDependencyTree(params: JsonObject, project: Project): ToolResult {
        return try {
            val moduleFilter = params["module"]?.jsonPrimitive?.content
            val artifactFilter = params["artifact"]?.jsonPrimitive?.content?.lowercase()

            val manager = MavenUtils.getMavenManager(project)
                ?: return MavenUtils.noMavenError()

            val mavenProjects = MavenUtils.getMavenProjects(manager)
            if (mavenProjects.isEmpty()) {
                return ToolResult("No Maven projects found.", "No Maven projects", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
            }

            val targetProject = MavenUtils.findMavenProject(mavenProjects, manager, moduleFilter)
                ?: return ToolResult(
                    "Module '${moduleFilter}' not found. Available: ${MavenUtils.getProjectNames(mavenProjects)}",
                    "Module not found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                )

            val projectName = MavenUtils.getDisplayName(targetProject)

            val treeNodes = getDependencyTree(targetProject)

            val content = if (treeNodes != null) {
                buildTreeOutput(projectName, treeNodes, artifactFilter)
            } else {
                buildFallbackOutput(projectName, targetProject, artifactFilter)
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "Dependency tree for $projectName",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        } catch (e: Exception) {
            ToolResult("Error building dependency tree: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    private fun getDependencyTree(mavenProject: Any): List<Any>? {
        return try {
            @Suppress("UNCHECKED_CAST")
            mavenProject.javaClass.getMethod("getDependencyTree").invoke(mavenProject) as? List<Any>
        } catch (_: NoSuchMethodException) { null }
        catch (_: Exception) { null }
    }

    private fun buildTreeOutput(projectName: String, rootNodes: List<Any>, artifactFilter: String?): String {
        return buildString {
            val filterDesc = if (artifactFilter != null) " (filtered: $artifactFilter)" else ""
            appendLine("Dependency tree for $projectName$filterDesc:")
            appendLine()

            if (artifactFilter != null) {
                val matchingLines = mutableListOf<String>()
                for (node in rootNodes) {
                    collectFilteredLines(node, artifactFilter, "", matchingLines, 0)
                }
                if (matchingLines.isEmpty()) {
                    appendLine("No dependencies matching '$artifactFilter' found.")
                } else {
                    matchingLines.forEach { appendLine(it) }
                }
            } else {
                for (node in rootNodes) {
                    renderNode(node, "", this, 0)
                }
            }
        }
    }

    private fun renderNode(node: Any, indent: String, sb: StringBuilder, depth: Int) {
        val coords = getNodeCoordinates(node) ?: return
        sb.appendLine("$indent$coords")

        if (depth >= maxTreeDepth) {
            val children = getChildNodes(node)
            if (children.isNotEmpty()) {
                sb.appendLine("$indent  [${children.size} more dependencies — max depth $maxTreeDepth reached]")
            }
            return
        }

        val children = getChildNodes(node)
        for (child in children) {
            renderNode(child, "$indent  ", sb, depth + 1)
        }
    }

    private fun collectFilteredLines(
        node: Any, filter: String, indent: String, result: MutableList<String>, depth: Int
    ): Boolean {
        val coords = getNodeCoordinates(node) ?: return false
        val nodeMatches = coords.lowercase().contains(filter)

        val children = if (depth < maxTreeDepth) getChildNodes(node) else emptyList()
        val childResults = mutableListOf<String>()
        var anyChildMatches = false
        for (child in children) {
            if (collectFilteredLines(child, filter, "$indent  ", childResults, depth + 1)) {
                anyChildMatches = true
            }
        }

        return if (nodeMatches || anyChildMatches) {
            result.add("$indent$coords")
            result.addAll(childResults)
            true
        } else {
            false
        }
    }

    private fun getNodeCoordinates(node: Any): String? {
        return try {
            val artifact = node.javaClass.getMethod("getArtifact").invoke(node) ?: return null
            val artifactClass = artifact.javaClass
            val groupId = artifactClass.getMethod("getGroupId").invoke(artifact) as? String ?: return null
            val artifactId = artifactClass.getMethod("getArtifactId").invoke(artifact) as? String ?: return null
            val version = artifactClass.getMethod("getVersion").invoke(artifact) as? String ?: ""
            val scope = try {
                artifactClass.getMethod("getScope").invoke(artifact) as? String ?: ""
            } catch (_: Exception) { "" }

            buildString {
                append("$groupId:$artifactId")
                if (version.isNotBlank()) append(":$version")
                if (scope.isNotBlank() && scope != "compile") append(" [$scope]")
            }
        } catch (_: Exception) { null }
    }

    private fun getChildNodes(node: Any): List<Any> {
        return try {
            @Suppress("UNCHECKED_CAST")
            node.javaClass.getMethod("getDependencies").invoke(node) as? List<Any> ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    private fun buildFallbackOutput(projectName: String, mavenProject: Any, artifactFilter: String?): String {
        val dependencies = MavenUtils.getDependencies(mavenProject)
        val filtered = if (artifactFilter != null) {
            dependencies.filter { dep ->
                dep.groupId.lowercase().contains(artifactFilter) ||
                    dep.artifactId.lowercase().contains(artifactFilter)
            }
        } else {
            dependencies
        }

        return buildString {
            appendLine("Dependency tree for $projectName:")
            appendLine()
            appendLine("Note: Full transitive tree unavailable (getDependencyTree() not supported by this Maven plugin version).")
            appendLine("Showing direct dependencies only:")
            appendLine()

            if (filtered.isEmpty()) {
                appendLine("No dependencies found${if (artifactFilter != null) " matching '$artifactFilter'" else ""}.")
            } else {
                for (dep in filtered.sortedBy { "${it.groupId}:${it.artifactId}" }) {
                    val version = if (dep.version.isNotBlank()) ":${dep.version}" else ""
                    val scopeSuffix = if (dep.scope.isNotBlank() && dep.scope != "compile") " [${dep.scope}]" else ""
                    appendLine("${dep.groupId}:${dep.artifactId}$version$scopeSuffix")
                }
            }
        }
    }

    // ==================== Maven Effective POM ====================

    private companion object {
        const val CONFIG_LINE_LIMIT = 30
    }

    private data class ExecutionInfo(
        val executionId: String,
        val phase: String,
        val goals: List<String>
    )

    private data class PluginConfigInfo(
        val groupId: String,
        val artifactId: String,
        val version: String,
        val configLines: List<String>,
        val executions: List<ExecutionInfo>
    )

    private fun executeMavenEffectivePom(params: JsonObject, project: Project): ToolResult {
        return try {
            val moduleFilter = params["module"]?.jsonPrimitive?.content
            val pluginFilter = params["plugin"]?.jsonPrimitive?.content?.lowercase()

            val manager = MavenUtils.getMavenManager(project)
                ?: return MavenUtils.noMavenError()

            val mavenProjects = MavenUtils.getMavenProjects(manager)
            if (mavenProjects.isEmpty()) {
                return ToolResult("No Maven projects found.", "No Maven projects", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
            }

            val targetProject = MavenUtils.findMavenProject(mavenProjects, manager, moduleFilter)
                ?: return ToolResult(
                    "Module '${moduleFilter}' not found. Available: ${MavenUtils.getProjectNames(mavenProjects)}",
                    "Module not found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                )

            val plugins = getPluginConfigs(targetProject)

            val filtered = if (pluginFilter != null) {
                plugins.filter { it.artifactId.lowercase().contains(pluginFilter) }
            } else {
                plugins
            }

            if (filtered.isEmpty()) {
                val filterDesc = if (pluginFilter != null) " matching '$pluginFilter'" else ""
                return ToolResult("No plugin configurations found$filterDesc.", "No plugins", 5)
            }

            val projectName = MavenUtils.getDisplayName(targetProject)
            val content = buildString {
                appendLine("Plugin configurations for $projectName:")
                appendLine()
                for (plugin in filtered.sortedBy { "${it.groupId}:${it.artifactId}" }) {
                    val version = if (plugin.version.isNotBlank()) ":${plugin.version}" else ""
                    appendLine("${plugin.groupId}:${plugin.artifactId}$version")

                    if (plugin.configLines.isNotEmpty()) {
                        appendLine("  Configuration:")
                        for (line in plugin.configLines) {
                            appendLine("    $line")
                        }
                    }

                    if (plugin.executions.isNotEmpty()) {
                        appendLine("  Executions:")
                        for (exec in plugin.executions) {
                            val phase = if (exec.phase.isNotBlank()) " (phase: ${exec.phase})" else ""
                            val goals = if (exec.goals.isNotEmpty()) " goals: ${exec.goals.joinToString(", ")}" else ""
                            appendLine("    ${exec.executionId}$phase$goals")
                        }
                    }

                    appendLine()
                }
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "${filtered.size} plugin configurations",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        } catch (e: Exception) {
            ToolResult("Error reading effective POM: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    private fun getPluginConfigs(mavenProject: Any): List<PluginConfigInfo> {
        return try {
            @Suppress("UNCHECKED_CAST")
            val plugins = mavenProject.javaClass.getMethod("getDeclaredPlugins").invoke(mavenProject) as List<Any>
            plugins.mapNotNull { plugin ->
                try {
                    val groupId = plugin.javaClass.getMethod("getGroupId").invoke(plugin) as? String ?: return@mapNotNull null
                    val artifactId = plugin.javaClass.getMethod("getArtifactId").invoke(plugin) as? String ?: return@mapNotNull null
                    val version = plugin.javaClass.getMethod("getVersion").invoke(plugin) as? String ?: ""
                    val configLines = extractConfigLines(plugin)
                    val executions = extractExecutions(plugin)
                    PluginConfigInfo(groupId, artifactId, version, configLines, executions)
                } catch (_: Exception) { null }
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun extractConfigLines(plugin: Any): List<String> {
        return try {
            val configElement = plugin.javaClass.getMethod("getConfigurationElement").invoke(plugin)
                ?: return emptyList()
            val rawText = configElement.javaClass.getMethod("getText").invoke(configElement) as? String
                ?: return emptyList()
            val lines = rawText.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
            if (lines.size > CONFIG_LINE_LIMIT) {
                lines.take(CONFIG_LINE_LIMIT) + listOf("... (${lines.size - CONFIG_LINE_LIMIT} more lines truncated)")
            } else {
                lines
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun extractExecutions(plugin: Any): List<ExecutionInfo> {
        return try {
            @Suppress("UNCHECKED_CAST")
            val executions = plugin.javaClass.getMethod("getExecutions").invoke(plugin) as List<Any>
            executions.mapNotNull { execution ->
                try {
                    val executionId = execution.javaClass.getMethod("getExecutionId").invoke(execution) as? String ?: "default"
                    val phase = execution.javaClass.getMethod("getPhase").invoke(execution) as? String ?: ""
                    @Suppress("UNCHECKED_CAST")
                    val goals = try {
                        execution.javaClass.getMethod("getGoals").invoke(execution) as? List<String> ?: emptyList()
                    } catch (_: Exception) { emptyList() }
                    ExecutionInfo(executionId, phase, goals)
                } catch (_: Exception) { null }
            }
        } catch (_: Exception) { emptyList() }
    }

    // ==================== Gradle Dependencies ====================

    private data class GradleDependencyEntry(
        val module: String,
        val configuration: String,
        val notation: String
    )

    private suspend fun executeGradleDependencies(params: JsonObject, project: Project): ToolResult {
        val moduleFilter = params["module"]?.jsonPrimitive?.content?.trimStart(':')
        val configFilter = params["configuration"]?.jsonPrimitive?.content?.lowercase()
        val searchFilter = params["search"]?.jsonPrimitive?.content?.lowercase()
        val basePath = project.basePath
            ?: return ToolResult("Error: project base path not available", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        return try {
            withContext(Dispatchers.IO) {
                val baseDir = File(basePath)
                val buildFiles = findGradleBuildFiles(baseDir, moduleFilter)

                if (buildFiles.isEmpty()) {
                    return@withContext ToolResult(
                        "No Gradle build files found${if (moduleFilter != null) " for module '$moduleFilter'" else ""}.",
                        "No build files", 10
                    )
                }

                val allDeps = mutableListOf<GradleDependencyEntry>()

                for ((moduleLabel, buildFile) in buildFiles) {
                    val deps = parseGradleDependencies(buildFile, moduleLabel)
                    allDeps.addAll(deps)
                }

                val filtered = allDeps.filter { dep ->
                    val matchesConfig = configFilter == null || dep.configuration.lowercase() == configFilter
                    val matchesSearch = searchFilter == null ||
                        dep.notation.lowercase().contains(searchFilter) ||
                        dep.configuration.lowercase().contains(searchFilter)
                    matchesConfig && matchesSearch
                }

                if (filtered.isEmpty()) {
                    val filterDesc = buildString {
                        if (configFilter != null) append(" configuration=$configFilter")
                        if (searchFilter != null) append(" search=$searchFilter")
                    }
                    return@withContext ToolResult("No dependencies found matching:$filterDesc", "No matches", 5)
                }

                val byModule = filtered.groupBy { it.module }

                val content = buildString {
                    appendLine("Gradle dependencies (${filtered.size} total across ${byModule.size} module(s)):")
                    appendLine()

                    for ((mod, modDeps) in byModule.toSortedMap()) {
                        if (byModule.size > 1) appendLine("[$mod]")

                        val byConfig = modDeps
                            .groupBy { it.configuration }
                            .toSortedMap(compareBy { gradleConfigOrder(it) })

                        for ((config, deps) in byConfig) {
                            appendLine("$config (${deps.size}):")
                            for (dep in deps.sortedBy { it.notation }) {
                                appendLine("  ${dep.notation}")
                            }
                            appendLine()
                        }
                    }
                }

                ToolResult(
                    content = content.trimEnd(),
                    summary = "${filtered.size} dependencies",
                    tokenEstimate = TokenEstimator.estimate(content)
                )
            }
        } catch (e: Exception) {
            ToolResult("Error reading Gradle dependencies: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    private fun gradleConfigOrder(config: String): Int = when (config.lowercase()) {
        "api" -> 0; "implementation" -> 1; "compilonly" -> 2; "runtimeonly" -> 3
        "testimplementation" -> 4; "testcompileonly" -> 5; "testruntimeonly" -> 6
        "kapt" -> 7; "ksp" -> 8; "annotationprocessor" -> 9; "classpath" -> 10
        else -> 20
    }

    private fun parseGradleDependencies(buildFile: File, moduleLabel: String): List<GradleDependencyEntry> {
        val content = buildFile.readText()
        val entries = mutableListOf<GradleDependencyEntry>()

        val depsBlockPattern = Regex("""dependencies\s*\{([^}]*(?:\{[^}]*\}[^}]*)*)\}""", RegexOption.DOT_MATCHES_ALL)
        val depsBlocks = depsBlockPattern.findAll(content)

        for (block in depsBlocks) {
            val blockContent = block.groupValues[1]
            parseGradleDepLines(blockContent, moduleLabel, entries)
        }

        if (entries.isEmpty()) {
            parseGradleDepLines(content, moduleLabel, entries)
        }

        return entries
    }

    private fun parseGradleDepLines(text: String, moduleLabel: String, entries: MutableList<GradleDependencyEntry>) {
        val lines = text.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("//") || trimmed.isBlank()) continue
            val entry = parseSingleGradleDependencyLine(trimmed, moduleLabel) ?: continue
            entries.add(entry)
        }
    }

    private fun parseSingleGradleDependencyLine(line: String, moduleLabel: String): GradleDependencyEntry? {
        // Kotlin DSL: implementation("group:artifact:version")
        val kotlinStringDep = Regex("""^(\w+)\s*\(\s*"([^"]+)"\s*\)""").find(line)
        if (kotlinStringDep != null) {
            val config = kotlinStringDep.groupValues[1]
            val notation = kotlinStringDep.groupValues[2]
            if (isValidGradleConfig(config)) return GradleDependencyEntry(moduleLabel, config, notation)
        }

        // Kotlin DSL single-quote
        val kotlinSingleQuoteDep = Regex("""^(\w+)\s*\(\s*'([^']+)'\s*\)""").find(line)
        if (kotlinSingleQuoteDep != null) {
            val config = kotlinSingleQuoteDep.groupValues[1]
            val notation = kotlinSingleQuoteDep.groupValues[2]
            if (isValidGradleConfig(config)) return GradleDependencyEntry(moduleLabel, config, notation)
        }

        // Version catalog: implementation(libs.xxx.yyy)
        val versionCatalogDep = Regex("""^(\w+)\s*\(\s*(libs\.[a-zA-Z0-9._-]+)\s*\)""").find(line)
        if (versionCatalogDep != null) {
            val config = versionCatalogDep.groupValues[1]
            val notation = versionCatalogDep.groupValues[2]
            if (isValidGradleConfig(config)) return GradleDependencyEntry(moduleLabel, config, notation)
        }

        // Project dependency: implementation(project(":module"))
        val projectDep = Regex("""^(\w+)\s*\(\s*project\s*\(\s*["']([^"']+)["']\s*\)\s*\)""").find(line)
        if (projectDep != null) {
            val config = projectDep.groupValues[1]
            val mod = projectDep.groupValues[2]
            if (isValidGradleConfig(config)) return GradleDependencyEntry(moduleLabel, config, "project($mod)")
        }

        // Groovy DSL: implementation 'group:artifact:version'
        val groovyDep = Regex("""^(\w+)\s+['"]([^'"]+)['"]""").find(line)
        if (groovyDep != null) {
            val config = groovyDep.groupValues[1]
            val notation = groovyDep.groupValues[2]
            if (isValidGradleConfig(config)) return GradleDependencyEntry(moduleLabel, config, notation)
        }

        // Groovy DSL project dep
        val groovyProjectDep = Regex("""^(\w+)\s+project\s*\(\s*['"]([^'"]+)['"]\s*\)""").find(line)
        if (groovyProjectDep != null) {
            val config = groovyProjectDep.groupValues[1]
            val mod = groovyProjectDep.groupValues[2]
            if (isValidGradleConfig(config)) return GradleDependencyEntry(moduleLabel, config, "project($mod)")
        }

        // Map syntax: implementation(group: "x", name: "y", version: "z")
        val mapSyntaxDep = Regex("""^(\w+)\s*\(\s*group\s*[=:]\s*["']([^"']+)["']\s*,\s*name\s*[=:]\s*["']([^"']+)["'](?:\s*,\s*version\s*[=:]\s*["']([^"']+)["'])?\s*\)""").find(line)
        if (mapSyntaxDep != null) {
            val config = mapSyntaxDep.groupValues[1]
            val group = mapSyntaxDep.groupValues[2]
            val name = mapSyntaxDep.groupValues[3]
            val version = mapSyntaxDep.groupValues[4]
            if (isValidGradleConfig(config)) {
                val notation = if (version.isNotBlank()) "$group:$name:$version" else "$group:$name"
                return GradleDependencyEntry(moduleLabel, config, notation)
            }
        }

        return null
    }

    private fun isValidGradleConfig(config: String): Boolean {
        val validConfigs = setOf(
            "implementation", "api", "compileOnly", "runtimeOnly",
            "testImplementation", "testCompileOnly", "testRuntimeOnly",
            "annotationProcessor", "kapt", "ksp",
            "debugImplementation", "releaseImplementation",
            "androidTestImplementation", "classpath",
            "compile", "testCompile", "provided"
        )
        return config in validConfigs || config.endsWith("Implementation") || config.endsWith("Api")
    }

    // ==================== Gradle Tasks ====================

    private data class GradleTaskEntry(
        val module: String,
        val name: String,
        val type: String?,
        val description: String?
    )

    private suspend fun executeGradleTasks(params: JsonObject, project: Project): ToolResult {
        val moduleFilter = params["module"]?.jsonPrimitive?.content?.trimStart(':')
        val searchFilter = params["search"]?.jsonPrimitive?.content?.lowercase()
        val basePath = project.basePath
            ?: return ToolResult("Error: project base path not available", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        return try {
            withContext(Dispatchers.IO) {
                val baseDir = File(basePath)
                val buildFiles = findGradleBuildFiles(baseDir, moduleFilter)

                if (buildFiles.isEmpty()) {
                    return@withContext ToolResult(
                        "No Gradle build files found${if (moduleFilter != null) " for module '$moduleFilter'" else ""}.",
                        "No build files", 10
                    )
                }

                val allTasks = mutableListOf<GradleTaskEntry>()

                for ((moduleLabel, buildFile) in buildFiles) {
                    val tasks = parseGradleTasks(buildFile, moduleLabel)
                    allTasks.addAll(tasks)
                }

                val filtered = if (searchFilter != null) {
                    allTasks.filter { task ->
                        task.name.lowercase().contains(searchFilter) ||
                            task.type?.lowercase()?.contains(searchFilter) == true
                    }
                } else {
                    allTasks
                }

                if (filtered.isEmpty()) {
                    val filterDesc = if (searchFilter != null) " matching '$searchFilter'" else ""
                    return@withContext ToolResult("No custom tasks found$filterDesc.", "No tasks", 5)
                }

                val byModule = filtered.groupBy { it.module }

                val content = buildString {
                    appendLine("Gradle tasks (${filtered.size} total across ${byModule.size} module(s)):")
                    appendLine()

                    for ((mod, modTasks) in byModule.toSortedMap()) {
                        if (byModule.size > 1) appendLine("[$mod]")

                        for (task in modTasks.sortedBy { it.name }) {
                            val typeStr = if (task.type != null) " <${task.type}>" else ""
                            val descStr = if (task.description != null) " — ${task.description}" else ""
                            appendLine("  ${task.name}$typeStr$descStr")
                        }
                        appendLine()
                    }
                }

                ToolResult(
                    content = content.trimEnd(),
                    summary = "${filtered.size} tasks",
                    tokenEstimate = TokenEstimator.estimate(content)
                )
            }
        } catch (e: Exception) {
            ToolResult("Error reading Gradle tasks: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    private fun parseGradleTasks(buildFile: File, moduleLabel: String): List<GradleTaskEntry> {
        val content = buildFile.readText()
        val tasks = mutableListOf<GradleTaskEntry>()

        // Kotlin DSL: tasks.register<Type>("name") { ... }
        val registerTyped = Regex("""tasks\.register\s*<\s*(\w+)\s*>\s*\(\s*["'](\w+)["']""")
        registerTyped.findAll(content).forEach { match ->
            val type = match.groupValues[1]
            val name = match.groupValues[2]
            val description = extractGradleTaskDescription(content, match.range.last)
            tasks.add(GradleTaskEntry(moduleLabel, name, type, description))
        }

        // Kotlin DSL: tasks.register("name") { ... }
        val registerUntyped = Regex("""tasks\.register\s*\(\s*["'](\w+)["']\s*\)""")
        registerUntyped.findAll(content).forEach { match ->
            val name = match.groupValues[1]
            if (tasks.none { it.name == name && it.module == moduleLabel }) {
                val description = extractGradleTaskDescription(content, match.range.last)
                tasks.add(GradleTaskEntry(moduleLabel, name, null, description))
            }
        }

        // Kotlin DSL: val name by tasks.registering(Type::class) { ... }
        val registeringBy = Regex("""val\s+(\w+)\s+by\s+tasks\.registering\s*\(\s*(\w+)::class""")
        registeringBy.findAll(content).forEach { match ->
            val name = match.groupValues[1]
            val type = match.groupValues[2]
            if (tasks.none { it.name == name && it.module == moduleLabel }) {
                tasks.add(GradleTaskEntry(moduleLabel, name, type, null))
            }
        }

        // Kotlin DSL: val name by tasks.registering { ... }
        val registeringByUntyped = Regex("""val\s+(\w+)\s+by\s+tasks\.registering\s*\{""")
        registeringByUntyped.findAll(content).forEach { match ->
            val name = match.groupValues[1]
            if (tasks.none { it.name == name && it.module == moduleLabel }) {
                tasks.add(GradleTaskEntry(moduleLabel, name, null, null))
            }
        }

        // Groovy DSL: task("name") { ... }
        val groovyTaskQuoted = Regex("""task\s*\(\s*["'](\w+)["']\s*\)""")
        groovyTaskQuoted.findAll(content).forEach { match ->
            val name = match.groupValues[1]
            if (tasks.none { it.name == name && it.module == moduleLabel }) {
                val description = extractGradleTaskDescription(content, match.range.last)
                tasks.add(GradleTaskEntry(moduleLabel, name, null, description))
            }
        }

        // Groovy DSL: task taskName { ... } or task taskName(type: SomeType) { ... }
        val groovyTaskSimple = Regex("""^task\s+(\w+)(?:\s*\(type:\s*(\w+)\))?""", RegexOption.MULTILINE)
        groovyTaskSimple.findAll(content).forEach { match ->
            val name = match.groupValues[1]
            val type = match.groupValues[2].ifBlank { null }
            if (tasks.none { it.name == name && it.module == moduleLabel }) {
                tasks.add(GradleTaskEntry(moduleLabel, name, type, null))
            }
        }

        // Groovy DSL: task taskName(type: SomeType) — alternate
        val groovyTaskType = Regex("""task\s+(\w+)\s*\(\s*type\s*:\s*(\w+)\s*\)""")
        groovyTaskType.findAll(content).forEach { match ->
            val name = match.groupValues[1]
            val type = match.groupValues[2]
            if (tasks.none { it.name == name && it.module == moduleLabel }) {
                tasks.add(GradleTaskEntry(moduleLabel, name, type, null))
            }
        }

        return tasks
    }

    private fun extractGradleTaskDescription(content: String, startPos: Int): String? {
        val window = content.substring(minOf(startPos, content.length), minOf(startPos + 500, content.length))
        val descPattern = Regex("""description\s*=\s*["']([^"']+)["']""")
        return descPattern.find(window)?.groupValues?.get(1)
    }

    // ==================== Gradle Properties ====================

    private data class GradlePropertySource(
        val label: String,
        val properties: Map<String, String>
    )

    private suspend fun executeGradleProperties(params: JsonObject, project: Project): ToolResult {
        val searchFilter = params["search"]?.jsonPrimitive?.content?.lowercase()
        val basePath = project.basePath
            ?: return ToolResult("Error: project base path not available", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        return try {
            withContext(Dispatchers.IO) {
                val baseDir = File(basePath)
                val sources = mutableListOf<GradlePropertySource>()

                collectGradleProperties(baseDir, "gradle.properties", sources)

                val modules = listGradleModules(baseDir)
                for (mod in modules) {
                    val modDir = File(baseDir, mod.trimStart(':').replace(':', '/'))
                    collectGradleProperties(modDir, "${mod.trimStart(':')}/gradle.properties", sources)
                }

                if (modules.isEmpty()) {
                    baseDir.listFiles()
                        ?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != "build" && it.name != "gradle" }
                        ?.forEach { subDir ->
                            collectGradleProperties(subDir, "${subDir.name}/gradle.properties", sources)
                        }
                }

                val versionCatalog = File(baseDir, "gradle/libs.versions.toml")
                if (versionCatalog.isFile) {
                    val catalogSource = parseVersionCatalog(versionCatalog)
                    if (catalogSource != null) sources.add(catalogSource)
                }

                if (sources.isEmpty()) {
                    return@withContext ToolResult(
                        "No gradle.properties or libs.versions.toml found in project.",
                        "No property files", 10
                    )
                }

                val filteredSources = sources.map { source ->
                    if (searchFilter != null) {
                        val filtered = source.properties.filter { (key, value) ->
                            key.lowercase().contains(searchFilter) || value.lowercase().contains(searchFilter)
                        }
                        source.copy(properties = filtered)
                    } else {
                        source
                    }
                }.filter { it.properties.isNotEmpty() }

                val totalCount = filteredSources.sumOf { it.properties.size }

                if (totalCount == 0) {
                    return@withContext ToolResult(
                        "No properties found matching '${searchFilter}'.",
                        "No matches", 5
                    )
                }

                val content = buildString {
                    appendLine("Gradle properties ($totalCount total across ${filteredSources.size} source(s)):")
                    appendLine()

                    for (source in filteredSources) {
                        appendLine("[${source.label}]")
                        source.properties.entries
                            .sortedBy { it.key }
                            .forEach { (key, value) ->
                                val displayValue = if (value.length > 100) value.take(97) + "..." else value
                                appendLine("  $key = $displayValue")
                            }
                        appendLine()
                    }
                }

                ToolResult(
                    content = content.trimEnd(),
                    summary = "$totalCount properties from ${filteredSources.size} file(s)",
                    tokenEstimate = TokenEstimator.estimate(content)
                )
            }
        } catch (e: Exception) {
            ToolResult("Error reading Gradle properties: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    private fun collectGradleProperties(dir: File, label: String, sources: MutableList<GradlePropertySource>) {
        val file = File(dir, "gradle.properties")
        if (!file.isFile) return

        val props = Properties()
        file.inputStream().use { props.load(it) }

        if (props.isNotEmpty()) {
            val map = props.entries.associate { it.key.toString() to it.value.toString() }
            sources.add(GradlePropertySource(label, map))
        }
    }

    private fun parseVersionCatalog(tomlFile: File): GradlePropertySource? {
        val lines = tomlFile.readLines()
        val versions = mutableMapOf<String, String>()

        var inVersionsSection = false

        for (line in lines) {
            val trimmed = line.trim()

            if (trimmed.startsWith("[")) {
                inVersionsSection = trimmed == "[versions]"
                continue
            }

            if (!inVersionsSection) continue
            if (trimmed.isBlank() || trimmed.startsWith("#")) continue

            val eqIndex = trimmed.indexOf('=')
            if (eqIndex < 0) continue

            val key = trimmed.substring(0, eqIndex).trim()
            val rawValue = trimmed.substring(eqIndex + 1).trim()

            val value = when {
                rawValue.startsWith('"') && rawValue.endsWith('"') ->
                    rawValue.removeSurrounding("\"")
                rawValue.startsWith('\'') && rawValue.endsWith('\'') ->
                    rawValue.removeSurrounding("'")
                rawValue.startsWith("{") -> {
                    val requirePattern = Regex("""require\s*=\s*["']([^"']+)["']""")
                    val refPattern = Regex("""ref\s*=\s*["']([^"']+)["']""")
                    requirePattern.find(rawValue)?.groupValues?.get(1)
                        ?: refPattern.find(rawValue)?.groupValues?.get(1)
                        ?: rawValue
                }
                else -> rawValue
            }

            if (key.isNotBlank() && value.isNotBlank()) {
                versions[key] = value
            }
        }

        if (versions.isEmpty()) return null
        return GradlePropertySource("gradle/libs.versions.toml [versions]", versions)
    }

    // ==================== Shared Gradle Utilities ====================

    private fun findGradleBuildFiles(baseDir: File, moduleFilter: String?): List<Pair<String, File>> {
        val result = mutableListOf<Pair<String, File>>()

        if (moduleFilter != null) {
            val moduleDir = File(baseDir, moduleFilter)
            val buildFile = findGradleBuildFile(moduleDir)
            if (buildFile != null) {
                result.add(moduleFilter to buildFile)
            } else {
                val rootBuild = findGradleBuildFile(baseDir)
                if (rootBuild != null) result.add("root" to rootBuild)
            }
        } else {
            val rootBuild = findGradleBuildFile(baseDir)
            if (rootBuild != null) result.add("root" to rootBuild)

            val modules = listGradleModules(baseDir)
            for (mod in modules) {
                val modDir = File(baseDir, mod.trimStart(':').replace(':', '/'))
                val buildFile = findGradleBuildFile(modDir)
                if (buildFile != null) result.add(mod.trimStart(':') to buildFile)
            }

            if (modules.isEmpty()) {
                baseDir.listFiles()
                    ?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != "build" && it.name != "gradle" }
                    ?.forEach { subDir ->
                        val buildFile = findGradleBuildFile(subDir)
                        if (buildFile != null) result.add(subDir.name to buildFile)
                    }
            }
        }

        return result
    }

    private fun findGradleBuildFile(dir: File): File? {
        val kts = File(dir, "build.gradle.kts")
        if (kts.isFile) return kts
        val groovy = File(dir, "build.gradle")
        if (groovy.isFile) return groovy
        return null
    }

    private fun listGradleModules(baseDir: File): List<String> {
        val settingsKts = File(baseDir, "settings.gradle.kts")
        val settingsGroovy = File(baseDir, "settings.gradle")
        val settingsFile = when {
            settingsKts.isFile -> settingsKts
            settingsGroovy.isFile -> settingsGroovy
            else -> return emptyList()
        }

        val modules = mutableListOf<String>()
        val pattern = Regex("""include\s*\(\s*["']([^"']+)["']\s*\)|include\s+['"]([^'"]+)['"]""")
        settingsFile.readLines().forEach { line ->
            pattern.findAll(line).forEach { match ->
                val mod = match.groupValues[1].ifEmpty { match.groupValues[2] }
                if (mod.isNotBlank()) modules.add(mod)
            }
        }
        return modules
    }

    // ==================== Project Modules ====================

    private data class MavenModuleInfo(
        val groupId: String,
        val artifactId: String,
        val version: String,
        val packaging: String,
        val dependencyCount: Int
    )

    private fun executeProjectModules(params: JsonObject, project: Project): ToolResult {
        return try {
            val modules = ModuleManager.getInstance(project).modules
            if (modules.isEmpty()) {
                return ToolResult("No modules found in project.", "No modules", 5)
            }

            val mavenInfo = tryGetMavenInfo(project)

            val content = buildString {
                appendLine("Project modules (${modules.size}):")
                appendLine()

                for (module in modules.sortedBy { it.name }) {
                    appendLine("Module: ${module.name}")

                    val maven = mavenInfo[module.name]
                    if (maven != null) {
                        appendLine("  Maven: ${maven.groupId}:${maven.artifactId}:${maven.version}")
                        if (maven.packaging.isNotBlank() && maven.packaging != "jar") {
                            appendLine("  Packaging: ${maven.packaging}")
                        }
                    }

                    val rootManager = ModuleRootManager.getInstance(module)
                    val sourceRoots = rootManager.sourceRoots
                    val contentRoots = rootManager.contentRoots

                    if (contentRoots.isNotEmpty()) {
                        val modulePath = contentRoots.first().path
                        val relativePath = project.basePath?.let { base ->
                            if (modulePath.startsWith(base)) modulePath.removePrefix("$base/") else modulePath
                        } ?: modulePath
                        appendLine("  Path: $relativePath")
                    }

                    if (sourceRoots.isNotEmpty()) {
                        appendLine("  Sources:")
                        sourceRoots.take(10).forEach { root ->
                            val relativePath = project.basePath?.let { base ->
                                if (root.path.startsWith(base)) root.path.removePrefix("$base/") else root.path
                            } ?: root.path
                            val isTest = rootManager.fileIndex.isInTestSourceContent(root)
                            val tag = if (isTest) "test" else "main"
                            appendLine("    [$tag] $relativePath")
                        }
                        if (sourceRoots.size > 10) {
                            appendLine("    ... and ${sourceRoots.size - 10} more")
                        }
                    }

                    if (maven != null && maven.dependencyCount > 0) {
                        appendLine("  Dependencies: ${maven.dependencyCount}")
                    }

                    appendLine()
                }
            }

            ToolResult(
                content = content,
                summary = "${modules.size} module(s)",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        } catch (e: Exception) {
            ToolResult("Error listing modules: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    private fun tryGetMavenInfo(project: Project): Map<String, MavenModuleInfo> {
        return try {
            val mavenManagerClass = Class.forName("org.jetbrains.idea.maven.project.MavenProjectsManager")
            val getInstanceMethod = mavenManagerClass.getMethod("getInstance", Project::class.java)
            val manager = getInstanceMethod.invoke(null, project)

            val isMavenized = mavenManagerClass.getMethod("isMavenizedProject").invoke(manager) as Boolean
            if (!isMavenized) return emptyMap()

            @Suppress("UNCHECKED_CAST")
            val projects = mavenManagerClass.getMethod("getProjects").invoke(manager) as List<Any>

            val result = mutableMapOf<String, MavenModuleInfo>()
            for (mavenProject in projects) {
                val mavenIdObj = mavenProject.javaClass.getMethod("getMavenId").invoke(mavenProject)
                val groupId = mavenIdObj.javaClass.getMethod("getGroupId").invoke(mavenIdObj) as? String ?: ""
                val artifactId = mavenIdObj.javaClass.getMethod("getArtifactId").invoke(mavenIdObj) as? String ?: ""
                val version = mavenIdObj.javaClass.getMethod("getVersion").invoke(mavenIdObj) as? String ?: ""

                val displayName = mavenProject.javaClass.getMethod("getDisplayName").invoke(mavenProject) as? String ?: artifactId

                val packaging = try {
                    mavenProject.javaClass.getMethod("getPackaging").invoke(mavenProject) as? String ?: "jar"
                } catch (_: Exception) { "jar" }

                val depCount = try {
                    @Suppress("UNCHECKED_CAST")
                    val deps = mavenProject.javaClass.getMethod("getDependencies").invoke(mavenProject) as List<Any>
                    deps.size
                } catch (_: Exception) { 0 }

                val moduleName = try {
                    val findModuleMethod = mavenManagerClass.getMethod("findModule", mavenProject.javaClass)
                    val module = findModuleMethod.invoke(manager, mavenProject)
                    if (module != null) {
                        module.javaClass.getMethod("getName").invoke(module) as? String ?: displayName
                    } else {
                        displayName
                    }
                } catch (_: Exception) {
                    displayName
                }

                result[moduleName] = MavenModuleInfo(
                    groupId = groupId,
                    artifactId = artifactId,
                    version = version,
                    packaging = packaging,
                    dependencyCount = depCount
                )
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    // ==================== Module Dependency Graph ====================

    private data class ModuleDependencyInfo(
        val name: String,
        val scope: String = "COMPILE"
    )

    private fun executeModuleDependencyGraph(params: JsonObject, project: Project): ToolResult {
        return try {
            val moduleName = params["module"]?.jsonPrimitive?.content
            val transitive = params["transitive"]?.jsonPrimitive?.booleanOrNull ?: false
            val includeLibraries = params["include_libraries"]?.jsonPrimitive?.booleanOrNull ?: false
            val detectCycles = params["detect_cycles"]?.jsonPrimitive?.booleanOrNull ?: true

            val allModules = ReadAction.compute<Array<Module>, Throwable> {
                ModuleManager.getInstance(project).modules
            }

            if (allModules.isEmpty()) {
                return ToolResult("No modules found in project.", "No modules", 5)
            }

            val targetModules = if (moduleName != null) {
                val found = allModules.find { it.name == moduleName }
                    ?: return ToolResult(
                        "Module '$moduleName' not found. Available modules: ${allModules.map { it.name }.sorted().joinToString(", ")}",
                        "Module not found",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )
                arrayOf(found)
            } else {
                allModules
            }

            val adjacency = ReadAction.compute<Map<String, List<ModuleDependencyInfo>>, Throwable> {
                buildAdjacencyList(allModules)
            }

            val content = buildString {
                val scope = if (moduleName != null) "Module '$moduleName'" else "Module dependency graph"
                appendLine("$scope (${allModules.size} modules total):")
                appendLine()

                for (module in targetModules.sortedBy { it.name }) {
                    val directDeps = adjacency[module.name] ?: emptyList()

                    if (transitive) {
                        val transitiveDeps = collectTransitiveDeps(module.name, adjacency)
                        val directNames = directDeps.map { it.name }.toSet()
                        val transitiveOnly = transitiveDeps - directNames

                        if (directDeps.isEmpty() && transitiveOnly.isEmpty()) {
                            appendLine("${module.name} (0 deps)")
                        } else {
                            val parts = mutableListOf<String>()
                            if (directDeps.isNotEmpty()) parts.add("${directDeps.size} direct")
                            if (transitiveOnly.isNotEmpty()) parts.add("${transitiveOnly.size} transitive")
                            appendLine("${module.name} (${parts.joinToString(", ")})")
                            if (directDeps.isNotEmpty()) {
                                appendLine("  Direct: [${directDeps.joinToString(", ") { formatModuleDep(it) }}]")
                            }
                            if (transitiveOnly.isNotEmpty()) {
                                appendLine("  Transitive: [${transitiveOnly.sorted().joinToString(", ")}]")
                            }
                        }
                    } else {
                        if (directDeps.isEmpty()) {
                            appendLine("${module.name} (0 module deps)")
                        } else {
                            append("${module.name}")
                            appendLine(" → [${directDeps.joinToString(", ") { formatModuleDep(it) }}]")
                        }
                    }

                    if (includeLibraries) {
                        val libraries = ReadAction.compute<List<String>, Throwable> {
                            collectLibraries(module)
                        }
                        if (libraries.isNotEmpty()) {
                            appendLine("  Libraries: ${libraries.joinToString(", ")}")
                        }
                    }

                    appendLine()
                }

                if (detectCycles) {
                    val cycles = detectCycles(adjacency)
                    if (cycles.isEmpty()) {
                        appendLine("Circular dependencies: NONE DETECTED")
                    } else {
                        appendLine("Circular dependencies DETECTED (${cycles.size}):")
                        for (cycle in cycles) {
                            appendLine("  ${cycle.joinToString(" → ")} → ${cycle.first()}")
                        }
                    }
                }
            }

            val moduleCount = targetModules.size
            val summary = if (moduleName != null) {
                val deps = adjacency[moduleName] ?: emptyList()
                "'$moduleName': ${deps.size} direct dep(s)"
            } else {
                "$moduleCount module(s), ${adjacency.values.sumOf { it.size }} dependency edge(s)"
            }

            ToolResult(
                content = content,
                summary = summary,
                tokenEstimate = TokenEstimator.estimate(content)
            )
        } catch (e: Exception) {
            ToolResult(
                "Error getting module dependency graph: ${e.message}",
                "Error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    private fun buildAdjacencyList(modules: Array<Module>): Map<String, List<ModuleDependencyInfo>> {
        val result = mutableMapOf<String, List<ModuleDependencyInfo>>()
        for (module in modules) {
            val deps = mutableListOf<ModuleDependencyInfo>()
            val rootManager = ModuleRootManager.getInstance(module)
            for (entry in rootManager.orderEntries) {
                if (entry is ModuleOrderEntry) {
                    val depModule = entry.module
                    if (depModule != null) {
                        deps.add(ModuleDependencyInfo(
                            name = depModule.name,
                            scope = entry.scope.name
                        ))
                    }
                }
            }
            result[module.name] = deps
        }
        return result
    }

    private fun collectTransitiveDeps(
        moduleName: String,
        adjacency: Map<String, List<ModuleDependencyInfo>>
    ): Set<String> {
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()

        val directDeps = adjacency[moduleName] ?: return emptySet()
        for (dep in directDeps) {
            queue.add(dep.name)
        }

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current in visited || current == moduleName) continue
            visited.add(current)
            val nextDeps = adjacency[current] ?: continue
            for (dep in nextDeps) {
                if (dep.name !in visited) {
                    queue.add(dep.name)
                }
            }
        }

        return visited
    }

    private fun collectLibraries(module: Module): List<String> {
        val libraries = mutableListOf<String>()
        val rootManager = ModuleRootManager.getInstance(module)
        rootManager.orderEntries().forEachLibrary { library ->
            val name = library.name
            if (name != null) {
                val cleanName = name
                    .removePrefix("Gradle: ")
                    .removePrefix("Maven: ")
                    .substringAfterLast(":")
                    .ifBlank { name }
                libraries.add(cleanName)
            }
            true
        }
        return libraries.sorted().distinct()
    }

    private fun detectCycles(adjacency: Map<String, List<ModuleDependencyInfo>>): List<List<String>> {
        val white = 0
        val gray = 1
        val black = 2

        val color = mutableMapOf<String, Int>()
        val parent = mutableMapOf<String, String?>()
        val cycles = mutableListOf<List<String>>()

        for (node in adjacency.keys) {
            color[node] = white
        }

        fun dfs(node: String) {
            color[node] = gray
            for (dep in adjacency[node] ?: emptyList()) {
                val neighbor = dep.name
                if (neighbor !in color) continue
                when (color[neighbor]) {
                    white -> {
                        parent[neighbor] = node
                        dfs(neighbor)
                    }
                    gray -> {
                        val cycle = mutableListOf(neighbor)
                        var current = node
                        while (current != neighbor) {
                            cycle.add(current)
                            current = parent[current] ?: break
                        }
                        cycle.reverse()
                        cycles.add(cycle)
                    }
                }
            }
            color[node] = black
        }

        for (node in adjacency.keys.sorted()) {
            if (color[node] == white) {
                parent[node] = null
                dfs(node)
            }
        }

        return cycles
    }

    private fun formatModuleDep(dep: ModuleDependencyInfo): String {
        return if (dep.scope != "COMPILE") {
            "${dep.name} (${dep.scope.lowercase()})"
        } else {
            dep.name
        }
    }
}
