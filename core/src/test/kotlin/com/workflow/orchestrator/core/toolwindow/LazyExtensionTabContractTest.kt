package com.workflow.orchestrator.core.toolwindow

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Source-contract guard for perf audit P0-6 / bug B4 — lazy extension tabs +
 * listener-once registration in [WorkflowToolWindowFactory].
 *
 * Why source-text: `WorkflowToolWindowFactory` is wired into a live `ToolWindow` /
 * `ContentManager` and is not unit-instantiable in a headless test (same constraint
 * as `AgentService` — see DialogModalityContractTest for the precedent). These pins
 * lock the three invariants the fix introduced so they can't silently regress:
 *
 * 1. **Extension tabs are LAZY.** `buildTabs()` must never call `provider.createPanel`
 *    for extension-provided tabs (Agent → JCEF Chromium ~200MB). They get a
 *    `LazyTabPlaceholder` and are materialized on first selection, exactly like the
 *    non-first default tabs.
 * 2. **ContentManagerListener registered ONCE per ToolWindow.** `buildTabs()` is re-run
 *    by "Refresh All Tabs" and the settings-change `toolWindowShown` path; registering
 *    the listener inside `buildTabs()` stacked one listener per rebuild.
 * 3. **Rebuild guard.** While `removeAllContents(true)` runs, the content manager can
 *    shift selection onto doomed contents and fire `selectionChanged(add)`; without the
 *    `rebuildInProgress` guard the listener would materialize a doomed placeholder
 *    (a transient Chromium spawn per rebuild for the Agent tab).
 */
class LazyExtensionTabContractTest {

    // ── Source location ──────────────────────────────────────────────────────

    private fun coreMainRoot(): File {
        val userDir = System.getProperty("user.dir") ?: error("user.dir system property is not set")
        val root = File(userDir)
        val moduleRooted = File(root, "src/main/kotlin") // user.dir == <repo>/core
        val repoRooted = File(root, "core/src/main/kotlin") // user.dir == <repo>
        return when {
            moduleRooted.isDirectory -> moduleRooted
            repoRooted.isDirectory -> repoRooted
            else -> error("core main sources not found at either layout; user.dir=$userDir")
        }
    }

    private fun factorySource(): String {
        val f = File(coreMainRoot(), "com/workflow/orchestrator/core/toolwindow/WorkflowToolWindowFactory.kt")
        assertTrue(f.isFile, "Expected source file not found: ${f.absolutePath} — module layout may have changed.")
        return f.readText()
    }

    /**
     * Slices a function body out of the file text: from the declaration marker to the
     * next top-level `private fun ` declaration (or EOF). Keep new functions OUTSIDE
     * sliced ranges or update the sentinels (see memory: sentinel-slice trap).
     */
    private fun slice(source: String, fromDecl: String): String {
        val start = source.indexOf(fromDecl)
        assertTrue(start >= 0, "Declaration '$fromDecl' not found in WorkflowToolWindowFactory.kt")
        val rest = source.substring(start + fromDecl.length)
        val end = rest.indexOf("\n    private fun ")
        return fromDecl + if (end >= 0) rest.substring(0, end) else rest
    }

    // ── 1. Extension tabs are lazy ───────────────────────────────────────────

    @Test
    fun `buildTabs must not eagerly create extension-provider panels`() {
        val body = slice(factorySource(), "private fun buildTabs(")
        assertFalse(
            body.contains("provider.createPanel"),
            "buildTabs() eagerly calls provider.createPanel — extension tabs (Agent → JCEF " +
                "Chromium ~200MB) must be lazy: placeholder in buildTabs, real panel on first " +
                "selection via materializeByTitle (perf audit P0-6)."
        )
        assertTrue(
            body.contains("LazyTabPlaceholder(), provider.tabTitle"),
            "buildTabs() must add a LazyTabPlaceholder content for each extension-provided tab."
        )
    }

    @Test
    fun `materializeByTitle must cover extension-provider tabs`() {
        val body = slice(factorySource(), "private fun materializeByTitle(")
        assertTrue(
            body.contains("provider.createPanel(project)"),
            "materializeByTitle() must create extension-provider panels on first selection — " +
                "otherwise lazy extension tabs would never materialize."
        )
    }

    // ── 2. Listener registered once, outside buildTabs ───────────────────────

    @Test
    fun `ContentManagerListener is registered exactly once and not inside buildTabs`() {
        val source = factorySource()
        val occurrences = Regex(Regex.escape("addContentManagerListener")).findAll(source).count()
        assertEquals(
            1,
            occurrences,
            "Expected exactly ONE addContentManagerListener call site (in createToolWindowContent). " +
                "Registering it per buildTabs() rerun accumulates listeners (perf audit P0-6)."
        )
        val buildTabsBody = slice(source, "private fun buildTabs(")
        assertFalse(
            buildTabsBody.contains("addContentManagerListener"),
            "buildTabs() must not register the ContentManagerListener — buildTabs is re-run by " +
                "'Refresh All Tabs' and settings-change toolWindowShown, stacking listeners."
        )
        val createContent = slice(source, "override fun createToolWindowContent(")
        assertTrue(
            createContent.contains("addContentManagerListener"),
            "createToolWindowContent() must register the lazy-materialization listener (once per ToolWindow)."
        )
    }

    // ── 3. Rebuild guard + dispose cascade wiring ────────────────────────────

    @Test
    fun `rebuilds are guarded and lazily materialized panels get a Content disposer`() {
        val createContent = slice(factorySource(), "override fun createToolWindowContent(")
        assertTrue(
            createContent.contains("rebuildInProgress"),
            "createToolWindowContent() must guard the selection listener during buildTabs reruns " +
                "(removeAllContents fires selectionChanged(add) on doomed contents — without the " +
                "guard the Agent placeholder would transiently spawn Chromium on every rebuild)."
        )
        assertTrue(
            createContent.contains("content.setDisposer(realPanel)"),
            "The lazy-materialization listener must wire content.setDisposer(realPanel) so " +
                "removeAllContents(true) on rebuild actually disposes materialized panels (bug B4)."
        )
    }
}
