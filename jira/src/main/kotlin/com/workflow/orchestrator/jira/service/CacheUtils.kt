package com.workflow.orchestrator.jira.service

/**
 * Generic TTL cache entry shared across service implementations in the :jira module.
 *
 * Usage: `val cache = ConcurrentHashMap<K, CacheEntry<V>>()`
 *        `if (entry == null || entry.expiresAt < clock()) { /* reload */ }`
 */
internal data class CacheEntry<T>(val value: T, val expiresAt: Long)
