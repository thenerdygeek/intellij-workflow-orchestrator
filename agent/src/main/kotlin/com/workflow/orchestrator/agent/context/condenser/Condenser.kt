package com.workflow.orchestrator.agent.context.condenser

import com.workflow.orchestrator.agent.context.events.CondensationAction
import com.workflow.orchestrator.agent.context.events.View

/**
 * Result of a condenser processing step.
 *
 * Either a modified [View] to pass to the next condenser in the pipeline,
 * or a [Condensation] action that short-circuits the pipeline and instructs
 * the event store to record a condensation event.
 */
sealed interface CondenserResult

/**
 * The condenser produced a (possibly modified) view. The pipeline continues
 * with this view as input to the next stage.
 */
data class CondenserView(val view: View) : CondenserResult

/**
 * The condenser determined that condensation is needed and produced a
 * [CondensationAction] to append to the event stream. This short-circuits
 * the pipeline -- no further condensers run.
 */
data class Condensation(val action: CondensationAction) : CondenserResult

/**
 * Input context provided to each condenser in the pipeline.
 *
 * Contains the current [view] (projection of event history) along with
 * token budget information reconciled from the LLM API.
 *
 * @property view The current filtered event projection
 * @property tokenUtilization Fraction of budget used (0.0-1.0), API-reconciled
 * @property effectiveBudget Total token budget available
 * @property currentTokens Current token count in the context
 */
data class CondenserContext(
    val view: View,
    val tokenUtilization: Double,
    val effectiveBudget: Int,
    val currentTokens: Int
)

/**
 * A composable context condensation stage.
 *
 * Condensers inspect the current [CondenserContext] and either:
 * - Return a [CondenserView] with a (possibly modified) view, or
 * - Return a [Condensation] with a [CondensationAction] to record
 *
 * Condensers are composed into a [CondenserPipeline] where each stage
 * receives the output view of the previous stage, and any [Condensation]
 * result short-circuits the pipeline.
 */
interface Condenser {
    fun condense(context: CondenserContext): CondenserResult
}

/**
 * A condenser that only triggers condensation when a threshold is crossed.
 *
 * Subclasses implement [shouldCondense] to define the trigger condition
 * and [getCondensation] to produce the condensation action. When the
 * condition is not met, the view passes through unchanged.
 *
 * This pattern is used by condensers that monitor token utilization
 * and only activate when the context window is filling up.
 */
abstract class RollingCondenser : Condenser {

    /**
     * Whether condensation should be triggered for the given context.
     */
    abstract fun shouldCondense(context: CondenserContext): Boolean

    /**
     * Produce the condensation action. Only called when [shouldCondense] returns true.
     */
    abstract fun getCondensation(context: CondenserContext): Condensation

    override fun condense(context: CondenserContext): CondenserResult {
        return if (shouldCondense(context)) {
            getCondensation(context)
        } else {
            CondenserView(context.view)
        }
    }
}
