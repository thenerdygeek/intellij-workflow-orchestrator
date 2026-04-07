package com.workflow.orchestrator.core.copyright

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.workflow.orchestrator.core.settings.PluginSettings

class CopyrightCheckService(private val project: Project) {

    fun checkFiles(files: List<VirtualFile>): CopyrightCheckResult {
        val settings = PluginSettings.getInstance(project).state
        val pattern = settings.copyrightHeaderPattern
        if (pattern.isNullOrBlank()) return CopyrightCheckResult(emptyList())

        val regex = try {
            Regex(pattern)
        } catch (_: java.util.regex.PatternSyntaxException) {
            return CopyrightCheckResult(emptyList())
        }
        val violations = mutableListOf<CopyrightViolation>()

        for (file in files) {
            if (!isSourceFile(file)) continue
            val document = FileDocumentManager.getInstance().getDocument(file) ?: continue
            val content = document.text
            val headerLines = content.lines().take(10).joinToString("\n")
            if (!regex.containsMatchIn(headerLines)) {
                violations.add(CopyrightViolation(file))
            }
        }

        return CopyrightCheckResult(violations)
    }

    private fun isSourceFile(file: VirtualFile): Boolean {
        val fileType = FileTypeRegistry.getInstance().getFileTypeByFile(file)
        if (fileType.isBinary) return false
        val fileIndex = ProjectFileIndex.getInstance(project)
        return fileIndex.isInSourceContent(file) && !fileIndex.isInGeneratedSources(file)
    }
}

data class CopyrightCheckResult(val violations: List<CopyrightViolation>) {
    val passed: Boolean get() = violations.isEmpty()
}

data class CopyrightViolation(val file: VirtualFile)
