package com.workflow.orchestrator.agent.context.condenser

/**
 * A composable pipeline of [Condenser] stages.
 *
 * Each condenser in the pipeline receives the output view of the previous
 * stage (via an updated [CondenserContext]). If any stage returns a
 * [Condensation], the pipeline short-circuits immediately -- no further
 * condensers run.
 *
 * If all condensers return [CondenserView], the final view is returned.
 * An empty pipeline returns the original view unchanged.
 *
 * This follows the OpenHands composable condenser pattern where lightweight
 * condensers (pruning, filtering) run first, and heavier ones (LLM
 * summarization) only trigger if earlier stages didn't resolve the pressure.
 */
class CondenserPipeline(private val condensers: List<Condenser>) : Condenser {

    override fun condense(context: CondenserContext): CondenserResult {
        var currentContext = context
        for (condenser in condensers) {
            when (val result = condenser.condense(currentContext)) {
                is Condensation -> return result
                is CondenserView -> currentContext = currentContext.copy(view = result.view)
            }
        }
        return CondenserView(currentContext.view)
    }
}
