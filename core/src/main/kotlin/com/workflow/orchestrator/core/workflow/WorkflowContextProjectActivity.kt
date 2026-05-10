package com.workflow.orchestrator.core.workflow

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.workflow.orchestrator.core.model.workflow.TicketRef
import com.workflow.orchestrator.core.settings.PluginSettings

/**
 * Project-open hydration for [WorkflowContextService].
 *
 * Seed order matters:
 *
 *  1. Editor-derived slice (`activeRepo`, `activeBranch`, `editorModule`, `projectModules`)
 *     is populated via [WorkflowContextService.recomputeFromEditor]. This must come first so
 *     any subsequent cascade has the correct repo context. See
 *     `docs/architecture/multi-module-compliance-plan.md` Phase A.
 *
 *  2. Phase 7 / T-AutoSeed: if a persisted anchor exists in [PluginSettings], call
 *     [WorkflowContextService.setActiveTicket] to fire the `focusPr` auto-seed cascade
 *     specified in the Phase 5 design (`workflow-context-design.md` §4.5 step 3). Without
 *     this, `focusBuild` stays null on fresh IDE and downstream focus-driven services
 *     (BuildMonitorService et al.) never start their ambient polling.
 *     See `docs/architecture/phase7-handover-context-plan.md` § T-AutoSeed.
 *
 *  3. [WorkflowEventMirror] is installed LAST so it is ready to forward any subsequent
 *     legacy `PrSelected` / `TicketChanged` events that arrive during the session, but
 *     the boot-time cascade (step 2) has already fired directly without going through the
 *     mirror. This ordering avoids a double-cascade for the boot anchor.
 *
 * Option A chosen (see T-AutoSeed task description): re-call `setActiveTicket` rather than
 * extracting a dedicated `autoSeedFromAnchor()`. The idempotent persistence write on boot
 * is negligible; the simplicity of using the existing well-tested mutator is worth it.
 */
class WorkflowContextProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val service = WorkflowContextService.getInstance(project)
        service.recomputeFromEditor()
        seedFromPersistedAnchor(project, service)
        WorkflowEventMirror(project, service).install()
    }

    /**
     * Phase 7 / T-AutoSeed: if a persisted anchor exists in [PluginSettings], fires
     * [WorkflowContextService.setActiveTicket] so the full `focusPr` auto-seed cascade runs.
     *
     * Extracted as an `internal` function so tests can exercise the anchor-detection and
     * cascade logic directly with a [TestScope]-backed service, without needing a real
     * IntelliJ [com.intellij.openapi.application.Application] instance (which `recomputeFromEditor`
     * requires via `readAction { }`).
     *
     * [WorkflowContextService.loadAnchorFromSettings] (called from service `init`) sets
     * `activeTicket` synchronously from the persisted ID, but does NOT trigger the
     * `focusPr` cascade. [WorkflowContextService.setActiveTicket] DOES trigger it via
     * [WorkflowContextService.findOpenPrMatchingTicket], and is idempotent for persistence
     * (writes the same value back, negligible on boot).
     */
    internal suspend fun seedFromPersistedAnchor(project: Project, service: WorkflowContextService) {
        val settings = PluginSettings.getInstance(project)
        val anchorKey = settings.state.activeTicketId?.trim()?.takeIf { it.isNotBlank() }
        if (anchorKey != null) {
            val anchorSummary = settings.state.activeTicketSummary.orEmpty()
            service.setActiveTicket(TicketRef(anchorKey, anchorSummary))
        }
    }
}
