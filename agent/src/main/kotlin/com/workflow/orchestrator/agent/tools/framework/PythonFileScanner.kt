package com.workflow.orchestrator.agent.tools.framework

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
        "dist", "build", ".eggs", "*.egg-info",
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
    fun redactIfSensitive(key: String, value: String): String {
        val upperKey = key.uppercase()
        return if (SENSITIVE_KEY_PATTERNS.any { upperKey.contains(it) }) {
            "***REDACTED***"
        } else {
            value
        }
    }
}
