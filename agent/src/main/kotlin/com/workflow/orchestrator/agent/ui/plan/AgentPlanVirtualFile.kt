package com.workflow.orchestrator.agent.ui.plan

import com.intellij.testFramework.LightVirtualFile
import com.workflow.orchestrator.agent.loop.PlanParser

/**
 * Virtual file backing the plan editor tab. Holds structured plan data.
 */
class AgentPlanVirtualFile(
    val plan: PlanParser.PlanJson,
    val sessionId: String
) : LightVirtualFile("Implementation Plan", AgentPlanFileType, "") {
    var currentPlan: PlanParser.PlanJson = plan
    override fun isWritable() = false
}
