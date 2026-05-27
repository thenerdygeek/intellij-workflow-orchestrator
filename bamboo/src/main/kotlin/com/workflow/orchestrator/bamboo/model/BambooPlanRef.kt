package com.workflow.orchestrator.bamboo.model

/**
 * Typed Bamboo plan/build target identity. Every instance is constructed ONLY from
 * Bamboo API responses (plan-branch list entries, result DTO keys) or from a verified
 * git default-branch comparison — never by inspecting the SHAPE of a key string.
 *
 * Replaces the `BRANCH_PLAN_KEY_REGEX` / `.last().isDigit()` heuristics that could not
 * distinguish a master key ending in a digit (`PROJ-BUILD2`) from a branch plan key
 * (`PROJ-PLAN138`) — the distinction is provenance, not text.
 * See docs/superpowers/specs/2026-05-27-bamboo-plan-identity-design.md.
 */
sealed interface BambooPlanRef {
    /** The real Bamboo key to address `/result/{planKey}/latest` — always a verbatim API value. */
    val planKey: String

    /** A child branch plan with its own distinct key (from `/plan/{master}/branch`). */
    data class BranchPlan(
        override val planKey: String,
        val parentPlanKey: String,
        val branchShortName: String,
    ) : BambooPlanRef

    /** The branch IS the master plan's tracked (default) branch; the master key is the target. */
    data class MasterTrackedBranch(
        override val planKey: String,
        val branchShortName: String,
    ) : BambooPlanRef

    /** A raw master plan with no branch context (plan picker / no branch supplied). */
    data class Master(override val planKey: String) : BambooPlanRef
}
