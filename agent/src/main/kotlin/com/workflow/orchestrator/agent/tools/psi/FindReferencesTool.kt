package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class FindReferencesTool : AgentTool {
    override val name = "find_references"
    override val description = "Find all usages/references of a symbol (class, method, or field) across the project."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "symbol" to ParameterProperty(type = "string", description = "Symbol name to search for (class name, method name, or field name)"),
            "file" to ParameterProperty(type = "string", description = "Optional file path for disambiguation when multiple symbols share the same name")
        ),
        required = listOf("symbol")
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        val symbol = params["symbol"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'symbol' parameter required", "Error: missing symbol", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val filePath = params["file"]?.jsonPrimitive?.content

        val content = ReadAction.nonBlocking<String> {
            val scope = GlobalSearchScope.projectScope(project)

            // Try to find as a class first
            val psiClass = PsiToolUtils.findClass(project, symbol)
            val targetElement = if (psiClass != null) {
                // If file path specified, check class is in that file
                if (filePath != null) {
                    val resolvedPath = if (filePath.startsWith("/")) filePath else "${project.basePath}/$filePath"
                    val classFile = psiClass.containingFile?.virtualFile?.path
                    if (classFile != resolvedPath) null else psiClass
                } else {
                    psiClass
                }
            } else {
                null
            }

            // If class not found, try to find as a method within a class context
            val searchTarget = targetElement ?: run {
                if (filePath != null) {
                    val resolvedPath = if (filePath.startsWith("/")) filePath else "${project.basePath}/$filePath"
                    val vFile = LocalFileSystem.getInstance().findFileByPath(resolvedPath)
                    val psiFile = vFile?.let { PsiManager.getInstance(project).findFile(it) }
                    // Search all classes in the file for a method with this name
                    val classes = (psiFile as? com.intellij.psi.PsiJavaFile)?.classes ?: emptyArray()
                    classes.flatMap { it.methods.toList() }
                        .firstOrNull { it.name == symbol }
                } else {
                    null
                }
            }

            if (searchTarget == null) {
                return@nonBlocking "No symbol '$symbol' found in project"
            }

            val references = ReferencesSearch.search(searchTarget, scope).findAll()
            if (references.isEmpty()) {
                return@nonBlocking "No references found for '$symbol'"
            }

            val results = references.take(50).mapNotNull { ref ->
                val element = ref.element
                val file = element.containingFile?.virtualFile?.path ?: return@mapNotNull null
                val document = com.intellij.psi.PsiDocumentManager.getInstance(project)
                    .getDocument(element.containingFile) ?: return@mapNotNull null
                val line = document.getLineNumber(element.textOffset) + 1
                val lineText = document.getText(
                    com.intellij.openapi.util.TextRange(
                        document.getLineStartOffset(line - 1),
                        document.getLineEndOffset(line - 1)
                    )
                ).trim()
                "$file:$line  $lineText"
            }

            val header = "References to '$symbol' (${references.size} total):\n"
            val truncated = if (references.size > 50) "\n... (showing first 50 of ${references.size})" else ""
            header + results.joinToString("\n") + truncated
        }.inSmartMode(project).executeSynchronously()

        return ToolResult(
            content = content,
            summary = "References for '$symbol'",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }
}
