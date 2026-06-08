package com.workflow.orchestrator.pullrequest.ui

import com.workflow.orchestrator.core.bitbucket.BitbucketMergeStatus
import com.workflow.orchestrator.core.bitbucket.BitbucketMergeVeto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MergeButtonStateDeriverTest {

    @Test
    fun `null status leaves icon and enabled untouched with unknown tooltip`() {
        val s = MergeButtonStateDeriver.derive(null)
        assertEquals(MergeButtonStateDeriver.IconKind.UNCHANGED, s.icon)
        assertFalse(s.forceDisable, "null status must not force-disable the button")
        assertEquals("Merge status unknown", s.tooltip)
    }

    @Test
    fun `mergeable and clean shows the merge icon and standard tooltip`() {
        val s = MergeButtonStateDeriver.derive(BitbucketMergeStatus(canMerge = true, conflicted = false))
        assertEquals(MergeButtonStateDeriver.IconKind.MERGE, s.icon)
        assertFalse(s.forceDisable, "a mergeable PR must not be force-disabled (renderPrHeader owns enabled)")
        assertEquals("Merge this pull request", s.tooltip)
    }

    @Test
    fun `mergeable but conflicted shows the warning icon and conflict tooltip`() {
        val s = MergeButtonStateDeriver.derive(BitbucketMergeStatus(canMerge = true, conflicted = true))
        assertEquals(MergeButtonStateDeriver.IconKind.WARNING, s.icon)
        assertFalse(s.forceDisable)
        assertTrue(s.tooltip.contains("conflicts detected"), "tooltip should warn about conflicts")
    }

    @Test
    fun `not mergeable with vetoes force-disables and lists the veto summaries`() {
        val s = MergeButtonStateDeriver.derive(
            BitbucketMergeStatus(
                canMerge = false,
                conflicted = false,
                vetoes = listOf(
                    BitbucketMergeVeto(summaryMessage = "Needs 2 approvals"),
                    BitbucketMergeVeto(summaryMessage = "Build failing"),
                ),
            )
        )
        assertEquals(MergeButtonStateDeriver.IconKind.MERGE, s.icon)
        assertTrue(s.forceDisable, "a non-mergeable PR must force-disable the button")
        assertTrue(s.tooltip.contains("Needs 2 approvals"))
        assertTrue(s.tooltip.contains("Build failing"))
    }

    @Test
    fun `not mergeable with no vetoes force-disables with a generic tooltip`() {
        val s = MergeButtonStateDeriver.derive(BitbucketMergeStatus(canMerge = false, conflicted = false))
        assertTrue(s.forceDisable)
        assertEquals("Cannot merge — preconditions not met", s.tooltip)
    }
}
