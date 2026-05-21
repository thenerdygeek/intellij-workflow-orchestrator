package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.encoding.EncodingProjectManager
import com.workflow.orchestrator.agent.memory.MemoryIndex
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.util.ProjectIdentifier
import com.workflow.orchestrator.core.vfs.PostMutationRefresh
import java.io.File
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.util.DiffUtil
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
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
    private val log = Logger.getInstance(CreateFileTool::class.java)

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

    override fun documentation(): ToolDocumentation = toolDoc("create_file") {
        summary {
            technical("Create a new file (or overwrite-on-flag) at a project-relative or absolute path. Routes through PathValidator (project root + `{agentDir}/memory/` only), auto-creates missing parent directories via VfsUtil/mkdirs, writes via WriteCommandAction so the change is undoable from the IDE's Edit menu, and falls back to direct java.io.File + LocalFileSystem.refresh if the VFS path is unavailable. Refuses to clobber existing files unless `overwrite=true`. Both write paths (VFS and I/O fallback) use the project's configured charset via EncodingProjectManager.")
            plain("Like hitting File → New → Save in your editor: type a path, type the contents, and the file appears on disk and in IntelliJ's project view. Won't quietly stomp a file that already exists — you have to explicitly say 'yes, replace it'.")
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.FILE_WRITE)
        counterfactual(
            "Without create_file, the LLM falls back to `run_command \"cat > path/to/file <<'EOF' … EOF\"` — which bypasses PathValidator (so the LLM could write to `/etc/`, `~/.ssh/`, or another project), bypasses the IntelliJ Document/VFS API (no IDE undo, no editor refresh, stale buffers in open editors), produces no unified diff for the approval dialog, and runs through run_command's stricter `ALWAYS_PER_INVOCATION` approval gate instead of the friendlier `SESSION_APPROVABLE` one. Net cost: a real path-traversal regression plus a worse UX on every single new-file write."
        )
        llmMistake("Reaches for create_file with `overwrite=true` to make a small change to an existing file — re-writing all 800 lines instead of using edit_file. Wastes output tokens, defeats the per-edit diff that ships to the approval dialog, and makes the change impossible to review at a glance.")
        llmMistake("Forgets that a file with that path already exists, calls without `overwrite=true`, gets the `file exists` error, then panics and retries with overwrite — when the right move was edit_file all along.")
        llmMistake("Truncates content with `// ... rest of file unchanged` placeholders. The description spells out 'COMPLETE intended content' and 'NO truncation', but the LLM still does it; the result is a file with literal `…` ellipses on disk that breaks the next compile.")
        llmMistake("Tries to `create_file` outside the project — most often into `/tmp/`, the user home, or a sibling repo to 'stage' something. PathValidator rejects with 'path outside project'; the only out-of-project root that works is `{agentDir}/memory/` for agent memory files.")
        params {
            required("path", "string") {
                llmSeesIt("The path of the file to create (absolute or relative to the project root).")
                humanReadable("Where the new file should land. Relative paths are resolved against the project root; absolute paths are accepted but must canonicalise back into the project (or `{agentDir}/memory/`).")
                whenPresent("Path is canonicalised, validated against project + memory roots, and the file is created at that location. Missing parent directories are created automatically (mkdirs / VfsUtil.createDirectoryIfMissing).")
                constraint("must resolve inside the project root or `{agentDir}/memory/` — anywhere else is rejected before any I/O")
                constraint("`..`-style traversal is canonicalised away before the boundary check")
                example("src/main/kotlin/com/foo/NewService.kt")
                example("docs/research/2026-05-09-followups.md")
                example(".workflow/skills/my-skill/SKILL.md")
            }
            required("content", "string") {
                llmSeesIt("The content to write to the file. ALWAYS provide the COMPLETE intended content of the file, without any truncation or omissions. You MUST include ALL parts of the file, even if they haven't been modified.")
                humanReadable("The full text of the file. Whatever you put here is exactly what hits disk — no template, no merge, no comment-stripping. Line endings and trailing newlines are preserved verbatim; the tool does not normalise CRLF to LF.")
                whenPresent("Bytes are written verbatim via VirtualFile.setBinaryContent (using the file's detected charset) or, on the fallback path, `File.writeText(EncodingProjectManager.getInstance(project).defaultCharset)`. Both paths use the same project-level charset so bytes are consistent regardless of which write path triggers.")
                constraint("MUST be the entire intended file body — placeholders like `// ... unchanged ...` end up literally on disk")
                example("package com.foo\n\nclass NewService {\n    fun hello() = \"world\"\n}\n")
            }
            required("description", "string") {
                llmSeesIt("Brief description of what this file is for (shown in approval dialog).")
                humanReadable("One-line human-readable rationale shown in the user's approval dialog. Not written to the file. Required so the user has context when granting approval.")
                whenPresent("Surfaces verbatim in the approval gate UI alongside the unified diff.")
                example("New service class for the X feature")
                example("Test fixture covering the empty-project branch")
            }
            optional("overwrite", "boolean") {
                llmSeesIt("Allow overwrite if file already exists. Default: false. Set to true for complete rewrites.")
                humanReadable("Safety latch. False (default) means 'I expect this file to be brand new — fail if it isn't'. True means 'I really do want to replace whatever is on disk with the content I'm about to send'.")
                whenPresent("If true, an existing file at `path` is replaced. The approval gate shows a real unified diff between the prior content and the new content so reviewers can see exactly what changes. If false (default), an existing file causes the call to error out with a `file exists` message that nudges toward edit_file.")
                whenAbsent("Defaults to false — the call errors if the target file already exists, protecting the user from accidental clobber.")
                example("true")
            }
        }
        verdict {
            keep(
                "Foundational write primitive. New file creation is one of the most common operations in any coding agent (new tests, new modules, new docs, new memory files), and create_file is the only path that combines PathValidator's safety, the IntelliJ Document/VFS API's undo + editor-refresh, parent-directory auto-creation, and a unified diff for the approval gate. Removing it forces the LLM onto raw shell heredocs, which regresses on safety, UX, and reviewability.",
                VerdictSeverity.STRONG,
            )
        }
        related("edit_file", Relationship.ALTERNATIVE, "Use this instead when you're modifying an EXISTING file — edit_file produces a targeted diff, preserves unrelated content, and is the right tool for nearly every change to an already-tracked file.")
        related("read_file", Relationship.COMPLEMENT, "Use after a create_file to confirm what landed on disk, especially before chaining further edits — read_file sees unsaved Document state, so it confirms the IDE picked up the change.")
        related("revert_file", Relationship.FALLBACK, "Use to undo a botched create_file before any further work touches the file. Pairs with the LocalHistory checkpoint create_file leaves behind.")
        related("glob_files", Relationship.COMPOSE_WITH, "Run before create_file when generating multiple files in a directory — confirms the directory layout and catches name collisions in advance.")
        downside("Writes through the `description` parameter into the approval dialog — but the parameter shares its name with the tool's own `description` LLM-facing field, which is a recurring source of authoring confusion when reading the source.")
        downside("Auto-creates parent directories without prompting. Convenient when intentional, but a typo in `path` (`src/maim/...` instead of `src/main/...`) will silently produce a stray directory tree that the user has to clean up manually.")
        downside("Plan mode blocks this tool entirely (it's in `WRITE_TOOLS`). Even an LLM that hallucinates a create_file call in plan mode will be rejected at the AgentLoop execution guard.")
        downside("Approval is required (`ApprovalPolicy.SESSION_APPROVABLE`) — the user can grant 'allow for session', but the first call always pauses the loop for human confirmation. In headless / CI-style usage this is a friction point.")
        downside("Empty content produces a 0-byte file rather than erroring. Sometimes useful (placeholder files, .gitkeep), occasionally surprising.")
        observation("`overwrite=true` could plausibly be split into a separate `overwrite_file` tool — keeping `create_file` strictly for new-file creation would tighten the description and eliminate the 'reach for overwrite instead of edit_file' failure mode.")
        observation("Bug fixed — two correctness issues surfaced by Phase 5 tool-docs swarm (Batch 3): (1) the I/O fallback now uses EncodingProjectManager.defaultCharset instead of hardcoded UTF-8, eliminating the charset asymmetry on non-UTF-8 projects; (2) overwrite=true now reads existing content and computes a real unified diff (existing → new) for the approval modal instead of always showing \"\" → new content.")
        flowchart("""
            flowchart TD
                A[LLM calls create_file] --> B{Path validates?}
                B -- no --> X1[Return path-outside-project error]
                B -- yes --> C{File exists?}
                C -- yes & overwrite=false --> X2[Return file-exists error<br/>nudge toward edit_file]
                C -- no, or overwrite=true --> D{Parent dir exists?}
                D -- no --> E[mkdirs / VfsUtil.createDirectoryIfMissing]
                E -- failed --> X3[Return mkdir-failed error]
                E -- ok --> F[Write via VFS + WriteCommandAction]
                D -- yes --> F
                F -- ok --> G[Return summary + unified diff + artifact]
                F -- failed --> H[Fallback: java.io.File.writeText project-charset]
                H -- ok --> I[LocalFileSystem.refresh]
                I --> G
                H -- failed --> X4[Return create-failed error]
        """)
    }

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

        // Capture existing content before overwrite so the approval diff shows what actually changes.
        // Only attempted when overwrite=true and the file exists; on any read failure fall back to "".
        val existingContent: String = if (overwrite && file.exists()) {
            try {
                val charset = EncodingProjectManager.getInstance(project).defaultCharset
                file.readText(charset)
            } catch (_: Exception) { "" }
        } else {
            ""
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
            || writeViaFileIo(file, content, project)

        if (!written) {
            return ToolResult(
                "Error: Failed to create file: $rawPath",
                "Error: create failed",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        // Auto-sync MEMORY.md if this is a memory file (and not MEMORY.md itself).
        // Best-effort: failures never propagate.
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

        // Drop JPS's in-memory incremental-build snapshot so the next
        // CompilerManager.make / ProjectTaskManager.build re-reads source
        // stamps from disk. Brand-new files (especially new test classes) are
        // the primary trigger for the "newly-added test method not found until
        // restart" symptom — the prior dependency graph has no entry for the
        // new file and may not be invalidated by the VFS change-listener
        // chain, leaving the next build a silent no-op. Mirrors the same
        // best-effort call in EditFileTool. Never let a cache-clear failure
        // block a successful write.
        try {
            if (ApplicationManager.getApplication() != null) {
                PostMutationRefresh.clearJpsCache(project)
            }
        } catch (_: Exception) { /* best-effort */ }

        val lineCount = content.lines().size

        // Change tracking: AgentLoop.modifiedFiles collects artifacts from ToolResult

        val summary = "Created $rawPath ($lineCount lines, ${content.length} chars)"

        // Generate unified diff for the approval gate.
        // For overwrite=true on an existing file, diff existing content → new content so the
        // reviewer sees what actually changed (not a misleading full-file addition).
        // For a brand-new file, diff "" → new content (all additions).
        val createDiff = try {
            DiffUtil.unifiedDiff(existingContent, content, rawPath)
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

    private fun writeViaFileIo(file: java.io.File, content: String, project: Project): Boolean {
        return try {
            // Use the project's configured default charset so the fallback path writes the same
            // bytes as the VFS path (which uses VirtualFile.charset). Hardcoding Charsets.UTF_8
            // here was the correctness bug: on a non-UTF-8 project (e.g. Shift_JIS, CP1252, GBK)
            // the same create_file call wrote different bytes depending on which path triggered.
            val charset = try {
                EncodingProjectManager.getInstance(project).defaultCharset
            } catch (_: Exception) {
                Charsets.UTF_8
            }
            file.writeText(content, charset)
            // Refresh VFS so IDE sees the new file immediately
            try {
                LocalFileSystem.getInstance().refreshAndFindFileByPath(file.absolutePath)
            } catch (_: Exception) { }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun tryMemoryIndexHook(project: Project, createdFile: java.io.File) {
        try {
            val memoryDir = project.basePath?.let {
                java.io.File(ProjectIdentifier.agentDir(it), "memory")
            } ?: return
            if (!memoryDir.exists()) return
            if (createdFile.parentFile?.absolutePath != memoryDir.absolutePath) return
            if (createdFile.name == "MEMORY.md") return
            MemoryIndex.onMemoryFileCreated(memoryDir.toPath(), createdFile.toPath())
        } catch (t: Throwable) {
            log.warn("MemoryIndex.onMemoryFileCreated failed for ${createdFile.name}", t)
        }
    }
}
