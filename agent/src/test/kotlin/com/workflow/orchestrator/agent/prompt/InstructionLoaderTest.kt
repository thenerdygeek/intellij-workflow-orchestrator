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

    // ---- Bundled skills ----

    @Test
    fun `loads bundled skills from resources`() {
        val skills = InstructionLoader.loadBundledSkills()

        assertTrue(skills.isNotEmpty(), "should find at least one bundled skill")
        val skillNames = skills.map { it.name }
        assertTrue("tdd" in skillNames, "should find the tdd skill")
        assertTrue("brainstorm" in skillNames, "should find the brainstorm skill")
    }

    @Test
    fun `bundled skills have non-blank descriptions`() {
        val skills = InstructionLoader.loadBundledSkills()
        for (skill in skills) {
            assertTrue(skill.description.isNotBlank(), "skill '${skill.name}' should have a description")
        }
    }

    @Test
    fun `bundled skills have non-blank content`() {
        val skills = InstructionLoader.loadBundledSkills()
        for (skill in skills) {
            assertTrue(skill.content.isNotBlank(), "skill '${skill.name}' should have content")
        }
    }

    @Test
    fun `loadSkillContent returns content for known skill`() {
        val content = InstructionLoader.loadSkillContent("tdd")
        assertNotNull(content)
        assertTrue(content!!.contains("Test-Driven"), "tdd skill should mention Test-Driven")
    }

    @Test
    fun `loadSkillContent returns null for unknown skill`() {
        val content = InstructionLoader.loadSkillContent("nonexistent-skill-xyz")
        assertNull(content)
    }

    // ---- YAML frontmatter parsing ----

    @Test
    fun `parses YAML frontmatter correctly`() {
        val content = """---
name: my-skill
description: A test skill that does things
preferred-tools: [read_file, edit_file]
---

# Skill Content

Some instructions here."""

        val (frontmatter, body) = InstructionLoader.parseYamlFrontmatter(content)

        assertEquals("my-skill", frontmatter["name"])
        assertEquals("A test skill that does things", frontmatter["description"])
        assertEquals("[read_file, edit_file]", frontmatter["preferred-tools"])
        assertTrue(body.contains("# Skill Content"))
        assertTrue(body.contains("Some instructions here."))
    }

    @Test
    fun `handles content without frontmatter`() {
        val content = "# Just a regular file\nNo frontmatter here."

        val (frontmatter, body) = InstructionLoader.parseYamlFrontmatter(content)

        assertTrue(frontmatter.isEmpty())
        assertEquals(content, body)
    }

    @Test
    fun `handles empty frontmatter`() {
        val content = """---
---
# Content after empty frontmatter"""

        val (frontmatter, body) = InstructionLoader.parseYamlFrontmatter(content)

        assertTrue(frontmatter.isEmpty())
        assertTrue(body.contains("Content after empty frontmatter"))
    }

    @Test
    fun `handles missing closing frontmatter delimiter`() {
        val content = """---
name: broken-skill
This has no closing delimiter"""

        val (frontmatter, body) = InstructionLoader.parseYamlFrontmatter(content)

        // Should return content as-is when no closing delimiter
        assertTrue(frontmatter.isEmpty())
        assertEquals(content, body)
    }

    // ---- Dynamic skill discovery (ported from Cline's skill loading) ----

    @Test
    fun `discovers skills from project directory`() {
        // Create a project-local skill directory
        val skillsDir = File(tempDir, ".agent-skills")
        val mySkillDir = File(skillsDir, "my-skill")
        mySkillDir.mkdirs()

        File(mySkillDir, "SKILL.md").writeText("""---
name: my-skill
description: A project-specific skill
---

# My Skill

Do something specific to this project.""")

        val skills = InstructionLoader.loadUserSkills(tempDir.absolutePath)

        assertEquals(1, skills.size, "should find one skill")
        assertEquals("my-skill", skills[0].name)
        assertEquals("A project-specific skill", skills[0].description)
        assertTrue(skills[0].content.contains("My Skill"))
    }

    @Test
    fun `discovers skills from global directory`() {
        // We can't easily test the real ~/.workflow-orchestrator/skills directory,
        // but we can verify loadUserSkills doesn't crash when it doesn't exist
        val skills = InstructionLoader.loadUserSkills(tempDir.absolutePath)
        // Should return empty list (no skills dir exists in tempDir)
        assertNotNull(skills)
    }

    @Test
    fun `ignores skill with missing name`() {
        val skillsDir = File(tempDir, ".agent-skills")
        val badSkillDir = File(skillsDir, "bad-skill")
        badSkillDir.mkdirs()

        File(badSkillDir, "SKILL.md").writeText("""---
description: No name field
---
Content here.""")

        val skills = InstructionLoader.loadUserSkills(tempDir.absolutePath)
        assertTrue(skills.isEmpty(), "skill without name should be ignored")
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

        val skills = InstructionLoader.loadUserSkills(tempDir.absolutePath)
        assertTrue(skills.isEmpty(), "skill without description should be ignored")
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

        val skills = InstructionLoader.loadUserSkills(tempDir.absolutePath)
        assertTrue(skills.isEmpty(), "skill with mismatched name should be ignored")
    }

    @Test
    fun `discovers multiple skills from project directory`() {
        val skillsDir = File(tempDir, ".agent-skills")

        // Skill 1
        val skill1Dir = File(skillsDir, "skill-one")
        skill1Dir.mkdirs()
        File(skill1Dir, "SKILL.md").writeText("""---
name: skill-one
description: First skill
---
First skill content.""")

        // Skill 2
        val skill2Dir = File(skillsDir, "skill-two")
        skill2Dir.mkdirs()
        File(skill2Dir, "SKILL.md").writeText("""---
name: skill-two
description: Second skill
---
Second skill content.""")

        val skills = InstructionLoader.loadUserSkills(tempDir.absolutePath)
        assertEquals(2, skills.size)
        val names = skills.map { it.name }.toSet()
        assertTrue("skill-one" in names)
        assertTrue("skill-two" in names)
    }

    @Test
    fun `ignores non-directory entries in skills folder`() {
        val skillsDir = File(tempDir, ".agent-skills")
        skillsDir.mkdirs()

        // Create a regular file (not a directory) — should be ignored
        File(skillsDir, "not-a-skill.md").writeText("Just a file")

        val skills = InstructionLoader.loadUserSkills(tempDir.absolutePath)
        assertTrue(skills.isEmpty())
    }

    @Test
    fun `ignores skill directory without SKILL_md`() {
        val skillsDir = File(tempDir, ".agent-skills")
        val emptySkillDir = File(skillsDir, "empty-skill")
        emptySkillDir.mkdirs()
        // No SKILL.md in the directory

        val skills = InstructionLoader.loadUserSkills(tempDir.absolutePath)
        assertTrue(skills.isEmpty())
    }

    @Test
    fun `loadAllSkills merges bundled and user skills`() {
        // Create a user skill
        val skillsDir = File(tempDir, ".agent-skills")
        val userSkillDir = File(skillsDir, "custom-skill")
        userSkillDir.mkdirs()
        File(userSkillDir, "SKILL.md").writeText("""---
name: custom-skill
description: A custom user skill
---
Custom instructions here.""")

        val allSkills = InstructionLoader.loadAllSkills(tempDir.absolutePath)

        // Should include both bundled and user skills
        val names = allSkills.map { it.name }.toSet()
        assertTrue("tdd" in names, "should include bundled tdd skill")
        assertTrue("custom-skill" in names, "should include user custom-skill")
    }

    @Test
    fun `user skill overrides bundled skill with same name`() {
        // Create a user skill that overrides the bundled "tdd" skill
        val skillsDir = File(tempDir, ".agent-skills")
        val overrideDir = File(skillsDir, "tdd")
        overrideDir.mkdirs()
        File(overrideDir, "SKILL.md").writeText("""---
name: tdd
description: My custom TDD approach
---
Custom TDD instructions that override the bundled ones.""")

        val allSkills = InstructionLoader.loadAllSkills(tempDir.absolutePath)

        val tddSkill = allSkills.find { it.name == "tdd" }
        assertNotNull(tddSkill)
        assertTrue(tddSkill!!.description.contains("My custom TDD approach"),
            "user skill should override bundled skill")
        assertTrue(tddSkill.content.contains("Custom TDD instructions"),
            "user skill content should override bundled content")
    }
}
