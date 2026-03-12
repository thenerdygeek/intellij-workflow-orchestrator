package com.workflow.orchestrator.handover.service

import com.workflow.orchestrator.handover.model.SuiteResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class JiraClosureServiceTest {

    private val service = JiraClosureService()

    @Test
    fun `buildClosureComment with passing suites`() {
        val suites = listOf(
            SuiteResult("PROJ-REGR", "PROJ-REGR-42", """{"my-service":"1.2.3-build.42"}""",
                true, 120_000, Instant.now(), "https://bamboo.example.com/browse/PROJ-REGR-42"),
            SuiteResult("PROJ-SMOKE", "PROJ-SMOKE-18", """{"my-service":"1.2.3-build.42"}""",
                true, 60_000, Instant.now(), "https://bamboo.example.com/browse/PROJ-SMOKE-18")
        )

        val comment = service.buildClosureComment(suites)

        assertTrue(comment.contains("h4. Automation Results"))
        assertTrue(comment.contains("|| Suite || Status || Link ||"))
        assertTrue(comment.contains("PROJ-REGR"))
        assertTrue(comment.contains("(/) PASS"))
        assertTrue(comment.contains("[View Results|https://bamboo.example.com/browse/PROJ-REGR-42]"))
        assertTrue(comment.contains("h4. Docker Tags"))
        assertTrue(comment.contains("my-service"))
        assertTrue(comment.contains("1.2.3-build.42"))
    }

    @Test
    fun `buildClosureComment with mixed pass and fail`() {
        val suites = listOf(
            SuiteResult("PROJ-REGR", "PROJ-REGR-42", """{"my-service":"1.2.3"}""",
                true, 120_000, Instant.now(), "https://bamboo.example.com/browse/PROJ-REGR-42"),
            SuiteResult("PROJ-SMOKE", "PROJ-SMOKE-18", """{"my-service":"1.2.3"}""",
                false, 30_000, Instant.now(), "https://bamboo.example.com/browse/PROJ-SMOKE-18")
        )

        val comment = service.buildClosureComment(suites)

        assertTrue(comment.contains("(/) PASS"))
        assertTrue(comment.contains("(x) FAIL"))
    }

    @Test
    fun `buildClosureComment with running suite`() {
        val suites = listOf(
            SuiteResult("PROJ-REGR", "PROJ-REGR-42", """{}""",
                null, null, Instant.now(), "https://bamboo.example.com/browse/PROJ-REGR-42")
        )

        val comment = service.buildClosureComment(suites)

        assertTrue(comment.contains("(?) RUNNING"))
    }

    @Test
    fun `buildClosureComment with empty suites returns empty message`() {
        val comment = service.buildClosureComment(emptyList())
        assertEquals("", comment)
    }

    @Test
    fun `buildClosureComment merges docker tags from multiple suites`() {
        val suites = listOf(
            SuiteResult("PROJ-REGR", "PROJ-REGR-42",
                """{"my-service":"1.2.3","auth-service":"2.0.1"}""",
                true, 120_000, Instant.now(), "https://bamboo.example.com/browse/PROJ-REGR-42"),
            SuiteResult("PROJ-SMOKE", "PROJ-SMOKE-18",
                """{"my-service":"1.2.3"}""",
                true, 60_000, Instant.now(), "https://bamboo.example.com/browse/PROJ-SMOKE-18")
        )

        val comment = service.buildClosureComment(suites)

        assertTrue(comment.contains("my-service"))
        assertTrue(comment.contains("auth-service"))
    }

    @Test
    fun `buildClosureComment handles malformed docker tags gracefully`() {
        val suites = listOf(
            SuiteResult("PROJ-REGR", "PROJ-REGR-42", "not-valid-json",
                true, 120_000, Instant.now(), "https://bamboo.example.com/browse/PROJ-REGR-42")
        )

        val comment = service.buildClosureComment(suites)

        assertTrue(comment.contains("h4. Automation Results"))
        // Should still render suite results even if tags are malformed
        assertTrue(comment.contains("PROJ-REGR"))
    }
}
