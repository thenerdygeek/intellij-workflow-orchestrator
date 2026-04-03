package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.*
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

class EditFileTool : AgentTool {

    companion object {
        /**
         * Tracks the line range of the most recent edit per file (canonical path → IntRange).
         * Used by SemanticDiagnosticsTool to filter results to only LLM-introduced issues.
         * Cleared when diagnostics runs, so each edit→diagnostics cycle is fresh.
         */
        val lastEditLineRanges = java.util.concurrent.ConcurrentHashMap<String, IntRange>()
    }

    override val name = "edit_file"
    override val description = "Perform an exact string replacement in a file. The old_string must match exactly once unless replace_all is true."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "path" to ParameterProperty(type = "string", description = "Absolute or project-relative file path"),
            "old_string" to ParameterProperty(type = "string", description = "The exact text to find and replace. Must be unique in the file."),
            "new_string" to ParameterProperty(type = "string", description = "The replacement text."),
            "replace_all" to ParameterProperty(type = "boolean", description = "Replace all occurrences of old_string instead of requiring a unique match. Default: false."),
            "description" to ParameterProperty(type = "string", description = "Brief description of what this action does and why (shown to user in approval dialog)")
        ),
        required = listOf("path", "old_string", "new_string", "description")
    )
    override val allowedWorkers = setOf(WorkerType.CODER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val rawPath = params["path"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'path' parameter required", "Error: missing path", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val oldString = params["old_string"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'old_string' parameter required", "Error: missing old_string", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val newString = params["new_string"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'new_string' parameter required", "Error: missing new_string", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val replaceAll = params["replace_all"]?.jsonPrimitive?.boolean ?: false

        val (path, pathError) = PathValidator.resolveAndValidate(rawPath, project.basePath)
        if (pathError != null) return pathError
        val resolvedPath = path!!

        // File ownership check — workers cannot edit files owned by other workers
        val workerCtx = kotlin.coroutines.coroutineContext[WorkerContext]
        if (workerCtx != null && !workerCtx.isOrchestrator) {
            val registry = workerCtx.fileOwnership
            if (registry != null) {
                val claim = registry.claim(resolvedPath, workerCtx.agentId!!, workerCtx.workerType)
                if (claim.result == ClaimResult.DENIED) {
                    workerCtx.messageBus?.send(WorkerMessage(
                        from = workerCtx.agentId!!,
                        to = WorkerMessageBus.ORCHESTRATOR_ID,
                        type = MessageType.FILE_CONFLICT,
                        content = "Edit denied for '$resolvedPath' — locked by agent '${claim.ownerAgentId}'",
                        metadata = mapOf("file" to resolvedPath, "owner" to (claim.ownerAgentId ?: ""))
                    ))
                    return ToolResult(
                        "Error: File '$rawPath' is locked by agent '${claim.ownerAgentId}'. " +
                            "Wait for it to complete or ask the orchestrator to coordinate.",
                        "File locked by ${claim.ownerAgentId}",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )
                }
            }
        }

        // Try VFS-backed path first (undo support, sees unsaved changes), fall back to java.io.File
        val vFile = findVirtualFile(resolvedPath)
        val file = java.io.File(resolvedPath)

        if (vFile == null && (!file.exists() || !file.isFile)) {
            return ToolResult("Error: File not found: $resolvedPath", "Error: file not found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        // Read content: prefer Document (sees unsaved editor changes), then VFS, then java.io.File
        val content = readFileContent(vFile, file)

        val occurrences = countOccurrences(content, oldString)

        if (occurrences == 0) {
            return ToolResult(
                "Error: old_string not found in $rawPath. Verify the exact text including whitespace.",
                "Error: old_string not found",
                5,
                isError = true
            )
        }

        if (occurrences > 1 && !replaceAll) {
            return ToolResult(
                "Error: old_string found $occurrences times in $rawPath. Provide a larger, unique string with more context or use replace_all=true.",
                "Error: old_string not unique ($occurrences occurrences)",
                5,
                isError = true
            )
        }

        // Compute new content for syntax validation and fallback writes
        val newContent = if (replaceAll) content.replace(oldString, newString)
        else content.replaceFirst(oldString, newString)

        // Syntax validation gate: warn about syntax errors but don't block
        val extension = resolvedPath.substringAfterLast('.', "").lowercase()
        var syntaxWarning: String? = null
        if (extension in setOf("kt", "java")) {
            try {
                val errors = SyntaxValidator.validate(project, resolvedPath, newContent)
                if (errors.isNotEmpty()) {
                    val errorDetails = errors.joinToString("\n") { "  Line ${it.line}:${it.column}: ${it.message}" }
                    syntaxWarning = "WARNING: This edit introduced ${errors.size} syntax error(s). You should fix these:\n$errorDetails"
                }
            } catch (_: Exception) {
                // Syntax validation unavailable (e.g., no PSI in test) — proceed without gate
            }
        }

        // Apply the edit: try Document API (undo-aware), then VFS, then direct file I/O
        val written = writeViaDocument(vFile, project, rawPath, oldString, newString, replaceAll)
            || writeViaVfs(vFile, project, newContent)
            || writeViaFileIo(file, newContent)

        if (!written) {
            return ToolResult(
                "Error: Failed to write to $rawPath",
                "Error: write failed",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        // Track edited line range for diff-aware diagnostics
        var startLine = 0
        var endLine = 0
        try {
            val editStart = content.indexOf(oldString)
            if (editStart >= 0) {
                startLine = content.substring(0, editStart).count { it == '\n' } + 1
                val newLines = newString.count { it == '\n' } + 1
                endLine = startLine + newLines - 1
                lastEditLineRanges[java.io.File(resolvedPath).canonicalPath] = startLine..endLine
            }
        } catch (_: Exception) { /* tracking is best-effort */ }

        // Record in ChangeLedger + create LocalHistory checkpoint
        // COMPRESSION: The ledger entry captures structured change data that
        // survives in the changeLedgerAnchor even after this tool result
        // is pruned from context during Phase 1 tiered pruning.
        try {
            val agentService = AgentService.getInstance(project)
            val ledger = agentService.currentChangeLedger
            val rollback = agentService.currentRollbackManager
            val iteration = agentService.currentIteration ?: 0

            val checkpointId = rollback?.createCheckpoint("Edit $rawPath") ?: ""
            rollback?.trackFileChange(resolvedPath)

            val oldLineCount = content.lines().size
            val newLineCount = newContent.lines().size
            ledger?.recordChange(com.workflow.orchestrator.agent.runtime.ChangeEntry(
                id = java.util.UUID.randomUUID().toString().take(12),
                sessionId = "",
                iteration = iteration,
                timestamp = System.currentTimeMillis(),
                filePath = resolvedPath,
                relativePath = rawPath,
                toolName = "edit_file",
                action = com.workflow.orchestrator.agent.runtime.ChangeAction.MODIFIED,
                linesAdded = (newLineCount - oldLineCount).coerceAtLeast(0),
                linesRemoved = (oldLineCount - newLineCount).coerceAtLeast(0),
                linesBefore = oldLineCount,
                linesAfter = newLineCount,
                // COMPRESSION: Previews capped to control anchor token budget
                oldPreview = oldString.take(com.workflow.orchestrator.agent.runtime.ChangeLedger.MAX_PREVIEW_CHARS),
                newPreview = newString.take(com.workflow.orchestrator.agent.runtime.ChangeLedger.MAX_PREVIEW_CHARS),
                editLineRange = "$startLine-$endLine",
                checkpointId = checkpointId
            ))

            // Update context anchor so LLM sees the change immediately
            // COMPRESSION: Re-renders changeLedgerAnchor from full ledger
            if (ledger != null) {
                agentService.currentContextBridge?.updateChangeLedgerAnchor(ledger)
            }
        } catch (_: Exception) { /* ledger recording is best-effort */ }

        // Build diff context (3 lines before/after edit) for LLM verification
        val contextLines = try {
            val newFileContent = readFileContent(vFile, file)
            val allNewLines = newFileContent.lines()
            val contextStart = (startLine - 4).coerceAtLeast(0)
            val contextEnd = (endLine + 3).coerceAtMost(allNewLines.size)
            if (contextStart < contextEnd) {
                allNewLines.subList(contextStart, contextEnd).mapIndexed { idx, line ->
                    "${contextStart + idx + 1}\t$line"
                }.joinToString("\n")
            } else null
        } catch (_: Exception) { null }

        val contextSection = if (contextLines != null) "\n\nContext after edit:\n$contextLines" else ""
        val occurrenceSuffix = if (replaceAll && occurrences > 1) " ($occurrences occurrences)" else ""
        val summary = "Replaced ${oldString.length} chars with ${newString.length} chars in $rawPath$occurrenceSuffix"

        if (syntaxWarning != null) {
            return ToolResult(
                content = "$summary\n$syntaxWarning$contextSection",
                summary = "$summary (${syntaxWarning.count { it == '\n' }} syntax warnings)",
                tokenEstimate = TokenEstimator.estimate(summary + syntaxWarning + (contextLines ?: "")),
                artifacts = listOf(resolvedPath),
                isError = false
            )
        }

        return ToolResult(
            content = "$summary$contextSection",
            summary = summary,
            tokenEstimate = TokenEstimator.estimate(summary + (contextLines ?: "")),
            artifacts = listOf(resolvedPath)
        )
    }

    /**
     * Find VirtualFile via LocalFileSystem. Returns null if VFS is unavailable (e.g., unit tests).
     */
    private fun findVirtualFile(resolvedPath: String): VirtualFile? {
        return try {
            if (ApplicationManager.getApplication() == null) return null
            LocalFileSystem.getInstance().findFileByPath(resolvedPath)
                ?: LocalFileSystem.getInstance().refreshAndFindFileByPath(resolvedPath)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Read file content. Prefers Document (sees unsaved editor changes), then VFS, then java.io.File.
     */
    private suspend fun readFileContent(vFile: VirtualFile?, file: java.io.File): String {
        if (vFile != null) {
            try {
                return readAction {
                    val document = FileDocumentManager.getInstance().getDocument(vFile)
                    document?.text ?: String(vFile.contentsToByteArray(), vFile.charset)
                }
            } catch (_: Exception) {
                // readAction unavailable — fall through to java.io.File
            }
        }
        return file.readText(Charsets.UTF_8)
    }

    /**
     * Write via Document API + WriteCommandAction. Provides undo support and immediate editor sync.
     * Returns true if write succeeded via Document.
     */
    private fun writeViaDocument(
        vFile: VirtualFile?,
        project: Project,
        rawPath: String,
        oldString: String,
        newString: String,
        replaceAll: Boolean
    ): Boolean {
        if (vFile == null) return false
        return try {
            var success = false
            invokeAndWaitIfNeeded {
                WriteCommandAction.runWriteCommandAction(project, "Agent: edit $rawPath", null, Runnable {
                    val document = FileDocumentManager.getInstance().getDocument(vFile) ?: return@Runnable
                    if (replaceAll) {
                        // Replace all occurrences from end to preserve offsets
                        var text = document.text
                        var offset = text.lastIndexOf(oldString)
                        while (offset >= 0) {
                            document.replaceString(offset, offset + oldString.length, newString)
                            text = document.text
                            offset = if (offset > 0) text.lastIndexOf(oldString, offset - 1) else -1
                        }
                    } else {
                        val offset = document.text.indexOf(oldString)
                        if (offset >= 0) {
                            document.replaceString(offset, offset + oldString.length, newString)
                        }
                    }
                    success = true
                })
            }
            success
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Write via VFS setBinaryContent inside WriteCommandAction.
     * Used when Document is null (file not open in editor).
     */
    private fun writeViaVfs(vFile: VirtualFile?, project: Project, newContent: String): Boolean {
        if (vFile == null) return false
        return try {
            invokeAndWaitIfNeeded {
                WriteCommandAction.runWriteCommandAction(project) {
                    vFile.setBinaryContent(newContent.toByteArray(vFile.charset))
                }
            }
            // Force VFS refresh so IDE diagnostics see changes immediately
            // COMPRESSION: Not compression-related, but ensures SelfCorrectionGate
            // gets fresh diagnostics without 1-2s VFS watcher delay
            try { vFile.refresh(false, false) } catch (_: Exception) { }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Final fallback: direct java.io.File write. Used in test environments without full IDE.
     */
    private fun writeViaFileIo(file: java.io.File, newContent: String): Boolean {
        return try {
            file.writeText(newContent, Charsets.UTF_8)
            // Refresh VFS so IDE sees the change
            try { LocalFileSystem.getInstance().refreshAndFindFileByPath(file.absolutePath) } catch (_: Exception) { }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun countOccurrences(text: String, search: String): Int {
        var count = 0
        var startIndex = 0
        while (true) {
            val index = text.indexOf(search, startIndex)
            if (index < 0) break
            count++
            startIndex = index + 1
        }
        return count
    }
}
