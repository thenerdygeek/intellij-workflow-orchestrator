package com.workflow.orchestrator.agent.tools.process

sealed class IdleClassification {
    data class LikelyPasswordPrompt(val promptText: String) : IdleClassification()
    data class LikelyStdinPrompt(val promptText: String) : IdleClassification()
    data object GenericIdle : IdleClassification()
}

/**
 * Tier 1 (regex) idle classification. Runs on every idle-threshold crossing in
 * on_idle=notify foreground runs. Tier 2 (Haiku LLM) is deferred to v1.1.
 */
object PromptHeuristics {

    private val passwordPatterns = listOf(
        Regex("""(?i)\bpassword\s*:\s*$""", RegexOption.MULTILINE),
        Regex("""(?i)\bpassphrase\b.*:\s*$""", RegexOption.MULTILINE),
        Regex("""(?i)\[sudo\]\s+password\s+for\s+\S+:\s*$""", RegexOption.MULTILINE),
        Regex("""(?i)\bpin\s*:\s*$""", RegexOption.MULTILINE),
    )

    // Trailing prompt shapes without a final newline (interactive prompt waiting on input).
    private val stdinPromptPatterns = listOf(
        Regex("""^\?\s+[^\n]*›[^\n]*$""", RegexOption.MULTILINE), // vite/inquirer: "? Project name: › default"
        Regex("""\[[yY][/\\][nN]\]\s*:?\s*$"""),            // [Y/n]
        Regex("""\([yY][/\\][nN]\)\s*:?\s*$"""),            // (Y/n)
        Regex("""[^\n]{0,100}[?:>»›]\s*$"""),               // generic "...? " / "...: " / "...> " / bare "> "
    )

    fun classify(tail: String): IdleClassification {
        if (tail.isBlank()) return IdleClassification.GenericIdle
        val trimmed = tail.trimEnd('\n', '\r', ' ', '\t')

        passwordPatterns.forEach { p ->
            if (p.containsMatchIn(tail)) {
                return IdleClassification.LikelyPasswordPrompt(lastLine(trimmed))
            }
        }

        // stdin prompts require the tail to end without a newline (prompt is waiting).
        if (!tail.endsWith('\n')) {
            stdinPromptPatterns.forEach { p ->
                if (p.containsMatchIn(tail)) {
                    return IdleClassification.LikelyStdinPrompt(lastLine(trimmed))
                }
            }
        }

        return IdleClassification.GenericIdle
    }

    private fun lastLine(s: String): String = s.substringAfterLast('\n').trim()
}
