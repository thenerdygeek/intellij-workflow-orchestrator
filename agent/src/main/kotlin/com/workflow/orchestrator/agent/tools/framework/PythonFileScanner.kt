package com.workflow.orchestrator.agent.tools.framework

import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.ai.TokenEstimator
import java.io.File

/**
 * Shared utility for Python framework file scanning across Django, FastAPI, and Flask tools.
 *
 * Provides:
 * - [scanPythonFiles] — walks a project directory excluding virtual environments, caches,
 *   and build artifacts. Prevents scanning 10K+ files in venv directories.
 * - [redactIfSensitive] — redacts values for keys matching common secret patterns
 *   (SECRET_KEY, PASSWORD, API_KEY, TOKEN, etc.).
 */
object PythonFileScanner {

    /** Precompiled regex for finding the next top-level class definition. */
    private val NEXT_CLASS_REGEX = Regex("""^class\s+\w+""", RegexOption.MULTILINE)

    /**
     * Directory names to exclude from Python project file scanning.
     * These are virtual environments, caches, build artifacts, and VCS directories
     * that should never be scanned for Python framework code.
     */
    val EXCLUDED_DIRS = setOf(
        "venv", ".venv", "env", ".env",
        "node_modules",
        "__pycache__",
        ".git",
        ".tox", ".nox",
        ".mypy_cache", ".pytest_cache", ".ruff_cache",
        "dist", "build", ".eggs",
        ".hg", ".svn",
        ".idea", ".vscode",
        "site-packages"
    )

    /**
     * Key fragments that indicate a value should be redacted.
     * Matches are case-insensitive against the full key name.
     */
    private val SENSITIVE_KEY_PATTERNS = setOf(
        "SECRET_KEY", "PASSWORD", "API_KEY", "TOKEN",
        "DATABASE_URL", "PRIVATE_KEY", "AWS_SECRET",
        "REDIS_URL", "BROKER_URL", "CELERY_BROKER",
        "AUTH_TOKEN", "ACCESS_KEY", "SIGNING_KEY"
    )

    /**
     * Returns true if the given directory should be scanned for Python source files.
     * Excludes virtual environments, caches, build artifacts, and hidden directories.
     */
    fun shouldScanDir(dir: File): Boolean =
        dir.name !in EXCLUDED_DIRS &&
            !dir.name.endsWith(".egg-info") &&
            !(dir.name.startsWith(".") && dir.name.length > 1)

    /**
     * Walks a project base directory and returns all Python files matching [fileFilter],
     * excluding directories in [EXCLUDED_DIRS] and hidden directories.
     *
     * @param baseDir the project root directory
     * @param fileFilter predicate to further filter files (e.g., by name or extension)
     * @return list of matching files
     */
    fun scanPythonFiles(baseDir: File, fileFilter: (File) -> Boolean): List<File> =
        baseDir.walkTopDown()
            .onEnter { shouldScanDir(it) }
            .filter { it.isFile && fileFilter(it) }
            .toList()

    /**
     * Scans for all .py files in the project, excluding venv and cache directories.
     */
    fun scanAllPyFiles(baseDir: File): List<File> =
        scanPythonFiles(baseDir) { it.extension == "py" }

    /**
     * Redacts the value if the key matches any sensitive key pattern.
     * Uses case-insensitive matching, so `database_url` and `DATABASE_URL` are both redacted.
     *
     * @param key the configuration key name
     * @param value the raw configuration value
     * @return "***REDACTED***" if the key is sensitive, otherwise the original value
     */
    /**
     * Composes a URL prefix and route path, normalizing slashes.
     * E.g., composePath("/api/v1", "/users") → "/api/v1/users"
     */
    fun composePath(prefix: String, routePath: String): String {
        if (prefix.isEmpty()) return routePath
        val normalizedPrefix = prefix.trimEnd('/')
        val normalizedRoute = if (routePath.startsWith("/")) routePath else "/$routePath"
        return "$normalizedPrefix$normalizedRoute"
    }

    fun redactIfSensitive(key: String, value: String): String {
        val upperKey = key.uppercase()
        return if (SENSITIVE_KEY_PATTERNS.any { upperKey.contains(it) }) {
            "***REDACTED***"
        } else {
            value
        }
    }

    /**
     * Finds the end of a Python class body by locating the next top-level class definition.
     * Uses a simple heuristic — does not handle nested classes.
     */
    fun findClassEnd(content: String, classStart: Int): Int {
        return NEXT_CLASS_REGEX.find(content, classStart + 1)?.range?.first ?: content.length
    }

    /**
     * Computes a relative path from [basePath] to [file].
     */
    fun relPath(file: File, basePath: String): String =
        file.absolutePath.removePrefix(basePath).trimStart(File.separatorChar)

    /**
     * Scans for template files under /templates/ directories and formats them grouped by directory.
     * Shared between Django and Flask template actions.
     */
    fun scanAndFormatTemplates(
        baseDir: File,
        basePath: String,
        extensions: Set<String>,
        headerLabel: String,
        filter: String?,
    ): ToolResult {
        val templateFiles = scanPythonFiles(baseDir) { file ->
            file.extension in extensions && file.absolutePath.contains("/templates/")
        }

        if (templateFiles.isEmpty()) {
            return ToolResult(
                "No template files found in templates/ directories.",
                "No templates found",
                5
            )
        }

        val filtered = if (filter != null) {
            templateFiles.filter { it.absolutePath.contains(filter, ignoreCase = true) }
        } else {
            templateFiles
        }

        if (filtered.isEmpty()) {
            val filterDesc = if (filter != null) " matching '$filter'" else ""
            return ToolResult("No templates found$filterDesc.", "No templates", 5)
        }

        val content = buildString {
            appendLine("$headerLabel (${filtered.size} total):")
            appendLine()
            val byDir = filtered.groupBy { it.parentFile?.absolutePath ?: "" }
            for ((dir, files) in byDir.toSortedMap()) {
                val relDir = dir.removePrefix(basePath).trimStart(File.separatorChar)
                appendLine("[$relDir]")
                for (tmpl in files.sortedBy { it.name }) {
                    appendLine("  ${tmpl.name}")
                }
                appendLine()
            }
        }

        return ToolResult(
            content = content.trimEnd(),
            summary = "${filtered.size} templates",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }
}
