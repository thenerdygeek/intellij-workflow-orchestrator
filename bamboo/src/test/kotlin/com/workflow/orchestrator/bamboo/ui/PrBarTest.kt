package com.workflow.orchestrator.bamboo.ui

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.workflow.PrRef
import com.workflow.orchestrator.core.model.workflow.WorkflowContext
import com.workflow.orchestrator.core.workflow.WorkflowContextService
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Item 9 — verifies [PrBar] is a passive mirror of [WorkflowContextService.state].
 *
 * The bar has two visual states:
 *  1. Live + focusPr != null → single-PR panel with `PR #id` text
 *  2. otherwise              → empty-state panel with the "pick one in the PR tab" prompt
 *
 * Tests do NOT exercise the Swing event thread — they call [PrBar.renderFromContextForTest]
 * (the test-only seam) to drive the decision deterministically. The full collector path is
 * structurally validated by [BuildDashboardPanelCoherenceTest] (single state emission).
 */
class PrBarTest {

    /**
     * Pre-cancelled scope — `cs.launch { ... }` inside [PrBar.init] becomes a no-op so
     * the production state-collector path doesn't fire (it would otherwise call
     * `invokeLater` and NPE in a non-platform JVM). The render decision is then
     * driven directly via the [renderFromContextForTest] reflection seam.
     */
    private val barScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        .also { it.cancel() }

    /** Live scope for stub flows. */
    private val helperScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @AfterEach
    fun teardown() {
        unmockkObject(WorkflowContextService.Companion)
        helperScope.cancel()
    }

    @Test
    fun `focusPr null renders empty-state panel`() {
        val (project, _) = stubService(WorkflowContext(focusPr = null, activeBranch = "main"))
        val bar = constructOnEdt { PrBar(project, barScope) }
        bar.renderFromContextForTest(WorkflowContext(focusPr = null, activeBranch = "main"))

        val rendered = currentRenderedPanel(bar)
        assertTrue(rendered.background == com.workflow.orchestrator.core.ui.StatusColors.INFO_BG,
            "Expected empty-state (INFO_BG) panel when focusPr is null")
    }

    @Test
    fun `interactionMode ReadOnly renders empty-state panel`() {
        val pr = PrRef(prId = 7, fromBranch = "feat/abc", toBranch = "main",
            repoName = "repo", bambooPlanKey = null, sonarProjectKey = null)
        // Local branch differs from PR's fromBranch → ReadOnly.
        val ctx = WorkflowContext(focusPr = pr, activeBranch = "main")
        val (project, _) = stubService(ctx)
        val bar = constructOnEdt { PrBar(project, barScope) }
        bar.renderFromContextForTest(ctx)

        val rendered = currentRenderedPanel(bar)
        assertTrue(rendered.background == com.workflow.orchestrator.core.ui.StatusColors.INFO_BG,
            "Expected empty-state panel under ReadOnly (branch mismatch)")
    }

    @Test
    fun `Live with focusPr renders single-PR panel containing PR-#id`() {
        val pr = PrRef(prId = 42, fromBranch = "feat/abc", toBranch = "main",
            repoName = "repo", bambooPlanKey = null, sonarProjectKey = null)
        val activeRepo = com.workflow.orchestrator.core.model.workflow.RepoRef(
            name = "repo", projectKey = "P", repoSlug = "repo", localVcsRootPath = "/p/repo"
        )
        val ctx = WorkflowContext(focusPr = pr, activeBranch = "feat/abc", activeRepo = activeRepo)
        val (project, _) = stubService(ctx)
        val bar = constructOnEdt { PrBar(project, barScope) }
        bar.renderFromContextForTest(ctx)

        val rendered = currentRenderedPanel(bar)
        assertTrue(rendered.background == com.workflow.orchestrator.core.ui.StatusColors.SUCCESS_BG,
            "Expected single-PR (SUCCESS_BG) panel under Live + focusPr != null")

        // Verify the rendered text includes PR #id.
        val labelText = findFirstLabelHtml(rendered)
        assertNotNull(labelText, "Single-PR panel should contain a JBLabel with PR text")
        assertTrue(labelText!!.contains("PR #42"),
            "Label text should contain 'PR #42', was: $labelText")
        assertTrue(labelText.contains("feat/abc"),
            "Label text should contain fromBranch, was: $labelText")
    }

