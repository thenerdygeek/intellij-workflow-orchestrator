package com.workflow.orchestrator.agent.context.events

import java.time.Instant
import kotlin.reflect.KClass

/**
 * Source of an event in the agent's conversation history.
 */
enum class EventSource {
    AGENT,
    USER,
    SYSTEM
}

/**
 * Base sealed interface for all events in the event-sourced context management system.
 *
 * Events are immutable records of what happened during an agent session.
 * The [id] and [timestamp] fields default to sentinel values and are assigned
 * by the EventStore when the event is appended.
 */
sealed interface Event {
    val id: Int
    val timestamp: Instant
    val source: EventSource
}

/**
 * Event types that must never be forgotten (removed) during context condensation.
 * These carry critical persistent state that the agent needs across the entire session.
 */
val NEVER_FORGET_TYPES: Set<KClass<out Event>> = setOf(
    FactRecordedAction::class,
    PlanUpdatedAction::class,
    SkillActivatedAction::class,
    GuardrailRecordedAction::class,
    MentionAction::class
)
