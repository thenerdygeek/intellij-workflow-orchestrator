package com.workflow.orchestrator.agent.monitor

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.services.BambooService
import com.workflow.orchestrator.core.services.BitbucketService
import com.workflow.orchestrator.core.services.JiraService
import com.workflow.orchestrator.core.services.SonarService
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * TDD tests for [MonitorSourceFactory].
 *
 * Each test verifies that [MonitorSourceFactory.build] correctly:
 * - Constructs the right [MonitorSource] subclass from a valid [MonitorSpec].
 * - Propagates [spec.id] as [MonitorSource.monitorId].
 * - Returns [MonitorSourceFactory.BuildResult.Failed] for missing required params.
 * - Returns [MonitorSourceFactory.BuildResult.Failed] when the service provider returns null
 *   (service not configured).
 *
 * Tests are headless (no IntelliJ Application) — all service objects are MockK stubs.
 */
class MonitorSourceFactoryTest {

    @TempDir
    lateinit var tempDir: Path

    private val cs: CoroutineScope = CoroutineScope(SupervisorJob())
    private val project: Project = mockk(relaxed = true)

    /** All providers return null (service not configured). */
    private val nullBamboo:    (Project) -> BambooService?    = { null }
    private val nullBitbucket: (Project) -> BitbucketService? = { null }
    private val nullJira:      (Project) -> JiraService?      = { null }
    private val nullSonar:     (Project) -> SonarService?     = { null }
    private val nullEventBus:  (Project) -> kotlinx.coroutines.flow.SharedFlow<WorkflowEvent>? = { null }

    /** Real stubs — services "configured". */
    private val bamboo:    BambooService    = mockk(relaxed = true)
    private val bitbucket: BitbucketService = mockk(relaxed = true)
    private val jira:      JiraService      = mockk(relaxed = true)
    private val sonar:     SonarService     = mockk(relaxed = true)
    private val eventBusFlow = MutableSharedFlow<WorkflowEvent>()

    private fun realBamboo():    (Project) -> BambooService?    = { bamboo }
    private fun realBitbucket(): (Project) -> BitbucketService? = { bitbucket }
    private fun realJira():      (Project) -> JiraService?      = { jira }
    private fun realSonar():     (Project) -> SonarService?     = { sonar }
    private fun realEventBus():  (Project) -> kotlinx.coroutines.flow.SharedFlow<WorkflowEvent>? = { eventBusFlow }

    private fun build(
        spec: MonitorSpec,
        bambooProvider:    (Project) -> BambooService?    = nullBamboo,
        bitbucketProvider: (Project) -> BitbucketService? = nullBitbucket,
        jiraProvider:      (Project) -> JiraService?      = nullJira,
        sonarProvider:     (Project) -> SonarService?     = nullSonar,
        eventBusProvider:  (Project) -> kotlinx.coroutines.flow.SharedFlow<WorkflowEvent>? = nullEventBus,
        onShellExit:       ((Int?) -> Unit)? = null,
    ) = MonitorSourceFactory.build(
        spec              = spec,
        project           = project,
        cs                = cs,
        bambooProvider    = bambooProvider,
        bitbucketProvider = bitbucketProvider,
        jiraProvider      = jiraProvider,
        sonarProvider     = sonarProvider,
        eventBusProvider  = eventBusProvider,
        onShellExit       = onShellExit,
    )

    // ─── shell ────────────────────────────────────────────────────────────────

    @Test
    fun `shell - valid spec returns Built ShellCommandSource with correct monitorId`() {
        val spec = MonitorSpec(
            id = "shell-abc12345",
            sourceType = "shell",
            description = "watch logs",
            params = mapOf("command" to "tail -f app.log", "filter" to "ERROR"),
        )
        val result = build(spec)
        assertInstanceOf(MonitorSourceFactory.BuildResult.Built::class.java, result)
        val source = (result as MonitorSourceFactory.BuildResult.Built).source
        assertInstanceOf(ShellCommandSource::class.java, source)
        assertEquals(spec.id, source.monitorId)
    }

