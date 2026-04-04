package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtil
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

class ReadWriteAccessTool : AgentTool {
    override val name = "read_write_access"
    override val description = "Find all read and write accesses to a variable, field, or parameter. " +
        "Shows which code reads the value vs which code modifies it. " +
        "Useful for understanding data flow and finding unintended mutations."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "file" to ParameterProperty(type = "string", description = "File path containing the variable/field declaration"),
            "offset" to ParameterProperty(type = "integer", description = "0-based character offset of the variable name"),
            "line" to ParameterProperty(type = "integer", description = "1-based line number (alternative to offset)"),
            "column" to ParameterProperty(type = "integer", description = "1-based column number (used with line)"),
            "scope" to ParameterProperty(type = "string", description = "Search scope: 'project' (default) or 'file'")
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

        val scopeParam = params["scope"]?.jsonPrimitive?.content ?: "project"

        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        val (resolvedPath, pathError) = PathValidator.resolveAndValidate(filePath, project.basePath)
        if (pathError != null) return pathError

        val content = ReadAction.nonBlocking<String> {
            val virtualFile = LocalFileSystem.getInstance().findFileByPath(resolvedPath!!)
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
                ?: return@nonBlocking "No element found at this position"

            // Find the target variable/field/parameter
            val target = findTargetElement(leafElement)
                ?: return@nonBlocking "No variable, field, or parameter found at this position"

            val targetName = (target as? PsiNamedElement)?.name ?: "unknown"
            val targetKind = classifyTargetKind(target)
            val targetLine = document?.let { doc ->
                doc.getLineNumber(target.textOffset) + 1
            } ?: 0
            val targetFileName = PsiToolUtils.relativePath(project, psiFile.virtualFile.path)

            // Determine search scope
            val searchScope: SearchScope = when (scopeParam) {
                "file" -> LocalSearchScope(psiFile)
                else -> GlobalSearchScope.projectScope(project)
            }

            val references = ReferencesSearch.search(target, searchScope).findAll()

            if (references.isEmpty()) {
                return@nonBlocking "Read/Write analysis for $targetKind '$targetName' ($targetFileName:$targetLine)\n\nNo references found."
            }

            val writes = mutableListOf<AccessInfo>()
            val reads = mutableListOf<AccessInfo>()
            val readWrites = mutableListOf<AccessInfo>()

            for (ref in references) {
                val element = ref.element
                val refFile = element.containingFile?.virtualFile?.path ?: continue
                val refRelPath = PsiToolUtils.relativePath(project, refFile)
                val refDoc = PsiDocumentManager.getInstance(project)
                    .getDocument(element.containingFile) ?: continue
                val refLine = refDoc.getLineNumber(element.textOffset) + 1
                val lineText = getLineText(refDoc, refLine - 1).trim()

                val accessType = classifyAccess(element)

                val info = AccessInfo(refRelPath, refLine, lineText)
                when (accessType) {
                    AccessType.WRITE -> writes.add(info)
                    AccessType.READ -> reads.add(info)
                    AccessType.READ_WRITE -> {
                        readWrites.add(info)
                        // += is both read and write
                        writes.add(info)
                        reads.add(info)
                    }
                }
            }

            val sb = StringBuilder()
            sb.appendLine("Read/Write analysis for $targetKind '$targetName' ($targetFileName:$targetLine)")
            sb.appendLine()

            sb.appendLine("Writes (${writes.size}):")
            if (writes.isEmpty()) {
                sb.appendLine("  (none)")
            } else {
                for (w in writes.take(50)) {
                    sb.appendLine("  ${w.file}:${w.line} — ${w.lineText}")
                }
                if (writes.size > 50) {
                    sb.appendLine("  ... (${writes.size - 50} more)")
                }
            }

            sb.appendLine()
            sb.appendLine("Reads (${reads.size}):")
            if (reads.isEmpty()) {
                sb.appendLine("  (none)")
            } else {
                for (r in reads.take(50)) {
                    sb.appendLine("  ${r.file}:${r.line} — ${r.lineText}")
                }
                if (reads.size > 50) {
                    sb.appendLine("  ... (${reads.size - 50} more)")
                }
            }

            if (readWrites.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("Read+Write (compound assignments like +=, -=) (${readWrites.size}):")
                for (rw in readWrites.take(50)) {
                    sb.appendLine("  ${rw.file}:${rw.line} — ${rw.lineText}")
                }
                if (readWrites.size > 50) {
                    sb.appendLine("  ... (${readWrites.size - 50} more)")
                }
            }

            sb.toString().trimEnd()
        }.inSmartMode(project).executeSynchronously()

