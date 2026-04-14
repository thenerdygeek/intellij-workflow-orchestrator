package com.workflow.orchestrator.agent.tools.process

import com.intellij.openapi.project.Project
import java.io.File

/**
 * Resolved shell configuration for command execution.
 */
data class ShellConfig(
    val executable: String,
    val args: List<String>,
    val shellType: ShellType,
    val displayName: String
)

/**
 * Thrown when a requested shell is not available on the current platform.
 */
class ShellUnavailableException(message: String) : RuntimeException(message)

/**
 * Detects and resolves shells per platform with Windows-first priority.
 *
 * Resolution order:
 * - Windows: Git Bash → PowerShell 7+ → PowerShell 5.1 → cmd.exe
 * - Unix: /bin/bash → $SHELL → /bin/sh
 */
object ShellResolver {

    private val ANSI_REGEX = Regex("\u001B\\[[;\\d]*[A-Za-z]")

    private val BUILD_COMMAND_PREFIXES = listOf(
        "gradle", "./gradlew", "gradlew", "mvn", "./mvnw", "mvnw",
        "npm", "yarn", "pnpm", "docker build", "cargo build", "go build",
        "dotnet build", "make", "cmake", "pytest", "python -m pytest"
    )

    private val BUILD_COMMAND_KEYWORDS = listOf("test", "coverage", "docker")

    private val PASSWORD_PATTERNS = listOf(
        Regex("""(?i)password\s*:"""),
        Regex("""(?i)passphrase\s*:"""),
        Regex("""(?i)enter\s+.*token"""),
        Regex("""(?i)secret\s*:"""),
        Regex("""(?i)credentials?\s*:"""),
        Regex("""(?i)api.?key\s*:"""),
    )

    /**
     * Main entry point. Resolves a shell configuration for the current platform.
     *
     * @param requestedShell The shell requested by the caller (bash, cmd, powershell), or null for platform default.
     * @param project Optional project for settings access.
     * @return A validated [ShellConfig] ready for process execution.
     * @throws ShellUnavailableException if the requested shell is not available.
     */
    fun resolve(requestedShell: String?, project: Project?): ShellConfig {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")

        if (requestedShell == null) {
            return resolveDefault(isWindows, project)
        }

        return when (requestedShell.lowercase()) {
            "bash" -> resolveBash(isWindows)
            "cmd" -> resolveCmd(isWindows)
            "powershell" -> resolvePowerShell(isWindows, project)
            else -> throw ShellUnavailableException(
                "Unknown shell '$requestedShell'. Available shells: ${detectAvailableShells(project).joinToString(", ") { it.displayName }}."
            )
        }
    }

    /**
     * List all available shells on the current platform.
     */
    fun detectAvailableShells(project: Project?): List<ShellConfig> {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        if (!isWindows) {
            val bash = resolveBashExecutable()
            return listOf(ShellConfig(bash, listOf("-c"), ShellType.BASH, "bash"))
        }

        val shells = mutableListOf<ShellConfig>()
        findGitBash()?.let {
            shells.add(ShellConfig(it, listOf("-c"), ShellType.BASH, "Git Bash"))
        }
        shells.add(ShellConfig("cmd.exe", listOf("/c"), ShellType.CMD, "cmd.exe"))

        val powershellAllowed = try {
            project?.let {
                com.workflow.orchestrator.agent.settings.AgentSettings.getInstance(it).state.powershellEnabled
            } ?: true
        } catch (_: Exception) {
            true
        }
        if (powershellAllowed) {
            findPowerShell()?.let { path ->
                val name = if (path.contains("pwsh")) "PowerShell 7" else "PowerShell 5.1"
                shells.add(ShellConfig(path, listOf("-NoProfile", "-NonInteractive", "-Command"), ShellType.POWERSHELL, name))
            }
        }
        return shells
    }

    /**
     * Check standard Windows Git Bash paths.
     * @return Absolute path to bash.exe, or null if not found.
     */
    fun findGitBash(): String? {
        val candidates = listOf(
            System.getenv("PROGRAMFILES")?.let { "$it\\Git\\bin\\bash.exe" },
            System.getenv("PROGRAMFILES(X86)")?.let { "$it\\Git\\bin\\bash.exe" },
            System.getenv("LOCALAPPDATA")?.let { "$it\\Programs\\Git\\bin\\bash.exe" },
            "C:\\Program Files\\Git\\bin\\bash.exe",
            "C:\\Program Files (x86)\\Git\\bin\\bash.exe"
        )
        return candidates.filterNotNull().firstOrNull { File(it).exists() }
    }

    /**
     * Find PowerShell on Windows. Prefers pwsh.exe (PS7+) over powershell.exe (5.1).
     * @return Executable path, or null if not found.
     */
    fun findPowerShell(): String? {
        val candidates = listOf(
            System.getenv("PROGRAMFILES")?.let { "$it\\PowerShell\\7\\pwsh.exe" },
            "C:\\Program Files\\PowerShell\\7\\pwsh.exe",
            System.getenv("SYSTEMROOT")?.let { "$it\\System32\\WindowsPowerShell\\v1.0\\powershell.exe" },
            "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"
        )
        return candidates.filterNotNull().firstOrNull { File(it).exists() }
    }