    @Test
    fun `shell - missing command param returns Failed`() {
        val spec = MonitorSpec(
            id = "shell-test",
            sourceType = "shell",
            description = "d",
            params = mapOf("filter" to "ERROR"),
        )
        val result = build(spec)
        assertInstanceOf(MonitorSourceFactory.BuildResult.Failed::class.java, result)
        val msg = (result as MonitorSourceFactory.BuildResult.Failed).error
        assertTrue(msg.contains("command"), "error should mention 'command': $msg")
    }

    @Test
    fun `shell - missing filter param returns Failed`() {
        val spec = MonitorSpec(
            id = "shell-test",
            sourceType = "shell",
            description = "d",
            params = mapOf("command" to "tail -f log"),
        )
        val result = build(spec)
        assertInstanceOf(MonitorSourceFactory.BuildResult.Failed::class.java, result)
        val msg = (result as MonitorSourceFactory.BuildResult.Failed).error
        assertTrue(msg.contains("filter"), "error should mention 'filter': $msg")
    }

    @Test
    fun `shell - invalid regex filter returns Failed with regex error`() {
        val spec = MonitorSpec(
            id = "shell-test",
            sourceType = "shell",
            description = "d",
            params = mapOf("command" to "tail -f log", "filter" to "("),
        )
        val result = build(spec)
        assertInstanceOf(MonitorSourceFactory.BuildResult.Failed::class.java, result)
        val msg = (result as MonitorSourceFactory.BuildResult.Failed).error
        assertTrue(msg.contains("regex"), "error should mention 'regex': $msg")
    }

    @Test
    fun `shell - onShellExit callback is accepted without error`() {
        val spec = MonitorSpec(
            id = "shell-exit-test",
            sourceType = "shell",
            description = "d",
            params = mapOf("command" to "echo done", "filter" to "done"),
        )
        var called = false
        val result = build(spec, onShellExit = { called = true })
        assertInstanceOf(MonitorSourceFactory.BuildResult.Built::class.java, result)
    }

    // ─── bamboo ───────────────────────────────────────────────────────────────

    @Test
    fun `bamboo - valid spec returns Built BambooMonitorSource with correct monitorId`() {
        val spec = MonitorSpec(
            id = "bamboo-abc12345",
            sourceType = "bamboo",
            description = "watch bamboo",
            params = mapOf("plan_key" to "PROJ-PLAN", "level" to "build"),
        )
        val result = build(spec, bambooProvider = realBamboo())
        assertInstanceOf(MonitorSourceFactory.BuildResult.Built::class.java, result)
        val source = (result as MonitorSourceFactory.BuildResult.Built).source
        assertInstanceOf(BambooMonitorSource::class.java, source)
        assertEquals(spec.id, source.monitorId)
    }

    @Test
    fun `bamboo - unconfigured service returns Failed with expected message`() {
        val spec = MonitorSpec(
            id = "bamboo-test",
            sourceType = "bamboo",
            description = "d",
            params = mapOf("plan_key" to "PROJ-PLAN"),
        )
        val result = build(spec, bambooProvider = nullBamboo)
        assertInstanceOf(MonitorSourceFactory.BuildResult.Failed::class.java, result)
        val msg = (result as MonitorSourceFactory.BuildResult.Failed).error
        assertEquals("Bamboo is not configured.", msg)
    }

    @Test
    fun `bamboo - missing plan_key param returns Failed`() {
        val spec = MonitorSpec(
            id = "bamboo-test",
            sourceType = "bamboo",
            description = "d",
            params = mapOf("level" to "build"),
        )
        val result = build(spec, bambooProvider = realBamboo())
        assertInstanceOf(MonitorSourceFactory.BuildResult.Failed::class.java, result)
        val msg = (result as MonitorSourceFactory.BuildResult.Failed).error
        assertTrue(msg.contains("plan_key"), "error should mention 'plan_key': $msg")
    }

    // ─── pull_request ─────────────────────────────────────────────────────────

