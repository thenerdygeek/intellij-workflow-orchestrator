package com.workflow.orchestrator.jira.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.jira.api.JiraApiClient
import com.workflow.orchestrator.jira.api.dto.TicketKeyInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * Session-scoped cache for validated Jira ticket keys.
 * Used for hyperlinking ticket keys in descriptions and comments.
 *
 * Valid keys map to [TicketKeyInfo], invalid keys map to null.
 * LRU eviction at 500 entries.
 */
@Service(Service.Level.PROJECT)
class TicketKeyCache {

    private val log = Logger.getInstance(TicketKeyCache::class.java)
    private val cache = ConcurrentHashMap<String, TicketKeyInfo?>()

    companion object {
        private const val MAX_SIZE = 500
        private val TICKET_KEY_REGEX = Regex("\\b([A-Z][A-Z0-9]+-\\d+)\\b")

        fun getInstance(project: Project): TicketKeyCache =
            project.getService(TicketKeyCache::class.java)
    }

    /** Extract all potential ticket keys from text. */
    fun extractKeys(text: String): Set<String> =
        TICKET_KEY_REGEX.findAll(text).map { it.groupValues[1] }.toSet()

    /** Get cached info for a key. Returns null if not cached or cached as invalid. */
    fun get(key: String): TicketKeyInfo? = cache[key]

    /** Check if a key has been validated (either valid or invalid). */
    fun isValidated(key: String): Boolean = cache.containsKey(key)

    /** Get all keys that need validation (not yet in cache). */
    fun getUnvalidated(keys: Set<String>): Set<String> =
        keys.filter { !cache.containsKey(it) }.toSet()

    /**
     * Validate keys against Jira API and cache results.
     * Valid keys get TicketKeyInfo, invalid keys get null.
     */
    suspend fun validateAndCache(client: JiraApiClient, keys: Set<String>) {
        if (keys.isEmpty()) return
        val uncached = getUnvalidated(keys)
        if (uncached.isEmpty()) return

        log.info("[Jira:KeyCache] Validating ${uncached.size} ticket keys")
        val result = client.validateTicketKeys(uncached.toList())
        when (result) {
            is ApiResult.Success -> {
                val validKeys = result.data
                for (key in uncached) {
                    cache[key] = validKeys[key] // null for invalid keys
                }
                evictIfNeeded()
                log.info("[Jira:KeyCache] Validated: ${validKeys.size} valid, ${uncached.size - validKeys.size} invalid")
            }
            is ApiResult.Error -> {
                log.warn("[Jira:KeyCache] Validation failed: ${result.message}")
            }
        }
    }

    /** Clear all cached entries. */
    fun clear() {
        cache.clear()
    }

    private fun evictIfNeeded() {
        if (cache.size > MAX_SIZE) {
            val toRemove = cache.size - MAX_SIZE
            val keys = cache.keys().toList().take(toRemove)
            keys.forEach { cache.remove(it) }
        }
    }
}
