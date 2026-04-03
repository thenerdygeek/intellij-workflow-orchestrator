package com.workflow.orchestrator.agent.context.events

import java.time.Instant

/**
 * Sealed interface for all action events -- things the agent, user, or system *does*.
 */
sealed interface Action : Event

/**
 * Sealed interface for tool invocation actions. All tool calls carry a [toolCallId]
 * (matching the LLM's tool_call ID) and a [responseGroupId] (grouping parallel tool calls
 * from a single LLM response).
 */
sealed interface ToolAction : Action {
    val toolCallId: String
    val responseGroupId: String
}

// ---------------------------------------------------------------------------
// Non-tool actions
// ---------------------------------------------------------------------------

/**
 * A message from the user (or injected on behalf of the user).
 */
data class MessageAction(
    val content: String,
    val imageUrls: List<String>? = null,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.USER
) : Action

/**
 * A system-injected message (e.g., budget warnings, loop-guard nudges).
 */
data class SystemMessageAction(
    val content: String,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.SYSTEM
) : Action

/**
 * A steering message from the user, sent mid-execution while the agent was working.
 * Injected at iteration boundaries (between tool calls) to redirect the agent.
 * Distinct from [MessageAction] so condensers can identify steering context.
 */
data class UserSteeringAction(
    val content: String,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.USER
) : Action

/**
 * The agent's internal reasoning step (think tool).
 */
data class AgentThinkAction(
    val thought: String,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.AGENT
) : Action

/**
 * The agent signals task completion.
 */
data class AgentFinishAction(
    val finalThought: String,
    val outputs: Map<String, String> = emptyMap(),
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.AGENT
) : Action

/**
 * The agent delegates work to a subagent.
 */
data class DelegateAction(
    val agentType: String,
    val prompt: String,
    val thought: String? = null,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.AGENT
) : Action

/**
 * Records that context condensation occurred, marking which events were forgotten.
 *
 * Supports two modes (exactly one must be set):
 * - **Explicit IDs**: [forgottenEventIds] lists individual event IDs to forget
 * - **Range**: [forgottenEventsStartId]..[forgottenEventsEndId] defines a contiguous range
 *
 * [summary] and [summaryOffset] must be both set or both null.
 */
data class CondensationAction(
    val forgottenEventIds: List<Int>?,
    val forgottenEventsStartId: Int?,
    val forgottenEventsEndId: Int?,
    val summary: String?,
    val summaryOffset: Int?,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.SYSTEM
) : Action {

    /**
     * The list of event IDs that were forgotten, computed from whichever mode is active.
     * Returns an empty list if neither mode is set (caller should [validate] first).
     */
    val forgotten: List<Int>
        get() = when {
            forgottenEventIds != null -> forgottenEventIds
            forgottenEventsStartId != null && forgottenEventsEndId != null ->
                (forgottenEventsStartId..forgottenEventsEndId).toList()
            else -> emptyList()
        }

    /**
     * Validates that exactly one mode is set and that summary/summaryOffset are paired.
     *
     * @throws IllegalArgumentException if the invariants are violated
     */
    fun validate() {
        val hasExplicitIds = forgottenEventIds != null
        val hasStartId = forgottenEventsStartId != null
        val hasEndId = forgottenEventsEndId != null
        val hasRange = hasStartId || hasEndId

        // Exactly one of explicit IDs XOR range
        require(hasExplicitIds xor hasRange) {
            "Exactly one of (forgottenEventIds) XOR (forgottenEventsStartId/forgottenEventsEndId) must be set"
        }

        // If range mode, both start and end must be present
        if (hasRange) {
            require(hasStartId && hasEndId) {
                "Range mode requires both forgottenEventsStartId and forgottenEventsEndId"
            }
        }

        // summary and summaryOffset must be both set or both null
        require((summary == null) == (summaryOffset == null)) {
            "summary and summaryOffset must be both set or both null"
        }
    }
}

/**
 * Requests that condensation be performed (e.g., triggered by budget enforcer).
 */
data class CondensationRequestAction(
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.SYSTEM
) : Action

// ---------------------------------------------------------------------------
// Tool actions
// ---------------------------------------------------------------------------

data class FileReadAction(
    override val toolCallId: String,
    override val responseGroupId: String,
    val path: String,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.AGENT
) : ToolAction

data class FileEditAction(
    override val toolCallId: String,
    override val responseGroupId: String,
    val path: String,
    val oldStr: String?,
    val newStr: String?,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.AGENT
) : ToolAction

data class CommandRunAction(
    override val toolCallId: String,
    override val responseGroupId: String,
    val command: String,
    val cwd: String? = null,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.AGENT
) : ToolAction

data class SearchCodeAction(
    override val toolCallId: String,
    override val responseGroupId: String,
    val query: String,
    val path: String? = null,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.AGENT
) : ToolAction

data class DiagnosticsAction(
    override val toolCallId: String,
    override val responseGroupId: String,
    val path: String? = null,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.AGENT
) : ToolAction

/**
 * A tool call for a tool not covered by the specific action types above.
 */
data class GenericToolAction(
    override val toolCallId: String,
    override val responseGroupId: String,
    val toolName: String,
    val arguments: String,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.AGENT
) : ToolAction

/**
 * A tool call targeting a meta-tool (e.g., jira, bamboo, sonar, bitbucket, git, etc.)
 * where the actual operation is specified by [actionName].
 */
data class MetaToolAction(
    override val toolCallId: String,
    override val responseGroupId: String,
    val toolName: String,
    val actionName: String,
    val arguments: String,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.AGENT
) : ToolAction

// ---------------------------------------------------------------------------
// Compression-proof actions (included in NEVER_FORGET_TYPES)
// ---------------------------------------------------------------------------

/**
 * Records a verified fact discovered during the session.
 */
data class FactRecordedAction(
    val factType: String,
    val path: String?,
    val content: String,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.AGENT
) : Action

/**
 * Records a plan update (creation or modification).
 */
data class PlanUpdatedAction(
    val planJson: String,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.AGENT
) : Action

/**
 * Records that a skill was activated.
 */
data class SkillActivatedAction(
    val skillName: String,
    val content: String,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.AGENT
) : Action

/**
 * Records that a skill was deactivated.
 */
data class SkillDeactivatedAction(
    val skillName: String,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.AGENT
) : Action

/**
 * Records a guardrail rule learned from a failure pattern.
 */
data class GuardrailRecordedAction(
    val rule: String,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.SYSTEM
) : Action

/**
 * Records an @ mention that injected file/folder content into context.
 */
data class MentionAction(
    val paths: List<String>,
    val content: String,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.USER
) : Action
