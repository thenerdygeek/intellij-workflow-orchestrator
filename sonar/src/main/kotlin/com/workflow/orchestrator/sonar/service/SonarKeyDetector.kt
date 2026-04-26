package com.workflow.orchestrator.sonar.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.autodetect.sonar.ScannerWorkReader
import com.workflow.orchestrator.core.autodetect.sonar.SonarPropertiesReader
import com.workflow.orchestrator.core.services.SonarKeyDetectorService
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.nio.file.Paths

/**
 * Detects the SonarQube project key using a four-tier waterfall:
 *
 *   Tier 0   — `.scannerwork/report-task.txt` (written by any scanner after a successful run).
 *   Tier 1   — `sonar-project.properties` (CLI-scanner repos: Python, JS/TS, Go, polyglot).
 *   Tier 2   — Maven model: walks `mavenManager.projects` (NOT rootProjects) so that
 *               per-submodule `<sonar.projectKey>` overrides are visible.
 *   Tier 2.5 — Gradle model: reads [ExternalProjectDataCache] populated during Gradle sync.
 *               Covers both Kotlin DSL and Groovy DSL projects with or without explicit
 *               `sonar.projectKey` set. No Tooling API roundtrip at detection time.
 *
 * First non-null result wins. Returns null if all tiers produce null.
 *
 * Two methods:
 *   - `detect()`: legacy single-project case — tiers 0/1 against `project.basePath`,
 *     then tier 2 against the first Maven root, then tier 2.5 against `project.basePath`.
 *   - `detectForPath(repoRootPath)`: multi-repo case — tiers 0/1 against the given path,
 *     then tier 2 by scanning all `mavenManager.projects` for a directory match,
 *     then tier 2.5 via [GradleSonarKeyDetector].
 *
 * Implements [SonarKeyDetectorService] so that [com.workflow.orchestrator.core.autodetect.AutoDetectOrchestrator]
 * can call it from :core without a compile-time dependency on :sonar.
 */
@Service(Service.Level.PROJECT)
class SonarKeyDetector(private val project: Project) : SonarKeyDetectorService {

    private val log = logger<SonarKeyDetector>()

    override fun detect(): String? {
        val basePath = project.basePath
        if (basePath != null) {
            val baseDir = Paths.get(basePath)

            // Tier 0 — scanner-work report-task.txt
            ScannerWorkReader.readProjectKey(baseDir)?.let { return it }

            // Tier 1 — sonar-project.properties
            SonarPropertiesReader.readProjectKey(baseDir)?.primaryKey?.let { return it }
        }

        // Tier 2 — Maven model (first root project, legacy behaviour)
        val root = firstMavenRoot()
        if (root != null) {
            extractKey(root)?.let { return it }
        }

        // Tier 2.5 — Gradle model
        val gradlePath = basePath ?: return null
        GradleSonarKeyDetector.detect(project, gradlePath)?.let { return it }

        log.debug("[Sonar:Detect] No tier matched project basePath: $basePath")
        return null
    }

    override fun detectForPath(repoRootPath: String): String? {
        return try {
            val targetPath = Paths.get(repoRootPath).toAbsolutePath().normalize()

            // Tier 0 — scanner-work report-task.txt
            ScannerWorkReader.readProjectKey(targetPath)?.let { return it }

            // Tier 1 — sonar-project.properties
            SonarPropertiesReader.readProjectKey(targetPath)?.primaryKey?.let { return it }

            // Tier 2 — Maven model: walk all projects (not just rootProjects) so submodule
            // overrides are visible. mavenProject.directory is still correct for platform 2025.1;
            // directoryPath (Path) replaces it from 2026.1.
            val mavenManager = MavenProjectsManager.getInstance(project)
            if (mavenManager.isMavenizedProject) {
                val matching = mavenManager.projects.firstOrNull { mavenProject ->
                    try {
                        Paths.get(mavenProject.directory).toAbsolutePath().normalize() == targetPath
                    } catch (_: Exception) { false }
                }
                if (matching != null) {
                    extractKey(matching)?.let { return it }
                } else {
                    log.debug("[Sonar:Detect] No Maven project matches repo path: $repoRootPath")
                }
            }

            // Tier 2.5 — Gradle model
            GradleSonarKeyDetector.detect(project, repoRootPath)?.let { return it }

            log.debug("[Sonar:Detect] No tier matched repo path: $repoRootPath")
            null
        } catch (e: Exception) {
            log.warn("[Sonar:Detect] detectForPath failed for $repoRootPath", e)
            null
        }
    }

    private fun firstMavenRoot(): MavenProject? {
        return try {
            val mavenManager = MavenProjectsManager.getInstance(project)
            if (!mavenManager.isMavenizedProject) {
                log.debug("[Sonar:Detect] Project is not mavenized")
                null
            } else mavenManager.rootProjects.firstOrNull()
                ?: run { log.debug("[Sonar:Detect] No root Maven projects"); null }
        } catch (e: Exception) {
            log.warn("[Sonar:Detect] firstMavenRoot failed", e); null
        }
    }

    private fun extractKey(mavenProject: MavenProject): String? {
        return try {
            val explicit = mavenProject.properties?.getProperty("sonar.projectKey")
            if (!explicit.isNullOrBlank()) {
                log.info("[Sonar:Detect] Found explicit sonar.projectKey: $explicit")
                return explicit
            }
            val mavenId = mavenProject.mavenId
            val synthesized = "${mavenId.groupId}:${mavenId.artifactId}"
            log.info("[Sonar:Detect] Synthesized sonar key from coordinates: $synthesized")
            synthesized
        } catch (e: Exception) {
            log.warn("[Sonar:Detect] extractKey failed", e); null
        }
    }
}
