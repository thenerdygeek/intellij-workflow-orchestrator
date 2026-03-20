package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.diagnostic.Logger
import java.io.File

class SkillRegistry(private val projectBasePath: String?, private val userHome: String) {

    companion object {
        private val LOG = Logger.getInstance(SkillRegistry::class.java)
    }

    data class SkillEntry(
        val name: String,
        val description: String,
        val disableModelInvocation: Boolean = false,
        val userInvocable: Boolean = true,
        val preferredTools: List<String> = emptyList(),
        val filePath: String,
        val scope: SkillScope
    )

    enum class SkillScope { PROJECT, USER }

    private val skills = mutableMapOf<String, SkillEntry>()

    fun scan(): List<SkillEntry> {
        skills.clear()

        // Scan user directory first (lower priority)
        scanDirectory(File(userHome, ".workflow-orchestrator/skills"), SkillScope.USER)

        // Scan project directory second (higher priority — overwrites user skills with same name)
        if (projectBasePath != null) {
            scanDirectory(File(projectBasePath, ".workflow/skills"), SkillScope.PROJECT)
        }

        return skills.values.sortedBy { it.name }
    }

    fun getSkill(name: String): SkillEntry? = skills[name]

    fun getSkillContent(name: String): String? {
        val entry = skills[name] ?: return null
        return try {
            val content = File(entry.filePath).readText()
            extractBody(content)
        } catch (e: Exception) {
            null
        }
    }

    fun getUserInvocableSkills(): List<SkillEntry> =
        skills.values.filter { it.userInvocable }.sortedBy { it.name }

    fun getAutoDiscoverableSkills(): List<SkillEntry> =
        skills.values.filter { !it.disableModelInvocation }.sortedBy { it.name }

    fun buildDescriptionIndex(): String {
        val sorted = skills.values.sortedBy { it.name }
        if (sorted.isEmpty()) return "No skills available."
        return "Available skills:\n" + sorted.joinToString("\n") { "- /${it.name} — ${it.description}" }
    }

    private fun scanDirectory(dir: File, scope: SkillScope) {
        try {
            if (!dir.isDirectory) return
            val skillDirs = dir.listFiles() ?: return
            for (skillDir in skillDirs) {
                if (!skillDir.isDirectory) continue
                val skillFile = File(skillDir, "SKILL.md")
                if (!skillFile.isFile) continue
                // Validate skill file is within the skills directory (prevent symlink escape)
                if (!skillFile.canonicalPath.startsWith(dir.canonicalPath)) {
                    LOG.warn("SkillRegistry: path traversal blocked for skill at ${skillFile.path}")
                    continue
                }
                try {
                    val content = skillFile.readText()
                    val frontmatter = parseFrontmatter(content) ?: continue
                    val name = frontmatter["name"] ?: skillDir.name
                    val description = frontmatter["description"]?.trim()
                    if (description.isNullOrBlank()) {
                        LOG.warn("SkillRegistry: skipping skill at ${skillFile.path} — missing 'description' field")
                        continue
                    }
                    val entry = SkillEntry(
                        name = name,
                        description = description,
                        disableModelInvocation = frontmatter["disable-model-invocation"]?.toBooleanStrictOrNull() ?: false,
                        userInvocable = frontmatter["user-invocable"]?.toBooleanStrictOrNull() ?: true,
                        preferredTools = parseList(frontmatter["preferred-tools"] ?: ""),
                        filePath = skillFile.absolutePath,
                        scope = scope
                    )
                    skills[name] = entry
                } catch (e: Exception) {
                    // Skip malformed skill files
                }
            }
        } catch (e: Exception) {
            // Directory not readable — skip
        }
    }

    private fun parseFrontmatter(content: String): Map<String, String>? {
        if (!content.trimStart().startsWith("---")) return null
        val end = content.indexOf("---", 3)
        if (end < 0) return null
        val yaml = content.substring(3, end).trim()
        val map = mutableMapOf<String, String>()
        for (line in yaml.lines()) {
            val sep = line.indexOf(':')
            if (sep > 0) {
                val key = line.substring(0, sep).trim()
                val value = line.substring(sep + 1).trim()
                map[key] = value
            }
        }
        return map
    }

    private fun parseList(value: String): List<String> {
        return value.removeSurrounding("[", "]").split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun extractBody(content: String): String {
        val secondDashes = content.indexOf("---", 3)
        return if (secondDashes >= 0) content.substring(secondDashes + 3).trim() else content
    }
}
