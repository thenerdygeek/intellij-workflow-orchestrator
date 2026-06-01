package com.workflow.orchestrator.agent.session

import com.workflow.orchestrator.agent.tools.CompletionData
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class UiMessageType { ASK, SAY }

@Serializable
enum class UiAsk {
    RESUME_TASK,
    RESUME_COMPLETED_TASK,
    TOOL,
    COMMAND,
    FOLLOWUP,
    COMPLETION_RESULT,
    PLAN_APPROVE,
    PLAN_MODE_RESPOND,
    ARTIFACT_RENDER,
    QUESTION_WIZARD,
    APPROVAL_GATE,
    SUBAGENT_PERMISSION,
}

@Serializable
enum class UiSay {
    API_REQ_STARTED,
    API_REQ_FINISHED,
    TEXT,
    USER_MESSAGE,
    PLAN_APPROVED,
    REASONING,
    TOOL,
    STATUS,
    ERROR,
    PLAN_UPDATE,
    ARTIFACT_RESULT,
    SUBAGENT_STARTED,
    SUBAGENT_PROGRESS,
    SUBAGENT_COMPLETED,
    STEERING_RECEIVED,
    CONTEXT_COMPRESSED,
    MEMORY_SAVED,

    /**
     * Cross-IDE delegation conversation card (rendered on IDE-B's panel). Carries
     * [UiMessage.delegationCardData] describing one leg of the delegation narration:
     * the question routed to the delegator, the answer received back, or the terminal
     * result sent back. The incoming-task leg (a) reuses the USER_MESSAGE slot, so it
     * is NOT one of these. Persisted so reopening a delegated session from history
     * shows the full conversation, not just the task + the agent's work.
     */
    DELEGATION_CARD,
}

/** Kind of delegation conversation card (which leg of the narration). */
@Serializable
enum class DelegationCardKind { ASKED, ANSWERED, RESULT }

/**
 * Payload for a [UiSay.DELEGATION_CARD] message. The "other side" on IDE-B's panel
 * is always the delegator's repo ([delegatorRepo]) — never "IDE-A"/"IDE-B".
 *
 * - [DelegationCardKind.ASKED]   — (b) question routed to the delegator (text + options).
 * - [DelegationCardKind.ANSWERED] — (c) answer received from the delegator (answer text).
 * - [DelegationCardKind.RESULT]  — (d) terminal result sent back ([resultStatus] + [durationSeconds] + summary/reason).
 */
@Serializable
data class DelegationCardData(
    val kind: DelegationCardKind,
    val delegatorRepo: String,
    /** Correlation id: ASKED carries the question id; the matching ANSWERED flips it. */
    val questionId: String? = null,
    /** Question text (ASKED) or answer text (ANSWERED) or result summary (RESULT). */
    val text: String = "",
    /** Suggested options for an ASKED card; empty otherwise. */
    val options: List<String> = emptyList(),
    /** Set on ANSWERED cards (and used to flip the matching ASKED card to resolved). */
    val answered: Boolean = false,
    /** RESULT card: terminal status string (COMPLETED / FAILED / CANCELED / REJECTED). */
    val resultStatus: String? = null,
    /** RESULT card: wall-clock duration of the delegated session. */
    val durationSeconds: Long = 0,
    /** RESULT card: failure/cancel reason, if any. */
    val reason: String? = null,
)

@Serializable
enum class ApprovalStatus { PENDING, APPROVED, REJECTED }

@Serializable
enum class PlanStatus { DRAFTING, AWAITING_APPROVAL, APPROVED, EXECUTING }

@Serializable
enum class SubagentStatus { RUNNING, COMPLETED, FAILED, KILLED }

@Serializable
enum class WizardStatus { IN_PROGRESS, COMPLETED, SKIPPED }

@Serializable
enum class PlanStepStatus {
    @SerialName("pending") PENDING,
    @SerialName("in_progress") IN_PROGRESS,
    @SerialName("running") RUNNING,
    @SerialName("completed") COMPLETED,
    @SerialName("done") DONE,
    @SerialName("failed") FAILED,
    @SerialName("skipped") SKIPPED,
}

@Serializable
enum class ToolCallStatus { PENDING, RUNNING, COMPLETED, ERROR }

@Serializable
data class PlanStep(val title: String, val status: PlanStepStatus = PlanStepStatus.PENDING)

@Serializable
data class PlanCardData(
    val steps: List<PlanStep>,
    val status: PlanStatus,
    val comments: Map<Int, String> = emptyMap(),
)

@Serializable
data class PlanApprovalData(val planMarkdown: String)

@Serializable
data class ApprovalGateData(
    val toolName: String,
    val toolInput: String,
    val diffPreview: String? = null,
    val status: ApprovalStatus,
)

@Serializable
data class SubagentCardData(
    val agentId: String,
    val agentType: String,
    val description: String,
    val status: SubagentStatus,
    val iterations: Int = 0,
    val summary: String? = null,
)

@Serializable
data class QuestionItem(val text: String, val options: List<String> = emptyList())

@Serializable
data class QuestionWizardData(
    val questions: List<QuestionItem>,
    val currentIndex: Int = 0,
    val answers: Map<Int, String> = emptyMap(),
    val status: WizardStatus,
)

@Serializable
data class ToolCallData(
    val toolCallId: String,
    val toolName: String,
    val args: String = "",
    val status: ToolCallStatus = ToolCallStatus.COMPLETED,
    val result: String? = null,
    val output: String? = null,
    val durationMs: Long = 0,
    val diff: String? = null,
    val isError: Boolean = false,
)

@Serializable
data class ModelInfo(val modelId: String? = null, val provider: String? = null)

@Serializable
data class UiMessage(
    val ts: Long,
    val type: UiMessageType,
    val ask: UiAsk? = null,
    val say: UiSay? = null,
    val text: String? = null,
    val reasoning: String? = null,
    val images: List<String>? = null,
    val files: List<String>? = null,
    val partial: Boolean = false,
    val conversationHistoryIndex: Int? = null,
    val conversationHistoryDeletedRange: List<Int>? = null,
    val modelInfo: ModelInfo? = null,
    val artifactId: String? = null,
    val planData: PlanCardData? = null,
    val approvalData: ApprovalGateData? = null,
    val questionData: QuestionWizardData? = null,
    val subagentData: SubagentCardData? = null,
    val toolCallData: ToolCallData? = null,
    val delegationCardData: DelegationCardData? = null,
    val completionData: CompletionData? = null,
    val planApprovalData: PlanApprovalData? = null,
    /**
     * Multimodal-agent Phase 6 — true when the assistant message is the
     * second leg of the image+tools two-step workaround. The webview renders
     * a small `📷 image analyzed` strip with a tooltip explaining that the
     * image was analyzed in a separate request to enable tool use.
     */
    val analyzedImageBadge: Boolean = false,
    /**
     * Multimodal-agent — image attachments the user added to this turn. The
     * webview renders thumbnails inside the USER_MESSAGE bubble; the actual
     * bytes are served from disk via the `http://workflow-agent/attachments/<sha256>`
     * resource handler (read-only). Null/empty for text-only turns.
     */
    val attachments: List<ContentBlock.ImageRef>? = null,
    /** Wall-clock ms from first ThinkingDelta to ThinkingEnd. Null for history messages
     *  written before this field was added; those render "Thought for <1s". */
    val thinkingDurationMs: Long? = null,
)
