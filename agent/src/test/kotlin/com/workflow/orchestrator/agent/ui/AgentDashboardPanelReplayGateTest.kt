package com.workflow.orchestrator.agent.ui

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Source-contract pins for audit P2-2 + bug B7 on [AgentDashboardPanel]'s mirror
 * replay log (the eviction BEHAVIOUR itself is unit-tested in [ReplayRingTest]):
 *
 *  - the log is the bounded [ReplayRing] (ArrayDeque under a lock, O(1) add), not a
 *    CopyOnWriteArrayList (which full-array-copied on every batched stream token);
 *  - recording is UNCONDITIONAL — `replayStateTo` is the only content source for a
 *    late-opened mirror, so a mirror-presence gate (tried in W4-B3, reverted in
 *    review) left the FIRST late-opened editor mirror and any close-then-reopen
 *    completely blank. Memory is bounded by the ring's 5000-entry cap;
 *  - W4-B1's dispose() wiring stays intact (mirrors + replay log cleared).
 */
class AgentDashboardPanelReplayGateTest {

    private val src = java.io.File(
        "src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentDashboardPanel.kt"
    ).readText()

    @Test
    fun `replay log is the bounded ReplayRing, not a CopyOnWriteArrayList`() {
        assertTrue(
            src.contains("ReplayRing<(AgentDashboardPanel) -> Unit>(MAX_REPLAY_LOG_SIZE)"),
            "replayLog must be a ReplayRing (P2-2: O(1) add + B7: evict-oldest on overflow)"
        )
        assertTrue(
            !src.contains("CopyOnWriteArrayList<(AgentDashboardPanel) -> Unit>"),
            "the CopyOnWriteArrayList replay log (full array copy per add) must be gone"
        )
    }

    @Test
    fun `recording is unconditional — no mirror-presence gate`() {
        val slice = src.substringAfter("private fun recordReplay(").substringBefore("\n    }")
        assertTrue(
            !slice.contains("mirrors.isEmpty()"),
            "recordReplay must NOT gate on mirror presence: replayStateTo is the only " +
                "content source for a late-opened mirror, so the gate left the first " +
                "late-opened editor tab (and close-then-reopen) blank"
        )
        assertTrue(slice.contains("replayLog.add(action)"))
    }

    @Test
    fun `removing a mirror does not drop the log`() {
        val slice = src.substringAfter("fun removeMirror(").substringBefore("\n    /**")
        assertTrue(
            !slice.contains("replayLog.clear()"),
            "removeMirror must keep the log — a reopened editor tab must replay history"
        )
    }

    @Test
    fun `dispose still clears mirrors and replay log (W4-B1)`() {
        val slice = src.substringAfter("override fun dispose()").substringBefore("\n    }")
        assertTrue(slice.contains("mirrors.clear()"))
        assertTrue(slice.contains("replayLog.clear()"))
    }
}
