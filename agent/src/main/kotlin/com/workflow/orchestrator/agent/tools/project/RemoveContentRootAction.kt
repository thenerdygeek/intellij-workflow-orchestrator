package com.workflow.orchestrator.agent.tools.project

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.workflow.orchestrator.agent.loop.ApprovalResult
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Removes a content root from a non-external-system IntelliJ module.
 *
 * - Matches by canonical path (normalises relative paths against [Project.basePath]).
 * - If no matching content root found → error listing known roots.
 * - If removal leaves the module with 0 content roots → warns in the success message.
 * - Refuses Gradle/Maven-managed modules.
 * - Risk: HIGH — removing a content root detaches an entire directory tree from the IDE.
 *   Not session-approvable: every invocation requires an explicit approval click.
 *
 * Called from [ProjectStructureTool] for the "remove_content_root" action.
 */
internal suspend fun executeRemoveContentRoot(
    params: JsonObject,
    project: Project,
    tool: AgentTool,
): ToolResult {

    // ── Step 1: extract params ────────────────────────────────────────────────
    val moduleName = params["module"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        ?: return ToolResult(
            content = "Error: 'module' parameter is required for remove_content_root.",
            summary = "Missing 'module' param",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )
    val pathStr = params["path"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        ?: return ToolResult(
            content = "Error: 'path' parameter is required for remove_content_root.",
            summary = "Missing 'path' param",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )

    // ── Step 2: find module ───────────────────────────────────────────────────
    val module = ReadAction.compute<com.intellij.openapi.module.Module?, Throwable> {
        ModuleManager.getInstance(project).findModuleByName(moduleName)
    } ?: return ToolResult(
        content = "Module '$moduleName' not found.",
        summary = "Module '$moduleName' not found",
        tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
        isError = true
    )

    // ── Step 3: external-system guard ─────────────────────────────────────────
    val extId = moduleExternalSystemId(module)
    if (extId != null) {
        return ToolResult(
            content = "Module '$moduleName' is an external system module managed by '$extId'. " +
                "Edit the build file (build.gradle / pom.xml) instead — direct edits are overwritten on reimport.",
            summary = "External-system module '$moduleName'",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )
    }

    // ── Step 4: normalise path ────────────────────────────────────────────────
    val absolute = if (File(pathStr).isAbsolute) {
        File(pathStr).canonicalPath
    } else {
        File(project.basePath ?: "", pathStr).canonicalPath
    }

    // ── Step 5: pre-check — does the content root exist? ─────────────────────
    data class PreCheck(val targetEntry: ContentEntry?, val knownPaths: List<String>)
    val preCheck = ReadAction.compute<PreCheck, Throwable> {
        val rm = ModuleRootManager.getInstance(module)
        val target = rm.contentEntries.firstOrNull {
            it.file?.path?.let { p -> File(p).canonicalPath } == absolute
        }
        val known = rm.contentEntries.mapNotNull { it.file?.path }
        PreCheck(target, known)
    }
    if (preCheck.targetEntry == null) {
        return ToolResult(
            content = "No content root '$absolute' found on module '$moduleName'. " +
                "Known content roots: ${
                    if (preCheck.knownPaths.isEmpty()) "(none)" else preCheck.knownPaths.joinToString(", ")
                }",
            summary = "Content root not found",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )
    }

    // ── Step 6: approval gate ─────────────────────────────────────────────────
    val approval = tool.requestApproval(
        toolName = "project_structure.remove_content_root",
        args = "remove content root '$absolute' from module $moduleName",
        riskLevel = "high",
        allowSessionApproval = false
    )
    if (approval == ApprovalResult.DENIED) {
        return ToolResult(
            content = "Remove content root denied by user.",
            summary = "Approval denied",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )
    }

    // ── Step 7: write action ──────────────────────────────────────────────────
    var remainingCount = -1
    WriteCommandAction.runWriteCommandAction(project, "Agent: remove content root", null, Runnable {
        ModuleRootModificationUtil.updateModel(module) { model ->
            val target = model.contentEntries.firstOrNull {
                it.file?.path?.let { p -> File(p).canonicalPath } == absolute
            }
            if (target != null) {
                model.removeContentEntry(target)
            }
            remainingCount = model.contentEntries.size
        }
    })

    // ── Step 8: result ────────────────────────────────────────────────────────
    val warning = if (remainingCount == 0) " Warning: module '$moduleName' now has 0 content roots." else ""
    return ToolResult(
        content = "Removed content root '$absolute' from module '$moduleName'.$warning",
        summary = "Content root removed from $moduleName",
        tokenEstimate = 20
    )
}
