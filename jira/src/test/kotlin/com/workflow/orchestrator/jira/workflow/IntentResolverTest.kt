package com.workflow.orchestrator.jira.workflow

import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.workflow.WorkflowIntent
import com.workflow.orchestrator.jira.api.dto.JiraStatus
import com.workflow.orchestrator.jira.api.dto.JiraStatusCategory
import com.workflow.orchestrator.jira.api.dto.JiraTransition
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class IntentResolverTest {

    private lateinit var store: TransitionMappingStore

    private fun makeTransition(
        id: String,
        name: String,
        categoryKey: String = "indeterminate"
    ): JiraTransition = JiraTransition(
        id = id,
        name = name,
        to = JiraStatus(
            id = id,
            name = name,
            statusCategory = JiraStatusCategory(key = categoryKey, name = categoryKey)
        )
    )

    @BeforeEach
    fun setUp() {
        store = TransitionMappingStore()
    }

    // Test 1: resolves via explicit mapping when configured
    @Test
    fun `resolves via explicit mapping when configured`() {
        val transitions = listOf(
            makeTransition("11", "Custom In Progress", "indeterminate"),
            makeTransition("21", "Done", "done")
        )
        store.saveMapping(
            TransitionMapping(
                intent = WorkflowIntent.START_WORK.name,
                transitionName = "Custom In Progress",
                projectKey = "PROJ",
                issueTypeId = null,
                source = "explicit"
            )
        )

        val result = IntentResolver.resolveFromTransitions(
            intent = WorkflowIntent.START_WORK,
            transitions = transitions,
            mappingStore = store,
            projectKey = "PROJ"
        )

        assertTrue(result.isSuccess)
        val resolved = (result as ApiResult.Success).data
        assertEquals("11", resolved.transitionId)
        assertEquals("Custom In Progress", resolved.transitionName)
        assertEquals(ResolutionMethod.EXPLICIT_MAPPING, resolved.resolution)
    }

    // Test 2: resolves via name matching for standard workflow
    @Test
    fun `resolves via name matching for standard workflow`() {
        val transitions = listOf(
            makeTransition("11", "In Progress", "indeterminate"),
            makeTransition("21", "Done", "done")
        )

        val result = IntentResolver.resolveFromTransitions(
            intent = WorkflowIntent.START_WORK,
            transitions = transitions,
            mappingStore = store,
            projectKey = "PROJ"
        )

        assertTrue(result.isSuccess)
        val resolved = (result as ApiResult.Success).data
        assertEquals("11", resolved.transitionId)
        assertEquals("In Progress", resolved.transitionName)
        assertEquals(ResolutionMethod.NAME_MATCH, resolved.resolution)
    }

    // Test 3: resolves via category matching when names differ
    @Test
    fun `resolves via category matching when names differ`() {
        val transitions = listOf(
            makeTransition("11", "Begin Sprint Task", "indeterminate"),
            makeTransition("21", "Finish", "done")
        )

        val result = IntentResolver.resolveFromTransitions(
            intent = WorkflowIntent.CLOSE,
            transitions = transitions,
            mappingStore = store,
            projectKey = "PROJ"
        )

        assertTrue(result.isSuccess)
        val resolved = (result as ApiResult.Success).data
        assertEquals("21", resolved.transitionId)
        assertEquals(ResolutionMethod.CATEGORY_MATCH, resolved.resolution)
    }

    // Test 4: returns error when no transitions match
    @Test
    fun `returns error when no transitions match`() {
        val transitions = listOf(
            makeTransition("11", "Some Custom Step", "indeterminate")
        )

        val result = IntentResolver.resolveFromTransitions(
            intent = WorkflowIntent.CLOSE,
            transitions = transitions,
            mappingStore = store,
            projectKey = "PROJ"
        )

        assertTrue(result.isError)
    }

    // Test 5: returns error for empty transitions
    @Test
    fun `returns error for empty transitions`() {
        val result = IntentResolver.resolveFromTransitions(
            intent = WorkflowIntent.START_WORK,
            transitions = emptyList(),
            mappingStore = store,
            projectKey = "PROJ"
        )

        assertTrue(result.isError)
    }

    // Test 6: explicit mapping verified against available transitions — falls back if mapping target doesn't exist
    @Test
    fun `explicit mapping falls back if mapping target transition does not exist`() {
        val transitions = listOf(
            makeTransition("11", "In Progress", "indeterminate")
        )
        // Explicit mapping points to a transition name not in the available list
        store.saveMapping(
            TransitionMapping(
                intent = WorkflowIntent.START_WORK.name,
                transitionName = "Non-Existent Transition",
                projectKey = "PROJ",
                issueTypeId = null,
                source = "explicit"
            )
        )

        val result = IntentResolver.resolveFromTransitions(
            intent = WorkflowIntent.START_WORK,
            transitions = transitions,
            mappingStore = store,
            projectKey = "PROJ"
        )

        // Should fall back to name matching and still succeed
        assertTrue(result.isSuccess)
        val resolved = (result as ApiResult.Success).data
        assertEquals("11", resolved.transitionId)
        // Resolution method should NOT be EXPLICIT_MAPPING since it fell through
        assertNotEquals(ResolutionMethod.EXPLICIT_MAPPING, resolved.resolution)
    }

    // Test 7: CLOSE intent resolves Done transition
    @Test
    fun `CLOSE intent resolves Done transition`() {
        val transitions = listOf(
            makeTransition("11", "In Progress", "indeterminate"),
            makeTransition("31", "Done", "done"),
            makeTransition("41", "Closed", "done")
        )

        val result = IntentResolver.resolveFromTransitions(
            intent = WorkflowIntent.CLOSE,
            transitions = transitions,
            mappingStore = store,
            projectKey = "PROJ"
        )

        assertTrue(result.isSuccess)
        val resolved = (result as ApiResult.Success).data
        // "Done" is first in CLOSE.defaultNames
        assertEquals("Done", resolved.transitionName)
        assertEquals(ResolutionMethod.NAME_MATCH, resolved.resolution)
    }

    // Test 8: saves learned mapping after name match
    @Test
    fun `saves learned mapping after name match`() {
        val transitions = listOf(
            makeTransition("11", "In Progress", "indeterminate")
        )

        IntentResolver.resolveFromTransitions(
            intent = WorkflowIntent.START_WORK,
            transitions = transitions,
            mappingStore = store,
            projectKey = "PROJ"
        )

        val savedMapping = store.getMapping(WorkflowIntent.START_WORK.name, "PROJ")
        assertNotNull(savedMapping)
        assertEquals("In Progress", savedMapping!!.transitionName)
        assertEquals("learned", savedMapping.source)
    }

    // Test 9: returns disambiguation error when multiple category matches
    @Test
    fun `returns disambiguation error when multiple category matches`() {
        val transitions = listOf(
            makeTransition("11", "Custom Done A", "done"),
            makeTransition("12", "Custom Done B", "done")
        )

        val result = IntentResolver.resolveFromTransitions(
            intent = WorkflowIntent.CLOSE,
            transitions = transitions,
            mappingStore = store,
            projectKey = "PROJ"
        )

        assertTrue(result.isError)
        val error = result as ApiResult.Error
        assertTrue(
            error.message.startsWith("DISAMBIGUATE:"),
            "Expected message starting with 'DISAMBIGUATE:' but was: ${error.message}"
        )
        assertTrue(error.message.contains("11::Custom Done A"))
        assertTrue(error.message.contains("12::Custom Done B"))
    }
}
