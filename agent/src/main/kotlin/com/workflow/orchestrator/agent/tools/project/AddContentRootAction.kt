package com.workflow.orchestrator.agent.tools.project

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.workflow.orchestrator.agent.loop.ApprovalResult
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Adds a content root to a non-external-system IntelliJ module.
 *
 * - Path must exist on disk and be a directory.
 * - Idempotent: if already a content root of this module, returns non-error without re-adding.
 * - Refuses paths that overlap (contain or are contained by) an existing content root.
 * - Refuses Gradle/Maven-managed modules.
 * - Risk: low, session-approvable.
 *
 * Called from [ProjectStructureTool] for the "add_content_root" action.
 */
internal suspend fun executeAddContentRoot(
    params: JsonObject,
    project: Project,
    tool: AgentTool,
): ToolResult {

    // ── Step 1: extract params ────────────────────────────────────────────────
    val moduleName = params["module"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        ?: return ToolResult(
            content = "Error: 'module' parameter is required for add_content_root.",
            summary = "Missing 'module' param",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )
    val pathStr = params["path"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        ?: return ToolResult(
            content = "Error: 'path' parameter is required for add_content_root.",
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

    // ── Step 4: resolve path, check existence and directory ──────────────────
    val absolute = if (File(pathStr).isAbsolute) {
        File(pathStr).canonicalPath
    } else {
        File(project.basePath ?: "", pathStr).canonicalPath
    }
    val vFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(absolute))
        ?: return ToolResult(
            content = "Path not found: $absolute. Create the directory first.",
            summary = "Path not found: $absolute",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )
    if (!vFile.isDirectory) {
        return ToolResult(
            content = "Path is not a directory: $absolute.",
            summary = "Not a directory: $absolute",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )
    }

    // ── Step 5: pre-check — already a content root or overlapping ────────────
    data class OverlapResult(val isExact: Boolean, val overlapPath: String?)
    val overlapResult = ReadAction.compute<OverlapResult, Throwable> {
        val entries = ModuleRootManager.getInstance(module).contentEntries
        val exactMatch = entries.any { it.file?.path == vFile.path }
        if (exactMatch) return@compute OverlapResult(true, vFile.path)
        val overlapping = entries.mapNotNull { it.file }.firstOrNull { existing ->
            VfsUtilCore.isAncestor(existing, vFile, false) ||
                VfsUtilCore.isAncestor(vFile, existing, false)
        }
        OverlapResult(false, overlapping?.path)
    }
    if (overlapResult.isExact) {
        return ToolResult(
            content = "Content root '$absolute' is already registered on module '$moduleName'. No change.",
            summary = "Content root already present",
            tokenEstimate = 20
        )
    }
    if (overlapResult.overlapPath != null) {
        return ToolResult(
            content = "Cannot add '$absolute': it overlaps with existing content root '${overlapResult.overlapPath}'. " +
                "Remove the overlapping root first or choose a non-overlapping path.",
            summary = "Overlapping content root",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )
    }

    // ── Step 6: approval gate ─────────────────────────────────────────────────
    val rel = relativizeToProject(absolute, project.basePath)
    val approval = tool.requestApproval(
        toolName = "project_structure.add_content_root",
        args = "add content root '$rel' to module $moduleName",
        riskLevel = "low",
        allowSessionApproval = true
    )
    if (approval == ApprovalResult.DENIED) {
        return ToolResult(
            content = "Add content root denied by user.",
            summary = "Approval denied",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )
    }

    // ── Step 7: write action ──────────────────────────────────────────────────
    WriteCommandAction.runWriteCommandAction(project, "Agent: add content root", null, Runnable {
        ModuleRootModificationUtil.updateModel(module) { model ->
            model.addContentEntry(vFile)
        }
    })

    // ── Step 8: result ────────────────────────────────────────────────────────
    return ToolResult(
        content = "Added content root '$rel' to module '$moduleName'.",
        summary = "Content root added to $moduleName",
        tokenEstimate = 20
    )
}
