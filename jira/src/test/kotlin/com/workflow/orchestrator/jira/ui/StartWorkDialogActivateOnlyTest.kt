package com.workflow.orchestrator.jira.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.workflow.orchestrator.core.bitbucket.BitbucketBranch
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Synchronous tests for the Start Work "activate only" toggle.
 *
 * The dialog is constructed but never modally shown — we drive its public API
 * (`setActivateOnlyForTest`, `doValidate`, `invokeOKActionForTest`) directly on
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

    fun `test activateOnly default is unchecked`() {
        val d = newDialog()
        try {
            assertFalse("checkbox must default unchecked to preserve existing flow", d.isActivateOnlyForTest())
        } finally { d.closeForTest() }
    }

    fun `test toggling activateOnly disables branch fields and validation passes`() {
        val d = newDialog()
        try {
            d.setActivateOnlyForTest(true)

            // Validation must NOT block OK in activateOnly mode, regardless of branch fields.
            assertNull("activateOnly must short-circuit validation", d.doValidateForTest())
        } finally { d.closeForTest() }
    }

    fun `test doOKAction in activateOnly mode produces an activateOnly result`() {
        val d = newDialog()
        try {
            d.setActivateOnlyForTest(true)
            d.invokeOKActionForTest()

            val r = d.result ?: error("dialog produced no result")
            assertTrue("result must carry activateOnly=true", r.activateOnly)
            assertEquals("", r.branchName)
            assertEquals("", r.sourceBranch)
            assertFalse(r.useExisting)
        } finally { d.closeForTest() }
    }

    fun `test doOKAction in default mode is unaffected`() {
        val d = newDialog()
        try {
            d.invokeOKActionForTest()
            val r = d.result ?: error("dialog produced no result")
            assertFalse("default mode must yield activateOnly=false", r.activateOnly)
            assertEquals("feature/ABC-123-test", r.branchName)
            assertEquals("main", r.sourceBranch)
        } finally { d.closeForTest() }
    }

    private fun StartWorkDialog.closeForTest() = try { close(0) } catch (_: Throwable) { /* already closed */ }
}
