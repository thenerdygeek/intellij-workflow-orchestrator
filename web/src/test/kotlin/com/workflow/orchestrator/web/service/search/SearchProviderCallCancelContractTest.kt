// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.web.service.search

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Source-text contract tests: verify that [SearXNGProvider] and [CustomHttpProvider]
 * register a coroutine cancellation hook that cancels the in-flight OkHttp [okhttp3.Call]
 * when the enclosing coroutine is stopped (Fix C — graceful web_search stop).
 *
 * Mirrors [com.workflow.orchestrator.web.service.WebFetchEngineCallCancelContractTest].
 * Real cancellation behaviour is verified by in-IDE/integration smoke; these tests pin
 * the static wiring so no refactor silently removes the hook.
 */
class SearchProviderCallCancelContractTest {

    private val searxngSrc = File(
        "src/main/kotlin/com/workflow/orchestrator/web/service/search/SearXNGProvider.kt"
    ).readText()

    private val customHttpSrc = File(
        "src/main/kotlin/com/workflow/orchestrator/web/service/search/CustomHttpProvider.kt"
    ).readText()

    @Test
    fun `SearXNGProvider registers an OkHttp Call cancel hook on coroutine cancellation`() {
        assertTrue(
            searxngSrc.contains("call.cancel()") || searxngSrc.contains(".cancel()"),
            "SearXNGProvider must cancel the OkHttp Call on stop so the socket is released promptly",
        )
        assertTrue(
            searxngSrc.contains("invokeOnCompletion"),
            "SearXNGProvider must wire a cancellation hook via invokeOnCompletion",
        )
        assertTrue(
            searxngSrc.contains("CancellationException"),
            "SearXNGProvider must guard the cancel hook with a CancellationException check",
        )
    }

    @Test
    fun `CustomHttpProvider registers an OkHttp Call cancel hook on coroutine cancellation`() {
        assertTrue(
            customHttpSrc.contains("call.cancel()") || customHttpSrc.contains(".cancel()"),
            "CustomHttpProvider must cancel the OkHttp Call on stop so the socket is released promptly",
        )
        assertTrue(
            customHttpSrc.contains("invokeOnCompletion"),
            "CustomHttpProvider must wire a cancellation hook via invokeOnCompletion",
        )
        assertTrue(
            customHttpSrc.contains("CancellationException"),
            "CustomHttpProvider must guard the cancel hook with a CancellationException check",
        )
    }
}
