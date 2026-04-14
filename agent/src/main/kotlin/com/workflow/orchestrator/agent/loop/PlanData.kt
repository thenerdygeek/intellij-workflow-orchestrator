package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.agent.session.PlanStepStatus
import kotlinx.serialization.Serializable

/**
 * Plan data classes for the JCEF plan card UI.
 *
 * [PlanStep] and [PlanJson] are serialized to JSON and sent to the React webview
 * via the JCEF bridge. The LLM provides step titles via the mandatory `steps`
 * parameter on `plan_mode_respond`; execution progress is tracked via `task_progress`.
 */

@Serializable
data class PlanStep(
    val id: String,
    val title: String,
    val description: String = "",
    val status: PlanStepStatus = PlanStepStatus.PENDING
)

@Serializable
data class PlanJson(
    val summary: String,
    val steps: List<PlanStep>,
    val markdown: String? = null
)
