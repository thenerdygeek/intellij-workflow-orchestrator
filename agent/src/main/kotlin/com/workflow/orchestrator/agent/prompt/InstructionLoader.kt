package com.workflow.orchestrator.agent.prompt

import com.intellij.openapi.diagnostic.Logger
import java.io.File

/**
 * Loads project instructions and bundled skills.
 *
 * Project instructions: reads CLAUDE.md or .agent-rules from project root.
 * Bundled skills: reads SKILL.md files from classpath resources under /skills/.
 *
 * Skill files use YAML frontmatter for metadata:
 * ```
 * ---
 * name: skill-name
 * description: What the skill does
 * preferred-tools: [tool1, tool2]
 * ---
 * # Skill Content
 * ...
 * ```
 *
 * Ported from Cline's skill loading pattern in skills.ts:
 * - Skills are discovered at startup and listed in the system prompt
 * - The use_skill tool loads the full skill content on demand
 * - Skill content survives compaction via re-injection
 */
object InstructionLoader {

    private val LOG = Logger.getInstance(InstructionLoader::class.java)

    /** Well-known instruction file names, checked in priority order. */
    private val INSTRUCTION_FILES = listOf("CLAUDE.md", ".agent-rules", ".claude-rules")

    /** Max description length (Cline enforces 1024 chars). */
    private const val MAX_DESCRIPTION_LENGTH = 1024

    /**
     * Known bundled skill directory names.
     * These correspond to directories under resources/skills/ containing SKILL.md files.
     */
    private val BUNDLED_SKILL_DIRS = listOf(
        "brainstorm",
        "create-skill",
        "git-workflow",
        "interactive-debugging",
        "subagent-driven",
        "systematic-debugging",
        "tdd",
        "writing-plans"
    )

    /**
     * Load project instructions from CLAUDE.md or .agent-rules in project root.
     *
     * @param projectPath absolute path to the project root
     * @return the instruction file content, or null if not found
     */
    fun loadProjectInstructions(projectPath: String): String? {
        for (fileName in INSTRUCTION_FILES) {
            val file = File(projectPath, fileName)
            if (file.isFile && file.canRead()) {
                return try {
                    val content = file.readText(Charsets.UTF_8)
                    if (content.isBlank()) null else content
                } catch (e: Exception) {
                    LOG.warn("InstructionLoader: failed to read $fileName: ${e.message}")
                    null
                }
            }
        }
        return null
    }

    /**
     * Load all bundled skill definitions from classpath resources.
     *
     * Each skill is a directory under /skills/ containing a SKILL.md file
     * with YAML frontmatter (name, description) and markdown content.
     *
     * @return list of skill definitions with name, description, and full content
     */
    fun loadBundledSkills(): List<SkillDefinition> {
        val skills = mutableListOf<SkillDefinition>()

        for (dirName in BUNDLED_SKILL_DIRS) {
            val resourcePath = "/skills/$dirName/SKILL.md"
            try {
                val content = loadClasspathResource(resourcePath) ?: continue
                val (frontmatter, body) = parseYamlFrontmatter(content)

                val name = frontmatter["name"]
                val description = frontmatter["description"] ?: ""

                // Validate: name must exist and match directory name (Cline requirement)
                if (name.isNullOrBlank()) {
                    LOG.warn("InstructionLoader: bundled skill '$dirName' missing 'name' field")
                    continue
                }
                if (name != dirName) {
                    LOG.warn("InstructionLoader: bundled skill name '$name' doesn't match directory '$dirName'")
                    continue
                }

                // Cline enforces max 1024 char descriptions
                val trimmedDescription = if (description.length > MAX_DESCRIPTION_LENGTH) {
                    LOG.warn("InstructionLoader: skill '$name' description truncated to $MAX_DESCRIPTION_LENGTH chars")
                    description.take(MAX_DESCRIPTION_LENGTH)
                } else description

                skills.add(SkillDefinition(
                    name = name,
                    description = trimmedDescription,
                    content = body.ifBlank { content }
                ))
            } catch (e: Exception) {
                LOG.warn("InstructionLoader: failed to load skill '$dirName': ${e.message}")
            }
        }

        return skills
    }

