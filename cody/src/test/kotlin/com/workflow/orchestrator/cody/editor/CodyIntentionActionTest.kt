package com.workflow.orchestrator.cody.editor

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CodyIntentionActionTest {

    @Test
    fun `getText returns expected label`() {
        val action = CodyIntentionAction()
        assertEquals("Ask Cody to fix", action.text)
    }

    @Test
    fun `getFamilyName returns Cody AI`() {
        val action = CodyIntentionAction()
        assertEquals("Cody AI", action.familyName)
    }

    @Test
    fun `startInWriteAction returns false`() {
        val action = CodyIntentionAction()
        assertFalse(action.startInWriteAction())
    }
}
