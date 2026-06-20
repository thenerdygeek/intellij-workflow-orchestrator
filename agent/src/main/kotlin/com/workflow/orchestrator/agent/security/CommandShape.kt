package com.workflow.orchestrator.agent.security

/**
 * Conservative, pure command analysis for the run_command AUTO-APPROVE gate.
 *
 * Unlike [CommandSafetyAnalyzer] (tuned to avoid false DANGEROUS positives for a
 * human-reviewed card), this object is tuned for the inverted cost function of
 * unattended execution: a false "prompt" costs nothing, a false "skip" runs code.
 * Every ambiguous case resolves to NOT-approvable.
 *
 * Reuses only [CommandSafetyAnalyzer.tokenize] (quote-aware token stream); it does
 * NOT reuse splitSegments (which misses newlines and lone `&`).
 */
object CommandShape {

    private val SPLIT_OPS = setOf("&&", "||", ";", "|")
    private val REDIRECT_OPS = setOf(">", ">>", "<")
    private val ASSIGNMENT = Regex("^[A-Za-z_][A-Za-z0-9_]*=")

    /** First-token programs whose REAL command is an argument — never auto-approvable. */
    private val WRAPPER_DENYLIST = setOf(
        "env", "sudo", "doas", "su", "timeout", "nice", "ionice", "nohup",
        "setsid", "stdbuf", "xargs", "command", "exec", "time", "watch", "flock",
    )
    private val SHELL_INTERPRETERS = setOf("sh", "bash", "zsh", "fish", "dash", "ksh")
    private val CODE_INTERPRETERS = setOf("python", "python3", "node", "ruby", "perl", "php")
    private val INLINE_EVAL_FLAGS = setOf("-c", "-e", "--eval", "-E", "-r", "-m", "-p", "--print", "-")

    /** Tools whose first sub-verb is meaningful for a prefix (e.g. `git add`). */
    private val MULTI_VERB_TOOLS = setOf(
        "git", "npm", "yarn", "pnpm", "npx", "docker", "docker-compose", "kubectl",
        "helm", "mvn", "./mvnw", "mvnw", "gradle", "./gradlew", "gradlew",
        "cargo", "go", "pip", "pip3", "poetry", "uv", "gh", "terraform", "make",
    )

    private fun tokenLine(line: String) = CommandSafetyAnalyzer.tokenize(line)

    /** Split into sub-command token lists: newlines first, then top-level operators. */
    private fun subCommandTokenLists(command: String): List<List<CommandSafetyAnalyzer.Token>> {
        val result = mutableListOf<List<CommandSafetyAnalyzer.Token>>()
        for (line in command.split(Regex("\\r\\n|\\r|\\n"))) {
            if (line.isBlank()) continue
            var current = mutableListOf<CommandSafetyAnalyzer.Token>()
            for (t in tokenLine(line)) {
                if (t.isOperator && t.value in SPLIT_OPS) {
                    if (current.isNotEmpty()) { result.add(current); current = mutableListOf() }
                } else {
                    current.add(t)
                }
            }
            if (current.isNotEmpty()) result.add(current)
        }
        return result
    }

    private fun values(tokens: List<CommandSafetyAnalyzer.Token>): List<String> =
        tokens.filter { !it.isOperator }.map { it.value }

    private fun subIsSimple(tokens: List<CommandSafetyAnalyzer.Token>): Boolean {
        if (tokens.isEmpty()) return false
        if (tokens.any { it.isOperator && it.value in REDIRECT_OPS }) return false
        val vals = values(tokens)
        if (vals.isEmpty()) return false
        if (vals.any { it.contains('$') || it.contains('`') }) return false   // expansion / substitution
        if (vals.any { it.contains('&') }) return false                       // backgrounding / stray &
        val first = vals.first()
        if (ASSIGNMENT.containsMatchIn(first)) return false                   // FOO=bar cmd
        val firstLc = first.lowercase()
        if (firstLc in WRAPPER_DENYLIST) return false
        if (firstLc in SHELL_INTERPRETERS) return false
        if (firstLc in CODE_INTERPRETERS && vals.drop(1).any { it in INLINE_EVAL_FLAGS }) return false
        val baseName = firstLc.substringAfterLast('/')
        if (baseName in SHELL_INTERPRETERS) return false
        if (baseName in CODE_INTERPRETERS && vals.drop(1).any { it in INLINE_EVAL_FLAGS }) return false
        return true
    }

    /** Public, mostly for tests/spec: render sub-commands as strings (quotes are not preserved). */
    fun splitSubCommands(command: String): List<String> =
        subCommandTokenLists(command).map { sub -> sub.joinToString(" ") { it.value } }

    /** True iff EVERY sub-command is structurally simple (auto-approve precondition). */
    fun isAutoApprovable(command: String): Boolean {
        val subs = subCommandTokenLists(command)
        return subs.isNotEmpty() && subs.all { subIsSimple(it) }
    }

    /**
     * The prefix to offer in the "Approve all <prefix> this session" button.
     * Single, simple sub-command only; null otherwise. May return original case
     * (the button LABEL); normalization happens in SessionCommandAllowlist.approve.
     */
    fun derivePrefix(command: String): String? {
        val subs = subCommandTokenLists(command)
        if (subs.size != 1) return null
        val sub = subs.first()
        if (!subIsSimple(sub)) return null
        val vals = values(sub)
        if (vals.isEmpty()) return null
        val firstLc = vals[0].lowercase()
        return if (firstLc in MULTI_VERB_TOOLS && vals.size >= 2 && !vals[1].startsWith("-")) {
            "${vals[0]} ${vals[1]}"
        } else {
            vals[0]
        }
    }

    /**
     * Returns the distinct prefixes that cover the command iff EVERY sub-command is
     * token-prefix-matched by some entry in [prefixes] (assumed already normalized:
     * trimmed, single-spaced, lowercased) AND the command is structurally simple.
     * null = not covered (caller prompts).
     */
    fun coveringPrefixes(command: String, prefixes: Set<String>): List<String>? {
        if (prefixes.isEmpty()) return null
        if (!isAutoApprovable(command)) return null
        val subs = subCommandTokenLists(command)
        if (subs.isEmpty()) return null
        val matched = subs.map { sub ->
            val vals = values(sub).map { it.lowercase() }
            prefixes.firstOrNull { p ->
                val pt = p.split(' ').filter { it.isNotEmpty() }
                pt.isNotEmpty() && vals.size >= pt.size && vals.subList(0, pt.size) == pt
            }
        }
        return if (matched.all { it != null }) matched.filterNotNull().distinct() else null
    }
}