    /**
     * Load user-created skill definitions from project-local and global directories.
     *
     * Faithful port of Cline's skill discovery from:
     * src/core/context/instructions/user-instructions/skills.ts
     *
     * Cline scans these directories (via getSkillsDirectoriesForScan):
     * - {cwd}/.clinerules/skills/  (project, Cline-specific)
     * - {cwd}/.cline/skills/       (project, Cline-specific)
     * - {cwd}/.claude/skills/      (project, Claude-specific)
     * - {cwd}/.agents/skills/      (project, generic)
     * - ~/.cline/skills/           (global, Cline-specific)
     * - ~/.agents/skills/          (global, generic)
     *
     * Our adaptation scans:
     * - {projectPath}/.agent-skills/     — project-local skills
     * - ~/.workflow-orchestrator/skills/  — global user skills
     *
     * Each skill directory must contain a SKILL.md file with YAML frontmatter
     * (name, description). Directory name must match the name field.
     *
     * Global skills take precedence over project skills with the same name
     * (matching Cline's getAvailableSkills resolution order).
     *
     * @param projectPath absolute path to the project root
     * @return list of discovered user skill definitions
     */
    fun loadUserSkills(projectPath: String): List<SkillDefinition> {
        val skills = mutableListOf<SkillDefinition>()

        // Scan project-local skills first (lower precedence)
        val projectSkillsDir = File(projectPath, ".agent-skills")
        scanSkillsDirectory(projectSkillsDir, skills)

        // Scan global user skills (higher precedence — overrides project skills)
        val globalSkillsDir = File(System.getProperty("user.home"), ".workflow-orchestrator/skills")
        scanSkillsDirectory(globalSkillsDir, skills)

        // Deduplicate: global skills override project skills with the same name
        // (ported from Cline's getAvailableSkills: iterate backwards, global last = highest precedence)
        val seen = mutableSetOf<String>()
        val deduped = mutableListOf<SkillDefinition>()
        for (skill in skills.reversed()) {
            if (skill.name !in seen) {
                seen.add(skill.name)
                deduped.add(0, skill)
            }
        }

        return deduped
    }

    /**
     * Load all skills: bundled + user-created (project-local + global).
     *
     * Merges bundled skills with user-discovered skills. User skills take
     * precedence over bundled skills with the same name.
     *
     * @param projectPath absolute path to the project root
     * @return merged list of all available skill definitions
     */
    fun loadAllSkills(projectPath: String): List<SkillDefinition> {
        val bundled = loadBundledSkills()
        val user = loadUserSkills(projectPath)

        // Merge: user skills override bundled with the same name
        val byName = LinkedHashMap<String, SkillDefinition>()
        for (skill in bundled) {
            byName[skill.name] = skill
        }
        for (skill in user) {
            byName[skill.name] = skill
        }
        return byName.values.toList()
    }

    /**
     * Scan a directory for skill subdirectories containing SKILL.md files.
     *
     * Ported from Cline's scanSkillsDirectory:
     * - Lists subdirectories in the given directory
     * - For each subdirectory, looks for SKILL.md
     * - Parses YAML frontmatter for name and description
     * - Validates that frontmatter name matches directory name (Cline requirement)
     *
     * @param dir the directory to scan
     * @param skills mutable list to add discovered skills to
     */
    private fun scanSkillsDirectory(dir: File, skills: MutableList<SkillDefinition>) {
        if (!dir.isDirectory) return

        try {
            val entries = dir.listFiles() ?: return
            for (entry in entries) {
                if (!entry.isDirectory) continue

                val skillMd = File(entry, "SKILL.md")
                if (!skillMd.isFile || !skillMd.canRead()) continue

                try {
                    val content = skillMd.readText(Charsets.UTF_8)
                    val (frontmatter, body) = parseYamlFrontmatter(content)

                    val name = frontmatter["name"]
                    val description = frontmatter["description"]

                    // Cline validates: name must exist and match directory name
                    if (name.isNullOrBlank()) {
                        LOG.warn("InstructionLoader: skill at ${entry.path} missing 'name' field")
                        continue
                    }
                    if (description.isNullOrBlank()) {
                        LOG.warn("InstructionLoader: skill at ${entry.path} missing 'description' field")
                        continue
                    }
                    if (name != entry.name) {
                        LOG.warn("InstructionLoader: skill name '$name' doesn't match directory '${entry.name}'")
                        continue
                    }

                    // Cline enforces max 1024 char descriptions
                    val trimmedDescription = if (description.length > MAX_DESCRIPTION_LENGTH) {
                        LOG.warn("InstructionLoader: skill '$name' description truncated to $MAX_DESCRIPTION_LENGTH chars")
                        description.take(MAX_DESCRIPTION_LENGTH)
                    } else description

                    skills.add(SkillDefinition(
                        name = name,
                        description = trimmedDescription,
                        content = body.ifBlank { content }
                    ))
                } catch (e: Exception) {
                    LOG.warn("InstructionLoader: failed to load skill at ${entry.path}: ${e.message}")
                }
            }
        } catch (e: SecurityException) {
            LOG.warn("InstructionLoader: permission denied reading skills directory: ${dir.path}")
        }
    }