    @Test
    fun `branch matches but repo differs renders empty-state (multi-module same-branch case)`() {
        // H1 from the 2026-04-27 sweep code review: two submodules sharing a branch name
        // (e.g. both branched from the same Jira ticket) should NOT render the focused PR
        // when the editor's active repo differs from the PR's repo.
        val pr = PrRef(prId = 42, fromBranch = "feat/abc", toBranch = "main",
            repoName = "repo-a", bambooPlanKey = null, sonarProjectKey = null)
        val activeRepoB = com.workflow.orchestrator.core.model.workflow.RepoRef(
            name = "repo-b", projectKey = "P", repoSlug = "repo-b", localVcsRootPath = "/p/repo-b"
        )
        val ctx = WorkflowContext(focusPr = pr, activeBranch = "feat/abc", activeRepo = activeRepoB)
        val (project, _) = stubService(ctx)
        val bar = constructOnEdt { PrBar(project, barScope) }
        bar.renderFromContextForTest(ctx)

        val rendered = currentRenderedPanel(bar)
        assertTrue(rendered.background == com.workflow.orchestrator.core.ui.StatusColors.INFO_BG,
            "Expected empty-state panel: branch matched but repo differed (multi-module ambiguity)")
    }

    // -------------------- helpers --------------------

    private fun stubService(initial: WorkflowContext): Pair<Project, MutableStateFlow<WorkflowContext>> {
        val project = mockk<Project>(relaxed = true)
        val service = mockk<WorkflowContextService>(relaxed = true)
        val flow = MutableStateFlow(initial)
        every { service.state } returns flow as StateFlow<WorkflowContext>
        every { service.interactionModeFlow } returns flow
            .map { it.interactionMode }
            .distinctUntilChanged()
            .stateIn(helperScope, SharingStarted.Eagerly, initial.interactionMode)
        mockkObject(WorkflowContextService.Companion)
        every { WorkflowContextService.getInstance(project) } returns service
        return project to flow
    }

    private fun <T> constructOnEdt(builder: () -> T): T {
        var result: T? = null
        if (SwingUtilities.isEventDispatchThread()) {
            result = builder()
        } else {
            SwingUtilities.invokeAndWait { result = builder() }
        }
        return result!!
    }

    private fun currentRenderedPanel(bar: PrBar): JPanel {
        // contentPanel holds exactly one child JPanel after each render decision.
        val contentField = PrBar::class.java.getDeclaredField("contentPanel").apply { isAccessible = true }
        val content = contentField.get(bar) as JPanel
        return content.getComponent(0) as JPanel
    }

    /** Walks the rendered panel for a JBLabel and returns its text. */
    private fun findFirstLabelHtml(panel: JPanel): String? {
        for (c in panel.components) {
            if (c is JPanel) {
                for (cc in c.components) {
                    if (cc is com.intellij.ui.components.JBLabel && cc.text.contains("PR #")) {
                        return cc.text
                    }
                }
            }
        }
        // Fallback — direct children.
        for (c in panel.components) {
            if (c is com.intellij.ui.components.JBLabel) return c.text
        }
        return null
    }
}

/**
 * Test seam — invoke the private renderFromContext synchronously without going through
 * the live state collector. Marked internal so the test class in the same module can call
 * it; production code does NOT use this.
 */
internal fun PrBar.renderFromContextForTest(ctx: WorkflowContext) {
    val m = PrBar::class.java.getDeclaredMethod("renderFromContext", WorkflowContext::class.java)
    m.isAccessible = true
    if (SwingUtilities.isEventDispatchThread()) {
        m.invoke(this, ctx)
    } else {
        SwingUtilities.invokeAndWait { m.invoke(this, ctx) }
    }
    // Sanity check the renderedPr field exists post-call (mirror of focus state).
    val f = PrBar::class.java.getDeclaredField("renderedPr").apply { isAccessible = true }
    if (ctx.focusPr != null && ctx.interactionMode == com.workflow.orchestrator.core.model.workflow.InteractionMode.Live) {
        assertNotNull(f.get(this))
    } else {
        assertNull(f.get(this))
    }
}
