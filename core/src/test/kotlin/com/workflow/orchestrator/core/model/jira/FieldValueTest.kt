package com.workflow.orchestrator.core.model.jira

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FieldValueTest {
    @Test
    fun `TransitionInput keys field values by TransitionField id`() {
        val input = TransitionInput(
            transitionId = "31",
            fieldValues = mapOf(
                "assignee" to FieldValue.UserRef("jdoe"),
                "labels" to FieldValue.LabelList(listOf("bug"))
            ),
            comment = "moving forward"
        )
        assertEquals("jdoe", (input.fieldValues["assignee"] as FieldValue.UserRef).name)
        assertEquals(listOf("bug"), (input.fieldValues["labels"] as FieldValue.LabelList).labels)
    }

    @Test
    fun `TransitionOutcome carries from and to status`() {
        val outcome = TransitionOutcome(
            key = "ABC-1",
            fromStatus = StatusRef("1", "To Do", StatusCategory.TO_DO),
            toStatus = StatusRef("3", "In Progress", StatusCategory.IN_PROGRESS),
            transitionId = "21",
            appliedFields = emptyMap()
        )
        assertEquals("To Do", outcome.fromStatus.name)
    }
}
