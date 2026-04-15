package com.workflow.orchestrator.agent.tools.project

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.workflow.orchestrator.agent.loop.ApprovalResult
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Write action: adds a typed source root to an IntelliJ module content entry.
 *
 * Steps:
 * 1. Extract and validate required params: module, path, kind.
 * 2. Parse kind via [parseSourceRootKind] — invalid kind → error.
 * 3. Find module via [ModuleManager] in a ReadAction — not found → error.
 * 4. Guard against external-system modules via [moduleExternalSystemId] — owned → error.
 * 5. Resolve path to absolute; look up VirtualFile via [LocalFileSystem].
 * 6. Find containing ContentEntry in a ReadAction — none → error.
 * 7. Request user approval via [AgentTool.requestApproval] — denied → error.
 * 8. Execute [ModuleRootModificationUtil.updateModel] via [WriteCommandAction] to add the folder.
 * 9. Return success [ToolResult].
 *
 * This function is called from [ProjectStructureTool] for the "add_source_root" action.
 */
internal suspend fun executeAddSourceRoot(
    params: JsonObject,
    project: Project,
    tool: AgentTool
): ToolResult {

    // ── Step 1: extract required params ──────────────────────────────────────
    val moduleName = params["module"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        ?: return ToolResult(
            content = "Error: 'module' parameter is required for add_source_root.",
            summary = "Missing 'module' param",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )

    val pathStr = params["path"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        ?: return ToolResult(
            content = "Error: 'path' parameter is required for add_source_root.",
            summary = "Missing 'path' param",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )

    val kindStr = params["kind"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        ?: return ToolResult(
            content = "Error: 'kind' parameter is required for add_source_root. Valid kinds: source, test_source, resource, test_resource",
            summary = "Missing 'kind' param",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )

    // ── Step 2: parse kind ────────────────────────────────────────────────────
    val kind = parseSourceRootKind(kindStr)
        ?: return ToolResult(
            content = "Invalid kind '$kindStr'. Valid kinds: source, test_source, resource, test_resource",
            summary = "Invalid source root kind '$kindStr'",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )

    // ── Step 3: find module ───────────────────────────────────────────────────
    val module = ReadAction.compute<com.intellij.openapi.module.Module?, Throwable> {
        ModuleManager.getInstance(project).findModuleByName(moduleName)
    } ?: return ToolResult(
        content = "Module not found: '$moduleName'",
        summary = "Module '$moduleName' not found",
        tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
        isError = true
    )

    // ── Step 4: guard against external-system ownership ──────────────────────
    val externalSystemId = moduleExternalSystemId(module)
    if (externalSystemId != null) {
        return ToolResult(
            content = "Module '$moduleName' is owned by external system '$externalSystemId'. " +
                "Direct source root edits will be overwritten on the next resync. " +
                "Use project_structure.refresh_external_project to trigger a reimport instead, " +
                "or modify the external build file (e.g., build.gradle / pom.xml) and reimport.",
            summary = "Module '$moduleName' owned by external system '$externalSystemId'",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )
    }

    // ── Step 5: resolve path to absolute, look up VirtualFile ────────────────
    val absolute = if (File(pathStr).isAbsolute) {
        pathStr
    } else {
        File(project.basePath ?: "", pathStr).canonicalPath
    }

    val vFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(absolute))
        ?: return ToolResult(
            content = "Path not found: $absolute (create the directory first)",
            summary = "Path not found: $absolute",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )

    // ── Step 6: find containing ContentEntry ─────────────────────────────────
    val parentEntry = ReadAction.compute<com.intellij.openapi.roots.ContentEntry?, Throwable> {
        ModuleRootManager.getInstance(module).contentEntries.find { ce ->
            ce.file != null && VfsUtil.isAncestor(ce.file!!, vFile, false)
        }
    }
    if (parentEntry == null) {
        val contentRoots = ReadAction.compute<List<String>, Throwable> {
            ModuleRootManager.getInstance(module).contentEntries
                .mapNotNull { it.file?.path }
        }
        return ToolResult(
            content = "Path $absolute is not under any content root of module '$moduleName'. " +
                "Content roots: ${if (contentRoots.isEmpty()) "(none)" else contentRoots.joinToString(", ")}",
            summary = "Path not under any content root of '$moduleName'",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )
    }

    // ── Step 7: approval gate ─────────────────────────────────────────────────
    val approval = tool.requestApproval(
        toolName = "project_structure.add_source_root",
        args = "add source folder [$kindStr] $absolute to module $moduleName",
        riskLevel = "low",
        allowSessionApproval = true
    )
    if (approval == ApprovalResult.DENIED) {
        return ToolResult(
            content = "add_source_root denied by user.",
            summary = "Approval denied",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )
    }

    // ── Step 8: write action — add source folder ──────────────────────────────
    WriteCommandAction.runWriteCommandAction(project, "Agent: add source root", null, Runnable {
        ModuleRootModificationUtil.updateModel(module) { modModel ->
            val liveEntry = modModel.contentEntries.find { it.file == parentEntry.file }
                ?: return@updateModel
            liveEntry.addSourceFolder(vFile, sourceRootKindToJpsType(kind))
        }
    })

    // ── Step 9: result ────────────────────────────────────────────────────────
    val relative = relativizeToProject(absolute, project.basePath)
    return ToolResult(
        content = "Added [${sourceRootKindLabel(kind)}] source folder to '$moduleName': $relative",
        summary = "Added ${sourceRootKindLabel(kind)} root to $moduleName",
        tokenEstimate = 20
    )
}
