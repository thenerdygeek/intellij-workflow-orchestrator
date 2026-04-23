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
        /** Tracks whether the user has manually selected a model (prevents auto-upgrade). */
        var userManuallySelectedModel by property(false)
        var maxInputTokens by property(190000)
        /** Max output tokens per LLM response. Limit varies per model — no hardcoded cap. */
        var maxOutputTokens by property(64000)
        var approvalRequiredForEdits by property(true)
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
        var powershellEnabled by property(false)
        var cmdEnabled by property(false)
        /** Use Haiku to generate contextual humorous working indicator messages. */
        var smartWorkingIndicator by property(true)
        /** Enable auto-memory: retrieve relevant archival memories at session start
         *  and extract insights into core + archival memory at session end. */
        var autoMemoryEnabled by property(true)
        /**
         * Strategy when network errors exhaust retries:
         * - "none": fail immediately (default)
         * - "model_fallback": switch to cheaper model, escalate back when stable
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
        /** Byte threshold above which background process output is spilled to disk.
         *  Defaults to 1 MB. */
        var backgroundOutputSpillThresholdBytes by property(1_048_576L)
        /** Automatically wake the session when a background process completes. */
        var autoWakeOnBackgroundCompletion by property(true)
        /** Maximum number of auto-wake events allowed per session to prevent spam. */
        var autoWakeMaxPerSession by property(10)
        /** Minimum gap (milliseconds) between consecutive auto-wake events. */
        var autoWakeCooldownMs by property(5_000L)
        /** When true, skip the confirmation dialog before killing background processes
         *  on session transition. */
        var suppressBackgroundKillConfirmation by property(false)
    }

    companion object {
        fun getInstance(project: Project): AgentSettings {
            return project.service<AgentSettings>()
        }
    }
}
