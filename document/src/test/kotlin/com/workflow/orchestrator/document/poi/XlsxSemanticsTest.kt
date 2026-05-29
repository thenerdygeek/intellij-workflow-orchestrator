package com.workflow.orchestrator.document.poi

import com.workflow.orchestrator.core.model.DocumentBlock
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.util.AreaReference
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.ss.util.CellReference
import org.apache.poi.xddf.usermodel.chart.AxisPosition
import org.apache.poi.xddf.usermodel.chart.ChartTypes
import org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * XLSX semantic-fidelity tests for audit finding G-8 (P-1, P-5, P-6, P-7).
 *
 * - **P-1**: formula text must be emitted alongside the cached value (`=SUM(...) (237)`),
 *   not just the bare cached number.
 * - **P-5**: a headerless sheet must NOT promote its first data row to a markdown table
 *   header; positional `Col A | Col B` headers are used and row 1 stays as data.
 * - **P-6**: a defined Excel table (`XSSFTable` / ListObject) must surface with its declared
 *   column headers and A1 range.
 * - **P-7**: charts must be labelled with their type + source sheet (`Chart: doughnut (Sheet2)`),
 *   not a generic `Chart`.
 */
class XlsxSemanticsTest {

    private val extractor = XlsxTableExtractor()

    // ── P-1: formula text emitted with cached value ────────────────────────────

    @Test
    fun `formula cell emits the formula text alongside the cached value`() {
        // Mirror book1 Sheet1 B19: =SUM(Sheet2!D2,Sheet2!D11) cached 237.
        val bytes = buildXlsx { wb ->
            val sheet = wb.createSheet("Sheet1")
            val h = sheet.createRow(0)
            h.createCell(0).setCellValue("Label")
            h.createCell(1).setCellValue("Amount")
            val r = sheet.createRow(1)
            r.createCell(0).setCellValue("Total:")
            val f = r.createCell(1)
            f.cellFormula = "SUM(Sheet2!D2,Sheet2!D11)"
            f.setCellValue(237.0) // author-cached <v>
        }

        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val flat = blocks.filterIsInstance<DocumentBlock.Table>().flatMap { it.rows.flatten() }

        val formulaCell = flat.firstOrNull { it.contains("SUM(Sheet2!D2,Sheet2!D11)") }
        assertNotNull(formulaCell, "Formula text must be present; got cells: $flat")
        assertTrue(
            formulaCell!!.contains("237"),
            "Cached value must accompany the formula text; got: '$formulaCell'",
        )
        assertEquals(
            "=SUM(Sheet2!D2,Sheet2!D11) (237)", formulaCell,
            "Expected the '=<formula> (<value>)' rendering",
        )
    }

    @Test
    fun `formula cell with no cached value still emits the formula text`() {
        val bytes = buildXlsx { wb ->
            val sheet = wb.createSheet("Sheet1")
            sheet.createRow(0).createCell(0).setCellValue("H")
            // setCellFormula with no setCellValue → no <v>; POI evaluates 1+2 = 3.
            sheet.createRow(1).createCell(0).cellFormula = "1+2"
        }
        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val flat = blocks.filterIsInstance<DocumentBlock.Table>().flatMap { it.rows.flatten() }
        val cell = flat.first()
        assertTrue(cell.contains("=1+2"), "Formula text must be present even without a cached value; got '$cell'")
        assertTrue(cell.contains("3"), "Evaluated value should accompany the formula; got '$cell'")
    }

    // ── P-5: no false header promotion on a headerless sheet ───────────────────

    @Test
    fun `headerless sheet does not promote row 1 to a header — uses positional headers`() {
        // calcchain-like: a single data row of bare numbers, no header evidence.
        val bytes = buildXlsx { wb ->
            val sheet = wb.createSheet("Sheet1")
            val r = sheet.createRow(0)
            r.createCell(0).setCellValue(0.0)
            r.createCell(1).setCellValue(1.0)
        }
        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val table = blocks.filterIsInstance<DocumentBlock.Table>().first()

        // Headers must be positional, NOT the data values 0 / 1.
        assertEquals(
            listOf("Col A", "Col B"), table.headers,
            "Headerless sheet must use positional column headers, not promote data values",
        )
        // The original first row (0,1) must survive as DATA, not be consumed as the header.
        assertTrue(
            table.rows.any { it == listOf("0", "1") },
            "Row 1 data (0,1) must remain a data row; rows=${table.rows}",
        )
    }

    @Test
    fun `sheet with a styled bold header row keeps that row as the header`() {
        val bytes = buildXlsx { wb ->
            val bold = wb.createFont().apply { bold = true }
            val headerStyle = wb.createCellStyle().apply { setFont(bold) }
            val sheet = wb.createSheet("Sheet1")
            val h = sheet.createRow(0)
            h.createCell(0).apply { setCellValue("Name"); cellStyle = headerStyle }
            h.createCell(1).apply { setCellValue("Qty"); cellStyle = headerStyle }
            val d = sheet.createRow(1)
            d.createCell(0).setCellValue("widget")
            d.createCell(1).setCellValue(5.0)
        }
        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val table = blocks.filterIsInstance<DocumentBlock.Table>().first()
        assertEquals(listOf("Name", "Qty"), table.headers, "Bold-styled row 1 is a real header")
        assertTrue(table.rows.any { it == listOf("widget", "5") }, "rows=${table.rows}")
    }

