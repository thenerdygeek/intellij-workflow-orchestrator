package com.workflow.orchestrator.pullrequest.service

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Source-text contract test for D2 (audit finding pullrequest:F-3).
 *
 * PrListService.refresh() auto-detects the Bitbucket username on first call and
 * persists it to ConnectionSettings.bitbucketUsername. ConnectionSettings is a
 * PersistentStateComponent — IntelliJ requires all state mutations to run on the
 * EDT to prevent corrupt XML serialization and missed StateChanged notifications.
 *
 * Previously the assignment ran directly inside a Dispatchers.IO coroutine.
 * Fix: wrap in withContext(Dispatchers.EDT) { ... }.
 *
 * Why source-text: PrListService depends on IntelliJ platform services that
 * cannot be instantiated in a plain JUnit 5 environment. Source-text pins are
 * the established pattern in this codebase for threading invariants that require
 * the platform to exercise end-to-end.
 */
class PrListServiceEdtMutationTest {

    private val src: String by lazy {
        java.io.File(
            "src/main/kotlin/com/workflow/orchestrator/pullrequest/service/PrListService.kt"
        ).readText()
    }

    @Test
    fun `bitbucketUsername assignment is wrapped in withContext(Dispatchers EDT)`() {
        // Locate the assignment and verify it is inside a withContext(Dispatchers.EDT) block.
        val edtCtxIdx = src.indexOf("withContext(Dispatchers.EDT)")
        assertTrue(edtCtxIdx >= 0,
            "PrListService must contain withContext(Dispatchers.EDT) for the bitbucketUsername assignment")

        val assignIdx = src.indexOf("connSettings.bitbucketUsername = result.data", edtCtxIdx)
        assertTrue(assignIdx > edtCtxIdx && assignIdx < edtCtxIdx + 200,
            "connSettings.bitbucketUsername assignment must appear inside withContext(Dispatchers.EDT) block")
    }

    @Test
    fun `raw assignment without EDT hop does not exist in IO scope`() {
        // There must be no occurrence of the assignment OUTSIDE the withContext block.
        // Verify the only occurrence follows the EDT hop.
        val allAssignments = Regex("""connSettings\.bitbucketUsername\s*=\s*result\.data""")
            .findAll(src).toList()
        assertTrue(allAssignments.isNotEmpty(), "Assignment must exist in the source")

        val edtIdx = src.indexOf("withContext(Dispatchers.EDT)")
        allAssignments.forEach { match ->
            assertTrue(match.range.first > edtIdx,
                "Every connSettings.bitbucketUsername assignment must appear after withContext(Dispatchers.EDT)")
        }
    }
}
