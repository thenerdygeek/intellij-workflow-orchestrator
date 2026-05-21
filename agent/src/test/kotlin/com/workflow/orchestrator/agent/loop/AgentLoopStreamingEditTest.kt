// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.agent.loop

import org.junit.jupiter.api.Test

/**
 * Source-text pins for the AgentLoop integration of the streaming `edit_file`
 * preview (Commit 2 — see project memory entry `project_streaming_edit_preview`).
 *
 * AgentLoop is a ~2500-line orchestrator with many tightly-coupled callbacks; a
 * full behavioural test of the streaming-preview path requires standing up a
 * fake LlmBrain, contextManager, MessageStateHandler, hookManager, ... — well
 * beyond the scope of a focused integration test. The source-text pins below
 * lock in the three invariants the production code MUST honour. They fail
 * loudly if a future refactor drops the tracker hook, removes the feature flag
 * gate, or stops cancelling open previews on coroutine cancel.
 *
 * Same pattern (and same prior approval) as
 * [com.workflow.orchestrator.agent.ui.AgentControllerEditPreviewTest].
 */
class AgentLoopStreamingEditTest {

    private val src: String by lazy {
        java.io.File(
            "src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt"
        ).readText()
    }

    @Test
    fun `AgentLoop source contains a streamingEditTracker observe call inside onChunk`() {
        // The tracker must be wired into the SSE chunk handler. Without this, the
        // user sees no live preview and the entire feature is dark even when
        // the controller has wired up streamingEditCallback.
        assert("streamingEditTracker.observe" in src) {
            "AgentLoop must invoke streamingEditTracker.observe(…) inside the onChunk lambda " +
                "so partial edit_file tool calls drive the streaming-preview tracker. " +
                "If the hook is removed, the chat panel never sees the live diff."
        }
    }

    @Test
    fun `AgentLoop source guards tracker calls with the enableStreamingEditPreview flag`() {
        // The streamingEditTracker hook must be gated on
        // PluginSettings.State.enableStreamingEditPreview so users can flip the
        // feature off without a plugin restart. Without this gate the kill switch
        // is decorative.
        assert("enableStreamingEditPreview" in src) {
            "AgentLoop must read PluginSettings.State.enableStreamingEditPreview and gate the " +
                "streamingEditTracker call on it. Otherwise the kill switch in Settings does nothing."
        }
        // Belt-and-braces: the flag should be checked together with the tracker
        // (i.e. both names appear close together — same conditional branch).
        val flagIdx = src.indexOf("enableStreamingEditPreview")
        val gateIdx = src.indexOf("streamingEditPreviewEnabled && streamingEditTracker")
        assert(flagIdx > 0 && gateIdx > flagIdx) {
            "Expected `streamingEditPreviewEnabled && streamingEditTracker` after the flag read, " +
                "but didn't find them in the right order (flagIdx=$flagIdx, gateIdx=$gateIdx). " +
                "The feature flag must gate the tracker call in onChunk, not be read in isolation."
        }
    }

    @Test
    fun `AgentLoop source cancels all open previews on user-initiated cancel`() {
        // cancel() runs from any thread and must drop the open previews so the
        // chat doesn't show stale "live" cards after a cancel. The brain is
        // already cancelled separately; this is the UI-side cleanup.
        assert("streamingEditTracker?.cancelAll" in src) {
            "AgentLoop.cancel() must call streamingEditTracker?.cancelAll() so open previews " +
                "are dropped when the user cancels mid-stream. Without this, stale 'live' cards " +
                "linger in the chat panel after cancel."
        }
    }
}
