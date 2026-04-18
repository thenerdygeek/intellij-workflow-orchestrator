package com.workflow.orchestrator.agent.tools.framework.build

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Poetry package management actions for Python projects.
 *
 * All actions delegate to the `poetry` CLI and parse its output,
 * or read lock/config files directly where appropriate.
 */

private const val CLI_TIMEOUT_SECONDS = 30L

internal suspend fun executePoetryList(params: JsonObject, project: Project): ToolResult {
    val searchFilter = params["search"]?.jsonPrimitive?.content?.lowercase()

    return try {
        withContext(Dispatchers.IO) {
            val output = runPoetryCommand(listOf("show", "--no-ansi"), project)
                ?: return@withContext poetryNotFoundError(project)

            val packages = parsePoetryShowList(output)

            val filtered = if (searchFilter != null) {
                packages.filter { it.name.lowercase().contains(searchFilter) }
            } else {
                packages
            }

            if (filtered.isEmpty()) {
                val filterDesc = if (searchFilter != null) " matching '$searchFilter'" else ""
                return@withContext ToolResult("No packages found$filterDesc.", "No packages", 5)
            }

            val content = buildString {
                appendLine("Poetry packages (${filtered.size} total):")
                appendLine()
                for (pkg in filtered.sortedBy { it.name.lowercase() }) {
                    val descStr = if (pkg.description.isNotBlank()) " — ${pkg.description}" else ""
                    appendLine("  ${pkg.name.padEnd(35)} ${pkg.version.padEnd(12)}$descStr")
                }
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "${filtered.size} packages",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult("Error listing Poetry packages: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}

internal suspend fun executePoetryOutdated(params: JsonObject, project: Project): ToolResult {
    return try {
        withContext(Dispatchers.IO) {
            val output = runPoetryCommand(listOf("show", "--outdated", "--no-ansi"), project)
                ?: return@withContext poetryNotFoundError(project)

            val packages = parsePoetryOutdatedList(output)

            if (packages.isEmpty()) {
                return@withContext ToolResult("All packages are up to date.", "No outdated packages", 5)
            }

            val content = buildString {
                appendLine("Outdated Poetry packages (${packages.size} total):")
                appendLine()
                appendLine("  ${"Package".padEnd(35)} ${"Current".padEnd(12)} ${"Latest".padEnd(12)}")
                appendLine("  ${"─".repeat(35)} ${"─".repeat(12)} ${"─".repeat(12)}")
                for (pkg in packages.sortedBy { it.name.lowercase() }) {
                    appendLine("  ${pkg.name.padEnd(35)} ${pkg.current.padEnd(12)} ${pkg.latest.padEnd(12)}")
                }
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "${packages.size} outdated packages",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult("Error checking outdated packages: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}

internal suspend fun executePoetryShow(params: JsonObject, project: Project): ToolResult {
    val packageName = params["package"]?.jsonPrimitive?.content
        ?: return ToolResult(
            "Error: 'package' parameter required for poetry_show action.",
            "Error: missing package",
            ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )

    return try {
        withContext(Dispatchers.IO) {
            val output = runPoetryCommand(listOf("show", packageName, "--no-ansi"), project)
                ?: return@withContext poetryNotFoundError(project)

            if (output.isBlank() || output.contains("Package not found")) {
                return@withContext ToolResult(
                    "Package '$packageName' not found.",
                    "Package not found",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
            }

            val content = buildString {
                appendLine("Poetry package details for '$packageName':")
                appendLine()
                append(output.trim())
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "poetry show $packageName",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult("Error showing package '$packageName': ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}

internal suspend fun executePoetryLockStatus(params: JsonObject, project: Project): ToolResult {
    return try {
        withContext(Dispatchers.IO) {
            val basePath = project.basePath
                ?: return@withContext ToolResult("Error: project base path not available", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

            val baseDir = File(basePath)
            val pyprojectFile = File(baseDir, "pyproject.toml")
            val lockFile = File(baseDir, "poetry.lock")

            if (!pyprojectFile.isFile) {
                return@withContext ToolResult(
                    "No pyproject.toml found. This does not appear to be a Poetry project.",
                    "No pyproject.toml",
                    5
                )
            }

            val content = buildString {
                appendLine("Poetry lock status:")
                appendLine()
                appendLine("  pyproject.toml: present (modified ${formatFileAge(pyprojectFile)})")

                if (lockFile.isFile) {
                    appendLine("  poetry.lock:    present (modified ${formatFileAge(lockFile)})")

                    // Check if lock is older than pyproject.toml
                    if (lockFile.lastModified() < pyprojectFile.lastModified()) {
                        appendLine()
                        appendLine("  WARNING: poetry.lock is older than pyproject.toml.")
                        appendLine("  Run 'poetry lock' to update the lock file.")
                    } else {
                        appendLine()
                        appendLine("  Lock file appears up to date.")
                    }

                    // Parse lock file stats
                    val lockStats = parseLockFileStats(lockFile)
                    appendLine()
                    appendLine("  Lock file stats:")
                    appendLine("    Packages: ${lockStats.packageCount}")
                    if (lockStats.pythonVersions.isNotBlank()) {
                        appendLine("    Python: ${lockStats.pythonVersions}")
                    }
                    if (lockStats.contentHash.isNotBlank()) {
                        appendLine("    Content hash: ${lockStats.contentHash}")
                    }
                } else {
                    appendLine("  poetry.lock:    MISSING")
                    appendLine()
                    appendLine("  Run 'poetry lock' to generate the lock file.")
                }
            }

            ToolResult(
                content = content.trimEnd(),
                summary = if (lockFile.isFile) "Lock file present" else "Lock file missing",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult("Error checking lock status: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}

internal suspend fun executePoetryScripts(params: JsonObject, project: Project): ToolResult {
    return try {
        withContext(Dispatchers.IO) {
            val basePath = project.basePath
                ?: return@withContext ToolResult("Error: project base path not available", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

            val pyprojectFile = File(basePath, "pyproject.toml")
            if (!pyprojectFile.isFile) {
                return@withContext ToolResult(
                    "No pyproject.toml found.",
                    "No pyproject.toml",
                    5
                )
            }

            val scripts = parsePoetryScripts(pyprojectFile)

            if (scripts.isEmpty()) {
                return@withContext ToolResult(
                    "No scripts defined in pyproject.toml.",
                    "No scripts",
                    5
                )
            }

            val content = buildString {
                appendLine("Poetry scripts (${scripts.size} total):")
                appendLine()
                for ((name, target) in scripts.toSortedMap()) {
                    appendLine("  $name = $target")
                }
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "${scripts.size} scripts",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult("Error reading scripts: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}

// ── Data classes ──────────────────────────────────────────────────────────

private data class PoetryPackageInfo(val name: String, val version: String, val description: String)
private data class PoetryOutdatedInfo(val name: String, val current: String, val latest: String)
private data class LockFileStats(val packageCount: Int, val pythonVersions: String, val contentHash: String)

// ── CLI execution ────────────────────────────────────────────────────────

private fun runPoetryCommand(args: List<String>, project: Project): String? {
    val basePath = project.basePath ?: return null

    try {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val command = if (isWindows) {
            listOf("cmd.exe", "/c", "poetry") + args
        } else {
            listOf("poetry") + args
        }

        val process = ProcessBuilder(command)
            .directory(File(basePath))
            .redirectErrorStream(true)
            .start()

        // Drain stdout concurrently to prevent pipe-buffer deadlock
        val outputFuture = CompletableFuture.supplyAsync {
            process.inputStream.bufferedReader().readText()
        }
        val completed = process.waitFor(CLI_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            return null
        }

        val output = try {
            outputFuture.get(5, TimeUnit.SECONDS)
        } catch (_: Exception) { "" }
        return if (process.exitValue() == 0) output else null
    } catch (_: Exception) {
        return null
    }
}

// ── Output parsers ───────────────────────────────────────────────────────

private fun parsePoetryShowList(output: String): List<PoetryPackageInfo> {
    // poetry show outputs: package-name   version  description
    val results = mutableListOf<PoetryPackageInfo>()
    for (line in output.lines()) {
        val trimmed = line.trim()
        if (trimmed.isBlank() || trimmed.startsWith("Warning")) continue
        // Split on 2+ whitespace chars
        val parts = trimmed.split(Regex("""\s{2,}"""), limit = 3)
        if (parts.size >= 2) {
            results.add(PoetryPackageInfo(
                name = parts[0].trim(),
                version = parts[1].trim(),
                description = if (parts.size >= 3) parts[2].trim() else ""
            ))
        }
    }
    return results
}

private fun parsePoetryOutdatedList(output: String): List<PoetryOutdatedInfo> {
    // poetry show --outdated outputs: package-name  current  available  description
    val results = mutableListOf<PoetryOutdatedInfo>()
    for (line in output.lines()) {
        val trimmed = line.trim()
        if (trimmed.isBlank() || trimmed.startsWith("Warning")) continue
        val parts = trimmed.split(Regex("""\s{2,}"""), limit = 4)
        if (parts.size >= 3) {
            results.add(PoetryOutdatedInfo(
                name = parts[0].trim(),
                current = parts[1].trim(),
                latest = parts[2].trim()
            ))
        }
    }
    return results
}

// ── File parsers ─────────────────────────────────────────────────────────

private fun parseLockFileStats(lockFile: File): LockFileStats {
    var packageCount = 0
    var pythonVersions = ""
    var contentHash = ""

    lockFile.useLines { lines ->
        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed == "[[package]]" -> packageCount++
                trimmed.startsWith("python-versions") && pythonVersions.isBlank() -> {
                    pythonVersions = trimmed.substringAfter("=").trim().trim('"')
                }
                trimmed.startsWith("content-hash") && contentHash.isBlank() -> {
                    contentHash = trimmed.substringAfter("=").trim().trim('"')
                }
            }
        }
    }

    return LockFileStats(packageCount, pythonVersions, contentHash)
}

private fun parsePoetryScripts(pyprojectFile: File): Map<String, String> {
    val scripts = mutableMapOf<String, String>()
    var inScripts = false

    pyprojectFile.readLines().forEach { rawLine ->
        val line = rawLine.trim()
        when {
            line == "[tool.poetry.scripts]" -> inScripts = true
            inScripts && line.startsWith("[") -> inScripts = false
            inScripts && line.contains("=") && !line.startsWith("#") -> {
                val parts = line.split("=", limit = 2)
                if (parts.size == 2) {
                    scripts[parts[0].trim()] = parts[1].trim().trim('"', '\'')
                }
            }
        }
    }
    return scripts
}

private fun formatFileAge(file: File): String {
    val ageMs = System.currentTimeMillis() - file.lastModified()
    val ageSec = ageMs / 1000
    return when {
        ageSec < 60 -> "${ageSec}s ago"
        ageSec < 3600 -> "${ageSec / 60}m ago"
        ageSec < 86400 -> "${ageSec / 3600}h ago"
        else -> "${ageSec / 86400}d ago"
    }
}

private fun poetryNotFoundError(project: Project): ToolResult {
    val basePath = project.basePath
    val hint = if (basePath != null && File(basePath, "pyproject.toml").isFile) {
        " pyproject.toml exists but Poetry CLI is not available."
    } else {
        ""
    }
    return ToolResult(
        "Poetry is not available.$hint Ensure Poetry is installed and on PATH.",
        "Poetry not found",
        ToolResult.ERROR_TOKEN_ESTIMATE,
        isError = true
    )
}
