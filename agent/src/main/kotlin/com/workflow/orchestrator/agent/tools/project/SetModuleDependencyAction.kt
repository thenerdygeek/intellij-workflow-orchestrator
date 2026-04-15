package com.workflow.orchestrator.agent.tools.project

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.DependencyScope
import com.intellij.openapi.roots.ModuleOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.workflow.orchestrator.agent.loop.ApprovalResult
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Adds (or updates the scope/exported flag of) an inter-module dependency.
 *
 * - Refuses self-dependency.
 * - Refuses Gradle/Maven-managed modules (edits are overwritten on reimport).
 * - No-op when the identical dependency (same scope) already exists.
 * - When the same target exists with a different scope, updates in place.
 *
 * Called from [ProjectStructureTool] for the "set_module_dependency" action.
 */
internal suspend fun executeSetModuleDependency(
    params: JsonObject,
    project: Project,
    tool: AgentTool,
): ToolResult {

    // ── Step 1: extract params ────────────────────────────────────────────────
    val moduleName = params["module"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        ?: return ToolResult(
            content = "Error: 'module' parameter is required for set_module_dependency.",
            summary = "Missing 'module' param",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )
    val dependsOn = params["dependsOn"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        ?: return ToolResult(
            content = "Error: 'dependsOn' parameter is required for set_module_dependency.",
            summary = "Missing 'dependsOn' param",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )
    if (moduleName == dependsOn) {
        return ToolResult(
            content = "Module '$moduleName' cannot depend on itself.",
            summary = "Self-dependency refused",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )
    }
    val scopeStr = params["scope"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "compile"
    val scope = parseDependencyScope(scopeStr)
        ?: return ToolResult(
            content = "Unknown scope '$scopeStr'. Expected one of: compile, test, runtime, provided.",
            summary = "Invalid scope",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )
    val exported = params["exported"]?.jsonPrimitive?.booleanOrNull ?: false

    // ── Step 2: find both modules ─────────────────────────────────────────────
    data class ModulePair(val owner: Module, val target: Module)
    val found: ModulePair? = ReadAction.compute<ModulePair?, Throwable> {
        val mgr = ModuleManager.getInstance(project)
        val owner = mgr.findModuleByName(moduleName) ?: return@compute null
        val target = mgr.findModuleByName(dependsOn) ?: return@compute null
        ModulePair(owner, target)
    }
    if (found == null) {
        val available = ReadAction.compute<List<String>, Throwable> {
            ModuleManager.getInstance(project).modules.map { it.name }.sorted()
        }
        return ToolResult(
            content = "Module '$moduleName' or '$dependsOn' not found. Available modules: ${available.joinToString(", ")}",
            summary = "Module not found",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )
    }
    val (owner, target) = found

    // ── Step 3: external-system guard ─────────────────────────────────────────
    val extId = moduleExternalSystemId(owner)
    if (extId != null) {
        return ToolResult(
            content = "Module '$moduleName' is an external system module managed by '$extId'. " +
                "Edit the build file (build.gradle / pom.xml) instead — direct edits are overwritten on reimport.",
            summary = "External-system module '$moduleName'",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )
    }

    // ── Step 4: pre-check existing state ──────────────────────────────────────
    val existingScope: DependencyScope? = ReadAction.compute<DependencyScope?, Throwable> {
        ModuleRootManager.getInstance(owner).orderEntries
            .filterIsInstance<ModuleOrderEntry>()
            .firstOrNull { it.moduleName == dependsOn }
            ?.scope
    }
    if (existingScope == scope) {
        return ToolResult(
            content = "Dependency '$moduleName' → '$dependsOn' (scope=${dependencyScopeLabel(scope)}) already exists. No change.",
            summary = "Dependency already present",
            tokenEstimate = 20
        )
    }

    // ── Step 5: approval gate ─────────────────────────────────────────────────
    val approval = tool.requestApproval(
        toolName = "project_structure.set_module_dependency",
        args = "set dependency $moduleName → $dependsOn (scope=${dependencyScopeLabel(scope)}, exported=$exported)",
        riskLevel = "medium",
        allowSessionApproval = true
    )
    if (approval == ApprovalResult.DENIED) {
        return ToolResult(
            content = "Dependency change denied by user.",
            summary = "Approval denied",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )
    }

    // ── Step 6: write action ──────────────────────────────────────────────────
    WriteCommandAction.runWriteCommandAction(project, "Agent: set module dependency", null, Runnable {
        ModuleRootModificationUtil.updateModel(owner) { model ->
            val existing = model.orderEntries
                .filterIsInstance<ModuleOrderEntry>()
                .firstOrNull { it.moduleName == dependsOn }
            if (existing != null) {
                existing.scope = scope
                existing.isExported = exported
            } else {
                val entry = model.addModuleOrderEntry(target)
                entry.scope = scope
                entry.isExported = exported
            }
        }
    })

    // ── Step 7: result ────────────────────────────────────────────────────────
    val verb = if (existingScope == null) "Added" else "Updated"
    return ToolResult(
        content = "$verb dependency $moduleName → $dependsOn (scope=${dependencyScopeLabel(scope)}, exported=$exported).",
        summary = "$verb dependency on $dependsOn",
        tokenEstimate = 20
    )
}
