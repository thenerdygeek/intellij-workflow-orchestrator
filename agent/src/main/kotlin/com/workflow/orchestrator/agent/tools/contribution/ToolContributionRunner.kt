package com.workflow.orchestrator.agent.tools.contribution

import com.workflow.orchestrator.agent.tools.ToolRegistry

/** Diagnostics from running the agent-tool contributors. Purely a return value for logging. */
data class ContributionDiagnostics(
    val contributorCount: Int,
    val contributorClasses: List<String>,
    val addedToolNames: Set<String>,
    val failures: List<ContributorFailure>,
)

data class ContributorFailure(val contributorClass: String, val error: Throwable)

/**
 * Runs each [AgentToolContributor] under its OWN `runCatching` so one misbehaving contributor
 * (e.g. from plugin B) cannot abort the others — the per-contributor isolation deferred from
 * Phase 0a (where a single `runCatching` wrapped the whole `forEach`). Pure: no platform/EP
 * access — the caller fetches the EP list and supplies the registry.
 */
object ToolContributionRunner {
    fun run(
        contributors: List<AgentToolContributor>,
        context: ToolRegistrationContext,
        registry: ToolRegistry,
    ): ContributionDiagnostics {
        val before = registry.getActiveTools().keys.toSet()
        val failures = mutableListOf<ContributorFailure>()
        for (c in contributors) {
            runCatching { c.registerTools(context) }
                .onFailure { failures += ContributorFailure(c::class.java.simpleName, it) }
        }
        val added = registry.getActiveTools().keys - before
        return ContributionDiagnostics(
            contributorCount = contributors.size,
            contributorClasses = contributors.map { it::class.java.simpleName },
            addedToolNames = added,
            failures = failures,
        )
    }
}
