package com.workflow.orchestrator.agent.prompt

import com.intellij.openapi.diagnostic.Logger
import java.io.File

/**
 * Loads project instructions and discovers skills.
 *
 * Project instructions: reads CLAUDE.md or .agent-rules from project root.
 * Skills: discovered from bundled resources, project-local, and global directories.
 *
 * Faithful port of Cline's skill loading pipeline:
 *   src/core/context/instructions/user-instructions/skills.ts
 *   src/core/context/instructions/user-instructions/frontmatter.ts
 *   src/core/storage/disk.ts (getSkillsDirectoriesForScan)
 *
 * Two-tier loading pattern (matching Cline):
 *   - discoverSkills() → metadata only (name, description, path, source) — cheap, at session start
 *   - getSkillContent() → full instructions — on demand when use_skill is called
 *
 * Adaptation: bundled skills from classpath resources (Cline has no bundled skills concept)
 */
object InstructionLoader {

    private val LOG = Logger.getInstance(InstructionLoader::class.java)

    /** Well-known instruction file names, checked in priority order. */
    private val INSTRUCTION_FILES = listOf("CLAUDE.md", ".agent-rules", ".claude-rules")

    /**
     * Known bundled skill directory names.
     * These correspond to directories under resources/skills/ containing SKILL.md files.
     * Adaptation: Cline has no bundled skills — these ship with our plugin.
     */
    private val BUNDLED_SKILL_DIRS = listOf(
        "using-skills",
        "brainstorm",
        "create-skill",
        "git-workflow",
        "interactive-debugging",
        "subagent-driven",
        "systematic-debugging",
        "tdd",
        "writing-plans"
    )

    /** Name of the meta-skill that is auto-injected into the system prompt. */
    const val META_SKILL_NAME = "using-skills"

    // ---- Project instructions ----

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

    // ---- Skill discovery (port of Cline's discoverSkills + getAvailableSkills) ----

    /**
     * Discover all skills from bundled resources, project-local, and global directories.
     * Returns metadata only (no content loaded) — cheap for system prompt listing.
     *
     * Port of Cline's discoverSkills(cwd) from skills.ts.
     *
     * Cline scans (via getSkillsDirectoriesForScan):
     *   - {cwd}/.clinerules/skills/  (project)
     *   - {cwd}/.cline/skills/       (project)
     *   - {cwd}/.claude/skills/      (project)
     *   - {cwd}/.agents/skills/      (project)
     *   - ~/.cline/skills/           (global)
     *   - ~/.agents/skills/          (global)
     *
     * Our adaptation scans:
     *   - classpath:/skills/          (bundled — our addition)
     *   - {projectPath}/.agent-skills/ (project)
     *   - ~/.workflow-orchestrator/skills/ (global)
     *
     * @param projectPath absolute path to the project root
     * @return list of discovered skill metadata (project skills first, then global)
     */
    fun discoverSkills(projectPath: String): List<SkillMetadata> {
        val skills = mutableListOf<SkillMetadata>()

        // Bundled skills (our addition — Cline has no bundled skills)
        skills.addAll(loadBundledSkillMetadata())

        // Project-local skills (lower precedence)
        val projectSkillsDir = File(projectPath, ".agent-skills")
        skills.addAll(scanSkillsDirectory(projectSkillsDir, SkillSource.PROJECT))

        // Global user skills (higher precedence)
        val globalSkillsDir = File(System.getProperty("user.home"), ".workflow-orchestrator/skills")
        skills.addAll(scanSkillsDirectory(globalSkillsDir, SkillSource.GLOBAL))

        return skills
    }

    /**
     * Get available skills with override resolution (global > project > bundled).
     *
     * Port of Cline's getAvailableSkills(skills) from skills.ts:
     * Deduplicates by name — skills added later (global) take precedence.
     *
     * @param skills list from [discoverSkills]
     * @return deduplicated list preserving original order
     */
    fun getAvailableSkills(skills: List<SkillMetadata>): List<SkillMetadata> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<SkillMetadata>()

        // Iterate backwards: global skills (added last) are seen first and take precedence
        for (i in skills.indices.reversed()) {
            val skill = skills[i]
            if (skill.name !in seen) {
                seen.add(skill.name)
                result.add(0, skill)
            }
        }

