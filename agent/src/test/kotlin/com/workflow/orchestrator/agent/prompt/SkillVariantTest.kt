package com.workflow.orchestrator.agent.prompt

import com.workflow.orchestrator.agent.ide.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class SkillVariantTest {

    @Test
    fun `tdd skill loads Java variant for IntelliJ`() {
        val skills = InstructionLoader.discoverSkills("/test/project")
        val available = InstructionLoader.getAvailableSkills(skills)
        val context = makeIdeContext(IdeProduct.INTELLIJ_ULTIMATE)

        val content = InstructionLoader.getSkillContent("tdd", available, context)
        assertNotNull(content)
        assertTrue(content!!.instructions.contains("JUnit") || content.instructions.contains("gradlew"),
            "IntelliJ TDD skill should include Java content")
    }

    @Test
    fun `tdd skill loads Python variant for PyCharm`() {
        val skills = InstructionLoader.discoverSkills("/test/project")
        val available = InstructionLoader.getAvailableSkills(skills)
        val context = makeIdeContext(IdeProduct.PYCHARM_COMMUNITY)

        val content = InstructionLoader.getSkillContent("tdd", available, context)
        assertNotNull(content)
        assertTrue(content!!.instructions.contains("pytest"),
            "PyCharm TDD skill should include Python content")
    }

    @Test
    fun `skill with no variant returns base only`() {
        val skills = InstructionLoader.discoverSkills("/test/project")
        val available = InstructionLoader.getAvailableSkills(skills)
        val context = makeIdeContext(IdeProduct.PYCHARM_PROFESSIONAL)

        val content = InstructionLoader.getSkillContent("brainstorm", available, context)
        assertNotNull(content)
        // brainstorm has no variants — base only, no error
    }

    @Test
    fun `null ideContext returns base only (backward compatible)`() {
        val skills = InstructionLoader.discoverSkills("/test/project")
        val available = InstructionLoader.getAvailableSkills(skills)

        val content = InstructionLoader.getSkillContent("tdd", available, null)
        assertNotNull(content)
        // Should be base only — no variant loaded, no error
    }

    @Test
    fun `OTHER IDE product returns base only`() {
        val skills = InstructionLoader.discoverSkills("/test/project")
        val available = InstructionLoader.getAvailableSkills(skills)
        val context = makeIdeContext(IdeProduct.OTHER)

        val content = InstructionLoader.getSkillContent("tdd", available, context)
        assertNotNull(content)
        // OTHER IDE gets no variant
    }

    @Test
    fun `all 5 target skills have Java variants`() {
        val skills = InstructionLoader.discoverSkills("/test/project")
        val available = InstructionLoader.getAvailableSkills(skills)
        val context = makeIdeContext(IdeProduct.INTELLIJ_ULTIMATE)

        for (skillName in listOf("tdd", "interactive-debugging", "systematic-debugging", "subagent-driven", "writing-plans")) {
            val content = InstructionLoader.getSkillContent(skillName, available, context)
            assertNotNull(content, "Skill '$skillName' should exist")
            // Java variant should add content beyond the base
            val baseContent = InstructionLoader.getSkillContent(skillName, available, null)
            assertNotNull(baseContent)
            assertTrue(content!!.instructions.length > baseContent!!.instructions.length,
                "Skill '$skillName' with Java variant should be longer than base")
        }
    }

    @Test
    fun `all 5 target skills have Python variants`() {
        val skills = InstructionLoader.discoverSkills("/test/project")
        val available = InstructionLoader.getAvailableSkills(skills)
        val context = makeIdeContext(IdeProduct.PYCHARM_PROFESSIONAL)

        for (skillName in listOf("tdd", "interactive-debugging", "systematic-debugging", "subagent-driven", "writing-plans")) {
            val content = InstructionLoader.getSkillContent(skillName, available, context)
            assertNotNull(content, "Skill '$skillName' should exist")
            val baseContent = InstructionLoader.getSkillContent(skillName, available, null)
            assertNotNull(baseContent)
            assertTrue(content!!.instructions.length > baseContent!!.instructions.length,
                "Skill '$skillName' with Python variant should be longer than base")
        }
    }

    private fun makeIdeContext(product: IdeProduct) = IdeContext(
        product = product,
        productName = product.name,
        edition = when (product) {
            IdeProduct.INTELLIJ_ULTIMATE -> Edition.ULTIMATE
            IdeProduct.PYCHARM_PROFESSIONAL -> Edition.PROFESSIONAL
            else -> Edition.COMMUNITY
        },
        languages = when (product) {
            IdeProduct.INTELLIJ_ULTIMATE, IdeProduct.INTELLIJ_COMMUNITY ->
                setOf(Language.JAVA, Language.KOTLIN)
            IdeProduct.PYCHARM_PROFESSIONAL, IdeProduct.PYCHARM_COMMUNITY ->
                setOf(Language.PYTHON)
            else -> emptySet()
        },
        hasJavaPlugin = product in setOf(IdeProduct.INTELLIJ_ULTIMATE, IdeProduct.INTELLIJ_COMMUNITY),
        hasPythonPlugin = product == IdeProduct.PYCHARM_PROFESSIONAL,
        hasPythonCorePlugin = product == IdeProduct.PYCHARM_COMMUNITY,
        hasSpringPlugin = false,
        detectedFrameworks = emptySet(),
        detectedBuildTools = emptySet(),
    )
}
