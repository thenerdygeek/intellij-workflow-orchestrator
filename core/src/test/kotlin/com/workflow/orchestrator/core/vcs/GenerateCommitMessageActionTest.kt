package com.workflow.orchestrator.core.vcs

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.ui.Refreshable
import com.intellij.vcs.commit.CommitWorkflowUi
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [GenerateCommitMessageAction.resolveCheckedChanges].
 *
 * The helper must:
 * - prefer COMMIT_WORKFLOW_UI as the authoritative checked-files source
 * - treat an empty list from COMMIT_WORKFLOW_UI as authoritative (nothing checked)
 * - fall back to CheckinProjectPanel.selectedChanges when COMMIT_WORKFLOW_UI is absent
 * - fall back to VcsDataKeys.SELECTED_CHANGES as a tertiary source
 * - return (emptyList, "NONE") when all sources are absent
 * - NEVER use VcsDataKeys.CHANGES (root-cause of the original bug)
 */
class GenerateCommitMessageActionTest {

    private lateinit var action: GenerateCommitMessageAction
    private lateinit var event: AnActionEvent

    private val change1: Change = mockk(relaxed = true)
    private val change2: Change = mockk(relaxed = true)
    private val change3: Change = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        action = GenerateCommitMessageAction()
        event = mockk(relaxed = true)

        // Default: all data keys return null unless overridden in a specific test
        every { event.getData(VcsDataKeys.COMMIT_WORKFLOW_UI) } returns null
        every { event.getData(Refreshable.PANEL_KEY) } returns null
        every { event.getData(VcsDataKeys.SELECTED_CHANGES) } returns null
        every { event.getData(VcsDataKeys.CHANGES) } returns arrayOf(change1, change2, change3)
    }

    // -------------------------------------------------------------------------
    // Test 1 — COMMIT_WORKFLOW_UI is present, has included changes
    // -------------------------------------------------------------------------

    @Test
    fun `resolveCheckedChanges prefers COMMIT_WORKFLOW_UI includedChanges when present`() {
        val ui = mockk<CommitWorkflowUi>(relaxed = true)
        every { ui.getIncludedChanges() } returns listOf(change1, change2)
        every { event.getData(VcsDataKeys.COMMIT_WORKFLOW_UI) } returns ui

        val (changes, tag) = action.resolveCheckedChanges(event)

        assertEquals(listOf(change1, change2), changes)
        assertEquals("COMMIT_WORKFLOW_UI", tag)
    }

    // -------------------------------------------------------------------------
    // Test 2 — COMMIT_WORKFLOW_UI present but nothing checked → empty, authoritative
    // -------------------------------------------------------------------------

    @Test
    fun `resolveCheckedChanges returns empty list with COMMIT_WORKFLOW_UI source when handler present but nothing checked`() {
        val ui = mockk<CommitWorkflowUi>(relaxed = true)
        every { ui.getIncludedChanges() } returns emptyList()
        every { event.getData(VcsDataKeys.COMMIT_WORKFLOW_UI) } returns ui

        val (changes, tag) = action.resolveCheckedChanges(event)

        // Non-null UI is authoritative — we must NOT fall through to CheckinProjectPanel or
        // SELECTED_CHANGES just because the list happens to be empty.
        assertTrue(changes.isEmpty())
        assertEquals("COMMIT_WORKFLOW_UI", tag)
    }

    // -------------------------------------------------------------------------
    // Test 3 — No COMMIT_WORKFLOW_UI → falls back to CheckinProjectPanel
    // -------------------------------------------------------------------------

    @Test
    fun `resolveCheckedChanges falls back to CHECKIN_PROJECT_PANEL when workflow UI absent`() {
        val panel = mockk<CheckinProjectPanel>(relaxed = true)
        every { panel.selectedChanges } returns listOf(change1, change3)
        every { event.getData(Refreshable.PANEL_KEY) } returns panel

        val (changes, tag) = action.resolveCheckedChanges(event)

        assertEquals(listOf(change1, change3), changes)
        assertEquals("CHECKIN_PROJECT_PANEL", tag)
    }

    // -------------------------------------------------------------------------
    // Test 4 — No COMMIT_WORKFLOW_UI, no panel → falls back to SELECTED_CHANGES
    // -------------------------------------------------------------------------

    @Test
    fun `resolveCheckedChanges falls back to SELECTED_CHANGES when panels absent`() {
        every { event.getData(VcsDataKeys.SELECTED_CHANGES) } returns arrayOf(change2)

        val (changes, tag) = action.resolveCheckedChanges(event)

        assertEquals(listOf(change2), changes)
        assertEquals("SELECTED_CHANGES", tag)
    }

    // -------------------------------------------------------------------------
    // Test 5 — All sources absent → NONE with empty list
    // -------------------------------------------------------------------------

    @Test
    fun `resolveCheckedChanges returns NONE source with empty list when all sources absent`() {
        every { event.getData(VcsDataKeys.SELECTED_CHANGES) } returns null

        val (changes, tag) = action.resolveCheckedChanges(event)

        assertTrue(changes.isEmpty())
        assertEquals("NONE", tag)
    }

    // -------------------------------------------------------------------------
    // Test 6 — VcsDataKeys.CHANGES must NEVER be used as a source
    // -------------------------------------------------------------------------

    @Test
    fun `resolveCheckedChanges NEVER uses VcsDataKeys CHANGES`() {
        // CHANGES has 3 items, but all other authoritative sources are null.
        // The result must be (emptyList, "NONE") — not the 3 items from CHANGES.
        every { event.getData(VcsDataKeys.COMMIT_WORKFLOW_UI) } returns null
        every { event.getData(Refreshable.PANEL_KEY) } returns null
        every { event.getData(VcsDataKeys.SELECTED_CHANGES) } returns null
        every { event.getData(VcsDataKeys.CHANGES) } returns arrayOf(change1, change2, change3)

        val (changes, tag) = action.resolveCheckedChanges(event)

        assertTrue(changes.isEmpty(), "Must not return items from VcsDataKeys.CHANGES (size=${changes.size})")
        assertEquals("NONE", tag)

        // Verify CHANGES was never even accessed — belt-and-suspenders
        verify(exactly = 0) { event.getData(VcsDataKeys.CHANGES) }
    }
}
