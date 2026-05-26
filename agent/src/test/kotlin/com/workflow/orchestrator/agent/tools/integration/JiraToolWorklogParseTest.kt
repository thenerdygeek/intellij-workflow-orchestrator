package com.workflow.orchestrator.agent.tools.integration

import com.workflow.orchestrator.core.model.jira.WorklogData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Regression for my_worklogs / get_worklogs returning "0 worklogs across 0 tickets".
 *
 * Jira returns worklog `started` in a BASIC-offset shape (`+0000`, no colon), which
 * `OffsetDateTime.parse` rejects. The date-window post-filter therefore saw a null
 * `started` for every worklog and dropped them all. [JiraTool.parseStartedDateTime]
 * must accept Jira's wire format alongside strict ISO and bare dates.
 *
 * Run: ./gradlew :agent:test --tests "*JiraToolWorklogParseTest*"
 */
class JiraToolWorklogParseTest {

    private val tool = JiraTool()

    @Test
    fun `parses Jira basic-offset worklog started (the bug)`() {
        val parsed = tool.parseStartedDateTime("2026-05-10T09:00:00.000+0000")
        assertNotNull(parsed, "Jira's +0000 worklog timestamp must parse")
        assertEquals(2026, parsed!!.year)
        assertEquals(5, parsed.monthValue)
        assertEquals(10, parsed.dayOfMonth)
        assertEquals(java.time.ZoneOffset.UTC, parsed.offset)
    }

    @Test
    fun `worklogStarted resolves a real worklog so the date window can match`() {
        val wl = WorklogData(
            author = "Jane Doe",
            timeSpent = "1h",
            timeSpentSeconds = 3600,
            comment = null,
            started = "2026-05-10T09:00:00.000+0000",
        )
        assertNotNull(tool.worklogStarted(wl), "A real Jira worklog must yield a non-null started")
    }

    @Test
    fun `still parses strict ISO with colon offset and Z`() {
        assertNotNull(tool.parseStartedDateTime("2026-05-10T09:00:00+01:00"))
        assertNotNull(tool.parseStartedDateTime("2026-05-10T09:00:00Z"))
    }

    @Test
    fun `still parses bare date and rejects garbage`() {
        assertNotNull(tool.parseStartedDateTime("2026-05-10"))
        assertNull(tool.parseStartedDateTime("not-a-date"))
    }
}
