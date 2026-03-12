package com.workflow.orchestrator.jira.workflow

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class TransitionExecutorTest {

    @Test
    fun `builds minimal payload with transition id only`() {
        val payload = TransitionExecutor.buildPayload("21", null, null)
        assertEquals("""{"transition":{"id":"21"}}""", payload)
    }

    @Test
    fun `builds payload with name-based fields`() {
        val payload = TransitionExecutor.buildPayload("41", mapOf("assignee" to "jsmith"), null)
        assertTrue(payload.contains(""""transition":{"id":"41"}"""))
        assertTrue(payload.contains(""""fields":{"""))
        assertTrue(payload.contains(""""assignee":{"name":"jsmith"}"""))
    }

    @Test
    fun `builds payload with id-based fields`() {
        val payload = TransitionExecutor.buildPayload("41", mapOf("resolution" to mapOf("id" to "1")), null)
        assertTrue(payload.contains(""""resolution":{"id":"1"}"""))
    }

    @Test
    fun `builds payload with comment`() {
        val payload = TransitionExecutor.buildPayload("21", null, "Test comment")
        assertTrue(payload.contains(""""update":{"comment":[{"add":{"body":"Test comment"}}]}"""))
    }

    @Test
    fun `builds payload with fields and comment`() {
        val payload = TransitionExecutor.buildPayload("41", mapOf("assignee" to "jsmith"), "Closing this")
        assertTrue(payload.contains(""""transition":{"id":"41"}"""))
        assertTrue(payload.contains(""""fields":{"""))
        assertTrue(payload.contains(""""update":{"comment":[{"add":{"body":"Closing this"}}]}"""))
    }

    @Test
    fun `escapes special characters in comment`() {
        val payload = TransitionExecutor.buildPayload("21", null, "Line1\nLine2 with \"quotes\"")
        assertTrue(payload.contains("Line1\\nLine2 with \\\"quotes\\\""))
    }
}
