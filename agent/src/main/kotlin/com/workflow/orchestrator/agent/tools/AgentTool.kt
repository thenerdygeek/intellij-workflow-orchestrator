package com.workflow.orchestrator.agent.tools

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionDefinition
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.api.dto.ToolDefinition
import kotlinx.serialization.json.JsonObject

/** Worker type for tool access control. Kept for compatibility with existing tools. */
enum class WorkerType {
    ORCHESTRATOR, ANALYZER, CODER, REVIEWER, TOOLER
}

/** Simple token estimation: bytes / 4 (Codex CLI pattern, ~80% accurate). */
fun estimateTokens(text: String): Int = (text.toByteArray().size + 3) / 4

/**
 * Middle-truncate content keeping first 60% and last 40%.
 */
fun truncateOutput(content: String, maxChars: Int = 50_000): String {
    if (content.length <= maxChars) return content
    val headChars = (maxChars * 0.6).toInt()
    val tailChars = maxChars - headChars - 200
    val omitted = content.length - headChars - tailChars
    return content.take(headChars) +
        "\n\n[... $omitted characters omitted ...]\n\n" +
        content.takeLast(tailChars)
}

interface AgentTool {
    val name: String
    val description: String
    val parameters: FunctionParameters
    val allowedWorkers: Set<WorkerType>

    suspend fun execute(params: JsonObject, project: Project): ToolResult

    fun toToolDefinition(): ToolDefinition = ToolDefinition(
        function = FunctionDefinition(
            name = name,
            description = description,
            parameters = parameters
        )
    )

    companion object {
        /** Shared task_progress property injected into every tool schema at the API call site. */
        val TASK_PROGRESS_PROPERTY = ParameterProperty(
            type = "string",
            description = "A markdown checklist of plan progress (e.g. '- [x] Step 1\\n- [ ] Step 2'). " +
                "Include this when working through a plan to keep the progress widget updated."
        )

        /**
         * Inject task_progress into a tool definition's schema (Cline's FocusChain pattern).
         * Called at the API boundary so the LLM sees the parameter on every tool.
         */
        fun injectTaskProgress(def: ToolDefinition): ToolDefinition {
            val params = def.function.parameters
            if ("task_progress" in params.properties) return def
            return def.copy(
                function = def.function.copy(
                    parameters = params.copy(
                        properties = params.properties + ("task_progress" to TASK_PROGRESS_PROPERTY)
                    )
                )
            )
        }
    }
}

data class ToolResult(
    val content: String,
    val summary: String,
    val tokenEstimate: Int,
    val artifacts: List<String> = emptyList(),
    val isError: Boolean = false,
    val isCompletion: Boolean = false,
    val verifyCommand: String? = null,
    /** True when this result is a plan_mode_respond output (plan presentation). */
    val isPlanResponse: Boolean = false,
    /** True when the LLM needs more exploration before finalizing the plan. */
    val needsMoreExploration: Boolean = false,
    /** True when this result activates a skill via use_skill tool. */
    val isSkillActivation: Boolean = false,
    /** The skill name that was activated (set by UseSkillTool). */
    val activatedSkillName: String? = null,
    /** The full skill content that was loaded (set by UseSkillTool). */
    val activatedSkillContent: String? = null,
    /**
     * True when this result signals a session handoff via new_task tool.
     * Ported from Cline: the LLM creates a structured context summary and
     * hands off to a fresh session to escape context exhaustion.
     */
    val isSessionHandoff: Boolean = false,
    /**
     * The structured handoff context for the new session.
     * Contains: Current Work, Key Technical Concepts, Relevant Files and Code,
     * Problem Solving, Pending Tasks and Next Steps.
     */
    val handoffContext: String? = null,
    /**
     * Unified diff for file changes (edit_file, create_file).
     * Sent to the UI for before/after diff display.
     */
    val diff: String? = null,
    /** True when the LLM requests switching to plan mode via enable_plan_mode tool. */
    val enablePlanMode: Boolean = false
) {
    companion object {
        const val ERROR_TOKEN_ESTIMATE = 5
    }
}
