package com.workflow.orchestrator.mockserver.bitbucket

import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class MockBitbucketUser(
    val name: String,
    val displayName: String,
    val emailAddress: String,
)

@Serializable
data class MockBitbucketReviewer(
    val user: MockBitbucketUser,
    val role: String = "REVIEWER",
    val approved: Boolean = false,
    val status: String = "UNAPPROVED",
)

@Serializable
data class MockBitbucketRef(
    val id: String,
    val displayId: String,
    val latestCommit: String,
    val repoSlug: String,
    val projectKey: String,
)

@Serializable
data class MockBitbucketComment(
    val id: Long,
    var text: String,
    val authorName: String,
    val authorDisplayName: String,
    val createdDate: Long,
    var version: Int = 0,
)

@Serializable
data class MockBitbucketCommit(
    val id: String,
    val displayId: String,
    val message: String,
    val authorName: String,
    val authorTimestamp: Long,
)

@Serializable
data class MockBitbucketChange(
    val path: String,
    val type: String,   // ADD | MODIFY | DELETE
)

@Serializable
data class MockBitbucketPr(
    val id: Int,
    var title: String,
    var description: String,
    var state: String,   // OPEN | MERGED | DECLINED
    var version: Int,
    val fromRef: MockBitbucketRef,
    val toRef: MockBitbucketRef,
    val author: MockBitbucketUser,
    val reviewers: MutableList<MockBitbucketReviewer>,
    val createdDate: Long,
    var updatedDate: Long,
    val comments: MutableList<MockBitbucketComment>,
    val commits: List<MockBitbucketCommit>,
    val changes: List<MockBitbucketChange>,
    val diff: String,
)

class BitbucketState {
    var currentUser: MockBitbucketUser =
        MockBitbucketUser("mock.user", "Mock User", "mock.user@example.com")
    val prs: ConcurrentHashMap<Int, MockBitbucketPr> = ConcurrentHashMap()
    var nextCommentId: Long = 100L

    fun reset() {
        prs.clear()
        nextCommentId = 100L
        currentUser = MockBitbucketUser("mock.user", "Mock User", "mock.user@example.com")
    }
}
