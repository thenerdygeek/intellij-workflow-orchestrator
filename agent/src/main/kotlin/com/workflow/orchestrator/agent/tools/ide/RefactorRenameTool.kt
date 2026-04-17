package com.workflow.orchestrator.agent.tools.ide

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.refactoring.rename.RenameProcessor
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.ide.LanguageProviderRegistry
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.builtin.PathValidator
import com.workflow.orchestrator.agent.tools.psi.PsiToolUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

class RefactorRenameTool(
    private val registry: LanguageProviderRegistry
) : AgentTool {
    override val name = "refactor_rename"
    override val description = "Safely rename a class, method, field, or variable across the entire project. Updates ALL references, imports, and usages. Much safer than text replacement with edit_file."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "symbol" to ParameterProperty(type = "string", description = "Symbol to rename (class name, method name, or ClassName.methodName)"),
            "new_name" to ParameterProperty(type = "string", description = "New name for the symbol"),
            "file" to ParameterProperty(type = "string", description = "Optional: file path for disambiguation if multiple symbols share the name"),
            "description" to ParameterProperty(type = "string", description = "Brief description of what this action does and why (shown to user in approval dialog)"),
            "confirm_cross_module" to ParameterProperty(
                type = "boolean",
                description = "Required when the rename spans >1 project module. First call without this flag returns a preview of affected modules; set to true on a follow-up call to apply. Library renames are ALWAYS blocked and cannot be bypassed with this flag."
            )
        ),
        required = listOf("symbol", "new_name", "description")
    )
    override val allowedWorkers = setOf(WorkerType.CODER)

    /**
     * ## F4 refactor safety (Task 5.3)
     *
     * Two guards wrap the existing `RenameProcessor` flow:
     *
     * 1. **Hard library block — UNCONDITIONAL.** If any usage is inside a jar /
     *    external library (detected via [ProjectFileIndex.isInLibrary] +
     *    [ProjectFileIndex.isInLibraryClasses]), the tool returns an error
     *    before touching the refactoring. `confirm_cross_module=true` CANNOT
     *    bypass this — project code would reference a name that no longer
     *    exists in the library jar's bytecode.
     *
     * 2. **Cross-module confirmation gate.** If usages span >1 module, the tool
     *    returns a non-error PREVIEW (not `isError=true`). The LLM must
     *    re-invoke with `confirm_cross_module=true` to proceed.
     *
     * Single-module renames (the common case) still proceed without
     * confirmation — preserves the existing UX.
     *
     * Classification is delegated to the pure [summarizeForApproval] helper in
     * [RenameSafetyAnalyzer], which is unit-testable without an IntelliJ
     * fixture.
     */
    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val symbol = params["symbol"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'symbol' required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val newName = params["new_name"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'new_name' required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val rawFile = params["file"]?.jsonPrimitive?.content
        val confirmCrossModule = params["confirm_cross_module"]?.jsonPrimitive?.booleanOrNull ?: false

        if (DumbService.isDumb(project)) {
            return PsiToolUtils.dumbModeError()
        }

        // Resolve the PsiElement to rename (requires read lock for PSI access)
        val element = ReadAction.nonBlocking<com.intellij.psi.PsiElement?> {
            findElement(project, symbol, rawFile)
        }.inSmartMode(project).executeSynchronously()
            ?: return ToolResult(
                "Cannot find symbol '$symbol'. Provide a class name, ClassName.methodName, or specify 'file' for disambiguation.",
                "Not found",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val oldName = (element as? PsiNamedElement)?.name ?: symbol

        return try {
            // Phase 1: Find + classify usages (read-only, off-EDT).
            // We classify inside the same ReadAction so PSI/VFS state is
            // stable and we don't race against indexing.
            data class FindResult(
                val usages: Array<com.intellij.usageView.UsageInfo>,
                val classifications: List<UsageClassification>,
            )
            val findResult = ReadAction.nonBlocking<FindResult> {
                val processor = RenameProcessor(project, element, newName, false, false)
                processor.setPreviewUsages(false)
                val usages = processor.findUsages()
                val classifications = usages.mapNotNull { classifyUsage(it, project) }
                FindResult(usages, classifications)
            }.inSmartMode(project).executeSynchronously()

            val usages = findResult.usages
            val classifications = findResult.classifications

            // Phase 2: Safety analysis via the pure helper.
            when (val summary = summarizeForApproval(classifications)) {
                is SummaryResult.LibraryBlocked -> {
                    // Hard block — UNCONDITIONAL. The cross-module confirm
                    // flag is deliberately NOT consulted here: rename of
                    // library code is never allowed because we cannot modify
                    // jar bytecode. See RenameSafetyAnalyzer kdoc for rationale.
                    val fileList = summary.libraryFiles.joinToString(", ")
                    return ToolResult(
                        "Rename of '$oldName' blocked: ${summary.libraryFiles.size} usage(s) " +
                            "are in external library code (cannot modify jar contents). " +
                            "Library files: $fileList",
                        "Library rename blocked",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )
                }

                is SummaryResult.CrossModulePreview -> {
                    if (!confirmCrossModule) {
                        val moduleLines = summary.moduleBreakdown.entries.joinToString("\n") { (mod, counts) ->
                            "  - ${counts.total} usages in module :$mod (${counts.testCount} test, ${counts.prodCount} production)"
                        }
                        return ToolResult(
                            "Rename of '$oldName' → '$newName' will affect:\n$moduleLines\n\n" +
                                "This spans multiple modules. Re-run with confirm_cross_module=true to apply.",
                            "Cross-module preview — confirmation required",
                            10
                            // NOT isError — this is a successful preview, not
                            // a failure. The LLM is expected to re-invoke with
                            // confirm_cross_module=true.
                        )
                    }
                    // Fall through to perform the rename.
                }

                is SummaryResult.NoUsages, is SummaryResult.SingleModuleOK -> {
                    // Proceed without confirmation — common case.
                }
            }

            // Phase 3: Perform refactoring (write action on EDT).
            withContext(Dispatchers.EDT) {
                WriteCommandAction.runWriteCommandAction(project, "Agent: Rename $oldName → $newName", null, {
                    val processor = RenameProcessor(project, element, newName, false, false)
                    processor.setPreviewUsages(false)
                    processor.performRefactoring(usages)
                })
            }

            // Module count — distinct non-null modules touched. Library
            // count is zero here (we'd have returned above) so modules are
            // all project modules. Include unknown-bucket in the count so
            // the LLM sees the full scope.
            val moduleCount = classifications.map { it.module }.distinct().size.coerceAtLeast(1)

            ToolResult(
                "Renamed '$oldName' → '$newName'. ${usages.size} usages updated across $moduleCount module(s).",
                "Renamed $oldName → $newName",
                10
            )
        } catch (e: Exception) {
            ToolResult("Error during rename: ${e.message}", "Rename error", 5, isError = true)
        }
    }

    private fun findElement(project: Project, symbol: String, rawFile: String?): com.intellij.psi.PsiElement? {
        // If a file is provided, search within that file first for disambiguation
        if (rawFile != null) {
            val (path, pathError) = PathValidator.resolveAndValidate(rawFile, project.basePath)
            if (pathError == null && path != null) {
                val vf = LocalFileSystem.getInstance().findFileByIoFile(java.io.File(path))
                if (vf != null) {
                    val psiFile = PsiManager.getInstance(project).findFile(vf)
                    if (psiFile != null) {
                        findSymbolInFile(psiFile, symbol)?.let { return it }
                    }
                }
            }
        }

        // If symbol contains a dot, try ClassName.memberName via Java/Kotlin PSI
        if ('.' in symbol) {
            val parts = symbol.split('.')
            if (parts.size == 2) {
                val className = parts[0]
                val memberName = parts[1]
                val psiClass = PsiToolUtils.findClass(project, className)
                if (psiClass != null) {
                    // Try methods first, then fields
                    psiClass.findMethodsByName(memberName, false).firstOrNull()?.let { return it }
                    psiClass.findFieldByName(memberName, false)?.let { return it }
                }
            }
        }

        // Try all registered providers (works for Java, Kotlin, Python, etc.)
        registry.allProviders().firstNotNullOfOrNull { provider ->
            provider.findSymbol(project, symbol)
        }?.let { return it }

        // Fallback: try Java PSI facade directly for class names
        PsiToolUtils.findClass(project, symbol)?.let { return it }

        return null
    }

    private fun findSymbolInFile(psiFile: com.intellij.psi.PsiFile, symbol: String): com.intellij.psi.PsiElement? {
        var found: com.intellij.psi.PsiElement? = null
        psiFile.accept(object : com.intellij.psi.PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: com.intellij.psi.PsiElement) {
                if (found != null) return
                if (element is PsiNamedElement && element.name == symbol) {
                    found = element
                    return
                }
                super.visitElement(element)
            }
        })
        return found
    }
}
