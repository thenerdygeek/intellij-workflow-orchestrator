package com.workflow.orchestrator.agent.security

/**
 * Risk level for a shell command.
 */
enum class CommandRisk { SAFE, RISKY, DANGEROUS }

/**
 * Classifies shell commands by risk level before execution.
 *
 * Uses a POSIX-style shell tokenizer to avoid false positives from patterns
 * appearing inside quoted strings (e.g., `grep "DROP TABLE" file.sql` is SAFE,
 * not DANGEROUS). Each pipeline segment is classified independently.
 *
 * Used by RunCommandTool and ApprovalGate to enforce safety policies:
 * - SAFE commands execute without approval
 * - RISKY commands require user approval (configurable)
 * - DANGEROUS commands are blocked outright
 *
 * Unknown commands default to RISKY (safe-by-default principle).
 */
object CommandSafetyAnalyzer {

    /**
     * Classifies a shell command by risk level.
     *
     * 1. Tokenize respecting quotes (single, double, escaped)
     * 2. Split into segments by operators (|, &&, ||, ;)
     * 3. Check structural DANGEROUS patterns on unquoted tokens
     * 4. Check local curl/wget exemption
     * 5. Check RISKY patterns on unquoted tokens
     * 6. Check SAFE command names
     * 7. Default to RISKY
     *
     * The highest risk across all segments wins.
     */
    fun classify(command: String): CommandRisk {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return CommandRisk.RISKY

        val tokens = tokenize(trimmed)
        val segments = splitSegments(tokens)

        var maxRisk = CommandRisk.SAFE

        for (segment in segments) {
            val risk = classifySegment(segment)
            if (risk == CommandRisk.DANGEROUS) return CommandRisk.DANGEROUS
            if (risk.ordinal > maxRisk.ordinal) maxRisk = risk
        }

        return maxRisk
    }

    // ═══════════════════════════════════════════════════
    //  Shell tokenizer
    // ═══════════════════════════════════════════════════

    /**
     * Token from shell tokenization. Tracks whether the token was quoted
     * so pattern matching can skip quoted content.
     */
    data class Token(
        val value: String,
        val quoted: Boolean,
        val isOperator: Boolean = false
    )

    /**
     * POSIX-style shell tokenizer. Respects:
     * - Single quotes: 'content preserved literally'
     * - Double quotes: "content with $var but no glob"
     * - Backslash escaping: \x
     * - Operators: |, &&, ||, ;, >, >>, <
     *
     * Does NOT expand variables or globs — tokens are raw text.
     */
    fun tokenize(input: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val current = StringBuilder()
        var i = 0
        var quoted = false
        var quoteChar = ' '

        fun flush(isQuoted: Boolean = false) {
            if (current.isNotEmpty()) {
                tokens.add(Token(current.toString(), isQuoted))
                current.clear()
            }
        }

        while (i < input.length) {
            val c = input[i]

            when {
                // Inside single quotes — everything is literal until closing '
                quoted && quoteChar == '\'' -> {
                    if (c == '\'') {
                        flush(isQuoted = true)
                        quoted = false
                    } else {
                        current.append(c)
                    }
                }

                // Inside double quotes — mostly literal, backslash escaping
                quoted && quoteChar == '"' -> {
                    if (c == '"') {
                        flush(isQuoted = true)
                        quoted = false
                    } else if (c == '\\' && i + 1 < input.length) {
                        current.append(input[i + 1])
                        i++
                    } else {
                        current.append(c)
                    }
                }

                // Backslash escape outside quotes
                c == '\\' && i + 1 < input.length -> {
                    current.append(input[i + 1])
                    i++
                }

                // Start quote
                c == '\'' || c == '"' -> {
                    flush()
                    quoted = true
                    quoteChar = c
                }

                // Whitespace — token boundary
                c.isWhitespace() -> {
                    flush()
                }

                // Operators: &&, ||, |, ;, >>, >, <
                c == '|' -> {
                    flush()
                    if (i + 1 < input.length && input[i + 1] == '|') {
                        tokens.add(Token("||", quoted = false, isOperator = true))
                        i++
                    } else {
                        tokens.add(Token("|", quoted = false, isOperator = true))
                    }
                }
                c == '&' && i + 1 < input.length && input[i + 1] == '&' -> {
                    flush()
                    tokens.add(Token("&&", quoted = false, isOperator = true))
                    i++
                }
                c == ';' -> {
                    flush()
                    tokens.add(Token(";", quoted = false, isOperator = true))
                }
                c == '>' -> {
                    flush()
                    if (i + 1 < input.length && input[i + 1] == '>') {
                        tokens.add(Token(">>", quoted = false, isOperator = true))
                        i++
                    } else {
                        tokens.add(Token(">", quoted = false, isOperator = true))
                    }
                }
                c == '<' -> {
                    flush()
                    tokens.add(Token("<", quoted = false, isOperator = true))
                }

                // Subshell marker $( — track as unquoted token
                c == '$' && i + 1 < input.length && input[i + 1] == '(' -> {
                    flush()
                    current.append("$(")
                    i++
                }

                // Backtick
                c == '`' -> {
                    flush()
                    current.append('`')
                }

                else -> current.append(c)
            }
            i++
        }
        flush()
        return tokens
    }

