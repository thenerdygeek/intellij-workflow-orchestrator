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
     * Expands a leading `~` or `~/` to the user's home directory.
     * The shell does this for run_command paths, but raw file tool paths
     * (read_file, glob_files, search_code, edit_file, create_file) never
     * go through a shell, so we need to expand them here. Without this,
     * `~/.workflow-orchestrator/memory/MEMORY.md` is treated as a literal
     * relative path and resolves under the project root, where it doesn't exist.
     *
     * Only the leading `~` is expanded. `~user/x` (other-user expansion) is
     * intentionally NOT supported — that requires resolving a username to a
     * home directory and the failure mode would be confusing on Windows.
     */
    internal fun expandUserHome(rawPath: String): String {
        if (rawPath.isEmpty() || rawPath[0] != '~') return rawPath
        val home = System.getProperty("user.home") ?: return rawPath
        return when {
            rawPath == "~" -> home
            rawPath.startsWith("~/") || rawPath.startsWith("~" + File.separator) -> home + File.separator + rawPath.substring(2)
            else -> rawPath
        }
    }

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
     * Resolve a raw path and validate it stays within the project OR any of the supplied
     * extra allow-listed roots (typically `{agentDir}/memory/` and `{agentDir}/research/`).
     *
     * Write tools (edit_file, create_file, delete_file, revert_file) call this so the agent
     * can manage its own file-based memory + per-session research dumps using general file
     * tools — without granting write access to the whole `~/.workflow-orchestrator/` tree.
     *
     * The first allow-list match wins; traversal-via-`..` is defeated because we canonicalise
     * the target path before comparing prefixes.
     *
     * @return canonical path if valid, or ToolResult error if traversal detected
     */
    fun resolveAndValidateForWrite(
        rawPath: String,
        projectBasePath: String?,
        allowedExtraRoots: List<String> = emptyList(),
    ): Pair<String?, ToolResult?> {
        if (projectBasePath == null) {
            return null to ToolResult.error("Error: project base path not available", "Error: no project")
        }

        val expanded = expandUserHome(rawPath)
        val resolved = if (File(expanded).isAbsolute) expanded else File(projectBasePath, expanded).path

        val canonical = try {
            File(resolved).canonicalPath
        } catch (e: Exception) {
            return null to ToolResult.error("Error: invalid path '$rawPath': ${e.message}", "Error: invalid path")
        }

        val projectCanonical = File(projectBasePath).canonicalPath
        if (canonical.startsWith(projectCanonical + File.separator) || canonical == projectCanonical) {
            return canonical to null
        }

        for (extraRoot in allowedExtraRoots) {
            val extraCanonical = try { File(extraRoot).canonicalPath } catch (_: Exception) { null }
            if (extraCanonical != null &&
                (canonical.startsWith(extraCanonical + File.separator) || canonical == extraCanonical)
            ) {
                return canonical to null
            }
        }

        return null to ToolResult.error(
            "Error: path '$rawPath' resolves outside the project directory and the agent's allow-listed roots. " +
            "Write operations are restricted to the project directory and `{agentDir}/memory/` + `{agentDir}/research/`.",
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

        val expanded = expandUserHome(rawPath)
        val resolved = if (File(expanded).isAbsolute) expanded else File(projectBasePath, expanded).path

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
