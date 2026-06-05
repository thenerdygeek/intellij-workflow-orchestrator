package com.workflow.orchestrator.agent.monitor

import com.workflow.orchestrator.core.model.sonar.SonarIssueData
import com.workflow.orchestrator.core.services.SonarService
import com.workflow.orchestrator.core.services.ToolResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SonarIssuesSourceTest {

    /** Minimal SonarIssueData builder — vary key/severity/type/message as needed. */
    private fun issue(
        key: String = "AV-001",
        severity: String = "MAJOR",
        type: String = "BUG",
        message: String = "Some issue message",
        component: String = "com.example:src/main/Foo.java",
        line: Int? = 42,
        status: String = "OPEN",
        rule: String = "java:S1234",
    ) = SonarIssueData(
        key = key,
        rule = rule,
        severity = severity,
        message = message,
        component = component,
        line = line,
        status = status,
        type = type,
    )

    private fun okResult(issues: List<SonarIssueData>) =
        ToolResult(data = issues, summary = "ok", isError = false)

    private fun errResult() =
        ToolResult<List<SonarIssueData>>(data = null, summary = "error", isError = true)

    private fun source(
        sonar: SonarService,
        projectKey: String = "my-project",
        branch: String? = null,
        minSeverity: String? = null,
        scope: TestScope,
    ) = SonarIssuesSource(
        monitorId = "test-sonar-issues",
        description = "watch sonar issues",
        cs = scope,
        sonar = sonar,
        projectKey = projectKey,
        branch = branch,
        minSeverity = minSeverity,
    )

    // ---- SonarIssuesDiff pure tests ------------------------------------------

    @Test
    fun `diff first poll (previous null) returns empty`() {
        val current = listOf(issue(key = "AV-001", severity = "BLOCKER"))
        val events = SonarIssuesDiff.diff("m1", "my-project", previous = null, current = current)
        assertTrue(events.isEmpty(), "first poll should be baseline only, got: $events")
    }

    @Test
    fun `diff new BLOCKER issue emits ALERT`() {
        val prev = emptyList<SonarIssueData>()
        val cur = listOf(issue(key = "AV-001", severity = "BLOCKER", type = "BUG", message = "Null pointer deref"))
        val events = SonarIssuesDiff.diff("m1", "my-project", prev, cur)
        assertEquals(1, events.size, "expected 1 event, got: $events")
        assertEquals(Severity.ALERT, events[0].severity)
        assertTrue(events[0].line.contains("BLOCKER"), "line should mention severity: ${events[0].line}")
        assertTrue(events[0].line.contains("AV-001"), "line should mention issue key: ${events[0].line}")
    }

    @Test
    fun `diff new CRITICAL issue emits ALERT`() {
        val prev = emptyList<SonarIssueData>()
        val cur = listOf(issue(key = "AV-002", severity = "CRITICAL", type = "VULNERABILITY", message = "SQL injection risk"))
        val events = SonarIssuesDiff.diff("m1", "my-project", prev, cur)
        assertEquals(1, events.size, "expected 1 event, got: $events")
        assertEquals(Severity.ALERT, events[0].severity, "CRITICAL should map to ALERT")
    }

    @Test
    fun `diff new MAJOR issue emits NOTABLE`() {
        val prev = emptyList<SonarIssueData>()
        val cur = listOf(issue(key = "AV-003", severity = "MAJOR", type = "CODE_SMELL", message = "Too many lines"))
        val events = SonarIssuesDiff.diff("m1", "my-project", prev, cur)
        assertEquals(1, events.size, "expected 1 event, got: $events")
        assertEquals(Severity.NOTABLE, events[0].severity, "MAJOR should map to NOTABLE")
    }

    @Test
    fun `diff new MINOR issue emits NOTABLE`() {
        val prev = emptyList<SonarIssueData>()
        val cur = listOf(issue(key = "AV-004", severity = "MINOR", type = "CODE_SMELL", message = "Minor style issue"))
        val events = SonarIssuesDiff.diff("m1", "my-project", prev, cur)
        assertEquals(1, events.size, "expected 1 event, got: $events")
        assertEquals(Severity.NOTABLE, events[0].severity, "MINOR should map to NOTABLE")
    }

    @Test
    fun `diff new INFO issue emits NOTABLE`() {
        val prev = emptyList<SonarIssueData>()
        val cur = listOf(issue(key = "AV-005", severity = "INFO", type = "CODE_SMELL", message = "Info level finding"))
        val events = SonarIssuesDiff.diff("m1", "my-project", prev, cur)
        assertEquals(1, events.size, "expected 1 event, got: $events")
        assertEquals(Severity.NOTABLE, events[0].severity, "INFO should map to NOTABLE")
    }

    @Test
    fun `diff resolved issues emits one NOTABLE with count`() {
        val prev = listOf(
            issue(key = "AV-001", severity = "MAJOR"),
            issue(key = "AV-002", severity = "MINOR"),
        )
        val cur = emptyList<SonarIssueData>()
        val events = SonarIssuesDiff.diff("m1", "my-project", prev, cur)
        assertEquals(1, events.size, "expected exactly 1 event for resolved issues, got: $events")
        assertEquals(Severity.NOTABLE, events[0].severity)
        assertTrue(events[0].line.contains("2"), "line should mention the count 2: ${events[0].line}")
        assertTrue(events[0].line.contains("resolved"), "line should mention 'resolved': ${events[0].line}")
    }

    @Test
    fun `diff no change returns empty`() {
        val issues = listOf(issue(key = "AV-001", severity = "MAJOR"))
        val events = SonarIssuesDiff.diff("m1", "my-project", issues, issues.map { it.copy() })
        assertTrue(events.isEmpty(), "no change should yield no events, got: $events")
    }

    @Test
    fun `diff multiple new and some resolved in one diff emits correct mix`() {
        val prev = listOf(
            issue(key = "OLD-1", severity = "MINOR"),
            issue(key = "OLD-2", severity = "MINOR"),
        )
        val cur = listOf(
            issue(key = "OLD-1", severity = "MINOR"),  // retained
            issue(key = "NEW-1", severity = "BLOCKER", type = "BUG", message = "New blocker"),
            issue(key = "NEW-2", severity = "MAJOR", type = "CODE_SMELL", message = "New major"),
        )
        val events = SonarIssuesDiff.diff("m1", "my-project", prev, cur)
        // OLD-2 resolved → 1 NOTABLE; NEW-1 BLOCKER → ALERT; NEW-2 MAJOR → NOTABLE
        assertEquals(3, events.size, "expected 3 events total, got: $events")
        val alerts = events.filter { it.severity == Severity.ALERT }
        val notables = events.filter { it.severity == Severity.NOTABLE }
        assertEquals(1, alerts.size, "expected 1 ALERT for blocker, got alerts: $alerts")
        assertEquals(2, notables.size, "expected 2 NOTABLE (major + resolved), got notables: $notables")
    }

    @Test
    fun `diff event line contains projectKey and issue info`() {
        val prev = emptyList<SonarIssueData>()
        val cur = listOf(issue(key = "AV-100", severity = "CRITICAL", type = "VULNERABILITY", message = "Critical security issue that is quite long"))
        val events = SonarIssuesDiff.diff("m1", "proj-key", prev, cur)
        assertEquals(1, events.size)
        val line = events[0].line
        assertTrue(line.contains("proj-key"), "line should contain projectKey: $line")
        assertTrue(line.contains("AV-100"), "line should contain issue key: $line")
        assertTrue(line.contains("CRITICAL"), "line should contain severity: $line")
        assertTrue(line.contains("VULNERABILITY"), "line should contain type: $line")
    }

    @Test
    fun `diff event message is truncated at 80 chars`() {
        val longMessage = "A".repeat(200)
        val prev = emptyList<SonarIssueData>()
        val cur = listOf(issue(key = "AV-999", severity = "MAJOR", message = longMessage))
        val events = SonarIssuesDiff.diff("m1", "p", prev, cur)
        assertEquals(1, events.size)
        // The line itself may be longer than 80 due to prefix, but the message portion is capped
        // Verify the message does NOT appear verbatim (200 A's)
        assertFalse(events[0].line.contains("A".repeat(100)), "message should be truncated: ${events[0].line}")
    }

    // ---- SonarIssuesSource via pollOnce (MockK) --------------------------------

    @Test
    fun `getIssues isError results in fetch null and pollOnce returns false with no events`() = runTest {
        val sonar = mockk<SonarService>()
        coEvery { sonar.getIssues("my-project", branch = null) } returns errResult()
        val src = source(sonar, scope = this)
        val events = mutableListOf<MonitorEvent>()
        val changed = src.pollOnce { events.add(it) }
        assertFalse(changed, "isError fetch should return false")
        assertTrue(events.isEmpty(), "isError should yield no events")
    }

    @Test
    fun `first pollOnce (baseline) returns false and no events`() = runTest {
        val sonar = mockk<SonarService>()
        coEvery { sonar.getIssues("my-project", branch = null) } returns okResult(
            listOf(issue(key = "AV-001", severity = "BLOCKER"))
        )
        val src = source(sonar, scope = this)
        val events = mutableListOf<MonitorEvent>()
        val changed = src.pollOnce { events.add(it) }
        assertFalse(changed, "first poll should be baseline only (false)")
        assertTrue(events.isEmpty(), "first poll should emit no events")
    }

    @Test
    fun `two-poll new issue emits ALERT or NOTABLE on second poll`() = runTest {
        val sonar = mockk<SonarService>()
        coEvery { sonar.getIssues("my-project", branch = null) } returnsMany listOf(
            okResult(emptyList()),
            okResult(listOf(issue(key = "NEW-1", severity = "BLOCKER", type = "BUG", message = "Crash on startup"))),
        )
        val src = source(sonar, scope = this)
        val events = mutableListOf<MonitorEvent>()
        // First poll: baseline
        val changed1 = src.pollOnce { events.add(it) }
        assertFalse(changed1, "first poll is baseline")
        assertTrue(events.isEmpty(), "first poll emits nothing")
        // Second poll: new issue
        val changed2 = src.pollOnce { events.add(it) }
        assertTrue(changed2, "second poll with new issue should return true")
        assertEquals(1, events.size, "expected 1 event on new issue")
        assertEquals(Severity.ALERT, events[0].severity, "new BLOCKER should be ALERT")
    }

    @Test
    fun `min_severity filter — MINOR filtered out of snapshot so MINOR never added and never resolved`() = runTest {
        val sonar = mockk<SonarService>()
        // First call: returns both MINOR and MAJOR
        // Second call: removes MINOR (but MINOR was never in snapshot due to filter)
        coEvery { sonar.getIssues("my-project", branch = null) } returnsMany listOf(
            okResult(listOf(
                issue(key = "MINOR-1", severity = "MINOR"),
                issue(key = "MAJOR-1", severity = "MAJOR"),
            )),
            okResult(listOf(
                issue(key = "MAJOR-1", severity = "MAJOR"),  // MINOR removed from server but was never tracked
            )),
        )
        val src = source(sonar, minSeverity = "MAJOR", scope = this)
        val events = mutableListOf<MonitorEvent>()
        // First poll: baseline — should snapshot only MAJOR-1 (MINOR-1 filtered out)
        val changed1 = src.pollOnce { events.add(it) }
        assertFalse(changed1, "first poll is baseline")
        assertTrue(events.isEmpty(), "first poll emits nothing")
        // Second poll: MINOR-1 gone but was never tracked → no "resolved" for MINOR; MAJOR-1 retained → no event
        val changed2 = src.pollOnce { events.add(it) }
        assertFalse(changed2, "no change to tracked (MAJOR+) issues should return false")
        assertTrue(events.isEmpty(), "removing a non-tracked MINOR should produce no events")
    }

    @Test
    fun `min_severity filter — MINOR appearing is never added`() = runTest {
        val sonar = mockk<SonarService>()
        coEvery { sonar.getIssues("my-project", branch = null) } returnsMany listOf(
            okResult(emptyList()),
            okResult(listOf(issue(key = "MINOR-1", severity = "MINOR"))),
        )
        val src = source(sonar, minSeverity = "MAJOR", scope = this)
        val events = mutableListOf<MonitorEvent>()
        src.pollOnce { events.add(it) }  // baseline
        val changed = src.pollOnce { events.add(it) }
        assertFalse(changed, "MINOR appearing when minSeverity=MAJOR should not trigger event")
        assertTrue(events.isEmpty(), "MINOR filtered by min_severity should produce no events")
    }
}