    @Test
    fun `pull_request - valid spec returns Built PullRequestMonitorSource with correct monitorId`() {
        val spec = MonitorSpec(
            id = "pr-abc12345",
            sourceType = "pull_request",
            description = "watch pr",
            params = mapOf("pr_id" to "42"),
        )
        val result = build(spec, bitbucketProvider = realBitbucket(), eventBusProvider = realEventBus())
        assertInstanceOf(MonitorSourceFactory.BuildResult.Built::class.java, result)
        val source = (result as MonitorSourceFactory.BuildResult.Built).source
        assertInstanceOf(PullRequestMonitorSource::class.java, source)
        assertEquals(spec.id, source.monitorId)
    }

    @Test
    fun `pull_request - unconfigured bitbucket returns Failed with expected message`() {
        val spec = MonitorSpec(
            id = "pr-test",
            sourceType = "pull_request",
            description = "d",
            params = mapOf("pr_id" to "42"),
        )
        val result = build(spec, bitbucketProvider = nullBitbucket, eventBusProvider = realEventBus())
        assertInstanceOf(MonitorSourceFactory.BuildResult.Failed::class.java, result)
        val msg = (result as MonitorSourceFactory.BuildResult.Failed).error
        assertEquals("Bitbucket is not configured.", msg)
    }

    @Test
    fun `pull_request - unconfigured eventBus returns Failed with expected message`() {
        val spec = MonitorSpec(
            id = "pr-test",
            sourceType = "pull_request",
            description = "d",
            params = mapOf("pr_id" to "42"),
        )
        val result = build(spec, bitbucketProvider = realBitbucket(), eventBusProvider = nullEventBus)
        assertInstanceOf(MonitorSourceFactory.BuildResult.Failed::class.java, result)
        val msg = (result as MonitorSourceFactory.BuildResult.Failed).error
        assertEquals("EventBus is not available.", msg)
    }

    @Test
    fun `pull_request - missing pr_id param returns Failed`() {
        val spec = MonitorSpec(
            id = "pr-test",
            sourceType = "pull_request",
            description = "d",
            params = emptyMap(),
        )
        val result = build(spec, bitbucketProvider = realBitbucket(), eventBusProvider = realEventBus())
        assertInstanceOf(MonitorSourceFactory.BuildResult.Failed::class.java, result)
        val msg = (result as MonitorSourceFactory.BuildResult.Failed).error
        assertTrue(msg.contains("pr_id"), "error should mention 'pr_id': $msg")
    }

    // ─── jira_ticket ──────────────────────────────────────────────────────────

    @Test
    fun `jira_ticket - valid spec returns Built JiraTicketMonitorSource with correct monitorId`() {
        val spec = MonitorSpec(
            id = "jira-ticket-abc12345",
            sourceType = "jira_ticket",
            description = "watch jira ticket",
            params = mapOf("ticket_key" to "PROJ-123"),
        )
        val result = build(spec, jiraProvider = realJira())
        assertInstanceOf(MonitorSourceFactory.BuildResult.Built::class.java, result)
        val source = (result as MonitorSourceFactory.BuildResult.Built).source
        assertInstanceOf(JiraTicketMonitorSource::class.java, source)
        assertEquals(spec.id, source.monitorId)
    }

    @Test
    fun `jira_ticket - unconfigured jira returns Failed with expected message`() {
        val spec = MonitorSpec(
            id = "jira-ticket-test",
            sourceType = "jira_ticket",
            description = "d",
            params = mapOf("ticket_key" to "PROJ-123"),
        )
        val result = build(spec, jiraProvider = nullJira)
        assertInstanceOf(MonitorSourceFactory.BuildResult.Failed::class.java, result)
        val msg = (result as MonitorSourceFactory.BuildResult.Failed).error
        assertEquals("Jira is not configured.", msg)
    }

    @Test
    fun `jira_ticket - missing ticket_key param returns Failed`() {
        val spec = MonitorSpec(
            id = "jira-ticket-test",
            sourceType = "jira_ticket",
            description = "d",
            params = emptyMap(),
        )
        val result = build(spec, jiraProvider = realJira())
        assertInstanceOf(MonitorSourceFactory.BuildResult.Failed::class.java, result)
        val msg = (result as MonitorSourceFactory.BuildResult.Failed).error
        assertTrue(msg.contains("ticket_key"), "error should mention 'ticket_key': $msg")
    }

