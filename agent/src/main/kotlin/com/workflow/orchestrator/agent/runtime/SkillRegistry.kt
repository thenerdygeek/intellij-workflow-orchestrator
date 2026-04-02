package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.diagnostic.Logger
import java.io.File

class SkillRegistry(
    private val projectBasePath: String?,
    private val userHome: String,
    private val loadBuiltins: Boolean = true
) {

    companion object {
        private val LOG = Logger.getInstance(SkillRegistry::class.java)
    }

    data class SkillEntry(
        val name: String,
        val description: String,
        val disableModelInvocation: Boolean = false,
        val userInvocable: Boolean = true,
        val preferredTools: List<String> = emptyList(),
        val allowedTools: List<String>? = null,      // hard tool restriction (null = no restriction)
        val contextFork: Boolean = false,             // run in isolated subagent
        val agentType: String? = null,                // subagent type when context: fork
        val argumentHint: String? = null,             // autocomplete hint
        val filePath: String,
        val scope: SkillScope
    )

    enum class SkillScope { BUILTIN, USER, PROJECT }

    @Volatile
    private var skills: Map<String, SkillEntry> = emptyMap()

    fun scan(): List<SkillEntry> {
        val newSkills = mutableMapOf<String, SkillEntry>()

        // 1. Load built-in skills from plugin resources (lowest priority)
        if (loadBuiltins) loadBuiltinSkills(newSkills)

        // 2. Scan user directory (overwrites built-in with same name)
        scanDirectory(File(userHome, ".workflow-orchestrator/skills"), SkillScope.USER, newSkills)

        // 3. Scan project directory (highest priority — overwrites user + built-in)
        if (projectBasePath != null) {
            scanDirectory(File(projectBasePath, ".workflow/skills"), SkillScope.PROJECT, newSkills)
        }

        skills = newSkills  // atomic swap
        return skills.values.sortedBy { it.name }
    }

    /**
     * Load built-in skills bundled with the plugin from resources.
     * These ship with the plugin and provide default workflows.
     * Users can override them by creating a skill with the same name
     * in their project or user directory.
     */
    private fun loadBuiltinSkills(target: MutableMap<String, SkillEntry>) {
        val builtinSkillNames = listOf("systematic-debugging", "interactive-debugging", "create-skill", "git-workflow", "brainstorm", "planning")
        for (skillName in builtinSkillNames) {
            try {
                val resourcePath = "/skills/$skillName/SKILL.md"
                val content = javaClass.getResourceAsStream(resourcePath)?.bufferedReader()?.readText() ?: continue
                val frontmatter = parseFrontmatter(content) ?: continue
                val name = frontmatter["name"] ?: skillName
                val description = frontmatter["description"]?.trim()
                if (description.isNullOrBlank()) continue

                target[name] = SkillEntry(
                    name = name,
                    description = description,
                    disableModelInvocation = frontmatter["disable-model-invocation"]?.toBooleanStrictOrNull() ?: false,
                    userInvocable = frontmatter["user-invocable"]?.toBooleanStrictOrNull() ?: true,
                    preferredTools = parseList(frontmatter["preferred-tools"] ?: ""),
                    allowedTools = frontmatter["allowed-tools"]?.let { parseList(it) }?.takeIf { it.isNotEmpty() },
                    contextFork = frontmatter["context"]?.trim()?.equals("fork", ignoreCase = true) ?: false,
                    agentType = frontmatter["agent"]?.trim()?.takeIf { it.isNotBlank() },
                    argumentHint = frontmatter["argument-hint"]?.trim()?.takeIf { it.isNotBlank() },
                    filePath = "builtin:$resourcePath",
                    scope = SkillScope.BUILTIN
                )
            } catch (e: Exception) {
                LOG.warn("SkillRegistry: failed to load built-in skill '$skillName'", e)
            }
        }
    }

    fun getSkill(name: String): SkillEntry? = skills[name]

    fun getSkillContent(name: String): String? {
        val entry = skills[name] ?: return null
        return try {
            val content = if (entry.filePath.startsWith("builtin:")) {
                // Load from plugin resources
                javaClass.getResourceAsStream(entry.filePath.removePrefix("builtin:"))
                    ?.bufferedReader()?.readText() ?: return null
            } else {
                File(entry.filePath).readText()
            }
            extractBody(content)
        } catch (e: Exception) {
            null
        }
    }

    fun getUserInvocableSkills(): List<SkillEntry> =
        skills.values.filter { it.userInvocable }.sortedBy { it.name }

    fun getAutoDiscoverableSkills(): List<SkillEntry> =
        skills.values.filter { !it.disableModelInvocation }.sortedBy { it.name }

    /**
     * List supporting files in a skill's directory (excluding SKILL.md).
     * Returns relative paths from the skill directory.
     */
    fun getSupportingFiles(skillName: String): List<String> {
        val entry = skills[skillName] ?: return emptyList()
        if (entry.filePath.startsWith("builtin:")) return emptyList()
        val skillDir = File(entry.filePath).parentFile ?: return emptyList()
        if (!skillDir.isDirectory) return emptyList()
        return skillDir.walkTopDown()
            .filter { it.isFile && it.name != "SKILL.md" }
            .map { it.relativeTo(skillDir).path }
            .toList()
    }

    /**
     * Build a compact index of auto-discoverable skills for LLM context.
     * Enforces a description budget of 2% of the context window (max 16K chars).
     */
    fun buildDescriptionIndex(maxInputTokens: Int = 190_000): String {
        // Only show auto-discoverable skills (disable-model-invocation: false)
        // Skills with disable-model-invocation: true must be completely hidden from LLM context
        val discoverable = getAutoDiscoverableSkills()
        if (discoverable.isEmpty()) return "No skills available."

        // Budget: 2% of context window in chars (1 token ~ 4 chars), capped at 16K
        val budget = maxOf(1000, (maxInputTokens * 0.02 * 4).toInt().coerceAtMost(16_000))

        val sb = StringBuilder("Available skills:\n")
        var usedChars = sb.length
        var excluded = 0

        for (skill in discoverable) {
            val line = "- /${skill.name} — ${skill.description}\n"
            if (usedChars + line.length > budget) {
                excluded++
                continue
            }
            sb.append(line)
            usedChars += line.length
        }

        if (excluded > 0) {
            sb.append("\n($excluded skill(s) hidden due to description budget. Use /skill-name to invoke directly.)")
        }

        return sb.toString().trimEnd()
    }

    private fun scanDirectory(dir: File, scope: SkillScope, target: MutableMap<String, SkillEntry>) {
        try {
            if (!dir.isDirectory) return
            val skillDirs = dir.listFiles() ?: return
            for (skillDir in skillDirs) {
                if (!skillDir.isDirectory) continue
                val skillFile = File(skillDir, "SKILL.md")
                if (!skillFile.isFile) continue
                // Validate skill file is within the skills directory (prevent symlink escape)
                if (!skillFile.canonicalPath.startsWith(dir.canonicalPath + File.separator)) {
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
                        allowedTools = frontmatter["allowed-tools"]?.let { parseList(it) }?.takeIf { it.isNotEmpty() },
                        contextFork = frontmatter["context"]?.trim()?.equals("fork", ignoreCase = true) ?: false,
                        agentType = frontmatter["agent"]?.trim()?.takeIf { it.isNotBlank() },
                        argumentHint = frontmatter["argument-hint"]?.trim()?.takeIf { it.isNotBlank() },
                        filePath = skillFile.absolutePath,
                        scope = scope
                    )
                    target[name] = entry
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
                    .removeSurrounding("\"")
                    .removeSurrounding("'")
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
