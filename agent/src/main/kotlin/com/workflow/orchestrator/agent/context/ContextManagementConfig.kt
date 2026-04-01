package com.workflow.orchestrator.agent.context

data class ContextManagementConfig(
    val observationMaskingWindow: Int = 30,
    val smartPrunerEnabled: Boolean = true,
    val llmSummarizingMaxSize: Int = 150,
    val llmSummarizingKeepFirst: Int = 4,
    val llmSummarizingTokenThreshold: Double = 0.75,
    val llmSummarizingMaxEventLength: Int = 10_000,
    val rotationThreshold: Double = 0.97,
    val condensationLoopThreshold: Int = 10,
    val useCheaperModelForSummarization: Boolean = true
) {
    companion object {
        val DEFAULT = ContextManagementConfig()
        val WORKER = ContextManagementConfig(
            observationMaskingWindow = 15,
            llmSummarizingMaxSize = 50,
            llmSummarizingKeepFirst = 2,
            llmSummarizingTokenThreshold = 0.70,
            condensationLoopThreshold = 5
        )
    }
}
