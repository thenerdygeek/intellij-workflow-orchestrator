package com.workflow.orchestrator.core.maven

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

class MavenModuleDetector(private val project: Project) {

    fun detectChangedModules(changedFiles: List<VirtualFile>): List<String> {
        val mavenManager = getMavenManager()
        if (mavenManager != null && mavenManager.projects.isNotEmpty()) {
            return detectViaMavenApi(changedFiles, mavenManager)
        }
        return detectViaFallback(changedFiles)
    }

    /**
     * Primary path: uses MavenProjectsManager which provides the full Maven model.
     * Maps each changed file to its owning Maven module via directory ancestry.
     */
    private fun detectViaMavenApi(
        changedFiles: List<VirtualFile>,
        mavenManager: org.jetbrains.idea.maven.project.MavenProjectsManager
    ): List<String> {
        val modules = mutableSetOf<String>()
        val mavenProjects = mavenManager.projects
        for (file in changedFiles) {
            val owningProject = mavenProjects.find { mavenProject ->
                VfsUtilCore.isAncestor(mavenProject.directoryFile, file, false)
            }
            if (owningProject != null) {
                owningProject.mavenId.artifactId?.let { modules.add(it) }
            }
        }
        return modules.toList()
    }

    /**
     * Fallback path: used when Maven plugin is not available or projects haven't imported yet.
     */
    private fun detectViaFallback(changedFiles: List<VirtualFile>): List<String> {
        val modules = mutableSetOf<String>()
        for (file in changedFiles) {
            val pomFile = findNearestPomFallback(file) ?: continue
            val artifactId = extractArtifactIdFallback(pomFile)
            if (artifactId != null) {
                modules.add(artifactId)
            }
        }
        return modules.toList()
    }

    fun buildMavenArgs(modules: List<String>, goals: String): List<String> {
        val goalList = goals.trim().split("\\s+".toRegex())
        if (modules.isEmpty()) {
            return goalList
        }
        return listOf("-pl", modules.joinToString(","), "-am") + goalList
    }

    private fun getMavenManager(): org.jetbrains.idea.maven.project.MavenProjectsManager? {
        return try {
            org.jetbrains.idea.maven.project.MavenProjectsManager.getInstance(project)
                .takeIf { it.isMavenizedProject }
        } catch (_: Exception) {
            null // Maven plugin not available
        }
    }

    // --- Fallback methods (preserved from original implementation) ---

    internal fun findNearestPomFallback(file: VirtualFile): VirtualFile? {
        var dir = file.parent
        while (dir != null) {
            val pom = dir.findChild("pom.xml")
            if (pom != null) return pom
            dir = dir.parent
        }
        return null
    }

    internal fun extractArtifactIdFallback(pomFile: VirtualFile): String? {
        val content = String(pomFile.contentsToByteArray())
        val lines = content.lines()
        var inParent = false
        var inDependencies = false
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("<parent>") || trimmed.startsWith("<parent ")) inParent = true
            if (trimmed.startsWith("</parent>")) inParent = false
            if (trimmed.startsWith("<dependencies>") || trimmed.startsWith("<dependencies ")) inDependencies = true
            if (trimmed.startsWith("</dependencies>")) inDependencies = false
            if (!inParent && !inDependencies) {
                val match = ARTIFACT_ID_PATTERN.find(trimmed)
                if (match != null) return match.groupValues[1]
            }
        }
        return null
    }

    companion object {
        private val ARTIFACT_ID_PATTERN = Regex("<artifactId>([^<]+)</artifactId>")
    }
}
