package com.workflow.orchestrator.agent.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class SkillManagerTest {

    @TempDir
    lateinit var projectDir: Path

    @TempDir
    lateinit var userDir: Path

    private lateinit var registry: SkillRegistry
    private lateinit var manager: SkillManager

    private val reviewSkill = """
        |---
        |name: review
        |description: Review code changes
        |preferred-tools: [read_file, search_code]
        |---
        |Review the changes for ${'$'}ARGUMENTS
        |
        |Check file ${'$'}1 and module ${'$'}2 carefully.
    """.trimMargin()

    private val deploySkill = """
        |---
        |name: deploy
        |description: Deploy to staging
        |preferred-tools: [bamboo_trigger_build]
        |---
        |Deploy service ${'$'}ARGUMENTS to staging.
    """.trimMargin()

    @BeforeEach
    fun setup() {
        registry = SkillRegistry(projectDir.toString(), userDir.toString(), loadBuiltins = false)
        manager = SkillManager(registry)
        writeProjectSkill("review", reviewSkill)
        writeProjectSkill("deploy", deploySkill)
        registry.scan()
    }

    private fun writeProjectSkill(name: String, content: String) {
        val dir = File(projectDir.toFile(), ".workflow/skills/$name")
        dir.mkdirs()
        File(dir, "SKILL.md").writeText(content)
    }

    @Test
    fun `activateSkill loads content and fires callback`() {
        var activated: SkillManager.ActiveSkill? = null
        manager.onSkillActivated = { activated = it }

        val result = manager.activateSkill("review", "my-service")

        assertNotNull(result)
        assertEquals("review", result!!.entry.name)
        assertEquals("my-service", result.arguments)
        assertTrue(result.content.contains("Review the changes"))
        assertSame(result, activated)
        assertTrue(manager.isActive())
    }

    @Test
    fun `activateSkill substitutes ARGUMENTS`() {
        val result = manager.activateSkill("deploy", "payment-api")

        assertNotNull(result)
        assertTrue(result!!.content.contains("Deploy service payment-api to staging."))
        assertFalse(result.content.contains("\$ARGUMENTS"))
    }

    @Test
    fun `activateSkill substitutes positional arguments`() {
        val result = manager.activateSkill("review", "Main.kt core")

        assertNotNull(result)
        assertTrue(result!!.content.contains("Check file Main.kt and module core carefully."))
        assertFalse(result.content.contains("\$1"))
        assertFalse(result.content.contains("\$2"))
    }

    @Test
    fun `activateSkill without arguments leaves placeholders`() {
        val result = manager.activateSkill("review")

        assertNotNull(result)
        assertTrue(result!!.content.contains("\$ARGUMENTS"))
        assertTrue(result.content.contains("\$1"))
        assertTrue(result.content.contains("\$2"))
    }

    @Test
    fun `deactivateSkill clears state and fires callback`() {
        manager.activateSkill("review", "test")
        assertTrue(manager.isActive())

        var deactivated = false
        manager.onSkillDeactivated = { deactivated = true }

        manager.deactivateSkill()

        assertFalse(manager.isActive())
        assertNull(manager.activeSkill)
        assertTrue(deactivated)
    }

    @Test
    fun `activating new skill deactivates previous`() {
        manager.activateSkill("review", "test")
        assertEquals("review", manager.activeSkill?.entry?.name)

        var deactivated = false
        manager.onSkillDeactivated = { deactivated = true }

        manager.activateSkill("deploy", "svc")

        assertTrue(deactivated)
        assertEquals("deploy", manager.activeSkill?.entry?.name)
    }

    @Test
    fun `getPreferredTools returns active skill tools`() {
        manager.activateSkill("review")

        val tools = manager.getPreferredTools()
        assertEquals(setOf("read_file", "search_code"), tools)
    }

    @Test
    fun `getPreferredTools returns empty when no skill active`() {
        val tools = manager.getPreferredTools()
        assertTrue(tools.isEmpty())
    }

    @Test
    fun `activateSkill returns null for unknown skill`() {
        val result = manager.activateSkill("nonexistent")

        assertNull(result)
        assertFalse(manager.isActive())
    }
}
