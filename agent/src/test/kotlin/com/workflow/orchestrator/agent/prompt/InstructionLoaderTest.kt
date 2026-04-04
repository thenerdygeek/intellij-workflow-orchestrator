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
}
