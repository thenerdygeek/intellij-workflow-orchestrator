package com.workflow.orchestrator.handover.service

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Forces lazy init of [HandoverOverrideTracker] at project open. The tracker subscribes to
 * `EventBus.events` in its `init` block; without this wake-up, the service never instantiates
 * and `HandoverOverride` events are silently dropped from the audit log.
 *
 * Mirrors `DevStatusCacheInvalidatorActivity` in :core.
 */
internal class HandoverOverrideTrackerActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        HandoverOverrideTracker.getInstance(project)
    }
}
