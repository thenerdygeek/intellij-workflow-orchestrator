package com.workflow.orchestrator.agent.security

import com.workflow.orchestrator.agent.tools.process.ShellType

/**
 * Pre-spawn command filter. Returns Allow or Reject.
 * This is the hard-block layer — rejected commands NEVER execute, regardless of user approval.
 * For risk classification and user-approvable commands, see [CommandSafetyAnalyzer].
 */
interface CommandFilter {
    fun check(command: String, shellType: ShellType): FilterResult
}

sealed class FilterResult {
    object Allow : FilterResult()
    data class Reject(val reason: String) : FilterResult()
}
