package com.workflow.orchestrator.agent.tools.apidocs

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ApiDocLoadError(val id: String, val error: String)

@Serializable
data class ApiDocPayload(
    val families: List<ApiFamily>,
    val loadErrors: List<ApiDocLoadError>,
)

object ApiDocPayloadBuilder {
    private val json = Json { prettyPrint = false; encodeDefaults = true }

    /**
     * Build the combined payload from all family resources. Errors are paired with
     * their family id by zipping with [ApiDocLoader.FAMILY_IDS] (NOT by re-filtering +
     * indexing, which would mis-key once any family is dropped).
     */
    fun build(): ApiDocPayload {
        val results = ApiDocLoader.loadAll()
        val families = results.mapNotNull { it.family }
        val errors = ApiDocLoader.FAMILY_IDS.zip(results)
            .filter { it.second.family == null }
            .map { ApiDocLoadError(it.first, it.second.error ?: "unknown error") }
        return ApiDocPayload(families, errors)
    }

    /** Serialize [build] to a JSON string for injection into the webview. */
    fun buildJson(): String = json.encodeToString(ApiDocPayload.serializer(), build())
}
