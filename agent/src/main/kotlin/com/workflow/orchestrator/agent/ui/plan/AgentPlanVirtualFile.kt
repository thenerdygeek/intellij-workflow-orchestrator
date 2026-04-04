package com.workflow.orchestrator.agent.ui.plan

import com.intellij.testFramework.LightVirtualFile

/**
 * Minimal stub — plan mode will be reimplemented.
 */
class AgentPlanVirtualFile(
    val planContent: String,
    val sessionId: String
) : LightVirtualFile("Implementation Plan", AgentPlanFileType, "") {
    var currentPlanContent: String = planContent
    override fun isWritable() = false
}
