package com.workflow.orchestrator.agent.tools.framework.build

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

private data class ModuleDependencyInfo(
    val name: String,
    val scope: String = "COMPILE"
)

internal fun executeModuleDependencyGraph(params: JsonObject, project: Project): ToolResult {
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
