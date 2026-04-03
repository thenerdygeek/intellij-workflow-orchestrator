package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.workflow.orchestrator.agent.AgentService
import java.util.concurrent.CompletableFuture

@Serializable
data class PlanStep(
    val id: String,
    val title: String,
    val description: String = "",
    val files: List<String> = emptyList(),
    val action: String = "code",
    var status: String = "pending",
    var userComment: String? = null,
    val dependsOn: List<String> = emptyList()
)

@Serializable
data class AgentPlan(
    val goal: String,
    val approach: String = "",
    val steps: List<PlanStep>,
    val testing: String = "",
    var approved: Boolean = false,
    /** Display title for the plan (shown in chat card header). */
    val title: String = "",
    /** Raw markdown document from the LLM. When present, the plan editor
     *  renders this directly instead of synthesizing from structured fields.
     *  Null for backward compat with old plans that only have structured data. */
    val markdown: String? = null,
    /** LLM-generated short summary for the plan card preview. Set asynchronously
     *  after plan creation via a cheap model call. */
    var summary: String? = null
)

/** A single user comment on a specific line of the plan. */
data class PlanRevisionComment(
    val line: String,     // The actual plan line content the comment refers to
    val comment: String   // The user's feedback
)

sealed class PlanApprovalResult {
    object Approved : PlanApprovalResult()
    /** Legacy: line-ID keyed comments (from old plan card UI). */
    data class Revised(val comments: Map<String, String>) : PlanApprovalResult()
    /** New: contextual comments with line content + full plan markdown. */
    data class RevisedWithContext(
        val revisions: List<PlanRevisionComment>,
        val fullMarkdown: String?
    ) : PlanApprovalResult()
    /** User typed a message in chat while the plan was awaiting approval.
     *  Could be a question, revision feedback, or general discussion — the agent decides. */
    data class ChatMessage(val message: String) : PlanApprovalResult()
}

class PlanManager {
    companion object {
        private val LOG = Logger.getInstance(PlanManager::class.java)
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** Default timeout for plan approval — auto-approve after this period. */
        const val DEFAULT_APPROVAL_TIMEOUT_MS = 60_000L
    }

    var currentPlan: AgentPlan? = null
        private set

    private var approvalFuture: CompletableFuture<PlanApprovalResult>? = null

    var onPlanCreated: ((AgentPlan) -> Unit)? = null
    var onStepUpdated: ((String, String) -> Unit)? = null

    /** Session directory for plan.json persistence. Set by AgentController. */
    var sessionDir: java.io.File? = null

    /** Callback to update the anchored plan summary in context. */
    var onPlanAnchorUpdate: ((AgentPlan) -> Unit)? = null

    /** Callback fired after the plan is approved — carries the approved plan for UI re-render. */
    var onPlanApproved: ((AgentPlan) -> Unit)? = null

    fun submitPlan(plan: AgentPlan): CompletableFuture<PlanApprovalResult> {
        currentPlan = plan
        approvalFuture = CompletableFuture()
        LOG.info("PlanManager: plan submitted with ${plan.steps.size} steps")
        onPlanCreated?.invoke(plan)
        sessionDir?.let { PlanPersistence.save(plan, it) }
        onPlanAnchorUpdate?.invoke(plan)
        return approvalFuture!!
    }

