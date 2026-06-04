package com.workflow.orchestrator.agent.monitor

import com.workflow.orchestrator.core.model.bitbucket.ParticipantData
import com.workflow.orchestrator.core.services.BitbucketService
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
class PrReviewsSourceTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun participant(
        username: String,
        displayName: String = username,
        status: String,
        role: String = "REVIEWER",
        approved: Boolean = status.uppercase() == "APPROVED",
    ) = ParticipantData(
        username = username,
        displayName = displayName,
        role = role,
        approved = approved,
        status = status,
    )

    private fun okResult(participants: List<ParticipantData>) =
        ToolResult(data = participants, summary = "ok", isError = false)

    private fun errResult() =
        ToolResult<List<ParticipantData>>(data = null, summary = "error", isError = true)

    private fun source(
        bitbucket: BitbucketService,
        prId: Int = 42,
        repoName: String? = null,
        scope: TestScope,
    ) = PrReviewsSource(
        monitorId = "test-pr-reviews",
        description = "test",
        cs = scope,
        bitbucket = bitbucket,
        prId = prId,
        repoName = repoName,
    )

    // -------------------------------------------------------------------------
    // Pure PrReviewsDiff tests (no coroutines needed)
    // -------------------------------------------------------------------------

    @Test
    fun `UNAPPROVED to APPROVED emits one NOTABLE event`() {
        val prev = listOf(participant("alice", "Alice", "UNAPPROVED"))
        val cur  = listOf(participant("alice", "Alice", "APPROVED"))
        val events = PrReviewsDiff.diff("m1", prId = 7, previous = prev, current = cur)
        assertEquals(1, events.size)
        assertEquals(Severity.NOTABLE, events[0].severity)
        assertTrue(events[0].line.contains("Alice"))
        assertTrue(events[0].line.contains("APPROVED"))
        assertTrue(events[0].line.contains("#7"))
    }

    @Test
    fun `UNAPPROVED to NEEDS_WORK emits one ALERT event`() {
        val prev = listOf(participant("bob", "Bob Smith", "UNAPPROVED"))
        val cur  = listOf(participant("bob", "Bob Smith", "NEEDS_WORK"))
        val events = PrReviewsDiff.diff("m1", prId = 7, previous = prev, current = cur)
        assertEquals(1, events.size)
        assertEquals(Severity.ALERT, events[0].severity)
        assertTrue(events[0].line.contains("Bob Smith"))
        assertTrue(events[0].line.contains("NEEDS_WORK"))
    }

    @Test
    fun `no status change emits nothing`() {
        val prev = listOf(participant("carol", "Carol", "APPROVED"))
        val cur  = listOf(participant("carol", "Carol", "APPROVED"))
        val events = PrReviewsDiff.diff("m1", prId = 7, previous = prev, current = cur)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `new reviewer APPROVED emits one NOTABLE`() {
        val prev = emptyList<ParticipantData>()
        val cur  = listOf(participant("dave", "Dave", "APPROVED"))
        val events = PrReviewsDiff.diff("m1", prId = 7, previous = prev, current = cur)
        assertEquals(1, events.size)
        assertEquals(Severity.NOTABLE, events[0].severity)
    }

    @Test
    fun `new reviewer UNAPPROVED emits nothing`() {
        val prev = emptyList<ParticipantData>()
        val cur  = listOf(participant("eve", "Eve", "UNAPPROVED"))
        val events = PrReviewsDiff.diff("m1", prId = 7, previous = prev, current = cur)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `first poll previous null APPROVED participant emits NOTABLE`() {
        val cur = listOf(participant("frank", "Frank", "APPROVED"))
        val events = PrReviewsDiff.diff("m1", prId = 7, previous = null, current = cur)
        assertEquals(1, events.size)
        assertEquals(Severity.NOTABLE, events[0].severity)
    }

    @Test
    fun `first poll previous null all UNAPPROVED emits nothing`() {
        val cur = listOf(
            participant("grace", "Grace", "UNAPPROVED"),
            participant("hank", "Hank", "UNAPPROVED"),
        )
        val events = PrReviewsDiff.diff("m1", prId = 7, previous = null, current = cur)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `first poll previous null NEEDS_WORK emits ALERT`() {
        val cur = listOf(participant("ivan", "Ivan", "NEEDS_WORK"))
        val events = PrReviewsDiff.diff("m1", prId = 7, previous = null, current = cur)
        assertEquals(1, events.size)
        assertEquals(Severity.ALERT, events[0].severity)
    }

    @Test
    fun `case-insensitive status comparison treats approved same as APPROVED`() {
        // Prev stored as uppercase; Bitbucket might return mixed case on current
        val prev = listOf(participant("judy", "Judy", "APPROVED"))
        val cur  = listOf(participant("judy", "Judy", "approved")) // lowercase from server
        val events = PrReviewsDiff.diff("m1", prId = 7, previous = prev, current = cur)
        assertTrue(events.isEmpty()) // same status, no event
    }

    // -------------------------------------------------------------------------
    // Source tests (via pollOnce, MockK BitbucketService)
    // -------------------------------------------------------------------------

    @Test
    fun `source getPullRequestParticipants isError returns false and no events`() = runTest {
        val bitbucket = mockk<BitbucketService>()
        coEvery { bitbucket.getPullRequestParticipants(42, null) } returns errResult()
        val src = source(bitbucket, prId = 42, scope = this)
        val events = mutableListOf<MonitorEvent>()
        val changed = src.pollOnce { events.add(it) }
        assertFalse(changed)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `source emits no events when all participants UNAPPROVED on first poll`() = runTest {
        val bitbucket = mockk<BitbucketService>()
        coEvery { bitbucket.getPullRequestParticipants(42, null) } returns
            okResult(listOf(participant("zara", "Zara", "UNAPPROVED")))
        val src = source(bitbucket, prId = 42, scope = this)
        val events = mutableListOf<MonitorEvent>()
        val changed = src.pollOnce { events.add(it) }
        assertFalse(changed)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `status transition across two pollOnce calls emits the event`() = runTest {
        val bitbucket = mockk<BitbucketService>()
        coEvery { bitbucket.getPullRequestParticipants(42, null) } returnsMany listOf(
            // First poll: reviewer is UNAPPROVED — no event expected
            okResult(listOf(participant("nina", "Nina", "UNAPPROVED"))),
            // Second poll: reviewer becomes APPROVED — NOTABLE expected
            okResult(listOf(participant("nina", "Nina", "APPROVED"))),
        )
        val src = source(bitbucket, prId = 42, scope = this)
        val events = mutableListOf<MonitorEvent>()

        val changed1 = src.pollOnce { events.add(it) }
        assertFalse(changed1)
        assertTrue(events.isEmpty())

        val changed2 = src.pollOnce { events.add(it) }
        assertTrue(changed2)
        assertEquals(1, events.size)
        assertEquals(Severity.NOTABLE, events[0].severity)
        assertTrue(events[0].line.contains("Nina"))
    }
}
