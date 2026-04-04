package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.builtin.PathValidator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

class DataFlowAnalysisTool : AgentTool {
    override val name = "dataflow_analysis"
    override val description = "Analyze nullability, value ranges, and constant values of an expression in Java code. " +
        "Returns whether a variable can be null, its possible value range, and constant value if determinable. " +
        "Java only — does not work on Kotlin files."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "file" to ParameterProperty(type = "string", description = "Java file path relative to project or absolute"),
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

        // Check for Kotlin files
        if (filePath.endsWith(".kt") || filePath.endsWith(".kts")) {
            val msg = "DataFlow analysis is only available for Java files. Kotlin has its own analysis not exposed via this API."
            return ToolResult(msg, msg, ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

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

            if (psiFile !is PsiJavaFile) {
                return@nonBlocking "Error: not a Java file: $filePath"
            }

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
                ?: return@nonBlocking "No element found at this position"

            // Find the nearest PsiExpression
            val expression = PsiTreeUtil.getParentOfType(leafElement, PsiExpression::class.java, false)
                ?: return@nonBlocking "No expression found at this position. DataFlow analysis requires an expression inside a method body."

            val fileName = virtualFile.name
            val lineNumber = document?.getLineNumber(offset)?.plus(1) ?: 0

            analyzeDataFlow(expression, fileName, lineNumber)
        }.inSmartMode(project).executeSynchronously()

        val isError = content.startsWith("Error:")
        return ToolResult(
            content = content,
            summary = if (isError) content else "DataFlow analyzed at $filePath",
            tokenEstimate = TokenEstimator.estimate(content),
            isError = isError
        )
    }

    private fun analyzeDataFlow(expression: PsiExpression, fileName: String, lineNumber: Int): String {
        val sb = StringBuilder()
        sb.appendLine("DataFlow analysis at $fileName:$lineNumber")
        sb.appendLine("  Expression: ${expression.text.take(80)}")

        // Type
        val exprType = expression.type
        sb.appendLine("  Type: ${exprType?.presentableText ?: "Unknown (analysis inconclusive)"}")

        // DfType via CommonDataflow
        try {
            val commonDataflowClass = Class.forName("com.intellij.codeInspection.dataFlow.CommonDataflow")

            // Get DfType
            val getDfTypeMethod = commonDataflowClass.getMethod("getDfType", PsiExpression::class.java)
            val dfType = getDfTypeMethod.invoke(null, expression)

            if (dfType != null) {
                val dfTypeClass = Class.forName("com.intellij.codeInspection.dataFlow.types.DfType")
                val topField = dfTypeClass.getField("TOP")
                val topValue = topField.get(null)

                // Nullability
                val nullability = resolveNullability(dfType, topValue, exprType)
                sb.appendLine("  Nullability: $nullability")
            } else {
                sb.appendLine("  Nullability: UNKNOWN (no dataflow result)")
            }

            // Range via getExpressionRange
            val rangeText = resolveRange(commonDataflowClass, expression, exprType)
            sb.appendLine("  Range: $rangeText")

            // Constant value via computeValue
            val constantText = resolveConstant(commonDataflowClass, expression)
            sb.appendLine("  Constant value: $constantText")

        } catch (_: ClassNotFoundException) {
            sb.appendLine("  Nullability: UNKNOWN (DFA API not available)")
            sb.appendLine("  Range: (not available)")
            sb.appendLine("  Constant value: (not available)")
        } catch (_: Exception) {
            sb.appendLine("  Nullability: UNKNOWN (analysis error)")
            sb.appendLine("  Range: (analysis error)")
            sb.appendLine("  Constant value: (analysis error)")
        }

        return sb.toString().trimEnd()
    }

    private fun resolveNullability(dfType: Any, topValue: Any?, exprType: PsiType?): String {
        // Check if dfType is TOP (unanalyzable)
        if (dfType == topValue) {
            return "Unknown (analysis inconclusive)"
        }

        return try {
            val dfaNullabilityClass = Class.forName("com.intellij.codeInspection.dataFlow.DfaNullability")
            val fromDfTypeMethod = dfaNullabilityClass.getMethod("fromDfType", Class.forName("com.intellij.codeInspection.dataFlow.types.DfType"))
            val nullability = fromDfTypeMethod.invoke(null, dfType)
            val nullabilityName = nullability.toString()

            when {
                nullabilityName.contains("NULLABLE", ignoreCase = true) -> "NULLABLE"
                nullabilityName.contains("NOT_NULL", ignoreCase = true) -> "NOT_NULL"
                nullabilityName.contains("FLUSHED", ignoreCase = true) -> "UNKNOWN (flushed by method call)"
                else -> "UNKNOWN"
            }
        } catch (_: Exception) {
            // Fallback: check if primitive (always NOT_NULL)
            if (exprType is PsiPrimitiveType) "NOT_NULL" else "UNKNOWN"
        }
    }

    private fun resolveRange(commonDataflowClass: Class<*>, expression: PsiExpression, exprType: PsiType?): String {
        // Only applicable for numeric types
        if (exprType == null || !isNumericType(exprType)) {
            return "(not applicable)"
        }

        return try {
            val getRangeMethod = commonDataflowClass.getMethod("getExpressionRange", PsiExpression::class.java)
            val range = getRangeMethod.invoke(null, expression)
            if (range != null) {
                range.toString()
            } else {
                "(could not determine)"
            }
        } catch (_: Exception) {
            "(not available)"
        }
    }

    private fun resolveConstant(commonDataflowClass: Class<*>, expression: PsiExpression): String {
        return try {
            val computeValueMethod = commonDataflowClass.getMethod("computeValue", PsiExpression::class.java)
            val value = computeValueMethod.invoke(null, expression)
            if (value != null) {
                value.toString()
            } else {
                "(none)"
            }
        } catch (_: Exception) {
            "(not available)"
        }
    }

    private fun isNumericType(type: PsiType): Boolean {
        val text = type.canonicalText
        return text in setOf(
            "int", "long", "short", "byte", "float", "double", "char",
            "java.lang.Integer", "java.lang.Long", "java.lang.Short",
            "java.lang.Byte", "java.lang.Float", "java.lang.Double",
            "java.lang.Character"
        )
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