    /**
     * Submit a plan and suspend until the user approves or revises it.
     * Auto-approves if the user does not respond within [timeoutMs] (plan is advisory).
     *
     * @param plan The plan to submit
     * @param timeoutMs How long to wait before auto-approving (default 60s)
     * @return [PlanApprovalResult] indicating user decision or auto-approval
     */
    suspend fun submitPlanAndWait(plan: AgentPlan, timeoutMs: Long = DEFAULT_APPROVAL_TIMEOUT_MS): PlanApprovalResult {
        currentPlan = plan
        val deferred = CompletableDeferred<PlanApprovalResult>()
        // Store for resolve by approvePlan()/revisePlan()
        approvalDeferred = deferred
        LOG.info("PlanManager: plan submitted (async) with ${plan.steps.size} steps, timeout=${timeoutMs}ms")
        onPlanCreated?.invoke(plan)
        sessionDir?.let { PlanPersistence.save(plan, it) }
        onPlanAnchorUpdate?.invoke(plan)
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            LOG.info("PlanManager: approval timed out after ${timeoutMs / 1000}s — auto-approving")
            currentPlan?.approved = true
            AgentService.planModeActive.set(false)
            currentPlan?.let {
                onPlanAnchorUpdate?.invoke(it)
                onPlanApproved?.invoke(it)
            }
            PlanApprovalResult.Approved
        } finally {
            approvalDeferred = null
        }
    }

    @Volatile
    private var approvalDeferred: CompletableDeferred<PlanApprovalResult>? = null

    /** True when a plan has been submitted and is awaiting user approval/revision. */
    val isAwaitingApproval: Boolean
        get() = (approvalDeferred != null && approvalDeferred?.isCompleted == false)
            || (approvalFuture != null && approvalFuture?.isDone == false)

    fun approvePlan() {
        currentPlan?.approved = true
        // Auto-exit plan mode: tools are restored on next LLM call
        AgentService.planModeActive.set(false)
        // Resolve the async deferred if using submitPlanAndWait
        approvalDeferred?.complete(PlanApprovalResult.Approved)
        approvalDeferred = null
        // Complete the legacy CompletableFuture if using submitPlan
        approvalFuture?.complete(PlanApprovalResult.Approved)
        approvalFuture = null
        currentPlan?.let {
            onPlanAnchorUpdate?.invoke(it)
            onPlanApproved?.invoke(it)
        }
    }

    fun revisePlan(comments: Map<String, String>) {
        currentPlan?.steps?.forEach { step ->
            comments[step.id]?.let { step.userComment = it }
        }
        LOG.info("PlanManager: plan revision requested with ${comments.size} comments")
        sessionDir?.let { dir -> currentPlan?.let { PlanPersistence.save(it, dir) } }
        val result = PlanApprovalResult.Revised(comments)
        approvalDeferred?.complete(result)
        approvalDeferred = null
        approvalFuture?.complete(result)
    }

    /** New revision method: carries the actual line content + full markdown for LLM context. */
    fun revisePlanWithContext(revisions: List<PlanRevisionComment>, fullMarkdown: String?) {
        LOG.info("PlanManager: contextual revision requested with ${revisions.size} comments")
        sessionDir?.let { dir -> currentPlan?.let { PlanPersistence.save(it, dir) } }
        val result = PlanApprovalResult.RevisedWithContext(revisions, fullMarkdown)
        approvalDeferred?.complete(result)
        approvalDeferred = null
        approvalFuture?.complete(result)
    }

    /** Resolve pending plan approval with a free-form chat message.
     *  The agent decides whether this is a question, revision, or discussion. */
    fun resolveWithChatMessage(message: String) {
        LOG.info("PlanManager: chat message during plan approval — message length=${message.length}")
        val result = PlanApprovalResult.ChatMessage(message)
        approvalDeferred?.complete(result)
        approvalDeferred = null
        approvalFuture?.complete(result)
    }

    fun updateStepStatus(stepId: String, status: String) {
        currentPlan?.steps?.find { it.id == stepId }?.status = status
        onStepUpdated?.invoke(stepId, status)
        sessionDir?.let { dir -> currentPlan?.let { PlanPersistence.save(it, dir) } }
        currentPlan?.let { onPlanAnchorUpdate?.invoke(it) }
    }

    /**
     * Check if a step's dependencies are all completed.
     *
     * @param stepId The step to check
     * @return true if all dependencies are done (or step has no dependencies), false otherwise
     */
    fun areDependenciesMet(stepId: String): Boolean {
        val plan = currentPlan ?: return true
        val step = plan.steps.find { it.id == stepId } ?: return true
        if (step.dependsOn.isEmpty()) return true
        return step.dependsOn.all { depId ->
            val dep = plan.steps.find { it.id == depId }
            dep?.status == "done" || dep?.status == "skipped"
        }
    }

    /**
     * Detect deviation from the current plan.
     *
     * Checks whether the tool being called and the file being modified align with
     * the currently active (IN_PROGRESS) step. Returns a warning message if the
     * agent appears to be working on files not listed in the current step.
     *
     * @param toolName The tool being called
     * @param filePath The file being operated on (nullable for non-file tools)
     * @return Warning message if deviation detected, null if on track
     */
    fun checkDeviation(toolName: String, filePath: String?): String? {
        val plan = currentPlan ?: return null
        val activeStep = plan.steps.find { it.status == "running" || it.status == "in_progress" } ?: return null
        if (filePath != null && activeStep.files.isNotEmpty()) {
            if (!activeStep.files.any { filePath.endsWith(it) || filePath.contains(it) }) {
                return "Warning: editing '$filePath' but current step '${activeStep.title}' targets: ${activeStep.files.joinToString()}"
            }
        }
        return null
    }

    /**
     * Restore a plan from disk persistence (e.g., after session recovery).
     * Sets currentPlan and triggers the anchor update so the LLM sees it in context.
     */
    fun restorePlan(plan: AgentPlan) {
        currentPlan = plan
        LOG.info("PlanManager: restored plan with ${plan.steps.size} steps (approved=${plan.approved})")
        onPlanAnchorUpdate?.invoke(plan)
    }

    fun hasPlan(): Boolean = currentPlan != null
    fun isPlanApproved(): Boolean = currentPlan?.approved == true

    fun clear() {
        currentPlan = null
        approvalFuture = null
        approvalDeferred = null
    }
}
