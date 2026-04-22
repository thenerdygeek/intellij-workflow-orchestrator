package com.workflow.orchestrator.core.model.jira

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TransitionModelsTest {
    @Test
    fun `TransitionMeta defaults fields to empty list`() {
        val meta = TransitionMeta(
            id = "31",
            name = "In Review",
            toStatus = StatusRef("3", "In Review", StatusCategory.IN_PROGRESS),
            hasScreen = false,
            fields = emptyList()
        )
        assertEquals(emptyList<TransitionField>(), meta.fields)
        assertEquals(StatusCategory.IN_PROGRESS, meta.toStatus.category)
    }

    @Test
    fun `TransitionField marks required and captures schema`() {
        val field = TransitionField(
            id = "assignee",
            name = "Assignee",
            required = true,
            schema = FieldSchema.User(multi = false),
            allowedValues = emptyList(),
            autoCompleteUrl = "/rest/api/2/user/assignable/search?issueKey=KEY",
            defaultValue = null
        )
        assertEquals(true, field.required)
    }
}
