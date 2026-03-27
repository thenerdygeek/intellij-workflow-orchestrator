package com.workflow.orchestrator.cody.editor

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CodyIntentionActionTest {

    @Test
    fun `getText returns expected label`() {
        val action = CodyIntentionAction()
        assertEquals("Fix with AI Agent (Workflow)", action.text)
    }

    @Test
    fun `getFamilyName returns Workflow Orchestrator`() {
        val action = CodyIntentionAction()
        assertEquals("Workflow Orchestrator", action.familyName)
    }

    @Test
    fun `startInWriteAction returns false`() {
        val action = CodyIntentionAction()
        assertFalse(action.startInWriteAction())
    }
}