    /**
     * Split token list into command segments by pipe/chain operators.
     */
    private fun splitSegments(tokens: List<Token>): List<List<Token>> {
        val segments = mutableListOf<List<Token>>()
        var current = mutableListOf<Token>()

        for (token in tokens) {
            if (token.isOperator && token.value in setOf("|", "&&", "||", ";")) {
                if (current.isNotEmpty()) segments.add(current)
                current = mutableListOf()
                // Track pipe-to-shell: if operator is | and next segment starts with sh/bash
                if (token.value == "|") {
                    // Add a marker so we can detect pipe targets
                    current.add(Token("__PIPE_TARGET__", quoted = false, isOperator = true))
                }
            } else {
                current.add(token)
            }
        }
        if (current.isNotEmpty()) segments.add(current)
        return segments
    }

    /**
     * Classify a single command segment (tokens between operators).
     */
    private fun classifySegment(tokens: List<Token>): CommandRisk {
        if (tokens.isEmpty()) return CommandRisk.SAFE

        val isPipeTarget = tokens.firstOrNull()?.value == "__PIPE_TARGET__"
        val effectiveTokens = if (isPipeTarget) tokens.drop(1) else tokens
        if (effectiveTokens.isEmpty()) return CommandRisk.SAFE

        // Unquoted tokens — only these participate in pattern matching
        val unquoted = effectiveTokens.filter { !it.quoted && !it.isOperator }
        val unquotedText = unquoted.joinToString(" ") { it.value }
        val commandName = unquoted.firstOrNull()?.value?.lowercase() ?: return CommandRisk.RISKY
        val allTokenText = effectiveTokens.joinToString(" ") { it.value }

        // ── DANGEROUS checks (on unquoted tokens only) ──

        // Pipe to shell: previous segment piped into sh/bash
        if (isPipeTarget && commandName in setOf("sh", "bash", "zsh")) {
            return CommandRisk.DANGEROUS
        }

        // rm with -r or -f flags (unquoted)
        if (commandName == "rm") {
            val flags = unquoted.drop(1).filter { it.value.startsWith("-") }
            if (flags.any { it.value.contains('r') || it.value.contains('f') }) {
                return CommandRisk.DANGEROUS
            }
        }

        // Subshell $( in unquoted position
        if (unquoted.any { it.value.contains("$(") }) {
            return CommandRisk.DANGEROUS
        }

        // Backtick in unquoted position
        if (unquoted.any { it.value.contains('`') }) {
            return CommandRisk.DANGEROUS
        }

        // SQL destruction keywords — check unquoted tokens always,
        // and also check quoted tokens when the command is a database CLI
        // (psql, mysql, sqlite3, etc.) since -c/-e passes SQL as a quoted argument
        val upperUnquoted = unquotedText.uppercase()
        if (SQL_DESTRUCTIVE.any { upperUnquoted.contains(it) }) {
            return CommandRisk.DANGEROUS
        }
        if (commandName in DB_CLI_COMMANDS) {
            val allText = effectiveTokens.joinToString(" ") { it.value }.uppercase()
            if (SQL_DESTRUCTIVE.any { allText.contains(it) }) {
                return CommandRisk.DANGEROUS
            }
        }

        // System-level dangerous commands
        if (commandName == "sudo") return CommandRisk.DANGEROUS
        if (commandName == "mkfs" || commandName.startsWith("mkfs.")) return CommandRisk.DANGEROUS
        if (commandName == "dd" && unquoted.any { it.value.startsWith("if=") }) return CommandRisk.DANGEROUS

        // Fork bomb pattern (very specific, keep regex for this one)
        if (FORK_BOMB.containsMatchIn(allTokenText)) return CommandRisk.DANGEROUS

        // Redirect to device files
        if (effectiveTokens.any { it.isOperator && it.value == ">" }) {
            val afterRedirect = effectiveTokens.dropWhile { !(it.isOperator && it.value == ">") }.drop(1)
            val target = afterRedirect.firstOrNull { !it.isOperator }?.value ?: ""
            if (target.startsWith("/dev/sd") || target.startsWith("/dev/nvm")) {
                return CommandRisk.DANGEROUS
            }
        }

        // chmod 777 on root
        if (commandName == "chmod") {
            if (unquoted.any { it.value == "777" } && unquoted.any { it.value == "/" }) {
                return CommandRisk.DANGEROUS
            }
        }

        // chown -R on root
        if (commandName == "chown") {
            val flags = unquoted.filter { it.value.startsWith("-") }
            if (flags.any { it.value.contains('R') } && unquoted.any { it.value == "/" }) {
                return CommandRisk.DANGEROUS
            }
        }

        // kill -9 -1 or kill -9 1 (kill all)
        if (commandName == "kill") {
            if (unquoted.any { it.value == "-9" } && unquoted.any { it.value == "-1" || it.value == "1" }) {
                return CommandRisk.DANGEROUS
            }
        }

        // Windows admin share
        if (unquoted.any { WINDOWS_ADMIN_SHARE.containsMatchIn(it.value) }) {
            return CommandRisk.DANGEROUS
        }

        // ── Local curl/wget exemption (before RISKY) ──

        if (commandName in setOf("curl", "wget")) {
            val urlTokens = unquoted.drop(1).filter { !it.value.startsWith("-") }
            val isLocal = urlTokens.any { token ->
                LOCAL_HOSTS.any { host ->
                    token.value.contains(host)
                }
            }
            if (isLocal) return CommandRisk.SAFE
        }

        // ── RISKY checks ──

        // Git write operations
        if (commandName == "git") {
            val subCmd = unquoted.getOrNull(1)?.value?.lowercase()
            when (subCmd) {
                "push" -> return CommandRisk.RISKY
                "reset" -> if (unquoted.any { it.value == "--hard" }) return CommandRisk.RISKY
                "checkout" -> if (unquoted.any { it.value == "--" }) return CommandRisk.RISKY
                "branch" -> if (unquoted.any { it.value == "-d" || it.value == "-D" }) return CommandRisk.RISKY
            }
        }

        // Docker write operations
        if (commandName == "docker") {
            val subCmd = unquoted.getOrNull(1)?.value?.lowercase()
            if (subCmd in setOf("build", "push", "run")) return CommandRisk.RISKY
        }

        // npm publish
        if (commandName == "npm" && unquoted.getOrNull(1)?.value == "publish") return CommandRisk.RISKY

        // curl/wget to remote with mutating methods
        if (commandName in setOf("curl", "wget")) {
            val hasMethod = unquoted.any { it.value == "-X" }
            if (hasMethod) {
                val methodIdx = unquoted.indexOfFirst { it.value == "-X" }
                val method = unquoted.getOrNull(methodIdx + 1)?.value?.uppercase()
                if (method in setOf("PUT", "POST", "DELETE", "PATCH")) {
                    return CommandRisk.RISKY
                }
            }
        }

        // gh CLI write operations
        if (commandName == "gh") {
            val subCmd = unquoted.getOrNull(1)?.value
            val action = unquoted.getOrNull(2)?.value
            if (subCmd in setOf("pr", "issue") && action in setOf("create", "close", "merge")) {
                return CommandRisk.RISKY
            }
        }

        // ── SAFE checks ──

        if (commandName in SAFE_COMMANDS) return CommandRisk.SAFE

        // Git read-only sub-commands
        if (commandName == "git") {
            val subCmd = unquoted.getOrNull(1)?.value?.lowercase()
            if (subCmd in SAFE_GIT_SUBCOMMANDS) return CommandRisk.SAFE
        }

        // Build tools with path prefix
        val fullCmd = effectiveTokens.firstOrNull()?.value ?: ""
        if (SAFE_COMMAND_PREFIXES.any { fullCmd == it || fullCmd.startsWith("$it ") || fullCmd == "./$it" }) {
            return CommandRisk.SAFE
        }

        // Docker read-only
        if (commandName == "docker") {
            val subCmd = unquoted.getOrNull(1)?.value?.lowercase()
            if (subCmd in setOf("ps", "images", "logs", "inspect")) return CommandRisk.SAFE
        }

        // npm/yarn read + run
        if (commandName in setOf("npm", "yarn", "pnpm")) {
            val subCmd = unquoted.getOrNull(1)?.value
            if (subCmd in setOf("test", "run", "list", "ls", "info", "view", "outdated", "audit")) {
                return CommandRisk.SAFE
            }
        }

        return CommandRisk.RISKY
    }

