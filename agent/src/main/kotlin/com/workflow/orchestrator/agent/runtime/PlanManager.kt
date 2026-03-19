package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.CompletableFuture

@Serializable
data class PlanStep(
    val id: String,
    val title: String,
    val description: String = "",
    val files: List<String> = emptyList(),
    val action: String = "code",
    var status: String = "pending",
    var userComment: String? = null
)

@Serializable
data class AgentPlan(
    val goal: String,
    val approach: String = "",
    val steps: List<PlanStep>,
    val testing: String = "",
    var approved: Boolean = false
)

sealed class PlanApprovalResult {
    object Approved : PlanApprovalResult()
    data class Revised(val comments: Map<String, String>) : PlanApprovalResult()
}

class PlanManager {
    companion object {
        private val LOG = Logger.getInstance(PlanManager::class.java)
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }

    var currentPlan: AgentPlan? = null
        private set

    private var approvalFuture: CompletableFuture<PlanApprovalResult>? = null

    var onPlanCreated: ((AgentPlan) -> Unit)? = null
    var onStepUpdated: ((String, String) -> Unit)? = null

    fun submitPlan(plan: AgentPlan): CompletableFuture<PlanApprovalResult> {
        currentPlan = plan
        approvalFuture = CompletableFuture()
        LOG.info("PlanManager: plan submitted with ${plan.steps.size} steps")
        onPlanCreated?.invoke(plan)
        return approvalFuture!!
    }

    fun approvePlan() {
        currentPlan?.approved = true
        LOG.info("PlanManager: plan approved")
        approvalFuture?.complete(PlanApprovalResult.Approved)
    }

    fun revisePlan(comments: Map<String, String>) {
        currentPlan?.steps?.forEach { step ->
            comments[step.id]?.let { step.userComment = it }
        }
        LOG.info("PlanManager: plan revision requested with ${comments.size} comments")
        approvalFuture?.complete(PlanApprovalResult.Revised(comments))
    }

    fun updateStepStatus(stepId: String, status: String) {
        currentPlan?.steps?.find { it.id == stepId }?.status = status
        onStepUpdated?.invoke(stepId, status)
    }

    fun hasPlan(): Boolean = currentPlan != null
    fun isPlanApproved(): Boolean = currentPlan?.approved == true

    fun clear() {
        currentPlan = null
        approvalFuture = null
    }
}
