package com.workflow.orchestrator.sonar.ui

import com.workflow.orchestrator.sonar.model.FileCoverageData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import javax.swing.event.TableModelEvent
import javax.swing.event.TableModelListener

/**
 * Pins the P2-20 equality gate in [CoverageTableModel.setData]: re-applying equal
 * data in the same mode must NOT fire a table event (a fire resets the JTable
 * selection and blanks the preview — the B11 symptom), and the pre-computed value
 * cache must serve [CoverageTableModel.getValueAt] without per-cell allocation.
 */
class CoverageTableModelGateTest {

    private fun file(path: String, lineCoverage: Double = 78.5) = FileCoverageData(
        filePath = path,
        lineCoverage = lineCoverage,
        branchCoverage = 60.0,
        uncoveredLines = 5,
        uncoveredConditions = 2,
        lineStatuses = emptyMap(),
        projectKey = "proj",
    )

    private class EventRecorder : TableModelListener {
        val events = mutableListOf<TableModelEvent>()
        override fun tableChanged(e: TableModelEvent) {
            events.add(e)
        }
    }

    @Test
    fun `setData with equal data and same mode does not fire`() {
        val model = CoverageTableModel()
        val recorder = EventRecorder()
        model.addTableModelListener(recorder)

        model.setData(listOf(file("src/A.kt"), file("src/B.kt")), isNewCode = false)
        assertEquals(1, recorder.events.size, "first load must fire")

        // Equal-by-value list instance — the no-op refresh path
        model.setData(listOf(file("src/A.kt"), file("src/B.kt")), isNewCode = false)
        assertEquals(1, recorder.events.size, "equal data must not fire (P2-20 gate)")
    }

    @Test
    fun `setData with changed data fires a data-changed event`() {
        val model = CoverageTableModel()
        val recorder = EventRecorder()
        model.addTableModelListener(recorder)

        model.setData(listOf(file("src/A.kt")), isNewCode = false)
        model.setData(listOf(file("src/A.kt"), file("src/B.kt")), isNewCode = false)

        assertEquals(2, recorder.events.size)
        val second = recorder.events[1]
        assertEquals(0, second.firstRow, "same-mode update must be a data change, not a structure change")
    }

    @Test
    fun `mode switch fires a structure change even for equal data`() {
        val model = CoverageTableModel()
        val recorder = EventRecorder()
        model.addTableModelListener(recorder)

        model.setData(listOf(file("src/A.kt")), isNewCode = false)
        model.setData(listOf(file("src/A.kt")), isNewCode = true)

        assertEquals(2, recorder.events.size)
        assertEquals(
            TableModelEvent.HEADER_ROW,
            recorder.events[1].firstRow,
            "mode switch changes columns and must fire a structure change"
        )
    }

    @Test
    fun `getValueAt serves pre-computed display name and formatted coverage`() {
        val model = CoverageTableModel()
        model.setData(listOf(file("src/main/kotlin/Foo.kt", lineCoverage = 78.5)), isNewCode = false)

        assertEquals("Foo.kt", model.getValueAt(0, 0), "col 0 is the file display name")
        assertEquals("%.1f%%".format(78.5), model.getValueAt(0, 1), "col 1 is formatted line coverage")
        assertEquals(5, model.getValueAt(0, 3), "col 3 is uncovered lines")
    }

    @Test
    fun `getFilePath still returns the full path used for selection restore`() {
        val model = CoverageTableModel()
        model.setData(listOf(file("src/main/kotlin/Foo.kt")), isNewCode = false)
        assertEquals("src/main/kotlin/Foo.kt", model.getFilePath(0))
    }
}
