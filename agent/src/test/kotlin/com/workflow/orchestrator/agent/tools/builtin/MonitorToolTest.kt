package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.monitor.MonitorEvent
import com.workflow.orchestrator.agent.monitor.MonitorHandle
import com.workflow.orchestrator.agent.monitor.MonitorPersistence
import com.workflow.orchestrator.agent.monitor.MonitorPool
import com.workflow.orchestrator.agent.monitor.MonitorSource
import com.workflow.orchestrator.agent.monitor.MonitorSpec
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MonitorToolTest {

    private fun tool() = MonitorTool(sessionIdProvider = { "s1" }, cs = CoroutineScope(SupervisorJob()))

    private fun handleWith(id: String, label: String, vararg lines: String): MonitorHandle {
        val src = object : MonitorSource {
            override val monitorId = id
            override val description = label
            override fun start(emit: (MonitorEvent) -> Unit) {}
            override fun stop() {}
        }
        return MonitorHandle(src, sessionId = "s1", startedAt = 0L).also { h -> lines.forEach { h.appendLine(it) } }
    }

    @Test
    fun `renderStatus shows id label state and buffered event lines`() {
        val s = MonitorTool.renderStatus(handleWith("shell-abc", "watch build", "line1 ERROR", "line2 done"))
        assertTrue(s.contains("shell-abc"), "should show id: $s")
        assertTrue(s.contains("watch build"), "should show label: $s")
        assertTrue(s.contains("RUNNING"), "should show state: $s")
        assertTrue(s.contains("line1 ERROR"), "should show buffered events: $s")
        assertTrue(s.contains("line2 done"), "should show buffered events: $s")
    }

    @Test
    fun `renderStatus notes when no events have matched yet`() {
        val s = MonitorTool.renderStatus(handleWith("shell-xyz", "watch"))
        assertTrue(s.contains("shell-xyz"), "should show id: $s")
        assertTrue(s.contains("no matching events", ignoreCase = true), "should note empty buffer: $s")
    }

    @Test
    fun `status is an allowed action value in the schema`() {
        val enum = tool().parameters.properties["action"]?.enumValues
        assertTrue(enum != null && "status" in enum, "action enum should include 'status', was: $enum")
    }

    @Test
    fun `filter description documents case sensitivity`() {
        val desc = tool().parameters.properties["filter"]?.description ?: ""
        assertTrue(desc.contains("(?i)"), "filter desc should document case-insensitive (?i) prefix: $desc")
    }

    @Test
    fun `start requires command and filter for shell source`() {
        val err = MonitorTool.validateStart(source = "shell", command = null, filter = "ERROR")
        assertTrue(err!!.contains("command"))
    }

    @Test
    fun `start rejects an unknown source`() {
        val err = MonitorTool.validateStart(source = "kafka", command = "x", filter = "y")
        assertTrue(err!!.contains("not supported"))
    }

    @Test
    fun `source enum includes bamboo`() {
        val enum = tool().parameters.properties["source"]?.enumValues
        assertTrue(enum != null && "bamboo" in enum, "source enum should include 'bamboo', was: $enum")
    }

    @Test
    fun `source enum includes pull_request`() {
        val enum = tool().parameters.properties["source"]?.enumValues
        assertTrue(enum != null && "pull_request" in enum, "source enum should include 'pull_request', was: $enum")
    }

    // ---- validateBambooStart -------------------------------------------------

    @Test
    fun `validateBambooStart missing planKey returns error`() {
        val err = MonitorTool.validateBambooStart(planKey = null, level = "build", stageName = null, jobName = null)
        assertTrue(err != null && err.contains("plan_key"), "expected plan_key error, got: $err")
    }

    @Test
    fun `validateBambooStart bad level returns error`() {
        val err = MonitorTool.validateBambooStart(planKey = "PROJ-PLAN", level = "sprint", stageName = null, jobName = null)
        assertTrue(err != null && (err.contains("level") || err.contains("build") || err.contains("stage") || err.contains("job")),
            "expected level error, got: $err")
    }

    @Test
    fun `validateBambooStart level stage without stage_name returns error`() {
        val err = MonitorTool.validateBambooStart(planKey = "PROJ-PLAN", level = "stage", stageName = null, jobName = null)
        assertTrue(err != null && err.contains("stage_name"), "expected stage_name error, got: $err")
    }

    @Test
    fun `validateBambooStart level job without stage_name and job_name returns error`() {
        val err = MonitorTool.validateBambooStart(planKey = "PROJ-PLAN", level = "job", stageName = null, jobName = null)
        assertTrue(err != null, "expected error for job without stage_name and job_name")
    }

    @Test
    fun `validateBambooStart level job with stage_name but no job_name returns job_name error`() {
        val err = MonitorTool.validateBambooStart(planKey = "P", level = "job", stageName = "s", jobName = null)
        assertTrue(err != null && err.contains("job_name"), "expected job_name-required error, got: $err")
    }

    @Test
    fun `validateBambooStart valid build returns null`() {
        val err = MonitorTool.validateBambooStart(planKey = "PROJ-PLAN", level = "build", stageName = null, jobName = null)
        assertEquals(null, err, "valid build params should pass, got: $err")
    }

    @Test
    fun `validateBambooStart null level defaults to build and passes`() {
        val err = MonitorTool.validateBambooStart(planKey = "PROJ-PLAN", level = null, stageName = null, jobName = null)
        assertEquals(null, err, "null level should default to build (valid), got: $err")
    }

    @Test
    fun `valid shell start passes validation`() {
        assertEquals(null, MonitorTool.validateStart(source = "shell", command = "tail -f log", filter = "ERROR"))
    }

    @Test
    fun `invalid regex filter is reported`() {
        val err = MonitorTool.validateStart(source = "shell", command = "tail -f log", filter = "(")
        assertTrue(err!!.contains("regex"))
    }

    @Test
    fun `renderStatus shows EXITED state and exit code for a finished monitor`() {
        val h = handleWith("shell-z", "watch build", "compiling…", "BUILD FAILED")
        h.markExited(1)
        val s = MonitorTool.renderStatus(h)
        assertTrue(s.contains("EXITED"), "status should show EXITED state: $s")
        assertTrue(s.contains("code=1") || s.contains("(1)"), "status should surface the exit code: $s")
        assertTrue(s.contains("BUILD FAILED"), "status should still show buffered matched lines: $s")
    }

    @Test
    fun `renderStatus shows code=0 for a clean exit`() {
        val h = handleWith("shell-ok", "watch build", "BUILD SUCCESSFUL")
        h.markExited(0)
        val s = MonitorTool.renderStatus(h)
        assertTrue(s.contains("code=0"), "status should surface a clean exit code: $s")
    }

    @Test
    fun `renderStatus handles unknown (null) exit code`() {
        val h = handleWith("shell-unknown", "watch build", "something")
        h.markExited(null)
        val s = MonitorTool.renderStatus(h)
        assertTrue(s.contains("EXITED"), "status should render EXITED without crashing on a null code: $s")
    }

    // ---- validatePullRequestStart --------------------------------------------

    @Test
    fun `validatePullRequestStart missing pr_id returns error`() {
        val err = MonitorTool.validatePullRequestStart(prIdRaw = null, aspects = null)
        assertTrue(err != null && err.contains("pr_id"), "expected pr_id error, got: $err")
    }

    @Test
    fun `validatePullRequestStart blank pr_id returns error`() {
        val err = MonitorTool.validatePullRequestStart(prIdRaw = "", aspects = null)
        assertTrue(err != null && err.contains("pr_id"), "expected pr_id error for blank, got: $err")
    }

    @Test
    fun `validatePullRequestStart non-numeric pr_id returns error`() {
        val err = MonitorTool.validatePullRequestStart(prIdRaw = "abc", aspects = null)
        assertTrue(err != null && err.contains("pr_id"), "expected numeric pr_id error, got: $err")
    }

    @Test
    fun `validatePullRequestStart unknown aspect token returns error naming the bad token`() {
        val err = MonitorTool.validatePullRequestStart(prIdRaw = "42", aspects = "state,bogus")
        assertTrue(err != null && err.contains("bogus"), "expected error naming the bad aspect token, got: $err")
    }

    @Test
    fun `validatePullRequestStart valid pr_id with no aspects returns null`() {
        val err = MonitorTool.validatePullRequestStart(prIdRaw = "42", aspects = null)
        assertEquals(null, err, "valid numeric pr_id with no aspects should pass, got: $err")
    }

    @Test
    fun `validatePullRequestStart valid pr_id with valid aspects returns null`() {
        val err = MonitorTool.validatePullRequestStart(prIdRaw = "42", aspects = "state,reviews,comments")
        assertEquals(null, err, "valid pr_id + known aspects should pass, got: $err")
    }

    @Test
    fun `validatePullRequestStart valid pr_id with single valid aspect returns null`() {
        val err = MonitorTool.validatePullRequestStart(prIdRaw = "7", aspects = "reviews")
        assertEquals(null, err, "valid pr_id + single known aspect should pass, got: $err")
    }

    @Test
    fun `source enum includes jira_ticket`() {
        val enum = tool().parameters.properties["source"]?.enumValues
        assertTrue(enum != null && "jira_ticket" in enum, "source enum should include 'jira_ticket', was: $enum")
    }

    // ---- validateJiraTicketStart --------------------------------------------

    @Test
    fun `validateJiraTicketStart missing ticket_key returns error`() {
        val err = MonitorTool.validateJiraTicketStart(ticketKey = null)
        assertTrue(err != null && err.contains("ticket_key"), "expected ticket_key error, got: $err")
    }

    @Test
    fun `validateJiraTicketStart blank ticket_key returns error`() {
        val err = MonitorTool.validateJiraTicketStart(ticketKey = "")
        assertTrue(err != null && err.contains("ticket_key"), "expected ticket_key error for blank, got: $err")
    }

    @Test
    fun `validateJiraTicketStart whitespace-only ticket_key returns error`() {
        val err = MonitorTool.validateJiraTicketStart(ticketKey = "   ")
        assertTrue(err != null && err.contains("ticket_key"), "expected ticket_key error for whitespace, got: $err")
    }

    @Test
    fun `validateJiraTicketStart valid key returns null`() {
        val err = MonitorTool.validateJiraTicketStart(ticketKey = "PROJ-123")
        assertEquals(null, err, "valid ticket key should pass, got: $err")
    }

    @Test
    fun `validateJiraTicketStart any non-blank key is accepted`() {
        val err = MonitorTool.validateJiraTicketStart(ticketKey = "ABC-1")
        assertEquals(null, err, "any non-blank key should pass, got: $err")
    }

    @Test
    fun `source enum includes jira_sprint`() {
        val enum = tool().parameters.properties["source"]?.enumValues
        assertTrue(enum != null && "jira_sprint" in enum, "source enum should include 'jira_sprint', was: $enum")
    }

    // ---- validateJiraSprintStart --------------------------------------------

    @Test
    fun `validateJiraSprintStart neither board_id nor sprint_id returns error`() {
        val err = MonitorTool.validateJiraSprintStart(boardIdRaw = null, sprintIdRaw = null)
        assertTrue(err != null, "expected error when neither id is provided, got: $err")
        assertTrue(err!!.contains("board_id") || err.contains("sprint_id"),
            "error should mention at least one of board_id/sprint_id: $err")
    }

    @Test
    fun `validateJiraSprintStart both blank returns error`() {
        val err = MonitorTool.validateJiraSprintStart(boardIdRaw = "", sprintIdRaw = "")
        assertTrue(err != null, "expected error when both are blank, got: $err")
    }

    @Test
    fun `validateJiraSprintStart non-numeric board_id returns error`() {
        val err = MonitorTool.validateJiraSprintStart(boardIdRaw = "abc", sprintIdRaw = null)
        assertTrue(err != null && err.contains("board_id"), "expected numeric board_id error, got: $err")
    }

    @Test
    fun `validateJiraSprintStart non-numeric sprint_id returns error`() {
        val err = MonitorTool.validateJiraSprintStart(boardIdRaw = null, sprintIdRaw = "notanumber")
        assertTrue(err != null && err.contains("sprint_id"), "expected numeric sprint_id error, got: $err")
    }

    @Test
    fun `validateJiraSprintStart valid board_id only returns null`() {
        val err = MonitorTool.validateJiraSprintStart(boardIdRaw = "5", sprintIdRaw = null)
        assertEquals(null, err, "valid numeric board_id only should pass, got: $err")
    }

    @Test
    fun `validateJiraSprintStart valid sprint_id only returns null`() {
        val err = MonitorTool.validateJiraSprintStart(boardIdRaw = null, sprintIdRaw = "42")
        assertEquals(null, err, "valid numeric sprint_id only should pass, got: $err")
    }

    @Test
    fun `validateJiraSprintStart valid board_id and sprint_id both present returns null`() {
        val err = MonitorTool.validateJiraSprintStart(boardIdRaw = "7", sprintIdRaw = "100")
        assertEquals(null, err, "valid numeric board_id and sprint_id should pass, got: $err")
    }

    @Test
    fun `source enum includes sonar_gate`() {
        val enum = tool().parameters.properties["source"]?.enumValues
        assertTrue(enum != null && "sonar_gate" in enum, "source enum should include 'sonar_gate', was: $enum")
    }

    // ---- validateSonarGateStart --------------------------------------------

    @Test
    fun `validateSonarGateStart missing project_key returns error`() {
        val err = MonitorTool.validateSonarGateStart(projectKey = null)
        assertTrue(err != null && err.contains("project_key"), "expected project_key error, got: $err")
    }

    @Test
    fun `validateSonarGateStart blank project_key returns error`() {
        val err = MonitorTool.validateSonarGateStart(projectKey = "")
        assertTrue(err != null && err.contains("project_key"), "expected project_key error for blank, got: $err")
    }

    @Test
    fun `validateSonarGateStart whitespace-only project_key returns error`() {
        val err = MonitorTool.validateSonarGateStart(projectKey = "   ")
        assertTrue(err != null && err.contains("project_key"), "expected project_key error for whitespace, got: $err")
    }

    @Test
    fun `validateSonarGateStart valid project_key returns null`() {
        val err = MonitorTool.validateSonarGateStart(projectKey = "my-sonar-project")
        assertEquals(null, err, "valid project key should pass, got: $err")
    }

    @Test
    fun `validateSonarGateStart any non-blank key is accepted`() {
        val err = MonitorTool.validateSonarGateStart(projectKey = "com.example:my-app")
        assertEquals(null, err, "any non-blank key should pass, got: $err")
    }

    @Test
    fun `source enum includes sonar_issues`() {
        val enum = tool().parameters.properties["source"]?.enumValues
        assertTrue(enum != null && "sonar_issues" in enum, "source enum should include 'sonar_issues', was: $enum")
    }

    // ---- validateSonarIssuesStart -------------------------------------------

    @Test
    fun `validateSonarIssuesStart missing project_key returns error`() {
        val err = MonitorTool.validateSonarIssuesStart(projectKey = null, minSeverity = null)
        assertTrue(err != null && err.contains("project_key"), "expected project_key error, got: $err")
    }

    @Test
    fun `validateSonarIssuesStart blank project_key returns error`() {
        val err = MonitorTool.validateSonarIssuesStart(projectKey = "", minSeverity = null)
        assertTrue(err != null && err.contains("project_key"), "expected project_key error for blank, got: $err")
    }

    @Test
    fun `validateSonarIssuesStart whitespace-only project_key returns error`() {
        val err = MonitorTool.validateSonarIssuesStart(projectKey = "   ", minSeverity = null)
        assertTrue(err != null && err.contains("project_key"), "expected project_key error for whitespace, got: $err")
    }

    @Test
    fun `validateSonarIssuesStart valid project_key with no min_severity returns null`() {
        val err = MonitorTool.validateSonarIssuesStart(projectKey = "my-sonar-project", minSeverity = null)
        assertEquals(null, err, "valid project key with no min_severity should pass, got: $err")
    }

    @Test
    fun `validateSonarIssuesStart valid project_key with valid min_severity returns null`() {
        for (sev in listOf("INFO", "MINOR", "MAJOR", "CRITICAL", "BLOCKER")) {
            val err = MonitorTool.validateSonarIssuesStart(projectKey = "proj", minSeverity = sev)
            assertEquals(null, err, "valid project key + severity $sev should pass, got: $err")
        }
    }

    @Test
    fun `validateSonarIssuesStart invalid min_severity returns error`() {
        val err = MonitorTool.validateSonarIssuesStart(projectKey = "proj", minSeverity = "UNKNOWN")
        assertTrue(err != null && err.contains("UNKNOWN"), "expected error for invalid severity, got: $err")
    }

    @Test
    fun `validateSonarIssuesStart blank min_severity is treated as absent (returns null)`() {
        val err = MonitorTool.validateSonarIssuesStart(projectKey = "proj", minSeverity = "")
        assertEquals(null, err, "blank min_severity should be treated as absent, got: $err")
    }

    // ─── persist-assertion tests ───────────────────────────────────────────────
    //
    // These tests inject a recording MonitorPersistence mock and verify that:
    // (A) MonitorPersistence.add(sessionId, spec) is called when a shell monitor starts
    //     successfully, and the spec.id matches the id returned in the tool result.
    // (B) MonitorPersistence.remove(sessionId, id) is called when action=stop succeeds.
    //
    // We stub both `project.getService(MonitorPool::class.java)` and
    // `project.getService(AgentService::class.java)` so the test runs headless (no
    // IntelliJ Application required — the project mock itself services all calls).

    @Test
    fun `persist - MonitorPersistence add is called on successful shell monitor start with matching id`() = runTest {
        val persistence = mockk<MonitorPersistence>(relaxed = true)
        val pool = mockk<MonitorPool>(relaxed = true)
        val agentService = mockk<AgentService>(relaxed = true)

        coEvery { pool.register(any(), any()) } returns Unit   // no MaxConcurrentReached

        val project = mockk<Project>(relaxed = true) {
            every { getService(MonitorPool::class.java)  } returns pool
            every { getService(AgentService::class.java) } returns agentService
            every { basePath } returns "/tmp/test-project"
        }

        val specSlot = slot<MonitorSpec>()
        every { persistence.add("s1", capture(specSlot)) } returns Unit

        val tool = MonitorTool(
            sessionIdProvider = { "s1" },
            cs = CoroutineScope(SupervisorJob()),
            monitorPersistenceProvider = { _ -> persistence },
        )

        val result = tool.execute(
            params = buildJsonObject {
                put("action", "start")
                put("source", "shell")
                put("command", "tail -f app.log")
                put("filter", "ERROR")
            },
            project = project,
        )

        // Tool result must not be an error
        assertTrue(!result.isError, "expected success: ${result.content}")

        // Persistence.add must have been called once
        verify(exactly = 1) { persistence.add("s1", any()) }

        // The spec.id captured must appear in the result message
        val capturedId = specSlot.captured.id
        assertTrue(result.content.contains(capturedId),
            "result content '$result.content' should contain the monitor id '$capturedId'")
    }

    // ─── source-contract tests: remove on exit / flood-stop ───────────────────
    //
    // These tests assert the source TEXT of MonitorTool and AgentService contain the
    // `remove(` call in the right lambdas — the same pattern used by RunInvocationLeakTest.
    // A unit-level seam for onExit would require injecting MonitorSourceFactory; these
    // source-text pins are the lightest-weight anchor that is not subject to false negatives.

    @Test
    fun `source-contract - startShell onExit lambda calls monitorPersistenceProvider remove`() {
        val src = java.io.File("src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/MonitorTool.kt")
            .readText()
        // Find the onExit lambda inside startShell — it must call both markExited and remove.
        val onExitBlock = src.substringAfter("val onExit: (Int?) -> Unit = { code ->")
            .substringBefore("val result = MonitorSourceFactory.build")
        assertTrue(onExitBlock.contains("pool.markExited"),
            "onExit should call pool.markExited: $onExitBlock")
        assertTrue(onExitBlock.contains("monitorPersistenceProvider(project).remove(sessionId, id)"),
            "onExit should call monitorPersistenceProvider(project).remove(sessionId, id) to avoid stale re-arm: $onExitBlock")
    }

    @Test
    fun `source-contract - AgentService reArmMonitors onShellExit calls monitorPersistence remove`() {
        val src = java.io.File("src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt")
            .readText()
        val onShellExitBlock = src.substringAfter("val onShellExit: (Int?) -> Unit = { code ->")
            .substringBefore("val result = MonitorSourceFactory.build")
        assertTrue(onShellExitBlock.contains("pool.markExited"),
            "onShellExit should call pool.markExited: $onShellExitBlock")
        assertTrue(onShellExitBlock.contains("monitorPersistence.remove(sessionId, spec.id)"),
            "onShellExit should call monitorPersistence.remove(sessionId, spec.id) to avoid re-launch on resume: $onShellExitBlock")
    }

    @Test
    fun `source-contract - AgentService monitorManagerFor onFloodStop calls monitorPersistence remove`() {
        val src = java.io.File("src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt")
            .readText()
        val onFloodStopBlock = src.substringAfter("onFloodStop = { id ->")
            .substringBefore("},")
        assertTrue(onFloodStopBlock.contains("MonitorPool.getInstance(project).stop(sessionId, id)"),
            "onFloodStop should call pool.stop: $onFloodStopBlock")
        assertTrue(onFloodStopBlock.contains("monitorPersistence.remove(sessionId, id)"),
            "onFloodStop should call monitorPersistence.remove(sessionId, id) to avoid spurious re-arm: $onFloodStopBlock")
    }

    @Test
    fun `persist - MonitorPersistence remove is called on successful monitor stop`() = runTest {
        val persistence = mockk<MonitorPersistence>(relaxed = true)
        val pool = mockk<MonitorPool>(relaxed = true)
        val agentService = mockk<AgentService>(relaxed = true)

        // pool.stop returns true (monitor was found and stopped)
        every { pool.stop("s1", "shell-test1234") } returns true

        val project = mockk<Project>(relaxed = true) {
            every { getService(MonitorPool::class.java)  } returns pool
            every { getService(AgentService::class.java) } returns agentService
        }

        val tool = MonitorTool(
            sessionIdProvider = { "s1" },
            cs = CoroutineScope(SupervisorJob()),
            monitorPersistenceProvider = { _ -> persistence },
        )

        val result = tool.execute(
            params = buildJsonObject {
                put("action", "stop")
                put("monitor_id", "shell-test1234")
            },
            project = project,
        )

        assertTrue(!result.isError, "expected success: ${result.content}")
        verify(exactly = 1) { persistence.remove("s1", "shell-test1234") }
    }
}
