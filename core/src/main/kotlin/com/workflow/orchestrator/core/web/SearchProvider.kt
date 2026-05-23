package com.workflow.orchestrator.core.web

interface SearchProvider {
    val id: ProviderId
    suspend fun validate(): Result<Unit>
    suspend fun search(query: String, maxResults: Int): Result<List<RawHit>>

    enum class ProviderId { SEARXNG, BRAVE, CUSTOM_HTTP }
    data class RawHit(val title: String, val url: String, val snippet: String, val rank: Int)
}
