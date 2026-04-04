package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.WorkerType
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class UseSkillToolTest {

    private val project = mockk<Project>(relaxed = true)
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
    fun `loads and returns skill content for known skill`() = runTest {
        val result = tool.execute(buildJsonObject {
            put("skill_name", "tdd")
        }, project)

        assertFalse(result.isError, "should not be an error for known skill")
        assertTrue(result.isSkillActivation, "should be a skill activation")
        assertEquals("tdd", result.activatedSkillName)
        assertNotNull(result.activatedSkillContent)
        assertTrue(result.content.contains("tdd"), "content should mention skill name")
        assertTrue(result.activatedSkillContent!!.contains("Test-Driven"), "should contain TDD skill content")
    }

    @Test
    fun `errors on unknown skill name`() = runTest {
        val result = tool.execute(buildJsonObject {
            put("skill_name", "nonexistent-skill-xyz")
        }, project)

        assertTrue(result.isError, "should error on unknown skill")
        assertFalse(result.isSkillActivation)
        assertNull(result.activatedSkillName)
        assertTrue(result.content.contains("Unknown skill"), "error should mention unknown skill")
    }

    @Test
    fun `missing skill_name returns error`() = runTest {
        val result = tool.execute(buildJsonObject {}, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("Missing required parameter"))
    }

    @Test
    fun `loads brainstorm skill`() = runTest {
        val result = tool.execute(buildJsonObject {
            put("skill_name", "brainstorm")
        }, project)

        assertFalse(result.isError)
        assertTrue(result.isSkillActivation)
        assertEquals("brainstorm", result.activatedSkillName)
        assertTrue(result.activatedSkillContent!!.contains("Brainstorming"), "should contain brainstorm content")
    }

    @Test
    fun `description mentions SKILLS section and exact name`() {
        assertTrue(tool.description.contains("SKILLS"), "description should mention SKILLS section")
        assertTrue(tool.description.contains("exactly"), "description should mention exact name matching")
    }
}
