package com.workflow.orchestrator.handover.ui

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.awt.Container

class HandoverOverrideBannerTest {

    private fun walkText(c: Container): String = buildString {
        for (i in 0 until c.componentCount) {
            val child = c.getComponent(i)
            if (child is javax.swing.JLabel) append(" ").append(child.text ?: "")
            if (child is com.intellij.ui.components.ActionLink) append(" ").append(child.text ?: "")
            if (child is Container) append(walkText(child))
        }
    }

    @Test
    fun `banner is hidden by default`() {
        val b = HandoverOverrideBanner()
        assertFalse(b.isVisible)
    }

    @Test
    fun `banner shows when failures non-empty`() {
        val b = HandoverOverrideBanner()
        b.setFailures(listOf(FailedCheck("quality.gate", "Quality gate FAILED", "Quality")))
        assertTrue(b.isVisible)
        val text = walkText(b)
        assertTrue(text.contains("Quality gate FAILED"), text)
        assertTrue(text.contains("View Quality tab"), text)
    }

    @Test
    fun `banner hides when failures cleared`() {
        val b = HandoverOverrideBanner()
        b.setFailures(listOf(FailedCheck("quality.gate", "Quality gate FAILED", "Quality")))
        b.setFailures(emptyList())
        assertFalse(b.isVisible)
    }

    @Test
    fun `banner text mentions count when multiple failures`() {
        val b = HandoverOverrideBanner()
        b.setFailures(listOf(
            FailedCheck("quality.gate", "Quality gate FAILED", "Quality"),
            FailedCheck("suite.web-e2e", "Web E2E running", "Automation"),
        ))
        val text = walkText(b)
        assertTrue(text.contains("2"), "expected '2' in: $text")
        // First failure's tab is the link target
        assertTrue(text.contains("View Quality tab"), text)
    }
}
