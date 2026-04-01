package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.ChangeAction
import com.workflow.orchestrator.agent.runtime.ChangeEntry
import com.workflow.orchestrator.agent.runtime.ChangeLedger
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
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
    override val description = "Create a new file with specified content. " +
        "Use this for creating new source files, test files, config files, etc. " +
        "Parent directories are created automatically. " +
        "Fails if file already exists unless overwrite=true."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "path" to ParameterProperty(type = "string", description = "Absolute or project-relative file path for the new file"),
            "content" to ParameterProperty(type = "string", description = "Content to write to the new file"),
            "overwrite" to ParameterProperty(type = "boolean", description = "Allow overwrite if file already exists. Default: false"),
            "description" to ParameterProperty(type = "string", description = "Brief description of what this file is for (shown in approval dialog)")
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

        val (path, pathError) = PathValidator.resolveAndValidate(rawPath, project.basePath)
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

        // Track in EditFileTool.lastEditLineRanges for diff-aware diagnostics
        try {
            val lineCount = content.count { it == '\n' } + 1
            EditFileTool.lastEditLineRanges[file.canonicalPath] = 1..lineCount
        } catch (_: Exception) { }

        // Record in ChangeLedger + create LocalHistory checkpoint
        // COMPRESSION: The ledger entry captures structured change data that
        // survives in the changeLedgerAnchor even after this tool result
        // is pruned from context during Phase 1 tiered pruning.
        try {
            val agentService = AgentService.getInstance(project)
            val ledger = agentService.currentChangeLedger
            val rollback = agentService.currentRollbackManager
            val iteration = agentService.currentIteration ?: 0

            val checkpointId = rollback?.createCheckpoint("Create $rawPath") ?: ""

            val lineCount = content.lines().size
            ledger?.recordChange(ChangeEntry(
                id = java.util.UUID.randomUUID().toString().take(12),
                sessionId = "",
                iteration = iteration,
                timestamp = System.currentTimeMillis(),
                filePath = resolvedPath,
                relativePath = rawPath,
                toolName = "create_file",
                action = ChangeAction.CREATED,
                linesAdded = lineCount,
                linesRemoved = 0,
                linesBefore = 0,
                linesAfter = lineCount,
                oldPreview = "",
                newPreview = content.take(ChangeLedger.MAX_PREVIEW_CHARS),
                editLineRange = "1-$lineCount",
                checkpointId = checkpointId
            ))

            // Update context anchor so LLM sees the change immediately
            // COMPRESSION: This keeps the changeLedgerAnchor current.
            agentService.currentContextBridge?.updateChangeLedgerAnchor(ledger!!)
        } catch (_: Exception) { /* ledger recording is best-effort */ }

        val lineCount = content.lines().size
        val summary = "Created $rawPath ($lineCount lines, ${content.length} chars)"
        return ToolResult(
            content = summary,
            summary = summary,
            tokenEstimate = TokenEstimator.estimate(summary),
            artifacts = listOf(resolvedPath)
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
