package com.workflow.orchestrator.agent.loop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LoopDetectorExemptionTest {

    @Test
    fun `exempt tool never trips soft or hard limits regardless of repeats`() {
        val detector = LoopDetector(exemptTools = setOf("read_document"))
        repeat(10) {
            assertEquals(LoopStatus.OK, detector.recordToolCall("read_document", "{\"path\":\"/x.pdf\",\"offset\":0}"))
        }
    }

    @Test
    fun `non-exempt tool still trips hard limit on identical repeats`() {
        val detector = LoopDetector(exemptTools = setOf("read_document"))
        var last = LoopStatus.OK
        repeat(5) { last = detector.recordToolCall("search_code", "{\"q\":\"foo\"}") }
        assertEquals(LoopStatus.HARD_LIMIT, last)
    }
}
