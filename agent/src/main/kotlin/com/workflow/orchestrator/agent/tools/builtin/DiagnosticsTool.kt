package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Returns compilation diagnostics (syntax errors) for a file using PSI parsing.
 * For Java and Kotlin files, parses the file and collects PsiErrorElement nodes.
 * For other file types, returns "no diagnostics available."
 */
class DiagnosticsTool : AgentTool {
    override val name = "diagnostics"
    override val description = "Check a file for syntax/compilation errors. Returns error locations and messages. Use after editing files to verify no errors were introduced."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "path" to ParameterProperty(type = "string", description = "Absolute or project-relative file path to check (e.g., 'src/main/kotlin/MyService.kt')")
        ),
        required = listOf("path")
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val rawPath = params["path"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'path' parameter required", "Error: missing path", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val (path, pathError) = PathValidator.resolveAndValidate(rawPath, project.basePath)
        if (pathError != null) return pathError
        val file = File(path!!)

        if (!file.exists() || !file.isFile) {
            return ToolResult("Error: File not found: $path", "Error: file not found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        val extension = file.extension.lowercase()
        if (extension !in setOf("kt", "java")) {
            return ToolResult(
                "No diagnostics available for .$extension files. Diagnostics are supported for .kt and .java files.",
                "No diagnostics for .$extension",
                5
            )
        }

        if (DumbService.isDumb(project)) {
            return ToolResult(
                "IDE is still indexing. Try again in a moment.",
                "Indexing in progress",
                5,
                isError = true
            )
        }

        return try {
            ReadAction.compute<ToolResult, Exception> {
                val vf = LocalFileSystem.getInstance().findFileByIoFile(file)
                    ?: return@compute ToolResult("Error: Cannot access file: $path", "Error: file access", 5, isError = true)

                val psiFile = PsiManager.getInstance(project).findFile(vf)
                    ?: return@compute ToolResult("Error: Cannot parse file: $path", "Error: file parse", 5, isError = true)

                val errors = PsiTreeUtil.collectElementsOfType(psiFile, PsiErrorElement::class.java)

                if (errors.isEmpty()) {
                    val result = "No errors found in ${file.name}."
                    ToolResult(result, result, TokenEstimator.estimate(result))
                } else {
                    val errorLines = errors.map { error ->
                        val doc = psiFile.viewProvider.document
                        val offset = error.textOffset
                        val line = doc?.getLineNumber(offset)?.plus(1) ?: 0
                        val col = if (doc != null) offset - doc.getLineStartOffset(doc.getLineNumber(offset)) + 1 else 0
                        "  Line $line:$col: ${error.errorDescription}"
                    }
                    val result = "${errors.size} error(s) in ${file.name}:\n${errorLines.joinToString("\n")}"
                    ToolResult(result, "${errors.size} errors in ${file.name}", TokenEstimator.estimate(result), isError = true)
                }
            }
        } catch (e: Exception) {
            ToolResult(
                "Error running diagnostics: ${e.message}",
                "Diagnostics error",
                5,
                isError = true
            )
        }
    }
}
