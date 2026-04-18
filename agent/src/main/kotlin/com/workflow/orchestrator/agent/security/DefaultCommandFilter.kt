package com.workflow.orchestrator.agent.security

import com.workflow.orchestrator.agent.tools.process.ShellType

/**
 * Default command filter that hard-blocks universally dangerous shell patterns.
 *
 * Unlike [CommandSafetyAnalyzer] (which classifies risk for user-approvable gating),
 * this filter produces a binary Allow/Reject result. Rejected commands are NEVER executed,
 * regardless of user approval.
 *
 * Uses [CommandSafetyAnalyzer.tokenize] to avoid false positives on patterns that appear
 * inside quoted strings (e.g., `grep "rm -rf" file.txt` is allowed).
 */
class DefaultCommandFilter : CommandFilter {

    override fun check(command: String, shellType: ShellType): FilterResult {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return FilterResult.Allow

        // Tokenize to distinguish unquoted (structural) content from quoted (literal) content.
        // Operators (|, >, ;, &&, ||) are included because they are structural parts of the
        // command that dangerous patterns depend on (e.g., curl ... | sh, > /dev/sd).
        val tokens = CommandSafetyAnalyzer.tokenize(trimmed)
        val unquotedText = tokens
            .filter { !it.quoted }
            .joinToString(" ") { it.value }

        for ((pattern, reason) in HARD_BLOCKED) {
            if (pattern.containsMatchIn(unquotedText)) {
                return FilterResult.Reject(reason)
            }
        }

        return FilterResult.Allow
    }

    companion object {
        /**
         * Hard-block patterns checked against the unquoted portion of the command.
         * Each entry is a regex pattern paired with a human-readable rejection reason.
         */
        private val HARD_BLOCKED: List<Pair<Regex, String>> = listOf(
            Regex("""rm\s+-rf\s+/""") to "Blocked: rm -rf / (recursive root deletion)",
            Regex("""rm\s+-rf\s+~""") to "Blocked: rm -rf ~ (recursive home deletion)",
            Regex("""^\s*sudo\s""") to "Blocked: sudo (privilege escalation)",
            Regex(""":\(\)\s*\{""") to "Blocked: fork bomb detected",
            Regex("""mkfs\.""") to "Blocked: mkfs (filesystem format)",
            Regex("""dd\s+if=""") to "Blocked: dd (raw disk write)",
            Regex(""":\s*>\s*/""") to "Blocked: truncate root filesystem file",
            Regex(""">\s*/dev/sd""") to "Blocked: redirect to /dev/sd (raw disk write)",
            Regex("""chmod\s+-R\s+777\s+/""") to "Blocked: chmod -R 777 / (open all permissions on root)",
            Regex("""chown\s+-R\s+.*\s+/""") to "Blocked: chown -R on / (recursive ownership change on root)",
            Regex("""curl\s+.*\|\s*sh""") to "Blocked: curl ... | sh (remote code execution)",
            Regex("""curl\s+.*\|\s*bash""") to "Blocked: curl ... | bash (remote code execution)",
            Regex("""wget\s+.*\|\s*sh""") to "Blocked: wget ... | sh (remote code execution)",
            Regex("""wget\s+.*\|\s*bash""") to "Blocked: wget ... | bash (remote code execution)",
        )
    }
}
