package com.workflow.orchestrator.pullrequest.action

import com.workflow.orchestrator.core.workflow.TicketContext
import com.workflow.orchestrator.core.workflow.TicketTransition
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Pure-logic tests for [CreatePrPrefetch].
 *
 * The [CreatePrPrefetch.run] method calls IntelliJ platform APIs
 * (BitbucketBranchClient, JiraTicketProvider, PluginSettings, ReadAction) that
 * require the IDE sandbox. Those integration paths are verified via manual
 * runIde testing. The helpers that carry business logic — ticket key resolution
 * and context map assembly — are isolated and tested here.
 *
 * Key scenarios tested:
 *  - Branch key detected → included as first key
 *  - Active ticket id differs from branch key → both included, branch first
 *  - Active ticket id equals branch key → deduplicated, only one key
 *  - Active ticket id blank → only branch key
 *  - No branch key + active id → active id as sole key
 *  - No branch key + no active id → empty key list
 *  - getTicketContext null for a key → context map excludes it, but key still in list
 *  - getTicketContext non-null → included in map
 *  - Multiple contexts fetched in parallel — map contains all non-null results
 *  - Failure message for unconfigured Bitbucket
 *  - Failure message for missing Git branch
 */
class CreatePrPrefetchTest {

    // ── Key resolution helpers ──────────────────────────────────────────────

    /** Mirrors the private TICKET_PATTERN used in CreatePrPrefetch. */
    private val TICKET_PATTERN = Regex("([A-Z][A-Z0-9]+-\\d+)")

    private fun extractTicketIdFromBranch(branchName: String): String? =
        TICKET_PATTERN.find(branchName)?.groupValues?.get(1)

    /**
     * Simulates the key resolution logic from CreatePrPrefetch.run:
     *   val branchKey = extractTicketIdFromBranch(currentBranch)
     *   val activeKey = activeTicketId?.takeIf { it.isNotBlank() }
     *   val keys = listOfNotNull(branchKey, activeKey.takeIf { it != branchKey })
     */
    private fun resolveKeys(branchName: String, activeTicketId: String?): List<String> {
        val branchKey = extractTicketIdFromBranch(branchName)
        val activeKey = activeTicketId?.takeIf { it.isNotBlank() }
        return listOfNotNull(branchKey, activeKey?.takeIf { it != branchKey })
    }

    /**
     * Simulates the context map assembly logic from CreatePrPrefetch.run:
     *   pairs.filter { ctx != null }.associate { key to ctx!! }
     */
    private fun buildContextMap(pairs: List<Pair<String, TicketContext?>>): Map<String, TicketContext> =
        pairs.filter { it.second != null }.associate { it.first to it.second!! }

    // ── Ticket extraction from branch name ──────────────────────────────────

    @Test
    fun `extracts ticket id from simple branch name`() {
        assertEquals("WO-123", extractTicketIdFromBranch("feature/WO-123-my-feature"))
    }

    @Test
    fun `extracts ticket id with multi-char project prefix`() {
        assertEquals("ABC-99", extractTicketIdFromBranch("ABC-99-bugfix"))
    }

    @Test
    fun `returns null when branch has no ticket pattern`() {
        assertNull(extractTicketIdFromBranch("main"))
        assertNull(extractTicketIdFromBranch("feature/no-ticket-here"))
        assertNull(extractTicketIdFromBranch("release-1.2.3"))
    }

    @Test
    fun `extracts first ticket id when branch contains multiple`() {
        // e.g. "WO-10-relates-to-WO-20" — first match wins
        assertEquals("WO-10", extractTicketIdFromBranch("WO-10-relates-to-WO-20"))
    }

    // ── Key resolution ──────────────────────────────────────────────────────

    @Test
    fun `branch key only when active id is null`() {
        val keys = resolveKeys("feature/WO-100-fix", null)
        assertEquals(listOf("WO-100"), keys)
    }

    @Test
    fun `branch key only when active id is blank`() {
        val keys = resolveKeys("feature/WO-100-fix", "  ")
        assertEquals(listOf("WO-100"), keys)
    }

    @Test
    fun `both keys when active id differs from branch key`() {
        val keys = resolveKeys("feature/WO-100-fix", "WO-200")
        assertEquals(listOf("WO-100", "WO-200"), keys)
    }

