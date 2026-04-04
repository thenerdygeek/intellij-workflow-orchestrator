package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.builtin.PathValidator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class FileStructureTool : AgentTool {
    override val name = "file_structure"
    override val description = "Get the structure of a file: class declarations, method signatures, fields. No method bodies — use read_file for full content."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "path" to ParameterProperty(type = "string", description = "Absolute or project-relative file path"),
            "detail" to ParameterProperty(
                type = "string",
                description = "Detail level: 'signatures' (default — class/method/field names only), 'full' (includes method bodies, annotations, field initializers), 'minimal' (class names + field/method counts only)"
            )
        ),
        required = listOf("path")
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        val rawPath = params["path"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'path' required", "Error: missing path", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val detail = params["detail"]?.jsonPrimitive?.content ?: "signatures"

        val (path, pathError) = PathValidator.resolveAndValidate(rawPath, project.basePath)
        if (pathError != null) return pathError

        val vFile = LocalFileSystem.getInstance().findFileByPath(path!!)
            ?: return ToolResult("Error: File not found: $path", "Error: file not found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        // Use nonBlocking read action — avoids blocking EDT and write actions
        val content = ReadAction.nonBlocking<String> {
            val psiFile = PsiManager.getInstance(project).findFile(vFile)
            when {
                psiFile is PsiJavaFile -> {
                    when (detail) {
                        "full" -> psiFile.text
                        "minimal" -> psiFile.classes.joinToString("\n") { cls ->
                            val methodCount = cls.methods.size
                            val fieldCount = cls.fields.size
                            "${cls.name} // $methodCount methods, $fieldCount fields"
                        }
                        else -> psiFile.classes.joinToString("\n\n") { PsiToolUtils.formatClassSkeleton(it) }
                    }
                }
                psiFile?.javaClass?.name?.contains("KtFile") == true -> {
                    PsiToolUtils.formatKotlinFileStructure(psiFile, detail) ?: "Error: Kotlin PSI unavailable"
                }
                else -> {
                    // Non-Java/Kotlin: return first 100 lines
                    val text = psiFile?.text ?: return@nonBlocking "Error: Cannot read file"
                    val lines = text.lines()
                    val shown = lines.take(100).joinToString("\n")
                    if (lines.size > 100) "$shown\n... (${lines.size - 100} more lines)" else shown
                }
            }
        }.inSmartMode(project).executeSynchronously()

        return ToolResult(
            content = content,
            summary = "Structure of $rawPath (${content.lines().size} lines)",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }
}
