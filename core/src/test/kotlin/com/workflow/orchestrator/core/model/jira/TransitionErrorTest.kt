package com.workflow.orchestrator.core.model.jira

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TransitionErrorTest {
    @Test
    fun `MissingFieldsError carries structured schema`() {
        val err = MissingFieldsError(
            transitionId = "31",
            transitionName = "In Review",
            fields = listOf(
                TransitionField(
                    "reviewer", "Reviewer", true,
                    FieldSchema.User(multi = false), emptyList(),
                    "/rest/api/2/user/assignable/search?issueKey=ABC-1", null
                )
            ),
            guidance = "call ask_followup_question"
        )
        val wrapped: TransitionError = TransitionError.MissingFields(err)
        assertEquals("missing_required_fields", err.kind)
        assertEquals("Reviewer", (wrapped as TransitionError.MissingFields).payload.fields[0].name)
    }
}
