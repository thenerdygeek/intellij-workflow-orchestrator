package com.workflow.orchestrator.agent.tools.project

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Implements the `resolve_file` action of [ProjectStructureTool].
 *
 * Resolves a file path to its owning module, content root, source root classification,
 * and external build-system ownership. Also detects disagreements between
 * [ProjectFileIndex] and [ModuleUtilCore] (the two IntelliJ module-resolution strategies)
 * so callers can debug project model inconsistencies.
 *
 * Must be called from a non-EDT thread — [ReadAction.compute] is used internally for
 * all IntelliJ platform reads.
 */
internal fun executeResolveFile(params: JsonObject, project: Project): ToolResult {
    // 1. Extract and validate "path" parameter
    val rawPath = params["path"]?.jsonPrimitive?.content
        ?: return ToolResult.error("Missing required parameter 'path'")

    // 2. Resolve to absolute path
    val absolutePath = if (rawPath.startsWith("/") || rawPath.length >= 2 && rawPath[1] == ':') {
        rawPath
    } else {
        (project.basePath?.trimEnd('/') ?: "") + "/" + rawPath
    }

    // 3. Dumb mode guard — check before VFS lookup so indexing callers get a clean message
    if (DumbService.isDumb(project)) {
        return ToolResult.error("Project is indexing — retry after indexing completes.")
    }

    // 4. Look up VirtualFile via LocalFileSystem
    val vFile = LocalFileSystem.getInstance().findFileByPath(absolutePath)
        ?: return ToolResult.error("File not found: $absolutePath")

    // 5. All IntelliJ model reads inside ReadAction
    return ReadAction.compute<ToolResult, RuntimeException> {
        val index = ProjectFileIndex.getInstance(project)

        val moduleViaIndex = index.getModuleForFile(vFile)
        val moduleViaUtil = ModuleUtilCore.findModuleForFile(vFile, project)

        // Source classification — ordered from most specific to least
        val sourceRootKind: String = when {
            index.isInTestSourceContent(vFile) -> "test_source"
            index.isInSourceContent(vFile) -> "source"
            index.isInLibraryClasses(vFile) -> "library_classes"
            else -> "not_in_source_content"
        }

        val contentRoot = index.getContentRootForFile(vFile)
        val sourceRoot = index.getSourceRootForFile(vFile)

        val externalSystem: String? = moduleViaIndex?.let {
            try {
                moduleExternalSystemId(it)
            } catch (_: Exception) {
                null
            }
        }

        // Agreement / disagreement between the two module-resolution strategies
        val agreementLine: String = when {
            moduleViaIndex == null && moduleViaUtil == null ->
                "both agree: no module"
            moduleViaIndex != null && moduleViaUtil != null &&
                moduleViaIndex.name == moduleViaUtil.name ->
                "both agree"
            else -> {
                val indexName = moduleViaIndex?.name ?: "none"
                val utilName = moduleViaUtil?.name ?: "none"
                "DISAGREEMENT — ProjectFileIndex=$indexName vs ModuleUtilCore=$utilName"
            }
        }

        val moduleName = moduleViaIndex?.name ?: moduleViaUtil?.name ?: "none"
        val basePath = project.basePath

        val sb = StringBuilder()
        sb.append("File: ${relativizeToProject(vFile.path, basePath)}\n")
        sb.append("Module (via ProjectFileIndex): ${moduleViaIndex?.name ?: "none"}\n")
        sb.append("Module (via ModuleUtilCore):    ${moduleViaUtil?.name ?: "none"}\n")
        sb.append("Agreement: $agreementLine\n")
        sb.append("Source root kind: $sourceRootKind\n")
        if (contentRoot != null) {
            sb.append("Content root: ${relativizeToProject(contentRoot.path, basePath)}\n")
        }
        if (sourceRoot != null) {
            sb.append("Source root: ${relativizeToProject(sourceRoot.path, basePath)}\n")
        }
        if (externalSystem != null) {
            sb.append("External system: $externalSystem (module is owned by this build system)\n")
        } else {
            sb.append("External system: none\n")
        }

        val content = sb.toString().trimEnd()
        val summary = "$moduleName — $sourceRootKind"
        ToolResult(content, summary, TokenEstimator.estimate(content))
    }
}
