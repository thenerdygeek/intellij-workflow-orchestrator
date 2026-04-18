package com.workflow.orchestrator.agent.tools.project

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.pom.java.LanguageLevel
import com.workflow.orchestrator.agent.loop.ApprovalResult
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Sets the Java language level on an IntelliJ module via
 * `com.intellij.openapi.roots.LanguageLevelModuleExtension` (accessed by reflection, as
 * the `Impl` class lives in an internal package not available at compile time in the plugin SDK).
 *
 * - Empty/omitted [languageLevel] → inherit from project (sets extension level to null).
 * - Parses user-friendly inputs ("8", "11", "17", "21", "JDK_17", "JDK_17_PREVIEW") via
 *   [parseLanguageLevel] helper in ProjectStructureHelpers.
 * - Refuses Gradle/Maven-managed modules.
 * - Risk: low, session-approvable (language level is easy to undo).
 *
 * Called from [ProjectStructureTool] for the "set_language_level" action.
 */
internal suspend fun executeSetLanguageLevel(
    params: JsonObject,
    project: Project,
    tool: AgentTool,
): ToolResult {

    // ── Step 1: extract params ────────────────────────────────────────────────
    val moduleName = params["module"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        ?: return ToolResult(
            content = "Error: 'module' parameter is required for set_language_level.",
            summary = "Missing 'module' param",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )
    val levelRaw = params["languageLevel"]?.jsonPrimitive?.content
    val inherit = levelRaw.isNullOrBlank()

    // ── Step 2: parse language level (if not inheriting) ─────────────────────
    val level: LanguageLevel? = if (inherit) {
        null
    } else {
        parseLanguageLevel(levelRaw)
            ?: return ToolResult(
                content = "Unknown language level '$levelRaw'. " +
                    "Use a number ('8', '11', '17', '21') or canonical name ('JDK_17', 'JDK_17_PREVIEW').",
                summary = "Invalid language level '$levelRaw'",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
    }

    // ── Step 3: find module ───────────────────────────────────────────────────
    val module = ReadAction.compute<com.intellij.openapi.module.Module?, Throwable> {
        ModuleManager.getInstance(project).findModuleByName(moduleName)
    } ?: return ToolResult(
        content = "Module '$moduleName' not found.",
        summary = "Module '$moduleName' not found",
        tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
        isError = true
    )

    // ── Step 4: external-system guard ─────────────────────────────────────────
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

    // ── Step 5: approval gate ─────────────────────────────────────────────────
    val descr = if (inherit) "inherit from project" else level!!.name
    val approval = tool.requestApproval(
        toolName = "project_structure.set_language_level",
        args = "set module $moduleName language level to: $descr",
        riskLevel = "low",
        allowSessionApproval = true
    )
    if (approval == ApprovalResult.DENIED) {
        return ToolResult(
            content = "Language-level change denied by user.",
            summary = "Approval denied",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )
    }

    // ── Step 6: write action (reflection — LanguageLevelModuleExtensionImpl not on compile CP) ──
    var extensionError: String? = null
    WriteCommandAction.runWriteCommandAction(project, "Agent: set language level", null, Runnable {
        ModuleRootModificationUtil.updateModel(module) { model ->
            extensionError = applyLanguageLevelToModule(model, level, moduleName)
        }
    })

    // ── Step 7: result ────────────────────────────────────────────────────────
    if (extensionError != null) {
        return ToolResult(
            content = extensionError!!,
            summary = "Language level extension unavailable",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )
    }
    return ToolResult(
        content = "Module '$moduleName' language level set to: $descr.",
        summary = "Language level set to $descr",
        tokenEstimate = 20
    )
}

/**
 * Applies [level] (null = inherit from project) to the `LanguageLevelModuleExtension`
 * on [model] via reflection. The extension's `Impl` class lives in an internal IntelliJ
 * package not available at plugin compile time.
 *
 * Returns `null` on success, or a non-null error message string on failure.
 * Extracted as a package-level function so tests can mock it via `mockkStatic`.
 */
internal fun applyLanguageLevelToModule(
    model: com.intellij.openapi.roots.ModifiableRootModel,
    level: LanguageLevel?,
    moduleName: String,
): String? {
    return try {
        val extClass = Class.forName("com.intellij.openapi.roots.LanguageLevelModuleExtension")
        val getExtMethod = model.javaClass.getMethod("getModuleExtension", Class::class.java)
        val ext = getExtMethod.invoke(model, extClass)
            ?: return "LanguageLevelModuleExtension not present on module '$moduleName' — module may not be a Java module."
        val setLevelMethod = ext.javaClass.getMethod("setLanguageLevel", LanguageLevel::class.java)
        setLevelMethod.invoke(ext, level) // null = inherit from project
        null // success
    } catch (e: Exception) {
        "Failed to set language level via reflection: ${e.message}"
    }
}
