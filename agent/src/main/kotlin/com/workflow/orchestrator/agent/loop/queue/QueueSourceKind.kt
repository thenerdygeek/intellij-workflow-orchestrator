package com.workflow.orchestrator.agent.loop.queue

import kotlinx.serialization.Serializable

/** The async source a [QueuedMessage] came from. Append a value to add a future source. */
@Serializable
enum class QueueSourceKind { USER, DELEGATION, BACKGROUND, MONITOR }
