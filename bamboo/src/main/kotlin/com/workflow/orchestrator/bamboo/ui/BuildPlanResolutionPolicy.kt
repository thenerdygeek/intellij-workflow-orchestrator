package com.workflow.orchestrator.bamboo.ui

import com.workflow.orchestrator.core.services.ToolResult

/**
 * Pure decision logic for "given the multi-tier waterfall outcome and the user's
 * configured master plan key, what should the Build dashboard do next?"
 *
 * Extracted from [BuildDashboardPanel] so the resolution matrix is testable without
 * standing up the Swing/IntelliJ infrastructure the panel needs (Project,
 * GitRepositoryManager, EDT). Routes back into the panel via a single sealed return.
 */
object BuildPlanResolutionPolicy {

    sealed class Resolution {
        /** Waterfall succeeded — switch the monitor to this resolved branch plan key. */
        data class UseDetected(val planKey: String) : Resolution()

        /** Waterfall miss but the user configured a master plan key — honour it. */
        data class UseConfigured(val planKey: String) : Resolution()

        /** Waterfall miss and no configured master — show the user a hint. */
        data class NoPlan(val hintMessage: String) : Resolution()
    }

    fun resolve(
        detection: ToolResult<String>,
        configuredMasterKey: String?,
    ): Resolution {
        val detected = detection.data
        val waterfallHit = !detection.isError && !detected.isNullOrBlank()

        if (waterfallHit) return Resolution.UseDetected(detected!!)

        if (!configuredMasterKey.isNullOrBlank()) {
            return Resolution.UseConfigured(configuredMasterKey)
        }

        return Resolution.NoPlan(
            "No Bamboo build for this commit yet — push to trigger one, or configure a plan key in Settings > CI/CD"
        )
    }
}
