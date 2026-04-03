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

class SkillToolTest {

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
    fun `tool name is skill`() {
        val tool = SkillTool()
        assertEquals("skill", tool.name)
    }

    @Test
    fun `requires skill parameter`() {
        val tool = SkillTool()
        assertTrue(tool.parameters.required.contains("skill"))
    }

    @Test
    fun `has optional args parameter`() {
        val tool = SkillTool()
        assertTrue(tool.parameters.properties.containsKey("args"))
        assertFalse(tool.parameters.required.contains("args"))
    }

    @Test
    fun `only allowed for ORCHESTRATOR`() {
        val tool = SkillTool()
        assertEquals(setOf(WorkerType.ORCHESTRATOR), tool.allowedWorkers)
    }

    @Test
    fun `returns error when skill parameter is missing`() = runTest {
        val tool = SkillTool()
        val params = buildJsonObject { }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("'skill' parameter required"))
    }

    @Test
    fun `returns error when no skill manager available`() = runTest {
        every { agentService.currentSkillManager } returns null

        val tool = SkillTool()
        val params = buildJsonObject { put("skill", "test") }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("no skill manager"))
    }

    @Test
    fun `returns error when skill not found`() = runTest {
        every { agentService.currentSkillManager } returns skillManager
        every { registry.getSkill("nonexistent") } returns null

        val tool = SkillTool()
        val params = buildJsonObject { put("skill", "nonexistent") }

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

        val tool = SkillTool()
        val params = buildJsonObject { put("skill", "restricted") }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("disabled model invocation"))
    }

    @Test
    fun `returns full skill content on success`() = runTest {
        val skillContent = "# Review Workflow\n\n1. Read the code\n2. Check for issues\n3. Report findings"
        val entry = SkillRegistry.SkillEntry(
            name = "review",
            description = "Code review skill",
            disableModelInvocation = false,
            filePath = "/fake/path",
            scope = SkillRegistry.SkillScope.PROJECT
        )
        val activeSkill = SkillManager.ActiveSkill(entry, skillContent, null)

        every { agentService.currentSkillManager } returns skillManager
        every { registry.getSkill("review") } returns entry
        every { registry.getSkillContent("review") } returns skillContent

        val tool = SkillTool()
        val params = buildJsonObject { put("skill", "review") }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertEquals(skillContent, result.content)
        assertEquals("Loaded skill: review", result.summary)
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

        val tool = SkillTool()
        val params = buildJsonObject {
            put("skill", "deploy")
            put("args", "staging")
        }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("Deploy to staging"))
    }

    @Test
    fun `activates skill via skill manager callback`() = runTest {
        val skillContent = "Skill instructions"
        val entry = SkillRegistry.SkillEntry(
            name = "debug",
            description = "Debug skill",
            disableModelInvocation = false,
            filePath = "/fake/path",
            scope = SkillRegistry.SkillScope.BUILTIN
        )

        every { agentService.currentSkillManager } returns skillManager
        every { registry.getSkill("debug") } returns entry
        every { registry.getSkillContent("debug") } returns skillContent

        val tool = SkillTool()
        val params = buildJsonObject { put("skill", "debug") }

        tool.execute(params, project)

        // Verify activateSkill was called (which sets the anchor via callback)
        verify { skillManager.activateSkill("debug", null) }
    }

    @Test
    fun `forked skill returns error when agent service unavailable`() = runTest {
        val entry = SkillRegistry.SkillEntry(
            name = "forked-skill",
            description = "A forked skill",
            disableModelInvocation = false,
            contextFork = true,
            filePath = "/fake/path",
            scope = SkillRegistry.SkillScope.PROJECT
        )

        every { agentService.currentSkillManager } returns skillManager
        every { registry.getSkill("forked-skill") } returns entry
        every { registry.getSkillContent("forked-skill") } returns "Forked content"

        // First call to AgentService.getInstance succeeds (returns skillManager via agentService),
        // second call in executeForked() throws to simulate unavailability in forked path
        every { AgentService.getInstance(project) } returns agentService andThenThrows IllegalStateException("not available")

        val tool = SkillTool()
        val params = buildJsonObject { put("skill", "forked-skill") }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("agent service not available") || result.content.contains("not available"))
    }

    @Test
    fun `forked skill returns error when content fails to load`() = runTest {
        val entry = SkillRegistry.SkillEntry(
            name = "forked-broken",
            description = "A broken forked skill",
            disableModelInvocation = false,
            contextFork = true,
            filePath = "/nonexistent/path",
            scope = SkillRegistry.SkillScope.PROJECT
        )

        every { agentService.currentSkillManager } returns skillManager
        every { registry.getSkill("forked-broken") } returns entry
        every { registry.getSkillContent("forked-broken") } returns null

        val tool = SkillTool()
        val params = buildJsonObject { put("skill", "forked-broken") }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("could not load skill content"))
    }

    @Test
    fun `returns error when skill content fails to load`() = runTest {
        val entry = SkillRegistry.SkillEntry(
            name = "broken",
            description = "Broken skill",
            disableModelInvocation = false,
            filePath = "/nonexistent/path",
            scope = SkillRegistry.SkillScope.PROJECT
        )

        every { agentService.currentSkillManager } returns skillManager
        every { registry.getSkill("broken") } returns entry
        every { registry.getSkillContent("broken") } returns null

        val tool = SkillTool()
        val params = buildJsonObject { put("skill", "broken") }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("failed to load"))
    }
}
