package com.workflow.orchestrator.agent.ui.plan

import com.intellij.testFramework.LightVirtualFile

/**
 * Virtual file backing the plan editor tab. Holds plan content for display.
 */
class AgentPlanVirtualFile(
    val planContent: String,
    val sessionId: String
) : LightVirtualFile("Implementation Plan", AgentPlanFileType, "") {
    var currentPlanContent: String = planContent
    override fun isWritable() = false
}
