package com.workflow.orchestrator.agent.tools.builtin

import com.workflow.orchestrator.agent.tools.ToolResult
import java.io.File

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

    private fun resolveAndValidate(
        rawPath: String,
        projectBasePath: String?,
        allowAgentDataDir: Boolean
    ): Pair<String?, ToolResult?> {
        if (projectBasePath == null) {
            return null to ToolResult(
                "Error: project base path not available",
                "Error: no project", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        val resolved = if (File(rawPath).isAbsolute) rawPath else File(projectBasePath, rawPath).path

        val canonical = try {
            File(resolved).canonicalPath
        } catch (e: Exception) {
            return null to ToolResult(
                "Error: invalid path '$rawPath': ${e.message}",
                "Error: invalid path", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
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

        return null to ToolResult(
            "Error: path '$rawPath' resolves outside the project directory. " +
            "File operations are restricted to the project directory for security.",
            "Error: path outside project", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
        )
    }
}
