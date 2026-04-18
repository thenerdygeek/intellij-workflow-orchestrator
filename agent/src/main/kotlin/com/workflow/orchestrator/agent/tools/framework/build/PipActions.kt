package com.workflow.orchestrator.agent.tools.framework.build

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * pip package management actions for Python projects.
 *
 * All actions delegate to the `pip` CLI and parse its output.
 * Falls back to `pip3` if `pip` is not found.
 */

private const val CLI_TIMEOUT_SECONDS = 30L

internal suspend fun executePipList(params: JsonObject, project: Project): ToolResult {
    val searchFilter = params["search"]?.jsonPrimitive?.content?.lowercase()

    return try {
        withContext(Dispatchers.IO) {
            val output = runPipCommand(listOf("list", "--format=json"), project)
                ?: return@withContext pipNotFoundError()

            val lines = parsePipJsonList(output)

            val filtered = if (searchFilter != null) {
                lines.filter { it.name.lowercase().contains(searchFilter) }
            } else {
                lines
            }

            if (filtered.isEmpty()) {
                val filterDesc = if (searchFilter != null) " matching '$searchFilter'" else ""
                return@withContext ToolResult("No packages found$filterDesc.", "No packages", 5)
            }

            val content = buildString {
                appendLine("Installed pip packages (${filtered.size} total):")
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
        ToolResult("Error listing pip packages: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}

internal suspend fun executePipOutdated(params: JsonObject, project: Project): ToolResult {
    return try {
        withContext(Dispatchers.IO) {
            val output = runPipCommand(listOf("list", "--outdated", "--format=json"), project)
                ?: return@withContext pipNotFoundError()

            val lines = parsePipJsonOutdated(output)

            if (lines.isEmpty()) {
                return@withContext ToolResult("All packages are up to date.", "No outdated packages", 5)
            }

            val content = buildString {
                appendLine("Outdated pip packages (${lines.size} total):")
                appendLine()
                appendLine("  ${"Package".padEnd(30)} ${"Current".padEnd(15)} ${"Latest".padEnd(15)} Type")
                appendLine("  ${"─".repeat(30)} ${"─".repeat(15)} ${"─".repeat(15)} ${"─".repeat(8)}")
                for (pkg in lines.sortedBy { it.name.lowercase() }) {
                    appendLine("  ${pkg.name.padEnd(30)} ${pkg.version.padEnd(15)} ${pkg.latestVersion.padEnd(15)} ${pkg.latestFiletype}")
                }
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "${lines.size} outdated packages",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult("Error checking outdated packages: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}

internal suspend fun executePipShow(params: JsonObject, project: Project): ToolResult {
    val packageName = params["package"]?.jsonPrimitive?.content
        ?: return ToolResult(
            "Error: 'package' parameter required for pip_show action.",
            "Error: missing package",
            ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )

    return try {
        withContext(Dispatchers.IO) {
            val output = runPipCommand(listOf("show", packageName), project)
                ?: return@withContext pipNotFoundError()

            if (output.isBlank() || output.contains("WARNING: Package(s) not found")) {
                return@withContext ToolResult(
                    "Package '$packageName' not found.",
                    "Package not found",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
            }

            val content = buildString {
                appendLine("Package details for '$packageName':")
                appendLine()
                append(output.trim())
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "pip show $packageName",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult("Error showing package '$packageName': ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}

internal suspend fun executePipDependencies(params: JsonObject, project: Project): ToolResult {
    val searchFilter = params["search"]?.jsonPrimitive?.content?.lowercase()

    return try {
        withContext(Dispatchers.IO) {
            val basePath = project.basePath
                ?: return@withContext ToolResult("Error: project base path not available", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

            val baseDir = File(basePath)

            // Parse requirements files for declared dependencies
            val reqFiles = listOf("requirements.txt", "requirements-dev.txt", "requirements-test.txt", "requirements/base.txt", "requirements/dev.txt", "requirements/test.txt")
            val dependencies = mutableListOf<PipDependencyEntry>()

            for (reqFileName in reqFiles) {
                val reqFile = File(baseDir, reqFileName)
                if (reqFile.isFile) {
                    parseRequirementsFile(reqFile, reqFileName, dependencies)
                }
            }

            // Also check setup.cfg and setup.py for install_requires
            val setupCfg = File(baseDir, "setup.cfg")
            if (setupCfg.isFile) {
                parseSetupCfg(setupCfg, dependencies)
            }

            val setupPy = File(baseDir, "setup.py")
            if (setupPy.isFile) {
                parseSetupPy(setupPy, dependencies)
            }

            val pyprojectToml = File(baseDir, "pyproject.toml")
            if (pyprojectToml.isFile) {
                parsePyprojectDependencies(pyprojectToml, dependencies)
            }

            val filtered = if (searchFilter != null) {
                dependencies.filter { it.name.lowercase().contains(searchFilter) }
            } else {
                dependencies
            }

            if (filtered.isEmpty()) {
                val filterDesc = if (searchFilter != null) " matching '$searchFilter'" else ""
                return@withContext ToolResult("No declared dependencies found$filterDesc.", "No dependencies", 5)
            }

            val bySource = filtered.groupBy { it.source }

            val content = buildString {
                appendLine("Declared Python dependencies (${filtered.size} total):")
                appendLine()
                for ((source, deps) in bySource.toSortedMap()) {
                    appendLine("[$source]")
                    for (dep in deps.sortedBy { it.name.lowercase() }) {
                        val versionStr = if (dep.versionSpec.isNotBlank()) dep.versionSpec else ""
                        appendLine("  ${dep.name}$versionStr")
                    }
                    appendLine()
                }
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "${filtered.size} dependencies from ${bySource.size} source(s)",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult("Error reading dependencies: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}

// ── Data classes ──────────────────────────────────────────────────────────

private data class PipPackageInfo(val name: String, val version: String)
private data class PipOutdatedInfo(val name: String, val version: String, val latestVersion: String, val latestFiletype: String)
private data class PipDependencyEntry(val name: String, val versionSpec: String, val source: String)

// ── CLI execution ────────────────────────────────────────────────────────

private fun runPipCommand(args: List<String>, project: Project): String? {
    val basePath = project.basePath ?: return null

    // Try pip, then pip3
    for (cmd in listOf("pip", "pip3")) {
        try {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val command = if (isWindows) {
                listOf("cmd.exe", "/c", cmd) + args
            } else {
                listOf(cmd) + args
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
                continue
            }

            val output = try {
                outputFuture.get(5, TimeUnit.SECONDS)
            } catch (_: Exception) { "" }
            if (process.exitValue() == 0) return output
        } catch (_: Exception) {
            continue
        }
    }
    return null
}

// ── JSON parsers ─────────────────────────────────────────────────────────

private val pipJson = Json { ignoreUnknownKeys = true }

private fun parsePipJsonList(output: String): List<PipPackageInfo> {
    // pip list --format=json outputs: [{"name": "pkg", "version": "1.0"}, ...]
    val array = pipJson.parseToJsonElement(output).jsonArray
    return array.map { item ->
        val obj = item.jsonObject
        PipPackageInfo(
            name = obj["name"]?.jsonPrimitive?.content ?: "",
            version = obj["version"]?.jsonPrimitive?.content ?: "",
        )
    }
}

private fun parsePipJsonOutdated(output: String): List<PipOutdatedInfo> {
    // pip list --outdated --format=json outputs: [{"name": "pkg", "version": "1.0", "latest_version": "2.0", "latest_filetype": "wheel"}, ...]
    val array = pipJson.parseToJsonElement(output).jsonArray
    return array.map { item ->
        val obj = item.jsonObject
        PipOutdatedInfo(
            name = obj["name"]?.jsonPrimitive?.content ?: "",
            version = obj["version"]?.jsonPrimitive?.content ?: "",
            latestVersion = obj["latest_version"]?.jsonPrimitive?.content ?: "",
            latestFiletype = obj["latest_filetype"]?.jsonPrimitive?.content ?: "",
        )
    }
}

// ── Requirements/config parsers ──────────────────────────────────────────

private val REQUIREMENTS_LINE_PATTERN = Regex("""^([a-zA-Z0-9][\w.\-]*)(.*)$""")

private fun parseRequirementsFile(file: File, source: String, results: MutableList<PipDependencyEntry>) {
    file.readLines().forEach { rawLine ->
        val line = rawLine.trim()
        if (line.isBlank() || line.startsWith("#") || line.startsWith("-")) return@forEach
        val match = REQUIREMENTS_LINE_PATTERN.matchEntire(line) ?: return@forEach
        results.add(PipDependencyEntry(match.groupValues[1], match.groupValues[2].trim(), source))
    }
}

private fun parseSetupCfg(file: File, results: MutableList<PipDependencyEntry>) {
    var inInstallRequires = false
    file.readLines().forEach { rawLine ->
        val line = rawLine.trim()
        when {
            line == "install_requires =" || line.startsWith("install_requires=") -> {
                inInstallRequires = true
            }
            inInstallRequires && line.isNotBlank() && !line.startsWith("[") && !line.contains("=") -> {
                val match = REQUIREMENTS_LINE_PATTERN.matchEntire(line)
                if (match != null) {
                    results.add(PipDependencyEntry(match.groupValues[1], match.groupValues[2].trim(), "setup.cfg"))
                }
            }
            inInstallRequires && (line.startsWith("[") || (line.contains("=") && !line.startsWith(" "))) -> {
                inInstallRequires = false
            }
        }
    }
}

private fun parseSetupPy(file: File, results: MutableList<PipDependencyEntry>) {
    val content = file.readText()
    val pattern = Regex("""install_requires\s*=\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
    val match = pattern.find(content) ?: return
    val depList = match.groupValues[1]
    val depPattern = Regex("""["']([a-zA-Z0-9][\w.\-]*)([^"']*)["']""")
    for (depMatch in depPattern.findAll(depList)) {
        results.add(PipDependencyEntry(depMatch.groupValues[1], depMatch.groupValues[2].trim(), "setup.py"))
    }
}

private fun parsePyprojectDependencies(file: File, results: MutableList<PipDependencyEntry>) {
    var inDependencies = false
    file.readLines().forEach { rawLine ->
        val line = rawLine.trim()
        when {
            line == "dependencies = [" || line.startsWith("dependencies = [") -> {
                inDependencies = true
                // Handle inline single-line: dependencies = ["foo>=1.0"]
                val inlinePattern = Regex("""["']([a-zA-Z0-9][\w.\-]*)([^"']*)["']""")
                for (match in inlinePattern.findAll(line)) {
                    results.add(PipDependencyEntry(match.groupValues[1], match.groupValues[2].trim(), "pyproject.toml"))
                }
            }
            inDependencies && line == "]" -> {
                inDependencies = false
            }
            inDependencies -> {
                val depPattern = Regex("""["']([a-zA-Z0-9][\w.\-]*)([^"']*)["']""")
                val match = depPattern.find(line)
                if (match != null) {
                    results.add(PipDependencyEntry(match.groupValues[1], match.groupValues[2].trim(), "pyproject.toml"))
                }
            }
        }
    }
}

private fun pipNotFoundError(): ToolResult = ToolResult(
    "pip is not available. Ensure Python and pip are installed and on PATH.",
    "pip not found",
    ToolResult.ERROR_TOKEN_ESTIMATE,
    isError = true
)
