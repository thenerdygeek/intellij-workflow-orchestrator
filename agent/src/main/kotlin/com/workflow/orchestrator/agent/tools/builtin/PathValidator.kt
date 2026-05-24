package com.workflow.orchestrator.agent.tools.builtin

import com.workflow.orchestrator.agent.tools.ToolResult
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

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

        val expanded = expandUserHome(rawPath)
        val resolved = if (File(expanded).isAbsolute) expanded else File(projectBasePath, expanded).path

        val canonical = try {
            File(resolved).canonicalPath
        } catch (e: Exception) {
            return null to ToolResult.error("Error: invalid path '$rawPath': ${e.message}", "Error: invalid path")
        }

        val projectCanonical = File(projectBasePath).canonicalPath

        // E4: Check symlinks BEFORE canonicalization — canonicalize derefs symlinks.
        checkSymlinksInPath(rawPath, resolved, projectCanonical)?.let { return null to it }

        if (canonical.startsWith(projectCanonical + File.separator) || canonical == projectCanonical) {
            return canonical to null
        }

        if (memoryDir != null) {
            val memCanonical = try { File(memoryDir).canonicalPath } catch (_: Exception) { null }
            if (memCanonical != null) {
                // E4: Also check symlinks for memory dir writes
                checkSymlinksInPath(rawPath, resolved, memCanonical)?.let { return null to it }
                if (canonical.startsWith(memCanonical + File.separator) || canonical == memCanonical) {
                    return canonical to null
                }
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

    /**
     * E4: Walk each component of [normalizedPath] (outward from [rootCanonical]) and check
     * whether any segment is itself a symbolic link. Returns the first symlink path found, or null.
     *
     * **Important**: this function must be called on the NORMALIZED (not canonicalized) path —
     * `File.canonicalPath` / `Path.toRealPath` dereferences symlinks, so the symlink is gone
     * before we can detect it. Instead, we use `Path.normalize()` (resolves `.` and `..`
     * without following symlinks) and then walk components via `Files.isSymbolicLink`.
     *
     * Only components that start at [rootCanonical] and extend outward are checked — the root
     * itself and its parents are trusted OS/project-level paths.
     *
     * @return The string representation of the first symlink component found, or null if clean.
     */
    internal fun findSymlinkInPath(normalizedPath: String, rootCanonical: String): String? {
        val rootPath = Paths.get(rootCanonical).normalize()
        var current = Paths.get(normalizedPath).normalize()
        // Walk upward from the leaf toward (but not including) the root
        while (current != rootPath && current.startsWith(rootPath)) {
            if (Files.isSymbolicLink(current)) return current.toString()
            current = current.parent ?: break
        }
        return null
    }

    /**
     * Checks whether any path component from [rootCanonical] to the resolved path of [resolved]
     * is a symbolic link, WITHOUT dereferencing symlinks (uses normalize, not toRealPath).
     *
     * On macOS, `/var` is itself a symlink to `/private/var`. We must resolve the root's
     * real path so that `findSymlinkInPath` can walk from the real root correctly. We resolve
     * the root's prefix on the normalized path using `toRealPath()` so the prefix matches
     * without following symlinks in the LLM-provided suffix.
     *
     * @return error ToolResult if a symlink is found in any component; null if clean.
     */
    private fun checkSymlinksInPath(rawPath: String, resolved: String, rootCanonical: String): ToolResult? {
        // Normalize resolves .. and . without following symlinks — preserves symlink visibility
        val normalizedPath = Paths.get(resolved).toAbsolutePath().normalize()

        // rootCanonical was computed via File.canonicalPath (toRealPath). On macOS, the OS temp dir
        // `/var/folders/...` canonicalizes to `/private/var/folders/...`. The normalizedPath of the
        // LLM-provided path starts with `/var/...` (not dereffed), so we must also try the real
        // (toRealPath) prefix of the path's own root segment to align the comparison base.
        val effectiveRoot = try {
            // Only resolve the root portion up to the same depth as rootCanonical segments
            // by taking toRealPath of the root path itself (it is a trusted system path).
            Paths.get(rootCanonical)
        } catch (_: Exception) {
            return null  // can't resolve root; skip symlink check (canonical check covers traversal)
        }

        // Align the path's prefix to match effectiveRoot. If normalizedPath doesn't start with
        // effectiveRoot, the subsequent canonical check will catch the out-of-root case. If it does
        // start with effectiveRoot, we walk from effectiveRoot outward to detect symlink components.
        // Also handle the case where the OS root is a symlink (e.g. macOS /var -> /private/var):
        // try to resolve the portion of normalizedPath up to effectiveRoot's depth.
        val normalizedForWalk: String = run {
            // Attempt: reconstruct the path using effectiveRoot prefix + remainder of normalizedPath
            val rootStr = effectiveRoot.toString()
            val normalStr = normalizedPath.toString()
            // If normalStr already starts with rootStr, use it directly
            if (normalStr.startsWith(rootStr)) return@run normalStr
            // If rootStr ends with something that normalStr partially contains, try toRealPath on
            // the existing directories up to the first agent-controlled component
            // (i.e., the first segment beyond the root that may be a symlink)
            try {
                // Walk from the root outward in normalizedPath to find the first existing component
                // that can be resolved via toRealPath, then reconstruct
                var cur = normalizedPath
                while (cur.parent != null && !Files.exists(cur)) {
                    cur = cur.parent
                }
                // cur is now the deepest existing ancestor; resolve its real path
                val realCur = if (Files.exists(cur)) cur.toRealPath() else null
                if (realCur != null) {
                    val suffix = normalizedPath.toString().removePrefix(cur.toString())
                    realCur.toString() + suffix
                } else normalStr
            } catch (_: Exception) {
                normalStr
            }
        }

        val symlinkSegment = findSymlinkInPath(normalizedForWalk, effectiveRoot.toString())
        return if (symlinkSegment != null) {
            ToolResult.error(
                "Error: path '$rawPath' contains a symbolic link at '$symlinkSegment'. " +
                "Symlinks in path components are not allowed to prevent TOCTOU escapes.",
                "Error: symlink in path"
            )
        } else null
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

        // E4: Check symlinks BEFORE canonicalization — canonicalize derefs symlinks.
        // We normalize (dot-removal only) and check each component.
        val projectCanonical = File(projectBasePath).canonicalPath
        checkSymlinksInPath(rawPath, resolved, projectCanonical)?.let { return null to it }

        val canonical = try {
            File(resolved).canonicalPath
        } catch (e: Exception) {
            return null to ToolResult.error("Error: invalid path '$rawPath': ${e.message}", "Error: invalid path")
        }

        // Always allow paths within the project directory
        if (canonical.startsWith(projectCanonical + File.separator) || canonical == projectCanonical) {
            return canonical to null
        }

        // For read operations, also allow paths within ~/.workflow-orchestrator/
        if (allowAgentDataDir) {
            val agentDataCanonical = File(System.getProperty("user.home"), AGENT_DATA_DIR).canonicalPath
            // E4: Also check symlinks for agent-data-dir reads
            checkSymlinksInPath(rawPath, resolved, agentDataCanonical)?.let { return null to it }
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
