package com.workflow.orchestrator.automation.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.automation.model.DriftResult
import com.workflow.orchestrator.automation.model.TagEntry

/**
 * Drift detection service — registry calls removed.
 *
 * Tag validation flow was removed by user decision: the plugin no longer makes
 * Docker Registry v2 HTTP calls. Bamboo handles missing-tag failures at run-time.
 * This service is retained as a no-op so AutomationPanel.enrichWithLatestReleaseTags
 * continues to compile; it always returns entries unchanged and reports the registry
 * as unconfigured so the UI skips the enrich step silently.
 */
@Service(Service.Level.PROJECT)
class DriftDetectorService {

    /** Project service constructor — used by IntelliJ DI. */
    @Suppress("UNUSED_PARAMETER")
    constructor(project: Project)

    /** Whether the registry is configured. Always false — registry calls removed. */
    fun isRegistryConfigured(): Boolean = false

    /** No-op: returns the input list unchanged. Registry calls removed. */
    suspend fun checkDrift(entries: List<TagEntry>): List<DriftResult> = emptyList()

    /**
     * No-op: returns entries unchanged. Registry calls removed.
     * AutomationPanel guards this call with [isRegistryConfigured], so this
     * path is never reached in normal operation.
     */
    suspend fun enrichWithLatestReleaseTags(entries: List<TagEntry>): List<TagEntry> = entries
}
