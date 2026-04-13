package com.workflow.orchestrator.agent.tools.framework.build

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * uv package management actions for Python projects.
 *
 * uv is a fast Python package manager (https://github.com/astral-sh/uv).
 * Actions delegate to the `uv` CLI and parse its output, or read
 * lock/config files directly where appropriate.
 */

private const val CLI_TIMEOUT_SECONDS = 30L

internal suspend fun executeUvList(params: JsonObject, project: Project): ToolResult {
    val searchFilter = params["search"]?.jsonPrimitive?.content?.lowercase()

    return try {
        withContext(Dispatchers.IO) {
            val output = runUvCommand(listOf("pip", "list", "--format=json"), project)
                ?: return@withContext uvNotFoundError()

            val packages = parseUvJsonList(output)

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
                appendLine("uv installed packages (${filtered.size} total):")
                appendLine()
                for (pkg in filtered.sortedBy { it.name.lowercase() }) {
                    appendLine("  ${pkg.name}==${pkg.version}")
                }
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "${filtered.size} packages",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult("Error listing uv packages: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}

internal suspend fun executeUvOutdated(params: JsonObject, project: Project): ToolResult {
    return try {
        withContext(Dispatchers.IO) {
            // uv pip list --outdated --format=json
            val output = runUvCommand(listOf("pip", "list", "--outdated", "--format=json"), project)
                ?: return@withContext uvNotFoundError()

            val packages = parseUvJsonOutdated(output)

            if (packages.isEmpty()) {
                return@withContext ToolResult("All packages are up to date.", "No outdated packages", 5)
            }

            val content = buildString {
                appendLine("Outdated uv packages (${packages.size} total):")
                appendLine()
                appendLine("  ${"Package".padEnd(30)} ${"Current".padEnd(15)} ${"Latest".padEnd(15)}")
                appendLine("  ${"─".repeat(30)} ${"─".repeat(15)} ${"─".repeat(15)}")
                for (pkg in packages.sortedBy { it.name.lowercase() }) {
                    appendLine("  ${pkg.name.padEnd(30)} ${pkg.current.padEnd(15)} ${pkg.latest.padEnd(15)}")
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

internal suspend fun executeUvLockStatus(params: JsonObject, project: Project): ToolResult {
    return try {
        withContext(Dispatchers.IO) {
            val basePath = project.basePath
                ?: return@withContext ToolResult("Error: project base path not available", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

            val baseDir = File(basePath)
            val pyprojectFile = File(baseDir, "pyproject.toml")
            val lockFile = File(baseDir, "uv.lock")

            if (!pyprojectFile.isFile) {
                return@withContext ToolResult(
                    "No pyproject.toml found. This does not appear to be a uv project.",
                    "No pyproject.toml",
                    5
                )
            }

            val content = buildString {
                appendLine("uv lock status:")
                appendLine()
                appendLine("  pyproject.toml: present (modified ${formatUvFileAge(pyprojectFile)})")

                if (lockFile.isFile) {
                    appendLine("  uv.lock:        present (modified ${formatUvFileAge(lockFile)})")

                    if (lockFile.lastModified() < pyprojectFile.lastModified()) {
                        appendLine()
                        appendLine("  WARNING: uv.lock is older than pyproject.toml.")
                        appendLine("  Run 'uv lock' to update the lock file.")
                    } else {
                        appendLine()
                        appendLine("  Lock file appears up to date.")
                    }

                    // Parse lock file stats
                    val lockStats = parseUvLockStats(lockFile)
                    appendLine()
                    appendLine("  Lock file stats:")
                    appendLine("    Packages: ${lockStats.packageCount}")
                    if (lockStats.requiresPython.isNotBlank()) {
                        appendLine("    Requires Python: ${lockStats.requiresPython}")
                    }
                } else {
                    appendLine("  uv.lock:        MISSING")
                    appendLine()
                    appendLine("  Run 'uv lock' to generate the lock file.")
                }

                // Check for .python-version file
                val pythonVersion = File(baseDir, ".python-version")
                if (pythonVersion.isFile) {
                    appendLine()
                    appendLine("  .python-version: ${pythonVersion.readText().trim()}")
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

// ── Data classes ──────────────────────────────────────────────────────────

private data class UvPackageInfo(val name: String, val version: String)
private data class UvOutdatedInfo(val name: String, val current: String, val latest: String)
private data class UvLockStats(val packageCount: Int, val requiresPython: String)

// ── CLI execution ────────────────────────────────────────────────────────

private fun runUvCommand(args: List<String>, project: Project): String? {
    val basePath = project.basePath ?: return null

    try {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val command = if (isWindows) {
            listOf("cmd.exe", "/c", "uv") + args
        } else {
            listOf("uv") + args
        }

        val process = ProcessBuilder(command)
            .directory(File(basePath))
            .redirectErrorStream(true)
            .start()

        val completed = process.waitFor(CLI_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            return null
        }

        val output = process.inputStream.bufferedReader().readText()
        return if (process.exitValue() == 0) output else null
    } catch (_: Exception) {
        return null
    }
}

// ── JSON parsers ─────────────────────────────────────────────────────────

private fun parseUvJsonList(json: String): List<UvPackageInfo> {
    // Same JSON format as pip: [{"name": "pkg", "version": "1.0"}, ...]
    val results = mutableListOf<UvPackageInfo>()
    val pattern = Regex("""\{"name"\s*:\s*"([^"]+)"\s*,\s*"version"\s*:\s*"([^"]+)"\}""")
    for (match in pattern.findAll(json)) {
        results.add(UvPackageInfo(match.groupValues[1], match.groupValues[2]))
    }
    return results
}

private fun parseUvJsonOutdated(json: String): List<UvOutdatedInfo> {
    val results = mutableListOf<UvOutdatedInfo>()
    val pattern = Regex("""\{[^}]*"name"\s*:\s*"([^"]+)"[^}]*"version"\s*:\s*"([^"]+)"[^}]*"latest_version"\s*:\s*"([^"]+)"[^}]*\}""")
    for (match in pattern.findAll(json)) {
        results.add(UvOutdatedInfo(match.groupValues[1], match.groupValues[2], match.groupValues[3]))
    }
    return results
}

// ── File parsers ─────────────────────────────────────────────────────────

private fun parseUvLockStats(lockFile: File): UvLockStats {
    var packageCount = 0
    var requiresPython = ""

    lockFile.useLines { lines ->
        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed == "[[package]]" -> packageCount++
                trimmed.startsWith("requires-python") && requiresPython.isBlank() -> {
                    requiresPython = trimmed.substringAfter("=").trim().trim('"')
                }
            }
        }
    }

    return UvLockStats(packageCount, requiresPython)
}

private fun formatUvFileAge(file: File): String {
    val ageMs = System.currentTimeMillis() - file.lastModified()
    val ageSec = ageMs / 1000
    return when {
        ageSec < 60 -> "${ageSec}s ago"
        ageSec < 3600 -> "${ageSec / 60}m ago"
        ageSec < 86400 -> "${ageSec / 3600}h ago"
        else -> "${ageSec / 86400}d ago"
    }
}

private fun uvNotFoundError(): ToolResult = ToolResult(
    "uv is not available. Ensure uv is installed and on PATH (https://github.com/astral-sh/uv).",
    "uv not found",
    ToolResult.ERROR_TOKEN_ESTIMATE,
    isError = true
)
