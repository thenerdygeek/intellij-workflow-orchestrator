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
 * - **Caption** (audit P-7): the chart title, enriched with the chart TYPE and SOURCE SHEET.
 *   Titled → `"<title> — <type> chart (<sourceSheet>)"`; untitled → `"Chart: <type> (<sourceSheet>)"`;
 *   degrades to `"Chart"` only when neither type nor source can be determined. This distinguishes
 *   a doughnut from a bar chart and names where the data came from, instead of labelling every
 *   chart identically.
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
            val caption = buildCaption(chart)

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

    // ── Caption (title + type + source sheet) ──────────────────────────────────

    /**
     * Builds the chart caption (audit P-7): `Chart: <type> (<sourceSheet>)`, prefixed with
     * the chart's own title when it has one.
     *
     * Examples:
     * - titled doughnut sourced from Sheet2 → `"Sales — doughnut chart (Sheet2)"`
     * - untitled bar chart sourced from Data → `"Chart: bar (Data)"`
     * - type/source undetectable → `"Chart"` (the pre-P-7 fallback, never worse than before)
     *
     * Earlier output labelled every chart identically as `"Chart"`, losing the doughnut/bar
     * distinction and the originating sheet — so two different charts were indistinguishable.
     */
    private fun buildCaption(chart: XDDFChart): String {
        val title = extractTitle(chart)
        val type = chartType(chart)
        val source = sourceSheet(chart)

        // The descriptive tail: "<type> chart (<source>)" with each part optional.
        val typeAndSource = buildString {
            if (type != null) append(type) else append("chart")
            if (type != null) append(" chart")
            if (source != null) append(" ($source)")
        }.trim()

        return when {
            title != null && (type != null || source != null) -> "$title — $typeAndSource"
            title != null -> title
            type != null || source != null -> "Chart: ${typeAndSourceCompact(type, source)}"
            else -> "Chart"
        }
    }

    /** Compact "type (source)" tail for the untitled case, e.g. `"bar (Data)"` or `"doughnut"`. */
    private fun typeAndSourceCompact(type: String?, source: String?): String = buildString {
        append(type ?: "chart")
        if (source != null) append(" ($source)")
    }

    /**
     * Detects the chart's plot type (e.g. `"bar"`, `"doughnut"`, `"pie"`, `"line"`) by inspecting
     * the first populated chart-type list on the OOXML plot area (`CTPlotArea`). Returns the type
     * of the FIRST populated list — overlay/combo charts collapse to their primary type. Returns
     * `null` when the plot area cannot be read or no known type list is populated.
     */
    private fun chartType(chart: XDDFChart): String? {
        val plotArea = try { chart.ctChart?.plotArea } catch (_: Exception) { return null } ?: return null
        return try {
            when {
                plotArea.barChartList.isNotEmpty() -> "bar"
                plotArea.bar3DChartList.isNotEmpty() -> "bar"
                plotArea.lineChartList.isNotEmpty() -> "line"
                plotArea.line3DChartList.isNotEmpty() -> "line"
                plotArea.pieChartList.isNotEmpty() -> "pie"
                plotArea.pie3DChartList.isNotEmpty() -> "pie"
                plotArea.doughnutChartList.isNotEmpty() -> "doughnut"
                plotArea.areaChartList.isNotEmpty() -> "area"
                plotArea.area3DChartList.isNotEmpty() -> "area"
                plotArea.scatterChartList.isNotEmpty() -> "scatter"
                plotArea.radarChartList.isNotEmpty() -> "radar"
                plotArea.bubbleChartList.isNotEmpty() -> "bubble"
                plotArea.surfaceChartList.isNotEmpty() -> "surface"
                plotArea.surface3DChartList.isNotEmpty() -> "surface"
                plotArea.stockChartList.isNotEmpty() -> "stock"
                plotArea.ofPieChartList.isNotEmpty() -> "pie"
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Resolves the source SHEET name from the chart's series data references. Chart series carry
     * formula references like `Sheet2!$B$2:$B$5`; the sheet name is the part before the `!`. We
     * scan the raw chart XML for the first such reference and return the sheet name (stripped of
     * surrounding quotes used for names with spaces). Returns `null` when no reference is found.
     *
     * Reads from `chart.ctChart` XML text rather than walking every XDDF data-source type, so it
     * works uniformly across chart types without a type-specific accessor matrix.
     */
    private fun sourceSheet(chart: XDDFChart): String? {
        val xml = try { chart.ctChart?.toString() } catch (_: Exception) { return null } ?: return null
        // Match <c:f>SheetName!$A$1:$B$2</c:f> (or quoted 'My Sheet'!...). Capture the sheet part.
        val ref = SHEET_REF_REGEX.find(xml) ?: return null
        return ref.groupValues[1].trim().trim('\'').takeIf { it.isNotBlank() }
    }

    private val SHEET_REF_REGEX = Regex("""<c:f>\s*('?[^!<']+'?)!\$""")

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
