package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.memory.MemoryIndex
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.util.ProjectIdentifier
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Deletes a file at a project-relative or absolute path. Uses VFS + WriteCommandAction so
 * the deletion is recorded in IntelliJ Local History — recoverable via `Edit → Local History
 * → Show History`. The file is NOT moved to the OS trash; once Local History expires (default
 * ~5 days), the deletion is permanent. Falls back to `java.io.File.delete` if the VFS path is
 * unavailable (no Local History entry in that case). Routes through PathValidator's allowlist
 * (project root + `{agentDir}/memory/`).
 *
 * When the deleted path is a memory file (under `{agentDir}/memory/` and not `MEMORY.md`),
 * the corresponding entry is removed from `MEMORY.md` via `MemoryIndex.onMemoryFileDeleted`.
 * The hook is best-effort: a failure to update the index never fails the delete itself.
 *
 * Gated by `PluginSettings.memoryAutoIndexEnabled` (default true) — disabling skips the
 * MemoryIndex hook only; the delete itself still runs.
 */
class DeleteFileTool : AgentTool {
    private val log = Logger.getInstance(DeleteFileTool::class.java)

    override val name = "delete_file"
    override val description = "Delete a file at the specified path. The deletion is recorded in IntelliJ's Local History (recoverable via Edit → Local History → Show History) but is NOT sent to the OS trash. Use for retiring stale files, including stale memory files under {agentDir}/memory/."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "path" to ParameterProperty(type = "string", description = "The path of the file to delete (absolute or relative to the project root)."),
            "description" to ParameterProperty(type = "string", description = "Brief reason for the deletion (shown in approval dialog).")
        ),
        required = listOf("path", "description")
    )
    override val allowedWorkers = setOf(WorkerType.CODER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val rawPath = params["path"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'path' parameter required", "Error: missing path", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val memoryDirPath = project.basePath?.let { File(ProjectIdentifier.agentDir(it), "memory").absolutePath }
        val (path, pathError) = PathValidator.resolveAndValidateForWrite(rawPath, project.basePath, memoryDirPath)
        if (pathError != null) return pathError
        val resolvedPath = path!!

        val file = File(resolvedPath)
        if (!file.exists()) {
            return ToolResult(
                "Error: File does not exist: $rawPath",
                "Error: file not found",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
        if (file.isDirectory) {
            return ToolResult(
                "Error: Path is a directory, not a file: $rawPath",
                "Error: path is directory",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        val deleted = deleteViaVfs(resolvedPath, project, rawPath) || deleteViaFileIo(file)
        if (!deleted) {
            return ToolResult(
                "Error: Failed to delete file: $rawPath",
                "Error: delete failed",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        // Auto-sync MEMORY.md if the deleted file was under the memory dir (and wasn't MEMORY.md itself).
        // Best-effort: failures are logged, never propagated.
        // ClassCastException only occurs in relaxed MockK tests where the generic service
        // return type resolves to Object — treated as "settings unavailable, use default (enabled)".
        val autoIndexEnabled = try {
            PluginSettings.getInstance(project).state.memoryAutoIndexEnabled
        } catch (_: ClassCastException) {
            true
        }
        if (autoIndexEnabled) {
            tryMemoryIndexHook(project, file)
        }

        val summary = "Deleted $rawPath"
        return ToolResult(
            content = summary,
            summary = summary,
            tokenEstimate = TokenEstimator.estimate(summary),
            artifacts = listOf(resolvedPath)
        )
    }

    private suspend fun deleteViaVfs(resolvedPath: String, project: Project, rawPath: String): Boolean {
        return try {
            if (ApplicationManager.getApplication() == null) return false
            // writeAction (com.intellij.openapi.application) is the coroutine-friendly replacement
            // for invokeAndWaitIfNeeded { WriteCommandAction.runWriteCommandAction { } }: it suspends
            // the caller coroutine, switches to EDT, acquires the write lock, runs the block, and
            // returns — without blocking the IO thread (audit finding agent-tools:F-1).
            writeAction {
                val vFile = LocalFileSystem.getInstance().findFileByIoFile(File(resolvedPath))
                    ?: return@writeAction false
                vFile.delete(this@DeleteFileTool)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun deleteViaFileIo(file: File): Boolean {
        return try {
            val ok = file.delete()
            try { LocalFileSystem.getInstance().refreshAndFindFileByPath(file.absolutePath) } catch (_: Exception) {}
            ok
        } catch (_: Exception) {
            false
        }
    }

    private fun tryMemoryIndexHook(project: Project, deletedFile: File) {
        try {
            val memoryDir = project.basePath?.let { File(ProjectIdentifier.agentDir(it), "memory") } ?: return
            if (!memoryDir.exists()) return
            if (deletedFile.parentFile?.absolutePath != memoryDir.absolutePath) return
            if (deletedFile.name == "MEMORY.md") return
            MemoryIndex.onMemoryFileDeleted(memoryDir.toPath(), deletedFile.name)
        } catch (t: Throwable) {
            log.warn("MemoryIndex.onMemoryFileDeleted failed for ${deletedFile.name}", t)
        }
    }
}
