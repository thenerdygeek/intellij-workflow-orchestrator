package com.workflow.orchestrator.agent.tools.project

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.workflow.orchestrator.agent.loop.ApprovalResult
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Sets or clears the SDK for an IntelliJ module.
 *
 * - Empty/omitted [sdkName] → inherit from project (calls [ModifiableRootModel.inheritSdk]).
 * - Non-empty [sdkName] → look up in [ProjectJdkTable]; unknown name → error listing available SDKs.
 * - Refuses Gradle/Maven-managed modules.
 * - Risk: medium, not session-approvable (SDK changes affect compilation; should be confirmed per-use).
 *
 * Called from [ProjectStructureTool] for the "set_module_sdk" action.
 */
internal suspend fun executeSetModuleSdk(
    params: JsonObject,
    project: Project,
    tool: AgentTool,
): ToolResult {

    // ── Step 1: extract params ────────────────────────────────────────────────
    val moduleName = params["module"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        ?: return ToolResult(
            content = "Error: 'module' parameter is required for set_module_sdk.",
            summary = "Missing 'module' param",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )
    val sdkNameRaw = params["sdkName"]?.jsonPrimitive?.content
    val inherit = sdkNameRaw.isNullOrBlank()

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

    // ── Step 4: resolve SDK (if not inheriting) ───────────────────────────────
    val sdk: Sdk? = if (inherit) {
        null
    } else {
        ProjectJdkTable.getInstance().findJdk(sdkNameRaw!!)
            ?: run {
                val available = ProjectJdkTable.getInstance().allJdks.map { it.name }.sorted()
                return ToolResult(
                    content = "SDK '$sdkNameRaw' not found. Available SDKs: ${
                        if (available.isEmpty()) "(none configured)" else available.joinToString(", ")
                    }",
                    summary = "SDK '$sdkNameRaw' not found",
                    tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
            }
    }

    // ── Step 5: approval gate ─────────────────────────────────────────────────
    val descr = if (inherit) "inherit from project" else "SDK=$sdkNameRaw"
    val approval = tool.requestApproval(
        toolName = "project_structure.set_module_sdk",
        args = "set module $moduleName SDK to: $descr",
        riskLevel = "medium",
        allowSessionApproval = false
    )
    if (approval == ApprovalResult.DENIED) {
        return ToolResult(
            content = "SDK change denied by user.",
            summary = "Approval denied",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )
    }

    // ── Step 6: write action ──────────────────────────────────────────────────
    WriteCommandAction.runWriteCommandAction(project, "Agent: set module SDK", null, Runnable {
        ModuleRootModificationUtil.updateModel(module) { model ->
            if (inherit) model.inheritSdk() else model.sdk = sdk
        }
    })

    // ── Step 7: result ────────────────────────────────────────────────────────
    val resultDescr = if (inherit) "inherit from project" else sdkNameRaw!!
    return ToolResult(
        content = "Module '$moduleName' SDK set to: $resultDescr.",
        summary = "SDK updated to $resultDescr",
        tokenEstimate = 20
    )
}
