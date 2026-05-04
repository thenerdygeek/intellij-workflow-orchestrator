package com.workflow.orchestrator.agent.tools.process

/**
 * Defense-in-depth: detects when an LLM hand-rolls a shell prefix
 * (`cmd /c …`, `bash -c …`, `powershell -Command …`) inside the
 * `command` parameter and strips it, since `RunCommandTool` already
 * wraps the command in a [ShellResolver]-selected shell.
 *
 * Without this, the user's shell wraps an inner shell — on Windows,
 * cmd.exe's quote-handling rules combined with Java's argv-to-cmdline
 * encoding produce a malformed inner command line that frequently drops
 * cmd.exe into its interactive banner + prompt, hanging the tool until
 * the timeout expires.
 *
 * The stripped prefix is returned alongside the cleaned command so
 * `RunCommandTool` can attach a one-line `[NOTE]` to the result, which
 * teaches the LLM not to do it again on subsequent calls within the
 * same session.
 */
object CommandPrefixStripper {

    data class StripResult(val command: String, val strippedPrefix: String?)

    private val PATTERNS = listOf(
        Regex("""^\s*cmd(?:\.exe)?\s+/c\s+""", RegexOption.IGNORE_CASE),
        Regex("""^\s*(?:bash|sh|zsh)\s+-c\s+"""),
        Regex(
            """^\s*(?:powershell|pwsh)(?:\.exe)?\s+(?:-NoProfile\s+|-NonInteractive\s+|-NoLogo\s+)*-Command\s+""",
            RegexOption.IGNORE_CASE,
        ),
    )

    fun strip(command: String): StripResult {
        for (pattern in PATTERNS) {
            val match = pattern.find(command) ?: continue
            val rest = command.substring(match.range.last + 1)
            val unwrapped = unwrapQuotes(rest)
            // Guard: don't strip if the result is empty (would surface a
            // confusing "Error: 'command' parameter required"). Treat it
            // as a non-match instead.
            if (unwrapped.isBlank()) return StripResult(command, null)
            return StripResult(unwrapped, match.value.trim())
        }
        return StripResult(command, null)
    }

    private fun unwrapQuotes(s: String): String {
        val trimmed = s.trim()
        if (trimmed.length < 2) return trimmed
        val first = trimmed.first()
        val last = trimmed.last()
        return if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            trimmed.substring(1, trimmed.length - 1)
        } else {
            trimmed
        }
    }
}