    // ─── jira_sprint ──────────────────────────────────────────────────────────

    @Test
    fun `jira_sprint - valid spec with board_id returns Built JiraSprintMonitorSource with correct monitorId`() {
        val spec = MonitorSpec(
            id = "jira-sprint-abc12345",
            sourceType = "jira_sprint",
            description = "watch sprint",
            params = mapOf("board_id" to "5"),
        )
        val result = build(spec, jiraProvider = realJira())
        assertInstanceOf(MonitorSourceFactory.BuildResult.Built::class.java, result)
        val source = (result as MonitorSourceFactory.BuildResult.Built).source
        assertInstanceOf(JiraSprintMonitorSource::class.java, source)
        assertEquals(spec.id, source.monitorId)
    }

    @Test
    fun `jira_sprint - valid spec with sprint_id returns Built`() {
        val spec = MonitorSpec(
            id = "jira-sprint-test",
            sourceType = "jira_sprint",
            description = "d",
            params = mapOf("sprint_id" to "42"),
        )
        val result = build(spec, jiraProvider = realJira())
        assertInstanceOf(MonitorSourceFactory.BuildResult.Built::class.java, result)
        assertEquals(spec.id, (result as MonitorSourceFactory.BuildResult.Built).source.monitorId)
    }

    @Test
    fun `jira_sprint - unconfigured jira returns Failed with expected message`() {
        val spec = MonitorSpec(
            id = "jira-sprint-test",
            sourceType = "jira_sprint",
            description = "d",
            params = mapOf("board_id" to "5"),
        )
        val result = build(spec, jiraProvider = nullJira)
        assertInstanceOf(MonitorSourceFactory.BuildResult.Failed::class.java, result)
        val msg = (result as MonitorSourceFactory.BuildResult.Failed).error
        assertEquals("Jira is not configured.", msg)
    }

    @Test
    fun `jira_sprint - missing both board_id and sprint_id returns Failed`() {
        val spec = MonitorSpec(
            id = "jira-sprint-test",
            sourceType = "jira_sprint",
            description = "d",
            params = emptyMap(),
        )
        val result = build(spec, jiraProvider = realJira())
        assertInstanceOf(MonitorSourceFactory.BuildResult.Failed::class.java, result)
        val msg = (result as MonitorSourceFactory.BuildResult.Failed).error
        assertTrue(msg.contains("board_id") || msg.contains("sprint_id"), "error should mention ids: $msg")
    }

    // ─── sonar_gate ───────────────────────────────────────────────────────────

    @Test
    fun `sonar_gate - valid spec returns Built SonarGateSource with correct monitorId`() {
        val spec = MonitorSpec(
            id = "sonar-gate-abc12345",
            sourceType = "sonar_gate",
            description = "watch gate",
            params = mapOf("project_key" to "my-project"),
        )
        val result = build(spec, sonarProvider = realSonar(), eventBusProvider = realEventBus())
        assertInstanceOf(MonitorSourceFactory.BuildResult.Built::class.java, result)
        val source = (result as MonitorSourceFactory.BuildResult.Built).source
        assertInstanceOf(SonarGateSource::class.java, source)
        assertEquals(spec.id, source.monitorId)
    }

    @Test
    fun `sonar_gate - unconfigured sonar returns Failed with expected message`() {
        val spec = MonitorSpec(
            id = "sonar-gate-test",
            sourceType = "sonar_gate",
            description = "d",
            params = mapOf("project_key" to "my-project"),
        )
        val result = build(spec, sonarProvider = nullSonar, eventBusProvider = realEventBus())
        assertInstanceOf(MonitorSourceFactory.BuildResult.Failed::class.java, result)
        val msg = (result as MonitorSourceFactory.BuildResult.Failed).error
        assertEquals("Sonar is not configured.", msg)
    }