    // ═══════════════════════════════════════════════════
    //  Constants
    // ═══════════════════════════════════════════════════

    private val SQL_DESTRUCTIVE = listOf("DROP ", "TRUNCATE ", "DELETE FROM ")

    /** Database CLI tools where quoted arguments contain executable SQL. */
    private val DB_CLI_COMMANDS = setOf(
        "psql", "mysql", "sqlite3", "sqlcmd", "sqlplus",
        "mongosh", "mongo", "redis-cli", "cqlsh",
    )

    private val FORK_BOMB = Regex(""":\(\)\s*\{""")

    private val WINDOWS_ADMIN_SHARE = Regex("""(?i)\\\\.*\\(admin|c)\$""")

    private val LOCAL_HOSTS = listOf(
        "localhost", "127.0.0.1", "0.0.0.0", "::1",
        "host.docker.internal", "host-gateway",
    )

    private val SAFE_COMMANDS = setOf(
        "ls", "cat", "head", "tail", "wc", "find", "grep", "rg", "ag",
        "echo", "pwd", "which", "env", "printenv", "date", "uname",
        "whoami", "hostname", "file", "stat", "tree", "less", "more",
        "sort", "uniq", "cut", "tr", "tee", "diff", "comm", "xargs",
        "java", "javac", "kotlin", "kotlinc", "jar",
        "python", "python3", "pytest", "pip", "pip3",
        "node", "npx", "tsc",
        "cargo", "rustc",
        "go",
        "mvn", "gradle",
    )

    private val SAFE_COMMAND_PREFIXES = listOf(
        "./gradlew", "gradlew", "./mvnw", "mvnw",
    )

    private val SAFE_GIT_SUBCOMMANDS = setOf(
        "status", "log", "diff", "show", "blame", "shortlog", "branch", "tag",
        "rev-parse", "config", "stash", "ls-files", "cat-file", "rev-list",
        "merge-base", "name-rev", "describe", "reflog", "for-each-ref",
        "check-ignore", "ls-tree", "worktree", "version",
    )
}
