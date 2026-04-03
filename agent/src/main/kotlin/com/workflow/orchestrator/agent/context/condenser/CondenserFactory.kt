package com.workflow.orchestrator.agent.context.condenser

import com.workflow.orchestrator.agent.context.ContextManagementConfig

/**
 * Factory that builds a [CondenserPipeline] from a [ContextManagementConfig].
 *
 * The pipeline stages are ordered from cheapest to most expensive:
 * 1. [SmartPrunerCondenser] — zero-loss deduplication and pruning (if enabled)
 * 2. [ObservationMaskingCondenser] — token-gated 3-tier observation compression (>=60%)
 * 3. [ConversationWindowCondenser] — proactive sliding-window trimming (>=75%)
 * 4. [LLMSummarizingCondenser] — LLM-powered summarization (>=85%, if client provided)
 *
 * If [llmClient] is null, the LLM summarization stage is omitted.
 * If [ContextManagementConfig.smartPrunerEnabled] is false, the SmartPruner stage is omitted.
 */
object CondenserFactory {

    /**
     * Build a [CondenserPipeline] from [config] and an optional [llmClient].
     *
     * @param config Configuration controlling condenser parameters
     * @param llmClient Optional LLM client for summarization; if null, LLM stage is skipped
     * @return A [CondenserPipeline] with the appropriate stages
     */
    fun create(
        config: ContextManagementConfig = ContextManagementConfig.DEFAULT,
        llmClient: SummarizationClient? = null
    ): CondenserPipeline {
        val condensers = mutableListOf<Condenser>()

        // Stage 1: SmartPruner (zero-loss optimization)
        if (config.smartPrunerEnabled) {
            condensers.add(SmartPrunerCondenser())
        }

        // Stage 2: ObservationMasking (token-gated, 3-tier compression)
        condensers.add(
            ObservationMaskingCondenser(
                threshold = config.observationMaskingThreshold,
                innerWindowTokens = config.observationMaskingInnerWindowTokens,
                outerWindowTokens = config.observationMaskingOuterWindowTokens
            )
        )

        // Stage 3: ConversationWindow (proactive sliding-window trimming)
        condensers.add(ConversationWindowCondenser(threshold = config.conversationWindowThreshold))

        // Stage 4: LLM Summarization (most expensive, only if client available)
        if (llmClient != null) {
            condensers.add(
                LLMSummarizingCondenser(
                    llmClient = llmClient,
                    keepFirst = config.llmSummarizingKeepFirst,
                    maxSize = config.llmSummarizingMaxSize,
                    tokenThreshold = config.llmSummarizingTokenThreshold,
                    maxEventLength = config.llmSummarizingMaxEventLength
                )
            )
        }

        return CondenserPipeline(condensers)
    }
}
