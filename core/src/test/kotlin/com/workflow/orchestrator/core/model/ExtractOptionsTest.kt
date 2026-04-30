package com.workflow.orchestrator.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ExtractOptionsTest {

    // ── Default construction ───────────────────────────────────────────────────

    @Test
    fun `default ExtractOptions is valid`() {
        val opts = ExtractOptions()
        assertNull(opts.maxChars)
        assertEquals(30_000L, opts.timeoutMs)
        assertEquals(false, opts.includeEmbedded)
        assertEquals(false, opts.enableStreamMode)
    }

    // ── maxChars validation ────────────────────────────────────────────────────

    @Test
    fun `maxChars null is accepted (use settings default)`() {
        val opts = ExtractOptions(maxChars = null)
        assertNull(opts.maxChars)
    }

    @Test
    fun `maxChars positive value is accepted`() {
        val opts = ExtractOptions(maxChars = 200_000)
        assertEquals(200_000, opts.maxChars)
    }

    @Test
    fun `maxChars 1 is accepted (minimum positive)`() {
        val opts = ExtractOptions(maxChars = 1)
        assertEquals(1, opts.maxChars)
    }

    @Test
    fun `maxChars 0 is rejected`() {
        assertThrows<IllegalArgumentException> {
            ExtractOptions(maxChars = 0)
        }
    }

    @Test
    fun `maxChars negative is rejected`() {
        assertThrows<IllegalArgumentException> {
            ExtractOptions(maxChars = -1)
        }
    }

    @Test
    fun `maxChars large negative is rejected`() {
        assertThrows<IllegalArgumentException> {
            ExtractOptions(maxChars = -999_999)
        }
    }

    // ── timeoutMs validation ───────────────────────────────────────────────────

    @Test
    fun `timeoutMs positive value is accepted`() {
        val opts = ExtractOptions(timeoutMs = 60_000L)
        assertEquals(60_000L, opts.timeoutMs)
    }

    @Test
    fun `timeoutMs 1 is accepted (minimum positive)`() {
        val opts = ExtractOptions(timeoutMs = 1L)
        assertEquals(1L, opts.timeoutMs)
    }

    @Test
    fun `timeoutMs 0 is rejected`() {
        assertThrows<IllegalArgumentException> {
            ExtractOptions(timeoutMs = 0L)
        }
    }

    @Test
    fun `timeoutMs negative is rejected`() {
        assertThrows<IllegalArgumentException> {
            ExtractOptions(timeoutMs = -1L)
        }
    }

    // ── Flag fields ────────────────────────────────────────────────────────────

    @Test
    fun `includeEmbedded can be set to true`() {
        val opts = ExtractOptions(includeEmbedded = true)
        assertEquals(true, opts.includeEmbedded)
    }

    @Test
    fun `enableStreamMode can be set to true`() {
        val opts = ExtractOptions(enableStreamMode = true)
        assertEquals(true, opts.enableStreamMode)
    }

    @Test
    fun `all options set together remain valid`() {
        val opts = ExtractOptions(
            maxChars = 50_000,
            timeoutMs = 10_000L,
            includeEmbedded = true,
            enableStreamMode = true,
        )
        assertEquals(50_000, opts.maxChars)
        assertEquals(10_000L, opts.timeoutMs)
        assertEquals(true, opts.includeEmbedded)
        assertEquals(true, opts.enableStreamMode)
    }
}
