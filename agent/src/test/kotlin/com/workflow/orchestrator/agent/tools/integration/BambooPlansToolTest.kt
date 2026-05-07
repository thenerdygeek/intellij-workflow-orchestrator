package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.core.model.bamboo.ProjectData
import com.workflow.orchestrator.core.services.BambooService
import com.workflow.orchestrator.core.services.ToolResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BambooPlansToolTest {
    private val project = mockk<Project>(relaxed = true)
    private val tool = BambooPlansTool()

    @Test
    fun `tool name is bamboo_plans`() {
        assertEquals("bamboo_plans", tool.name)
    }

    @Test
    fun `action enum contains all 10 actions`() {
        val actions = tool.parameters.properties["action"]?.enumValues
        assertNotNull(actions)
        assertEquals(10, actions!!.size)
        assertTrue("auto_detect_plan" in actions)
        assertTrue("get_plans" in actions)
        assertTrue("get_project_plans" in actions)
        assertTrue("search_plans" in actions)
        assertTrue("get_plan_branches" in actions)
        assertTrue("get_build_variables" in actions)
        assertTrue("get_plan_variables" in actions)
        assertTrue("rerun_failed_jobs" in actions)
        assertTrue("trigger_stage" in actions)
        assertTrue("get_projects" in actions)
    }

    @Test
    fun `only action is required`() {
        assertEquals(listOf("action"), tool.parameters.required)
    }

    @Test
    fun `allowedWorkers includes TOOLER and ORCHESTRATOR`() {
        assertEquals(
            setOf(WorkerType.TOOLER, WorkerType.ORCHESTRATOR),
            tool.allowedWorkers
        )
    }

    @Test
    fun `toToolDefinition produces valid schema`() {
        val def = tool.toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("bamboo_plans", def.function.name)
        assertEquals("object", def.function.parameters.type)
        assertTrue(def.function.description.isNotBlank())
    }

    @Test
    fun `missing action returns error`() = runTest {
        val result = tool.execute(buildJsonObject { }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("action"))
    }

    @Test
    fun `unknown action returns error`() = runTest {
        val result = tool.execute(buildJsonObject { put("action", "nonexistent") }, project)
        assertTrue(result.isError)
    }

    // ── get_projects ──────────────────────────────────────────────────────────

    @Test
    fun `get_projects returns list of Bamboo projects`() = runTest {
        val service = mockk<BambooService>()
        coEvery { service.getProjects() } returns ToolResult(
            data = listOf(ProjectData(key = "PROJ", name = "My Project", description = "A project")),
            summary = "1 project(s)",
            isError = false
        )

        val params = buildJsonObject { put("action", "get_projects") }
        val result = tool.executeWithService(params, service)

        assertFalse(result.isError)
        assertTrue(result.content.contains("PROJ"), "content must reference the project key")
        coVerify(exactly = 1) { service.getProjects() }
    }

    // ── auto_detect_plan routing ──────────────────────────────────────────────

    @Test
    fun `auto_detect_plan with no extra args calls 1-arg overload`() = runTest {
        val service = mockk<BambooService>()
        coEvery { service.autoDetectPlan("git@example.com:foo/bar.git") } returns ToolResult(
            data = "FOO-BAR",
            summary = "Detected plan: FOO-BAR",
            isError = false
        )

        val params = buildJsonObject {
            put("action", "auto_detect_plan")
            put("git_remote_url", "git@example.com:foo/bar.git")
        }
        tool.executeWithService(params, service)

        coVerify(exactly = 1) { service.autoDetectPlan("git@example.com:foo/bar.git") }
        coVerify(exactly = 0) { service.autoDetectPlan(repoRoot = any(), remoteUrl = any(), branchName = any(), preferredMaster = any()) }
    }

    @Test
    fun `auto_detect_plan with all 5-tier params routes to 5-tier overload`() = runTest {
        val service = mockk<BambooService>()
        coEvery {
            service.autoDetectPlan(
                repoRoot = java.nio.file.Paths.get("/home/user/repo"),
                remoteUrl = "git@example.com:foo/bar.git",
                branchName = "feature/X",
                preferredMaster = "main"
            )
        } returns ToolResult(
            data = "FOO-BAR",
            summary = "Detected plan: FOO-BAR",
            isError = false
        )

        val params = buildJsonObject {
            put("action", "auto_detect_plan")
            put("git_remote_url", "git@example.com:foo/bar.git")
            put("repo_root", "/home/user/repo")
            put("branch_name", "feature/X")
            put("preferred_master", "main")
        }
        tool.executeWithService(params, service)

        coVerify(exactly = 1) {
            service.autoDetectPlan(
                repoRoot = java.nio.file.Paths.get("/home/user/repo"),
                remoteUrl = "git@example.com:foo/bar.git",
                branchName = "feature/X",
                preferredMaster = "main"
            )
        }
        coVerify(exactly = 0) { service.autoDetectPlan(any<String>()) }
    }

    @Test
    fun `auto_detect_plan with partial 5-tier args still routes to 5-tier overload`() = runTest {
        val service = mockk<BambooService>()
        coEvery {
            service.autoDetectPlan(
                repoRoot = java.nio.file.Paths.get("/r"),
                remoteUrl = "x",
                branchName = null,
                preferredMaster = null
            )
        } returns ToolResult(
            data = "FOO-BAR",
            summary = "Detected plan: FOO-BAR",
            isError = false
        )

        val params = buildJsonObject {
            put("action", "auto_detect_plan")
            put("git_remote_url", "x")
            put("repo_root", "/r")
        }
        tool.executeWithService(params, service)

        coVerify(exactly = 1) {
            service.autoDetectPlan(
                repoRoot = java.nio.file.Paths.get("/r"),
                remoteUrl = "x",
                branchName = null,
                preferredMaster = null
            )
        }
        coVerify(exactly = 0) { service.autoDetectPlan(any<String>()) }
    }
}
