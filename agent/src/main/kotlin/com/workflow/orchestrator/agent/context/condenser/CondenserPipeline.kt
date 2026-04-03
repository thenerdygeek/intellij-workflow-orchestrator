package com.workflow.orchestrator.agent.context.condenser

import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.context.events.*

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
 * **Inter-stage token update (M18):** After each stage that returns a
 * [CondenserView], the pipeline recalculates [CondenserContext.currentTokens]
 * and [CondenserContext.tokenUtilization] from the updated view so that
 * subsequent stages see accurate budget information. Without this, a stage
 * like SmartPruner could reduce tokens significantly but ObservationMasking
 * would still see the old (higher) utilization and over-compress.
 *
 * This follows the OpenHands composable condenser pattern where lightweight
 * condensers (pruning, filtering) run first, and heavier ones (LLM
 * summarization) only trigger if earlier stages didn't resolve the pressure.
 */
class CondenserPipeline(private val condensers: List<Condenser>) : Condenser {

    /** Returns the ordered list of condensers in this pipeline. Useful for testing. */
    fun getCondensers(): List<Condenser> = condensers

    override fun condense(context: CondenserContext): CondenserResult {
        var currentContext = context
        for (condenser in condensers) {
            when (val result = condenser.condense(currentContext)) {
                is Condensation -> return result
                is CondenserView -> {
                    // M18: Recalculate token utilization between stages so subsequent
                    // condensers see the actual post-reduction budget state.
                    val updatedTokens = estimateViewTokens(result.view.events) + currentContext.anchorTokens
                    val utilization = if (currentContext.effectiveBudget > 0)
                        updatedTokens.toDouble() / currentContext.effectiveBudget
                    else 0.0
                    currentContext = currentContext.copy(
                        view = result.view,
                        currentTokens = updatedTokens,
                        tokenUtilization = utilization
                    )
                }
            }
        }
        return CondenserView(currentContext.view)
    }

    companion object {
        /**
         * Estimate token count from raw events using content-based heuristic.
         * This avoids needing a full ConversationMemory round-trip just to get
         * an approximate token count between pipeline stages.
         *
         * Each event contributes its textual content (extracted via type-matching)
         * plus a 4-token overhead for role/separator framing, matching
         * [TokenEstimator.estimate(List<ChatMessage>)].
         */
        internal fun estimateViewTokens(events: List<Event>): Int {
            return events.sumOf { event ->
                val content = extractEventContent(event)
                if (content.isNotEmpty()) TokenEstimator.estimate(content) + 4 else 4
            }
        }

        /**
         * Extract the primary textual content from an event for token estimation.
         */
        private fun extractEventContent(event: Event): String = when (event) {
            // Observations all have a content field
            is Observation -> event.content
            // Non-tool actions with content
            is MessageAction -> event.content
            is SystemMessageAction -> event.content
            is UserSteeringAction -> event.content
            is AgentThinkAction -> event.thought
            is AgentFinishAction -> event.finalThought
            is DelegateAction -> event.prompt + (event.thought ?: "")
            is FactRecordedAction -> event.content
            is PlanUpdatedAction -> event.planJson
            is SkillActivatedAction -> event.content
            is SkillDeactivatedAction -> event.skillName
            is GuardrailRecordedAction -> event.rule
            is MentionAction -> event.content
            // Tool actions — estimate from arguments/identifiers
            is FileReadAction -> event.path
            is FileEditAction -> (event.oldStr ?: "") + (event.newStr ?: "")
            is CommandRunAction -> event.command
            is SearchCodeAction -> event.query
            is DiagnosticsAction -> event.path ?: ""
            is GenericToolAction -> event.arguments
            is MetaToolAction -> event.arguments
            // Structural actions with minimal content
            is CondensationAction -> event.summary ?: ""
            is CondensationRequestAction -> ""
        }
    }
}
