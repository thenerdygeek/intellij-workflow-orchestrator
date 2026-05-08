package com.workflow.orchestrator.handover.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HandoverPlaceholderValueTest {

    @Test
    fun `available value renders as raw text in both formats`() {
        val v = HandoverPlaceholderValue.available("AFTER8TE-912")
        assertTrue(v.isAvailable)
        assertEquals("AFTER8TE-912", v.value)
        assertEquals("AFTER8TE-912", v.renderForJira())
        assertEquals("AFTER8TE-912", v.renderForEmail())
        // No reason set when available
        assertEquals(null, v.unavailableReason)
    }

    @Test
    fun `unavailable value renders as em-dash in jira and as muted italic em-dash in email`() {
        val v = HandoverPlaceholderValue.unavailable("missing")
        assertFalse(v.isAvailable)
        assertEquals("missing", v.unavailableReason)
        assertEquals("—", v.renderForJira())
        assertEquals("<i style=\"color:#888\">—</i>", v.renderForEmail())
    }
}
