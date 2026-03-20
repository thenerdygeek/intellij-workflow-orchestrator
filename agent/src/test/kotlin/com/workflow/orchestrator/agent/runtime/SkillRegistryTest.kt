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
        registry = SkillRegistry(projectDir.toString(), userDir.toString())
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

        val expected = """
            |Available skills:
            |- /deploy — Deploy service to staging
            |- /hotfix — Create hotfix for production
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
        val emptyRegistry = SkillRegistry("/nonexistent/path", "/also/nonexistent")
        val results = emptyRegistry.scan()

        assertTrue(results.isEmpty())
    }
}
