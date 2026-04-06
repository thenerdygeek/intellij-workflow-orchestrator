package com.workflow.orchestrator.agent.loop

/**
 * Manages smart model fallback during the agent loop.
 *
 * On network errors, advances through a fallback chain of progressively cheaper models.
 * After a cooldown period of successful iterations, attempts to escalate back to the
 * primary model. If escalation fails, waits longer before trying again.
 *
 * Fallback chain order (resolved from ModelCache at runtime):
 * 1. Opus thinking (primary)
 * 2. Opus non-thinking
 * 3. Sonnet thinking
 * 4. Sonnet non-thinking
 *
 * @param fallbackChain ordered list of model IDs (index 0 = primary)
 * @param initialEscalationThreshold successful iterations before first escalation attempt
 * @param extendedEscalationThreshold successful iterations after a failed escalation
 */
class ModelFallbackManager(
    private val fallbackChain: List<String>,
    private val initialEscalationThreshold: Int = 3,
    private val extendedEscalationThreshold: Int = 6
) {
    init {
        require(fallbackChain.isNotEmpty()) { "Fallback chain must not be empty" }
    }

    private var currentIndex: Int = 0
    private var iterationsSinceSwitch: Int = 0
    private var useExtendedThreshold: Boolean = false
    private var preEscalationIndex: Int = 0

    fun getCurrentModelId(): String = fallbackChain[currentIndex]

    fun isPrimary(): Boolean = currentIndex == 0

    fun onNetworkError(): String? {
        if (currentIndex >= fallbackChain.size - 1) return null
        currentIndex++
        iterationsSinceSwitch = 0
        return fallbackChain[currentIndex]
    }

    fun onIterationSuccess(): String? {
        if (isPrimary()) return null
        iterationsSinceSwitch++
        val threshold = if (useExtendedThreshold) extendedEscalationThreshold else initialEscalationThreshold
        if (iterationsSinceSwitch >= threshold) {
            preEscalationIndex = currentIndex
            currentIndex = 0
            iterationsSinceSwitch = 0
            useExtendedThreshold = false
            return fallbackChain[0]
        }
        return null
    }

    fun onEscalationFailed(): String {
        currentIndex = preEscalationIndex
        iterationsSinceSwitch = 0
        useExtendedThreshold = true
        return fallbackChain[currentIndex]
    }

    fun reset() {
        currentIndex = 0
        iterationsSinceSwitch = 0
        useExtendedThreshold = false
        preEscalationIndex = 0
    }
}
