package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
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

class ReadWriteAccessTool(
    private val registry: LanguageProviderRegistry
) : AgentTool {
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
                ?: return@nonBlocking "No element found at this position"

            // Use the provider's findSymbolAt to locate the target variable/field/parameter
            val target = provider.findSymbolAt(psiFile, offset)
                ?: return@nonBlocking "No variable, field, or parameter found at this position"

            val targetName = (target as? PsiNamedElement)?.name ?: "unknown"
            val targetLine = document?.let { doc ->
                doc.getLineNumber(target.textOffset) + 1
            } ?: 0
            val targetFileName = PsiToolUtils.relativePath(project, psiFile.virtualFile.path)

            // Determine search scope
            val searchScope: SearchScope = when (scopeParam) {
                "file" -> LocalSearchScope(psiFile)
                else -> GlobalSearchScope.projectScope(project)
            }

            // Delegate access classification to the provider
            val classification = provider.classifyAccesses(target, searchScope)

            if (classification.reads.isEmpty() && classification.writes.isEmpty() && classification.readWrites.isEmpty()) {
                return@nonBlocking "Read/Write analysis for '$targetName' ($targetFileName:$targetLine)\n\nNo references found."
            }

            val sb = StringBuilder()
            sb.appendLine("Read/Write analysis for '$targetName' ($targetFileName:$targetLine)")
            sb.appendLine()

            sb.appendLine("Writes (${classification.writes.size}):")
            if (classification.writes.isEmpty()) {
                sb.appendLine("  (none)")
            } else {
                for (w in classification.writes.take(50)) {
                    sb.appendLine("  ${w.filePath}:${w.line} — ${w.context}")
                }
                if (classification.writes.size > 50) {
                    sb.appendLine("  ... (${classification.writes.size - 50} more)")
                }
            }

            sb.appendLine()
            sb.appendLine("Reads (${classification.reads.size}):")
            if (classification.reads.isEmpty()) {
                sb.appendLine("  (none)")
            } else {
                for (r in classification.reads.take(50)) {
                    sb.appendLine("  ${r.filePath}:${r.line} — ${r.context}")
                }
                if (classification.reads.size > 50) {
                    sb.appendLine("  ... (${classification.reads.size - 50} more)")
                }
            }

            if (classification.readWrites.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("Read+Write (compound assignments like +=, -=) (${classification.readWrites.size}):")
                for (rw in classification.readWrites.take(50)) {
                    sb.appendLine("  ${rw.filePath}:${rw.line} — ${rw.context}")
                }
                if (classification.readWrites.size > 50) {
                    sb.appendLine("  ... (${classification.readWrites.size - 50} more)")
                }
            }

            sb.toString().trimEnd()
        }.inSmartMode(project).executeSynchronously()

        val isError = content.startsWith("Error:") || content.startsWith("Code intelligence not available")
        return ToolResult(
            content = content,
            summary = if (isError) content else "Read/write analysis completed for $filePath",
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
}
