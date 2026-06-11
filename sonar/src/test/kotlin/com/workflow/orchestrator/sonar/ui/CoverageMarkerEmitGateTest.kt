package com.workflow.orchestrator.sonar.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the B21 emit gate: exactly ONE coverage gutter marker per document line —
 * the first non-whitespace leaf whose start offset falls on the line.
 *
 * Regression context: plain LineMarkerInfo is not merged by the platform, so a gate
 * that lets every leaf through paints one icon per token (`int x = 5;` -> ~5 icons).
 */
class CoverageMarkerEmitGateTest {

    /**
     * Simulated PSI leaf: start line of its start offset + whether it is whitespace
     * or zero-length (e.g. an empty PsiErrorElement).
     */
    private data class Leaf(
        val startLine: Int,
        val whitespace: Boolean = false,
        val zeroLength: Boolean = false,
    )

    /**
     * Mirrors CoverageLineMarkerProvider's traversal: whitespace and zero-length
     * leaves are rejected outright (they can never emit, MIN-1); for each remaining
     * leaf the gate receives the start line of the nearest PRECEDING
     * non-whitespace, non-zero-length leaf (null at file start).
     */
    private fun emittingIndices(leaves: List<Leaf>): List<Int> {
        val result = mutableListOf<Int>()
        leaves.forEachIndexed { index, leaf ->
            if (leaf.whitespace || leaf.zeroLength) return@forEachIndexed
            val prevLine = leaves.take(index)
                .lastOrNull { !it.whitespace && !it.zeroLength }
                ?.startLine
            if (CoverageMarkerEmitGate.isFirstNonWhitespaceLeafOnLine(leaf.startLine, prevLine)) {
                result += index
            }
        }
        return result
    }

    @Test
    fun `a line with many tokens emits exactly one marker on the first token`() {
        // `int x = 5 ;` — 5 non-ws leaves with interleaved whitespace, all on line 0
        val leaves = listOf(
            Leaf(0),
            Leaf(0, whitespace = true),
            Leaf(0),
            Leaf(0, whitespace = true),
            Leaf(0),
            Leaf(0, whitespace = true),
            Leaf(0),
            Leaf(0),
        )
        assertEquals(listOf(0), emittingIndices(leaves), "only the first token of the line may emit")
    }

    @Test
    fun `every line that starts a non-whitespace leaf gets exactly one marker`() {
        val leaves = listOf(
            Leaf(0),
            Leaf(0),
            Leaf(0, whitespace = true),
            Leaf(1),
            Leaf(1),
            Leaf(1),
            Leaf(2, whitespace = true),
            Leaf(2),
            Leaf(3),
        )
        val emitted = emittingIndices(leaves)
        assertEquals(listOf(0, 3, 7, 8), emitted)
        // One marker per distinct line
        val linesEmitted = emitted.map { leaves[it].startLine }
        assertEquals(linesEmitted.distinct(), linesEmitted, "no line may emit twice")
        assertEquals(listOf(0, 1, 2, 3), linesEmitted)
    }

    @Test
    fun `leading whitespace is skipped and the first real token emits`() {
        // line 1 starts with an indent whitespace leaf, then a token
        val leaves = listOf(Leaf(0), Leaf(1, whitespace = true), Leaf(1))
        assertEquals(listOf(0, 2), emittingIndices(leaves))
    }

    @Test
    fun `whitespace leaves never emit even at file start`() {
        val leaves = listOf(Leaf(0, whitespace = true), Leaf(0))
        assertEquals(listOf(1), emittingIndices(leaves))
    }

    @Test
    fun `zero-length leaf at line start never emits - only the first real token does`() {
        // An empty PsiErrorElement at line start must not yield a second icon: the
        // candidate gate rejects it just like the prev-leaf walk skips it (MIN-1).
        val leaves = listOf(Leaf(0, zeroLength = true), Leaf(0), Leaf(0))
        assertEquals(listOf(1), emittingIndices(leaves))
    }

    @Test
    fun `first leaf of the file emits (null previous leaf)`() {
        assertTrue(CoverageMarkerEmitGate.isFirstNonWhitespaceLeafOnLine(0, null))
        assertTrue(CoverageMarkerEmitGate.isFirstNonWhitespaceLeafOnLine(5, null))
    }

    @Test
    fun `leaf after a multi-line token starting on an earlier line emits`() {
        // e.g. a block comment leaf starting on line 2, followed by code on line 5
        assertTrue(CoverageMarkerEmitGate.isFirstNonWhitespaceLeafOnLine(5, 2))
    }

    @Test
    fun `second token on the same line does not emit`() {
        assertTrue(!CoverageMarkerEmitGate.isFirstNonWhitespaceLeafOnLine(3, 3))
    }
}
