package com.workflow.orchestrator.agent.ui.plan

import com.intellij.testFramework.LightVirtualFile
import com.workflow.orchestrator.agent.runtime.AgentPlan

class AgentPlanVirtualFile(
    val plan: AgentPlan,
    val sessionId: String
) : LightVirtualFile("Implementation Plan", AgentPlanFileType, "") {
    var currentPlan: AgentPlan = plan
    override fun isWritable() = false
}
