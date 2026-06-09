package com.workflow.orchestrator.agent.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(
    name = "AgentSettings",
    storages = [Storage("workflowAgent.xml")]
)
class AgentSettings : SimplePersistentStateComponent<AgentSettings.State>(State()) {

    class State : BaseState() {
        var agentEnabled by property(false)
        /**
         * Model name in Sourcegraph format: provider::apiVersion::modelId
         * Auto-resolved from API on first use. User can override in settings.
         */
        var sourcegraphChatModel by string(null)

        /** Global max-input-tokens override for ALL models. 0 = no override. */
        var maxTokenGlobalOverride by property(0)

        /** JSON-encoded {modelId: maxInputTokens} per-model overrides. "{}" = none. */
        var maxTokenPerModelOverrideJson by string("{}")
        /** Tracks whether the user has manually selected a model (prevents auto-upgrade). */
        var userManuallySelectedModel by property(false)
        /** Max output tokens per LLM response. Limit varies per model — no hardcoded cap. */
        var maxOutputTokens by property(64000)
        var showDebugLog by property(false)
        /** Idle threshold (seconds) for `run_command`. After this many seconds without
         *  output, the process is reported as [IDLE] so the LLM can interact with it. */
        var commandIdleThresholdSeconds by property(15)
        /** Idle threshold (seconds) for build commands (mvn / gradle / npm / docker build / etc.).
         *  Builds frequently pause for downloads or compilation, so the threshold is higher. */
        var buildCommandIdleThresholdSeconds by property(60)
        /** Maximum number of `send_stdin` calls allowed against a single running process.
         *  Prevents runaway agents from spamming stdin into hanging processes. */
        var maxStdinPerProcess by property(10)
        /** Timeout (minutes) for `ask_user_input` waiting for the user to respond.
         *  After this many minutes the prompt expires and the tool returns an error. */
        var askUserInputTimeoutMinutes by property(5)
        /** Hard upper bound (minutes) for `run_command`. Any per-call `timeout`
         *  parameter from the LLM is coerced into [1, this * 60] seconds. */
        var runCommandMaxTimeoutMinutes by property(10)
        var powershellEnabled by property(false)
        var cmdEnabled by property(false)
        /** Use Haiku to generate contextual humorous working indicator messages. */
        var smartWorkingIndicator by property(true)
        /**
         * Strategy when network errors exhaust retries:
         * - "none": fail immediately (default) — same-model brain recycle only
         * - "model_fallback": after same-model recycles are exhausted, escalate one tier
         *   down the fallback chain (Opus → Sonnet) via L2 tier escalation
         * - "context_compaction": compact context and retry with the same model
         */
        var networkErrorStrategy by string("none")
        /**
         * Tool execution mode:
         * - "accumulate": execute all tools after response completes (default, safe)
         * - "stream_interrupt": execute each tool as soon as it appears (Cline-style, responsive)
         */
        var toolExecutionMode by string("accumulate")
        /** Maximum number of concurrent background processes allowed per session.
         *  Enforced by BackgroundPool.register(). Configurable UI added in Task 1.7. */
        var concurrentBackgroundProcessesPerSession by property(5)
        /** Automatically wake the session when a background process completes. */
        var autoWakeOnBackgroundCompletion by property(true)
        /** Maximum number of auto-wake events allowed per session to prevent spam. */
        var autoWakeMaxPerSession by property(10)
        /** Minimum gap (milliseconds) between consecutive auto-wake events. */
        var autoWakeCooldownMs by property(5_000L)
        /** When true, skip the confirmation dialog before killing background processes
         *  on session transition. */
        var suppressBackgroundKillConfirmation by property(false)
        /** Coalesce window (ms) for monitor event delivery.  Events arriving within this
         *  window after the first are batched before waking the agent. */
        var monitorCoalesceWindowMs by property(2_000L)
        /** Maximum number of auto-wake events a single monitor handle may trigger per session. */
        var monitorWakeBudgetPerMonitor by property(3)
        /** Maximum number of monitor events (across all handles) allowed per minute before
         *  the flood-gate suppresses further wakes for that monitor. */
        var monitorFloodThresholdPerMin by property(20)

        /**
         * When true, the agent is asked to call the `feedback` tool immediately after
         * [AttemptCompletionTool]. The tool collects information about tools that
         * misbehaved, had confusing parameters, or produced unexpected results.
         * Feedback is appended to ~/.workflow-orchestrator/feedback.md.
         */
        var agentFeedbackEnabled by property(false)

        /**
         * When true, after [AttemptCompletionTool] the agent receives a one-shot nudge —
         * BEFORE the feedback nudge — asking whether anything it learned this session is
         * worth saving to file-based memory. Satisfied by re-issuing `attempt_completion`.
         * Off by default; the agent rarely updates memory unprompted.
         */
        var proactiveMemoryUpdatesEnabled by property(false)

        /**
         * When true, memory write operations (create/edit/delete under the agent memory dir)
         * bypass the approval gate entirely. When false (default), every memory write requires
         * per-invocation approval — see [com.workflow.orchestrator.agent.loop.AgentLoop].
         */
        var autoApproveMemoryOperations by property(false)

        fun maxTokenOverridesSnapshot(): com.workflow.orchestrator.agent.model.MaxTokenOverrides {
            val perModel = try {
                kotlinx.serialization.json.Json.decodeFromString<Map<String, Int>>(
                    maxTokenPerModelOverrideJson ?: "{}",
                ).filter { (_, v) -> v > 0 } // drop 0/negative (hand-edited XML) — mirrors the global takeIf
            } catch (_: Exception) {
                emptyMap()
            }
            return com.workflow.orchestrator.agent.model.MaxTokenOverrides(
                global = maxTokenGlobalOverride.takeIf { it > 0 },
                perModel = perModel,
            )
        }
    }

    companion object {
        fun getInstance(project: Project): AgentSettings {
            return project.service<AgentSettings>()
        }
    }
}
