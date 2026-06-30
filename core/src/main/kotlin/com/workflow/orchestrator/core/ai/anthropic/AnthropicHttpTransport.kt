package com.workflow.orchestrator.core.ai.anthropic

import com.workflow.orchestrator.core.model.ApiResult

/**
 * Transport abstraction for the Anthropic Messages API streaming endpoint.
 *
 * Separating the interface from [AnthropicHttpClient] allows Task 9's brain
 * (`AnthropicDirectBrain`) to receive a mock transport in unit tests without
 * spinning up a real HTTP client.
 *
 * Phase 4a Task 8.
 */
interface AnthropicHttpTransport {

    /**
     * POSTs [request] to `/v1/messages` and streams the raw SSE response lines
     * by invoking [onLine] for each line received (including blank separators and
     * `event:`/`data:` prefixes — callers feed them unchanged into [AnthropicSseParser]).
     *
     * Returns [ApiResult.Success] (Unit) when the stream is fully consumed, or
     * [ApiResult.Error] on any HTTP or network failure.
     */
    suspend fun postStream(
        request: AnthropicRequest,
        onLine: (String) -> Unit,
    ): ApiResult<Unit>
}
