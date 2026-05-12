package com.workflow.orchestrator.document.poi

import com.workflow.orchestrator.core.model.DocumentBlock
import org.apache.poi.xddf.usermodel.chart.XDDFChart
import org.apache.poi.xddf.usermodel.chart.XDDFChartData

/**
 * Converts an [XDDFChart] subclass (XLSX: [org.apache.poi.xssf.usermodel.XSSFChart],
 * PPTX: [org.apache.poi.xslf.usermodel.XSLFChart]) into a [DocumentBlock.Table].
 *
 * ## Output format
 *
 * - **Caption**: chart title from [XDDFChart.getTitle] → first-paragraph text,
 *   or `"Chart"` when the chart is untitled.
 * - **Headers**: `["Category", "<series 1 title>", "<series 2 title>", …]`. Series
 *   titles are read from the underlying CT series `<c:tx><c:v>` element via reflection
 *   on the protected `getSeriesText()` method; falls back to `"Series N"` on any error.
 * - **Rows**: one per category label, with numeric values (or empty string) for each
 *   series at that index.
 *
 * ## Scope
 *
 * All chart types whose series carry categorical X-axis labels and numeric Y-axis values
 * (bar, line, area, pie …) reduce to this table format adequately for LLM reasoning.
 * Scatter/bubble charts (two numeric axes) use their first axis as the "Category" column.
 *
 * ## Null return
 *
 * Returns `null` when:
 * - The chart has no `XDDFChartData` series groups (`getChartSeries()` is empty).
 * - The first series group has no series.
 * - The first series has no category data source.
 * - Any access throws an unexpected exception (full guard at the outer level).
 *
 * ## Thread safety
 *
 * Stateless singleton. The [XDDFChart] instances passed in are NOT thread-safe; callers
 * must use per-call POI workbook/slideshow instances (same constraint as other extractors).
 */
object ChartTableBuilder {

    /**
     * Converts [chart] into a [DocumentBlock.Table], or returns `null` when the chart
     * has no extractable series data.
     */
    fun toTable(chart: XDDFChart): DocumentBlock.Table? {
        return try {
            val caption = extractTitle(chart) ?: "Chart"

            val seriesGroups: List<XDDFChartData> = chart.chartSeries
            if (seriesGroups.isEmpty()) return null

            // Use the first series group; multi-group charts (rare overlay charts) are
            // not distinguished in this v1 — they all collapse to a single table anyway.
            val chartData: XDDFChartData = seriesGroups.first()
            val seriesCount = chartData.seriesCount
            if (seriesCount == 0) return null

            val firstSeries = chartData.getSeries(0) ?: return null
            val categoryDS = firstSeries.categoryData ?: return null
            val pointCount = categoryDS.pointCount

            if (pointCount == 0) return null

            val seriesTitles: List<String> = (0 until seriesCount).map { i ->
                try {
                    extractSeriesTitle(chartData.getSeries(i), i + 1)
                } catch (_: Exception) {
                    "Series ${i + 1}"
                }
            }

            val headers = listOf("Category") + seriesTitles

            val rows: List<List<String>> = (0 until pointCount).map { ptIdx ->
                val cat = try {
                    categoryDS.getPointAt(ptIdx)?.toString() ?: ""
                } catch (_: Exception) {
                    ""
                }
                val seriesValues = (0 until seriesCount).map { sIdx ->
                    try {
                        val values = chartData.getSeries(sIdx)?.valuesData
                        values?.getPointAt(ptIdx)?.toString() ?: ""
                    } catch (_: Exception) {
                        ""
                    }
                }
                listOf(cat) + seriesValues
            }

            DocumentBlock.Table(headers = headers, rows = rows, caption = caption)
        } catch (_: Exception) {
            null
        }
    }

    // ── Chart title ───────────────────────────────────────────────────────────

