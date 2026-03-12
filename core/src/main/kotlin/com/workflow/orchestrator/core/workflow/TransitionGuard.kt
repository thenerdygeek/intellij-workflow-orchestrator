package com.workflow.orchestrator.core.workflow

import com.intellij.openapi.project.Project

interface TransitionGuard {
    val id: String
    val description: String
    val applicableIntents: Set<WorkflowIntent>
    suspend fun evaluate(project: Project, issueKey: String): GuardResult
}

sealed class GuardResult {
    object Passed : GuardResult()
    data class Failed(val reason: String, val canOverride: Boolean = false) : GuardResult()
}
