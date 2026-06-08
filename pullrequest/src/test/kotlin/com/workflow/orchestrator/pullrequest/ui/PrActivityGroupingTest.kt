package com.workflow.orchestrator.pullrequest.ui

import com.workflow.orchestrator.core.bitbucket.BitbucketCommentAnchor
import com.workflow.orchestrator.core.bitbucket.BitbucketPrActivity
import com.workflow.orchestrator.core.bitbucket.BitbucketPrComment
import com.workflow.orchestrator.core.bitbucket.BitbucketUser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PrActivityGroupingTest {

    private val user = BitbucketUser(name = "alice")

    private fun activity(
        id: Long,
        action: String = "COMMENTED",
        commentAnchor: BitbucketCommentAnchor? = null,
        commentAnchorViaComment: BitbucketCommentAnchor? = null,
    ) = BitbucketPrActivity(
        id = id,
        action = action,
        comment = commentAnchorViaComment?.let {
            BitbucketPrComment(id = id, text = "c$id", author = user, anchor = it)
        },
        commentAnchor = commentAnchor,
        user = user,
    )

    private fun anchor(path: String, line: Int = 0) = BitbucketCommentAnchor(path = path, line = line)

    @Test
    fun `anchorOf prefers the activity commentAnchor over the comment anchor`() {
        val a = activity(1, commentAnchor = anchor("top.kt"), commentAnchorViaComment = anchor("inner.kt"))
        assertEquals("top.kt", PrActivityGrouping.anchorOf(a)?.path)
    }

    @Test
    fun `anchorOf falls back to the comment anchor`() {
        val a = activity(1, commentAnchorViaComment = anchor("inner.kt"))
        assertEquals("inner.kt", PrActivityGrouping.anchorOf(a)?.path)
    }

    @Test
    fun `anchorOf is null when neither is present`() {
        assertNull(PrActivityGrouping.anchorOf(activity(1)))
    }

    @Test
    fun `isInline is true only when an anchor has a non-blank path`() {
        assertTrue(PrActivityGrouping.isInline(activity(1, commentAnchor = anchor("a.kt"))))
        assertFalse(PrActivityGrouping.isInline(activity(2, commentAnchor = anchor(""))), "blank path is not inline")
        assertFalse(PrActivityGrouping.isInline(activity(3)), "no anchor is not inline")
    }

    @Test
    fun `partition splits inline comments from general activities`() {
        val inline = activity(1, commentAnchor = anchor("a.kt", 10))
        val general = activity(2, action = "MERGED")
        val blankAnchor = activity(3, commentAnchor = anchor(""))
        val split = PrActivityGrouping.partition(listOf(inline, general, blankAnchor))
        assertEquals(listOf(1L), split.inline.map { it.id })
        assertEquals(listOf(2L, 3L), split.general.map { it.id })
    }

    @Test
    fun `groupInlineByAnchor groups same path-line together and preserves first-seen order`() {
        val a1 = activity(1, commentAnchor = anchor("a.kt", 10))
        val b = activity(2, commentAnchor = anchor("b.kt", 5))
        val a2 = activity(3, commentAnchor = anchor("a.kt", 10))
        val grouped = PrActivityGrouping.groupInlineByAnchor(listOf(a1, b, a2))
        assertEquals(
            listOf(
                PrActivityGrouping.AnchorKey("a.kt", 10),
                PrActivityGrouping.AnchorKey("b.kt", 5),
            ),
            grouped.keys.toList(),
        )
        assertEquals(listOf(1L, 3L), grouped[PrActivityGrouping.AnchorKey("a.kt", 10)]?.map { it.id })
        assertEquals(listOf(2L), grouped[PrActivityGrouping.AnchorKey("b.kt", 5)]?.map { it.id })
    }
}
