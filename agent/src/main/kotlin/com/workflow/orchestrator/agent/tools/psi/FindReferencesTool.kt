package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.search.searches.ReferencesSearch
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.builtin.PathValidator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class FindReferencesTool : AgentTool {
    override val name = "find_references"
    override val description = "Find all usages/references of a symbol (class, method, or field) across the project."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "symbol" to ParameterProperty(type = "string", description = "Symbol name to search for (class name, method name, or field name)"),
            "file" to ParameterProperty(type = "string", description = "Optional file path for disambiguation when multiple symbols share the same name"),
            "context_lines" to ParameterProperty(type = "integer", description = "Number of context lines around each reference (default: 0, max: 3)")
        ),
        required = listOf("symbol")
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.REVIEWER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        val symbol = params["symbol"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'symbol' parameter required", "Error: missing symbol", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val rawFilePath = params["file"]?.jsonPrimitive?.content
        val contextLines = (params["context_lines"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0).coerceIn(0, 3)

        // Validate file path upfront if provided (prevents path traversal)
        val resolvedFilePath = if (rawFilePath != null) {
            val (validated, pathError) = PathValidator.resolveAndValidate(rawFilePath, project.basePath)
            if (pathError != null) return pathError
            validated
        } else {
            null
        }

        val content = ReadAction.nonBlocking<String> {
            val scope = GlobalSearchScope.projectScope(project)

            // Try to find as a class first
            val psiClass = PsiToolUtils.findClassAnywhere(project, symbol)
            val targetElement = if (psiClass != null) {
                // If file path specified, check class is in that file
                if (resolvedFilePath != null) {
                    val classFile = psiClass.containingFile?.virtualFile?.path
                    if (classFile != resolvedFilePath) null else psiClass
                } else {
                    psiClass
                }
            } else {
                null
            }

            // If class not found, try to find as a method within a class context
            val searchTarget = targetElement ?: run {
                if (resolvedFilePath != null) {
                    val vFile = LocalFileSystem.getInstance().findFileByPath(resolvedFilePath)
                    val psiFile = vFile?.let { PsiManager.getInstance(project).findFile(it) }
                    // Search all classes in the file for a method with this name
                    val classes = (psiFile as? com.intellij.psi.PsiJavaFile)?.classes ?: emptyArray()
                    classes.flatMap { it.methods.toList() }
                        .firstOrNull { it.name == symbol }
                } else {
                    null
                }
            } ?: run {
                // Global fallback via PsiShortNamesCache for methods and fields
                val shortNameCache = PsiShortNamesCache.getInstance(project)
                shortNameCache.getMethodsByName(symbol, scope).firstOrNull()
                    ?: shortNameCache.getFieldsByName(symbol, scope).firstOrNull()
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
                val absoluteFilePath = element.containingFile?.virtualFile?.path ?: return@mapNotNull null
                val relativeFilePath = PsiToolUtils.relativePath(project, absoluteFilePath)
                val document = com.intellij.psi.PsiDocumentManager.getInstance(project)
                    .getDocument(element.containingFile) ?: return@mapNotNull null
                val line = document.getLineNumber(element.textOffset) + 1
                val zeroIndexedLine = line - 1

                if (contextLines > 0) {
                    val startLine = maxOf(0, zeroIndexedLine - contextLines)
                    val endLine = minOf(document.lineCount - 1, zeroIndexedLine + contextLines)
                    val contextBlock = (startLine..endLine).joinToString("\n") { lineIdx ->
                        val lineText = document.getText(
                            com.intellij.openapi.util.TextRange(
                                document.getLineStartOffset(lineIdx),
                                document.getLineEndOffset(lineIdx)
                            )
                        )
                        val lineNum = lineIdx + 1
                        val marker = if (lineIdx == zeroIndexedLine) ">>>" else "   "
                        "$marker $lineNum: $lineText"
                    }
                    "$relativeFilePath:$line\n$contextBlock"
                } else {
                    val lineText = document.getText(
                        com.intellij.openapi.util.TextRange(
                            document.getLineStartOffset(zeroIndexedLine),
                            document.getLineEndOffset(zeroIndexedLine)
                        )
                    ).trim()
                    "$relativeFilePath:$line  $lineText"
                }
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
