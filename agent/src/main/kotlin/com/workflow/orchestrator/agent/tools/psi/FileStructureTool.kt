package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class FileStructureTool : AgentTool {
    override val name = "file_structure"
    override val description = "Get the structure of a file: class declarations, method signatures, fields. No method bodies — use read_file for full content."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "path" to ParameterProperty(type = "string", description = "Absolute or project-relative file path")
        ),
        required = listOf("path")
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        val rawPath = params["path"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'path' required", "Error: missing path", 5, isError = true)

        val path = if (rawPath.startsWith("/")) rawPath else "${project.basePath}/$rawPath"
        val vFile = LocalFileSystem.getInstance().findFileByPath(path)
            ?: return ToolResult("Error: File not found: $path", "Error: file not found", 5, isError = true)

        // Use nonBlocking read action — avoids blocking EDT and write actions
        val content = ReadAction.nonBlocking<String> {
            val psiFile = PsiManager.getInstance(project).findFile(vFile)
            if (psiFile is PsiJavaFile) {
                psiFile.classes.joinToString("\n\n") { PsiToolUtils.formatClassSkeleton(it) }
            } else {
                // Non-Java files: return first 50 lines as fallback
                val text = psiFile?.text ?: return@nonBlocking "Error: Cannot read file"
                text.lines().take(50).joinToString("\n")
            }
        }.inSmartMode(project).executeSynchronously()

        return ToolResult(
            content = content,
            summary = "Structure of $rawPath (${content.lines().size} lines)",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }
}
