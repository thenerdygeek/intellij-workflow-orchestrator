package com.workflow.orchestrator.handover.ui

import com.workflow.orchestrator.handover.ui.tabs.ChecksTab
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ChecksTab.checklistNeedsRebuild] — the pure equality-gate
 * function that determines whether the checklist panel needs to be rebuilt on
 * a new HandoverState emission.
 *
 * P2-20 fix: [ChecksTab.rebuildChecklist] previously called removeAll + rebuilt
 * all four checklist rows on every HandoverState emission, even when nothing
 * changed. The equality gate skips the rebuild when the four boolean flags
 * (copyrightFixed, prCreated, jiraCommentPosted, todayWorkLogged) are identical
 * to the previously rendered state.
 *
 * These tests run without IntelliJ or Swing infrastructure.
 */
class ChecksTabEqualityGateTest {

    @Test
    fun `null previous state always needs rebuild`() {
        assertTrue(
            ChecksTab.checklistNeedsRebuild(
                prev = null,
                next = booleanArrayOf(false, false, false, false)
            )
        )
    }

    @Test
    fun `identical flags do not need rebuild`() {
        val flags = booleanArrayOf(true, false, true, false)
        assertFalse(
            ChecksTab.checklistNeedsRebuild(
                prev = flags.copyOf(),
                next = flags.copyOf()
            )
        )
    }

    @Test
    fun `all-false identical flags do not need rebuild`() {
        assertFalse(
            ChecksTab.checklistNeedsRebuild(
                prev = booleanArrayOf(false, false, false, false),
                next = booleanArrayOf(false, false, false, false)
            )
        )
    }

    @Test
    fun `all-true identical flags do not need rebuild`() {
        assertFalse(
            ChecksTab.checklistNeedsRebuild(
                prev = booleanArrayOf(true, true, true, true),
                next = booleanArrayOf(true, true, true, true)
            )
        )
    }

    @Test
    fun `first flag changes needs rebuild`() {
        assertTrue(
            ChecksTab.checklistNeedsRebuild(
                prev = booleanArrayOf(false, false, false, false),
                next = booleanArrayOf(true, false, false, false)
            )
        )
    }

    @Test
    fun `last flag changes needs rebuild`() {
        assertTrue(
            ChecksTab.checklistNeedsRebuild(
                prev = booleanArrayOf(true, true, true, false),
                next = booleanArrayOf(true, true, true, true)
            )
        )
    }

    @Test
    fun `second flag changes needs rebuild`() {
        assertTrue(
            ChecksTab.checklistNeedsRebuild(
                prev = booleanArrayOf(true, false, true, true),
                next = booleanArrayOf(true, true, true, true)
            )
        )
    }

    @Test
    fun `third flag changes needs rebuild`() {
        assertTrue(
            ChecksTab.checklistNeedsRebuild(
                prev = booleanArrayOf(false, false, false, false),
                next = booleanArrayOf(false, false, true, false)
            )
        )
    }

    @Test
    fun `gate suppresses spurious emissions on same state`() {
        // Simulate a state that is emitted twice with no change — only the first
        // emission should trigger a rebuild.
        val flags = booleanArrayOf(true, true, false, false)
        assertTrue(ChecksTab.checklistNeedsRebuild(null, flags)) // first emission
        assertFalse(ChecksTab.checklistNeedsRebuild(flags, flags)) // duplicate emission
        val changed = booleanArrayOf(true, true, true, false)
        assertTrue(ChecksTab.checklistNeedsRebuild(flags, changed)) // real change
        assertFalse(ChecksTab.checklistNeedsRebuild(changed, changed)) // duplicate again
    }
}
