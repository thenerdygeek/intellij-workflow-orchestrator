package com.workflow.orchestrator.sonar.service

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.gradle.model.ExternalProject
import org.jetbrains.plugins.gradle.service.project.data.ExternalProjectDataCache
import org.jetbrains.plugins.gradle.settings.GradleSettings
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties

/**
 * Detects the SonarQube project key from the IntelliJ Gradle data model populated
 * during Gradle sync. Uses [ExternalProjectDataCache] which caches [ExternalProject]
 * data populated by `AbstractProjectDataService` implementations during sync —
 * no Tooling API roundtrip at detection time.
 *
 * Two-step detection per matched [ExternalProject]:
 *   1. Explicit: reads `sonar.projectKey` from the project's own `gradle.properties`
 *      file (set via `sonarqube { properties { property "sonar.projectKey", "..." } }`
 *      or Gradle's extra-properties DSL the scanner picks up).
 *   2. Synthesized: `[group:]name` for the root, `<rootKey><gradlePath>` for subprojects.
 *      This mirrors `SonarPropertyComputer.java` from sonar-scanner-gradle.
 *
 * Note: The Gradle tooling model's [GradleProperty] (stored in GradleExtensionsDataService)
 * exposes only the property's *type FQN* for IDE code-completion, not its runtime value.
 * Direct gradle.properties file reading is the correct path for value access without
 * triggering a Tooling API roundtrip.
 *
 * Returns null when:
 *   - No Gradle projects are linked (GradleSettings.linkedProjectsSettings is empty)
 *   - [ExternalProjectDataCache] holds no root project for any linked path
 *   - No linked Gradle project directory matches [repoRootPath]
 *   - The gradle.properties file is absent or does not contain sonar.projectKey
 *
 * Thread-safety: all reads are off-EDT (caller must dispatch to IO if needed);
 * this object is stateless.
 */
object GradleSonarKeyDetector {

    private val log = logger<GradleSonarKeyDetector>()

    /**
     * Internal seam for testing: production code uses [DefaultDataSource],
     * tests inject a [GradleSonarDataSource] fake.
     */
    interface GradleSonarDataSource {
        /**
         * Returns the linked Gradle project root paths registered for [project],
         * or an empty collection if no Gradle projects are linked.
         */
        fun linkedProjectPaths(project: Project): Collection<String>

        /**
         * Returns the root [ExternalProject] cached for [rootProjectPath], or null
         * if Gradle sync has not yet produced data for that path.
         */
        fun rootExternalProject(project: Project, rootProjectPath: String): ExternalProject?
    }

    /** Production implementation — delegates directly to the IDE services. Kept `internal` so tests can reset [dataSource] back to it after injection. */
    internal object DefaultGradleSonarDataSource : GradleSonarDataSource {
        override fun linkedProjectPaths(project: Project): Collection<String> =
            GradleSettings.getInstance(project).linkedProjectsSettings
                .map { it.externalProjectPath }

        override fun rootExternalProject(project: Project, rootProjectPath: String): ExternalProject? =
            ExternalProjectDataCache.getInstance(project).getRootExternalProject(rootProjectPath)
    }

    // Visible for tests; reset to DefaultGradleSonarDataSource in test teardown
    internal var dataSource: GradleSonarDataSource = DefaultGradleSonarDataSource

    // ──────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────

