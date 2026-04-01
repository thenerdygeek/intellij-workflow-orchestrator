package com.workflow.orchestrator.agent.runtime

import com.workflow.orchestrator.agent.api.dto.ChatMessage
import com.workflow.orchestrator.agent.util.AgentStringUtils
import java.io.File

/**
 * Tracks edit→verify pairs and enforces a verify-reflect-retry loop.
 *
 * After each [edit_file], demands verification (diagnostics/tests) on the edited file.
 * When verification fails, injects a structured reflection prompt with the error details
 * so the agent can reason about what went wrong before retrying.
 *
 * Blocks task completion until all edited files are verified or max retries exhausted.
 *
 * Works alongside [BackpressureGate] (which handles batch nudges after N edits)
 * and [LoopGuard] (which handles doom loops and pre-edit enforcement).
 */
class SelfCorrectionGate(
    /** Max retry attempts per file before giving up and letting the agent proceed. */
    private val maxRetriesPerFile: Int = 3
) {
    companion object {
        /** Tools that count as verification for a specific file. */
        val VERIFICATION_TOOLS = setOf("diagnostics", "runtime_config", "runtime_exec", "run_inspections")

        /** File extensions that support semantic verification (diagnostics/inspections). */
        private val VERIFIABLE_EXTENSIONS = setOf("kt", "java")
    }

    /** Verification state for a single edited file. */
    data class FileState(
        var editCount: Int = 0,
        var verified: Boolean = false,
        var lastError: String? = null,
        var retryCount: Int = 0,
        var verificationRequested: Boolean = false
    )

    private val fileStates = mutableMapOf<String, FileState>()

    /**
     * Normalize a file path to a canonical form for consistent map lookups.
     * Handles: absolute vs relative, forward vs back slashes, trailing separators.
     */
    private fun normalizePath(path: String): String {
        return try {
            File(path).canonicalPath
        } catch (_: Exception) {
            // Fallback: normalize separators and remove trailing slash
            path.replace('\\', '/').trimEnd('/')
        }
    }

    /**
     * Check if a file extension supports semantic verification.
     * Non-verifiable files (json, txt, md, xml, yaml, etc.) are auto-verified on edit
     * because diagnostics/inspections cannot check them.
     */
    private fun isVerifiableExtension(filePath: String): Boolean {
        val ext = filePath.substringAfterLast('.', "").lowercase()
        return ext in VERIFIABLE_EXTENSIONS
    }

    /**
     * Record a successful edit. Resets verification status for the file.
     * Called after edit_file succeeds.
     *
     * Non-code files (.json, .txt, .md, .xml, etc.) are auto-verified because
     * diagnostics only supports .kt and .java files.
     */
    fun recordEdit(filePath: String) {
        val normalized = normalizePath(filePath)
        val state = fileStates.getOrPut(normalized) { FileState() }
        state.editCount++

        if (isVerifiableExtension(normalized)) {
            state.verified = false
            state.verificationRequested = false
        } else {
            // Non-code files: auto-verify (diagnostics can't check them)
            state.verified = true
        }
        // Don't reset retryCount — it accumulates across edits to the same file
    }

    /**
     * Get a verification demand message to inject after an edit.
     * Returns a message for the first unverified, un-requested file, or null.
     */
    fun getVerificationDemand(): ChatMessage? {
        val unverified = fileStates.entries
            .filter { !it.value.verified && !it.value.verificationRequested && !isRetryExhausted(it.key) }
            .map { it.key }

        if (unverified.isEmpty()) return null

        // Mark as requested so we don't repeat
        unverified.forEach { fileStates[it]?.verificationRequested = true }

        val fileList = unverified.joinToString(", ")
        return ChatMessage(
            role = "system",
            content = "Self-correction: You edited $fileList. Run diagnostics on ${if (unverified.size == 1) "this file" else "these files"} NOW to verify your changes are correct before continuing."
        )
    }

    /**
     * Record the result of a verification tool on a file.
     *
     * @param filePath The file that was verified (null if verification was project-wide like compile_module)
     * @param passed Whether the verification passed (no errors)
     * @param errorDetails Details of what failed (for reflection prompt), null if passed
     */
    fun recordVerification(filePath: String?, passed: Boolean, errorDetails: String? = null) {
        if (filePath != null) {
            val normalized = normalizePath(filePath)
            val state = fileStates[normalized] ?: return
            if (passed) {
                state.verified = true
                state.lastError = null
            } else {
                state.verified = false
                state.retryCount++
                state.lastError = errorDetails
                state.verificationRequested = false // Allow new verification demand after fix
            }
        } else {
            // Project-wide verification (e.g., compile_module) — mark all as verified/failed
            for ((_, state) in fileStates) {
                if (!state.verified) {
                    if (passed) {
                        state.verified = true
                        state.lastError = null
                    } else {
                        state.retryCount++
                        state.lastError = errorDetails
                        state.verificationRequested = false
                    }
                }
            }
        }
    }

    /**
     * Generate a structured reflection prompt after a verification failure.
     * Helps the agent reason about what went wrong before retrying.
     *
     * @param filePath The file that failed verification
     * @param toolName The verification tool that was run
     * @param errorDetails The error output from the verification tool
     * @return Reflection message, or null if retries exhausted
     */
    fun buildReflectionPrompt(filePath: String, toolName: String, errorDetails: String): ChatMessage? {
        val normalized = normalizePath(filePath)
        val state = fileStates[normalized] ?: return null
        if (isRetryExhausted(normalized)) return null

        val trimmedError = errorDetails.take(1500)
        val attempt = state.retryCount
        val remaining = maxRetriesPerFile - attempt

        return ChatMessage(
            role = "system",
            content = buildString {
                appendLine("<self_correction>")
                appendLine("VERIFICATION FAILED for $filePath (attempt $attempt/$maxRetriesPerFile, $remaining remaining)")
                appendLine()
                appendLine("Tool: $toolName")
                appendLine("Errors:")
                appendLine(trimmedError)
                appendLine()
                appendLine("Before retrying, REFLECT on what went wrong:")
                appendLine("1. What was the intent of your edit?")
                appendLine("2. Why did it cause this error?")
                appendLine("3. What is the correct fix?")
                appendLine()
                if (remaining <= 1) {
                    appendLine("WARNING: This is your last retry. Read the file again to see current state before editing.")
                } else {
                    appendLine("Fix the error in $filePath, then run diagnostics again to verify.")
                }
                appendLine("</self_correction>")
            }
        )
    }

    /**
     * Quality gate check before task completion.
     * Returns a blocking message if there are unverified edits, or null if ready to complete.
     */
    fun checkCompletionReadiness(): ChatMessage? {
        val unverified = fileStates.entries
            .filter { !it.value.verified && !isRetryExhausted(it.key) }
            .map { it.key }

        if (unverified.isEmpty()) return null

        return ChatMessage(
            role = "system",
            content = "COMPLETION BLOCKED: You have edited files that haven't been verified: ${unverified.joinToString(", ")}. " +
                "Run diagnostics on these files before completing the task. " +
                "If you've already tried and the errors persist, explain the remaining issues."
        )
    }

    /** Check if max retries exhausted for a file. */
    fun isRetryExhausted(filePath: String): Boolean {
        val state = fileStates[normalizePath(filePath)] ?: return false
        return state.retryCount >= maxRetriesPerFile
    }

    /** Get list of files that were edited but not yet verified. */
    fun getUnverifiedFiles(): Set<String> =
        fileStates.filter { !it.value.verified }.keys

    /** Get list of files that exceeded max retries. */
    fun getExhaustedFiles(): Set<String> =
        fileStates.filter { isRetryExhausted(it.key) && !it.value.verified }.keys

    /** Get all tracked file states (for metrics/debugging). Returns defensive copies. */
    fun getFileStates(): Map<String, FileState> = fileStates.mapValues { it.value.copy() }

    /** Reset all state. */
    fun reset() {
        fileStates.clear()
    }

    /**
     * Extract the file path from verification tool arguments, if present.
     * Returns null for project-wide tools (compile_module) or unparseable args.
     */
    fun extractFilePathFromArgs(toolName: String, args: String): String? {
        if (toolName == "runtime_config" || toolName == "runtime_exec") return null // Project-wide (compile_module, run_tests actions)
        // diagnostics, run_inspections use "path" or "file_path"
        val pathMatch = AgentStringUtils.JSON_FILE_PATH_REGEX.find(args)
        return pathMatch?.groupValues?.get(1)
    }

    /**
     * Check if a file is tracked (was edited in this session).
     */
    fun isTracked(filePath: String): Boolean = normalizePath(filePath) in fileStates
}
