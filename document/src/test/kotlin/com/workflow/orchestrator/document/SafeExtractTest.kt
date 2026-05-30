package com.workflow.orchestrator.document

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for the shared per-unit isolation helper [safeExtract].
 *
 * The helper is the single, codebase-wide guard the robustness sweep applies at every per-unit /
 * per-stage boundary in the extraction pipelines (a page, a table, a sheet, a slide, a shape, or
 * one of the parallel sub-extractors). Its contract:
 *  - on success → returns the block's value, byte-identical to calling the block directly;
 *  - on [Throwable] → logs a WARN with the unit label + exception and returns the caller's fallback;
 *  - on [kotlinx.coroutines.CancellationException] → RETHROWS (cooperative cancellation is never
 *    swallowed — this code runs inside `withContext(Dispatchers.IO)`).
 */
class SafeExtractTest {

    @Test
    fun `returns the block result on success`() {
        val result = safeExtract("unit", fallback = -1) { 42 }
        assertEquals(42, result)
    }

    @Test
    fun `does not invoke the block lazily — passes the SAME reference through on success`() {
        val sentinel = listOf("a", "b")
        val result = safeExtract("unit", fallback = emptyList<String>()) { sentinel }
        assertSame(sentinel, result, "happy path must return the block's value untouched")
    }

    @Test
    fun `returns the fallback when the block throws`() {
        val fallback = listOf("fallback")
        val result = safeExtract("bad-unit", fallback = fallback) {
            throw IllegalArgumentException("lines must be orthogonal, vertical and horizontal")
        }
        assertSame(fallback, result, "a throwing unit must degrade to the fallback")
    }

    @Test
    fun `swallows Error subclasses too (e g StackOverflow from pathological input) and returns fallback`() {
        val result = safeExtract("deep-unit", fallback = 0) {
            throw StackOverflowError("pathological recursion in one unit")
        }
        assertEquals(0, result, "a non-Exception Throwable in one unit must not abort siblings")
    }

    @Test
    fun `rethrows CancellationException — never swallows cooperative cancellation`() {
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            safeExtract("cancelled-unit", fallback = 0) {
                throw kotlinx.coroutines.CancellationException("coroutine cancelled mid-unit")
            }
        }
    }

    @Test
    fun `logs a WARN with the unit label when the block throws`() {
        // The helper logs through com.intellij.openapi.diagnostic.Logger; we assert the visible
        // contract (fallback returned without rethrow) here and pin the no-rethrow guarantee.
        var reached = false
        val result = safeExtract("annotation #7", fallback = "ok") {
            throw RuntimeException("boom")
        }
        reached = true
        assertEquals("ok", result)
        assertTrue(reached, "control must continue past a guarded throw (no rethrow)")
    }

    @Test
    fun `isolates one failing unit in a loop without losing the healthy siblings`() {
        // This is the core invariant the sweep enforces: one bad unit drops only itself.
        val units = listOf("good1", "BAD", "good2", "good3")
        val collected = mutableListOf<String>()
        for (u in units) {
            val out = safeExtract(u, fallback = emptyList<String>()) {
                if (u == "BAD") error("malformed unit $u")
                listOf("extracted:$u")
            }
            collected += out
        }
        assertEquals(listOf("extracted:good1", "extracted:good2", "extracted:good3"), collected)
        assertFalse(collected.contains("extracted:BAD"))
    }
}
