package com.workflow.orchestrator.web.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Regression tests for the S3 fix: the approval dialog now has working subdomainGlob /
 * allowHttp checkboxes. We can't crisply unit-test the Swing flow from a headless module,
 * but we pin the [ApprovalDialog.eTldPlus1] tooltip helper that the new glob checkbox
 * label depends on. Manual smoke (per the S3 commit message) verifies the wiring end-to-end.
 */
class ApprovalDialogHelpersTest {

    @ParameterizedTest(name = "eTldPlus1({0}) == {1}")
    @CsvSource(
        "https://example.com/foo,            example.com",
        "https://docs.example.com/path,      example.com",
        "https://a.b.c.example.com/x,        example.com",
        "https://localhost:8080/path,        localhost",
        "not-a-url,                          ''",
    )
    fun `eTldPlus1 returns coarse domain for tooltip display`(url: String, expected: String) {
        assertEquals(expected, ApprovalDialog.eTldPlus1(url))
    }
}
