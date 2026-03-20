package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.runtime.SkillManager
import com.workflow.orchestrator.agent.runtime.SkillRegistry
import com.workflow.orchestrator.agent.runtime.WorkerType
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ActivateSkillToolTest {

    private lateinit var project: Project
    private lateinit var agentService: AgentService
    private lateinit var registry: SkillRegistry
    private lateinit var skillManager: SkillManager

    @BeforeEach
    fun setUp() {
        project = mockk<Project>(relaxed = true)
        agentService = mockk<AgentService>(relaxed = true)
        registry = mockk<SkillRegistry>(relaxed = true)
        skillManager = spyk(SkillManager(registry))

        mockkStatic(AgentService::class)
        every { AgentService.getInstance(project) } returns agentService
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(AgentService::class)
    }

    @Test
    fun `tool metadata is correct`() {
        val tool = ActivateSkillTool()
        assertEquals("activate_skill", tool.name)
        assertTrue(tool.parameters.required.contains("name"))
        assertEquals(setOf(WorkerType.ORCHESTRATOR), tool.allowedWorkers)
    }

    @Test
    fun `description mentions skills`() {
        val tool = ActivateSkillTool()
        assertTrue(tool.description.contains("skill", ignoreCase = true))
    }

    @Test
    fun `returns error when name is missing`() = runTest {
        val tool = ActivateSkillTool()
        val params = buildJsonObject { }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("'name' parameter required"))
    }

    @Test
    fun `returns error when skill not found`() = runTest {
        every { agentService.currentSkillManager } returns skillManager
        every { registry.getSkill("nonexistent") } returns null

        val tool = ActivateSkillTool()
        val params = buildJsonObject { put("name", "nonexistent") }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("not found"))
    }

    @Test
    fun `returns error when skill has disabled model invocation`() = runTest {
        val entry = SkillRegistry.SkillEntry(
            name = "restricted",
            description = "A restricted skill",
            disableModelInvocation = true,
            filePath = "/fake/path",
            scope = SkillRegistry.SkillScope.PROJECT
        )
        every { agentService.currentSkillManager } returns skillManager
        every { registry.getSkill("restricted") } returns entry

        val tool = ActivateSkillTool()
        val params = buildJsonObject { put("name", "restricted") }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("disabled model invocation"))
    }

    @Test
    fun `returns error when no skill manager available`() = runTest {
        every { agentService.currentSkillManager } returns null

        val tool = ActivateSkillTool()
        val params = buildJsonObject { put("name", "test") }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("no skill manager"))
    }

    @Test
    fun `activates skill successfully`() = runTest {
        val entry = SkillRegistry.SkillEntry(
            name = "review",
            description = "Code review skill",
            disableModelInvocation = false,
            filePath = "/fake/path",
            scope = SkillRegistry.SkillScope.PROJECT
        )
        val activeSkill = SkillManager.ActiveSkill(entry, "Review content", null)

        every { agentService.currentSkillManager } returns skillManager
        every { registry.getSkill("review") } returns entry
        every { registry.getSkillContent("review") } returns "Review content"

        val tool = ActivateSkillTool()
        val params = buildJsonObject { put("name", "review") }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("Skill 'review' activated"))
    }

    @Test
    fun `passes arguments to skill manager`() = runTest {
        val entry = SkillRegistry.SkillEntry(
            name = "deploy",
            description = "Deploy skill",
            disableModelInvocation = false,
            filePath = "/fake/path",
            scope = SkillRegistry.SkillScope.PROJECT
        )

        every { agentService.currentSkillManager } returns skillManager
        every { registry.getSkill("deploy") } returns entry
        every { registry.getSkillContent("deploy") } returns "Deploy to \$ARGUMENTS"

        val tool = ActivateSkillTool()
        val params = buildJsonObject {
            put("name", "deploy")
            put("arguments", "staging")
        }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("Skill 'deploy' activated"))
    }

    @Test
    fun `deactivate tool metadata is correct`() {
        val tool = DeactivateSkillTool()
        assertEquals("deactivate_skill", tool.name)
        assertTrue(tool.parameters.required.isEmpty())
        assertTrue(tool.parameters.properties.isEmpty())
        assertEquals(setOf(WorkerType.ORCHESTRATOR), tool.allowedWorkers)
    }

    @Test
    fun `deactivate calls skill manager`() = runTest {
        every { agentService.currentSkillManager } returns skillManager

        val tool = DeactivateSkillTool()
        val params = buildJsonObject { }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertEquals("Skill deactivated.", result.content)
        verify { skillManager.deactivateSkill() }
    }

    @Test
    fun `deactivate returns error when no skill manager`() = runTest {
        every { agentService.currentSkillManager } returns null

        val tool = DeactivateSkillTool()
        val params = buildJsonObject { }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("no skill manager"))
    }
}
