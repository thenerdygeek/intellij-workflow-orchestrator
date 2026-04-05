package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.WorkerType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class UseSkillToolTest {

    private val project = mockk<Project>(relaxed = true).apply {
        // UseSkillTool calls project.basePath for skill discovery
        every { basePath } returns System.getProperty("java.io.tmpdir")
    }
    private val tool = UseSkillTool()

    @Test
    fun `tool name is use_skill`() {
        assertEquals("use_skill", tool.name)
    }

    @Test
    fun `skill_name is required parameter`() {
        assertTrue(tool.parameters.required.contains("skill_name"))
    }

    @Test
    fun `allowedWorkers contains only ORCHESTRATOR`() {
        assertEquals(setOf(WorkerType.ORCHESTRATOR), tool.allowedWorkers)
    }

    @Test
    fun `description matches Cline use_skill tool spec`() {
        // Port of Cline's description from use_skill.ts
        assertTrue(tool.description.contains("Load and activate a skill by name"))
        assertTrue(tool.description.contains("ONCE"))
        assertTrue(tool.description.contains("do not call use_skill again"))
    }

    @Test
    fun `loads and returns skill content for known bundled skill`() = runTest {
        val result = tool.execute(buildJsonObject {
            put("skill_name", "tdd")
        }, project)

        assertFalse(result.isError, "should not be an error for known skill")
        assertTrue(result.isSkillActivation, "should be a skill activation")
        assertEquals("tdd", result.activatedSkillName)
        assertNotNull(result.activatedSkillContent)
        // Port of Cline's response format: "# Skill "X" is now active"
        assertTrue(result.content.contains("# Skill \"tdd\" is now active"))
        assertTrue(result.content.contains("IMPORTANT: The skill is now loaded"))
        assertTrue(result.activatedSkillContent!!.contains("Test-Driven"), "should contain TDD skill content")
    }

    @Test
    fun `errors on unknown skill name with available list`() = runTest {
        val result = tool.execute(buildJsonObject {
            put("skill_name", "nonexistent-skill-xyz")
        }, project)

        assertTrue(result.isError, "should error on unknown skill")
        assertFalse(result.isSkillActivation)
        assertNull(result.activatedSkillName)
        // Port of Cline: error includes available skill names
        assertTrue(result.content.contains("not found"), "error should say not found")
        assertTrue(result.content.contains("Available skills:"), "error should list available skills")
    }

    @Test
    fun `missing skill_name returns error matching Cline format`() = runTest {
        val result = tool.execute(buildJsonObject {}, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("Missing required parameter 'skill_name'"))
    }

    @Test
    fun `loads brainstorm skill with Cline response format`() = runTest {
        val result = tool.execute(buildJsonObject {
            put("skill_name", "brainstorm")
        }, project)

        assertFalse(result.isError)
        assertTrue(result.isSkillActivation)
        assertEquals("brainstorm", result.activatedSkillName)
        assertTrue(result.content.contains("# Skill \"brainstorm\" is now active"))
        assertTrue(result.activatedSkillContent!!.contains("Brainstorming"), "should contain brainstorm content")
    }

    @Test
    fun `response includes skill directory path`() = runTest {
        val result = tool.execute(buildJsonObject {
            put("skill_name", "tdd")
        }, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("You may access other files in the skill directory at:"))
    }
}
