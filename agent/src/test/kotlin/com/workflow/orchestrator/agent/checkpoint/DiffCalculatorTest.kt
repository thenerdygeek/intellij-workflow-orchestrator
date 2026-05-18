package com.workflow.orchestrator.agent.checkpoint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DiffCalculatorTest {

    @Test
    fun `identical text returns 0 added 0 removed`() {
        val (a, r) = DiffCalculator.countDiff("a\nb\nc", "a\nb\nc")
        assertEquals(0, a); assertEquals(0, r)
    }

    @Test
    fun `pure addition counts as added only`() {
        val (a, r) = DiffCalculator.countDiff("a\nb", "a\nb\nc\nd")
        assertEquals(2, a); assertEquals(0, r)
    }

    @Test
    fun `pure removal counts as removed only`() {
        val (a, r) = DiffCalculator.countDiff("a\nb\nc\nd", "a\nb")
        assertEquals(0, a); assertEquals(2, r)
    }

    @Test
    fun `in-place replacement counts as 1 added 1 removed per line`() {
        val (a, r) = DiffCalculator.countDiff("foo\nbar", "foo\nBAZ")
        assertEquals(1, a); assertEquals(1, r)
    }

    @Test
    fun `empty baseline vs content counts every line as added`() {
        val (a, r) = DiffCalculator.countDiff("", "x\ny\nz")
        assertEquals(3, a); assertEquals(0, r)
    }
}
