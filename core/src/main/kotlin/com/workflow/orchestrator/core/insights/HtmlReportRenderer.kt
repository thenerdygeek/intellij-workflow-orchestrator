package com.workflow.orchestrator.core.insights

import com.workflow.orchestrator.core.util.HtmlEscape
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object HtmlReportRenderer {

    fun render(
        mechanical: ReportData.Mechanical,
        narrative: ReportData.Narrative,
        pluginVersion: String = "unknown",
    ): String {
        val template = HtmlReportRenderer::class.java
            .getResourceAsStream("/reports/template.html")
            ?.bufferedReader()
            ?.readText()
            ?: error("template not found")

        return template
            .replace("{{SUBTITLE}}", buildSubtitle(mechanical))
            .replace("{{AT_A_GLANCE_WORKING}}", HtmlEscape.escapeHtml(narrative.atAGlance.working))
            .replace("{{AT_A_GLANCE_HINDERING}}", HtmlEscape.escapeHtml(narrative.atAGlance.hindering))
            .replace("{{AT_A_GLANCE_QUICK_WINS}}", HtmlEscape.escapeHtml(narrative.atAGlance.quickWins))
            .replace("{{AT_A_GLANCE_AMBITIOUS}}", HtmlEscape.escapeHtml(narrative.atAGlance.ambitious))
            .replace("{{STATS_ROW}}", buildStatsRow(mechanical))
            .replace("{{PROJECT_AREAS}}", buildProjectAreas(narrative.projectAreaDescriptions))
            .replace("{{CHART_TASK_TYPE}}", buildBarChart(
                tallyClassifications(narrative.perSessionClassifications) { it.taskType },
                "#2563eb"
            ))
            .replace("{{CHART_SESSION_TYPE}}", buildBarChart(
                tallyClassifications(narrative.perSessionClassifications) { it.sessionType },
                "#8b5cf6"
            ))
            .replace("{{HOW_YOU_USE_PARAGRAPHS}}", buildHowYouUseParagraphs(narrative.howYouUse.paragraphs))
            .replace("{{KEY_INSIGHT}}", HtmlEscape.escapeHtml(narrative.howYouUse.keyInsight))
            .replace("{{HOUR_HISTOGRAM_INIT}}", buildHourHistogramHtml(mechanical.userTurnsByHour))
            .replace("{{RAW_HOUR_COUNTS_JSON}}", buildRawHourCountsJson(mechanical.userTurnsByHour))
            .replace("{{BIG_WINS}}", buildBigWins(narrative.bigWins))
            .replace("{{CHART_WHAT_HELPED}}", buildBarChart(
                tallyClassifications(narrative.perSessionClassifications) { it.whatHelped },
                "#16a34a"
            ))
            .replace("{{CHART_OUTCOMES}}", buildBarChart(
                tallyClassifications(narrative.perSessionClassifications) { it.outcome },
                "#8b5cf6"
            ))
            .replace("{{FRICTION_CATEGORIES}}", buildFrictionCategories(narrative.frictionCategories))
            .replace("{{CHART_FRICTION_TYPES}}", buildBarChart(
                tallyClassifications(narrative.perSessionClassifications) { it.frictionType },
                "#dc2626"
            ))
            .replace("{{CHART_SATISFACTION}}", buildBarChart(
                tallyClassifications(narrative.perSessionClassifications) { it.satisfaction },
                "#eab308"
            ))
            .replace("{{AUTHORING_SNIPPETS}}", buildAuthoringSnippets(narrative.authoringSnippets))
            .replace("{{FEATURE_CARDS}}", buildFeatureCards(narrative.featureCards))
            .replace("{{PATTERN_CARDS}}", buildPatternCards(narrative.patternCards))
            .replace("{{HORIZON_CARDS}}", buildHorizonCards(narrative.horizonCards))
            .replace("{{FUN_ENDING_HEADLINE}}", buildFunEndingHeadline(narrative.funEnding, mechanical.sessionCount))
            .replace("{{FUN_ENDING_DETAIL}}", buildFunEndingDetail(narrative.funEnding))
            .replace("{{APPENDIX_TEXT}}", "Cost estimates use public list pricing (Anthropic/OpenAI). Not your actual enterprise bill.")
            .replace("{{GENERATED_AT}}", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
            .replace("{{WINDOW_DATES}}", "${formatDate(mechanical.windowStartMs)} to ${formatDate(mechanical.windowEndMs)}")
            .replace("{{PLUGIN_VERSION}}", HtmlEscape.escapeHtml(pluginVersion))
    }

    // -------------------------------------------------------------------------
    // Section builders
    // -------------------------------------------------------------------------

    private fun buildSubtitle(mechanical: ReportData.Mechanical): String =
        "${mechanical.sessionCount} sessions | ${formatDate(mechanical.windowStartMs)} to ${formatDate(mechanical.windowEndMs)}"

    private fun buildStatsRow(mechanical: ReportData.Mechanical): String = buildString {
        val costDisplay = if (mechanical.totalCostUsd > 0) "≈ ${"%.2f".format(mechanical.totalCostUsd)}" else "—"
        append(statTile(mechanical.sessionCount.toString(), "Sessions"))
        append(statTile(formatNumber(mechanical.totalTokensIn), "Tokens In"))
        append(statTile(formatNumber(mechanical.totalTokensOut), "Tokens Out"))
        append(statTile(costDisplay, "Est. Cost"))
        append(statTile(mechanical.distinctDays.toString(), "Days"))
    }

    private fun statTile(value: String, label: String): String =
        """<div class="stat"><div class="stat-value">${HtmlEscape.escapeHtml(value)}</div><div class="stat-label">${HtmlEscape.escapeHtml(label)}</div></div>"""

    private fun buildProjectAreas(areas: List<ReportData.ProjectAreaDescription>): String {
        if (areas.isEmpty()) return ""
        return buildString {
            areas.forEach { area ->
                append("""<div class="project-area">""")
                append("""<div class="area-header"><span class="area-name">${HtmlEscape.escapeHtml(area.name)}</span></div>""")
                append("""<div class="area-desc">${HtmlEscape.escapeHtml(area.description)}</div>""")
                append("""</div>""")
            }
        }
    }

    private fun buildHowYouUseParagraphs(paragraphs: List<String>): String =
        paragraphs.joinToString("") { p -> "<p>${HtmlEscape.escapeHtml(p)}</p>" }

    private fun buildHourHistogramHtml(userTurnsByHour: List<Int>): String {
        data class Period(val label: String, val range: List<Int>)
        val periods = listOf(
            Period("Morning (6-12)", (6..11).toList()),
            Period("Afternoon (12-18)", (12..17).toList()),
            Period("Evening (18-24)", (18..23).toList()),
            Period("Night (0-6)", (0..5).toList()),
        )
        val paddedCounts = if (userTurnsByHour.size >= 24) userTurnsByHour else userTurnsByHour + List(24 - userTurnsByHour.size) { 0 }
        val periodCounts = periods.map { p ->
            p.label to p.range.sumOf { h -> paddedCounts.getOrElse(h) { 0 } }
        }
        val max = periodCounts.maxOfOrNull { it.second }.takeIf { it != null && it > 0 } ?: 1
        return buildString {
            periodCounts.forEach { (label, count) ->
                val pct = count.toDouble() / max * 100
                append("""<div class="bar-row">""")
                append("""<div class="bar-label">${HtmlEscape.escapeHtml(label)}</div>""")
                append("""<div class="bar-track"><div class="bar-fill" style="width:${pct}%;background:#8b5cf6"></div></div>""")
                append("""<div class="bar-value">$count</div>""")
                append("""</div>""")
            }
        }
    }

    private fun buildRawHourCountsJson(userTurnsByHour: List<Int>): String = buildString {
        append("{")
        val paddedCounts = if (userTurnsByHour.size >= 24) userTurnsByHour else userTurnsByHour + List(24 - userTurnsByHour.size) { 0 }
        paddedCounts.take(24).forEachIndexed { i, c ->
            if (i > 0) append(",")
            append("\"$i\":$c")
        }
        append("}")
    }

    private fun buildBigWins(bigWins: List<ReportData.BigWin>): String {
        if (bigWins.isEmpty()) return """<p class="empty">No big wins identified yet.</p>"""
        return buildString {
            bigWins.forEach { win ->
                append("""<div class="big-win">""")
                append("""<div class="big-win-title">${HtmlEscape.escapeHtml(win.title)}</div>""")
                append("""<div class="big-win-desc">${HtmlEscape.escapeHtml(win.description)}</div>""")
                append("""</div>""")
            }
        }
    }

    private fun buildFrictionCategories(categories: List<ReportData.FrictionCategory>): String {
        if (categories.isEmpty()) return """<p class="empty">No friction patterns identified yet.</p>"""
        return buildString {
            categories.forEach { cat ->
                append("""<div class="friction-category">""")
                append("""<div class="friction-title">${HtmlEscape.escapeHtml(cat.title)}</div>""")
                append("""<div class="friction-desc">${HtmlEscape.escapeHtml(cat.description)}</div>""")
                append("""<ul class="friction-examples">""")
                cat.examples.forEach { ex ->
                    append("""<li>${HtmlEscape.escapeHtml(ex)}</li>""")
                }
                append("""</ul>""")
                append("""</div>""")
            }
        }
    }

    private fun buildAuthoringSnippets(snippets: List<ReportData.AuthoringSnippet>): String {
        if (snippets.isEmpty()) return """<p class="empty">No suggestions yet.</p>"""
        return buildString {
            snippets.forEachIndexed { idx, snippet ->
                append("""<div class="authoring-item">""")
                append("""<input type="checkbox" id="cmd-$idx" class="cmd-checkbox" checked data-text="${HtmlEscape.escapeHtml(snippet.content)}">""")
                append("""<label for="cmd-$idx">""")
                append("""<code class="cmd-code">${HtmlEscape.escapeHtml(snippet.path)}</code>""")
                append("""<button class="copy-btn" onclick="copyCmdItem($idx)">Copy</button>""")
                append("""</label>""")
                append("""<div class="cmd-why">${HtmlEscape.escapeHtml(snippet.why)}</div>""")
                append("""</div>""")
            }
        }
    }

    private fun buildFeatureCards(cards: List<ReportData.FeatureCard>): String = buildString {
        cards.forEach { card ->
            append("""<div class="feature-card">""")
            append("""<div class="feature-title">${HtmlEscape.escapeHtml(card.title)}</div>""")
            append("""<div class="feature-oneliner">${HtmlEscape.escapeHtml(card.oneLiner)}</div>""")
            append("""<div class="feature-why">${HtmlEscape.escapeHtml(card.why)}</div>""")
            append("""<div class="feature-code"><code>${HtmlEscape.escapeHtml(card.example)}</code><button class="copy-btn" onclick="copyText(this)">Copy</button></div>""")
            append("""</div>""")
        }
    }

    private fun buildPatternCards(cards: List<ReportData.PatternCard>): String = buildString {
        cards.forEach { card ->
            append("""<div class="pattern-card">""")
            append("""<div class="pattern-title">${HtmlEscape.escapeHtml(card.title)}</div>""")
            append("""<div class="pattern-summary">${HtmlEscape.escapeHtml(card.summary)}</div>""")
            append("""<div class="pattern-detail">${HtmlEscape.escapeHtml(card.detail)}</div>""")
            append("""<div class="copyable-prompt-section">""")
            append("""<div class="prompt-label">Paste into the agent chat:</div>""")
            append("""<div class="copyable-prompt-row">""")
            append("""<code class="copyable-prompt">${HtmlEscape.escapeHtml(card.promptToCopy)}</code>""")
            append("""<button class="copy-btn" onclick="copyText(this)">Copy</button>""")
            append("""</div>""")
            append("""</div>""")
            append("""</div>""")
        }
    }

    private fun buildHorizonCards(cards: List<ReportData.HorizonCard>): String = buildString {
        cards.forEach { card ->
            append("""<div class="horizon-card">""")
            append("""<div class="horizon-title">${HtmlEscape.escapeHtml(card.title)}</div>""")
            append("""<div class="horizon-possible">${HtmlEscape.escapeHtml(card.possible)}</div>""")
            append("""<div class="horizon-tip">${HtmlEscape.escapeHtml(card.tip)}</div>""")
            append("""<div class="copyable-prompt-section">""")
            append("""<div class="prompt-label">Paste into the agent chat:</div>""")
            append("""<div class="copyable-prompt-row">""")
            append("""<code class="copyable-prompt">${HtmlEscape.escapeHtml(card.promptToCopy)}</code>""")
            append("""<button class="copy-btn" onclick="copyText(this)">Copy</button>""")
            append("""</div>""")
            append("""</div>""")
            append("""</div>""")
        }
    }

    private fun buildFunEndingHeadline(funEnding: ReportData.FunEnding, sessionCount: Int): String =
        if (funEnding.headline.isNotEmpty()) HtmlEscape.escapeHtml(funEnding.headline)
        else "You ran $sessionCount sessions this period."

    private fun buildFunEndingDetail(funEnding: ReportData.FunEnding): String =
        if (funEnding.detail.isNotEmpty()) HtmlEscape.escapeHtml(funEnding.detail)
        else "Keep building."

    // -------------------------------------------------------------------------
    // Chart helpers
    // -------------------------------------------------------------------------

    private fun buildBarChart(items: List<Pair<String, Int>>, color: String): String {
        if (items.isEmpty()) return """<p class="empty">No data yet</p>"""
        val max = items.maxOf { it.second }.coerceAtLeast(1)
        return buildString {
            items.take(8).forEach { (label, count) ->
                val pct = count.toDouble() / max * 100
                append("""<div class="bar-row">""")
                append("""<div class="bar-label">${HtmlEscape.escapeHtml(label)}</div>""")
                append("""<div class="bar-track"><div class="bar-fill" style="width:${pct}%;background:${color}"></div></div>""")
                append("""<div class="bar-value">${count}</div>""")
                append("""</div>""")
            }
        }
    }

    private fun tallyClassifications(
        classifications: List<ReportData.PerSessionClassification>,
        selector: (ReportData.PerSessionClassification) -> String?,
    ): List<Pair<String, Int>> =
        classifications.mapNotNull(selector)
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key to it.value }

    // -------------------------------------------------------------------------
    // Formatting helpers
    // -------------------------------------------------------------------------

    private fun formatDate(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    private fun formatNumber(n: Long): String = "%,d".format(n)
}
