package com.workflow.orchestrator.handover.service

import com.workflow.orchestrator.handover.model.FindingSeverity
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PreReviewServiceTest {

    private val service = PreReviewService()

    @Test
    fun `parseFindings extracts structured findings from AI response`() {
        val response = """
            **HIGH** `UserService.kt:42` — Missing @Transactional on DB write method [missing-transactional]
            **MEDIUM** `ApiController.kt:15` — Unclosed HTTP connection [unclosed-resource]
            **LOW** `Utils.kt:88` — Consider using lazy initialization [unused-import]
        """.trimIndent()

        val findings = service.parseFindings(response)

        assertEquals(3, findings.size)
        assertEquals(FindingSeverity.HIGH, findings[0].severity)
        assertEquals("UserService.kt", findings[0].filePath)
        assertEquals(42, findings[0].lineNumber)
        assertEquals("missing-transactional", findings[0].pattern)
        assertEquals(FindingSeverity.MEDIUM, findings[1].severity)
        assertEquals(FindingSeverity.LOW, findings[2].severity)
    }

    @Test
    fun `parseFindings returns sorted by severity`() {
        val response = """
            **LOW** `a.kt:1` — minor [minor]
            **HIGH** `b.kt:2` — critical [critical]
        """.trimIndent()

        val findings = service.parseFindings(response)

        assertEquals(FindingSeverity.HIGH, findings[0].severity)
        assertEquals(FindingSeverity.LOW, findings[1].severity)
    }

    @Test
    fun `parseFindings returns empty list for no matches`() {
        val findings = service.parseFindings("No issues found in the code.")
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `buildReviewPrompt includes diff content`() {
        val diff = "+fun newMethod() { }"
        val prompt = service.buildReviewPrompt(diff)

        assertTrue(prompt.contains("Spring Boot"))
        assertTrue(prompt.contains(diff))
        assertTrue(prompt.contains("missing-transactional"))
    }

    @Test
    fun `validateDiff returns error for empty diff`() {
        val result = service.validateDiff("")
        assertEquals(PreReviewService.DiffValidation.EMPTY, result)
    }

    @Test
    fun `validateDiff returns warning for large diff`() {
        val largeDiff = "a\n".repeat(11_000)
        val result = service.validateDiff(largeDiff)
        assertEquals(PreReviewService.DiffValidation.TOO_LARGE, result)
    }

    @Test
    fun `validateDiff returns OK for normal diff`() {
        val result = service.validateDiff("+fun foo() {}")
        assertEquals(PreReviewService.DiffValidation.OK, result)
    }
}
