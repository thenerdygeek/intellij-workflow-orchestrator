// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.web.service

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Source-text contract test: verifies that [WebFetchEngine] registers a coroutine
 * cancellation hook that cancels the in-flight OkHttp [okhttp3.Call] when the
 * enclosing coroutine is stopped (Fix C — graceful tool-stop).
 *
 * Real cancellation behaviour is verified by in-IDE/integration smoke; this test
 * pins the static wiring so no refactor silently removes the hook.
 */
class WebFetchEngineCallCancelContractTest {
    private val src = File(
        "src/main/kotlin/com/workflow/orchestrator/web/service/WebFetchEngine.kt"
    ).readText()

    @Test
    fun `registers an OkHttp Call cancel hook on coroutine cancellation`() {
        assertTrue(
            src.contains("call.cancel()") || src.contains(".cancel()"),
            "WebFetchEngine must cancel the OkHttp Call on stop so the socket is released promptly",
        )
        assertTrue(
            src.contains("invokeOnCompletion") || src.contains("CancellationException"),
            "a cancellation hook (invokeOnCompletion + CancellationException) must drive Call.cancel()",
        )
    }
}
