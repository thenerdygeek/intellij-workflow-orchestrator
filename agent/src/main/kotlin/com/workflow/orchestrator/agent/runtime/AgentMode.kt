package com.workflow.orchestrator.agent.runtime

/**
 * Execution mode for the agent orchestrator.
 *
 * SINGLE_AGENT: Default mode. One ReAct loop with all tools available.
 * The LLM decides when to analyze, code, review, or call enterprise tools.
 *
 * ORCHESTRATED: Plan + step-by-step execution mode. Used when the user
 * explicitly requests a plan or when auto-escalation triggers because
 * the task is too complex for a single agent pass.
 */
enum class AgentMode {
    SINGLE_AGENT,
    ORCHESTRATED
}
