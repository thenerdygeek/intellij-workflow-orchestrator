package com.workflow.orchestrator.agent.tools.project

import com.intellij.facet.FacetManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Implements the `list_facets` action of [ProjectStructureTool].
 *
 * Lists all facets (Spring, Android, JPA, etc.) attached to modules in the project.
 *
 * If `module` parameter is present, only that module is inspected; otherwise all
 * modules are iterated in alphabetical order.
 *
 * All IntelliJ model reads run inside [ReadAction.compute] so this function is
 * safe to call from any non-EDT background thread.
 *
 * Summary uses natural pluralisation (facet/facets, module/modules) rather than
 * the literal module(s) template form.
 */
internal fun executeListFacets(params: JsonObject, project: Project): ToolResult {
    val moduleName = params["module"]?.jsonPrimitive?.content

    return ReadAction.compute<ToolResult, RuntimeException> {
        val modules: List<Module> = if (moduleName != null) {
            val found = ModuleManager.getInstance(project).findModuleByName(moduleName)
                ?: return@compute ToolResult.error("Module not found: '$moduleName'")
            listOf(found)
        } else {
            ModuleManager.getInstance(project).modules.toList().sortedBy { it.name }
        }

        val sb = StringBuilder()
        var totalFacets = 0

        modules.forEach { module ->
            val facets = FacetManager.getInstance(module).allFacets
            sb.appendLine("Module: ${module.name}")
            if (facets.isEmpty()) {
                sb.appendLine("  (no facets)")
            } else {
                facets.forEach { f ->
                    val typeId = try { f.type.stringId } catch (_: Exception) { "?" }
                    sb.appendLine("  ${f.name} [${f.type.presentableName}] (type id: $typeId)")
                    totalFacets++
                }
            }
            sb.appendLine()
        }

        val content = sb.toString().trimEnd()
        val summary = "$totalFacets facet${if (totalFacets == 1) "" else "s"} across ${modules.size} module${if (modules.size == 1) "" else "s"}"
        ToolResult(content, summary, TokenEstimator.estimate(content))
    }
}
