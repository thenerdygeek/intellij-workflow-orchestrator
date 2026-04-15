package com.workflow.orchestrator.agent.tools.project

import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.loop.ApprovalResult
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import java.io.File

/**
 * Write action: triggers an external system (Maven/Gradle) reimport for linked roots.
 *
 * 1. Collects all linked external project roots via [ExternalSystemApiUtil].
 * 2. If none are linked, returns a non-error "nothing to refresh" result immediately.
 * 3. Requests user approval via [AgentTool.requestApproval] — denied ⇒ error result.
 * 4. Filters to the requested `path` root when provided; no match ⇒ error result.
 * 5. Triggers [ExternalSystemUtil.refreshProject] for each target root in the background.
 *
 * This function is called from [ProjectStructureTool] for the "refresh_external_project" action.
 */
internal suspend fun executeRefreshExternalProject(
    params: JsonObject,
    project: Project,
    tool: AgentTool
): ToolResult {

    // ── Step 1: gather all linked external roots ─────────────────────────────
    val rootsPerSystem: List<Pair<ProjectSystemId, List<String>>> = try {
        ExternalSystemApiUtil.getAllManagers().mapNotNull { manager ->
            val systemId = manager.getSystemId()
            val roots: List<String> = try {
                ExternalSystemApiUtil.getSettings(project, systemId)
                    .linkedProjectsSettings
                    .mapNotNull { it.externalProjectPath }
                    .filter { it.isNotBlank() }
            } catch (_: Exception) {
                emptyList()
            }
            if (roots.isEmpty()) null else systemId to roots
        }
    } catch (_: Exception) {
        // ExternalSystem APIs unavailable (e.g., headless test environment without the plugin)
        return ToolResult(
            content = "No external project roots are linked. Nothing to refresh.",
            summary = "No external roots",
            tokenEstimate = 10,
            isError = false
        )
    }

    // ── Step 2: nothing linked ────────────────────────────────────────────────
    if (rootsPerSystem.isEmpty()) {
        return ToolResult(
            content = "No external project roots are linked (no Gradle or Maven import). Nothing to refresh.",
            summary = "No external roots",
            tokenEstimate = 10,
            isError = false
        )
    }

    // ── Step 3: approval gate ─────────────────────────────────────────────────
    val path = params["path"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
    val systemNames = rootsPerSystem.joinToString(", ") { (id, _) -> id.readableName }
    val argsDescription = "refresh ${if (path != null) "root=$path" else "all external roots"} ($systemNames)"

    val approval = tool.requestApproval(
        toolName = "project_structure.refresh_external_project",
        args = argsDescription,
        riskLevel = "medium",
        allowSessionApproval = true
    )
    if (approval == ApprovalResult.DENIED) {
        return ToolResult(
            content = "Refresh denied by user.",
            summary = "Approval denied",
            tokenEstimate = 20,
            isError = true
        )
    }

    // ── Step 4: determine targets ─────────────────────────────────────────────
    val targets: List<Pair<ProjectSystemId, String>> = if (path != null) {
        val normalized = File(path).let {
            if (it.isAbsolute) it.canonicalPath else File(project.basePath ?: "", path).canonicalPath
        }
        val matched = rootsPerSystem.flatMap { (systemId, roots) ->
            roots.filter { root ->
                val canonicalRoot = File(root).canonicalPath
                normalized == canonicalRoot || normalized.startsWith("$canonicalRoot/")
            }.map { root -> systemId to root }
        }
        if (matched.isEmpty()) {
            return ToolResult(
                content = "Path '$path' does not match any linked external project root. " +
                    "Known roots: ${rootsPerSystem.flatMap { it.second }.joinToString(", ")}",
                summary = "No matching root for '$path'",
                tokenEstimate = 20,
                isError = true
            )
        }
        matched
    } else {
        rootsPerSystem.flatMap { (systemId, roots) -> roots.map { systemId to it } }
    }

    // ── Step 5: trigger refresh for each target ───────────────────────────────
    val triggered = mutableListOf<String>()
    val warnings = mutableListOf<String>()

    for ((systemId, root) in targets) {
        try {
            ExternalSystemUtil.refreshProject(
                root,
                ImportSpecBuilder(project, systemId)
                    .use(ProgressExecutionMode.IN_BACKGROUND_ASYNC)
                    .build()
            )
            triggered.add("${systemId.readableName}: $root")
        } catch (e: Exception) {
            warnings.add("Warning: could not trigger refresh for $root (${systemId.readableName}): ${e.message}")
        }
    }

    // ── Step 6: result ────────────────────────────────────────────────────────
    val sb = StringBuilder()
    sb.appendLine("Refresh triggered for ${triggered.size} root(s):")
    triggered.forEach { sb.appendLine("  • $it") }
    if (warnings.isNotEmpty()) {
        sb.appendLine()
        warnings.forEach { sb.appendLine(it) }
    }
    sb.append("Refresh runs in the background — recheck module detail after a moment.")

    return ToolResult(
        content = sb.toString(),
        summary = "${targets.size} refresh(es) triggered",
        tokenEstimate = (sb.length / 4) + 1
    )
}