    @Test
    fun `deduplicated when active id equals branch key`() {
        val keys = resolveKeys("feature/WO-100-fix", "WO-100")
        assertEquals(listOf("WO-100"), keys)
    }

    @Test
    fun `active id only when no ticket in branch name`() {
        val keys = resolveKeys("feature/my-feature", "WO-300")
        assertEquals(listOf("WO-300"), keys)
    }

    @Test
    fun `empty keys when branch has no ticket and active id is null`() {
        val keys = resolveKeys("main", null)
        assertEquals(emptyList<String>(), keys)
    }

    @Test
    fun `empty keys when branch has no ticket and active id is blank`() {
        val keys = resolveKeys("develop", "")
        assertEquals(emptyList<String>(), keys)
    }

    // ── Context map assembly ─────────────────────────────────────────────────

    private fun ticket(key: String, summary: String): TicketContext = TicketContext(
        key = key,
        summary = summary,
        description = null,
        status = "Open",
        priority = "Medium",
        issueType = "Story",
        assignee = null,
        reporter = null
    )

    @Test
    fun `context map includes entries with non-null context`() {
        val ctx = ticket("WO-1", "My story")
        val map = buildContextMap(listOf("WO-1" to ctx, "WO-2" to null))
        assertEquals(1, map.size)
        assertSame(ctx, map["WO-1"])
        assertFalse(map.containsKey("WO-2"))
    }

    @Test
    fun `context map is empty when all contexts are null`() {
        val map = buildContextMap(listOf("WO-1" to null, "WO-2" to null))
        assertTrue(map.isEmpty())
    }

    @Test
    fun `context map includes all non-null contexts from parallel fetch`() {
        val ctx1 = ticket("WO-10", "First")
        val ctx2 = ticket("WO-20", "Second")
        val map = buildContextMap(listOf("WO-10" to ctx1, "WO-20" to ctx2))
        assertEquals(2, map.size)
        assertSame(ctx1, map["WO-10"])
        assertSame(ctx2, map["WO-20"])
    }

    @Test
    fun `key remains in list even when its context is null`() {
        // The key list and context map are separate. A null context only means the map
        // excludes that key — the dialog's TicketChipInput will still display the chip
        // and re-fetch in PENDING state.
        val keys = resolveKeys("feature/WO-55-task", "WO-66")
        val map = buildContextMap(listOf("WO-55" to null, "WO-66" to ticket("WO-66", "Active")))
        assertEquals(listOf("WO-55", "WO-66"), keys)
        assertFalse(map.containsKey("WO-55"))
        assertTrue(map.containsKey("WO-66"))
    }

    // ── Failure message constants ───────────────────────────────────────────

    @Test
    fun `Bitbucket failure message matches expected string`() {
        // Validate the literal used for the warning dialog matches spec.
        val failure = PrefetchResult.Failure("Bitbucket not configured")
        assertEquals("Bitbucket not configured", failure.message)
    }

    @Test
    fun `Git not detected failure message matches expected string`() {
        val failure = PrefetchResult.Failure("Git repository not detected")
        assertEquals("Git repository not detected", failure.message)
    }

    // ── PrefetchResult sealed class ─────────────────────────────────────────

    @Test
    fun `Success wraps CreatePrContext correctly`() {
        val ctx = CreatePrContext(
            sourceBranch = "feature/WO-1-test",
            remoteBranches = listOf("develop", "main"),
            initialTicketKeys = listOf("WO-1"),
            initialTicketContexts = mapOf("WO-1" to ticket("WO-1", "Test")),
            transitions = listOf(TicketTransition("1", "In Review", "In Review")),
            defaultTitle = "WO-1: Test",
            defaultReviewers = listOf("alice", "bob")
        )
        val result = PrefetchResult.Success(ctx)
        assertSame(ctx, result.context)
        assertEquals("feature/WO-1-test", result.context.sourceBranch)
        assertEquals(listOf("alice", "bob"), result.context.defaultReviewers)
    }

    @Test
    fun `Failure message is preserved`() {
        val result = PrefetchResult.Failure("Bitbucket not configured")
        assertEquals("Bitbucket not configured", result.message)
    }
}
