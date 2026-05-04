package com.workflow.orchestrator.core.util

import java.io.File

/**
 * Resolves Maven and Gradle executables for shell invocation. Centralizes the
 * Windows extension handling that several call sites need: Java's `ProcessBuilder`
 * uses `CreateProcess` on Windows, which auto-appends `.exe` for missing extensions
 * but does NOT consult `PATHEXT` for `.cmd` / `.bat`. So a bare `"mvn"` or `"gradle"`
 * literal is unrunnable on Windows even when Maven/Gradle is correctly installed —
 * the executables ship as `mvn.cmd` / `gradle.bat`. Same for the wrappers: the
 * Unix script is `mvnw` / `gradlew`, the Windows script is `mvnw.cmd` / `gradlew.bat`.
 *
 * Resolution preference (per build tool):
 *   1. Project-local wrapper script in [baseDir] (correct extension for current OS).
 *   2. PATH executable name (correct extension for current OS).
 *
 * The wrapper is preferred so the project's pinned Maven/Gradle version is used.
 * The wrapper path is returned absolute so `ProcessBuilder` does not depend on
 * the working directory's position in `PATH`.
 */
object BuildToolExecutableResolver {

    private val IS_WINDOWS = System.getProperty("os.name").lowercase().contains("win")

    /** Resolve the Maven executable to invoke from [baseDir]. */
    fun resolveMaven(baseDir: File): String = resolve(baseDir, mavenWrapperName(), mavenExecutableName())

    /** Resolve the Gradle executable to invoke from [baseDir]. */
    fun resolveGradle(baseDir: File): String = resolve(baseDir, gradleWrapperName(), gradleExecutableName())

    fun mavenWrapperName(): String = if (IS_WINDOWS) "mvnw.cmd" else "mvnw"
    fun mavenExecutableName(): String = if (IS_WINDOWS) "mvn.cmd" else "mvn"
    fun gradleWrapperName(): String = if (IS_WINDOWS) "gradlew.bat" else "gradlew"
    fun gradleExecutableName(): String = if (IS_WINDOWS) "gradle.bat" else "gradle"

    private fun resolve(baseDir: File, wrapperName: String, fallbackExecutable: String): String {
        val wrapper = File(baseDir, wrapperName)
        if (wrapper.exists() && wrapper.canExecute()) return wrapper.absolutePath
        return fallbackExecutable
    }
}
