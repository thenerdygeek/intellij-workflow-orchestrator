package com.workflow.orchestrator.agent.walkthrough

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Pure coverage of the navigator's 1-based line clamp (the markup add/dispose glue is trivial). */
class WalkthroughNavigatorClampTest {

    @Test
    fun `clamps start and end into the document bounds`() {
        assertEquals(2 to 3, clampLineRange(startLine = 2, endLine = 3, lineCount = 5))
        assertEquals(2 to 5, clampLineRange(startLine = 2, endLine = 99, lineCount = 5)) // end past EOF
        assertEquals(1 to 5, clampLineRange(startLine = 0, endLine = 99, lineCount = 5)) // start below 1
    }

    @Test
    fun `end is never before start, and an empty document yields 1 to 1`() {
        assertEquals(4 to 4, clampLineRange(startLine = 4, endLine = 2, lineCount = 5)) // end < start -> start
        assertEquals(1 to 1, clampLineRange(startLine = 3, endLine = 9, lineCount = 0)) // empty doc floored to 1
    }
}
