package com.workflow.orchestrator.core.maven

import com.intellij.openapi.diagnostic.Logger

/**
 * Validates Maven goal/phase tokens from user-editable settings fields before
 * they are placed on the `GeneralCommandLine` argv list.
 *
 * Even though [com.intellij.execution.configurations.GeneralCommandLine] does
 * NOT invoke a shell (each element is a distinct argv token, so classic
 * shell-injection via `;`, `|`, `&` is not possible at the OS level), allowing
 * arbitrary tokens is still dangerous:
 *
 * - Flags like `-Dmaven.repo.local=/tmp/evil` redirect artifact resolution.
 * - Plugin executions like `exec:exec` run arbitrary binaries.
 * - Tokens containing newlines, backslashes, or percent-encoded sequences can
 *   confuse downstream log parsers and CI systems.
 *
 * The allowed-token pattern covers:
 * - Standard lifecycle phases: `clean`, `validate`, `compile`, `test`,
 *   `package`, `verify`, `install`, `deploy`, `site`, `pre-*`, `post-*`, `process-*`
 * - Plugin-goal notation: `groupId:artifactId:goal`, `prefix:goal`
 *   (alphanumeric + dots + hyphens + colons, no slashes)
 * - System properties: `-Dkey=value` (value allows most printable chars except shell metas)
 * - Profile activation: `-Pprofile-name`, `-P!profile`
 * - Project-subset flags: `-pl module-path`, `-am`, `-amd`, `--also-make`, etc.
 * - Common single-word flags: `--batch-mode`, `--quiet`, `--offline`, `-o`,
 *   `-X`, `-e`, `-N`, `--no-transfer-progress`, `-ntp`
 *
 * Tokens containing shell metacharacters (`;`, `|`, `&`, `` ` ``, `$(`, `>`,
 * `<`, newline) are **always** rejected regardless of the token shape.
 *
 * (Audit finding core:F-11)
 */
object MavenGoalValidator {

    private val LOG = Logger.getInstance(MavenGoalValidator::class.java)

    /**
     * Characters that must never appear in any Maven goal token, even inside
     * an otherwise-valid `-D` property value. These are shell metacharacters
     * and characters that could corrupt argv interpretation or downstream logs.
     */
    private val SHELL_META_CHARS = Regex("""[;|&`$\n\r<>]|\$\(""")

    /**
     * Allowed shapes for a Maven goal/phase/flag token.
     *
     * Breakdown:
     *  - `-D<key>[=<value>]`  — system property flag
     *  - `-P[!]<profiles>`    — profile activation (comma-separated names allowed)
     *  - `-pl <paths>`        — reactor subset (comma-separated module paths)
     *  - `--<word>` / `-<letters>` — any single dash/double-dash word flag
     *  - `<phase|goal>`       — lifecycle phase or `grp:art:goal` / `prefix:goal`
     */
    private val ALLOWED_TOKEN = Regex(
        """^(?:""" +
            """-D[a-zA-Z0-9._\-]+=?[a-zA-Z0-9._\-/:\\@,+%~=]*""" + "|" +  // -Dkey=value
            """-P[!]?[a-zA-Z0-9._\-,]+""" + "|" +                            // -P profile(s)
            """-pl\s+[a-zA-Z0-9._\-/,:.]+""" + "|" +                         // -pl module-list
            """--[a-zA-Z][a-zA-Z0-9\-]*""" + "|" +                           // --long-flag
            """-[a-zA-Z]+""" + "|" +                                           // -short / -am / -amd
            """[a-zA-Z][a-zA-Z0-9._\-]*(?::[a-zA-Z0-9._\-]+)*"""            // phase or group:artifact:goal
            + """)$"""
    )

    /**
     * Splits [goals] on whitespace and validates each resulting token.
     *
     * Returns a [ValidationResult]:
     * - [ValidationResult.Valid] containing the clean token list if every token passes.
     * - [ValidationResult.Invalid] listing the offending tokens if any fail.
     *
     * Never throws.
     */
    fun validate(goals: String): ValidationResult {
        val tokens = goals.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return ValidationResult.Valid(emptyList())

        val bad = mutableListOf<String>()
        for (token in tokens) {
            if (!isAllowed(token)) {
                LOG.warn("[MavenGoalValidator] Rejected token: '${token.take(120)}'")
                bad.add(token)
            }
        }
        return if (bad.isEmpty()) ValidationResult.Valid(tokens)
        else ValidationResult.Invalid(bad)
    }

    /** Returns `true` iff [token] is a safe Maven goal/flag token. */
    fun isAllowed(token: String): Boolean {
        if (SHELL_META_CHARS.containsMatchIn(token)) return false
        return ALLOWED_TOKEN.matches(token)
    }

    sealed class ValidationResult {
        /** All tokens are valid; [tokens] is the argv-ready list. */
        data class Valid(val tokens: List<String>) : ValidationResult()

        /** One or more tokens failed validation; [offending] lists them. */
        data class Invalid(val offending: List<String>) : ValidationResult()
    }
}
