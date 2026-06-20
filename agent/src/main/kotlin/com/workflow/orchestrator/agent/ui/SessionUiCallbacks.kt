package com.workflow.orchestrator.agent.ui

import com.workflow.orchestrator.agent.loop.ApprovalResult
import com.workflow.orchestrator.agent.loop.LoopResult
import com.workflow.orchestrator.agent.loop.SessionApprovalStore
import com.workflow.orchestrator.agent.loop.SessionCommandAllowlist
import com.workflow.orchestrator.agent.loop.StreamingEditCallback
import com.workflow.orchestrator.agent.loop.ToolCallProgress
import com.workflow.orchestrator.agent.session.UiMessage
import com.workflow.orchestrator.agent.tools.subagent.SubagentProgressUpdate

/**
 * Single source of truth for the full set of controller→loop UI callbacks that
 * [com.workflow.orchestrator.agent.AgentService.executeTask] /
 * [com.workflow.orchestrator.agent.AgentService.resumeSession] accept and that
 * [AgentController.executeTaskInternal] historically wired one-by-one at the `executeTask(...)`
 * call site.
 *
 * **Why this exists (the bug class it closes):**
 * The cross-IDE delegated entry points ([AgentController.runDelegatedNow] /
 * `runResumedDelegatedNow` → [com.workflow.orchestrator.agent.AgentService.startDelegatedSession] /
 * `resumeDelegatedSession`) used to re-list only a handful of these callbacks by hand. Every
 * callback added to the normal path after the delegated wrappers were written was silently dropped
 * on the delegated path — the subagent cards, token/stats chips, retry pill, compaction overlay,
 * model-switch chip, streaming-edit preview, handoff card, etc. went dark for delegated sessions.
 *
 * **The contract:** there is exactly ONE builder — [AgentController.buildSessionUiCallbacks] —
 * that fills this bundle from the controller's own handlers. `executeTaskInternal`,
 * `runDelegatedNow`, and `runResumedDelegatedNow` ALL call it, so a future callback added to the
 * builder flows to both the interactive and delegated paths automatically. The delegated
 * AgentService methods accept this bundle and forward every field, and
 * [com.workflow.orchestrator.agent.delegation.SessionUiCallbacksParityTest] fails if any field is
 * dropped on either delegated seam.
 *
 * **Not in the bundle (intentionally):** per-call data — `task`, `sessionId`, `attachments`,
 * `contextManager`, `uiMessageOverride`, `delegationMetadata`, `messageStateHandler` — which differ
 * per invocation and are passed explicitly by each caller. The bundle holds only the reusable
 * controller→loop callbacks.
 *
 * Field signatures mirror the corresponding [com.workflow.orchestrator.agent.AgentService.executeTask]
 * parameters exactly, so each can be forwarded verbatim without an adapter.
 */
data class SessionUiCallbacks(
    val onStreamChunk: (String) -> Unit,
    val onToolCall: (ToolCallProgress) -> Unit,
    val onComplete: (LoopResult) -> Unit,
    val onRetry: ((attempt: Int, maxAttempts: Int, reason: String, delayMs: Long) -> Unit)?,
    val onCompactionState: ((active: Boolean, phase: String) -> Unit)?,
    val onModelSwitch: ((fromModel: String, toModel: String, reason: String) -> Unit)?,
    val onPlanResponse: ((planText: String, needsMoreExploration: Boolean, append: Boolean) -> Unit)?,
    val onPlanPartialContent: ((partialContent: String) -> Unit)?,
    val onPlanModeToggled: ((Boolean) -> Unit)?,
    val onPlanDiscarded: (() -> Unit)?,
    val approvalGate: (suspend (toolName: String, args: String, riskLevel: String, allowSessionApproval: Boolean) -> ApprovalResult)?,
    val sessionApprovalStore: SessionApprovalStore,
    val sessionCommandAllowlist: SessionCommandAllowlist,
    val onSubagentProgress: ((agentId: String, update: SubagentProgressUpdate) -> Unit)?,
    val onTokenUpdate: ((inputTokens: Int, outputTokens: Int) -> Unit)?,
    val onSessionStats: ((modelId: String, tokensIn: Long, tokensOut: Long, costUsd: Double?) -> Unit)?,
    val onDebugLog: ((level: String, event: String, detail: String, meta: Map<String, Any?>?) -> Unit)?,
    val onSessionStarted: ((sessionId: String) -> Unit)?,
    val onSteeringDrained: ((drainedIds: List<String>) -> Unit)?,
    val onAwaitingUserInput: ((reason: String) -> Unit)?,
    val onUserInputReceived: ((task: String) -> UiMessage?)?,
    val streamingEditCallback: StreamingEditCallback?,
    val onHandoffProposed: ((context: String) -> Unit)?,
)
