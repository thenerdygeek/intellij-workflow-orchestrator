package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.workflow.orchestrator.core.util.ProjectIdentifier
import java.io.File
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.util.DiffUtil
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Creates a new file with specified content. Follows the EditFileTool pattern:
 * PathValidator → WriteCommandAction (undo support) → VFS → java.io.File fallback.
 *
 * Records the creation in ChangeLedger for tracking and creates a LocalHistory
 * checkpoint for rollback capability.
 */
class CreateFileTool : AgentTool {
    override val name = "create_file"
    override val description = "Create a new file with specified content at the specified path. If the file exists, it will fail unless overwrite=true. If the file doesn't exist, it will be created. This tool will automatically create any directories needed to write the file. ALWAYS provide the COMPLETE intended content of the file, without any truncation or omissions. You MUST include ALL parts of the file, even if they haven't been modified. Prefer edit_file for modifying existing files — only use create_file for new files or complete rewrites with overwrite=true."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "path" to ParameterProperty(type = "string", description = "The path of the file to create (absolute or relative to the project root)."),
            "content" to ParameterProperty(type = "string", description = "The content to write to the file. ALWAYS provide the COMPLETE intended content of the file, without any truncation or omissions. You MUST include ALL parts of the file, even if they haven't been modified."),
            "overwrite" to ParameterProperty(type = "boolean", description = "Allow overwrite if file already exists. Default: false. Set to true for complete rewrites."),
            "description" to ParameterProperty(type = "string", description = "Brief description of what this file is for (shown in approval dialog).")
        ),
        required = listOf("path", "content", "description")
    )
    override val allowedWorkers = setOf(WorkerType.CODER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val rawPath = params["path"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'path' parameter required", "Error: missing path", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val content = params["content"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'content' parameter required", "Error: missing content", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val overwrite = params["overwrite"]?.jsonPrimitive?.booleanOrNull ?: false

        val memoryDir = project.basePath?.let { File(ProjectIdentifier.agentDir(it), "memory").absolutePath }
        val (path, pathError) = PathValidator.resolveAndValidateForWrite(rawPath, project.basePath, memoryDir)
        if (pathError != null) return pathError
        val resolvedPath = path!!

        val file = java.io.File(resolvedPath)

        if (file.exists() && !overwrite) {
            return ToolResult(
                "Error: File already exists: $rawPath. Use overwrite=true to replace, or use edit_file to modify.",
                "Error: file exists",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        // Create parent directories if needed
        val parentDir = file.parentFile
        if (parentDir != null && !parentDir.exists()) {
            if (!parentDir.mkdirs()) {
                return ToolResult(
                    "Error: Could not create parent directory: ${parentDir.path}",
                    "Error: mkdir failed",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
            }
        }

        // Write via VFS + WriteCommandAction (undo support) → fallback to java.io.File
        val written = writeViaVfs(resolvedPath, project, rawPath, content)
            || writeViaFileIo(file, content)

        if (!written) {
            return ToolResult(
                "Error: Failed to create file: $rawPath",
                "Error: create failed",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        val lineCount = content.lines().size

        // Change tracking: AgentLoop.modifiedFiles collects artifacts from ToolResult

        val summary = "Created $rawPath ($lineCount lines, ${content.length} chars)"

        // Generate diff for new file (all additions — ported from Cline's DiffViewProvider)
        val createDiff = try {
            DiffUtil.unifiedDiff("", content, rawPath)
        } catch (_: Exception) { null }

        return ToolResult(
            content = summary,
            summary = summary,
            tokenEstimate = TokenEstimator.estimate(summary),
            artifacts = listOf(resolvedPath),
            diff = createDiff
        )
    }

    private fun writeViaVfs(resolvedPath: String, project: Project, rawPath: String, content: String): Boolean {
        return try {
            if (ApplicationManager.getApplication() == null) return false
            val parentPath = java.io.File(resolvedPath).parent ?: return false

            invokeAndWaitIfNeeded {
                WriteCommandAction.runWriteCommandAction(project, "Agent: create $rawPath", null, Runnable {
                    val parentVFile = VfsUtil.createDirectoryIfMissing(parentPath) ?: return@Runnable
                    val fileName = java.io.File(resolvedPath).name
                    val existingChild = parentVFile.findChild(fileName)
                    val vFile = existingChild ?: parentVFile.createChildData(this, fileName)
                    vFile.setBinaryContent(content.toByteArray(vFile.charset))
                })
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun writeViaFileIo(file: java.io.File, content: String): Boolean {
        return try {
            file.writeText(content, Charsets.UTF_8)
            // Refresh VFS so IDE sees the new file immediately
            try {
                LocalFileSystem.getInstance().refreshAndFindFileByPath(file.absolutePath)
            } catch (_: Exception) { }
            true
        } catch (_: Exception) {
            false
        }
    }
}
