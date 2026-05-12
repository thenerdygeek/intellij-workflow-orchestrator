package com.workflow.orchestrator.agent.tools.builtin

import com.workflow.orchestrator.agent.tools.ToolResult
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Validates that file paths from LLM tool calls stay within allowed directories.
 * Prevents path traversal attacks (../../etc/passwd).
 *
 * All file tools (read, edit, search, diagnostics) MUST use this before
 * accessing the filesystem.
 */
object PathValidator {

    /** The agent's data directory under the user's home. */
    private const val AGENT_DATA_DIR = ".workflow-orchestrator"

    /**
     * Resolve a raw path and validate it stays within the project.
     * Write tools (edit_file, create_file) should use this — project-only access.
     *
     * @return canonical path if valid, or ToolResult error if traversal detected
     */
    fun resolveAndValidate(rawPath: String, projectBasePath: String?): Pair<String?, ToolResult?> {
        return resolveAndValidate(rawPath, projectBasePath, allowAgentDataDir = false)
    }

    /**
     * Resolve a raw path and validate it stays within the project OR the
     * `~/.workflow-orchestrator/` agent data directory.
     *
     * Read-only tools (read_file, glob_files, search_code) may use this
     * so the agent can access its own persisted data (plans, sessions, memory).
     *
     * @return canonical path if valid, or ToolResult error if traversal detected
     */
    fun resolveAndValidateForRead(rawPath: String, projectBasePath: String?): Pair<String?, ToolResult?> {
        return resolveAndValidate(rawPath, projectBasePath, allowAgentDataDir = true)
    }

    /**
     * Resolve a raw path and validate it stays within the project OR the supplied
     * memory directory (typically `{agentDir}/memory/`).
     *
     * Write tools (edit_file, create_file) call this so the agent can manage its own
     * file-based memory using general file tools — without granting write access to
     * the whole `~/.workflow-orchestrator/` tree.
     *
     * @return canonical path if valid, or ToolResult error if traversal detected
     */
    fun resolveAndValidateForWrite(
        rawPath: String,
        projectBasePath: String?,
        memoryDir: String?
    ): Pair<String?, ToolResult?> {
        if (projectBasePath == null) {
            return null to ToolResult.error("Error: project base path not available", "Error: no project")
        }

        val resolved = if (File(rawPath).isAbsolute) rawPath else File(projectBasePath, rawPath).path

        val canonical = try {
            File(resolved).canonicalPath
        } catch (e: Exception) {
            return null to ToolResult.error("Error: invalid path '$rawPath': ${e.message}", "Error: invalid path")
        }

        val projectCanonical = File(projectBasePath).canonicalPath
        if (canonical.startsWith(projectCanonical + File.separator) || canonical == projectCanonical) {
            return canonical to null
        }

        if (memoryDir != null) {
            val memCanonical = try { File(memoryDir).canonicalPath } catch (_: Exception) { null }
            if (memCanonical != null &&
                (canonical.startsWith(memCanonical + File.separator) || canonical == memCanonical)
            ) {
                return canonical to null
            }
        }

        return null to ToolResult.error(
            "Error: path '$rawPath' resolves outside the project directory and the memory directory. " +
            "Write operations are restricted to the project directory and `{agentDir}/memory/`.",
            "Error: path outside project"
        )
    }

    /**
     * Validates that [rawPath] resolves to a real file under `{sessionDir}/downloads/`,
     * with no symlink escape, no `..` traversal, and a canonical path that startsWith
     * the canonical session-downloads root.
     *
     * Stricter than [resolveAndValidateForRead]: that helper allows the entire
     * `~/.workflow-orchestrator/` tree (covering memory, logs, attachments, sessions, etc.).
     * `view_image` only needs access to images saved by `ImageExtractionService` —
     * exclusively under `{sessionDir}/downloads/`.
     *
     * @return Validated [Path] when safe; throws [SecurityException] otherwise.
     */
    fun resolveAndValidateForSessionDownloads(
        rawPath: String,
        sessionDir: Path,
    ): Path {
        require(rawPath.isNotBlank()) { "Path must not be blank" }
        val candidate = Path.of(rawPath).toAbsolutePath().normalize()
        val downloadsRoot = sessionDir.resolve("downloads").toAbsolutePath().normalize()
        if (!candidate.startsWith(downloadsRoot)) {
            throw SecurityException(
                "Path '$rawPath' is outside the current session's downloads/ directory ($downloadsRoot). " +
                    "view_image only accepts images saved by read_document."
            )
        }
        if (!Files.exists(candidate)) {
            throw java.nio.file.NoSuchFileException(candidate.toString())
        }
        if (!Files.isRegularFile(candidate)) {
            throw SecurityException("Path '$rawPath' is not a regular file")
        }
        // Defend against symlink-escape: re-canonicalise via toRealPath() and re-check the prefix.
        val real = try { candidate.toRealPath() } catch (e: java.nio.file.NoSuchFileException) { throw e }
        if (!real.startsWith(downloadsRoot.toRealPath())) {
            throw SecurityException("Path '$rawPath' resolves outside downloads/ via symlinks")
        }
        return real
    }

    private fun resolveAndValidate(
        rawPath: String,
        projectBasePath: String?,
        allowAgentDataDir: Boolean
    ): Pair<String?, ToolResult?> {
        if (projectBasePath == null) {
            return null to ToolResult.error("Error: project base path not available", "Error: no project")
        }

        val resolved = if (File(rawPath).isAbsolute) rawPath else File(projectBasePath, rawPath).path

        val canonical = try {
            File(resolved).canonicalPath
        } catch (e: Exception) {
            return null to ToolResult.error("Error: invalid path '$rawPath': ${e.message}", "Error: invalid path")
        }

        val projectCanonical = File(projectBasePath).canonicalPath

        // Always allow paths within the project directory
        if (canonical.startsWith(projectCanonical + File.separator) || canonical == projectCanonical) {
            return canonical to null
        }

        // For read operations, also allow paths within ~/.workflow-orchestrator/
        if (allowAgentDataDir) {
            val agentDataCanonical = File(System.getProperty("user.home"), AGENT_DATA_DIR).canonicalPath
            if (canonical.startsWith(agentDataCanonical + File.separator) || canonical == agentDataCanonical) {
                return canonical to null
            }
        }

        return null to ToolResult.error(
            "Error: path '$rawPath' resolves outside the project directory. " +
            "File operations are restricted to the project directory for security.",
            "Error: path outside project"
        )
    }
}
