package com.workflow.orchestrator.pullrequest.service

import com.workflow.orchestrator.core.bitbucket.BitbucketPrDetail
import com.workflow.orchestrator.core.bitbucket.BitbucketPrReviewer
import com.workflow.orchestrator.core.bitbucket.BitbucketUser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for the pure mutators that [PrActionService] passes to
 * [com.workflow.orchestrator.core.bitbucket.BitbucketBranchClient.modifyPullRequest].
 *
 * The retry semantics in `modifyPullRequest` (re-invoke the mutator with the
 * refetched PR on 409) only work if each mutator threads `current.version`
 * through the resulting [com.workflow.orchestrator.core.bitbucket.BitbucketPrUpdateRequest]
 * — these tests pin that contract per audit P0 finding #2 (PR 3 of the
 * 2026-05-07 write-ops fix plan).
 */
class PrActionServiceMutatorsTest {

    private fun pr(
        id: Int = 42,
        title: String = "Original",
        description: String? = "Original desc",
        version: Int = 3,
        reviewers: List<String> = listOf("alice", "bob"),
    ): BitbucketPrDetail = BitbucketPrDetail(
        id = id,
        title = title,
        description = description,
        state = "OPEN",
        version = version,
        reviewers = reviewers.map { BitbucketPrReviewer(user = BitbucketUser(name = it, displayName = it)) },
    )

    @Test
    fun `updateTitleMutator threads version and preserves description plus reviewers`() {
        val current = pr(title = "Old", description = "Keep me", version = 7,
            reviewers = listOf("alice", "bob"))

        val req = updateTitleMutator(current, "New title")

        assertEquals("New title", req.title)
        assertEquals("Keep me", req.description)
        assertEquals(7, req.version, "Mutator must thread current.version so the retry sees fresh value")
        assertEquals(listOf("alice", "bob"), req.reviewers.map { it.user.name })
    }

    @Test
    fun `updateTitleMutator on retry sees refreshed version`() {
        val first = pr(version = 3, title = "Old", description = "d")
        val second = pr(version = 5, title = "Old", description = "d")  // someone else bumped it

        val firstReq = updateTitleMutator(first, "New")
        val secondReq = updateTitleMutator(second, "New")

        // Different version on each invocation — proves the retry's "re-apply with refreshed version" works.
        assertEquals(3, firstReq.version)
        assertEquals(5, secondReq.version)
    }

    @Test
    fun `updateTitleMutator handles null description as empty string`() {
        val current = pr(description = null)
        val req = updateTitleMutator(current, "New")
        assertEquals("", req.description)
    }

    @Test
    fun `addReviewerMutator threads version and appends new reviewer`() {
        val current = pr(version = 9, reviewers = listOf("alice", "bob"))

        val req = addReviewerMutator(current, "carol")

        assertEquals(9, req.version)
        assertEquals(listOf("alice", "bob", "carol"), req.reviewers.map { it.user.name })
        // Title + description preserved
        assertEquals(current.title, req.title)
        assertEquals(current.description, req.description)
    }

    @Test
    fun `addReviewerMutator is idempotent when user already on PR after retry race`() {
        // Simulates the case where attempt-1 raced with another caller adding the same
        // user. modifyPullRequest's retry refetches: the new state has 'carol' already.
        // The mutator returns the same set so the PUT body matches current state and
        // Bitbucket's set-replace semantics don't error.
        val refetched = pr(reviewers = listOf("alice", "carol"))

        val req = addReviewerMutator(refetched, "carol")

        assertEquals(listOf("alice", "carol"), req.reviewers.map { it.user.name })
    }

    @Test
    fun `removeReviewerMutator threads version and filters target user`() {
        val current = pr(version = 4, reviewers = listOf("alice", "bob", "carol"))

        val req = removeReviewerMutator(current, "bob")

        assertEquals(4, req.version)
        assertEquals(listOf("alice", "carol"), req.reviewers.map { it.user.name })
        assertEquals(current.title, req.title)
        assertEquals(current.description, req.description)
    }

    @Test
    fun `removeReviewerMutator is no-op when user already absent`() {
        // Race: by the time we retry, the user was already removed by another caller.
        // The mutator just preserves the current reviewer list; no error.
        val refetched = pr(reviewers = listOf("alice", "carol"))

        val req = removeReviewerMutator(refetched, "bob")

        assertEquals(listOf("alice", "carol"), req.reviewers.map { it.user.name })
    }

    @Test
    fun `removeReviewerMutator handles empty reviewer list`() {
        val current = pr(reviewers = emptyList())
        val req = removeReviewerMutator(current, "bob")
        assertTrue(req.reviewers.isEmpty())
    }
}
