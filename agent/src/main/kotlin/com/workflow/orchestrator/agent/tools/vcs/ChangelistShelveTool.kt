package com.workflow.orchestrator.agent.tools.vcs

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.ChangeListManagerImpl
import com.intellij.openapi.vcs.changes.shelf.ShelveChangesManager
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.*

class ChangelistShelveTool : AgentTool {
    override val name = "changelist_shelve"
    override val description = "Manage VCS changelists and shelve/unshelve changes. " +
        "Actions: list (changelists), list_shelves, create (changelist), shelve (current changes), unshelve (shelved changes). " +
        "Shelving saves your changes and reverts the working tree."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "The action to perform",
                enumValues = listOf("list", "list_shelves", "create", "shelve", "unshelve")
            ),
            "name" to ParameterProperty(
                type = "string",
                description = "Changelist name (for create action)"
            ),
            "comment" to ParameterProperty(
                type = "string",
                description = "Description (for create/shelve actions)"
            ),
            "shelf_index" to ParameterProperty(
                type = "integer",
                description = "0-based index of shelf to unshelve (from list_shelves output)"
            )
        ),
        required = listOf("action")
    )
    override val allowedWorkers = setOf(WorkerType.CODER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val action = params["action"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(
                "Missing required parameter: action",
                "Error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        return try {
            when (action) {
                "list" -> listChangelists(project)
                "list_shelves" -> listShelves(project)
                "create" -> createChangelist(project, params)
                "shelve" -> shelveChanges(project, params)
                "unshelve" -> unshelveChanges(project, params)
                else -> ToolResult(
                    "Invalid action '$action'. Must be one of: list, list_shelves, create, shelve, unshelve",
                    "Error",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
            }
        } catch (e: Exception) {
            ToolResult(
                "Error executing changelist_shelve ($action): ${e.message}",
                "Error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    private fun listChangelists(project: Project): ToolResult {
        val content = ReadAction.compute<String, Exception> {
            val clm = ChangeListManager.getInstance(project)
            val changeLists = clm.changeLists

            if (changeLists.isEmpty()) {
                return@compute "No changelists found."
            }

            buildString {
                appendLine("Changelists (${changeLists.size}):")
                changeLists.forEachIndexed { index, cl ->
                    val defaultMarker = if (cl.isDefault) "[DEFAULT] " else ""
                    val fileCount = cl.changes.size
                    val filesDesc = if (fileCount == 1) "1 file modified" else "$fileCount files modified"
                    appendLine("  ${index + 1}. $defaultMarker${cl.name} — $filesDesc")
                }
            }
        }

        return ToolResult(
            content = content,
            summary = "Listed changelists",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }

    private fun listShelves(project: Project): ToolResult {
        val shelveManager = ShelveChangesManager.getInstance(project)
        val shelves = shelveManager.shelvedChangeLists

        if (shelves.isEmpty()) {
            return ToolResult("No shelved changes found.", "No shelves", 5)
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val content = buildString {
            appendLine("Shelved changes (${shelves.size}):")
            shelves.forEachIndexed { index, shelf ->
                val dateStr = shelf.date?.let { dateFormat.format(it) } ?: "unknown date"
                val description = shelf.description?.takeIf { it.isNotBlank() } ?: "untitled"
                @Suppress("DEPRECATION")
                val fileCount = shelf.getChanges(project)?.size ?: 0
                val filesDesc = if (fileCount == 1) "1 file" else "$fileCount files"
                appendLine("  $index. $description ($dateStr) — $filesDesc")
            }
        }

        return ToolResult(
            content = content,
            summary = "Listed ${shelves.size} shelved changelists",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }

    private fun createChangelist(project: Project, params: JsonObject): ToolResult {
        val name = params["name"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(
                "Missing required parameter: name (for create action)",
                "Error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val comment = params["comment"]?.jsonPrimitive?.contentOrNull ?: ""

        val clm = ChangeListManager.getInstance(project) as ChangeListManagerImpl
        val newList = clm.addChangeList(name, comment)

        val content = "Created changelist '${newList.name}'." +
            if (comment.isNotBlank()) " Comment: $comment" else ""

        return ToolResult(
            content = content,
            summary = "Created changelist '$name'",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }

    private fun shelveChanges(project: Project, params: JsonObject): ToolResult {
        val clm = ChangeListManager.getInstance(project)
        val allChanges = clm.allChanges.toList()

        if (allChanges.isEmpty()) {
            return ToolResult("No changes to shelve.", "No changes", 5)
        }

        val comment = params["comment"]?.jsonPrimitive?.contentOrNull ?: "Shelved changes"

        val shelveManager = ShelveChangesManager.getInstance(project)
        val shelvedList = shelveManager.shelveChanges(allChanges, comment, true)

        val content = buildString {
            appendLine("Shelved ${allChanges.size} file(s) as '${shelvedList.description ?: comment}'.")
            appendLine("Working tree has been reverted for shelved files.")
        }

        return ToolResult(
            content = content,
            summary = "Shelved ${allChanges.size} files",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }

    private fun unshelveChanges(project: Project, params: JsonObject): ToolResult {
        val shelfIndex = params["shelf_index"]?.jsonPrimitive?.intOrNull
            ?: return ToolResult(
                "Missing required parameter: shelf_index (for unshelve action). Use list_shelves to see available indices.",
                "Error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val shelveManager = ShelveChangesManager.getInstance(project)
        val shelves = shelveManager.shelvedChangeLists

        if (shelfIndex < 0 || shelfIndex >= shelves.size) {
            return ToolResult(
                "Invalid shelf_index $shelfIndex. Available range: 0..${shelves.size - 1} (${shelves.size} shelves).",
                "Error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        val shelf = shelves[shelfIndex]
        val clm = ChangeListManager.getInstance(project)
        val targetChangeList = clm.defaultChangeList

        shelveManager.unshelveChangeList(shelf, null, null, targetChangeList, true)

        val description = shelf.description?.takeIf { it.isNotBlank() } ?: "untitled"
        val content = "Unshelved '$description' into changelist '${targetChangeList.name}'."

        return ToolResult(
            content = content,
            summary = "Unshelved '$description'",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }
}
