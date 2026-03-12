package com.workflow.orchestrator.handover.service

import com.workflow.orchestrator.handover.model.ClipboardPayload
import com.workflow.orchestrator.handover.model.SuiteLinkEntry
import com.workflow.orchestrator.handover.model.SuiteResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class QaClipboardServiceTest {

    private val service = QaClipboardService()

    @Test
    fun `formatForClipboard produces expected output`() {
        val payload = ClipboardPayload(
            dockerTags = mapOf("my-service" to "1.2.3-build.42", "auth-service" to "release-2.0.1"),
            suiteLinks = listOf(
                SuiteLinkEntry("Regression Suite A", true, "https://bamboo.example.com/browse/RS-42"),
                SuiteLinkEntry("Smoke Tests", true, "https://bamboo.example.com/browse/ST-18")
            ),
            ticketIds = listOf("PROJ-123")
        )

        val text = service.formatForClipboard(payload)

        assertTrue(text.contains("Docker Tags:"))
        assertTrue(text.contains("my-service: 1.2.3-build.42"))
        assertTrue(text.contains("auth-service: release-2.0.1"))
        assertTrue(text.contains("Automation Results:"))
        assertTrue(text.contains("Regression Suite A: PASS"))
        assertTrue(text.contains("https://bamboo.example.com/browse/RS-42"))
        assertTrue(text.contains("Tickets: PROJ-123"))
    }

    @Test
    fun `formatForClipboard shows FAIL for failed suites`() {
        val payload = ClipboardPayload(
            dockerTags = mapOf("svc" to "1.0"),
            suiteLinks = listOf(SuiteLinkEntry("Suite A", false, "https://example.com")),
            ticketIds = listOf("PROJ-123")
        )

        val text = service.formatForClipboard(payload)
        assertTrue(text.contains("Suite A: FAIL"))
    }

    @Test
    fun `formatForClipboard with multiple tickets`() {
        val payload = ClipboardPayload(
            dockerTags = mapOf("svc" to "1.0"),
            suiteLinks = emptyList(),
            ticketIds = listOf("PROJ-123", "PROJ-456")
        )

        val text = service.formatForClipboard(payload)
        assertTrue(text.contains("Tickets: PROJ-123, PROJ-456"))
    }

    @Test
    fun `buildPayloadFromSuiteResults extracts docker tags and links`() {
        val suites = listOf(
            SuiteResult("PROJ-REGR", "PROJ-REGR-42",
                """{"my-service":"1.2.3"}""",
                true, 120_000, Instant.now(), "https://bamboo.example.com/browse/PROJ-REGR-42")
        )

        val payload = service.buildPayloadFromSuiteResults(suites, listOf("PROJ-123"))

        assertEquals(1, payload.dockerTags.size)
        assertEquals("1.2.3", payload.dockerTags["my-service"])
        assertEquals(1, payload.suiteLinks.size)
        assertTrue(payload.suiteLinks[0].passed)
    }

    @Test
    fun `buildPayloadFromSuiteResults handles malformed JSON`() {
        val suites = listOf(
            SuiteResult("PROJ-REGR", "PROJ-REGR-42", "bad-json",
                true, 120_000, Instant.now(), "https://bamboo.example.com/browse/PROJ-REGR-42")
        )

        val payload = service.buildPayloadFromSuiteResults(suites, listOf("PROJ-123"))
        assertTrue(payload.dockerTags.isEmpty())
        assertEquals(1, payload.suiteLinks.size)
    }
}
