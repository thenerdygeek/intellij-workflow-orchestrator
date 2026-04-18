package com.workflow.orchestrator.agent.tools.project

import com.intellij.facet.FacetManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.CompilerModuleExtension
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jetbrains.jps.model.java.JavaSourceRootType

/**
 * Implements the `module_detail` action of [ProjectStructureTool].
 *
 * Produces a full snapshot of a single IntelliJ module:
 *  - External build system (Gradle / Maven / sbt / none)
 *  - SDK (or "inherited from project")
 *  - Language level (if set)
 *  - Content roots
 *  - Source / test-source / resource / test-resource / excluded folders
 *  - Module and library order entries (with scope and export flag)
 *  - Compiler output URLs (main + test + inherit flag)
 *  - Facets (Spring, Android, JPA, etc.)
 *
 * All IntelliJ model reads run inside [ReadAction.compute] so this function is
 * safe to call from any non-EDT background thread.
 */
internal fun executeModuleDetail(params: JsonObject, project: Project): ToolResult {
    // 1. Validate "module" parameter
    val moduleName = params["module"]?.jsonPrimitive?.content
        ?: return ToolResult.error("Missing required parameter 'module'")

    // 2. Dumb mode guard — indexing in progress
    if (DumbService.isDumb(project)) {
        return ToolResult.error("Project is indexing — retry after indexing completes.")
    }

    // 3. Locate the module + all IntelliJ model reads inside ReadAction
    return ReadAction.compute<ToolResult, RuntimeException> {
        val module = ModuleManager.getInstance(project).findModuleByName(moduleName)
            ?: return@compute ToolResult.error(
                "Module not found: '$moduleName'. Use build.project_modules to list modules."
            )

        val rm = ModuleRootManager.getInstance(module)
        val basePath = project.basePath

        // ── External system ────────────────────────────────────────────────
        val externalSystem = try {
            moduleExternalSystemId(module) ?: "none"
        } catch (_: Exception) {
            "none"
        }

        // ── SDK ────────────────────────────────────────────────────────────
        val sdkName = rm.sdk?.name ?: "<inherited from project>"

        // ── Language level (Java modules only) ────────────────────────────
        val languageLevel: String? = try {
            val extClass = Class.forName(
                "com.intellij.openapi.roots.LanguageLevelModuleExtension"
            )
            val getInstanceMethod = extClass.getMethod("getInstance", module::class.java)
            val ext = getInstanceMethod.invoke(null, module)
            val getLevelMethod = extClass.getMethod("getLanguageLevel")
            val level = getLevelMethod.invoke(ext)
            level?.let {
                val nameMethod = it.javaClass.getMethod("name")
                nameMethod.invoke(it) as? String
            }
        } catch (_: Exception) {
            null
        }

        // ── Content roots ──────────────────────────────────────────────────
        val contentRoots = rm.contentRoots.map { relativizeToProject(it.path, basePath) }

        // ── Source folders + excluded folders ─────────────────────────────
        data class FolderLine(val kind: String, val path: String)

        val folderLines = mutableListOf<FolderLine>()
        for (entry in rm.contentEntries) {
            for (sf in entry.sourceFolders) {
                val kind: String = when (sf.jpsElement.rootType) {
                    JavaSourceRootType.SOURCE -> "source"
                    JavaSourceRootType.TEST_SOURCE -> "test_source"
                    JavaResourceRootType.RESOURCE -> "resource"
                    JavaResourceRootType.TEST_RESOURCE -> "test_resource"
                    else -> sf.jpsElement.rootType.javaClass.simpleName
                }
                val path = sf.file?.path?.let { relativizeToProject(it, basePath) } ?: "<unknown>"
                folderLines.add(FolderLine(kind, path))
            }
            for (excluded in entry.excludeFolderFiles) {
                folderLines.add(FolderLine("[excluded]", relativizeToProject(excluded.path, basePath)))
            }
        }

        // ── Order entries ──────────────────────────────────────────────────
        val moduleDepLines = mutableListOf<String>()
        val libraryDepLines = mutableListOf<String>()

        for (entry in rm.orderEntries) {
            when (entry) {
                is ModuleOrderEntry -> {
                    val exported = if (entry.isExported) " exported" else ""
                    moduleDepLines.add("${entry.moduleName} [${entry.scope.displayName}$exported]")
                }
                is LibraryOrderEntry -> {
                    val libName = entry.libraryName ?: "<unnamed>"
                    val exported = if (entry.isExported) " exported" else ""
                    libraryDepLines.add("$libName [${entry.scope.displayName}$exported]")
                }
            }
        }

        // ── Compiler output ────────────────────────────────────────────────
        val compilerExt = CompilerModuleExtension.getInstance(module)

        // ── Facets ────────────────────────────────────────────────────────
        val facetLines = FacetManager.getInstance(module).allFacets.map { f ->
            "${f.name} [${f.type.presentableName}]"
        }

        // ── Assemble output ────────────────────────────────────────────────
        val sb = StringBuilder()
        sb.appendLine("Module: $moduleName")
        sb.appendLine("External system: $externalSystem")
        sb.appendLine("SDK: $sdkName")
        if (languageLevel != null) {
            sb.appendLine("Language level: $languageLevel")
        }

        sb.appendLine()
        if (contentRoots.isEmpty()) {
            sb.appendLine("Content roots: (none)")
        } else {
            sb.appendLine("Content roots (${contentRoots.size}):")
            contentRoots.forEach { sb.appendLine("  $it") }
        }

        sb.appendLine()
        if (folderLines.isEmpty()) {
            sb.appendLine("Source folders: (none)")
        } else {
            sb.appendLine("Source/resource/excluded folders (${folderLines.size}):")
            folderLines.forEach { sb.appendLine("  [${it.kind}] ${it.path}") }
        }

        sb.appendLine()
        if (moduleDepLines.isEmpty()) {
            sb.appendLine("Module dependencies: (none)")
        } else {
            sb.appendLine("Module dependencies (${moduleDepLines.size}):")
            moduleDepLines.forEach { sb.appendLine("  $it") }
        }

        sb.appendLine()
        if (libraryDepLines.isEmpty()) {
            sb.appendLine("Library dependencies: (none)")
        } else {
            sb.appendLine("Library dependencies (${libraryDepLines.size}):")
            libraryDepLines.forEach { sb.appendLine("  $it") }
        }

        sb.appendLine()
        sb.appendLine("Compiler output:")
        if (compilerExt != null) {
            sb.appendLine("  Main output:  ${compilerExt.compilerOutputUrl ?: "(none)"}")
            sb.appendLine("  Test output:  ${compilerExt.compilerOutputUrlForTests ?: "(none)"}")
            sb.appendLine("  Inherits:     ${compilerExt.isCompilerOutputPathInherited}")
        } else {
            sb.appendLine("  (not available)")
        }

        sb.appendLine()
        if (facetLines.isEmpty()) {
            sb.appendLine("Facets: (none)")
        } else {
            sb.appendLine("Facets (${facetLines.size}):")
            facetLines.forEach { sb.appendLine("  $it") }
        }

        val content = sb.toString().trimEnd()
        val sourceCount = folderLines.count { it.kind == "source" || it.kind == "test_source" }
        val summary = "${module.name}: $sourceCount source folder(s), ${moduleDepLines.size} module dep(s)"

        ToolResult(content, summary, TokenEstimator.estimate(content))
    }
}
