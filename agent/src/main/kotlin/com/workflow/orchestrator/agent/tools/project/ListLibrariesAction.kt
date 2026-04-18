package com.workflow.orchestrator.agent.tools.project

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Implements the `list_libraries` action of [ProjectStructureTool].
 *
 * Lists libraries from the project-level library table and/or the application-level
 * library table depending on the `scope` parameter:
 *  - "project"            — project library table only
 *  - "application"       — application (global) library table only
 *  - "all" (default)     — both tables
 *
 * For each library the class root count and source root count are reported.
 *
 * All IntelliJ model reads run inside [ReadAction.compute] so this function is
 * safe to call from any non-EDT background thread.
 */
internal fun executeListLibraries(params: JsonObject, project: Project): ToolResult {
    val scope = params["scope"]?.jsonPrimitive?.content ?: "all"

    return ReadAction.compute<ToolResult, RuntimeException> {
        val reg = LibraryTablesRegistrar.getInstance()

        data class TableEntry(val tableName: String, val libraries: Array<out Library>)

        val tables: List<TableEntry> = when (scope) {
            "project" -> listOf(
                TableEntry("Project libraries", reg.getLibraryTable(project).libraries)
            )
            "application" -> listOf(
                TableEntry("Application libraries", reg.libraryTable.libraries)
            )
            else -> listOf(
                TableEntry("Project libraries", reg.getLibraryTable(project).libraries),
                TableEntry("Application libraries", reg.libraryTable.libraries)
            )
        }

        val sb = StringBuilder()
        var total = 0

        tables.forEach { entry ->
            sb.appendLine("${entry.tableName} (${entry.libraries.size}):")
            if (entry.libraries.isEmpty()) {
                sb.appendLine("  (none)")
            } else {
                entry.libraries.forEach { lib ->
                    val name = lib.name ?: "<unnamed>"
                    val classRoots = try {
                        lib.getUrls(OrderRootType.CLASSES).size
                    } catch (_: Exception) { 0 }
                    val sourceRoots = try {
                        lib.getUrls(OrderRootType.SOURCES).size
                    } catch (_: Exception) { 0 }
                    sb.appendLine("  - $name [classes: $classRoots, sources: $sourceRoots]")
                    total++
                }
            }
            sb.appendLine()
        }

        val content = sb.toString().trimEnd()
        val summary = "$total library table entr${if (total == 1) "y" else "ies"}"
        ToolResult(content, summary, TokenEstimator.estimate(content))
    }
}
