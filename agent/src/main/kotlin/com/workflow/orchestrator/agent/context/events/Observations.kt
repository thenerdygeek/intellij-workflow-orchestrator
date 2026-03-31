package com.workflow.orchestrator.agent.context.events

import java.time.Instant

/**
 * Sealed interface for all observation events -- results/feedback from tool execution
 * or the environment. All observations carry a [content] string.
 */
sealed interface Observation : Event {
    val content: String
}

/**
 * The result of a tool invocation, matched to the originating [ToolAction] by [toolCallId].
 */
data class ToolResultObservation(
    val toolCallId: String,
    override val content: String,
    val isError: Boolean,
    val toolName: String,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.SYSTEM
) : Observation

/**
 * The result of a condensation operation (e.g., a summary of forgotten events).
 */
data class CondensationObservation(
    override val content: String,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.SYSTEM
) : Observation

/**
 * An error from the system or environment (not from a specific tool invocation).
 */
data class ErrorObservation(
    override val content: String,
    val errorId: String? = null,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.SYSTEM
) : Observation

/**
 * A success signal from the system or environment.
 */
data class SuccessObservation(
    override val content: String,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.SYSTEM
) : Observation
