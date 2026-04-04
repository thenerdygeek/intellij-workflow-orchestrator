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

class TypeInferenceTool : AgentTool {
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

            // Try to resolve type from the element or its parents
            resolveTypeInfo(project, psiFile, leafElement, document, offset)
        }.inSmartMode(project).executeSynchronously()

        val isError = content.startsWith("Error:")
        return ToolResult(
            content = content,
            summary = if (isError) content else "Type resolved at $filePath",
            tokenEstimate = TokenEstimator.estimate(content),
            isError = isError
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

    private fun resolveTypeInfo(
        project: Project,
        psiFile: PsiFile,
        leafElement: PsiElement,
        document: Document?,
        offset: Int
    ): String {
        // Try Java PSI types first
        val javaResult = resolveJavaType(leafElement)
        if (javaResult != null) return javaResult

        // Try Kotlin via reflection
        val kotlinResult = resolveKotlinType(leafElement)
        if (kotlinResult != null) return kotlinResult

        return "No typed element found at this position"
    }

    private fun resolveJavaType(leafElement: PsiElement): String? {
        // Walk up from the leaf to find the nearest typed element
        // Order matters: check more specific types first

        // PsiLocalVariable
        PsiTreeUtil.getParentOfType(leafElement, PsiLocalVariable::class.java, false)?.let { variable ->
            return formatTypeResult(
                expression = variable.name ?: "local variable",
                type = variable.type,
                elementKind = "local variable"
            )
        }

        // PsiParameter
        PsiTreeUtil.getParentOfType(leafElement, PsiParameter::class.java, false)?.let { param ->
            return formatTypeResult(
                expression = param.name ?: "parameter",
                type = param.type,
                elementKind = "parameter"
            )
        }

        // PsiField
        PsiTreeUtil.getParentOfType(leafElement, PsiField::class.java, false)?.let { field ->
            return formatTypeResult(
                expression = field.name,
                type = field.type,
                elementKind = "field"
            )
        }

        // PsiMethod (return type)
        PsiTreeUtil.getParentOfType(leafElement, PsiMethod::class.java, false)?.let { method ->
            val returnType = method.returnType
                ?: return "Method '${method.name}' is a constructor (no return type)"
            return formatTypeResult(
                expression = "${method.name}()",
                type = returnType,
                elementKind = "method return type"
            )
        }

        // PsiExpression (most general — must be last)
        PsiTreeUtil.getParentOfType(leafElement, PsiExpression::class.java, false)?.let { expr ->
            val type = expr.type
                ?: return "Type could not be resolved (code may not compile)"
            return formatTypeResult(
                expression = expr.text.take(80),
                type = type,
                elementKind = "expression"
            )
        }

        return null
    }

    private fun formatTypeResult(expression: String, type: PsiType, elementKind: String): String {
        val presentable = type.presentableText
        val canonical = type.canonicalText
        val nullability = inferNullability(type)

        val sb = StringBuilder()
        sb.appendLine("Type of $elementKind:")
        sb.appendLine("  Expression: $expression")
        sb.appendLine("  Presentable: $presentable")
        sb.appendLine("  Qualified: $canonical")
        sb.appendLine("  Nullability: $nullability")
        return sb.toString().trimEnd()
    }

    private fun inferNullability(type: PsiType): String {
        val annotations = type.annotations
        for (annotation in annotations) {
            val qName = annotation.qualifiedName ?: continue
            val simpleName = qName.substringAfterLast('.')
            when {
                simpleName == "Nullable" || qName.endsWith(".Nullable") -> return "NULLABLE"
                simpleName == "NotNull" || simpleName == "NonNull" || qName.endsWith(".NotNull") || qName.endsWith(".NonNull") -> return "NOT_NULL"
            }
        }
        return when {
            type is PsiPrimitiveType -> "NOT_NULL"
            else -> "PLATFORM (no annotation)"
        }
    }

    private fun resolveKotlinType(leafElement: PsiElement): String? {
        return try {
            val ktExpressionClass = Class.forName("org.jetbrains.kotlin.psi.KtExpression")
            val ktPropertyClass = Class.forName("org.jetbrains.kotlin.psi.KtProperty")
            val ktParameterClass = Class.forName("org.jetbrains.kotlin.psi.KtParameter")
            val ktNamedFunctionClass = Class.forName("org.jetbrains.kotlin.psi.KtNamedFunction")

            // Try KtProperty
            val ktProperty = findParentOfKotlinType(leafElement, ktPropertyClass)
            if (ktProperty != null) {
                val name = ktPropertyClass.getMethod("getName").invoke(ktProperty) as? String ?: "property"
                val typeText = getKotlinTypeRefText(ktProperty, ktPropertyClass)
                    ?: resolveKotlinExpressionType(ktProperty, ktExpressionClass)
                    ?: return "Type could not be resolved for Kotlin property '$name'"
                return formatKotlinResult(name, typeText, "property")
            }

            // Try KtParameter
            val ktParam = findParentOfKotlinType(leafElement, ktParameterClass)
            if (ktParam != null) {
                val name = ktParameterClass.getMethod("getName").invoke(ktParam) as? String ?: "parameter"
                val typeText = getKotlinTypeRefText(ktParam, ktParameterClass)
                    ?: return "Type could not be resolved for Kotlin parameter '$name'"
                return formatKotlinResult(name, typeText, "parameter")
            }

            // Try KtNamedFunction (return type)
            val ktFunction = findParentOfKotlinType(leafElement, ktNamedFunctionClass)
            if (ktFunction != null) {
                val name = ktNamedFunctionClass.getMethod("getName").invoke(ktFunction) as? String ?: "function"
                val typeText = try {
                    val getTypeReference = ktNamedFunctionClass.getMethod("getTypeReference")
                    val ref = getTypeReference.invoke(ktFunction)
                    ref?.let { it.javaClass.getMethod("getText").invoke(it) as? String }
                } catch (_: Exception) { null }
                    ?: return "Kotlin function '$name' has inferred return type (not explicitly specified)"
                return formatKotlinResult("$name()", typeText, "function return type")
            }

            // Try KtExpression (most general)
            val ktExpr = findParentOfKotlinType(leafElement, ktExpressionClass)
            if (ktExpr != null) {
                val typeText = resolveKotlinExpressionType(ktExpr, ktExpressionClass)
                if (typeText != null) {
                    val exprText = try {
                        ktExpressionClass.getMethod("getText").invoke(ktExpr) as? String
                    } catch (_: Exception) { null }
                    return formatKotlinResult(exprText?.take(80) ?: "expression", typeText, "expression")
                }
            }

            null
        } catch (_: ClassNotFoundException) {
            // Kotlin plugin not loaded
            if (isKotlinFile(leafElement)) {
                "Kotlin plugin is not loaded — cannot resolve types in Kotlin files"
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun findParentOfKotlinType(element: PsiElement, ktClass: Class<*>): Any? {
        var current: PsiElement? = element
        while (current != null) {
            if (ktClass.isInstance(current)) return current
            current = current.parent
        }
        return null
    }

    private fun getKotlinTypeRefText(element: Any, elementClass: Class<*>): String? {
        return try {
            val getTypeReference = elementClass.getMethod("getTypeReference")
            val ref = getTypeReference.invoke(element)
            ref?.let { it.javaClass.getMethod("getText").invoke(it) as? String }
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveKotlinExpressionType(expression: Any, ktExpressionClass: Class<*>): String? {
        return try {
            // K1 path: call analyze() to get BindingContext, then getType()
            val analyzeMethod = Class.forName("org.jetbrains.kotlin.resolve.lazy.BodyResolveMode")
            val partialMode = analyzeMethod.getField("PARTIAL").get(null)
            val analyzeFn = Class.forName("org.jetbrains.kotlin.idea.caches.resolve.ResolutionUtils")
                .getMethod("analyze", ktExpressionClass, analyzeMethod.javaClass)
            val bindingContext = analyzeFn.invoke(null, expression, partialMode)

            val getTypeMethod = bindingContext.javaClass.getMethod("getType", ktExpressionClass)
            val kotlinType = getTypeMethod.invoke(bindingContext, expression)
            kotlinType?.toString()
        } catch (_: Exception) {
            // K2 or unavailable — fall back to type reference text
            null
        }
    }

    private fun formatKotlinResult(expression: String, typeText: String, elementKind: String): String {
        val nullability = when {
            typeText.endsWith("?") -> "NULLABLE"
            else -> "NOT_NULL"
        }
        val sb = StringBuilder()
        sb.appendLine("Type of $elementKind (Kotlin):")
        sb.appendLine("  Expression: $expression")
        sb.appendLine("  Type: $typeText")
        sb.appendLine("  Nullability: $nullability")
        return sb.toString().trimEnd()
    }

    private fun isKotlinFile(element: PsiElement): Boolean {
        val fileName = element.containingFile?.name ?: return false
        return fileName.endsWith(".kt") || fileName.endsWith(".kts")
    }
}
