package com.workflow.orchestrator.sonar.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.nio.file.Paths

/**
 * Detects the SonarQube project key from the IDE's resolved Maven model.
 *
 * Priority:
 *   1. Explicit `<sonar.projectKey>` property in the root pom.xml.
 *      (Maven model already resolves placeholders like `${project.artifactId}`.)
 *   2. Synthesized `groupId:artifactId` from the root project's MavenId.
 *
 * Returns null if the project is not mavenized or no Maven root matches.
 *
 * Two methods:
 *   - `detect()`: legacy single-project case, uses the first Maven root.
 *   - `detectForPath(repoRootPath)`: multi-repo case, finds the Maven root
 *     whose directory matches the given repo path.
 */
@Service(Service.Level.PROJECT)
class SonarKeyDetector(private val project: Project) {

    private val log = logger<SonarKeyDetector>()

    fun detect(): String? {
        val root = firstMavenRoot() ?: return null
        return extractKey(root)
    }

    fun detectForPath(repoRootPath: String): String? {
        return try {
            val mavenManager = MavenProjectsManager.getInstance(project)
            if (!mavenManager.isMavenizedProject) return null
            val targetPath = Paths.get(repoRootPath).toAbsolutePath().normalize()
            val matching = mavenManager.rootProjects.firstOrNull { mavenProject ->
                try {
                    Paths.get(mavenProject.directory).toAbsolutePath().normalize() == targetPath
                } catch (_: Exception) { false }
            } ?: run {
                log.debug("[Sonar:Detect] No Maven root matches repo path: $repoRootPath")
                return null
            }
            extractKey(matching)
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
