package com.workflow.orchestrator.agent.service

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.agent.runtime.SessionScorecard
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persists [SessionScorecard]s to JSON files for trend analysis.
 *
 * Storage layout:
 * ```
 * {projectBasePath}/.workflow/agent/metrics/
 *   scorecard-{sessionId}.json
 *   scorecard-{sessionId}.json
 *   ...
 * ```
 *
 * Each scorecard is a standalone JSON file, enabling simple append/query/cleanup.
 */
class MetricsStore(
    private val basePath: String
) {
    companion object {
        private val LOG = Logger.getInstance(MetricsStore::class.java)
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }
        private const val METRICS_DIR = ".workflow/agent/metrics"
        private const val DEFAULT_MAX_COUNT = 100
        private const val DEFAULT_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000 // 30 days
    }

    private val metricsDir: File
        get() = File(basePath, METRICS_DIR).also { it.mkdirs() }

    /**
     * Persist a scorecard to disk.
     */
    fun save(scorecard: SessionScorecard) {
        try {
            val file = File(metricsDir, "scorecard-${scorecard.sessionId}.json")
            // Atomic write: temp file then rename
            val tmp = File(metricsDir, "scorecard-${scorecard.sessionId}.json.tmp")
            tmp.writeText(json.encodeToString(scorecard))
            tmp.renameTo(file)
        } catch (e: Exception) {
            LOG.warn("MetricsStore: failed to save scorecard ${scorecard.sessionId}", e)
        }
    }

    /**
     * Load a single scorecard by session ID.
     *
     * @return The scorecard, or null if not found or corrupt
     */
    fun load(sessionId: String): SessionScorecard? {
        val file = File(metricsDir, "scorecard-$sessionId.json")
        if (!file.exists()) return null
        return try {
            json.decodeFromString<SessionScorecard>(file.readText())
        } catch (e: Exception) {
            LOG.warn("MetricsStore: failed to load scorecard $sessionId", e)
            null
        }
    }

    /**
     * Load all scorecards, sorted by timestamp descending (most recent first).
     */
    fun loadAll(): List<SessionScorecard> {
        val dir = metricsDir
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.name.startsWith("scorecard-") && f.name.endsWith(".json") }
            ?.mapNotNull { file ->
                try {
                    json.decodeFromString<SessionScorecard>(file.readText())
                } catch (e: Exception) {
                    LOG.debug("MetricsStore: skipping corrupt scorecard ${file.name}: ${e.message}")
                    null
                }
            }
            ?.sortedByDescending { it.timestamp }
            ?: emptyList()
    }

    /**
     * Load the most recent N scorecards.
     *
     * @param limit Maximum number of scorecards to return
     */
    fun loadRecent(limit: Int): List<SessionScorecard> {
        return loadAll().take(limit)
    }

    /**
     * Compute aggregate summary statistics across all stored scorecards.
     */
    fun getSummaryStats(): SummaryStats {
        val all = loadAll()
        if (all.isEmpty()) return SummaryStats.EMPTY

        val completedCount = all.count { it.completionStatus == "completed" }
        val totalCount = all.size

        return SummaryStats(
            totalSessions = totalCount,
            completedSessions = completedCount,
            failedSessions = all.count { it.completionStatus == "failed" },
            completionRate = if (totalCount > 0) completedCount.toDouble() / totalCount else 0.0,
            avgIterations = all.map { it.metrics.totalIterations }.average(),
            avgToolCalls = all.map { it.metrics.toolCallCount }.average(),
            avgDurationMs = all.map { it.metrics.durationMs }.average().toLong(),
            avgEstimatedCostUsd = all.map { it.metrics.estimatedCostUsd }.average(),
            totalEstimatedCostUsd = all.sumOf { it.metrics.estimatedCostUsd },
            avgErrorCount = all.map { it.metrics.errorCount }.average(),
            totalHallucinationFlags = all.sumOf { it.qualitySignals.hallucinationFlags },
            totalDoomLoopTriggers = all.sumOf { it.qualitySignals.doomLoopTriggers },
            totalCircuitBreakerTrips = all.sumOf { it.qualitySignals.circuitBreakerTrips }
        )
    }

    /**
     * Evict old scorecards based on age and count limits.
     *
     * @param maxAgeMs Maximum age in milliseconds (default 30 days)
     * @param maxCount Maximum number of scorecards to keep (default 100)
     * @return Number of scorecards removed
     */
    fun cleanup(
        maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
        maxCount: Int = DEFAULT_MAX_COUNT
    ): Int {
        val dir = metricsDir
        if (!dir.exists()) return 0

        val files = dir.listFiles { f -> f.name.startsWith("scorecard-") && f.name.endsWith(".json") }
            ?.toMutableList() ?: return 0

        var removed = 0
        val now = System.currentTimeMillis()

        // Phase 1: Remove files older than maxAge
        val expired = files.filter { (now - it.lastModified()) > maxAgeMs }
        expired.forEach { file ->
            if (file.delete()) {
                removed++
                files.remove(file)
            }
        }

        // Phase 2: If still over maxCount, remove oldest by last modified
        if (files.size > maxCount) {
            val sortedByAge = files.sortedBy { it.lastModified() }
            val toRemove = sortedByAge.take(files.size - maxCount)
            toRemove.forEach { file ->
                if (file.delete()) removed++
            }
        }

        if (removed > 0) {
            LOG.info("MetricsStore: cleaned up $removed scorecards")
        }
        return removed
    }
}

/**
 * Aggregate statistics across multiple session scorecards.
 */
data class SummaryStats(
    val totalSessions: Int,
    val completedSessions: Int,
    val failedSessions: Int,
    val completionRate: Double,
    val avgIterations: Double,
    val avgToolCalls: Double,
    val avgDurationMs: Long,
    val avgEstimatedCostUsd: Double,
    val totalEstimatedCostUsd: Double,
    val avgErrorCount: Double,
    val totalHallucinationFlags: Int,
    val totalDoomLoopTriggers: Int,
    val totalCircuitBreakerTrips: Int
) {
    companion object {
        val EMPTY = SummaryStats(
            totalSessions = 0,
            completedSessions = 0,
            failedSessions = 0,
            completionRate = 0.0,
            avgIterations = 0.0,
            avgToolCalls = 0.0,
            avgDurationMs = 0,
            avgEstimatedCostUsd = 0.0,
            totalEstimatedCostUsd = 0.0,
            avgErrorCount = 0.0,
            totalHallucinationFlags = 0,
            totalDoomLoopTriggers = 0,
            totalCircuitBreakerTrips = 0
        )
    }
}
