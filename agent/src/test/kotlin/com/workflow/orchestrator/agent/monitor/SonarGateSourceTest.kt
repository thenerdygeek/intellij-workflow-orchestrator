package com.workflow.orchestrator.agent.monitor

import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.sonar.QualityGateData
import com.workflow.orchestrator.core.services.SonarService
import com.workflow.orchestrator.core.services.ToolResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SonarGateSourceTest {

    private val watchedProjectKey = "my-project"
    private val monitorId = "sonar-gate-test"

    private fun makeSonar() = mockk<SonarService>()

    private fun okGateResult(status: String) = ToolResult(
        data = QualityGateData(status = status),
        summary = "ok",
        isError = false,
    )

    private fun errGateResult() = ToolResult<QualityGateData>(
        data = null,
        summary = "error",
        isError = true,
    )

    // ------------------------------------------------------------------ companion classify tests

    @Test
    fun `classify - QualityGateResult passed=true for watched projectKey returns NOTABLE`() {
        val event = WorkflowEvent.QualityGateResult(projectKey = watchedProjectKey, passed = true)
        val result = SonarGateSource.classify(monitorId, watchedProjectKey, event)
        assertEquals(Severity.NOTABLE, result?.severity)
        assertTrue(result?.line?.contains("OK") == true, "line should indicate OK: ${result?.line}")
    }

    @Test
    fun `classify - QualityGateResult passed=false for watched projectKey returns ALERT`() {
        val event = WorkflowEvent.QualityGateResult(projectKey = watchedProjectKey, passed = false)
        val result = SonarGateSource.classify(monitorId, watchedProjectKey, event)
        assertEquals(Severity.ALERT, result?.severity)
        assertTrue(result?.line?.contains("ERROR") == true, "line should indicate ERROR: ${result?.line}")
    }

    @Test
    fun `classify - QualityGateResult for different projectKey returns null`() {
        val event = WorkflowEvent.QualityGateResult(projectKey = "other-project", passed = true)
        val result = SonarGateSource.classify(monitorId, watchedProjectKey, event)
        assertNull(result)
    }

    @Test
    fun `classify - unrelated WorkflowEvent returns null`() {
        val event = WorkflowEvent.TicketChanged("PROJ-1", "Some ticket")
        val result = SonarGateSource.classify(monitorId, watchedProjectKey, event)
        assertNull(result)
    }

    @Test
    fun `classify - BuildFinished unrelated event returns null`() {
        val event = WorkflowEvent.BuildFinished("MY-PLAN", 1, WorkflowEvent.BuildEventStatus.SUCCESS)
        val result = SonarGateSource.classify(monitorId, watchedProjectKey, event)
        assertNull(result)
    }

    // ------------------------------------------------------------------ hydrate tests

    @Test
    fun `hydrate - status ERROR returns ALERT synthetic event`() =
        runTest(UnconfinedTestDispatcher()) {
            val sonar = makeSonar()
            coEvery { sonar.getQualityGateStatus(projectKey = watchedProjectKey, branch = null) } returns okGateResult("ERROR")

            val flow = MutableSharedFlow<WorkflowEvent>(extraBufferCapacity = 8)
            val source = SonarGateSource(
                monitorId = monitorId,
                description = "test",
                cs = this,
                flow = flow,
                sonar = sonar,
                projectKey = watchedProjectKey,
                branch = null,
            )

            val sink = mutableListOf<MonitorEvent>()
            source.start { sink.add(it) }
            testScheduler.advanceUntilIdle()
            source.stop()

            assertEquals(1, sink.size)
            assertEquals(Severity.ALERT, sink[0].severity)
            assertTrue(sink[0].line.contains("FAILED") || sink[0].line.contains("ERROR"),
                "line should indicate FAILED/ERROR state: ${sink[0].line}")
        }

    @Test
    fun `hydrate - status OK returns NOTABLE synthetic event`() =
        runTest(UnconfinedTestDispatcher()) {
            val sonar = makeSonar()
            coEvery { sonar.getQualityGateStatus(projectKey = watchedProjectKey, branch = null) } returns okGateResult("OK")

            val flow = MutableSharedFlow<WorkflowEvent>(extraBufferCapacity = 8)
            val source = SonarGateSource(
                monitorId = monitorId,
                description = "test",
                cs = this,
                flow = flow,
                sonar = sonar,
                projectKey = watchedProjectKey,
                branch = null,
            )

            val sink = mutableListOf<MonitorEvent>()
            source.start { sink.add(it) }
            testScheduler.advanceUntilIdle()
            source.stop()

            assertEquals(1, sink.size)
            assertEquals(Severity.NOTABLE, sink[0].severity)
            assertTrue(sink[0].line.contains("PASSING") || sink[0].line.contains("OK"),
                "line should indicate PASSING/OK state: ${sink[0].line}")
        }

    @Test
    fun `hydrate - isError returns null (no hydration event)`() =
        runTest(UnconfinedTestDispatcher()) {
            val sonar = makeSonar()
            coEvery { sonar.getQualityGateStatus(projectKey = watchedProjectKey, branch = null) } returns errGateResult()

            val flow = MutableSharedFlow<WorkflowEvent>(extraBufferCapacity = 8)
            val source = SonarGateSource(
                monitorId = monitorId,
                description = "test",
                cs = this,
                flow = flow,
                sonar = sonar,
                projectKey = watchedProjectKey,
                branch = null,
            )

            val sink = mutableListOf<MonitorEvent>()
            source.start { sink.add(it) }
            testScheduler.advanceUntilIdle()
            source.stop()

            assertTrue(sink.isEmpty(), "Error result must not emit any hydration event")
        }

    @Test
    fun `hydrate - unknown status returns null (no hydration event)`() =
        runTest(UnconfinedTestDispatcher()) {
            val sonar = makeSonar()
            coEvery { sonar.getQualityGateStatus(projectKey = watchedProjectKey, branch = null) } returns okGateResult("WARN")

            val flow = MutableSharedFlow<WorkflowEvent>(extraBufferCapacity = 8)
            val source = SonarGateSource(
                monitorId = monitorId,
                description = "test",
                cs = this,
                flow = flow,
                sonar = sonar,
                projectKey = watchedProjectKey,
                branch = null,
            )

            val sink = mutableListOf<MonitorEvent>()
            source.start { sink.add(it) }
            testScheduler.advanceUntilIdle()
            source.stop()

            assertTrue(sink.isEmpty(), "Unknown status must not emit any hydration event")
        }

    @Test
    fun `hydrate - lowercase 'error' status is treated case-insensitively and returns ALERT`() =
        runTest(UnconfinedTestDispatcher()) {
            val sonar = makeSonar()
            coEvery { sonar.getQualityGateStatus(projectKey = watchedProjectKey, branch = null) } returns okGateResult("error")

            val flow = MutableSharedFlow<WorkflowEvent>(extraBufferCapacity = 8)
            val source = SonarGateSource(
                monitorId = monitorId,
                description = "test",
                cs = this,
                flow = flow,
                sonar = sonar,
                projectKey = watchedProjectKey,
                branch = null,
            )

            val sink = mutableListOf<MonitorEvent>()
            source.start { sink.add(it) }
            testScheduler.advanceUntilIdle()
            source.stop()

            assertEquals(1, sink.size, "lowercase 'error' should still produce a hydration ALERT event")
            assertEquals(Severity.ALERT, sink[0].severity)
        }

    // ------------------------------------------------------------------ integration test

    @Test
    fun `integration - start emits hydrate event first then a flow QualityGateResult emits NOTABLE`() =
        runTest(UnconfinedTestDispatcher()) {
            val sonar = makeSonar()
            // Gate is already ERROR when we subscribe → hydration ALERT expected first
            coEvery { sonar.getQualityGateStatus(projectKey = watchedProjectKey, branch = null) } returns okGateResult("ERROR")

            val flow = MutableSharedFlow<WorkflowEvent>(extraBufferCapacity = 8)
            val sink = mutableListOf<MonitorEvent>()

            val source = SonarGateSource(
                monitorId = monitorId,
                description = "sonar gate $watchedProjectKey",
                cs = this,
                flow = flow,
                sonar = sonar,
                projectKey = watchedProjectKey,
                branch = null,
            )

            source.start { sink.add(it) }

            // UnconfinedTestDispatcher runs start() eagerly so hydration already ran.
            // Now emit a flow event — gate went to PASSING (passed=true).
            flow.emit(WorkflowEvent.QualityGateResult(projectKey = watchedProjectKey, passed = true))

            source.stop()

            assertEquals(2, sink.size, "Must have hydration event + flow event")
            // Hydration came first — ERROR state → ALERT
            assertEquals(Severity.ALERT, sink[0].severity, "First event must be the hydration ALERT")
            assertTrue(sink[0].line.contains("FAILED") || sink[0].line.contains("ERROR"),
                "Hydration line should indicate FAILED/ERROR: ${sink[0].line}")
            // Flow event came second — passed=true → NOTABLE
            assertEquals(Severity.NOTABLE, sink[1].severity, "Second event must be the flow NOTABLE")
            assertTrue(sink[1].line.contains("OK"), "Flow event line should indicate OK: ${sink[1].line}")
            assertEquals(monitorId, sink[1].monitorId)
        }
}
