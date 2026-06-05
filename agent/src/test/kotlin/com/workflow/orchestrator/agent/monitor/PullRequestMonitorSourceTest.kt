package com.workflow.orchestrator.agent.monitor

import com.workflow.orchestrator.agent.monitor.PullRequestMonitorSource.Aspect
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.bitbucket.PullRequestDetailData
import com.workflow.orchestrator.core.services.BitbucketService
import com.workflow.orchestrator.core.services.ToolResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PullRequestMonitorSourceTest {

    private val prId = 7
    private val monitorId = "pr-test"
    private val description = "watch PR #7"

    // ─────────────────────────────── parseAspects ────────────────────────────────

    @Test
    fun `parseAspects - null returns all three aspects`() {
        val result = PullRequestMonitorSource.parseAspects(null)
        assertEquals(setOf(Aspect.STATE, Aspect.REVIEWS, Aspect.COMMENTS), result)
    }

    @Test
    fun `parseAspects - blank string returns all three aspects`() {
        val result = PullRequestMonitorSource.parseAspects("   ")
        assertEquals(setOf(Aspect.STATE, Aspect.REVIEWS, Aspect.COMMENTS), result)
    }

    @Test
    fun `parseAspects - empty string returns all three aspects`() {
        val result = PullRequestMonitorSource.parseAspects("")
        assertEquals(setOf(Aspect.STATE, Aspect.REVIEWS, Aspect.COMMENTS), result)
    }

    @Test
    fun `parseAspects - single token 'state' returns only STATE`() {
        val result = PullRequestMonitorSource.parseAspects("state")
        assertEquals(setOf(Aspect.STATE), result)
    }

    @Test
    fun `parseAspects - 'STATE,comments' case-insensitive plus trim returns STATE and COMMENTS`() {
        val result = PullRequestMonitorSource.parseAspects("STATE, comments")
        assertEquals(setOf(Aspect.STATE, Aspect.COMMENTS), result)
    }

    @Test
    fun `parseAspects - all three tokens returns all three aspects`() {
        val result = PullRequestMonitorSource.parseAspects("state,reviews,comments")
        assertEquals(setOf(Aspect.STATE, Aspect.REVIEWS, Aspect.COMMENTS), result)
    }

    @Test
    fun `parseAspects - unknown token is ignored and remaining set kept`() {
        val result = PullRequestMonitorSource.parseAspects("state,bogus")
        // "bogus" ignored, only STATE recognised
        assertEquals(setOf(Aspect.STATE), result)
    }

    @Test
    fun `parseAspects - all unknown tokens falls back to all three aspects`() {
        val result = PullRequestMonitorSource.parseAspects("foo,bar")
        assertEquals(setOf(Aspect.STATE, Aspect.REVIEWS, Aspect.COMMENTS), result)
    }

    @Test
    fun `parseAspects - 'reviews' alone returns only REVIEWS`() {
        val result = PullRequestMonitorSource.parseAspects("reviews")
        assertEquals(setOf(Aspect.REVIEWS), result)
    }

    // ─────────────────────────────── childCount ──────────────────────────────────

    private fun fakeBitbucket(): BitbucketService {
        val bb = mockk<BitbucketService>()
        coEvery { bb.getPullRequestDetail(any(), any()) } returns ToolResult(
            data = PullRequestDetailData(
                id = prId, title = "T", description = null, state = "OPEN",
                fromBranch = "f", toBranch = "m", authorName = "a",
                reviewers = emptyList(), createdDate = 0L, updatedDate = 0L, version = 1,
            ),
            summary = "ok", isError = false,
        )
        coEvery { bb.getPullRequestParticipants(any(), any()) } returns ToolResult(
            data = emptyList(), summary = "ok", isError = false,
        )
        coEvery { bb.getBlockerCommentsCount(any(), any()) } returns ToolResult(
            data = 0, summary = "ok", isError = false,
        )
        return bb
    }

    private fun makeSource(
        aspects: Set<Aspect>,
        bb: BitbucketService = fakeBitbucket(),
        flow: MutableSharedFlow<WorkflowEvent> = MutableSharedFlow(),
    ) = PullRequestMonitorSource(
        monitorId = monitorId,
        description = description,
        aspects = aspects,
        bitbucket = bb,
        flow = flow,
        prId = prId,
        repoName = null,
        cs = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
    )

    @Test
    fun `STATE aspect produces 1 child`() {
        val src = makeSource(setOf(Aspect.STATE))
        assertEquals(1, src.childCount)
    }

    @Test
    fun `REVIEWS aspect produces 1 child`() {
        val src = makeSource(setOf(Aspect.REVIEWS))
        assertEquals(1, src.childCount)
    }

    @Test
    fun `COMMENTS aspect produces 2 children (blocker + total)`() {
        val src = makeSource(setOf(Aspect.COMMENTS))
        assertEquals(2, src.childCount)
    }

    @Test
    fun `all three aspects produce 4 children`() {
        val src = makeSource(setOf(Aspect.STATE, Aspect.REVIEWS, Aspect.COMMENTS))
        assertEquals(4, src.childCount)
    }

    @Test
    fun `STATE + REVIEWS produces 2 children`() {
        val src = makeSource(setOf(Aspect.STATE, Aspect.REVIEWS))
        assertEquals(2, src.childCount)
    }

    @Test
    fun `STATE + COMMENTS produces 3 children`() {
        val src = makeSource(setOf(Aspect.STATE, Aspect.COMMENTS))
        assertEquals(3, src.childCount)
    }

    // ─────────────────────── start emits hydrate event for STATE ─────────────────

    @Test
    fun `start - STATE aspect emits hydrate NOTABLE when PR is already MERGED`() = runTest(UnconfinedTestDispatcher()) {
        val bb = mockk<BitbucketService>()
        coEvery { bb.getPullRequestDetail(prId, null) } returns ToolResult(
            data = PullRequestDetailData(
                id = prId, title = "T", description = null, state = "MERGED",
                fromBranch = "f", toBranch = "m", authorName = "a",
                reviewers = emptyList(), createdDate = 0L, updatedDate = 0L, version = 1,
            ),
            summary = "ok", isError = false,
        )
        val flow = MutableSharedFlow<WorkflowEvent>()
        val src = PullRequestMonitorSource(
            monitorId = monitorId, description = description,
            aspects = setOf(Aspect.STATE), bitbucket = bb, flow = flow,
            prId = prId, repoName = null,
            cs = this,
        )

        val emitted = mutableListOf<MonitorEvent>()
        src.start { emitted.add(it) }

        // Allow the launched coroutine to complete hydration.
        // Use runCurrent() instead of advanceUntilIdle() because flow.collect()
        // never terminates and would cause advanceUntilIdle() to spin forever.
        testScheduler.runCurrent()
        src.stop()

        assertTrue(emitted.isNotEmpty(), "expected at least one hydrate event, got none")
        assertTrue(emitted.any { it.severity == Severity.NOTABLE && it.line.contains("MERGED") },
            "expected a NOTABLE hydrate event for MERGED state, got: $emitted")
    }

    // ─────────────────────────── stop stops all children ─────────────────────────

    @Test
    fun `stop - does not throw even when a child stop would fail`() {
        // We verify stop is idempotent / fault-tolerant (runCatching per child)
        val src = makeSource(setOf(Aspect.STATE, Aspect.REVIEWS, Aspect.COMMENTS))
        // start then stop — should complete without exception
        val emitted = mutableListOf<MonitorEvent>()
        src.start { emitted.add(it) }
        src.stop()
        src.stop() // double-stop should also be safe
    }

    // ─────── all children stamp the parent monitorId ─────────────────────────────

    @Test
    fun `all aspects use parseAspects - all three returned from full string`() = runTest(UnconfinedTestDispatcher()) {
        val bb = mockk<BitbucketService>()
        coEvery { bb.getPullRequestDetail(any(), any()) } returns ToolResult(
            data = PullRequestDetailData(
                id = prId, title = "T", description = null, state = "OPEN",
                fromBranch = "f", toBranch = "m", authorName = "a",
                reviewers = emptyList(), createdDate = 0L, updatedDate = 0L, version = 1,
            ),
            summary = "ok", isError = false,
        )
        coEvery { bb.getPullRequestParticipants(any(), any()) } returns ToolResult(
            data = emptyList(), summary = "ok", isError = false,
        )
        coEvery { bb.getBlockerCommentsCount(any(), any()) } returns ToolResult(
            data = 0, summary = "ok", isError = false,
        )

        val flow = MutableSharedFlow<WorkflowEvent>()
        val aspects = PullRequestMonitorSource.parseAspects("state,reviews,comments")
        val src = PullRequestMonitorSource(
            monitorId = monitorId, description = description,
            aspects = aspects, bitbucket = bb, flow = flow,
            prId = prId, repoName = null, cs = this,
        )
        assertEquals(4, src.childCount, "all 3 aspects should produce 4 children")

        // Emit a MERGED event — verify it is delivered with the parent monitorId.
        // We do NOT call advanceUntilIdle() on the whole scope because EventBusSource
        // launches a never-ending flow.collect coroutine that would cause the scheduler
        // to spin indefinitely. Instead: start, emit, advance once (enough for the
        // launched coroutine to resume), then stop to cancel the collectors.
        val emitted = mutableListOf<MonitorEvent>()
        src.start { emitted.add(it) }

        // Let hydration coroutines run (state=OPEN → no hydrate event)
        testScheduler.runCurrent()

        // Emit the event that should be delivered via PrStateSource
        flow.emit(WorkflowEvent.PullRequestMerged(prId = prId))
        testScheduler.runCurrent()

        // Stop all children (cancels the flow.collect coroutines)
        src.stop()

        val mergeEvent = emitted.firstOrNull { it.line.contains("merged") }
        assertTrue(mergeEvent != null, "expected a merged event, got: $emitted")
        assertEquals(monitorId, mergeEvent!!.monitorId, "event must carry the PARENT monitorId")
    }
}
