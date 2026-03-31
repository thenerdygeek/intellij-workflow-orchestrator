package com.workflow.orchestrator.agent.context.condenser

/**
 * A condenser that passes the view through unchanged.
 *
 * Useful as a default/fallback condenser, for testing pipelines,
 * and as a placeholder in configuration when no condensation is desired.
 */
class NoOpCondenser : Condenser {
    override fun condense(context: CondenserContext): CondenserResult {
        return CondenserView(context.view)
    }
}
