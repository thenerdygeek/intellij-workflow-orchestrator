package com.workflow.orchestrator.agent.hooks

/**
 * All valid hook types that can be created and executed.
 *
 * Faithful port of Cline's VALID_HOOK_TYPES from hooks/utils.ts:
 * - TaskStart, TaskResume, TaskCancel correspond to task lifecycle
 * - PreToolUse, PostToolUse wrap every tool execution
 * - UserPromptSubmit intercepts user input before processing
 * - PreCompact fires before context compaction
 *
 * @see <a href="https://github.com/cline/cline/blob/main/src/core/hooks/utils.ts">Cline VALID_HOOK_TYPES</a>
 */
enum class HookType {
    TASK_START,
    USER_PROMPT_SUBMIT,
    TASK_RESUME,
    PRE_COMPACT,
    TASK_CANCEL,
    PRE_TOOL_USE,
    POST_TOOL_USE;

    /**
     * PascalCase name matching Cline's hook naming convention.
     * Cline uses "PreToolUse", "TaskStart", etc. as hook directory/file names.
     */
    val hookName: String
        get() = name.split("_").joinToString("") { part ->
            part.lowercase().replaceFirstChar { it.uppercase() }
        }

    companion object {
        /** Whether this hook type supports cancellation (non-zero exit = cancel). */
        fun isCancellable(type: HookType): Boolean = when (type) {
            TASK_START, USER_PROMPT_SUBMIT, TASK_RESUME, PRE_COMPACT, PRE_TOOL_USE -> true
            TASK_CANCEL, POST_TOOL_USE -> false
        }

        /**
         * Parse from Cline-style PascalCase name or our UPPER_SNAKE_CASE name.
         * Returns null if the name doesn't match any hook type.
         */
        fun fromString(name: String): HookType? {
            // Try direct enum name first (TASK_START)
            try {
                return valueOf(name)
            } catch (_: IllegalArgumentException) {
                // Continue to PascalCase matching
            }

            // Try PascalCase matching (TaskStart -> TASK_START)
            return entries.find { it.hookName.equals(name, ignoreCase = true) }
        }
    }
}

/**
 * Event data passed to hook scripts via stdin as JSON.
 *
 * Faithful port of Cline's HookInput protobuf structure:
 * - Common fields: hookName, timestamp, taskId
 * - Event-specific data in the [data] map
 * - [cancellable] determines whether non-zero exit code means "cancel operation"
 *
 * @see <a href="https://github.com/cline/cline/blob/main/proto/cline/hooks.proto">Cline hooks.proto</a>
 */
data class HookEvent(
    val type: HookType,
    val data: Map<String, Any?>,
    val cancellable: Boolean = HookType.isCancellable(type)
)

/**
 * Result from hook execution.
 *
 * Faithful port of Cline's HookOutput protobuf:
 * - Proceed: hook succeeded, operation continues
 * - Cancel: hook requested cancellation (only honored for cancellable hooks)
 *
 * Cline's HookOutput has three fields:
 * - cancel (boolean) -> maps to Cancel vs Proceed
 * - contextModification (string) -> maps to Cancel.contextModification / Proceed.contextModification
 * - errorMessage (string) -> maps to Cancel.reason
 *
 * @see <a href="https://github.com/cline/cline/blob/main/src/core/hooks/hook-factory.ts">Cline HookOutput</a>
 */
sealed class HookResult {
    /**
     * Hook completed successfully, operation should continue.
     * @param contextModification optional context to inject into the conversation
     */
    data class Proceed(val contextModification: String? = null) : HookResult()

    /**
     * Hook requested cancellation of the operation.
     * Only honored for cancellable hook types; ignored for observation-only hooks.
     * @param reason human-readable reason for cancellation (from hook's stderr or JSON errorMessage)
     * @param contextModification optional context even on cancellation
     */
    data class Cancel(
        val reason: String,
        val contextModification: String? = null
    ) : HookResult()
}
