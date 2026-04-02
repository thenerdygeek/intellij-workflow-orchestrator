package com.workflow.orchestrator.sonar.editor

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SonarFixIntentionActionTest {

    @Test
    fun `getText returns expected label`() {
        val action = SonarFixIntentionAction()
        assertEquals("Fix with AI Agent (Workflow)", action.text)
    }

    @Test
    fun `getFamilyName returns Workflow Orchestrator`() {
        val action = SonarFixIntentionAction()
        assertEquals("Workflow Orchestrator", action.familyName)
    }

    @Test
    fun `startInWriteAction returns false`() {
        val action = SonarFixIntentionAction()
        assertFalse(action.startInWriteAction())
    }
}
