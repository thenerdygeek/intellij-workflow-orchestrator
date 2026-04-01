package com.workflow.orchestrator.agent.runtime

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

// TDD: Tests written FIRST for AgentPlan.markdown field.
// These tests should FAIL until the markdown field is added.
class PlanMarkdownTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `AgentPlan with markdown field serializes correctly`() {
        val plan = AgentPlan(
            goal = "Fix NPE",
            steps = listOf(PlanStep(id = "s1", title = "Read file")),
            markdown = "## Goal\nFix NPE in PaymentService"
        )
        val serialized = json.encodeToString(AgentPlan.serializer(), plan)
        assertTrue(serialized.contains("markdown"))

        val deserialized = json.decodeFromString(AgentPlan.serializer(), serialized)
        assertEquals("## Goal\nFix NPE in PaymentService", deserialized.markdown)
    }

    @Test
    fun `AgentPlan without markdown is backward compatible`() {
        // Old plans without markdown field should deserialize fine
        val oldJson = """{"goal":"Fix NPE","steps":[{"id":"s1","title":"Read"}]}"""
        val plan = json.decodeFromString(AgentPlan.serializer(), oldJson)
        assertNull(plan.markdown)
        assertEquals("Fix NPE", plan.goal)
    }

    @Test
    fun `AgentPlan markdown field defaults to null`() {
        val plan = AgentPlan(goal = "test", steps = emptyList())
        assertNull(plan.markdown)
    }

    @Test
    fun `AgentPlan with multiline markdown preserves formatting`() {
        val md = """
            ## Goal
            Fix the NPE

            ## Steps
            ### 1. Read PaymentService.kt
            ```kotlin
            val customer = order.customer
            ```

            ### 2. Add null check
            Guard clause
        """.trimIndent()

        val plan = AgentPlan(
            goal = "Fix NPE",
            steps = listOf(PlanStep(id = "s1", title = "Read")),
            markdown = md
        )

        val roundtripped = json.decodeFromString(
            AgentPlan.serializer(),
            json.encodeToString(AgentPlan.serializer(), plan)
        )
        assertEquals(md, roundtripped.markdown)
        assertTrue(roundtripped.markdown!!.contains("```kotlin"))
    }

    @Test
    fun `AgentPlan title field serializes correctly`() {
        val plan = AgentPlan(
            goal = "Fix bug",
            steps = emptyList(),
            title = "Fix NPE in PaymentService"
        )
        val serialized = json.encodeToString(AgentPlan.serializer(), plan)
        assertTrue(serialized.contains("Fix NPE in PaymentService"))

        val deserialized = json.decodeFromString(AgentPlan.serializer(), serialized)
        assertEquals("Fix NPE in PaymentService", deserialized.title)
    }

    @Test
    fun `AgentPlan title defaults to empty string`() {
        val plan = AgentPlan(goal = "test", steps = emptyList())
        assertEquals("", plan.title)
    }
}
