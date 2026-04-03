package com.workflow.orchestrator.agent.context

data class ContextManagementConfig(
    // Stage 2: Observation masking — token-utilization-gated with 3 content tiers
    val observationMaskingThreshold: Double = 0.60,
    val observationMaskingInnerWindowTokens: Int = 40_000,
    val observationMaskingOuterWindowTokens: Int = 60_000,

    // Stage 1: Smart pruner — zero-loss deduplication (always-on when enabled)
    val smartPrunerEnabled: Boolean = true,

    // Stage 3: Conversation window — proactive sliding-window trimming
    val conversationWindowThreshold: Double = 0.75,

    // Stage 4: LLM summarization — expensive, last resort before termination
    val llmSummarizingMaxSize: Int = 150,
    val llmSummarizingKeepFirst: Int = 4,
    val llmSummarizingTokenThreshold: Double = 0.85,
    val llmSummarizingMaxEventLength: Int = 10_000,

    // Rotation & safety
    val rotationThreshold: Double = 0.97,
    val condensationLoopThreshold: Int = 10,
    val useCheaperModelForSummarization: Boolean = true
) {
    companion object {
        val DEFAULT = ContextManagementConfig()
        val WORKER = ContextManagementConfig(
            observationMaskingThreshold = 0.50,
            observationMaskingInnerWindowTokens = 20_000,
            observationMaskingOuterWindowTokens = 30_000,
            conversationWindowThreshold = 0.65,
            llmSummarizingMaxSize = 50,
            llmSummarizingKeepFirst = 2,
            llmSummarizingTokenThreshold = 0.75,
            condensationLoopThreshold = 5
        )
    }
}
