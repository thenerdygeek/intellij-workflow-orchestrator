package com.workflow.orchestrator.agent.tools.builtin

import com.workflow.orchestrator.agent.tools.process.ManagedProcess

/**
 * Shared helpers for tools that interact with a [ManagedProcess] after sending input
 * (e.g. [AskUserInputTool], [SendStdinTool]). Extracted to avoid byte-identical
 * duplication between those tools.
 */
internal object ProcessToolHelpers {

    /**
     * Returns the concatenated output produced by [managed] since [fromIndex] lines.
     * Matches the behaviour previously duplicated in AskUserInputTool and SendStdinTool.
     */
    fun collectNewOutput(managed: ManagedProcess, fromIndex: Int): String {
        val lines = managed.outputLines.toList()
        return if (fromIndex < lines.size) {
            lines.drop(fromIndex).joinToString("")
        } else {
            ""
        }
    }

    /**
     * Builds the `[IDLE]` message returned when a process is waiting for more input.
     *
     * @param label phrase describing what the process is idle "after" — e.g.
     *              `"user input"` or `"stdin"`. Used in both the headline and the
     *              output-preamble line.
     */
    fun buildIdleContent(
        processId: String,
        newOutput: String,
        idleMs: Long,
        label: String
    ): String {
        val idleSec = idleMs / 1000
        return buildString {
            appendLine("[IDLE] Process idle for ${idleSec}s after $label — no new output.")
            appendLine("Process still running (ID: $processId).")
            if (newOutput.isNotBlank()) {
                appendLine()
                appendLine("Output since $label:")
                newOutput.lines().forEach { appendLine("  $it") }
            }
            appendLine()
            appendLine("Options:")
            appendLine("- send_stdin(process_id=\"$processId\", input=\"<your input>\\n\") to provide more input")
            appendLine("- ask_user_input(process_id=\"$processId\", description=\"...\", prompt=\"...\") for user input")
            appendLine("- kill_process(process_id=\"$processId\") to abort")
        }
    }
}
