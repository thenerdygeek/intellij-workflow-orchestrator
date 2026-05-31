package com.workflow.orchestrator.pullrequest.service

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Source-text contract tests for PULLREQUEST-COV-7 and PULLREQUEST-COV-8.
 *
 * Both gaps involve logic inside private methods of [BitbucketServiceImpl]:
 *
 * COV-7: The two client-side post-fetch filters in `listPrComments` (lines 1078-1079):
 *   `if (onlyOpen) mapped = mapped.filter { it.state == PrCommentState.OPEN }`
 *   `if (onlyInline) mapped = mapped.filter { it.anchor != null }`
 *
 * COV-8: The defensive `runCatching { PrCommentState.valueOf(state) }.getOrDefault(PrCommentState.OPEN)`
 *   pattern in the private `toPrComment` extension function (lines 1395-1396).
 *
 * [BitbucketServiceImpl] depends on IntelliJ platform services (`PluginSettings`,
 * `BitbucketBranchClientCache`) that cannot be instantiated in a plain JUnit 5
 * environment. The pure-function predicates and fallback patterns are correct
 * one-liners that carry essentially no branching risk — making source-text
 * contract tests the appropriate coverage vehicle here (established pattern in
 * [PrListServiceEdtMutationTest]).
 */
class BitbucketServiceImplCommentFilterContractTest {

    private val src: String by lazy {
        java.io.File(
            "src/main/kotlin/com/workflow/orchestrator/pullrequest/service/BitbucketServiceImpl.kt"
        ).readText()
    }

    // ── PULLREQUEST-COV-7: onlyOpen / onlyInline filter expressions ───────────

    @Test
    fun `listPrComments applies onlyOpen filter on PrCommentState OPEN`() {
        val pattern = "if (onlyOpen) mapped = mapped.filter { it.state == PrCommentState.OPEN }"
        assertTrue(
            src.contains(pattern),
            "BitbucketServiceImpl.listPrComments must contain the onlyOpen filter: $pattern"
        )
    }

    @Test
    fun `listPrComments applies onlyInline filter on non-null anchor`() {
        val pattern = "if (onlyInline) mapped = mapped.filter { it.anchor != null }"
        assertTrue(
            src.contains(pattern),
            "BitbucketServiceImpl.listPrComments must contain the onlyInline filter: $pattern"
        )
    }

    @Test
    fun `listPrComments onlyOpen filter references PrCommentState not a raw string`() {
        // Guard against accidentally replacing enum comparison with a string literal
        val badPattern = "it.state == \"OPEN\""
        assertTrue(
            !src.contains(badPattern),
            "onlyOpen filter must compare against PrCommentState.OPEN enum, not a raw string \"OPEN\""
        )
    }

    // ── PULLREQUEST-COV-8: toPrComment enum fallback patterns ─────────────────

    @Test
    fun `toPrComment uses runCatching valueOf with OPEN fallback for state`() {
        val pattern = "runCatching { PrCommentState.valueOf(state) }.getOrDefault(PrCommentState.OPEN)"
        assertTrue(
            src.contains(pattern),
            "toPrComment must use runCatching-getOrDefault for state with OPEN fallback: $pattern"
        )
    }

    @Test
    fun `toPrComment uses runCatching valueOf with NORMAL fallback for severity`() {
        val pattern = "runCatching { PrCommentSeverity.valueOf(severity) }.getOrDefault(PrCommentSeverity.NORMAL)"
        assertTrue(
            src.contains(pattern),
            "toPrComment must use runCatching-getOrDefault for severity with NORMAL fallback: $pattern"
        )
    }

    @Test
    fun `toPrComment uses runCatching valueOf with null fallback for lineType`() {
        // The lineType uses getOrNull (null fallback via the outer let)
        assertTrue(
            src.contains("runCatching { PrCommentLineType.valueOf(it) }.getOrNull()"),
            "toPrComment must use runCatching-getOrNull for lineType to safely handle unknown values"
        )
    }

    @Test
    fun `toPrComment uses runCatching valueOf with null fallback for fileType`() {
        assertTrue(
            src.contains("runCatching { PrCommentFileType.valueOf(it) }.getOrNull()"),
            "toPrComment must use runCatching-getOrNull for fileType to safely handle unknown values"
        )
    }

    @Test
    fun `toPrComment fallback does not use bare valueOf without runCatching`() {
        // Ensure there is no bare PrCommentState.valueOf(...) without a runCatching wrapper
        val barePattern = Regex("""PrCommentState\.valueOf\((?!it\))""")
        val matches = barePattern.findAll(src).toList()
        assertTrue(
            matches.all { match ->
                // All occurrences must be inside a runCatching block
                val preceding = src.substring(maxOf(0, match.range.first - 30), match.range.first)
                preceding.contains("runCatching")
            },
            "All PrCommentState.valueOf calls must be wrapped in runCatching to prevent NoSuchElementException on unknown values"
        )
    }
}
