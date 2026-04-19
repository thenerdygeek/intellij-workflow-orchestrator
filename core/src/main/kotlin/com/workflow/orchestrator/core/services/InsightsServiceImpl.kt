package com.workflow.orchestrator.core.services

import com.workflow.orchestrator.core.model.insights.SessionRecord
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

class InsightsServiceImpl(
    private val reader: SessionHistoryReader,
    private val baseDir: File,
) : InsightsService {

    override fun getSessions(since: Long): List<SessionRecord> =
        reader.loadSessions(baseDir)
            .filter { it.ts >= since }
            .sortedByDescending { it.ts }

    override fun getTodayStats(): InsightsStats {
        val midnight = LocalDate.now()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        return toInsightsStats(getSessions(midnight))
    }

    override fun getWeekStats(): InsightsStats {
        val sevenDaysAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        return toInsightsStats(getSessions(sevenDaysAgo))
    }

    private fun toInsightsStats(items: List<SessionRecord>): InsightsStats {
        val totalIn = items.sumOf { it.tokensIn }
        val totalOut = items.sumOf { it.tokensOut }
        val cost = items.sumOf { it.totalCost }
        val topTools = if (totalIn > 0) listOf("agent_calls" to items.size) else emptyList()
        return InsightsStats(
            sessionCount = items.size,
            totalTokensIn = totalIn,
            totalTokensOut = totalOut,
            totalCostUsd = cost,
            totalToolCalls = 0,
            failedToolCalls = 0,
            topTools = topTools,
            recentErrors = emptyList(),
        )
    }
}
