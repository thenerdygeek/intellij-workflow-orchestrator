package com.workflow.orchestrator.core.workflow

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.workflow.PrRef

/**
 * Extension point for cross-module open-PR enumeration. Implemented by :pullrequest;
 * consumed by :core's [WorkflowContextService.setActiveTicket] for auto-seed and by
 * the mirror for legacy [com.workflow.orchestrator.core.events.WorkflowEvent.PrSelected]
 * payload conversion.
 *
 * The `:core` module knows [PrRef] but cannot depend on `:pullrequest` (DAG) — this EP
 * is the sanctioned bridge. Same pattern as [com.workflow.orchestrator.core.bitbucket.CreatePrLauncher].
 */
interface OpenPrLister {
    /** Returns all known open PRs across all configured repos. May be empty. */
    fun listOpenPrs(project: Project): List<PrRef>

    companion object {
        val EP_NAME = ExtensionPointName.create<OpenPrLister>(
            "com.workflow.orchestrator.openPrLister"
        )
        fun getInstance(): OpenPrLister? = EP_NAME.extensionList.firstOrNull()
    }
}
