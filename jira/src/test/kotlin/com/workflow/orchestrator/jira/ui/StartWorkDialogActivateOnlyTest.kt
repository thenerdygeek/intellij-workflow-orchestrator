package com.workflow.orchestrator.jira.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.workflow.orchestrator.core.bitbucket.BitbucketBranch
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Synchronous tests for the Start Work "activate only" toggle.
 *
 * **Why a single test method?** When the class had 4 separate `fun test…()`
 * methods, `BasePlatformTestCase` leaked the IntelliJ project indexing slot
 * between methods — the first method consumed it and the next ones hung 600s
 * on "Indexing timeout". Collapsing into one method (one indexing slot
 * consumption, then teardown) sidesteps that quirk. The method exercises all
 * four scenarios in clearly-separated blocks; if one fails, the assertion
 * message identifies which scenario.
 *
 * The dialog is constructed but never modally shown — we drive its public API
 * (`setActivateOnlyForTest`, `doValidateForTest`, `invokeOKActionForTest`) directly on
 * the EDT and inspect `result`. No `runBlocking`, no async — nothing in
 * `StartWorkDialog`'s checkbox path is suspend.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class StartWorkDialogActivateOnlyTest : BasePlatformTestCase() {

    private fun newDialog(): StartWorkDialog = StartWorkDialog(
        project = project,
        ticketKey = "ABC-123",
        defaultBranchName = "feature/ABC-123-test",
        initialRemoteBranches = listOf(BitbucketBranch("refs/heads/main", "main")),
        initialDefaultSourceBranch = "main",
        repoDisplay = "PROJ/repoa",
        needsAiGeneration = false,
        fallbackBranchName = "feature/ABC-123-fallback",
    )

    private fun StartWorkDialog.disposeIfNeeded() = try { close(0) } catch (_: Throwable) { /* already closed */ }

    fun `test activateOnly checkbox covers default, toggle, and both doOK paths`() {
        // Scenario 1 — default state is unchecked, preserving existing flow.
        run {
            val d = newDialog()
            try {
                assertFalse(
                    "scenario 1: checkbox must default unchecked to preserve existing flow",
                    d.isActivateOnlyForTest(),
                )
            } finally { d.disposeIfNeeded() }
        }

        // Scenario 2 — toggling activateOnly short-circuits validation.
        run {
            val d = newDialog()
            try {
                d.setActivateOnlyForTest(true)
                assertNull(
                    "scenario 2: activateOnly must short-circuit validation regardless of branch fields",
                    d.doValidateForTest(),
                )
            } finally { d.disposeIfNeeded() }
        }

        // Scenario 3 — OK action in activateOnly mode produces an activateOnly result.
        run {
            val d = newDialog()
            try {
                d.setActivateOnlyForTest(true)
                d.invokeOKActionForTest()

                val r = d.result ?: error("scenario 3: dialog produced no result")
                assertTrue("scenario 3: result must carry activateOnly=true", r.activateOnly)
                assertEquals("scenario 3: branchName must be empty", "", r.branchName)
                assertEquals("scenario 3: sourceBranch must be empty", "", r.sourceBranch)
                assertFalse("scenario 3: useExisting must be false", r.useExisting)
            } finally { d.disposeIfNeeded() }
        }

        // Scenario 4 — OK action in default mode is unaffected by the new field.
        run {
            val d = newDialog()
            try {
                d.invokeOKActionForTest()
                val r = d.result ?: error("scenario 4: dialog produced no result")
                assertFalse("scenario 4: default mode must yield activateOnly=false", r.activateOnly)
                assertEquals("scenario 4: branchName must be the default", "feature/ABC-123-test", r.branchName)
                assertEquals("scenario 4: sourceBranch must be the initial source", "main", r.sourceBranch)
            } finally { d.disposeIfNeeded() }
        }
    }
}
