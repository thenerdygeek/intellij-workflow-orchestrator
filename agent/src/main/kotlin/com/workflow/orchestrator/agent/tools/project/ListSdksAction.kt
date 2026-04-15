package com.workflow.orchestrator.agent.tools.project

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.ProjectRootManager
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.serialization.json.JsonObject

/**
 * Implements the `list_sdks` action of [ProjectStructureTool].
 *
 * Lists all SDKs registered in the global [ProjectJdkTable] along with the
 * project-level SDK selected via [ProjectRootManager].
 *
 * All IntelliJ model reads run inside [ReadAction.compute] so this function is
 * safe to call from any non-EDT background thread.
 */
internal fun executeListSdks(params: JsonObject, project: Project): ToolResult {
    return ReadAction.compute<ToolResult, RuntimeException> {
        val sdks = ProjectJdkTable.getInstance().allJdks
        val projectSdk = ProjectRootManager.getInstance(project).projectSdk

        val sb = StringBuilder()

        if (sdks.isEmpty()) {
            sb.appendLine("Registered SDKs: (none)")
        } else {
            sb.appendLine("Registered SDKs (${sdks.size}):")
            sdks.forEach { sdk ->
                val typeName = try { sdk.sdkType.name } catch (_: Exception) { "unknown" }
                val version = sdk.versionString ?: "unknown version"
                sb.appendLine("  - ${sdk.name} [type: $typeName, version: $version]")
            }
        }

        sb.appendLine()
        val projectSdkStr = projectSdk?.name ?: "not set"
        sb.appendLine("Project SDK: $projectSdkStr")

        val content = sb.toString().trimEnd()
        val summary = "${sdks.size} SDK(s); project SDK=${projectSdk?.name ?: "none"}"
        ToolResult(content, summary, TokenEstimator.estimate(content))
    }
}