        return result
    }

    /**
     * Get full skill content including instructions.
     *
     * Port of Cline's getSkillContent(skillName, availableSkills) from skills.ts.
     * Loads content lazily — only when the skill is actually activated via use_skill.
     *
     * @param skillName the exact skill name to load
     * @param availableSkills the list from [getAvailableSkills]
     * @return full skill content, or null if not found
     */
    fun getSkillContent(skillName: String, availableSkills: List<SkillMetadata>): SkillContent? {
        val skill = availableSkills.find { it.name == skillName } ?: return null

        return try {
            val fileContent = when (skill.source) {
                SkillSource.BUNDLED -> loadClasspathResource(skill.path)
                SkillSource.PROJECT, SkillSource.GLOBAL -> {
                    val file = File(skill.path)
                    if (file.isFile && file.canRead()) file.readText(Charsets.UTF_8) else null
                }
            } ?: return null

            val (_, body) = parseYamlFrontmatter(fileContent)

            SkillContent(
                name = skill.name,
                description = skill.description,
                path = skill.path,
                source = skill.source,
                instructions = body.trim()
            )
        } catch (e: Exception) {
            LOG.warn("InstructionLoader: failed to load skill content for '${skill.name}': ${e.message}")
            null
        }
    }

    /**
     * Load the meta-skill content for auto-injection into the system prompt.
     *
     * The "using-skills" meta-skill teaches the LLM HOW to use skills.
     * It's auto-injected (not loaded via use_skill) because you can't tell
     * the LLM to "load the skill that teaches you how to load skills."
     *
     * @return the meta-skill instructions body, or null if not found
     */
    fun loadMetaSkillContent(): String? {
        val resourcePath = "/skills/$META_SKILL_NAME/SKILL.md"
        return try {
            val content = loadClasspathResource(resourcePath) ?: return null
            val (_, body) = parseYamlFrontmatter(content)
            body.trim().takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            LOG.warn("InstructionLoader: failed to load meta-skill: ${e.message}")
            null
        }
    }

    // ---- Internal: bundled skill metadata ----

    /**
     * Load metadata for bundled skills from classpath resources.
     * Only parses frontmatter (name, description) — does not load full content.
     */
    private fun loadBundledSkillMetadata(): List<SkillMetadata> {
        val skills = mutableListOf<SkillMetadata>()

        for (dirName in BUNDLED_SKILL_DIRS) {
            val resourcePath = "/skills/$dirName/SKILL.md"
            try {
                val content = loadClasspathResource(resourcePath) ?: continue
                val (frontmatter, _) = parseYamlFrontmatter(content)

                val name = frontmatter["name"]
                val description = frontmatter["description"]

                // Validate: name and description must exist (Cline requirement)
                if (name.isNullOrBlank()) {
                    LOG.warn("InstructionLoader: bundled skill '$dirName' missing 'name' field")
                    continue
                }
                if (description.isNullOrBlank()) {
                    LOG.warn("InstructionLoader: bundled skill '$dirName' missing 'description' field")
                    continue
                }
                if (name != dirName) {
                    LOG.warn("InstructionLoader: bundled skill name '$name' doesn't match directory '$dirName'")
                    continue
                }

                skills.add(SkillMetadata(
                    name = name,
                    description = description,
                    path = resourcePath,
                    source = SkillSource.BUNDLED
                ))
            } catch (e: Exception) {
                LOG.warn("InstructionLoader: failed to load bundled skill '$dirName': ${e.message}")
            }
        }

        return skills
    }

    // ---- Internal: filesystem skill scanning (port of Cline's scanSkillsDirectory) ----

    /**
     * Scan a directory for skill subdirectories containing SKILL.md files.
     *
     * Port of Cline's scanSkillsDirectory(dirPath, source) from skills.ts:
     * - Lists subdirectories in the given directory
     * - For each subdirectory, looks for SKILL.md
     * - Parses YAML frontmatter for name and description
     * - Validates that frontmatter name matches directory name
     *
     * @param dir the directory to scan
     * @param source whether this is a project or global skill
     * @return list of discovered skill metadata
     */
    private fun scanSkillsDirectory(dir: File, source: SkillSource): List<SkillMetadata> {
        if (!dir.isDirectory) return emptyList()

        val skills = mutableListOf<SkillMetadata>()
        try {
            val entries = dir.listFiles() ?: return emptyList()
            for (entry in entries) {
                if (!entry.isDirectory) continue

                val metadata = loadSkillMetadata(entry, source, entry.name)
                if (metadata != null) {
                    skills.add(metadata)
                }
            }
        } catch (e: SecurityException) {
            LOG.warn("InstructionLoader: permission denied reading skills directory: ${dir.path}")
        }

        return skills
    }

    /**
     * Load skill metadata from a skill directory.
     *
     * Port of Cline's loadSkillMetadata(skillDir, source, skillName) from skills.ts.
     *
     * @param skillDir the skill directory containing SKILL.md
     * @param source whether this is a project or global skill
     * @param skillName expected skill name (must match directory name)
     * @return skill metadata, or null if invalid
     */
    private fun loadSkillMetadata(skillDir: File, source: SkillSource, skillName: String): SkillMetadata? {
        val skillMd = File(skillDir, "SKILL.md")
        if (!skillMd.isFile || !skillMd.canRead()) return null

        return try {
            val content = skillMd.readText(Charsets.UTF_8)
            val (frontmatter, _) = parseYamlFrontmatter(content)

            val name = frontmatter["name"]
            val description = frontmatter["description"]

            // Validate required fields (Cline requirement)
            if (name.isNullOrBlank()) {
                LOG.warn("InstructionLoader: skill at ${skillDir.path} missing 'name' field")
                return null
            }
            if (description.isNullOrBlank()) {
                LOG.warn("InstructionLoader: skill at ${skillDir.path} missing 'description' field")
                return null
            }

            // Name must match directory name per spec
            if (name != skillName) {
                LOG.warn("InstructionLoader: skill name '$name' doesn't match directory '$skillName'")
                return null
            }

            SkillMetadata(
                name = name,
                description = description,
                path = skillMd.absolutePath,
                source = source
            )
        } catch (e: Exception) {
            LOG.warn("InstructionLoader: failed to load skill at ${skillDir.path}: ${e.message}")
            null
        }
    }

    // ---- Frontmatter parsing (port of Cline's parseYamlFrontmatter from frontmatter.ts) ----

    /**
     * Parse YAML frontmatter from a skill/rules file.
     *
     * Port of Cline's parseYamlFrontmatter(markdown) from frontmatter.ts:
     *   const frontmatterRegex = /^---\r?\n([\s\S]*?)\r?\n---\r?\n?([\s\S]*)$/
     *
     * Frontmatter is delimited by `---` on its own line at the start of the file.
     * Uses regex matching (like Cline) instead of indexOf for correctness with
     * line-ending handling.
     *
     * @param content the full file content
     * @return parsed frontmatter result
     */
    internal fun parseYamlFrontmatter(content: String): FrontmatterResult {
        val match = FRONTMATTER_REGEX.find(content)
            ?: return FrontmatterResult(data = emptyMap(), body = content, hadFrontmatter = false)

        val yamlContent = match.groupValues[1]
        val body = match.groupValues[2]

        // Simple key-value parsing (sufficient for skill frontmatter fields)
        // Cline uses js-yaml library; we use simple parsing since our frontmatter
        // only has flat key-value pairs (name, description, preferred-tools, user-invocable)
        val data = mutableMapOf<String, String>()
        for (line in yamlContent.lines()) {
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                val key = line.substring(0, colonIdx).trim()
                val value = line.substring(colonIdx + 1).trim()
                if (value.isNotEmpty()) {
                    data[key] = value
                }
            }
        }

        return FrontmatterResult(data = data, body = body, hadFrontmatter = true)
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

    // Port of Cline's frontmatter regex from frontmatter.ts:
    //   /^---\r?\n([\s\S]*?)\r?\n---\r?\n?([\s\S]*)$/
    private val FRONTMATTER_REGEX = Regex("^---\\r?\\n([\\s\\S]*?)\\r?\\n---\\r?\\n?([\\s\\S]*)\$")
}

