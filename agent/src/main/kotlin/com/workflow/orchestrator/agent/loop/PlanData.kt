package com.workflow.orchestrator.agent.loop

import kotlinx.serialization.Serializable

/**
 * Plan data class for the JCEF plan card UI.
 *
 * [PlanJson] is serialized to JSON and sent to the React webview via the JCEF bridge.
 * The LLM provides the plan as a markdown document via the `response` parameter on
 * `plan_mode_respond`. Execution progress is now tracked via the typed TaskStore
 * system (TaskCreate/TaskUpdate/TaskList tools) rather than the legacy step list.
 */

@Serializable
data class PlanJson(
    val summary: String,
    val markdown: String? = null,
)
