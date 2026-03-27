package com.workflow.orchestrator.agent.tools.ide

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.refactoring.rename.RenameProcessor
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.builtin.PathValidator
import com.workflow.orchestrator.agent.tools.psi.PsiToolUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class RefactorRenameTool : AgentTool {
    override val name = "refactor_rename"
    override val description = "Safely rename a class, method, field, or variable across the entire project. Updates ALL references, imports, and usages. Much safer than text replacement with edit_file."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "symbol" to ParameterProperty(type = "string", description = "Symbol to rename (class name, method name, or ClassName.methodName)"),
            "new_name" to ParameterProperty(type = "string", description = "New name for the symbol"),
            "file" to ParameterProperty(type = "string", description = "Optional: file path for disambiguation if multiple symbols share the name"),
            "description" to ParameterProperty(type = "string", description = "Brief description of what this action does and why (shown to user in approval dialog)")
        ),
        required = listOf("symbol", "new_name", "description")
    )
    override val allowedWorkers = setOf(WorkerType.CODER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val symbol = params["symbol"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'symbol' required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val newName = params["new_name"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'new_name' required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val rawFile = params["file"]?.jsonPrimitive?.content

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
            // Phase 1: Find usages (read-only, off-EDT)
            val usages = ReadAction.nonBlocking<Array<com.intellij.usageView.UsageInfo>> {
                val processor = RenameProcessor(project, element, newName, false, false)
                processor.setPreviewUsages(false)
                processor.findUsages()
            }.inSmartMode(project).executeSynchronously()

            // Phase 2: Perform refactoring (write action on EDT)
            withContext(Dispatchers.EDT) {
                WriteCommandAction.runWriteCommandAction(project, "Agent: Rename $oldName → $newName", null, {
                    val processor = RenameProcessor(project, element, newName, false, false)
                    processor.setPreviewUsages(false)
                    processor.performRefactoring(usages)
                })
            }

            ToolResult(
                "Renamed '$oldName' → '$newName'. All references, imports, and usages updated.",
                "Renamed $oldName → $newName",
                10
            )
        } catch (e: Exception) {
            ToolResult("Error during rename: ${e.message}", "Rename error", 5, isError = true)
        }
    }

    private fun findElement(project: Project, symbol: String, rawFile: String?): com.intellij.psi.PsiElement? {
        val scope = GlobalSearchScope.projectScope(project)
        val facade = JavaPsiFacade.getInstance(project)

        // If symbol contains a dot, try ClassName.memberName
        if ('.' in symbol) {
            val parts = symbol.split('.')
            if (parts.size == 2) {
                val className = parts[0]
                val memberName = parts[1]
                val psiClass = PsiToolUtils.findClass(project, className) ?: return null

                // Try methods first, then fields
                psiClass.findMethodsByName(memberName, false).firstOrNull()?.let { return it }
                psiClass.findFieldByName(memberName, false)?.let { return it }
            }
        }

        // Try as a class name (fully qualified or simple)
        PsiToolUtils.findClass(project, symbol)?.let { return it }

        // If a file is provided, search within that file for the symbol
        if (rawFile != null) {
            val (path, pathError) = PathValidator.resolveAndValidate(rawFile, project.basePath)
            if (pathError == null && path != null) {
                val vf = LocalFileSystem.getInstance().findFileByIoFile(java.io.File(path))
                if (vf != null) {
                    val psiFile = PsiManager.getInstance(project).findFile(vf)
                    if (psiFile != null) {
                        return findSymbolInFile(psiFile, symbol)
                    }
                }
            }
        }

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