        val isError = content.startsWith("Error:")
        return ToolResult(
            content = content,
            summary = if (isError) content else "Read/write analysis completed for $filePath",
            tokenEstimate = TokenEstimator.estimate(content),
            isError = isError
        )
    }

    private fun findTargetElement(leafElement: PsiElement): PsiElement? {
        // Java: PsiLocalVariable, PsiField, PsiParameter
        PsiTreeUtil.getParentOfType(leafElement, PsiLocalVariable::class.java, false)?.let { return it }
        PsiTreeUtil.getParentOfType(leafElement, PsiParameter::class.java, false)?.let { return it }
        PsiTreeUtil.getParentOfType(leafElement, PsiField::class.java, false)?.let { return it }

        // Kotlin: check via reflection
        return findKotlinTargetElement(leafElement)
    }

    private fun findKotlinTargetElement(element: PsiElement): PsiElement? {
        return try {
            val ktPropertyClass = Class.forName("org.jetbrains.kotlin.psi.KtProperty")
            val ktParameterClass = Class.forName("org.jetbrains.kotlin.psi.KtParameter")

            var current: PsiElement? = element
            while (current != null) {
                if (ktPropertyClass.isInstance(current) || ktParameterClass.isInstance(current)) {
                    return current
                }
                current = current.parent
            }
            null
        } catch (_: ClassNotFoundException) {
            null
        }
    }

    private fun classifyTargetKind(target: PsiElement): String {
        return when (target) {
            is PsiLocalVariable -> "local variable"
            is PsiParameter -> "parameter"
            is PsiField -> "field"
            else -> {
                // Kotlin types via reflection
                try {
                    val ktPropertyClass = Class.forName("org.jetbrains.kotlin.psi.KtProperty")
                    val ktParameterClass = Class.forName("org.jetbrains.kotlin.psi.KtParameter")
                    when {
                        ktPropertyClass.isInstance(target) -> "property"
                        ktParameterClass.isInstance(target) -> "parameter"
                        else -> "variable"
                    }
                } catch (_: ClassNotFoundException) {
                    "variable"
                }
            }
        }
    }

    private fun classifyAccess(element: PsiElement): AccessType {
        // Java: use PsiUtil.isAccessedForWriting / isAccessedForReading
        val expression = PsiTreeUtil.getParentOfType(element, PsiExpression::class.java, false)
        if (expression != null) {
            val isWrite = PsiUtil.isAccessedForWriting(expression)
            val isRead = PsiUtil.isAccessedForReading(expression)
            return when {
                isWrite && isRead -> AccessType.READ_WRITE
                isWrite -> AccessType.WRITE
                else -> AccessType.READ
            }
        }

        // Kotlin: check if parent is assignment LHS
        return classifyKotlinAccess(element)
    }

    private fun classifyKotlinAccess(element: PsiElement): AccessType {
        return try {
            val ktBinaryExprClass = Class.forName("org.jetbrains.kotlin.psi.KtBinaryExpression")
            val ktUnaryExprClass = Class.forName("org.jetbrains.kotlin.psi.KtUnaryExpression")

            var current: PsiElement? = element.parent
            while (current != null) {
                if (ktBinaryExprClass.isInstance(current)) {
                    val getOperationRef = ktBinaryExprClass.getMethod("getOperationReference")
                    val opRef = getOperationRef.invoke(current)
                    val opText = opRef?.javaClass?.getMethod("getText")?.invoke(opRef) as? String

                    val getLeft = ktBinaryExprClass.getMethod("getLeft")
                    val left = getLeft.invoke(current)

                    // Check if our element is on the left side of the assignment
                    val isOnLeft = if (left is PsiElement) {
                        PsiTreeUtil.isAncestor(left, element, false)
                    } else false

                    if (isOnLeft) {
                        return when (opText) {
                            "=" -> AccessType.WRITE
                            "+=", "-=", "*=", "/=", "%=" -> AccessType.READ_WRITE
                            else -> AccessType.READ
                        }
                    }
                    break
                }

                if (ktUnaryExprClass.isInstance(current)) {
                    val getOpText = try {
                        val getOperationRef = ktUnaryExprClass.getMethod("getOperationReference")
                        val opRef = getOperationRef.invoke(current)
                        opRef?.javaClass?.getMethod("getText")?.invoke(opRef) as? String
                    } catch (_: Exception) { null }

                    if (getOpText == "++" || getOpText == "--") {
                        return AccessType.READ_WRITE
                    }
                    break
                }

                current = current.parent
            }

            AccessType.READ
        } catch (_: ClassNotFoundException) {
            AccessType.READ
        } catch (_: Exception) {
            AccessType.READ
        }
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

    private fun getLineText(document: Document, zeroBasedLine: Int): String {
        if (zeroBasedLine < 0 || zeroBasedLine >= document.lineCount) return ""
        return document.getText(
            TextRange(
                document.getLineStartOffset(zeroBasedLine),
                document.getLineEndOffset(zeroBasedLine)
            )
        )
    }

    private data class AccessInfo(val file: String, val line: Int, val lineText: String)

    private enum class AccessType { READ, WRITE, READ_WRITE }
}
