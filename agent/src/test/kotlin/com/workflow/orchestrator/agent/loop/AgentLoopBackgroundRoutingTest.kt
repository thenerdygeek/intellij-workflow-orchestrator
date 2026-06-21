// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.agent.loop

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Source-text pins for Task 6 — the AgentLoop integration that routes eligible tools through the
 * [com.workflow.orchestrator.agent.tools.background.BackgroundToolExecutor].
 *
 * AgentLoop is a ~2700-line orchestrator with many tightly-coupled callbacks and is NOT
 * unit-instantiable; a full behavioural test of the background-routing path would require standing
 * up a fake LlmBrain, contextManager, MessageStateHandler, hookManager, BackgroundToolExecutor, …
 * — well beyond a focused integration test. The source-text pins below lock in the invariants the
 * production code MUST honour: they fail loudly if a future refactor drops the reserved-attribute
 * strip, the executor routing, the user-detach `select`, the un-detached inline cancel, or the
 * kill-switch / cap eligibility gate. Same pattern (and same prior approval) as
 * [AgentLoopStreamingEditTest].
 */
class AgentLoopBackgroundRoutingTest {
    private val src = File("src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt").readText()

    @Test fun `reads and strips the reserved background attribute`() {
        assertTrue(src.contains("BackgroundEligibility.RUN_IN_BACKGROUND_PARAM"))
        assertTrue(src.contains("JsonObject(params - bgKey)"))
    }

    @Test fun `routes eligible tools through the executor with a select over detach`() {
        assertTrue(src.contains("backgroundExecutor.start(handle)"))
        assertTrue(src.contains("handle.detachSignal.onAwait"))
        assertTrue(src.contains("syntheticBackgroundStartResult"))
        assertTrue(src.contains("syntheticMovedToBackgroundResult"))
    }

    @Test fun `un-detached inline background tool is cancelled on genuine loop cancel`() {
        assertTrue(src.contains("backgroundExecutor?.cancelOne(toolCallId)"))
    }

    @Test fun `eligibility honors kill switch and cap`() {
        assertTrue(src.contains("backgroundEnabled()"))
        assertTrue(src.contains("backgroundInFlightCount() < backgroundCap"))
    }
}
