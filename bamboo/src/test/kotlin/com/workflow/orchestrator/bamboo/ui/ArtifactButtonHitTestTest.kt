package com.workflow.orchestrator.bamboo.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.awt.Point
import java.awt.Rectangle

/**
 * Unit tests for [StageDetailPanel.resolveButtonAction] — the pure coordinate-math
 * function that determines which artifact action ("download" / "open") the user
 * clicked, given a point in actionsPanel-local coordinates and the button bounding
 * rectangles.
 *
 * These tests run without a running IDE (no IntelliJ infrastructure required).
 *
 * B5 fix: artifact "Download"/"Open" buttons were painted but unclickable because
 * they were inside a JList rubber-stamp renderer. A [java.awt.event.MouseAdapter]
 * on the list now forwards clicks via hit-testing; [resolveButtonAction] is the
 * pure decision function.
 */
class ArtifactButtonHitTestTest {

    // Typical button bounds in actionsPanel-local coordinates.
    private val downloadBounds = Rectangle(0, 2, 70, 20)
    private val openBounds = Rectangle(74, 2, 50, 20)

    @Test
    fun `click inside download button returns download action`() {
        val pt = Point(35, 10)
        assertEquals(
            StageDetailPanel.ARTIFACT_ACTION_DOWNLOAD,
            StageDetailPanel.resolveButtonAction(pt, downloadBounds, openBounds)
        )
    }

    @Test
    fun `click inside open button returns open action`() {
        val pt = Point(90, 10)
        assertEquals(
            StageDetailPanel.ARTIFACT_ACTION_OPEN,
            StageDetailPanel.resolveButtonAction(pt, downloadBounds, openBounds)
        )
    }

    @Test
    fun `click between buttons returns null`() {
        val pt = Point(71, 10)
        assertNull(StageDetailPanel.resolveButtonAction(pt, downloadBounds, openBounds))
    }

    @Test
    fun `click outside any button returns null`() {
        val pt = Point(200, 10)
        assertNull(StageDetailPanel.resolveButtonAction(pt, downloadBounds, openBounds))
    }

    @Test
    fun `null openBounds - click where open button would be returns null`() {
        val pt = Point(90, 10)
        assertNull(StageDetailPanel.resolveButtonAction(pt, downloadBounds, openBounds = null))
    }

    @Test
    fun `null openBounds - click on download still returns download`() {
        val pt = Point(35, 10)
        assertEquals(
            StageDetailPanel.ARTIFACT_ACTION_DOWNLOAD,
            StageDetailPanel.resolveButtonAction(pt, downloadBounds, openBounds = null)
        )
    }

    @Test
    fun `both null bounds - any click returns null`() {
        assertNull(StageDetailPanel.resolveButtonAction(Point(10, 10), null, null))
    }

    @Test
    fun `click exactly on download button top-left corner returns download`() {
        val pt = Point(downloadBounds.x, downloadBounds.y)
        assertEquals(
            StageDetailPanel.ARTIFACT_ACTION_DOWNLOAD,
            StageDetailPanel.resolveButtonAction(pt, downloadBounds, openBounds)
        )
    }

    @Test
    fun `click one pixel to the right of download button right edge returns null`() {
        val pt = Point(downloadBounds.x + downloadBounds.width, downloadBounds.y + 5)
        assertNull(StageDetailPanel.resolveButtonAction(pt, downloadBounds, openBounds))
    }
}
