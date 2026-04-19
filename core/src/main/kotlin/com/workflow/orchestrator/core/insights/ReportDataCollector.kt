package com.workflow.orchestrator.core.insights

import com.workflow.orchestrator.core.http.HttpMetricsRegistry
import com.workflow.orchestrator.core.model.ModelIdNormalizer
import com.workflow.orchestrator.core.model.insights.SessionRecord
import com.workflow.orchestrator.core.services.SessionHistoryReader
import java.io.File
import java.time.Instant
import java.time.ZoneId

class ReportDataCollector(
    private val reader: SessionHistoryReader,
    private val baseDir: File,
) {
    fun collect(windowStartMs: Long, windowEndMs: Long): ReportData.Mechanical {
        val all = reader.loadSessions(baseDir)
        val sessions = all.filter { it.ts in windowStartMs..windowEndMs }
            .sortedByDescending { it.ts }

        val totalTokensIn = sessions.sumOf { it.tokensIn }
        val totalTokensOut = sessions.sumOf { it.tokensOut }
        val totalCostUsd = sessions.sumOf { it.totalCost }

        // Distinct calendar days
        val distinctDays = sessions.map { toLocalDate(it.ts) }.toSet().size

        // Model usage + cost
        val modelUsage = mutableMapOf<String, Int>()
        val modelCost = mutableMapOf<String, Double>()
        sessions.forEach { s ->
            val modelKey = ModelIdNormalizer.normalize(s.modelId ?: "unknown")
            modelUsage[modelKey] = (modelUsage[modelKey] ?: 0) + 1
            modelCost[modelKey] = (modelCost[modelKey] ?: 0.0) + s.totalCost
        }

        // Hour-of-day tally from session start timestamps
        val hourBuckets = IntArray(24)
        sessions.forEach { s ->
            val hour = Instant.ofEpochMilli(s.ts).atZone(ZoneId.systemDefault()).hour
            hourBuckets[hour]++
        }

        // Response time buckets: use session count as a proxy per bucket
        // (real per-turn data from SessionMetrics.responseTimesMs is Phase 4b)
        val responseTimeBuckets = mapOf(
            "<5s" to 0, "5-15s" to 0, "15-60s" to 0, "1-5m" to 0, ">5m" to 0
        )

        // Compact session digests for LLM input
        val digests = sessions.take(100).map { s ->
            ReportData.SessionDigest(
                id = s.id,
                ts = s.ts,
                taskTitle = s.task.take(200),
                modelId = s.modelId,
                tokensIn = s.tokensIn,
                tokensOut = s.tokensOut,
                costUsd = s.totalCost,
                durationMs = 0L,
            )
        }

        // HTTP service stats from in-memory registry
        val serviceStats = HttpMetricsRegistry.getAllStats()

        return ReportData.Mechanical(
            windowStartMs = windowStartMs,
            windowEndMs = windowEndMs,
            sessionCount = sessions.size,
            totalTokensIn = totalTokensIn,
            totalTokensOut = totalTokensOut,
            totalCostUsd = totalCostUsd,
            distinctDays = distinctDays,
            distinctModels = modelUsage.size,
            modelUsage = modelUsage,
            modelCost = modelCost,
            sessionDigests = digests,
            serviceHttpStats = serviceStats,
            userTurnsByHour = hourBuckets.toList(),
            responseTimeBuckets = responseTimeBuckets,
            avgSessionDurationMs = 0L,
            totalApiCalls = sessions.size,  // one API session per session, Phase 4b refines
        )
    }

    private fun toLocalDate(epochMs: Long): String {
        return Instant.ofEpochMilli(epochMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toString()
    }
}
