package com.workflow.orchestrator.agent.prompt

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class InstructionLoaderTest {

    @TempDir
    lateinit var tempDir: File

    // ---- Project instructions ----

    @Test
    fun `discovers CLAUDE_md from project root`() {
        val claudeMd = File(tempDir, "CLAUDE.md")
        claudeMd.writeText("# Project Instructions\nBuild with gradle.")

        val result = InstructionLoader.loadProjectInstructions(tempDir.absolutePath)

        assertNotNull(result)
        assertTrue(result!!.contains("Project Instructions"))
        assertTrue(result.contains("Build with gradle"))
    }

    @Test
    fun `discovers agent-rules from project root`() {
        val rules = File(tempDir, ".agent-rules")
        rules.writeText("Always use Kotlin.\nFollow SOLID.")

        val result = InstructionLoader.loadProjectInstructions(tempDir.absolutePath)

        assertNotNull(result)
        assertTrue(result!!.contains("Always use Kotlin"))
    }

    @Test
    fun `returns null when no instruction files exist`() {
        val result = InstructionLoader.loadProjectInstructions(tempDir.absolutePath)
        assertNull(result)
    }

    @Test
    fun `CLAUDE_md takes priority over agent-rules`() {
        File(tempDir, "CLAUDE.md").writeText("From CLAUDE.md")
        File(tempDir, ".agent-rules").writeText("From agent-rules")

        val result = InstructionLoader.loadProjectInstructions(tempDir.absolutePath)

        assertNotNull(result)
        assertTrue(result!!.contains("From CLAUDE.md"), "CLAUDE.md should take priority")
    }

    @Test
    fun `returns null for blank instruction file`() {
        File(tempDir, "CLAUDE.md").writeText("   ")

        val result = InstructionLoader.loadProjectInstructions(tempDir.absolutePath)
        assertNull(result)
    }

    // ---- Skill discovery (port of Cline's discoverSkills + getAvailableSkills) ----

    @Test
    fun `discoverSkills finds bundled skills`() {
        val skills = InstructionLoader.discoverSkills(tempDir.absolutePath)

        assertTrue(skills.isNotEmpty(), "should find bundled skills")
        val skillNames = skills.map { it.name }
        assertTrue("tdd" in skillNames, "should find the tdd skill")
        assertTrue("brainstorm" in skillNames, "should find the brainstorm skill")
    }

    @Test
    fun `bundled skills have BUNDLED source`() {
        val skills = InstructionLoader.discoverSkills(tempDir.absolutePath)
        val bundled = skills.filter { it.source == SkillSource.BUNDLED }
        assertTrue(bundled.isNotEmpty(), "should have bundled skills")
        for (skill in bundled) {
            assertTrue(skill.path.startsWith("/skills/"), "bundled skill path should be classpath")
        }
    }

    @Test
    fun `bundled skills have non-blank descriptions`() {
        val skills = InstructionLoader.discoverSkills(tempDir.absolutePath)
        for (skill in skills.filter { it.source == SkillSource.BUNDLED }) {
            assertTrue(skill.description.isNotBlank(), "skill '${skill.name}' should have a description")
        }
    }

    @Test
    fun `discoverSkills finds project-local skills`() {
        val skillsDir = File(tempDir, ".agent-skills")
        val mySkillDir = File(skillsDir, "my-skill")
        mySkillDir.mkdirs()

        File(mySkillDir, "SKILL.md").writeText("""---
name: my-skill
description: A project-specific skill
---

# My Skill

Do something specific to this project.""")

        val skills = InstructionLoader.discoverSkills(tempDir.absolutePath)
        val projectSkill = skills.find { it.name == "my-skill" }

        assertNotNull(projectSkill, "should find project-local skill")
        assertEquals(SkillSource.PROJECT, projectSkill!!.source)
        assertEquals("A project-specific skill", projectSkill.description)
        assertTrue(projectSkill.path.endsWith("SKILL.md"))
    }

    @Test
    fun `getAvailableSkills deduplicates with global precedence`() {
        // Simulate: bundled "tdd" + project "tdd" override
        val skills = listOf(
            SkillMetadata("tdd", "Bundled TDD", "/skills/tdd/SKILL.md", SkillSource.BUNDLED),
            SkillMetadata("tdd", "Project TDD", "/project/.agent-skills/tdd/SKILL.md", SkillSource.PROJECT)
        )

        val available = InstructionLoader.getAvailableSkills(skills)

        assertEquals(1, available.size)
        assertEquals("Project TDD", available[0].description, "project should override bundled")
    }

    @Test
    fun `getAvailableSkills preserves order`() {
        val skills = listOf(
            SkillMetadata("alpha", "First", "/a", SkillSource.BUNDLED),
            SkillMetadata("beta", "Second", "/b", SkillSource.PROJECT),
            SkillMetadata("gamma", "Third", "/c", SkillSource.GLOBAL)
        )

        val available = InstructionLoader.getAvailableSkills(skills)

        assertEquals(listOf("alpha", "beta", "gamma"), available.map { it.name })
    }

    @Test
    fun `getAvailableSkills global overrides project with same name`() {
        val skills = listOf(
            SkillMetadata("custom", "Project version", "/p/custom/SKILL.md", SkillSource.PROJECT),
            SkillMetadata("custom", "Global version", "/g/custom/SKILL.md", SkillSource.GLOBAL)
        )

        val available = InstructionLoader.getAvailableSkills(skills)

        assertEquals(1, available.size)
        assertEquals("Global version", available[0].description)
        assertEquals(SkillSource.GLOBAL, available[0].source)
    }

    // ---- getSkillContent (lazy loading) ----

    @Test
    fun `getSkillContent loads bundled skill content`() {
        val skills = InstructionLoader.discoverSkills(tempDir.absolutePath)
        val available = InstructionLoader.getAvailableSkills(skills)

        val content = InstructionLoader.getSkillContent("tdd", available)

        assertNotNull(content, "should load tdd skill content")
        assertTrue(content!!.instructions.contains("Test-Driven"), "should contain TDD instructions")
        assertEquals("tdd", content.name)
        assertEquals(SkillSource.BUNDLED, content.source)
    }

    @Test
    fun `getSkillContent loads project skill content`() {
        val skillsDir = File(tempDir, ".agent-skills")
        val mySkillDir = File(skillsDir, "my-skill")
        mySkillDir.mkdirs()
        File(mySkillDir, "SKILL.md").writeText("""---
name: my-skill
description: A test skill
---

# My Skill Instructions

Follow these steps.""")

        val skills = InstructionLoader.discoverSkills(tempDir.absolutePath)
        val available = InstructionLoader.getAvailableSkills(skills)

        val content = InstructionLoader.getSkillContent("my-skill", available)

        assertNotNull(content, "should load project skill content")
        assertTrue(content!!.instructions.contains("My Skill Instructions"))
        assertEquals(SkillSource.PROJECT, content.source)
    }

    @Test
    fun `getSkillContent returns null for unknown skill`() {
        val skills = InstructionLoader.discoverSkills(tempDir.absolutePath)
        val available = InstructionLoader.getAvailableSkills(skills)

        val content = InstructionLoader.getSkillContent("nonexistent-skill-xyz", available)
        assertNull(content)
    }

    @Test
    fun `user skill overrides bundled skill content`() {
        val skillsDir = File(tempDir, ".agent-skills")
        val overrideDir = File(skillsDir, "tdd")
        overrideDir.mkdirs()
        File(overrideDir, "SKILL.md").writeText("""---
name: tdd
description: My custom TDD approach
---

Custom TDD instructions that override the bundled ones.""")

        val skills = InstructionLoader.discoverSkills(tempDir.absolutePath)
        val available = InstructionLoader.getAvailableSkills(skills)
        val content = InstructionLoader.getSkillContent("tdd", available)

        assertNotNull(content)
        assertEquals("My custom TDD approach", content!!.description)
        assertTrue(content.instructions.contains("Custom TDD instructions"),
            "user skill content should override bundled content")
    }

    // ---- Skill validation ----

    @Test
    fun `ignores skill with missing name`() {
        val skillsDir = File(tempDir, ".agent-skills")
        val badSkillDir = File(skillsDir, "bad-skill")
        badSkillDir.mkdirs()

        File(badSkillDir, "SKILL.md").writeText("""---
description: No name field
---
Content here.""")

        val skills = InstructionLoader.discoverSkills(tempDir.absolutePath)
        assertFalse(skills.any { it.name == "bad-skill" }, "skill without name should be ignored")
    }

    @Test
    fun `ignores skill with missing description`() {
        val skillsDir = File(tempDir, ".agent-skills")
        val badSkillDir = File(skillsDir, "no-desc")
        badSkillDir.mkdirs()

        File(badSkillDir, "SKILL.md").writeText("""---
name: no-desc
---
Content without description.""")

        val skills = InstructionLoader.discoverSkills(tempDir.absolutePath)
        assertFalse(skills.any { it.name == "no-desc" }, "skill without description should be ignored")
    }

    @Test
    fun `ignores skill where name does not match directory name`() {
        val skillsDir = File(tempDir, ".agent-skills")
        val dirName = File(skillsDir, "dir-name")
        dirName.mkdirs()

        File(dirName, "SKILL.md").writeText("""---
name: different-name
description: Name doesn't match dir
---
Content.""")

        val skills = InstructionLoader.discoverSkills(tempDir.absolutePath)
        assertFalse(skills.any { it.name == "different-name" }, "mismatched name should be ignored")
        assertFalse(skills.any { it.name == "dir-name" }, "mismatched dir should be ignored")
    }

    @Test
    fun `discovers multiple skills from project directory`() {
        val skillsDir = File(tempDir, ".agent-skills")

        val skill1Dir = File(skillsDir, "skill-one")
        skill1Dir.mkdirs()
        File(skill1Dir, "SKILL.md").writeText("""---
name: skill-one
description: First skill
---
First skill content.""")

        val skill2Dir = File(skillsDir, "skill-two")
        skill2Dir.mkdirs()
        File(skill2Dir, "SKILL.md").writeText("""---
name: skill-two
description: Second skill
---
Second skill content.""")

        val skills = InstructionLoader.discoverSkills(tempDir.absolutePath)
        val projectNames = skills.filter { it.source == SkillSource.PROJECT }.map { it.name }.toSet()
        assertTrue("skill-one" in projectNames)
        assertTrue("skill-two" in projectNames)
    }

    @Test
    fun `ignores non-directory entries in skills folder`() {
        val skillsDir = File(tempDir, ".agent-skills")
        skillsDir.mkdirs()
        File(skillsDir, "not-a-skill.md").writeText("Just a file")

        val skills = InstructionLoader.discoverSkills(tempDir.absolutePath)
        val projectSkills = skills.filter { it.source == SkillSource.PROJECT }
        assertTrue(projectSkills.isEmpty())
    }

    @Test
    fun `ignores skill directory without SKILL_md`() {
        val skillsDir = File(tempDir, ".agent-skills")
        val emptySkillDir = File(skillsDir, "empty-skill")
        emptySkillDir.mkdirs()

        val skills = InstructionLoader.discoverSkills(tempDir.absolutePath)
        assertFalse(skills.any { it.name == "empty-skill" })
    }

    // ---- YAML frontmatter parsing (port of Cline's frontmatter.ts) ----

    @Test
    fun `parses YAML frontmatter correctly`() {
        val content = """---
name: my-skill
description: A test skill that does things
preferred-tools: [read_file, edit_file]
---

# Skill Content

Some instructions here."""

        val result = InstructionLoader.parseYamlFrontmatter(content)

        assertTrue(result.hadFrontmatter)
        assertEquals("my-skill", result.data["name"])
        assertEquals("A test skill that does things", result.data["description"])
        assertEquals("[read_file, edit_file]", result.data["preferred-tools"])
        assertTrue(result.body.contains("# Skill Content"))
        assertTrue(result.body.contains("Some instructions here."))
    }

    @Test
    fun `handles content without frontmatter`() {
        val content = "# Just a regular file\nNo frontmatter here."

        val result = InstructionLoader.parseYamlFrontmatter(content)

        assertFalse(result.hadFrontmatter)
        assertTrue(result.data.isEmpty())
        assertEquals(content, result.body)
    }

    @Test
    fun `handles empty frontmatter`() {
        // Cline's regex requires a newline between --- delimiters (^---\r?\n([\s\S]*?)\r?\n---)
        // So empty frontmatter must have at least one blank line between delimiters
        val content = "---\n\n---\n# Content after empty frontmatter"

        val result = InstructionLoader.parseYamlFrontmatter(content)

        assertTrue(result.hadFrontmatter)
        assertTrue(result.data.isEmpty())
        assertTrue(result.body.contains("Content after empty frontmatter"))
    }

    @Test
    fun `handles missing closing frontmatter delimiter`() {
        val content = """---
name: broken-skill
This has no closing delimiter"""

        val result = InstructionLoader.parseYamlFrontmatter(content)

        // Should return content as-is when no closing delimiter
        assertFalse(result.hadFrontmatter)
        assertTrue(result.data.isEmpty())
        assertEquals(content, result.body)
    }

    @Test
    fun `handles windows line endings in frontmatter`() {
        val content = "---\r\nname: win-skill\r\ndescription: Windows skill\r\n---\r\n# Content"

        val result = InstructionLoader.parseYamlFrontmatter(content)

        assertTrue(result.hadFrontmatter)
        assertEquals("win-skill", result.data["name"])
        assertEquals("Windows skill", result.data["description"])
        assertTrue(result.body.contains("# Content"))
    }
}