    /**
     * Load the full content of a specific skill by name.
     *
     * Searches bundled skills for a matching name and returns the full
     * skill content (body after YAML frontmatter).
     *
     * @param skillName the exact skill name to load
     * @return the skill content, or null if not found
     */
    fun loadSkillContent(skillName: String): String? {
        // First try direct directory name match
        val directPath = "/skills/$skillName/SKILL.md"
        val directContent = loadClasspathResource(directPath)
        if (directContent != null) {
            val (_, body) = parseYamlFrontmatter(directContent)
            return body.ifBlank { directContent }
        }

        // Fall back to searching all skills by frontmatter name
        for (dirName in BUNDLED_SKILL_DIRS) {
            val resourcePath = "/skills/$dirName/SKILL.md"
            try {
                val content = loadClasspathResource(resourcePath) ?: continue
                val (frontmatter, body) = parseYamlFrontmatter(content)
                if (frontmatter["name"] == skillName) {
                    return body.ifBlank { content }
                }
            } catch (_: Exception) {
                // Skip unreadable skills
            }
        }

        return null
    }

    /**
     * Parse YAML frontmatter from a skill file.
     *
     * Frontmatter is delimited by `---` on its own line at the start of the file.
     * Returns a pair of (frontmatter map, body content after frontmatter).
     *
     * Supported frontmatter fields:
     * - name: skill identifier
     * - description: human-readable description
     * - user-invocable: whether users can trigger this directly
     * - preferred-tools: list of preferred tool names
     *
     * @param content the full file content
     * @return pair of (key-value map from frontmatter, body after frontmatter)
     */
    internal fun parseYamlFrontmatter(content: String): Pair<Map<String, String>, String> {
        val trimmed = content.trimStart()
        if (!trimmed.startsWith("---")) {
            return Pair(emptyMap(), content)
        }

        // Find the closing ---
        val endIndex = trimmed.indexOf("---", startIndex = 3)
        if (endIndex < 0) {
            return Pair(emptyMap(), content)
        }

        val frontmatterBlock = trimmed.substring(3, endIndex).trim()
        val body = trimmed.substring(endIndex + 3).trimStart()

        // Simple YAML key-value parsing (no nested structures)
        val map = mutableMapOf<String, String>()
        for (line in frontmatterBlock.lines()) {
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                val key = line.substring(0, colonIdx).trim()
                val value = line.substring(colonIdx + 1).trim()
                map[key] = value
            }
        }

        return Pair(map, body)
    }

    /**
     * Load a resource from the classpath.
     */
    private fun loadClasspathResource(path: String): String? {
        return try {
            InstructionLoader::class.java.getResourceAsStream(path)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
        } catch (e: Exception) {
            LOG.debug("InstructionLoader: classpath resource not found: $path")
            null
        }
    }
}

/**
 * A bundled skill definition loaded from resources.
 *
 * @param name unique skill identifier (used in use_skill tool)
 * @param description human-readable description (shown in system prompt skill list)
 * @param content full skill instructions (injected when skill is activated)
 */
data class SkillDefinition(
    val name: String,
    val description: String,
    val content: String
)
