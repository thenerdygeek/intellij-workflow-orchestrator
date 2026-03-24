package com.workflow.orchestrator.agent.security

/**
 * Risk level for a shell command.
 */
enum class CommandRisk { SAFE, RISKY, DANGEROUS }

/**
 * Classifies shell commands by risk level before execution.
 *
 * Used by RunCommandTool and ApprovalGate to enforce safety policies:
 * - SAFE commands execute without approval
 * - RISKY commands require user approval (configurable)
 * - DANGEROUS commands are blocked outright
 *
 * Unknown commands default to RISKY (safe-by-default principle).
 */
object CommandSafetyAnalyzer {

    private val DANGEROUS_PATTERNS = listOf(
        Regex("""rm\s+(-\w*[rf]\w*\s+)+"""),              // rm with -r or -f flags, any target
        Regex("""\|\s*(ba)?sh\b"""),                      // pipe to bash/sh
        Regex("""\$\("""),                                 // subshell $(...)
        Regex("""`[^`]+`"""),                              // backtick injection
        Regex("""(?i)(DROP|TRUNCATE|DELETE\s+FROM)\s"""), // SQL destruction
        Regex("""(?i)mkfs\."""),                           // format disk
        Regex("""(?i):()\{\s*:\|:&\s*\};:"""),            // fork bomb
        Regex(""">\s*/dev/sd"""),                          // overwrite disk
        Regex("""chmod\s+(-\w+\s+)*777\s+/"""),           // chmod 777 /
        Regex("""(?i)sudo\s"""),                           // sudo
        Regex("""kill\s+-9\s+(-1|1)\b"""),                // kill all
        Regex("""(?i)\\\\.*\\(admin|c)\$"""),             // Windows admin share
    )

    private val RISKY_PATTERNS = listOf(
        Regex("""git\s+push\b"""),
        Regex("""git\s+reset\s+--hard"""),
        Regex("""git\s+checkout\s+--\s"""),
        Regex("""git\s+branch\s+-[dD]\b"""),
        Regex("""docker\s+(build|push|run)\b"""),
        Regex("""npm\s+publish\b"""),
        Regex("""(?i)curl\s.*-X\s*(PUT|POST|DELETE)\b"""),
        Regex("""gh\s+(pr|issue)\s+(create|close|merge)\b"""),
    )

    private val SAFE_PREFIXES = listOf(
        "ls", "cat", "head", "tail", "wc", "find", "grep", "rg", "ag",
        "git status", "git log", "git diff", "git show", "git blame", "git branch",
        "mvn", "./mvnw", "gradle", "./gradlew", "npm test", "npm run", "npx",
        "java", "javac", "kotlinc", "python", "pytest", "node",
        "echo", "pwd", "which", "env", "printenv", "date", "uname",
        "docker ps", "docker images", "docker logs",
    )

    /**
     * Classifies a shell command by risk level.
     *
     * Evaluation order: DANGEROUS patterns checked first, then RISKY patterns,
     * then SAFE prefix matching. Unknown commands default to RISKY.
     *
     * @param command The shell command string to classify
     * @return The risk level: SAFE, RISKY, or DANGEROUS
     */
    fun classify(command: String): CommandRisk {
        val trimmed = command.trim()
        if (DANGEROUS_PATTERNS.any { it.containsMatchIn(trimmed) }) return CommandRisk.DANGEROUS
        if (RISKY_PATTERNS.any { it.containsMatchIn(trimmed) }) return CommandRisk.RISKY
        if (SAFE_PREFIXES.any { trimmed.startsWith(it) }) return CommandRisk.SAFE
        return CommandRisk.RISKY // default to RISKY for unknown commands
    }
}
