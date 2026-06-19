package com.workflow.orchestrator.agent.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Source-contract guard for perf audit P0-6 / bug B4 — Content-scoped JCEF disposal
 * on the Agent tab.
 *
 * The leak: `AgentDashboardPanel` was a plain `JPanel` (not `Disposable`), so the
 * tool-window factory's `if (panel is Disposable) content.setDisposer(panel)` never
 * wired for the Agent tab; and `AgentTabProvider` parented the JCEF browser, the
 * `AgentController`, and the EventBus collector scope to the PROJECT. Every tab
 * rebuild ("Refresh All Tabs" / settings change) created a fresh Chromium (~200MB)
 * + controller while the old ones survived to project close with live EventBus
 * collectors firing into a detached browser (B4).
 *
 * The fix chain — pinned here because the runtime path needs a live ToolWindow +
 * JCEF and is not reachable from headless tests (same precedent as
 * DialogModalityContractTest):
 *
 * 1. `AgentDashboardPanel` implements `Disposable` and self-parents the
 *    `AgentCefPanel` when no external parent is supplied.
 * 2. `AgentTabProvider` chains controller + scope disposal FROM the dashboard
 *    (Content-scoped), never from the project.
 * 3. `AgentController.dispose()` keeps the race-safe registry compare-and-null so
 *    a disposed controller is never left registered.
 */
class AgentTabDisposalContractTest {

    // ── Source location ──────────────────────────────────────────────────────

    private fun agentMainRoot(): File {
        val userDir = System.getProperty("user.dir") ?: error("user.dir system property is not set")
        val root = File(userDir)
        val moduleRooted = File(root, "src/main/kotlin") // user.dir == <repo>/agent
        val repoRooted = File(root, "agent/src/main/kotlin") // user.dir == <repo>
        return when {
            moduleRooted.isDirectory -> moduleRooted
            repoRooted.isDirectory -> repoRooted
            else -> error("agent main sources not found at either layout; user.dir=$userDir")
        }
    }

    private fun source(relPathFromKotlinRoot: String): String {
        val f = File(agentMainRoot(), relPathFromKotlinRoot)
        assertTrue(f.isFile, "Expected source file not found: ${f.absolutePath} — module layout may have changed.")
        return f.readText()
    }

    // ── 1. AgentDashboardPanel is Disposable and tears down the CEF panel ────

    @Test
    fun `AgentDashboardPanel must implement Disposable so content-setDisposer wires`() {
        val panel = source("com/workflow/orchestrator/agent/ui/AgentDashboardPanel.kt")
        assertTrue(
            Regex("""\)\s*:\s*JPanel\(BorderLayout\(\)\),\s*Disposable""").containsMatchIn(panel),
            "AgentDashboardPanel must declare Disposable as a supertype — the tool-window " +
                "factory only wires content.setDisposer(panel) for Disposable panels; without " +
                "it the Agent tab's Chromium is never tied to the Content lifecycle (P0-6)."
        )
        assertTrue(
            panel.contains("override fun dispose()"),
            "AgentDashboardPanel must override dispose()."
        )
    }

    @Test
    fun `AgentDashboardPanel self-parents the CEF panel and disposes it`() {
        val panel = source("com/workflow/orchestrator/agent/ui/AgentDashboardPanel.kt")
        assertTrue(
            panel.contains("parentDisposable ?: this"),
            "AgentDashboardPanel must self-parent the AgentCefPanel when no external parent is " +
                "supplied (the tool-window tab path), so Content.dispose() cascades into the " +
                "Chromium browser, its scope cancel, and the scheme-handler detach."
        )
        // dispose() must cover the direct-call path too (defense-in-depth for callers
        // that bypass the Disposer tree).
        assertTrue(
            Regex("""override fun dispose\(\)[\s\S]{0,800}Disposer\.dispose\(""").containsMatchIn(panel),
            "AgentDashboardPanel.dispose() must dispose the AgentCefPanel it owns."
        )
    }

    // ── 2. AgentTabProvider — Content-scoped, never project-parented ─────────

    @Test
    fun `AgentTabProvider must not parent JCEF or controller disposal to the project`() {
        val provider = source("com/workflow/orchestrator/agent/ui/AgentTabProvider.kt")
        assertFalse(
            provider.contains("Disposer.register(project"),
            "AgentTabProvider must NOT register disposables against the PROJECT — that is the " +
                "B4 leak: every tab rebuild leaked a live Chromium + AgentController (with live " +
                "EventBus collectors) until project close. Chain disposal from the dashboard " +
                "panel instead (Content-scoped via the factory's content.setDisposer)."
        )
        assertFalse(
            provider.contains("AgentDashboardPanel(parentDisposable = project"),
            "AgentTabProvider must NOT pass the project as the dashboard's parentDisposable — " +
                "the dashboard must self-parent so the Content disposer owns the JCEF lifecycle."
        )
    }

    @Test
    fun `AgentTabProvider chains controller and EventBus scope disposal from the dashboard`() {
        val provider = source("com/workflow/orchestrator/agent/ui/AgentTabProvider.kt")
        assertTrue(
            provider.contains("Disposer.register(dashboard, controller)"),
            "AgentTabProvider must chain AgentController disposal from the dashboard panel so a " +
                "tab rebuild (removeAllContents(true) → Content disposer → dashboard) disposes it."
        )
        assertTrue(
            Regex("""Disposer\.register\(dashboard,\s*Disposable\s*\{\s*scope\.cancel\(\)\s*\}\)""")
                .containsMatchIn(provider),
            "AgentTabProvider must chain the EventBus collector scope's cancel from the dashboard " +
                "panel — a project-scoped (or unregistered) scope keeps collecting into a detached " +
                "browser after rebuild (B4)."
        )
    }

    @Test
    fun `AgentTabProvider disposes the self-parented dashboard when post-construction wiring fails`() {
        val provider = source("com/workflow/orchestrator/agent/ui/AgentTabProvider.kt")
        assertTrue(
            Regex("""catch \(e: Exception\) \{[\s\S]{0,600}?Disposer\.dispose\(dashboard\)[\s\S]{0,120}?throw e""")
                .containsMatchIn(provider),
            "AgentTabProvider.createPanel must wrap post-construction wiring in try/catch, " +
                "Disposer.dispose(dashboard) on failure, and rethrow — otherwise a wiring " +
                "exception (controller ctor, EventBus getService) leaks the self-parented " +
                "dashboard + live Chromium unrooted until JVM exit."
        )
    }

    // ── 3. Registry stays race-safe on dispose ───────────────────────────────

    @Test
    fun `AgentController dispose keeps the registry compare-and-null`() {
        val controller = source("com/workflow/orchestrator/agent/ui/AgentController.kt")
        assertTrue(
            Regex("""if\s*\(reg\.controller\s*===\s*this\)\s*reg\.controller\s*=\s*null""")
                .containsMatchIn(controller),
            "AgentController.dispose() must compare-and-null itself in AgentControllerRegistry — " +
                "Content-scoped disposal relies on this so a disposed controller is never used " +
                "(and a newer controller that already re-registered is never clobbered)."
        )
    }
}