    /**
     * Reads the chart title from [XDDFChart.getTitle].
     *
     * For XLSX charts, [org.apache.poi.xssf.usermodel.XSSFChart] also exposes
     * `getTitleText()` returning an [org.apache.poi.xssf.usermodel.XSSFRichTextString];
     * we use it preferentially when available via type-specific casting so we capture
     * both formula-linked and inline titles.
     *
     * Falls back to the XDDF `getTitle().getBody().getParagraph(0).getText()` path
     * which works uniformly across XLSX and PPTX chart types.
     */
    private fun extractTitle(chart: XDDFChart): String? {
        // XLSX-specific: XSSFChart.getTitleText() is more reliable (handles formula-linked titles).
        if (chart is org.apache.poi.xssf.usermodel.XSSFChart) {
            try {
                val rts = chart.titleText
                val text = rts?.string?.trim()
                if (!text.isNullOrBlank()) return text
            } catch (_: Exception) { /* fall through to XDDF path */ }
        }

        // General XDDF path: works for both XLSX (when above returns null) and PPTX.
        return try {
            val title = chart.title ?: return null
            val body = title.body ?: return null
            val text = body.paragraphs
                .joinToString(" ") { it.getText()?.trim() ?: "" }
                .trim()
            text.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    // ── Series title ──────────────────────────────────────────────────────────

    /**
     * Reads the series title from an [XDDFChartData.Series].
     *
     * POI 5.4.1 does NOT expose a public `getTitle()` getter on the base
     * [XDDFChartData.Series] — only `setTitle()` exists. The title is stored in
     * the concrete CT series object's `<c:tx>` element. Two reading paths are attempted:
     *
     * 1. **Reflection on `getSeriesText()`** (protected method on [XDDFChartData.Series]).
     *    Returns a `CTSerTx` whose `getV()` holds an inline string title; `getStrRef()`
     *    holds a cell-range reference title. We use `getV()` — cell-ref resolution is
     *    intentionally skipped in v1 (the reference string itself is not useful to the LLM).
     *
     * 2. **Concrete-type pattern match** on known series subclasses. Each exposes a
     *    `getCTXxxSer().getTx()` method, so we can read the same `CTSerTx` without
     *    reflection. This covers the common chart types a user would encounter.
     *
     * Returns `"Series $ordinal"` when both paths fail or yield a blank string.
     */
    private fun extractSeriesTitle(series: XDDFChartData.Series?, ordinal: Int): String {
        if (series == null) return "Series $ordinal"

        // Path 1: reflection on protected getSeriesText() — broadest coverage.
        val reflectedTitle = try {
            val m = XDDFChartData.Series::class.java.getDeclaredMethod("getSeriesText")
            m.isAccessible = true
            val ctSerTx = m.invoke(series)
            extractCTSerTxString(ctSerTx)
        } catch (_: Exception) {
            null
        }
        if (!reflectedTitle.isNullOrBlank()) return reflectedTitle

        // Path 2: concrete-type pattern match. Avoids reflection as a secondary fallback.
        val concreteTitle = try { extractTitleFromConcreteSeries(series) } catch (_: Exception) { null }
        if (!concreteTitle.isNullOrBlank()) return concreteTitle

        return "Series $ordinal"
    }

    /**
     * Extracts the title string from a `CTSerTx` object (accessed generically as [Any]).
     * Calls `getV()` via reflection to avoid a compile-time dependency on the ooxml-lite
     * schema classes, which are not guaranteed to be in scope across all build environments.
     */
    private fun extractCTSerTxString(ctSerTx: Any?): String? {
        if (ctSerTx == null) return null
        return try {
            val getV = ctSerTx.javaClass.getMethod("getV")
            (getV.invoke(ctSerTx) as? String)?.trim()?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Reads the series `<c:tx><c:v>` text via concrete CT series types.
     * This is a compile-time fallback that avoids reflection for the most common cases.
     *
     * Note: POI uses Java getter naming (e.g. `getCTBarSer()`), which Kotlin exposes as
     * properties (`ctBarSer`). The CT series `tx` property maps to `getTx()` on CTXxxSer.
     */
    private fun extractTitleFromConcreteSeries(series: XDDFChartData.Series): String? {
        val ctSerTx: Any? = when (series) {
            is org.apache.poi.xddf.usermodel.chart.XDDFBarChartData.Series ->
                runCatching { series.getCTBarSer()?.getTx() }.getOrNull()
            is org.apache.poi.xddf.usermodel.chart.XDDFLineChartData.Series ->
                runCatching { series.getCTLineSer()?.getTx() }.getOrNull()
            is org.apache.poi.xddf.usermodel.chart.XDDFPieChartData.Series ->
                runCatching { series.getCTPieSer()?.getTx() }.getOrNull()
            is org.apache.poi.xddf.usermodel.chart.XDDFAreaChartData.Series ->
                runCatching { series.getCTAreaSer()?.getTx() }.getOrNull()
            else -> null
        }
        return extractCTSerTxString(ctSerTx)
    }
}
