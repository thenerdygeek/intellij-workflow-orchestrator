package com.workflow.orchestrator.core.maven

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class MavenModuleDetector(private val project: Project) {

    fun detectChangedModules(changedFiles: List<VirtualFile>): List<String> {
        val modules = mutableSetOf<String>()
        for (file in changedFiles) {
            val pomFile = findNearestPom(file) ?: continue
            val artifactId = extractArtifactId(pomFile)
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
        // Always use -pl for targeted builds, even with a single module.
        // For single-module projects, detectChangedModules returns empty list (no pom.xml).
        return listOf("-pl", modules.joinToString(","), "-am") + goalList
    }

    internal fun findNearestPom(file: VirtualFile): VirtualFile? {
        var dir = file.parent
        while (dir != null) {
            val pom = dir.findChild("pom.xml")
            if (pom != null) return pom
            dir = dir.parent
        }
        return null
    }

    internal fun extractArtifactId(pomFile: VirtualFile): String? {
        val content = String(pomFile.contentsToByteArray())
        // Simple regex to find <artifactId> directly under <project> (not inside <parent> or <dependency>)
        // We look for the first <artifactId> that's NOT inside a <parent> block
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
