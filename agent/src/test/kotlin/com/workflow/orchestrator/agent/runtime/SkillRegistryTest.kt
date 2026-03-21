package com.workflow.orchestrator.agent.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class SkillRegistryTest {

    @TempDir
    lateinit var projectDir: Path

    @TempDir
    lateinit var userDir: Path

    private lateinit var registry: SkillRegistry

    @BeforeEach
    fun setup() {
        registry = SkillRegistry(projectDir.toString(), userDir.toString(), loadBuiltins = false)
    }

    private fun writeSkill(base: File, skillName: String, content: String) {
        val dir = if (base == File(projectDir.toString())) {
            File(base, ".workflow/skills/$skillName")
        } else {
            File(base, ".workflow-orchestrator/skills/$skillName")
        }
        dir.mkdirs()
        File(dir, "SKILL.md").writeText(content)
    }

    private fun writeProjectSkill(name: String, content: String) {
        writeSkill(File(projectDir.toString()), name, content)
    }

    private fun writeUserSkill(name: String, content: String) {
        writeSkill(File(userDir.toString()), name, content)
    }

    private val hotfixSkill = """
        |---
        |name: hotfix
        |description: Create hotfix for production
        |preferred-tools: [jira_get_ticket, bamboo_trigger_build]
        |disable-model-invocation: true
        |user-invocable: true
        |---
        |## Hotfix Instructions
        |
        |Follow these steps to create a hotfix.
    """.trimMargin()

    private val deploySkill = """
        |---
        |name: deploy
        |description: Deploy service to staging
        |preferred-tools: [bamboo_trigger_build]
        |disable-model-invocation: false
        |user-invocable: false
        |---
        |## Deploy Instructions
        |
        |Deploy to staging environment.
    """.trimMargin()

    private val forkSkill = """
        |---
        |name: code-review
        |description: Run isolated code review
        |allowed-tools: [read_file, search_code, diagnostics]
        |context: fork
        |agent: reviewer
        |argument-hint: <file-path>
        |---
        |## Code Review
        |
        |Review the specified file.
    """.trimMargin()

    @Test
    fun `scan finds skills in project directory`() {
        writeProjectSkill("hotfix", hotfixSkill)

        val results = registry.scan()

        assertEquals(1, results.size)
        assertEquals("hotfix", results[0].name)
        assertEquals("Create hotfix for production", results[0].description)
        assertEquals(SkillRegistry.SkillScope.PROJECT, results[0].scope)
    }

    @Test
    fun `scan finds skills in user directory`() {
        writeUserSkill("deploy", deploySkill)

        val results = registry.scan()

        assertEquals(1, results.size)
        assertEquals("deploy", results[0].name)
        assertEquals("Deploy service to staging", results[0].description)
        assertEquals(SkillRegistry.SkillScope.USER, results[0].scope)
    }

    @Test
    fun `project skill overrides user skill with same name`() {
        writeUserSkill("hotfix", """
            |---
            |name: hotfix
            |description: User hotfix version
            |---
            |User instructions
        """.trimMargin())

        writeProjectSkill("hotfix", """
            |---
            |name: hotfix
            |description: Project hotfix version
            |---
            |Project instructions
        """.trimMargin())

        val results = registry.scan()

        assertEquals(1, results.size)
        assertEquals("Project hotfix version", results[0].description)
        assertEquals(SkillRegistry.SkillScope.PROJECT, results[0].scope)
    }

    @Test
    fun `parses preferred-tools from frontmatter`() {
        writeProjectSkill("hotfix", hotfixSkill)

        registry.scan()
        val skill = registry.getSkill("hotfix")

        assertNotNull(skill)
        assertEquals(listOf("jira_get_ticket", "bamboo_trigger_build"), skill!!.preferredTools)
    }

    @Test
    fun `parses disable-model-invocation and user-invocable`() {
        writeProjectSkill("hotfix", hotfixSkill)
        writeProjectSkill("deploy", deploySkill)

        registry.scan()

        val hotfix = registry.getSkill("hotfix")!!
        assertTrue(hotfix.disableModelInvocation)
        assertTrue(hotfix.userInvocable)

        val deploy = registry.getSkill("deploy")!!
        assertFalse(deploy.disableModelInvocation)
        assertFalse(deploy.userInvocable)
    }

    @Test
    fun `getSkillContent returns body without frontmatter`() {
        writeProjectSkill("hotfix", hotfixSkill)

        registry.scan()
        val content = registry.getSkillContent("hotfix")

        assertNotNull(content)
        assertTrue(content!!.startsWith("## Hotfix Instructions"))
        assertFalse(content.contains("---"))
        assertTrue(content.contains("Follow these steps to create a hotfix."))
    }

    @Test
    fun `buildDescriptionIndex formats compact list`() {
        writeProjectSkill("deploy", deploySkill)
        writeProjectSkill("hotfix", hotfixSkill)

        registry.scan()
        val index = registry.buildDescriptionIndex()

        // hotfix has disable-model-invocation: true, so it must be hidden from LLM context
        val expected = """
            |Available skills:
            |- /deploy — Deploy service to staging
        """.trimMargin()
        assertEquals(expected, index)
    }

    @Test
    fun `scan handles malformed YAML gracefully`() {
        // Skill with no frontmatter delimiters
        writeProjectSkill("bad1", "No frontmatter here, just text.")

        // Skill with only opening delimiter
        writeProjectSkill("bad2", "---\nname: broken\nNo closing delimiter")

        // Valid skill alongside malformed ones
        writeProjectSkill("good", hotfixSkill)

        val results = registry.scan()

        assertEquals(1, results.size)
        assertEquals("hotfix", results[0].name)
    }

    @Test
    fun `scan returns empty when no skills directory exists`() {
        val emptyRegistry = SkillRegistry("/nonexistent/path", "/also/nonexistent", loadBuiltins = false)
        val results = emptyRegistry.scan()

        assertTrue(results.isEmpty())
    }

    @Test
    fun `scan loads built-in skills when loadBuiltins is true`() {
        val builtinRegistry = SkillRegistry("/nonexistent", "/nonexistent", loadBuiltins = true)
        val results = builtinRegistry.scan()

        // Should find at least the systematic-debugging built-in skill
        assertTrue(results.any { it.name == "systematic-debugging" })
        val skill = results.find { it.name == "systematic-debugging" }!!
        assertEquals(SkillRegistry.SkillScope.BUILTIN, skill.scope)
        assertTrue(skill.description.contains("bug"))
        assertTrue(skill.preferredTools.contains("diagnostics"))
    }

    @Test
    fun `parses allowed-tools from frontmatter`() {
        writeProjectSkill("code-review", forkSkill)

        registry.scan()
        val skill = registry.getSkill("code-review")

        assertNotNull(skill)
        assertEquals(listOf("read_file", "search_code", "diagnostics"), skill!!.allowedTools)
    }

    @Test
    fun `parses context fork and agent type from frontmatter`() {
        writeProjectSkill("code-review", forkSkill)

        registry.scan()
        val skill = registry.getSkill("code-review")!!

        assertTrue(skill.contextFork)
        assertEquals("reviewer", skill.agentType)
    }

    @Test
    fun `parses argument-hint from frontmatter`() {
        writeProjectSkill("code-review", forkSkill)

        registry.scan()
        val skill = registry.getSkill("code-review")!!

        assertEquals("<file-path>", skill.argumentHint)
    }

    @Test
    fun `new fields default to null or false when absent`() {
        writeProjectSkill("hotfix", hotfixSkill)

        registry.scan()
        val skill = registry.getSkill("hotfix")!!

        assertNull(skill.allowedTools)
        assertFalse(skill.contextFork)
        assertNull(skill.agentType)
        assertNull(skill.argumentHint)
    }

    @Test
    fun `getSupportingFiles lists files excluding SKILL_MD`() {
        val skillDir = File(projectDir.toString(), ".workflow/skills/my-skill")
        skillDir.mkdirs()
        File(skillDir, "SKILL.md").writeText("""
            |---
            |name: my-skill
            |description: test skill
            |---
            |Hello
        """.trimMargin())
        File(skillDir, "template.md").writeText("Template content")
        val scriptsDir = File(skillDir, "scripts")
        scriptsDir.mkdirs()
        File(scriptsDir, "validate.sh").writeText("#!/bin/bash\necho ok")

        registry.scan()
        val files = registry.getSupportingFiles("my-skill")

        assertTrue(files.any { it == "template.md" })
        assertTrue(files.any { it.contains("validate.sh") })
        assertFalse(files.any { it == "SKILL.md" })
    }

    @Test
    fun `getSupportingFiles returns empty for builtin skills`() {
        val builtinRegistry = SkillRegistry("/nonexistent", "/nonexistent", loadBuiltins = true)
        builtinRegistry.scan()
        val files = builtinRegistry.getSupportingFiles("systematic-debugging")
        assertTrue(files.isEmpty())
    }

    @Test
    fun `description budget truncates when exceeded`() {
        // Create many skills with long descriptions that will exceed a small budget
        for (i in 1..50) {
            writeProjectSkill("skill-$i", """
                |---
                |name: skill-$i
                |description: This is a long description for skill number $i that takes up space in the budget
                |---
                |Content for skill $i
            """.trimMargin())
        }

        registry.scan()
        // Use a very small maxInputTokens to force a tiny budget
        val index = registry.buildDescriptionIndex(maxInputTokens = 5_000)

        assertTrue(index.contains("hidden due to description budget"))
    }

    @Test
    fun `description budget includes all skills when budget is large`() {
        writeProjectSkill("deploy", deploySkill)

        registry.scan()
        val index = registry.buildDescriptionIndex(maxInputTokens = 190_000)

        assertFalse(index.contains("hidden due to description budget"))
        assertTrue(index.contains("/deploy"))
    }

    @Test
    fun `project skill overrides built-in skill with same name`() {
        val projectDir = this.projectDir.toFile()
        writeSkill(projectDir, "systematic-debugging", """
            ---
            name: systematic-debugging
            description: Custom project debugging workflow
            ---
            Custom content
        """.trimIndent())

        val overrideRegistry = SkillRegistry(projectDir.absolutePath, "/nonexistent", loadBuiltins = true)
        val results = overrideRegistry.scan()

        val skill = results.find { it.name == "systematic-debugging" }!!
        assertEquals(SkillRegistry.SkillScope.PROJECT, skill.scope)
        assertEquals("Custom project debugging workflow", skill.description)
    }
}
