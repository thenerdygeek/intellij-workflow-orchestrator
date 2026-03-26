package com.workflow.orchestrator.agent.runtime

import com.workflow.orchestrator.agent.api.dto.ChatMessage

/**
 * Tracks file edits and injects verification nudges when the agent modifies code
 * without checking for errors.
 *
 * After [editThreshold] edits without running diagnostics/tests, injects a nudge
 * asking the agent to verify. If the agent ignores the nudge and makes more edits,
 * escalates to a stronger message.
 *
 * Set [editThreshold] to 0 to disable backpressure.
 */
class BackpressureGate(
    private val editThreshold: Int = 3
) {
    companion object {
        /** Tools that count as verification (reset the edit counter). */
        val VERIFICATION_TOOLS = setOf("diagnostics", "run_tests", "run_inspections", "compile_module")
    }

    private val pendingEdits = mutableListOf<String>()
    private var nudgeEmitted = false
    private var verificationPending = false

    fun recordEdit(filePath: String) {
        pendingEdits.add(filePath)
    }

    fun checkAndGetNudge(): ChatMessage? {
        if (editThreshold <= 0) return null
        if (pendingEdits.size < editThreshold) return null

        val fileList = pendingEdits.distinct().joinToString(", ")

        if (nudgeEmitted && verificationPending) {
            return ChatMessage(
                role = "system",
                content = "REQUIRED: You have edited files without running verification. Run diagnostics or run_tests on modified files before making more changes: $fileList"
            )
        }

        nudgeEmitted = true
        verificationPending = true
        return ChatMessage(
            role = "system",
            content = "Backpressure gate: you've edited ${pendingEdits.size} files ($fileList). Run diagnostics on these files before continuing to catch errors early."
        )
    }

    fun acknowledgeVerification() {
        pendingEdits.clear()
        nudgeEmitted = false
        verificationPending = false
    }

    fun isVerificationPending(): Boolean = verificationPending

    fun createBackpressureError(toolName: String, errorOutput: String): ChatMessage {
        val trimmedError = errorOutput.take(2000)
        return ChatMessage(
            role = "system",
            content = "<backpressure_error>\n$toolName failed. Errors:\n$trimmedError\nFix these errors before proceeding to the next plan step.\n</backpressure_error>"
        )
    }

    fun reset() {
        pendingEdits.clear()
        nudgeEmitted = false
        verificationPending = false
    }
}
