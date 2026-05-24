package com.workflow.orchestrator.agent.delegation

import com.workflow.orchestrator.agent.session.DelegationMetadata
import com.workflow.orchestrator.agent.session.HistoryItem
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for Plan 1 code-review followup fixes (F1–F6).
 *
 * F2: HistoryItem.delegated round-trips through sessions.json serialization.
 * F3: DelegationTool self-gates on the outbound setting (covers the former
 *     delegation_send / delegation_close per-tool gates after Plan 5 consolidation).
 * (F1/F4/F5/F6 are structural/threading fixes covered by source-text pins and the
 * updated E2E + server tests.)
 */
class DelegationReviewFollowupsTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ── F2: HistoryItem.delegated ──────────────────────────────────────────────

    @Test
    fun `F2 - HistoryItem with null delegated round-trips as backward-compatible`() {
        val item = HistoryItem(id = "s1", ts = 1000L, task = "hello")
        val encoded = json.encodeToString(item)
        val decoded = json.decodeFromString<HistoryItem>(encoded)
        assertNull(decoded.delegated, "delegated must be null by default (backward compat)")
    }

    @Test
    fun `F2 - HistoryItem with DelegationMetadata round-trips through JSON`() {
        val meta = DelegationMetadata(
            delegatorIde = "ide-A",
            delegatorRepo = "backend",
            delegatorSessionId = "sess-123",
            startedAt = 1_700_000_000_000L,
        )
        val item = HistoryItem(id = "s2", ts = 2000L, task = "delegated task", delegated = meta)
        val encoded = json.encodeToString(item)
        val decoded = json.decodeFromString<HistoryItem>(encoded)
        assertNotNull(decoded.delegated, "delegated must survive JSON round-trip")
        assertEquals("ide-A", decoded.delegated!!.delegatorIde)
        assertEquals("backend", decoded.delegated.delegatorRepo)
        assertEquals("sess-123", decoded.delegated.delegatorSessionId)
        assertEquals(1_700_000_000_000L, decoded.delegated.startedAt)
    }

    @Test
    fun `F2 - legacy HistoryItem without delegated field deserializes to null`() {
        // Simulate a pre-F2 sessions.json entry that has no "delegated" key
        val legacyJson = """{"id":"s3","ts":3000,"task":"old","tokensIn":0,"tokensOut":0,"totalCost":0.0,"planModeEnabled":false}"""
        val decoded = json.decodeFromString<HistoryItem>(legacyJson)
        assertNull(decoded.delegated, "missing delegated key must deserialize to null for backward compat")
    }

    @Test
    fun `F2 - List of HistoryItems with mixed delegated values round-trips`() {
        val items = listOf(
            HistoryItem(id = "s1", ts = 1000L, task = "normal"),
            HistoryItem(
                id = "s2", ts = 2000L, task = "delegated",
                delegated = DelegationMetadata("ide-B", "frontend", "sess-456", 1_700_000_000_001L)
            ),
        )
        val encoded = json.encodeToString(items)
        val decoded = json.decodeFromString<List<HistoryItem>>(encoded)
        assertNull(decoded[0].delegated)
        assertNotNull(decoded[1].delegated)
        assertEquals("ide-B", decoded[1].delegated!!.delegatorIde)
    }

    // ── F3: DelegationTool self-gate source-text pin ──────────────────────────
    // Plan 5: 5 per-action tools consolidated into DelegationTool with a single
    // settings gate at the top of execute(). Two pins on the consolidated source.

    @Test
    fun `F3 - DelegationTool checks enableOutboundCrossIdeDelegation at execute time`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/com/workflow/orchestrator/agent/tools/delegation/DelegationTool.kt")
        )
        assertTrue(
            source.contains("enableOutboundCrossIdeDelegation"),
            "DelegationTool.execute must check enableOutboundCrossIdeDelegation at runtime"
        )
        assertTrue(
            source.contains("DelegationOutboundDisabled"),
            "DelegationTool must return DelegationOutboundDisabled error when setting is off"
        )
    }

    @Test
    fun `F3 - DelegationTool gate covers every action (single check at top of execute)`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/com/workflow/orchestrator/agent/tools/delegation/DelegationTool.kt")
        )
        // The gate must come BEFORE the action dispatch so close/answer/fetch_transcript/
        // list_targets all inherit it without each handler repeating the check.
        val gateIdx = source.indexOf("DelegationOutboundDisabled")
        val dispatchIdx = source.indexOf("when (action)")
        assertTrue(gateIdx in 0 until dispatchIdx,
            "settings gate must appear before the action when-block so every action is gated")
    }

    // ── F5: DelegationOutboundService.send mutex source-text pin ─────────────

    @Test
    fun `F5 - DelegationOutboundService has a sendMutex for atomic 5-channel cap enforcement`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/com/workflow/orchestrator/agent/delegation/DelegationOutboundService.kt")
        )
        assertTrue(
            source.contains("sendMutex"),
            "DelegationOutboundService must declare sendMutex for atomic channel-cap enforcement"
        )
        assertTrue(
            source.contains("sendMutex.withLock"),
            "DelegationOutboundService.send must hold sendMutex.withLock for the check-then-insert"
        )
    }

    // ── F1: DelegationServer onConnect closeChannel source-text pin ───────────

    @Test
    fun `F1 - DelegationServer onConnect lambda signature has three parameters`() {
        val source = Files.readString(
            Path.of("../core/src/main/kotlin/com/workflow/orchestrator/core/delegation/DelegationServer.kt")
        )
        assertTrue(
            source.contains("closeChannel: suspend () -> Unit"),
            "DelegationServer.onConnect must include a closeChannel parameter (F1 socket-leak fix)"
        )
    }

    @Test
    fun `F1 - DelegationInboundService calls closeChannel after terminal result`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/com/workflow/orchestrator/agent/delegation/DelegationInboundService.kt")
        )
        assertTrue(
            source.contains("closeChannel()"),
            "DelegationInboundService.handleConnect must call closeChannel() after writing the terminal result"
        )
    }

    // ── F4: DelegationClient.ping withContext pin ─────────────────────────────

    @Test
    fun `F4 - DelegationClient ping wraps framing IO in withContext Dispatchers IO`() {
        val source = Files.readString(
            Path.of("../core/src/main/kotlin/com/workflow/orchestrator/core/delegation/DelegationClient.kt")
        )
        // ping() must have a withContext(Dispatchers.IO) block wrapping the socket I/O
        assertTrue(
            source.contains("withContext(Dispatchers.IO)"),
            "DelegationClient.ping must wrap framing I/O in withContext(Dispatchers.IO) to avoid EDT freeze"
        )
    }

    // ── F6: Session.delegated population source-text pin ─────────────────────

    @Test
    fun `F6 - executeTask populates Session delegated from delegationMetadata param`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt")
        )
        assertTrue(
            source.contains("delegated = delegationMetadata"),
            "executeTask must set Session.delegated = delegationMetadata so the live session has the field populated (F6)"
        )
    }
}
