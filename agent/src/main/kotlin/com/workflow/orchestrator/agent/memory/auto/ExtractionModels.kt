package com.workflow.orchestrator.agent.memory.auto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Result of an LLM memory extraction call.
 * The extraction LLM returns this as structured JSON.
 */
@Serializable
data class ExtractionResult(
    @SerialName("core_memory_updates")
    val coreMemoryUpdates: List<CoreMemoryUpdate> = emptyList(),
    @SerialName("archival_inserts")
    val archivalInserts: List<ArchivalInsert> = emptyList()
)

@Serializable
data class CoreMemoryUpdate(
    /** Target block: "user", "project", or "patterns" */
    val block: String,
    val action: UpdateAction,
    /** New content to append, or replacement content */
    val content: String,
    /** For REPLACE action: the old content to find and replace */
    @SerialName("old_content")
    val oldContent: String? = null
)

@Serializable
enum class UpdateAction {
    @SerialName("append") APPEND,
    @SerialName("replace") REPLACE
}

@Serializable
data class ArchivalInsert(
    val content: String,
    val tags: List<String> = emptyList()
)
