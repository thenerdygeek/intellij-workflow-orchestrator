package com.workflow.orchestrator.agent.ui.plan

import com.intellij.testFramework.LightVirtualFile
import com.workflow.orchestrator.agent.loop.PlanJson

/**
 * Virtual file backing the plan editor tab. Holds structured plan data.
 */
class AgentPlanVirtualFile(
    val plan: PlanJson,
    val sessionId: String
) : LightVirtualFile("Implementation Plan", AgentPlanFileType, "") {
    var currentPlan: PlanJson = plan
    override fun isWritable() = false
}
