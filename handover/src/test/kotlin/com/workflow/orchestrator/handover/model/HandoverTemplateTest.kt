package com.workflow.orchestrator.handover.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HandoverTemplateTest {

    @Test
    fun `bundled template flags isBundled true and isOverride false`() {
        val t = HandoverTemplate(
            id = "jira/standard-closure",
            name = "Standard closure",
            action = HandoverTemplateAction.JIRA,
            source = "h2. Handover",
            origin = HandoverTemplateOrigin.BUNDLED
        )
        assertTrue(t.isBundled)
        assertFalse(t.isOverride)
        assertEquals(HandoverTemplateAction.JIRA, t.action)
    }

    @Test
    fun `project override flags isOverride true and isBundled false`() {
        val t = HandoverTemplate(
            id = "jira/standard-closure",
            name = "Standard closure",
            action = HandoverTemplateAction.JIRA,
            source = "...",
            origin = HandoverTemplateOrigin.PROJECT
        )
        assertTrue(t.isOverride)
        assertFalse(t.isBundled)
    }

    @Test
    fun `global user template is neither bundled nor override`() {
        val t = HandoverTemplate(
            id = "email/qa",
            name = "QA handover",
            action = HandoverTemplateAction.EMAIL,
            source = "<h1>x</h1>",
            origin = HandoverTemplateOrigin.GLOBAL
        )
        assertFalse(t.isBundled)
        assertFalse(t.isOverride)
        assertEquals(HandoverTemplateAction.EMAIL, t.action)
    }
}
