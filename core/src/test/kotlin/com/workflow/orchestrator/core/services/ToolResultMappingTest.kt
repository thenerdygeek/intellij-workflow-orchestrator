package com.workflow.orchestrator.core.services

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolResultMappingTest {

    @Test fun `mapData transforms data and preserves the envelope`() {
        val src = ToolResult(
            data = listOf(1, 2, 3),
            summary = "three numbers",
            isError = false,
            hint = "a hint",
            tokenEstimate = 7,
        )

        val mapped = src.mapData { nums -> nums.map { it * 10 } }

        assertEquals(listOf(10, 20, 30), mapped.data)
        assertEquals("three numbers", mapped.summary)
        assertEquals(false, mapped.isError)
        assertEquals("a hint", mapped.hint)
        assertEquals(7, mapped.tokenEstimate)
    }

    @Test fun `mapData preserves imageRefs and payload verbatim`() {
        val refs = listOf(ToolResult.ImageRefData(sha256 = "abc", mime = "image/png", size = 1L))
        val src = ToolResult(
            data = listOf(1, 2, 3),
            summary = "with extras",
            imageRefs = refs,
            payload = "some-payload",
        )

        val mapped = src.mapData { nums -> nums.map { it * 10 } }

        assertEquals(listOf(10, 20, 30), mapped.data)
        assertEquals(src.imageRefs, mapped.imageRefs)
        assertEquals("some-payload", mapped.payload)
    }

    @Test fun `mapData leaves null data null and does not invoke transform`() {
        val src = ToolResult.error<List<Int>>(summary = "boom", hint = "retry")

        val mapped = src.mapData { error("transform must not run on null data") }

        assertNull(mapped.data)
        assertTrue(mapped.isError)
        assertEquals("boom", mapped.summary)
        assertEquals("retry", mapped.hint)
    }
}
