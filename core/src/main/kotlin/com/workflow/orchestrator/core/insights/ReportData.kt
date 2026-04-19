package com.workflow.orchestrator.core.insights

import com.workflow.orchestrator.core.http.HttpMetricsRegistry
import kotlinx.serialization.Serializable

object ReportData {

    @Serializable
    data class Mechanical(
        val windowStartMs: Long,
        val windowEndMs: Long,
        val sessionCount: Int,
        val totalTokensIn: Long,
        val totalTokensOut: Long,
        val totalCostUsd: Double,
        val distinctDays: Int,
        val distinctModels: Int,
        val modelUsage: Map<String, Int>,          // normalizedModelId -> session count
        val modelCost: Map<String, Double>,         // normalizedModelId -> total USD
        val sessionDigests: List<SessionDigest>,    // compact per-session rows for LLM
        val serviceHttpStats: Map<String, HttpMetricsRegistry.ServiceStats>,
        val userTurnsByHour: List<Int>,             // 24-element hour-of-day tally (derived from session ts)
        val responseTimeBuckets: Map<String, Int>,  // "<5s","5-15s","15-60s","1-5m",">5m" -> count
        val avgSessionDurationMs: Long,
        val totalApiCalls: Int,
    )

    @Serializable
    data class SessionDigest(
        val id: String,
        val ts: Long,
        val taskTitle: String,   // truncated to 200 chars
        val modelId: String?,
        val tokensIn: Long,
        val tokensOut: Long,
        val costUsd: Double,
        val durationMs: Long,    // 0 if unknown (HistoryItem has no duration yet)
    )

    @Serializable
    data class PerSessionClassification(
        val sessionId: String,
        val taskType: String? = null,
        val outcome: String? = null,
        val whatHelped: String? = null,
        val frictionType: String? = null,
        val satisfaction: String? = null,
        val sessionType: String? = null,
        val projectArea: String? = null,
    )

    @Serializable
    data class AtAGlance(
        val working: String = "",
        val hindering: String = "",
        val quickWins: String = "",
        val ambitious: String = "",
    )

    @Serializable
    data class HowYouUse(
        val paragraphs: List<String> = emptyList(),
        val keyInsight: String = "",
    )

    @Serializable
    data class ProjectAreaDescription(
        val name: String = "",
        val description: String = "",
    )

    @Serializable
    data class BigWin(
        val title: String = "",
        val description: String = "",
    )

    @Serializable
    data class FrictionCategory(
        val title: String = "",
        val description: String = "",
        val examples: List<String> = emptyList(),
    )

    @Serializable
    data class FunEnding(
        val headline: String = "",
        val detail: String = "",
    )

    @Serializable
    data class AuthoringSnippet(
        val kind: String = "",   // "persona" or "skill"
        val path: String = "",
        val content: String = "",
        val why: String = "",
    )

    @Serializable
    data class FeatureCard(
        val title: String = "",
        val oneLiner: String = "",
        val why: String = "",
        val example: String = "",
    )

    @Serializable
    data class PatternCard(
        val title: String = "",
        val summary: String = "",
        val detail: String = "",
        val promptToCopy: String = "",
    )

    @Serializable
    data class HorizonCard(
        val title: String = "",
        val possible: String = "",
        val tip: String = "",
        val promptToCopy: String = "",
    )

    @Serializable
    data class Narrative(
        val atAGlance: AtAGlance = AtAGlance(),
        val howYouUse: HowYouUse = HowYouUse(),
        val projectAreaDescriptions: List<ProjectAreaDescription> = emptyList(),
        val bigWins: List<BigWin> = emptyList(),
        val frictionCategories: List<FrictionCategory> = emptyList(),
        val funEnding: FunEnding = FunEnding(),
        val authoringSnippets: List<AuthoringSnippet> = emptyList(),
        val featureCards: List<FeatureCard> = emptyList(),
        val patternCards: List<PatternCard> = emptyList(),
        val horizonCards: List<HorizonCard> = emptyList(),
        val perSessionClassifications: List<PerSessionClassification> = emptyList(),
    )
}