    /**
     * Unix fallback chain: /bin/bash → $SHELL → /bin/sh → "sh"
     */
    fun resolveBashExecutable(): String {
        if (File("/bin/bash").exists()) return "/bin/bash"
        val shellEnv = System.getenv("SHELL")
        if (!shellEnv.isNullOrBlank() && File(shellEnv).exists()) return shellEnv
        if (File("/bin/sh").exists()) return "/bin/sh"
        return "sh"
    }

    /**
     * Build command detection via prefix + keyword matching.
     * Used to determine if a longer idle threshold should be applied.
     */
    fun isLikelyBuildCommand(command: String): Boolean {
        val trimmed = command.trim()
        if (BUILD_COMMAND_PREFIXES.any { trimmed.startsWith(it) }) return true
        val words = trimmed.lowercase().split("\\s+".toRegex())
        return BUILD_COMMAND_KEYWORDS.any { keyword -> words.any { it == keyword } }
    }

    /**
     * Strip ANSI escape codes from text.
     */
    fun stripAnsi(text: String): String = text.replace(ANSI_REGEX, "")

    /**
     * Detect password/credential prompts in the last 300 chars of output.
     */
    fun isLikelyPasswordPrompt(lastOutput: String): Boolean =
        PASSWORD_PATTERNS.any { it.containsMatchIn(lastOutput.takeLast(300)) }

    // ── Private resolution helpers ──────────────────

    private fun resolveDefault(isWindows: Boolean, project: Project?): ShellConfig {
        if (!isWindows) {
            val bash = resolveBashExecutable()
            return ShellConfig(bash, listOf("-c"), ShellType.BASH, "bash")
        }
        // Windows: Git Bash → PowerShell 7+ → PowerShell 5.1 → cmd.exe
        findGitBash()?.let {
            return ShellConfig(it, listOf("-c"), ShellType.BASH, "Git Bash")
        }
        val powershellAllowed = try {
            project?.let {
                com.workflow.orchestrator.agent.settings.AgentSettings.getInstance(it).state.powershellEnabled
            } ?: true
        } catch (_: Exception) {
            true
        }
        if (powershellAllowed) {
            findPowerShell()?.let { path ->
                val name = if (path.contains("pwsh")) "PowerShell 7" else "PowerShell 5.1"
                return ShellConfig(path, listOf("-NoProfile", "-NonInteractive", "-Command"), ShellType.POWERSHELL, name)
            }
        }
        return ShellConfig("cmd.exe", listOf("/c"), ShellType.CMD, "cmd.exe")
    }

    private fun resolveBash(isWindows: Boolean): ShellConfig {
        if (isWindows) {
            val gitBash = findGitBash()
                ?: throw ShellUnavailableException(
                    "shell='bash' requested but Git Bash is not installed. Available shells: cmd, powershell. Use one of those instead."
                )
            return ShellConfig(gitBash, listOf("-c"), ShellType.BASH, "Git Bash")
        }
        val bash = resolveBashExecutable()
        return ShellConfig(bash, listOf("-c"), ShellType.BASH, "bash")
    }

    private fun resolveCmd(isWindows: Boolean): ShellConfig {
        if (!isWindows) {
            val available = detectAvailableShells(null)
            throw ShellUnavailableException(
                "shell='cmd' is only available on Windows. Available shells: ${available.joinToString(", ") { it.displayName }}. Use shell='bash' instead."
            )
        }
        return ShellConfig("cmd.exe", listOf("/c"), ShellType.CMD, "cmd.exe")
    }

    private fun resolvePowerShell(isWindows: Boolean, project: Project?): ShellConfig {
        if (!isWindows) {
            val available = detectAvailableShells(null)
            throw ShellUnavailableException(
                "shell='powershell' is only available on Windows. Available shells: ${available.joinToString(", ") { it.displayName }}. Use shell='bash' instead."
            )
        }
        val powershellAllowed = try {
            project?.let {
                com.workflow.orchestrator.agent.settings.AgentSettings.getInstance(it).state.powershellEnabled
            } ?: true
        } catch (_: Exception) {
            true
        }
        if (!powershellAllowed) {
            throw ShellUnavailableException(
                "PowerShell is disabled in agent settings. Use shell='cmd' or shell='bash' instead."
            )
        }
        val path = findPowerShell()
            ?: throw ShellUnavailableException(
                "shell='powershell' requested but PowerShell is not found. Available shells: cmd${if (findGitBash() != null) ", bash" else ""}."
            )
        val name = if (path.contains("pwsh")) "PowerShell 7" else "PowerShell 5.1"
        return ShellConfig(path, listOf("-NoProfile", "-NonInteractive", "-Command"), ShellType.POWERSHELL, name)
    }
}
