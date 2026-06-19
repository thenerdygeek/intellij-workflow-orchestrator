package com.workflow.orchestrator.agent.ui

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Source-contract pins for audit P1-12: main prose streaming is 16ms-batched
 * ([StreamBatcher] → `appendStreamToken`), but two paths bypassed batching with one
 * JCEF executeJavaScript per SSE chunk:
 *
 *  1. Main-agent THINKING deltas — `dashboard.appendToThinking(part.text)` per chunk
 *     (thinking models emit thousands of chunks per response).
 *  2. Sub-agent THINKING deltas — `dashboard.appendSubAgentThinking(agentId, delta)` per
 *     chunk, each wrapped in its own `invokeLater`, × up to 5 parallel sub-agents.
 *     (Sub-agent PROSE was already batched at the source — `SubagentRunner.textBatcher`.)
 *
 * The fix routes both through 16ms coalescers ([StreamBatcher] / [PerToolStreamBatcher]
 * keyed by agentId) with controller-side ordering: a block-close (`endThinking` /
 * `endSubAgentThinking`) and a sub-agent completion card flush the batcher first, so
 * the CONTROLLER never reorders a tail delta behind its terminal event. (A pre-existing
 * producer-side race remains out of scope: SubagentRunner emits thinking deltas via
 * `scope.launch` — SubagentRunner.kt ~:369 — so a delta still in flight there can
 * arrive after the terminal update; the flush-first contract bounds the loss to that
 * producer window, it cannot eliminate it.)
 *
 * AgentController is not unit-instantiable, so per repo precedent these pin source text.
 */
class AgentControllerThinkingBatchingTest {

    private val src = java.io.File(
        "src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt"
    ).readText()

    // ── Main-agent thinking path ──

    private val dispatchSlice = src
        .substringAfter("private fun dispatchSplitParts(")
        .substringBefore("private fun flushStream()")

    @Test
    fun `thinking deltas route through the dedicated batcher, not straight to the bridge`() {
        assertTrue(
            dispatchSlice.contains("thinkingStreamBatcher.append(part.text)"),
            "ThinkingDelta must append to thinkingStreamBatcher (16ms coalescing)"
        )
        assertTrue(
            !dispatchSlice.contains("dashboard.appendToThinking("),
            "dispatchSplitParts must NOT call dashboard.appendToThinking per chunk — that is the P1-12 bypass"
        )
    }

    @Test
    fun `ThinkingEnd drains and closes in ONE EDT runnable (flush then endThinking, same invokeLater)`() {
        val invokeIdx = dispatchSlice.indexOf("invokeLater {")
        val flushIdx = dispatchSlice.indexOf("thinkingStreamBatcher.flush()")
        val endIdx = dispatchSlice.indexOf("dashboard.endThinking(")
        assertTrue(flushIdx >= 0, "ThinkingEnd must flush thinkingStreamBatcher")
        assertTrue(
            invokeIdx in 0..<flushIdx && flushIdx < endIdx,
            "the final drain and the block close must execute inside the SAME invokeLater " +
                "runnable (flush before endThinking). A flush-then-post pair is the W4-B3 " +
                "review hole: an EDT timer tick can drain the buffer without having " +
                "delivered yet, letting endThinking overtake up to 16ms of tail thinking."
        )
        // No second EDT post between drain and close — same runnable, EDT-serial.
        val between = dispatchSlice.substring(flushIdx, endIdx)
        assertTrue(
            !between.contains("invokeLater"),
            "endThinking must run in the same runnable as the flush, not a separate post"
        )
        // The inline delivery that makes the in-runnable flush synchronous lives on the
        // batcher field (EDT-inline invoker).
        val fieldSlice = src.substringAfter("private val thinkingStreamBatcher")
            .substringBefore("\n    )")
        assertTrue(
            fieldSlice.contains("isEventDispatchThread()"),
            "thinkingStreamBatcher must use an EDT-inline invoker so flush() from the " +
                "close runnable delivers synchronously before endThinking"
        )
    }

