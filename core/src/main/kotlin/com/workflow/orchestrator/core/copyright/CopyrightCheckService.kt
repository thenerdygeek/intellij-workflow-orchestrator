package com.workflow.orchestrator.core.copyright

import com.intellij.openapi.project.Project
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
            return CopyrightCheckResult(emptyList()) // Invalid pattern — skip check
        }
        val violations = mutableListOf<CopyrightViolation>()

        for (file in files) {
            if (!isSourceFile(file)) continue
            val content = String(file.contentsToByteArray())
            val headerLines = content.lines().take(10).joinToString("\n")
            if (!regex.containsMatchIn(headerLines)) {
                violations.add(CopyrightViolation(file, "Missing copyright header"))
            }
        }

        return CopyrightCheckResult(violations)
    }

    private fun isSourceFile(file: VirtualFile): Boolean {
        val ext = file.extension ?: return false
        return ext in setOf("java", "kt", "kts", "xml", "yaml", "yml", "properties")
    }
}

data class CopyrightCheckResult(val violations: List<CopyrightViolation>) {
    val passed: Boolean get() = violations.isEmpty()
}

data class CopyrightViolation(
    val file: VirtualFile,
    val reason: String
)
