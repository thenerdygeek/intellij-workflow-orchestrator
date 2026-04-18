package com.workflow.orchestrator.agent.tools.project

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.workflow.orchestrator.agent.loop.ApprovalResult
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Removes all inter-module dependency entries from an owner module to a target module.
 *
 * - Refuses Gradle/Maven-managed modules (edits are overwritten on reimport).
 * - Idempotent: if the dependency doesn't exist, returns non-error "nothing to remove".
 * - Removes ALL matching [ModuleOrderEntry] instances (platform allows duplicate scopes).
 * - Risk: medium, no session approval (cannot be auto-approved for the session).
 *
 * Called from [ProjectStructureTool] for the "remove_module_dependency" action.
 */
internal suspend fun executeRemoveModuleDependency(
    params: JsonObject,
    project: Project,
    tool: AgentTool,
): ToolResult {

    // ── Step 1: extract params ────────────────────────────────────────────────
    val moduleName = params["module"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        ?: return ToolResult(
            content = "Error: 'module' parameter is required for remove_module_dependency.",
            summary = "Missing 'module' param",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )
    val dependsOn = params["dependsOn"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        ?: return ToolResult(
            content = "Error: 'dependsOn' parameter is required for remove_module_dependency.",
            summary = "Missing 'dependsOn' param",
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

    // ── Step 4: pre-check — does the dependency exist? ────────────────────────
    val existingCount = ReadAction.compute<Int, Throwable> {
        ModuleRootManager.getInstance(module).orderEntries
            .filterIsInstance<ModuleOrderEntry>()
            .count { it.moduleName == dependsOn }
    }
    if (existingCount == 0) {
        return ToolResult(
            content = "Module '$moduleName' has no dependency on '$dependsOn'. Nothing to remove.",
            summary = "No dependency on '$dependsOn' found",
            tokenEstimate = 20
        )
    }

    // ── Step 5: approval gate ─────────────────────────────────────────────────
    val approval = tool.requestApproval(
        toolName = "project_structure.remove_module_dependency",
        args = "remove dependency $moduleName → $dependsOn ($existingCount entry/entries)",
        riskLevel = "medium",
        allowSessionApproval = false
    )
    if (approval == ApprovalResult.DENIED) {
        return ToolResult(
            content = "Dependency removal denied by user.",
            summary = "Approval denied",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )
    }

    // ── Step 6: write action ──────────────────────────────────────────────────
    var removedCount = 0
    WriteCommandAction.runWriteCommandAction(project, "Agent: remove module dependency", null, Runnable {
        ModuleRootModificationUtil.updateModel(module) { model ->
            val toRemove = model.orderEntries
                .filterIsInstance<ModuleOrderEntry>()
                .filter { it.moduleName == dependsOn }
            toRemove.forEach { model.removeOrderEntry(it) }
            removedCount = toRemove.size
        }
    })

    // ── Step 7: result ────────────────────────────────────────────────────────
    return ToolResult(
        content = "Removed $removedCount dependency entry/entries from '$moduleName' on '$dependsOn'.",
        summary = "Removed $removedCount dep entry/entries on $dependsOn",
        tokenEstimate = 20
    )
}
