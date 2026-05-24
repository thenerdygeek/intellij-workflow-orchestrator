package com.workflow.orchestrator.agent.security

import com.workflow.orchestrator.agent.tools.process.ShellType

/**
 * Default command filter that hard-blocks universally dangerous shell patterns.
 *
 * Unlike [CommandSafetyAnalyzer] (which classifies risk for user-approvable gating),
 * this filter produces a binary Allow/Reject result. Rejected commands are NEVER executed,
 * regardless of user approval.
 *
 * ## Defense-in-depth layering
 *
 * This filter applies multiple hardening passes before evaluating patterns:
 *
 * 1. **Raw-text patterns** — pipe-to-shell, base64-pipe, redirect-to-device, fork-bomb, and
 *    credential-file truncation are checked on the raw (unmodified) command string. These
 *    patterns span shell operators (`|`, `>`) which the tokenizer classifies as structural.
 * 2. **Unquoted token patterns** — patterns for `sudo`, `rm -rf /`, `mkfs`, `dd`,
 *    `chmod 777 /`, `chown -R /`, `kill -9 -1`, `nc -l`, `mount` are checked against
 *    the structural unquoted tokens (operators filtered out). Quoted content like
 *    `grep "rm -rf" file.txt` will NOT trigger these.
 * 3. **Quote-concatenation join** — the source is re-parsed at the *word* level, joining
 *    adjacent quoted/unquoted segments that have no whitespace between them
 *    (e.g., `'r''m'` → `rm`). The reconstructed word list is checked against the same
 *    unquoted patterns. Quoted arguments to other commands (e.g., `echo 'never run rm -rf /'`)
 *    are treated as a single argument word and won't produce a false match because the
 *    check works word-by-word, not on the full flattened string.
 * 4. **Variable expansion** — simple `VAR=value; $VAR ...` single-line patterns are
 *    expanded before matching.
 * 5. **Recursive wrapper detection** — `bash -c "..."`, `sh -c '...'`, `eval "..."`,
 *    `powershell -c "..."`, `cmd /c "..."` have their inner strings recursively re-checked.
 * 6. **Command substitution** — `` `...` `` and `$(...)` bodies are recursively re-checked.
 * 7. **PowerShell blocklist** — `Remove-Item -Recurse -Force`, `Format-Volume`,
 *    `Invoke-Expression`, etc. when shellType == POWERSHELL.
 *
 * ## Limitations
 *
 * Shell parsing is an undecidable problem in the general case. This filter deliberately
 * does best-effort sanitization and relies on [CommandSafetyAnalyzer] (the approval-gate
 * analyzer) as the second line of defense. Commands that require user approval in
 * [CommandSafetyAnalyzer] are visible to the human before execution.
 *
 * TODO: Future hardening — integrate a sandbox (bubblewrap / firejail / macOS sandbox-exec)
 * to limit syscalls even for commands that pass this filter. The filter would remain the
 * fast-path pre-spawn gate; the sandbox would be the enforcement boundary.
 */
class DefaultCommandFilter : CommandFilter {

    override fun check(command: String, shellType: ShellType): FilterResult {
        return checkRecursive(command, shellType, depth = 0)
    }

