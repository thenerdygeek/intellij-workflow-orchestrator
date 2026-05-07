package com.workflow.orchestrator.core.bitbucket

import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for the DTO + matcher logic introduced by PR 6 of the 2026-05-07
 * write-ops fix plan (audit P1 finding #6).
 *
 * Two surfaces under test:
 *  1. JSON deserialisation round-trip — the real Bitbucket DC shape from
 *     `tools/atlassian-probe/Result_Bitbucket/.../default_reviewers_conditions.json`
 *     uses a nested `type: {id, name}` object on each matcher; we must decode it
 *     without losing fields.
 *  2. [RefMatcher.matches] semantics for each [RefMatcherType].
 *  3. [BitbucketBranchClient.getDefaultReviewersForBranch] returns the union of
 *     reviewers across only the matching conditions, not all of them.
 */
class DefaultReviewerConditionTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** Probe-derived sample (sanitised). */
    private val probeBody = """[
        {
            "id": 2525,
            "scope": {"type": "REPOSITORY", "resourceId": 53499},
            "sourceRefMatcher": {"id": "ANY_REF_MATCHER_ID", "displayId": "Any branch", "type": {"id": "ANY_REF", "name": "Any branch"}, "active": true},
            "targetRefMatcher": {"id": "refs/heads/develop", "displayId": "develop", "type": {"id": "BRANCH", "name": "Branch"}, "active": true},
            "reviewers": [
                {"name": "alice", "displayName": "Alice", "active": true, "id": 1, "slug": "alice", "type": "NORMAL"},
                {"name": "bob",   "displayName": "Bob",   "active": true, "id": 2, "slug": "bob",   "type": "NORMAL"}
            ],
            "requiredApprovals": 1
        },
        {
            "id": 5105,
            "scope": {"type": "REPOSITORY", "resourceId": 53499},
            "sourceRefMatcher": {"id": "feature", "displayId": "feature", "type": {"id": "MODEL_CATEGORY", "name": "Branching model category"}, "active": true},
            "targetRefMatcher": {"id": "refs/heads/master", "displayId": "master", "type": {"id": "BRANCH", "name": "Branch"}, "active": true},
            "reviewers": [
                {"name": "carol", "displayName": "Carol", "active": true, "id": 3, "slug": "carol", "type": "NORMAL"}
            ],
            "requiredApprovals": 2
        },
        {
            "id": 9001,
            "sourceRefMatcher": {"id": "release/*", "displayId": "release/*", "type": {"id": "PATTERN", "name": "Pattern"}, "active": true},
            "targetRefMatcher": {"id": "ANY_REF_MATCHER_ID", "displayId": "Any branch", "type": {"id": "ANY_REF", "name": "Any branch"}, "active": true},
            "reviewers": [
                {"name": "dave", "displayName": "Dave", "active": true, "id": 4, "slug": "dave", "type": "NORMAL"}
            ],
            "requiredApprovals": 1
        }
    ]""".trimIndent()

    @Test
    fun `decodes probe shape without losing matcher fields`() {
        val parsed = json.decodeFromString<List<DefaultReviewerCondition>>(probeBody)
        assertEquals(3, parsed.size)
        val first = parsed[0]
        assertEquals(2525, first.id)
        assertEquals(RefMatcherType.ANY_REF, first.sourceRefMatcher.matcherType)
        assertEquals(RefMatcherType.BRANCH, first.targetRefMatcher.matcherType)
        assertEquals("refs/heads/develop", first.targetRefMatcher.id)
        assertEquals(2, first.reviewers.size)
        assertEquals(listOf("alice", "bob"), first.reviewers.map { it.name })
    }

    @Test
    fun `decodes MODEL_CATEGORY type from probe`() {
        val parsed = json.decodeFromString<List<DefaultReviewerCondition>>(probeBody)
        assertEquals(RefMatcherType.MODEL_CATEGORY, parsed[1].sourceRefMatcher.matcherType)
        assertEquals("feature", parsed[1].sourceRefMatcher.id)
    }

    @Test
    fun `decodes PATTERN type from probe`() {
        val parsed = json.decodeFromString<List<DefaultReviewerCondition>>(probeBody)
        assertEquals(RefMatcherType.PATTERN, parsed[2].sourceRefMatcher.matcherType)
        assertEquals("release/*", parsed[2].sourceRefMatcher.id)
    }

    @Test
    fun `decoder defaults unknown type id to ANY_REF`() {
        val body = """[{"id":1,"sourceRefMatcher":{"id":"x","displayId":"x","type":{"id":"NEW_KIND","name":"New"}},"targetRefMatcher":{"id":"y","displayId":"y","type":{"id":"BRANCH","name":"Branch"}}}]"""
        val parsed = json.decodeFromString<List<DefaultReviewerCondition>>(body)
        assertEquals(RefMatcherType.ANY_REF, parsed[0].sourceRefMatcher.matcherType,
            "Unknown matcher types must fall back to ANY_REF — never crash on a Bitbucket upgrade")
    }

    // -------------------------- RefMatcher.matches ---------------------------

    private fun matcher(type: String, id: String) = RefMatcher(
        id = id, displayId = id,
        type = RefMatcherTypeDescriptor(id = type, name = type),
    )

    @Test
    fun `BRANCH matches on full ref or displayId`() {
        val m = matcher("BRANCH", "refs/heads/develop")
        assertTrue(m.matches("develop"))
        assertTrue(m.matches("refs/heads/develop"))
        assertFalse(m.matches("master"))
        assertFalse(m.matches("feature/develop"))
    }

    @Test
    fun `MODEL_BRANCH matches like BRANCH`() {
        val m = matcher("MODEL_BRANCH", "refs/heads/master")
        assertTrue(m.matches("master"))
        assertTrue(m.matches("refs/heads/master"))
        assertFalse(m.matches("develop"))
    }

    @Test
    fun `MODEL_CATEGORY matches the category prefix`() {
        val m = matcher("MODEL_CATEGORY", "feature")
        assertTrue(m.matches("feature/login"))
        assertTrue(m.matches("feature/very/nested/branch"))
        assertTrue(m.matches("feature"))   // exact category name (rare but valid)
        assertFalse(m.matches("featurefoo")) // no slash boundary → no match
        assertFalse(m.matches("hotfix/x"))
    }

    @Test
    fun `ANY_REF always matches`() {
        val m = matcher("ANY_REF", "ANY_REF_MATCHER_ID")
        assertTrue(m.matches("anything"))
        assertTrue(m.matches("refs/heads/whatever"))
        assertTrue(m.matches(""))
    }

    @Test
    fun `PATTERN supports glob star and question`() {
        val star = matcher("PATTERN", "release/*")
        assertTrue(star.matches("release/1.0"))
        assertTrue(star.matches("release/2026-05"))
        assertFalse(star.matches("master"))
        assertFalse(star.matches("feature/x"))

        val q = matcher("PATTERN", "v?.0")
        assertTrue(q.matches("v1.0"))
        assertTrue(q.matches("v9.0"))
        assertFalse(q.matches("v10.0")) // ? = exactly one char
    }

    @Test
    fun `PATTERN escapes regex meta-chars in non-glob characters`() {
        val m = matcher("PATTERN", "release-1.0")
        assertTrue(m.matches("release-1.0"))
        assertFalse(m.matches("release-100"), "the literal '.' must not match arbitrary char")
    }

    // ----------------- getDefaultReviewersForBranch end-to-end ---------------

    private lateinit var server: MockWebServer
    private lateinit var client: BitbucketBranchClient

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        client = BitbucketBranchClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { "test-token" },
        )
    }

    @AfterEach
    fun tearDown() { server.shutdown() }

    @Test
    fun `getDefaultReviewersForBranch filters union by source plus target matcher`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(probeBody))

        // Source=feature/login, Target=master matches only condition #5105 (feature → master)
        val r = client.getDefaultReviewersForBranch(
            repo = RepoCoords("P", "r"),
            sourceBranch = "feature/login",
            targetBranch = "master",
        )

        assertTrue(r is ApiResult.Success)
        val users = (r as ApiResult.Success).data
        assertEquals(listOf("carol"), users.map { it.name },
            "Only the feature→master condition should match — alice/bob/dave belong to other conditions")
    }

    @Test
    fun `getDefaultReviewersForBranch matches multiple conditions when all matchers agree`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(probeBody))

        // Source=anything, Target=develop matches only the ANY_REF→develop condition (#2525)
        val r = client.getDefaultReviewersForBranch(
            repo = RepoCoords("P", "r"),
            sourceBranch = "feature/x",
            targetBranch = "develop",
        )
        assertTrue(r is ApiResult.Success)
        assertEquals(setOf("alice", "bob"), (r as ApiResult.Success).data.map { it.name }.toSet())
    }

    @Test
    fun `getDefaultReviewersForBranch returns empty when nothing matches`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(probeBody))

        // Source=hotfix/x, Target=staging matches no condition.
        val r = client.getDefaultReviewersForBranch(
            repo = RepoCoords("P", "r"),
            sourceBranch = "hotfix/x",
            targetBranch = "staging",
        )
        assertTrue(r is ApiResult.Success)
        assertEquals(0, (r as ApiResult.Success).data.size)
    }

    @Test
    fun `getDefaultReviewersForBranch matches PATTERN sources`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(probeBody))

        // Source=release/2026-05 → PATTERN release/* matches (cond 9001), target=ANY → matches anything.
        val r = client.getDefaultReviewersForBranch(
            repo = RepoCoords("P", "r"),
            sourceBranch = "release/2026-05",
            targetBranch = "anything",
        )
        assertTrue(r is ApiResult.Success)
        assertTrue((r as ApiResult.Success).data.any { it.name == "dave" })
    }

    @Test
    fun `getDefaultReviewersForBranch returns 404 as empty success`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val r = client.getDefaultReviewersForBranch(
            repo = RepoCoords("P", "r"),
            sourceBranch = "feature/x",
            targetBranch = "develop",
        )
        assertTrue(r is ApiResult.Success)
        assertEquals(0, (r as ApiResult.Success).data.size)
    }

    @Test
    fun `getDefaultReviewers union-all retains legacy callers' behaviour`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(probeBody))

        val r = client.getDefaultReviewers("P", "r")

        assertTrue(r is ApiResult.Success)
        // alice + bob from cond 2525, carol from 5105, dave from 9001 = all four de-duplicated.
        assertEquals(setOf("alice", "bob", "carol", "dave"), (r as ApiResult.Success).data.map { it.name }.toSet())
    }
}
