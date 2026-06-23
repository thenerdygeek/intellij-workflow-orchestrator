package com.workflow.orchestrator.core.ai.protocol

import com.workflow.orchestrator.core.ai.GatewayErrorDetector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ClassifyStreamLineParityTest {
    private val protocol = XmlToolProtocol()
    private val frames = listOf(
        """data: {"type":"completion.process_completion","error":"context deadline exceeded"}""",
        """data: {"choices":[{"delta":{"content":"hi"}}]}""",
        "data: [DONE]",
        "",
    )
    @Test fun `classifyStreamLine returns upstream_timeout exactly when GatewayErrorDetector fires`() {
        for (line in frames) {
            val expected = if (GatewayErrorDetector.isUpstreamTimeoutFrame(line)) "upstream_timeout" else null
            assertEquals(expected, protocol.classifyStreamLine(line), "mismatch on: $line")
        }
    }
}