    fun detect(project: Project, repoRootPath: String): String? {
        return try {
            val linkedPaths = dataSource.linkedProjectPaths(project)
            if (linkedPaths.isEmpty()) {
                log.debug("[Sonar:Detect:T2.5] No linked Gradle projects")
                return null
            }

            val targetPath = Paths.get(repoRootPath).toAbsolutePath().normalize()

            // Walk each linked Gradle root; the root itself or one of its children
            // may match the target repo path.
            for (rootPath in linkedPaths) {
                val rootProject = dataSource.rootExternalProject(project, rootPath) ?: continue
                val key = findInTree(rootProject, rootProject, targetPath)
                if (key != null) return key
            }

            log.debug("[Sonar:Detect:T2.5] No Gradle project matched repo path: $repoRootPath")
            null
        } catch (e: LinkageError) {
            // The Gradle plugin (org.jetbrains.plugins.gradle) is NOT a declared dependency,
            // so its classes (GradleSettings, ExternalProject…) are unavailable at runtime
            // whenever that plugin isn't loaded — touching them throws NoClassDefFoundError,
            // a LinkageError (NOT an Exception). Degrade to null instead of crashing the
            // AutoDetect coroutine. To actually enable Gradle detection, declare an optional
            // dependency on org.jetbrains.plugins.gradle in plugin.xml.
            log.debug("[Sonar:Detect:T2.5] Gradle plugin classes unavailable; skipping Gradle tier: ${e.message}")
            null
        } catch (e: Exception) {
            log.warn("[Sonar:Detect:T2.5] Detection failed", e)
            null
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Tree traversal
    // ──────────────────────────────────────────────────────────────────

    /**
     * Depth-first search through [ExternalProject.childProjects].
     * [rootProject] is passed through to enable root-key synthesis for subprojects.
     */
    private fun findInTree(
        rootProject: ExternalProject,
        current: ExternalProject,
        targetPath: Path
    ): String? {
        val projectPath = try {
            current.projectDir.toPath().toAbsolutePath().normalize()
        } catch (_: Exception) {
            return null
        }

        if (projectPath == targetPath) {
            return resolveKey(rootProject, current)
        }

        for (child in current.childProjects.values) {
            val found = findInTree(rootProject, child, targetPath)
            if (found != null) return found
        }
        return null
    }

    // ──────────────────────────────────────────────────────────────────
    // Key resolution
    // ──────────────────────────────────────────────────────────────────

    private fun resolveKey(rootProject: ExternalProject, matched: ExternalProject): String {
        // Tier 2.5a — explicit sonar.projectKey in gradle.properties
        val explicitKey = readSonarKeyFromGradleProperties(matched)
        if (explicitKey != null) {
            log.info("[Sonar:Detect:T2.5] Explicit sonar.projectKey='$explicitKey' for ${matched.projectDir}")
            return explicitKey
        }

        // Tier 2.5b — synthesize per SonarPropertyComputer default
        return synthesizeKey(rootProject, matched)
    }

    /**
     * Reads `sonar.projectKey` from the `gradle.properties` file in the project's
     * own directory. This is where the Gradle sonar plugin reads it at scan time.
     * Returns null if the file is absent or the property is not set.
     */
    internal fun readSonarKeyFromGradleProperties(project: ExternalProject): String? {
        return try {
            val propsFile: Path = project.projectDir.toPath().resolve("gradle.properties")
            if (!Files.isRegularFile(propsFile)) return null
            val props = Properties()
            Files.newInputStream(propsFile).use { stream ->
                props.load(InputStreamReader(stream, StandardCharsets.UTF_8))
            }
            props.getProperty("sonar.projectKey")?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Synthesizes a Sonar key per the default rule used by sonar-scanner-gradle's
     * `SonarPropertyComputer.java`:
     *   - Root project: `[${group}:]${name}` (group prefix only when non-blank)
     *   - Subproject: `<rootKey><gradlePath>` where gradlePath starts with `:`
     *
     * Subproject identity is determined by comparing project directories; if the
     * matched project's directory equals the root project's directory it is the root.
     */
    internal fun synthesizeKey(rootProject: ExternalProject, matched: ExternalProject): String {
        val rootGroup = rootProject.group.takeIf { it.isNotBlank() }
        val rootName = rootProject.name.ifBlank { "project" }
        val rootKey = if (rootGroup != null) "$rootGroup:$rootName" else rootName

        val isRoot = try {
            matched.projectDir.canonicalFile == rootProject.projectDir.canonicalFile
        } catch (_: Exception) {
            matched.name == rootProject.name
        }

        val synthesized = if (isRoot) {
            rootKey
        } else {
            // ExternalProject.path is the Gradle path, e.g., ":sub:module"
            val gradlePath = matched.path.takeIf { it.isNotBlank() && it != ":" } ?: ":${matched.name}"
            "$rootKey$gradlePath"
        }
        log.info("[Sonar:Detect:T2.5] Synthesized key '$synthesized' for module '${matched.name}'")
        return synthesized
    }
}
