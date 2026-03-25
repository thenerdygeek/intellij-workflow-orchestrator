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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

class ModuleDependencyGraphTool : AgentTool {
    override val name = "module_dependency_graph"
    override val description = "Get the project's module dependency graph. Shows direct and transitive dependencies between modules, detects circular dependencies, and optionally lists library dependencies."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "module" to ParameterProperty(
                type = "string",
                description = "Specific module name to show dependencies for. Shows all modules if omitted."
            ),
            "transitive" to ParameterProperty(
                type = "boolean",
                description = "Include transitive (indirect) dependencies. Default: false."
            ),
            "include_libraries" to ParameterProperty(
                type = "boolean",
                description = "Include library dependencies in the output. Default: false."
            ),
            "detect_cycles" to ParameterProperty(
                type = "boolean",
                description = "Run circular dependency detection. Default: true."
            )
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.REVIEWER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
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

            // Filter to requested module if specified
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

            // Build adjacency list for all modules (needed for cycle detection)
            val adjacency = ReadAction.compute<Map<String, List<DependencyInfo>>, Throwable> {
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
                            if (directDeps.isNotEmpty()) {
                                parts.add("${directDeps.size} direct")
                            }
                            if (transitiveOnly.isNotEmpty()) {
                                parts.add("${transitiveOnly.size} transitive")
                            }
                            appendLine("${module.name} (${parts.joinToString(", ")})")
                            if (directDeps.isNotEmpty()) {
                                appendLine("  Direct: [${directDeps.joinToString(", ") { formatDep(it) }}]")
                            }
                            if (transitiveOnly.isNotEmpty()) {
                                appendLine("  Transitive: [${transitiveOnly.sorted().joinToString(", ")}]")
                            }
                        }
                    } else {
                        val depCount = directDeps.size
                        if (directDeps.isEmpty()) {
                            appendLine("${module.name} (0 module deps)")
                        } else {
                            append("${module.name}")
                            appendLine(" → [${directDeps.joinToString(", ") { formatDep(it) }}]")
                        }
                    }

                    // Library dependencies
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

                // Cycle detection
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

    private fun buildAdjacencyList(modules: Array<Module>): Map<String, List<DependencyInfo>> {
        val result = mutableMapOf<String, List<DependencyInfo>>()
        for (module in modules) {
            val deps = mutableListOf<DependencyInfo>()
            val rootManager = ModuleRootManager.getInstance(module)
            for (entry in rootManager.orderEntries) {
                if (entry is ModuleOrderEntry) {
                    val depModule = entry.module
                    if (depModule != null) {
                        deps.add(DependencyInfo(
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
        adjacency: Map<String, List<DependencyInfo>>
    ): Set<String> {
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()

        // Start with direct deps
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
                // Clean up library name — remove "Gradle: " or "Maven: " prefix
                val cleanName = name
                    .removePrefix("Gradle: ")
                    .removePrefix("Maven: ")
                    .substringAfterLast(":")  // Keep just artifact name if GAV
                    .ifBlank { name }
                libraries.add(cleanName)
            }
            true // continue
        }
        return libraries.sorted().distinct()
    }

    /**
     * Detect cycles using DFS with coloring (WHITE=unvisited, GRAY=in-stack, BLACK=done).
     * Returns list of cycles, each as a list of module names forming the cycle.
     */
    internal fun detectCycles(adjacency: Map<String, List<DependencyInfo>>): List<List<String>> {
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
                if (neighbor !in color) continue // not a project module
                when (color[neighbor]) {
                    white -> {
                        parent[neighbor] = node
                        dfs(neighbor)
                    }
                    gray -> {
                        // Back edge found — reconstruct cycle
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

    private fun formatDep(dep: DependencyInfo): String {
        return if (dep.scope != "COMPILE") {
            "${dep.name} (${dep.scope.lowercase()})"
        } else {
            dep.name
        }
    }

    internal data class DependencyInfo(
        val name: String,
        val scope: String = "COMPILE"
    )
}