    private fun checkRecursive(command: String, shellType: ShellType, depth: Int): FilterResult {
        if (depth > MAX_RECURSION_DEPTH) return FilterResult.Allow
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return FilterResult.Allow

        // ── Pass 1: raw-text patterns (operators are structural) ──
        // Pipe-to-shell, redirect-to-device, base64-pipe-shell, and fork-bomb patterns
        // span shell operators. We check on the raw string.
        for ((pattern, reason) in RAW_TEXT_BLOCKED) {
            if (pattern.containsMatchIn(trimmed)) {
                return FilterResult.Reject(reason)
            }
        }

        // ── Pass 2: tokenize and build unquoted token text ──
        val tokens = CommandSafetyAnalyzer.tokenize(trimmed)
        val unquotedTokens = tokens.filter { !it.quoted && !it.isOperator }
        val unquotedText = unquotedTokens.joinToString(" ") { it.value }

        // ── Pass 3: pattern matching on structural unquoted text ──
        // Quoted arguments are excluded, preventing false positives like
        // `grep "rm -rf" file.txt` or `echo 'never run rm -rf /'`.
        for ((pattern, reason) in UNQUOTED_BLOCKED) {
            if (pattern.containsMatchIn(unquotedText)) {
                return FilterResult.Reject(reason)
            }
        }

        // ── Pass 4: quote-concatenation join (single-char quote splitting evasion) ──
        // Rebuild the command's word list by joining adjacent quoted/unquoted segments
        // that have no whitespace between them. This detects 'r''m' -rf / → rm -rf /.
        // We check only the SIMPLE WORD PATTERNS that involve a single known-dangerous
        // command name to avoid false positives from quoted argument content like
        // echo 'never run rm -rf /'.
        val concatenatedWords = buildConcatenatedWords(trimmed)
        val evasionResult = checkConcatenatedWords(concatenatedWords)
        if (evasionResult is FilterResult.Reject) return evasionResult

        // ── Pass 5: PowerShell-specific patterns ──
        if (shellType == ShellType.POWERSHELL) {
            val allText = tokens.filter { !it.isOperator }.joinToString(" ") { it.value }
            for ((pattern, reason) in POWERSHELL_BLOCKED) {
                if (pattern.containsMatchIn(allText)) {
                    return FilterResult.Reject(reason)
                }
            }
        }

        // ── Pass 6: variable expansion (VAR=value; $VAR or VAR=value command) ──
        val expanded = expandSimpleVars(trimmed)
        if (expanded != trimmed) {
            val expandedResult = checkRecursive(expanded, shellType, depth + 1)
            if (expandedResult is FilterResult.Reject) return expandedResult
        }

        // ── Pass 7: recursive wrapper detection ──
        val wrapperInner = extractWrapperInner(trimmed)
        if (wrapperInner != null) {
            val innerResult = checkRecursive(wrapperInner, shellType, depth + 1)
            if (innerResult is FilterResult.Reject) return innerResult
        }

        // ── Pass 8: command substitution ($(...) and backtick) ──
        val subshellInner = extractSubshellInner(trimmed)
        if (subshellInner != null) {
            val innerResult = checkRecursive(subshellInner, shellType, depth + 1)
            if (innerResult is FilterResult.Reject) return innerResult
        }

        return FilterResult.Allow
    }

