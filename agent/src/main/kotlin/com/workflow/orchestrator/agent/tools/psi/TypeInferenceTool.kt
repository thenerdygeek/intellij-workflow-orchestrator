package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiParameter
import com.intellij.psi.util.PsiTreeUtil
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.ide.LanguageProviderRegistry
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.builtin.PathValidator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

class TypeInferenceTool(
    private val registry: LanguageProviderRegistry
) : AgentTool {
    override val name = "type_inference"
    override val description = "Get the resolved type of an expression or variable at a given position in a file. " +
        "Returns the fully-qualified type, presentable type name, and nullability info. Supports Java and Kotlin."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "file" to ParameterProperty(type = "string", description = "File path relative to project or absolute"),
            "offset" to ParameterProperty(type = "integer", description = "0-based character offset in the file"),
            "line" to ParameterProperty(type = "integer", description = "1-based line number (alternative to offset)"),
            "column" to ParameterProperty(type = "integer", description = "1-based column number (used with line)")
        ),
        required = listOf("file")
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.CODER, WorkerType.REVIEWER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val filePath = params["file"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Error: 'file' parameter is required",
                "Error: missing file", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )

        val offsetParam = try {
            params["offset"]?.jsonPrimitive?.int
        } catch (_: Exception) { null }

        val lineParam = try {
            params["line"]?.jsonPrimitive?.int
        } catch (_: Exception) { null }

        val columnParam = try {
            params["column"]?.jsonPrimitive?.int
        } catch (_: Exception) { null }

        if (offsetParam == null && lineParam == null) {
            return ToolResult(
                "Error: at least one of 'offset' or 'line' must be provided",
                "Error: missing position", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        val (resolvedPath, pathError) = PathValidator.resolveAndValidate(filePath, project.basePath)
        if (pathError != null) return pathError

        val content = ReadAction.nonBlocking<String> {
            val virtualFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .findFileByPath(resolvedPath!!)
                ?: return@nonBlocking "Error: file not found: $filePath"

            val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                ?: return@nonBlocking "Error: could not parse file: $filePath"

            val provider = registry.forFile(psiFile)
                ?: return@nonBlocking "Code intelligence not available for ${psiFile.language.displayName}"

            val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)

            val offset = if (offsetParam != null) {
                offsetParam
            } else {
                resolveLineColumnToOffset(document, lineParam!!, columnParam ?: 1)
                    ?: return@nonBlocking "Error: line $lineParam is out of bounds (file has ${document?.lineCount ?: 0} lines)"
            }

            if (offset < 0 || offset >= psiFile.textLength) {
                return@nonBlocking "Error: offset $offset is out of bounds (file length: ${psiFile.textLength})"
            }

            val leafElement = psiFile.findElementAt(offset)
                ?: return@nonBlocking "No typed element found at this position"

            // Classify the element kind for descriptive output
            val (elementKind, elementName) = classifyElementKind(leafElement)

            // Delegate type inference to the language provider
            val result = provider.inferType(leafElement)
                ?: return@nonBlocking "No typed element found at this position"

            val sb = StringBuilder()
            sb.appendLine("Type of $elementKind:")
            if (elementName != null) {
                sb.appendLine("  Expression: $elementName")
            }
            sb.appendLine("  Presentable: ${result.typeName}")
            if (result.qualifiedName != null) {
                sb.appendLine("  Qualified: ${result.qualifiedName}")
            }
            sb.appendLine("  Nullability: ${result.nullability}")
            sb.toString().trimEnd()
        }.inSmartMode(project).executeSynchronously()

        val isError = content.startsWith("Error:") || content.startsWith("Code intelligence not available")
        return ToolResult(
            content = content,
            summary = if (isError) content else "Type resolved at $filePath",
            tokenEstimate = TokenEstimator.estimate(content),
            isError = isError
        )
    }

    /**
     * Classify a leaf PsiElement into its element kind and name for descriptive output.
     * Walks up from the leaf to find the nearest typed parent (variable, parameter, field, method, expression).
     * Supports both Java PSI types and Kotlin types via class name.
     */
    private fun classifyElementKind(leaf: PsiElement): Pair<String, String?> {
        // Java types
        PsiTreeUtil.getParentOfType(leaf, PsiLocalVariable::class.java, false)?.let { variable ->
            return "local variable" to (variable.name ?: variable.text.take(50))
        }
        PsiTreeUtil.getParentOfType(leaf, PsiParameter::class.java, false)?.let { param ->
            return "parameter" to (param.name ?: param.text.take(50))
        }
        PsiTreeUtil.getParentOfType(leaf, PsiField::class.java, false)?.let { field ->
            return "field" to (field.name ?: field.text.take(50))
        }
        PsiTreeUtil.getParentOfType(leaf, PsiMethod::class.java, false)?.let { method ->
            return "method return type" to method.name
        }

        // Kotlin types via class name (avoids hard compile-time dependency)
        var current: PsiElement? = leaf
        while (current != null) {
            when (current.javaClass.simpleName) {
                "KtProperty" -> {
                    val name = (current as? PsiNamedElement)?.name ?: current.text.take(50)
                    return "property" to name
                }
                "KtParameter" -> {
                    val name = (current as? PsiNamedElement)?.name ?: current.text.take(50)
                    return "parameter" to name
                }
                "KtNamedFunction" -> {
                    val name = (current as? PsiNamedElement)?.name ?: current.text.take(50)
                    return "function return type" to name
                }
            }
            current = current.parent
        }

        // Fallback: expression
        val exprText = leaf.text.take(100)
        return "expression" to exprText
    }

    private fun resolveLineColumnToOffset(document: Document?, line: Int, column: Int): Int? {
        if (document == null) return null
        val zeroBasedLine = line - 1
        if (zeroBasedLine < 0 || zeroBasedLine >= document.lineCount) return null
        val lineStartOffset = document.getLineStartOffset(zeroBasedLine)
        val lineEndOffset = document.getLineEndOffset(zeroBasedLine)
        val zeroBasedColumn = column - 1
        val offset = lineStartOffset + zeroBasedColumn
        return if (offset > lineEndOffset) lineEndOffset else offset
    }
}
