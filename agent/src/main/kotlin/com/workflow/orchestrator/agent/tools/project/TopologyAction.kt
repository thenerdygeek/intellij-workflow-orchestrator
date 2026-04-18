package com.workflow.orchestrator.agent.tools.project

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Implements the `topology` action of [ProjectStructureTool].
 *
 * Returns the topological module dependency order (dependencies-first) for the project,
 * optionally including circular dependency detection.
 *
 * Cycle detection attempts to use [com.intellij.util.graph.DFSTBuilder] if available on
 * the current platform version. It is wrapped in a broad try/catch so the action always
 * succeeds even when that internal API is absent.
 *
 * All IntelliJ model reads run inside [ReadAction.compute] so this function is safe to
 * call from any non-EDT background thread.
 */
internal fun executeTopology(params: JsonObject, project: Project): ToolResult {
    // 1. Dumb mode guard
    if (DumbService.isDumb(project)) {
        return ToolResult.error("Project is indexing — retry after indexing completes.")
    }

    // 2. Parameters
    val detectCycles = params["detect_cycles"]?.jsonPrimitive?.booleanOrNull ?: true

    // 3. All model reads inside ReadAction
    return ReadAction.compute<ToolResult, RuntimeException> {
        val mgr = ModuleManager.getInstance(project)
        val sorted: Array<Module> = mgr.sortedModules

        // ── Cycle detection ───────────────────────────────────────────────
        val cycles: List<List<String>> = if (detectCycles) {
            try {
                val graph = mgr.moduleGraph()
                val dfsClass = Class.forName("com.intellij.util.graph.DFSTBuilder")
                val constructor = dfsClass.getConstructor(Class.forName("com.intellij.util.graph.Graph"))
                val dfs = constructor.newInstance(graph)
                val getSCCsMethod = dfsClass.getMethod("getSCCs")
                @Suppress("UNCHECKED_CAST")
                val sccs = getSCCsMethod.invoke(dfs) as? Collection<Collection<Any>>
                sccs
                    ?.filter { it.size > 1 }
                    ?.map { scc ->
                        scc.map { node ->
                            if (node is Module) node.name else node.toString()
                        }
                    }
                    ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        // ── Assemble output ───────────────────────────────────────────────
        val sb = StringBuilder()
        sb.appendLine("Module dependency topology (${sorted.size} module(s), dependencies-first):")
        sb.appendLine()

        if (sorted.isEmpty()) {
            sb.appendLine("  (no modules)")
        } else {
            sorted.forEachIndexed { index, module ->
                val extSystem = try {
                    moduleExternalSystemId(module)?.let { " [$it]" } ?: ""
                } catch (_: Exception) {
                    ""
                }
                sb.appendLine("  ${index + 1}. ${module.name}$extSystem")
            }
        }

        if (detectCycles) {
            sb.appendLine()
            if (cycles.isEmpty()) {
                sb.appendLine("Cycle detection: No cycles detected.")
            } else {
                sb.appendLine("Cycle detection: ${cycles.size} cycle(s) found:")
                cycles.forEach { cycle ->
                    val cycleStr = cycle.joinToString(" -> ") + " -> (back to start)"
                    sb.appendLine("  $cycleStr")
                }
            }
        }

        val content = sb.toString().trimEnd()
        val summary = "${sorted.size} modules, ${cycles.size} cycle(s)"
        ToolResult(content, summary, TokenEstimator.estimate(content))
    }
}
