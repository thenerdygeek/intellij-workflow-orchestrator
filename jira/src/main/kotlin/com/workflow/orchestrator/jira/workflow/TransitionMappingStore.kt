package com.workflow.orchestrator.jira.workflow

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class TransitionMapping(
    val intent: String,
    val transitionName: String,
    val projectKey: String,
    val issueTypeId: String? = null,
    val source: String
)

class TransitionMappingStore {
    private val log = Logger.getInstance(TransitionMappingStore::class.java)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val mappings = mutableListOf<TransitionMapping>()

    fun getMapping(intent: String, projectKey: String, issueTypeId: String? = null): TransitionMapping? {
        if (issueTypeId != null) {
            val specific = mappings.find {
                it.intent == intent && it.projectKey == projectKey && it.issueTypeId == issueTypeId
            }
            if (specific != null) return specific
        }
        return mappings.find {
            it.intent == intent && it.projectKey == projectKey && it.issueTypeId == null
        }
    }

    fun saveMapping(mapping: TransitionMapping) {
        mappings.removeAll {
            it.intent == mapping.intent &&
            it.projectKey == mapping.projectKey &&
            it.issueTypeId == mapping.issueTypeId
        }
        mappings.add(mapping)
    }

    fun clearExplicitGlobalMapping(intent: String) {
        mappings.removeAll { it.intent == intent && it.projectKey == "" && it.source == "explicit" }
    }

    fun toJson(): String = json.encodeToString(mappings.toList())

    fun loadFromJson(jsonString: String) {
        if (jsonString.isBlank()) return
        try {
            val loaded = json.decodeFromString<List<TransitionMapping>>(jsonString)
            mappings.clear()
            mappings.addAll(loaded)
        } catch (_: Exception) {
            // Corrupted JSON — start fresh
        }
    }
}
