package com.workflow.orchestrator.agent.tools.builtin

import com.workflow.orchestrator.agent.tools.ToolResult
import java.io.File

/**
 * Validates that file paths from LLM tool calls stay within the project directory.
 * Prevents path traversal attacks (../../etc/passwd).
 *
 * All file tools (read, edit, search, diagnostics) MUST use this before
 * accessing the filesystem.
 */
object PathValidator {

    /**
     * Resolve a raw path and validate it stays within the project.
     * @return canonical path if valid, or ToolResult error if traversal detected
     */
    fun resolveAndValidate(rawPath: String, projectBasePath: String?): Pair<String?, ToolResult?> {
        if (projectBasePath == null) {
            return null to ToolResult(
                "Error: project base path not available",
                "Error: no project", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        val resolved = if (rawPath.startsWith("/")) rawPath else "$projectBasePath/$rawPath"

        val canonical = try {
            File(resolved).canonicalPath
        } catch (e: Exception) {
            return null to ToolResult(
                "Error: invalid path '$rawPath': ${e.message}",
                "Error: invalid path", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        val projectCanonical = File(projectBasePath).canonicalPath

        return if (canonical.startsWith(projectCanonical + File.separator) || canonical == projectCanonical) {
            canonical to null
        } else {
            null to ToolResult(
                "Error: path '$rawPath' resolves outside the project directory. " +
                "File operations are restricted to the project directory for security.",
                "Error: path outside project", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }
    }
}
