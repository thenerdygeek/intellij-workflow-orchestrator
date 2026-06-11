package com.workflow.orchestrator.sonar.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the B11 selection-restore contract for the coverage table search filter:
 * typing in the search field must NOT reset the selection or blank the preview
 * while the selected file still matches the filter.
 */
class CoverageFilterSelectionTest {

    @Test
    fun `selected file still visible - selection restored, preview kept`() {
        val outcome = CoverageFilterSelection.resolve(
            selectedFilePath = "src/B.kt",
            visibleFilePaths = listOf("src/A.kt", "src/B.kt", "src/C.kt"),
        )
        assertEquals(1, outcome.restoreRow)
        assertFalse(outcome.blankPreview)
    }

    @Test
    fun `selected file filtered out - no selection, preview blanked`() {
        val outcome = CoverageFilterSelection.resolve(
            selectedFilePath = "src/B.kt",
            visibleFilePaths = listOf("src/A.kt", "src/C.kt"),
        )
        assertNull(outcome.restoreRow)
        assertTrue(outcome.blankPreview)
    }

    @Test
    fun `no previous selection - nothing restored, preview untouched`() {
        val outcome = CoverageFilterSelection.resolve(
            selectedFilePath = null,
            visibleFilePaths = listOf("src/A.kt"),
        )
        assertNull(outcome.restoreRow)
        assertFalse(outcome.blankPreview, "no prior selection means the preview must not be blanked")
    }

    @Test
    fun `empty filter result with a previous selection blanks the preview`() {
        val outcome = CoverageFilterSelection.resolve(
            selectedFilePath = "src/B.kt",
            visibleFilePaths = emptyList(),
        )
        assertNull(outcome.restoreRow)
        assertTrue(outcome.blankPreview)
    }
}
