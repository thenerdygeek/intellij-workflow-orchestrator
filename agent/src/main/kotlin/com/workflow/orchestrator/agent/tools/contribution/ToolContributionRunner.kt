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
 *
 * The `addedToolNames` diff in [ContributionDiagnostics] is computed over [ToolRegistry.allToolNames],
 * which spans all three registry tiers (core, active-deferred, and deferred). Contributors that
 * register a deferred tool via [ToolRegistrationContext.registerDeferred] therefore appear in
 * the diagnostic correctly — using only [ToolRegistry.getActiveTools] would exclude them.
 */
object ToolContributionRunner {
    fun run(
        contributors: List<AgentToolContributor>,
        context: ToolRegistrationContext,
        registry: ToolRegistry,
    ): ContributionDiagnostics {
        val before = registry.allToolNames().toSet()
        val failures = mutableListOf<ContributorFailure>()
        for (c in contributors) {
            runCatching { c.registerTools(context) }
                .onFailure { failures += ContributorFailure(c::class.java.simpleName, it) }
        }
        val added = registry.allToolNames() - before
        return ContributionDiagnostics(
            contributorCount = contributors.size,
            contributorClasses = contributors.map { it::class.java.simpleName },
            addedToolNames = added,
            failures = failures,
        )
    }
}