    companion object {
        private const val MAX_RECURSION_DEPTH = 5

        /**
         * Patterns checked on the raw command string (before tokenization).
         * Used for patterns that span shell operators (|, >) which are structural.
         *
         * Rationale: operators are not in quoted text, so matching the full raw string
         * for cross-operator patterns (pipe-to-shell, redirect-to-device) is correct.
         * The dangerous payload on the LEFT side of | is not in quotes in a real attack.
         */
        private val RAW_TEXT_BLOCKED: List<Pair<Regex, String>> = listOf(
            // Pipe-to-shell (remote code execution)
            Regex("""curl\s+\S.*\|\s*(ba)?sh\b""") to "Blocked: curl ... | sh/bash (remote code execution)",
            Regex("""wget\s+\S.*\|\s*(ba)?sh\b""") to "Blocked: wget ... | sh/bash (remote code execution)",
            Regex("""fetch\s+\S.*\|\s*(ba)?sh\b""") to "Blocked: fetch ... | sh/bash (remote code execution)",

            // Base64-decode pipe to shell (evasion pattern)
            Regex("""base64\s+(-d|--decode)\s*\|\s*(ba)?sh\b""") to
                "Blocked: base64-decode pipe to shell (evasion pattern)",

            // Redirect to raw devices
            Regex(""">\s*/dev/sd[a-z]""") to "Blocked: redirect to /dev/sd (raw disk write)",
            Regex(""">\s*/dev/nvm""") to "Blocked: redirect to /dev/nvm (raw disk write)",

            // Root file truncation (:> /file)
            Regex(""":\s*>\s*/""") to "Blocked: truncate root filesystem file",

            // PowerShell remote code execution via pipe (applies to all shells, not just POWERSHELL type)
            Regex("""(Invoke-WebRequest|curl|wget)\s+.*\|\s*(Invoke-Expression|iex)\b""", RegexOption.IGNORE_CASE) to
                "Blocked: download | Invoke-Expression (PowerShell remote code execution)",

            // Credential file overwrite
            Regex(""">\s*/etc/passwd""") to "Blocked: overwrite /etc/passwd",
            Regex(""">\s*/etc/shadow""") to "Blocked: overwrite /etc/shadow",
            Regex(""">\s*~/\.bashrc""") to "Blocked: overwrite ~/.bashrc",
            Regex(""">\s*~/\.bash_profile""") to "Blocked: overwrite ~/.bash_profile",
            Regex(""">\s*~/\.profile""") to "Blocked: overwrite ~/.profile",
            Regex(""">\s*~/\.zshrc""") to "Blocked: overwrite ~/.zshrc",
        )

        /**
         * Patterns checked against the unquoted (structural) portion of the command.
         * Quoted arguments are excluded, preventing false positives like
         * `grep "rm -rf" file.txt` or `echo 'sudo command'`.
         *
         * Also applied to the quote-concatenated word list (Pass 4) for evasion detection.
         */
        private val UNQUOTED_BLOCKED: List<Pair<Regex, String>> = listOf(
            // Deletion — rm with recursive+force flags targeting / or ~
            Regex("""rm\s+-[rRfF]*[rR][rRfF]*\s+/""") to "Blocked: rm -rf / (recursive root deletion)",
            Regex("""rm\s+-[rRfF]*[rR][rRfF]*\s+~""") to "Blocked: rm -rf ~ (recursive home deletion)",
            Regex("""rm\s+-[rRfF]*[rR][rRfF]*\s+\${'$'}HOME""") to "Blocked: rm -rf \$HOME",

            // Privilege escalation
            Regex("""^\s*sudo\s""") to "Blocked: sudo (privilege escalation)",

            // Fork bomb — checked in unquoted tokens so :(){ inside a quoted echo is safe
            Regex(""":\(\)\s*\{""") to "Blocked: fork bomb detected",

            // Filesystem destruction
            Regex("""mkfs\.""") to "Blocked: mkfs (filesystem format)",
            Regex("""mkfs\b""") to "Blocked: mkfs (filesystem format)",
            Regex("""dd\s+if=""") to "Blocked: dd (raw disk write)",

            // Permission / ownership nukes
            Regex("""chmod\s+(-R\s+)?777\s+/""") to "Blocked: chmod 777 / (open all permissions on root)",
            Regex("""chmod\s+(-R\s+)?777\s+~""") to "Blocked: chmod 777 ~ (open home permissions)",
            Regex("""chown\s+-R\s+\S+\s+/""") to "Blocked: chown -R on / (recursive ownership change on root)",

            // Kill everything
            Regex("""kill\s+(-9\s+-1|-1\s+-9|-KILL\s+-1|-1\s+-KILL)""") to "Blocked: kill -9 -1 (kill all processes)",

            // Netcat listener (recon/backdoor)
            Regex("""nc\s+(-l|-lvp|-lvnp|-lnvp)\b""") to "Blocked: nc listener (network backdoor)",
            Regex("""netcat\s+(-l|-lvp)\b""") to "Blocked: netcat listener (network backdoor)",

            // Mount/unmount (root-level disk ops)
            Regex("""^\s*(u?mount)\s""") to "Blocked: mount/umount (disk operation requires elevated privileges)",
        )

        /**
         * PowerShell-specific hard-block patterns.
         * Applied only when shellType == POWERSHELL.
         */
        private val POWERSHELL_BLOCKED: List<Pair<Regex, String>> = listOf(
            Regex("""Remove-Item\s+.*(-Recurse|-r)\b.*(-Force|-fo?)\b""", RegexOption.IGNORE_CASE) to
                "Blocked: Remove-Item -Recurse -Force (PowerShell recursive deletion)",
            Regex("""Remove-Item\s+.*(-Force|-fo?)\b.*(-Recurse|-r)\b""", RegexOption.IGNORE_CASE) to
                "Blocked: Remove-Item -Force -Recurse (PowerShell recursive deletion)",
            Regex("""Remove-Item\s+-r\b""", RegexOption.IGNORE_CASE) to
                "Blocked: Remove-Item -r (PowerShell recursive deletion)",
            Regex("""Format-Volume\b""", RegexOption.IGNORE_CASE) to
                "Blocked: Format-Volume (PowerShell volume format)",
            Regex("""Clear-Disk\b""", RegexOption.IGNORE_CASE) to
                "Blocked: Clear-Disk (PowerShell disk wipe)",
            Regex("""Initialize-Disk\b""", RegexOption.IGNORE_CASE) to
                "Blocked: Initialize-Disk (PowerShell disk initialization)",
            Regex("""Stop-Process\s+-Id\s+\*""", RegexOption.IGNORE_CASE) to
                "Blocked: Stop-Process -Id * (kill all processes)",
            Regex("""Stop-Process\s+-Name\s+\*""", RegexOption.IGNORE_CASE) to
                "Blocked: Stop-Process -Name * (kill all processes)",
            Regex("""(Invoke-Expression|iex)\s+""", RegexOption.IGNORE_CASE) to
                "Blocked: Invoke-Expression / iex (PowerShell eval — evasion vector)",
            Regex("""Set-MpPreference\s+-DisableRealtimeMonitoring\b""", RegexOption.IGNORE_CASE) to
                "Blocked: disable Windows Defender (antivirus bypass)",
        )

        // ─────────────────────────────────────────────────────────────────────
        //  Helper: targeted check on concatenated word list
        // ─────────────────────────────────────────────────────────────────────

        /**
         * Checks the concatenated word list for dangerous commands that could be
         * constructed via quote-splitting (e.g., 'r''m' → rm).
         *
         * Only checks word[N] == dangerous-command-name AND word[N+1] looks like dangerous
         * flags/targets. This avoids false positives from quoted argument content where
         * `echo 'never run rm -rf /'` produces the word `never run rm -rf /` as an
         * opaque string (it stays as a single word, not split).
         *
         * Note: `buildConcatenatedWords` keeps content inside quotes as a single opaque
         * word (space inside quotes is NOT a word delimiter). So `echo 'rm -rf /'` gives
         * `["echo", "rm -rf /"]`. Word[1] is `"rm -rf /"` — this does NOT equal `"rm"`,
         * so the check below is safe.
         */
        internal fun checkConcatenatedWords(words: List<String>): FilterResult {
            // Find segment starts (after operators) to identify command positions
            val commandPositions = mutableListOf<Int>()
            commandPositions.add(0)
            for (i in words.indices) {
                if (words[i] in OPERATOR_WORDS) {
                    if (i + 1 < words.size) commandPositions.add(i + 1)
                }
            }

            for (startIdx in commandPositions) {
                if (startIdx >= words.size) continue
                val cmdWord = words[startIdx].lowercase()
                val remaining = words.drop(startIdx + 1)
                val remainingText = remaining.joinToString(" ")

                when (cmdWord) {
                    "rm" -> {
                        // rm with -r or -f and / or ~ target
                        val hasRecursive = remaining.any { it.matches(Regex("""-[rRfF]*[rR][rRfF]*""")) }
                        val hasRoot = remaining.any { it == "/" || it == "~" || it.startsWith("/") && it.length <= 2 }
                        if (hasRecursive && hasRoot) {
                            return FilterResult.Reject("Blocked: rm -rf / (recursive root deletion — quote-split evasion)")
                        }
                    }
                    "sudo" -> {
                        return FilterResult.Reject("Blocked: sudo (privilege escalation — quote-split evasion)")
                    }
                    "mkfs", "mkfs.ext4", "mkfs.xfs", "mkfs.btrfs", "mkfs.vfat" -> {
                        return FilterResult.Reject("Blocked: mkfs (filesystem format — quote-split evasion)")
                    }
                }
            }

            return FilterResult.Allow
        }

        private val OPERATOR_WORDS = setOf("|", "||", "&&", ";", ">", ">>", "<")

        // ─────────────────────────────────────────────────────────────────────
        //  Helper: quote-concatenation word reconstruction
        // ─────────────────────────────────────────────────────────────────────

        /**
         * Reconstructs the command's word list after shell quote removal.
         *
         * In bash, adjacent tokens with no whitespace between them are concatenated:
         *   'r''m' -rf /   →   ["rm", "-rf", "/"]
         *   "rm" -rf /     →   ["rm", "-rf", "/"]
         *
         * Strategy: walk the raw command character by character. Words are delimited by
         * whitespace (outside quotes). Adjacent quoted/unquoted segments with no whitespace
         * between them are concatenated into a single word. Operators (|, >, ;, &&, etc.)
         * are treated as word delimiters so they don't merge into adjacent words.
         *
         * This is intentionally more conservative than full POSIX parsing:
         * - Content *inside* a quoted argument (e.g., `echo 'foo bar'`) is a single word
         *   `foo bar`. This will NOT match `rm\s+-rf` because those are separate words.
         * - `'r''m' -rf /` produces words ["rm", "-rf", "/"], which DOES match.
         */
        internal fun buildConcatenatedWords(command: String): List<String> {
            val words = mutableListOf<String>()
            val currentWord = StringBuilder()
            var i = 0

            fun flushWord() {
                if (currentWord.isNotEmpty()) {
                    words.add(currentWord.toString())
                    currentWord.clear()
                }
            }

            while (i < command.length) {
                val c = command[i]
                when {
                    c == '\'' -> {
                        // Single-quote: copy literal content (space inside quote does NOT delimit words)
                        i++
                        while (i < command.length && command[i] != '\'') {
                            currentWord.append(command[i])
                            i++
                        }
                        // skip closing '
                    }
                    c == '"' -> {
                        // Double-quote: copy content with backslash escaping
                        i++
                        while (i < command.length && command[i] != '"') {
                            if (command[i] == '\\' && i + 1 < command.length) {
                                currentWord.append(command[i + 1])
                                i += 2
                            } else {
                                currentWord.append(command[i])
                                i++
                            }
                        }
                        // skip closing "
                    }
                    c == '\\' && i + 1 < command.length -> {
                        currentWord.append(command[i + 1])
                        i++
                    }
                    c.isWhitespace() -> {
                        flushWord()
                    }
                    // Shell operators: flush current word and add operator as its own word
                    c == '|' || c == '>' || c == '<' || c == ';' || c == '&' -> {
                        flushWord()
                        currentWord.append(c)
                        // handle >> and &&
                        if ((c == '>' || c == '&') && i + 1 < command.length && command[i + 1] == c) {
                            currentWord.append(c)
                            i++
                        }
                        flushWord()
                    }
                    else -> {
                        currentWord.append(c)
                    }
                }
                i++
            }
            flushWord()
            return words.filter { it.isNotBlank() }
        }

        // ─────────────────────────────────────────────────────────────────────
        //  Helper: simple variable expansion
        // ─────────────────────────────────────────────────────────────────────

        /**
         * Resolves simple single-line variable assignments before the command, e.g.:
         *   `RM=rm; $RM -rf /`  →  `rm -rf /`
         *
         * Deliberately limited: only `VAR=value; $VAR` / `VAR=value $VAR` where VAR is a
         * simple identifier and value is a single word. Does not handle arrays or subshells.
         */
        internal fun expandSimpleVars(command: String): String {
            val assignmentPattern = Regex("""^([A-Z_][A-Z0-9_]*)=(\S+)\s*[;]?\s*""", RegexOption.IGNORE_CASE)
            val vars = mutableMapOf<String, String>()

            var remainder = command.trim()
            while (true) {
                val match = assignmentPattern.find(remainder) ?: break
                vars[match.groupValues[1]] = match.groupValues[2]
                remainder = remainder.removePrefix(match.value)
            }

            if (vars.isEmpty()) return command

            var expanded = remainder
            for ((name, value) in vars) {
                expanded = expanded.replace("\$$name", value)
                expanded = expanded.replace("\${$name}", value)
            }
            return expanded.trim()
        }

        // ─────────────────────────────────────────────────────────────────────
        //  Helper: extract inner command from shell wrapper
        // ─────────────────────────────────────────────────────────────────────

        // bash -c "..."  or  bash -c '...'  (and sh, zsh, dash, ksh)
        // Group 1 = shell name, Group 2 = quote char, Group 3 = inner content
        private val BASH_C_PATTERN = Regex(
            """^\s*(bash|sh|zsh|dash|ksh)\s+(?:-[a-z]+\s+)*-c\s+(['"])(.+)\2\s*$""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        // eval "..."  or  eval '...'
        // Group 1 = quote char, Group 2 = inner content
        private val EVAL_PATTERN = Regex(
            """^\s*eval\s+(['"])(.+)\1\s*$""",
            RegexOption.DOT_MATCHES_ALL
        )

        // powershell -c "..." or powershell -Command "..."
        // Group 1 = quote char, Group 2 = inner content
        private val POWERSHELL_C_PATTERN = Regex(
            """^\s*powershell(?:\.exe)?\s+(?:-[a-z]+\s+)*(?:-c|-Command)\s+(['"])(.+)\1\s*$""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        // cmd /c "..."
        // Group 1 = quote char, Group 2 = inner content
        private val CMD_C_PATTERN = Regex(
            """^\s*cmd(?:\.exe)?\s+/[cC]\s+(['"])(.+)\1\s*$""",
            RegexOption.DOT_MATCHES_ALL
        )

        /**
         * Extracts the inner payload from shell wrapper commands.
         * Returns null if the command is not a recognized wrapper form.
         */
        internal fun extractWrapperInner(command: String): String? {
            // bash/sh -c: group 3 is inner content (group 2 is the quote char)
            BASH_C_PATTERN.find(command)?.let { m ->
                val inner = m.groupValues[3]
                if (inner.isNotBlank() && inner != command.trim()) return inner
            }
            // eval: group 2 is inner content
            EVAL_PATTERN.find(command)?.let { m ->
                val inner = m.groupValues[2]
                if (inner.isNotBlank() && inner != command.trim()) return inner
            }
            POWERSHELL_C_PATTERN.find(command)?.let { m ->
                val inner = m.groupValues[2]
                if (inner.isNotBlank() && inner != command.trim()) return inner
            }
            CMD_C_PATTERN.find(command)?.let { m ->
                val inner = m.groupValues[2]
                if (inner.isNotBlank() && inner != command.trim()) return inner
            }
            return null
        }

        // ─────────────────────────────────────────────────────────────────────
        //  Helper: extract inner command from $(...) and backtick substitution
        // ─────────────────────────────────────────────────────────────────────

        private val DOLLAR_PAREN = Regex("""\$\((.+)\)""", RegexOption.DOT_MATCHES_ALL)
        private val BACKTICK_PATTERN = Regex("""`(.+)`""", RegexOption.DOT_MATCHES_ALL)

        /**
         * Extracts the inner command from `$(...)` or backtick substitution.
         * Returns null if no substitution is found.
         */
        internal fun extractSubshellInner(command: String): String? {
            DOLLAR_PAREN.find(command)?.let { return it.groupValues[1] }
            BACKTICK_PATTERN.find(command)?.let { return it.groupValues[1] }
            return null
        }
    }
}
