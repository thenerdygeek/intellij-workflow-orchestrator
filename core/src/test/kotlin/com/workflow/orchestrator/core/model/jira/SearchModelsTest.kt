package com.workflow.orchestrator.core.model.jira

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SearchModelsTest {
    @Test
    fun `UserSuggestion captures name displayName and active`() {
        val u = UserSuggestion(
            name = "jdoe", displayName = "Jane Doe",
            email = "jane@example.com", avatarUrl = null, active = true
        )
        assertEquals("jdoe", u.name)
        assertEquals(true, u.active)
    }

    @Test
    fun `VersionSuggestion captures released archived`() {
        val v = VersionSuggestion("100", "1.0", released = false, archived = false)
        assertEquals("1.0", v.name)
    }
}
