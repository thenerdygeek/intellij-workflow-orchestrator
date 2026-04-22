package com.workflow.orchestrator.pullrequest.ui

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.workflow.JiraTicketProvider
import com.workflow.orchestrator.core.workflow.TicketContext
import com.workflow.orchestrator.core.workflow.TicketDetails
import com.workflow.orchestrator.core.workflow.TicketTransition
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Headless tests for [TicketChipInput].
 *
 * Uses a stub [JiraTicketProvider] to return canned responses keyed by ticket.
 * Runs the coroutine work inline via [UnconfinedTestDispatcher] and [EmptyCoroutineContext]
 * so the async resolve completes deterministically before assertions.
 *
 * UI re-renders dispatch synchronously via the `uiDispatcher = Runnable::run`
 * seam — no EDT interaction needed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TicketChipInputTest {

    private val project: Project = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildWidget(
        scope: CoroutineScope,
        provider: JiraTicketProvider,
        initialKeys: List<String> = emptyList(),
        initialContexts: Map<String, TicketContext> = emptyMap(),
        onChange: (TicketChipInput.Snapshot) -> Unit = {}
    ): TicketChipInput = TicketChipInput(
        project = project,
        scope = scope,
        initialKeys = initialKeys,
        initialContexts = initialContexts,
        onChange = onChange,
        testProvider = provider,
        uiDispatcher = { it.run() },
        ioContext = EmptyCoroutineContext
    )

    // ── Stub providers ───────────────────────────────────────────────────────

    private fun stubValid(key: String, summary: String = "Summary of $key"): TicketContext =
        TicketContext(
            key = key,
            summary = summary,
            description = null,
            status = "In Progress",
            priority = "Medium",
            issueType = "Bug",
            assignee = null,
            reporter = null
        )

    /**
     * Canned provider. `validKeys` → returns a TicketContext. `notFoundKeys` → null.
     * `errorKeys` → throws. Any other key → null.
     */
    private class StubProvider(
        val validKeys: Map<String, TicketContext> = emptyMap(),
        val notFoundKeys: Set<String> = emptySet(),
        val errorKeys: Set<String> = emptySet()
    ) : JiraTicketProvider {
        override suspend fun getTicketDetails(ticketId: String): TicketDetails? = null
        override suspend fun getTicketContext(key: String): TicketContext? {
            if (key in errorKeys) throw RuntimeException("boom for $key")
            if (key in notFoundKeys) return null
            return validKeys[key]
        }
        override suspend fun getAvailableTransitions(ticketId: String): List<TicketTransition> = emptyList()
        override suspend fun transitionTicket(ticketId: String, transitionId: String): Boolean = false
        override fun showTransitionDialog(project: Project, ticketId: String, onTransitioned: () -> Unit) {}
    }

    // ── 1. valid key → VALID status ──────────────────────────────────────────

    @Test
    fun `addKey with valid response becomes VALID`() = runTest {
        val ctx = stubValid("WO-912", "Fix login")
        val provider = StubProvider(validKeys = mapOf("WO-912" to ctx))
        val widget = buildWidget(this, provider)

        widget.addKey("WO-912")
        advanceUntilIdle()

        val chips = widget.allChips()
        assertEquals(1, chips.size)
        assertEquals(TicketChipInput.Chip.Status.VALID, chips[0].status)
        assertSame(ctx, chips[0].context)
        assertSame(ctx, widget.validPrimary())
    }

    // ── 2. missing key → NOT_FOUND ───────────────────────────────────────────

    @Test
    fun `addKey with null response becomes NOT_FOUND`() = runTest {
        val provider = StubProvider(notFoundKeys = setOf("WO-999"))
        val widget = buildWidget(this, provider)

        widget.addKey("WO-999")
        advanceUntilIdle()

        assertEquals(TicketChipInput.Chip.Status.NOT_FOUND, widget.allChips()[0].status)
        assertNull(widget.validPrimary())
    }

    // ── 3. throwing provider → NETWORK_ERROR ────────────────────────────────

    @Test
    fun `addKey with throwing provider becomes NETWORK_ERROR`() = runTest {
        val provider = StubProvider(errorKeys = setOf("WO-500"))
        val widget = buildWidget(this, provider)

        widget.addKey("WO-500")
        advanceUntilIdle()

        assertEquals(TicketChipInput.Chip.Status.NETWORK_ERROR, widget.allChips()[0].status)
    }

    // ── 4. paste → split into 3 chips ───────────────────────────────────────

    @Test
    fun `paste with mixed separators commits three chips`() = runTest {
        val provider = StubProvider(
            validKeys = mapOf(
                "WO-100" to stubValid("WO-100"),
                "WO-200" to stubValid("WO-200"),
                "WO-300" to stubValid("WO-300")
            )
        )
        val widget = buildWidget(this, provider)

        widget.handlePastedTextForTest("WO-100, WO-200 WO-300")
        advanceUntilIdle()

        val keys = widget.allChips().map { it.key }
        assertEquals(listOf("WO-100", "WO-200", "WO-300"), keys)
        assertEquals(3, widget.allValid().size)
    }

    // ── 5. max 5 chips — 6th rejected, input disabled ───────────────────────

    @Test
    fun `max chips constraint rejects sixth and disables input`() = runTest {
        val keys = (1..5).map { "WO-$it" }
        val valids = keys.associateWith { stubValid(it) }
        val provider = StubProvider(validKeys = valids)
        val widget = buildWidget(this, provider, initialKeys = keys)
        advanceUntilIdle()

        assertEquals(5, widget.allChips().size)
        assertFalse(widget.inputFieldForTest().isEnabled)

        widget.addKey("WO-6")
        advanceUntilIdle()

        assertEquals(5, widget.allChips().size)
        assertFalse(widget.allChips().any { it.key == "WO-6" })
    }

    // ── 6. allValid returns only VALID contexts ─────────────────────────────

    @Test
    fun `allValid returns only VALID contexts`() = runTest {
        val good = stubValid("WO-1")
        val provider = StubProvider(
            validKeys = mapOf("WO-1" to good),
            notFoundKeys = setOf("WO-2"),
            errorKeys = setOf("WO-3")
        )
        val widget = buildWidget(this, provider, initialKeys = listOf("WO-1", "WO-2", "WO-3"))
        advanceUntilIdle()

        val valids = widget.allValid()
        assertEquals(1, valids.size)
        assertSame(good, valids[0])
    }

    // ── 7. validPrimary semantics ───────────────────────────────────────────

    @Test
    fun `validPrimary returns context only when primary is VALID`() = runTest {
        val goodProvider = StubProvider(validKeys = mapOf("WO-1" to stubValid("WO-1")))
        val valid = buildWidget(this, goodProvider, initialKeys = listOf("WO-1"))
        advanceUntilIdle()
        assertNotNull(valid.validPrimary())

        val badProvider = StubProvider(notFoundKeys = setOf("WO-X"))
        val notValid = buildWidget(this, badProvider, initialKeys = listOf("WO-X"))
        advanceUntilIdle()
        assertNull(notValid.validPrimary())
    }

    // ── 8. Set as primary moves chip at index 2 to index 0 ─────────────────

    @Test
    fun `setAsPrimary moves index 2 to index 0`() = runTest {
        val provider = StubProvider(
            validKeys = mapOf(
                "WO-10" to stubValid("WO-10"),
                "WO-20" to stubValid("WO-20"),
                "WO-30" to stubValid("WO-30")
            )
        )
        val widget = buildWidget(this, provider, initialKeys = listOf("WO-10", "WO-20", "WO-30"))
        advanceUntilIdle()

        widget.setAsPrimaryForTest("WO-30")

        val keys = widget.allChips().map { it.key }
        assertEquals("WO-30", keys[0])
        // Remaining order preserved: 10 then 20.
        assertEquals(listOf("WO-30", "WO-10", "WO-20"), keys)
    }

    // ── 9. Auto-promotion when primary 404 + next chip valid ───────────────

    @Test
    fun `primary NOT_FOUND auto-promotes first valid chip`() = runTest {
        val provider = StubProvider(
            validKeys = mapOf("WO-22" to stubValid("WO-22")),
            notFoundKeys = setOf("WO-11")
        )
        val widget = buildWidget(this, provider, initialKeys = listOf("WO-11", "WO-22"))
        advanceUntilIdle()

        val keys = widget.allChips().map { it.key }
        assertEquals("WO-22", keys[0], "Expected WO-22 to be auto-promoted to primary after WO-11 resolved 404")
        assertEquals(TicketChipInput.Chip.Status.VALID, widget.allChips()[0].status)
        // WO-11 should still be present (in the swapped slot), in NOT_FOUND state.
        val other = widget.allChips().first { it.key == "WO-11" }
        assertEquals(TicketChipInput.Chip.Status.NOT_FOUND, other.status)
    }

    // ── 10. Remove chip fires onChange with updated snapshot ───────────────

    @Test
    fun `removeChip fires onChange with new snapshot`() = runTest {
        val provider = StubProvider(
            validKeys = mapOf(
                "WO-1" to stubValid("WO-1"),
                "WO-2" to stubValid("WO-2")
            )
        )
        val snapshots = mutableListOf<TicketChipInput.Snapshot>()
        val widget = buildWidget(
            this,
            provider,
            initialKeys = listOf("WO-1", "WO-2"),
            onChange = { snapshots.add(it) }
        )
        advanceUntilIdle()

        val snapshotsBefore = snapshots.size
        widget.removeChipForTest("WO-1")

        assertTrue(snapshots.size > snapshotsBefore, "onChange should fire on removal")
        val latest = snapshots.last()
        assertEquals(1, latest.chips.size)
        assertEquals("WO-2", latest.chips[0].key)
        assertEquals("WO-2", latest.primary?.key)
    }

    // ── 11. initialContexts fast path — no async fetch needed ──────────────

    @Test
    fun `initialContexts skips async fetch and marks VALID synchronously`() = runTest {
        val ctx = stubValid("WO-777", "prefetched")
        // Provider deliberately returns null — if the widget consulted it, the chip
        // would be NOT_FOUND. The fast path should bypass the fetch entirely.
        val provider = StubProvider(notFoundKeys = setOf("WO-777"))
        val widget = buildWidget(
            this,
            provider,
            initialKeys = listOf("WO-777"),
            initialContexts = mapOf("WO-777" to ctx)
        )

        // No advanceUntilIdle — should already be VALID.
        val chip = widget.allChips().single()
        assertEquals(TicketChipInput.Chip.Status.VALID, chip.status)
        assertSame(ctx, chip.context)
    }
}
