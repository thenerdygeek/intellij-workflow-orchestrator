package com.workflow.orchestrator.core.http

import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object HttpMetricsRegistry {

    @Serializable
    data class ServiceStats(
        val requestCount: Int,
        val errorCount: Int,
        val errorRate: Double,
        val p50Ms: Long,
        val p95Ms: Long,
        val lastErrorAt: Long?,
        val lastStatusCode: Int?
    )

    private data class Record(
        val ts: Long,
        val durationMs: Long,
        val statusCode: Int,
        val isError: Boolean
    )

    private val store = ConcurrentHashMap<String, CopyOnWriteArrayList<Record>>()

    fun record(service: String, statusCode: Int, durationMs: Long, isError: Boolean) {
        store.getOrPut(service) { CopyOnWriteArrayList() }
            .add(Record(System.currentTimeMillis(), durationMs, statusCode, isError))
    }

    fun getStats(service: String): ServiceStats? {
        val raw = store[service] ?: return null
        return buildStats(raw)
    }

    fun getAllStats(): Map<String, ServiceStats> {
        return store.keys.associateWith { service ->
            buildStats(store[service] ?: return@associateWith null)
        }.filterValues { it != null }.mapValues { it.value!! }
    }

    private fun buildStats(raw: CopyOnWriteArrayList<Record>): ServiceStats? {
        val cutoff = System.currentTimeMillis() - 86_400_000L
        val window = raw.filter { it.ts > cutoff }
        if (window.isEmpty()) return null

        val sorted = window.map { it.durationMs }.sorted()
        val p50 = percentile(sorted, 50)
        val p95 = percentile(sorted, 95)

        val errors = window.filter { it.isError }
        val lastErrorAt = errors.maxOfOrNull { it.ts }
        val lastStatusCode = window.maxByOrNull { it.ts }?.statusCode

        return ServiceStats(
            requestCount = window.size,
            errorCount = errors.size,
            errorRate = errors.size.toDouble() / window.size,
            p50Ms = p50,
            p95Ms = p95,
            lastErrorAt = lastErrorAt,
            lastStatusCode = lastStatusCode
        )
    }

    private fun percentile(sorted: List<Long>, pct: Int): Long {
        if (sorted.isEmpty()) return 0L
        // Nearest-rank method
        val idx = ((pct / 100.0) * sorted.size).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }
}
