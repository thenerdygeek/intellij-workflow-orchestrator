package com.workflow.orchestrator.core.services

import com.workflow.orchestrator.core.model.insights.SessionRecord

interface InsightsService {
    fun getSessions(since: Long = 0L): List<SessionRecord>
    fun getTodayStats(): InsightsStats
    fun getWeekStats(): InsightsStats

    /** Load the session history ONCE and derive both stat windows + the list from it. */
    fun getOverview(): InsightsOverview
}

data class InsightsStats(
    val sessionCount: Int,
    val totalTokensIn: Long,
    val totalTokensOut: Long,
    val totalCostUsd: Double,
    val totalToolCalls: Int,
    val failedToolCalls: Int,
    val topTools: List<Pair<String, Int>>,
    val recentErrors: List<String>,
)

/** One-load snapshot for the Insights panel: today + week stats and the full session list. */
data class InsightsOverview(
    val today: InsightsStats,
    val week: InsightsStats,
    val sessions: List<SessionRecord>,
)