    @Test
    fun `type-break heuristic treats an all-string row 1 over numeric data as a header`() {
        val bytes = buildXlsx { wb ->
            val sheet = wb.createSheet("Sheet1")
            val h = sheet.createRow(0)
            h.createCell(0).setCellValue("Month")
            h.createCell(1).setCellValue("Sales")
            val d1 = sheet.createRow(1)
            d1.createCell(0).setCellValue("Jan")
            d1.createCell(1).setCellValue(100.0)
            val d2 = sheet.createRow(2)
            d2.createCell(0).setCellValue("Feb")
            d2.createCell(1).setCellValue(150.0)
        }
        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val table = blocks.filterIsInstance<DocumentBlock.Table>().first()
        // Column B is string in row 1 ("Sales") but numeric in data rows → header evidence.
        assertEquals(listOf("Month", "Sales"), table.headers, "Type break ⇒ row 1 is a header")
    }

    // ── P-6: defined Excel table (ListObject) surfaced with headers + range ────

    @Test
    fun `defined table is surfaced with its declared headers and A1 range`() {
        // Mirror book1 Table1 C21:D26 with headers Column1 / Column2.
        val bytes = buildXlsx { wb ->
            val sheet = wb.createSheet("Sheet1")
            // Put the table header cells where the table ref points (C21:D21 = row20, cols 2..3).
            val hr = sheet.createRow(20)
            hr.createCell(2).setCellValue("Column1")
            hr.createCell(3).setCellValue("Column2")
            // Some body rows.
            for (i in 1..2) {
                val br = sheet.createRow(20 + i)
                br.createCell(2).setCellValue("v$i")
                br.createCell(3).setCellValue("w$i")
            }
            val table = sheet.createTable(AreaReference("C21:D23", wb.spreadsheetVersion))
            table.name = "Table1"
            table.displayName = "Table1"
            // Name the columns to match book1.
            table.ctTable.tableColumns.getTableColumnArray(0).name = "Column1"
            table.ctTable.tableColumns.getTableColumnArray(1).name = "Column2"
        }
        val blocks = extractor.extract(ByteArrayInputStream(bytes))

        // The defined-table block is the CAPTIONED one carrying exactly the declared headers.
        val tableBlock = blocks.filterIsInstance<DocumentBlock.Table>()
            .firstOrNull { it.caption?.contains("Table1") == true }
        assertNotNull(
            tableBlock,
            "Expected a captioned defined-table block for Table1; tables=${blocks.filterIsInstance<DocumentBlock.Table>().map { it.headers to it.caption }}",
        )
        assertEquals(
            listOf("Column1", "Column2"), tableBlock!!.headers,
            "Defined-table block must expose the declared column headers",
        )
        assertTrue(
            tableBlock.caption!!.contains("C21:D23"),
            "Defined-table caption should name the A1 range; caption='${tableBlock.caption}'",
        )
    }

    // ── P-7: chart labelled with type + source sheet ───────────────────────────

    @Test
    fun `chart is labelled with its type and source sheet`() {
        val bytes = buildXlsx { wb ->
            // Data lives on a sheet named "Data"; the chart lives on "Sheet1".
            val data = wb.createSheet("Data")
            val hr = data.createRow(0)
            hr.createCell(0).setCellValue("Cat")
            hr.createCell(1).setCellValue("Val")
            val d1 = data.createRow(1); d1.createCell(0).setCellValue("A"); d1.createCell(1).setCellValue(10.0)
            val d2 = data.createRow(2); d2.createCell(0).setCellValue("B"); d2.createCell(1).setCellValue(20.0)

            val sheet = wb.createSheet("Sheet1")
            sheet.createRow(0).createCell(0).setCellValue("placeholder")
            val drawing = sheet.createDrawingPatriarch()
            val anchor = wb.creationHelper.createClientAnchor().apply {
                setCol1(3); row1 = 0; setCol2(10); row2 = 15
            }
            val chart = drawing.createChart(anchor)
            val bottom = chart.createCategoryAxis(AxisPosition.BOTTOM)
            val left = chart.createValueAxis(AxisPosition.LEFT)
            val cats = XDDFDataSourcesFactory.fromStringCellRange(data, CellRangeAddress(1, 2, 0, 0))
            val vals = XDDFDataSourcesFactory.fromNumericCellRange(data, CellRangeAddress(1, 2, 1, 1))
            val barData = chart.createData(ChartTypes.BAR, bottom, left)
            barData.addSeries(cats, vals).setTitle("Val", null)
            chart.plot(barData)
        }

        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val chartTable = blocks.filterIsInstance<DocumentBlock.Table>()
            .firstOrNull { it.caption?.contains("Chart", ignoreCase = true) == true }
        assertNotNull(chartTable, "Expected a chart Table; captions=${blocks.filterIsInstance<DocumentBlock.Table>().map { it.caption }}")
        val caption = chartTable!!.caption!!
        assertTrue(caption.contains("bar", ignoreCase = true), "Chart caption must name the type (bar); caption='$caption'")
        assertTrue(caption.contains("Data"), "Chart caption must name the source sheet (Data); caption='$caption'")
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun buildXlsx(build: (XSSFWorkbook) -> Unit): ByteArray {
        val wb = XSSFWorkbook()
        try {
            build(wb)
            val out = ByteArrayOutputStream()
            wb.write(out)
            return out.toByteArray()
        } finally {
            wb.close()
        }
    }
}