// ---- Data classes (port of Cline's src/shared/skills.ts) ----

/**
 * Where a skill was discovered.
 * Cline has "global" | "project". We add BUNDLED for classpath skills (our adaptation).
 */
enum class SkillSource { BUNDLED, PROJECT, GLOBAL }

/**
 * Skill metadata — loaded at discovery time (cheap).
 * Port of Cline's SkillMetadata from src/shared/skills.ts.
 *
 * @param name unique skill identifier (used in use_skill tool)
 * @param description human-readable description (shown in system prompt skill list)
 * @param path file path to SKILL.md (classpath path for bundled, absolute path for user)
 * @param source where the skill was discovered
 */
data class SkillMetadata(
    val name: String,
    val description: String,
    val path: String,
    val source: SkillSource
)

/**
 * Skill content — loaded on demand when activated via use_skill.
 * Port of Cline's SkillContent from src/shared/skills.ts.
 *
 * @param name unique skill identifier
 * @param description human-readable description
 * @param path file path to SKILL.md
 * @param source where the skill was discovered
 * @param instructions full skill body (after frontmatter) — the actual instructions
 */
data class SkillContent(
    val name: String,
    val description: String,
    val path: String,
    val source: SkillSource,
    val instructions: String
)

/**
 * Result of parsing YAML frontmatter from a markdown file.
 * Port of Cline's FrontmatterParseResult from frontmatter.ts.
 *
 * @param data key-value pairs from frontmatter
 * @param body markdown content after stripping frontmatter
 * @param hadFrontmatter true if --- delimiters were found (even if parsing failed)
 * @param parseError error message if YAML parsing failed
 */
data class FrontmatterResult(
    val data: Map<String, String>,
    val body: String,
    val hadFrontmatter: Boolean,
    val parseError: String? = null
)