    @Test
    fun `thinking batcher follows the flushStream-clearStream lockstep lifecycle`() {
        val flushSlice = src.substringAfter("private fun flushStream()").substringBefore("private fun clearStream()")
        assertTrue(
            flushSlice.contains("thinkingStreamBatcher.flush()"),
            "flushStream must drain the thinking batcher alongside streamBatcher"
        )
        val clearSlice = src.substringAfter("private fun clearStream()").substringBefore("\n    /**")
        assertTrue(
            clearSlice.contains("thinkingStreamBatcher.clear()"),
            "clearStream must reset the thinking batcher alongside streamBatcher"
        )
        assertTrue(
            clearSlice.contains("subAgentThinkingBatcher.clear()"),
            "clearStream must drop sub-agent thinking buffers too — a cancelled run's " +
                "tail deltas must not deliver into the next chat (W4-B3 review minor #2)"
        )
    }

    @Test
    fun `both new batchers are disposer-registered with the controller`() {
        assertTrue(src.contains("Disposer.register(this, thinkingStreamBatcher)"))
        assertTrue(src.contains("Disposer.register(this, subAgentThinkingBatcher)"))
    }

    // ── Sub-agent thinking path ──

    private val subagentSlice = src
        .substringAfter("private fun onSubagentProgress(")
        .substringBefore("\n    private fun ")

    @Test
    fun `thinking-only sub-agent updates take the batched fast path without a per-chunk invokeLater`() {
        val fastPathIdx = subagentSlice.indexOf("subAgentThinkingBatcher.append(")
        val invokeIdx = subagentSlice.indexOf("invokeLater {")
        assertTrue(fastPathIdx >= 0, "thinking deltas must append to the agentId-keyed batcher")
        assertTrue(
            fastPathIdx < invokeIdx,
            "the thinking-only fast path must run BEFORE the invokeLater block — " +
                "one EDT post per SSE chunk is the P1-12 cost"
        )
    }

    @Test
    fun `sub-agent completion flushes the thinking batcher before the completion card`() {
        val completedIdx = subagentSlice.indexOf("SubagentExecutionStatus.COMPLETED ->")
        val failedIdx = subagentSlice.indexOf("SubagentExecutionStatus.FAILED ->")
        assertTrue(completedIdx >= 0 && failedIdx >= 0)
        val completedBranch = subagentSlice.substring(completedIdx, failedIdx)
        val failedBranch = subagentSlice.substring(failedIdx)
        for ((name, branch) in listOf("COMPLETED" to completedBranch, "FAILED" to failedBranch)) {
            val flushIdx = branch.indexOf("subAgentThinkingBatcher.flush(agentId)")
            val cardIdx = branch.indexOf("dashboard.completeSubAgent(")
            assertTrue(flushIdx >= 0, "$name branch must flush the agent's thinking batcher")
            assertTrue(
                cardIdx > flushIdx,
                "$name: the completion card must not arrive before the last batched delta"
            )
        }
    }

    @Test
    fun `sub-agent thinkingEnd flushes the keyed batcher before closing the block`() {
        val flushIdx = subagentSlice.lastIndexOf("subAgentThinkingBatcher.flush(agentId)")
        val endIdx = subagentSlice.indexOf("dashboard.endSubAgentThinking(agentId)")
        assertTrue(endIdx >= 0, "thinkingEnd must still close the sub-agent block")
        assertTrue(
            subagentSlice.substringBefore("dashboard.endSubAgentThinking(agentId)")
                .contains("subAgentThinkingBatcher.flush(agentId)"),
            "the keyed batcher must flush before endSubAgentThinking so tail bytes land inside the block"
        )
        assertTrue(flushIdx >= 0 && endIdx >= 0)
    }

    @Test
    fun `keyed sub-agent batcher uses an EDT-inline invoker so EDT flushes deliver in order`() {
        val fieldSlice = src.substringAfter("private val subAgentThinkingBatcher")
            .substringBefore("\n    )")
        assertTrue(
            fieldSlice.contains("isEventDispatchThread()"),
            "invoker must run inline when already on the EDT — otherwise flush(agentId) from the " +
                "EDT completion handler would re-post and land AFTER the completion card"
        )
    }
}
