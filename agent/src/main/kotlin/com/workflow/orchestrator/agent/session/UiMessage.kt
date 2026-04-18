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
    REASONING,
    TOOL,
    CHECKPOINT_CREATED,
    ERROR,
    PLAN_UPDATE,
    ARTIFACT_RESULT,
    SUBAGENT_STARTED,
    SUBAGENT_PROGRESS,
    SUBAGENT_COMPLETED,
    STEERING_RECEIVED,
    CONTEXT_COMPRESSED,
    MEMORY_SAVED,
    ROLLBACK_PERFORMED,
}

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
    val lastCheckpointHash: String? = null,
    val modelInfo: ModelInfo? = null,
    val artifactId: String? = null,
    val planData: PlanCardData? = null,
    val approvalData: ApprovalGateData? = null,
    val questionData: QuestionWizardData? = null,
    val subagentData: SubagentCardData? = null,
    val toolCallData: ToolCallData? = null,
    val completionData: CompletionData? = null,
)