    @Test
    fun `sonar_gate - unconfigured eventBus returns Failed with expected message`() {
        val spec = MonitorSpec(
            id = "sonar-gate-test",
            sourceType = "sonar_gate",
            description = "d",
            params = mapOf("project_key" to "my-project"),
        )
        val result = build(spec, sonarProvider = realSonar(), eventBusProvider = nullEventBus)
        assertInstanceOf(MonitorSourceFactory.BuildResult.Failed::class.java, result)
        val msg = (result as MonitorSourceFactory.BuildResult.Failed).error
        assertEquals("EventBus is not available.", msg)
    }

    @Test
    fun `sonar_gate - missing project_key param returns Failed`() {
        val spec = MonitorSpec(
            id = "sonar-gate-test",
            sourceType = "sonar_gate",
            description = "d",
            params = emptyMap(),
        )
        val result = build(spec, sonarProvider = realSonar(), eventBusProvider = realEventBus())
        assertInstanceOf(MonitorSourceFactory.BuildResult.Failed::class.java, result)
        val msg = (result as MonitorSourceFactory.BuildResult.Failed).error
        assertTrue(msg.contains("project_key"), "error should mention 'project_key': $msg")
    }

    // ─── sonar_issues ─────────────────────────────────────────────────────────

    @Test
    fun `sonar_issues - valid spec returns Built SonarIssuesSource with correct monitorId`() {
        val spec = MonitorSpec(
            id = "sonar-issues-abc12345",
            sourceType = "sonar_issues",
            description = "watch issues",
            params = mapOf("project_key" to "my-project"),
        )
        val result = build(spec, sonarProvider = realSonar())
        assertInstanceOf(MonitorSourceFactory.BuildResult.Built::class.java, result)
        val source = (result as MonitorSourceFactory.BuildResult.Built).source
        assertInstanceOf(SonarIssuesSource::class.java, source)
        assertEquals(spec.id, source.monitorId)
    }

    @Test
    fun `sonar_issues - valid spec with min_severity returns Built`() {
        val spec = MonitorSpec(
            id = "sonar-issues-test",
            sourceType = "sonar_issues",
            description = "d",
            params = mapOf("project_key" to "my-project", "min_severity" to "MAJOR"),
        )
        val result = build(spec, sonarProvider = realSonar())
        assertInstanceOf(MonitorSourceFactory.BuildResult.Built::class.java, result)
        assertEquals(spec.id, (result as MonitorSourceFactory.BuildResult.Built).source.monitorId)
    }

    @Test
    fun `sonar_issues - unconfigured sonar returns Failed with expected message`() {
        val spec = MonitorSpec(
            id = "sonar-issues-test",
            sourceType = "sonar_issues",
            description = "d",
            params = mapOf("project_key" to "my-project"),
        )
        val result = build(spec, sonarProvider = nullSonar)
        assertInstanceOf(MonitorSourceFactory.BuildResult.Failed::class.java, result)
        val msg = (result as MonitorSourceFactory.BuildResult.Failed).error
        assertEquals("Sonar is not configured.", msg)
    }

    @Test
    fun `sonar_issues - missing project_key param returns Failed`() {
        val spec = MonitorSpec(
            id = "sonar-issues-test",
            sourceType = "sonar_issues",
            description = "d",
            params = emptyMap(),
        )
        val result = build(spec, sonarProvider = realSonar())
        assertInstanceOf(MonitorSourceFactory.BuildResult.Failed::class.java, result)
        val msg = (result as MonitorSourceFactory.BuildResult.Failed).error
        assertTrue(msg.contains("project_key"), "error should mention 'project_key': $msg")
    }

    // ─── unknown sourceType ───────────────────────────────────────────────────

    @Test
    fun `unknown sourceType returns Failed with not-supported message`() {
        val spec = MonitorSpec(
            id = "unknown-test",
            sourceType = "kafka",
            description = "d",
            params = emptyMap(),
        )
        val result = build(spec)
        assertInstanceOf(MonitorSourceFactory.BuildResult.Failed::class.java, result)
        val msg = (result as MonitorSourceFactory.BuildResult.Failed).error
        assertTrue(msg.contains("not supported"), "error should mention 'not supported': $msg")
    }
}
