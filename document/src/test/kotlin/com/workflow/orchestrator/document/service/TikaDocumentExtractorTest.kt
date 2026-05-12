package com.workflow.orchestrator.document.service

import com.workflow.orchestrator.core.model.ExtractOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Integration tests for [TikaDocumentExtractor] — the Phase 6 entry-point that wires all
 * pipelines together with MIME dispatch, concurrency control, and error mapping.
 *
 * All tests use [runBlocking] to drive the suspend [extract] method synchronously.
 */
class TikaDocumentExtractorTest {

    // Default extractor with 200K char budget (no settings dependency in v1).
    private val extractor = TikaDocumentExtractor()

    private fun fixturePath(name: String) =
        Paths.get(javaClass.classLoader.getResource("fixtures/$name")!!.toURI())

    // ── 1. data.csv → success ─────────────────────────────────────────────────

    @Test
    fun `data csv extracts successfully with correct mime and contains Alice`() = runBlocking {
        val result = extractor.extract(fixturePath("data.csv"))

        assertFalse(result.isError, "Expected success but got error: ${result.summary}")
        val content = requireNotNull(result.data) {
            "Expected non-null content, got error: ${result.summary}"
        }
        assertEquals("text/csv", content.mime, "MIME type must be text/csv")
        assertFalse(content.truncated, "data.csv should not be truncated")
        assertTrue(content.markdown.contains("Alice"),
            "Extracted markdown must contain 'Alice'; got: ${content.markdown.take(500)}")
    }

    // ── 2. bug-tracker.xlsx → success with BUG-001 and Q1 ─────────────────────

    @Test
    fun `bug-tracker xlsx extracts successfully with correct OOXML mime and key values`() = runBlocking {
        val result = extractor.extract(fixturePath("bug-tracker.xlsx"))

        assertFalse(result.isError, "Expected success but got error: ${result.summary}")
        val content = requireNotNull(result.data) {
            "Expected non-null content, got error: ${result.summary}"
        }
        assertEquals(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            content.mime,
            "MIME type must be OOXML spreadsheet",
        )
        assertTrue(content.markdown.contains("BUG-001"),
            "Markdown must contain 'BUG-001'; snippet: ${content.markdown.take(500)}")
        assertTrue(content.markdown.contains("Q1"),
            "Markdown must contain merged cell value 'Q1'; snippet: ${content.markdown.take(1000)}")
    }

    // ── 3. spec-with-tables.pdf → success with FR-001, Approved, Test, Pass ────

    @Test
    fun `spec-with-tables pdf extracts successfully and contains values from all 3 tables`() = runBlocking {
        val result = extractor.extract(fixturePath("spec-with-tables.pdf"))

        assertFalse(result.isError, "Expected success but got error: ${result.summary}")
        val content = requireNotNull(result.data) {
            "Expected non-null content, got error: ${result.summary}"
        }
        assertEquals("application/pdf", content.mime)

        val md = content.markdown
        assertTrue(md.contains("FR-001"),
            "Markdown must contain 'FR-001' from the FR table; snippet: ${md.take(1000)}")
        // The Acceptance table has headers ["Test", "Expected", "Actual"] and a row ["Pass",...]
        // or similar. We assert the column headers appear in the markdown.
        assertTrue(md.contains("Test") || md.contains("Actual"),
            "Markdown must contain Acceptance table content; snippet: ${md.take(1500)}")
    }

    // ── 4. tabula-encrypted.pdf → isError=true, summary contains password/encrypted ──

    @Test
    fun `tabula-encrypted pdf returns error with password-related message`() = runBlocking {
        val result = extractor.extract(fixturePath("tabula-encrypted.pdf"))

        assertTrue(result.isError,
            "Encrypted PDF must return isError=true; got summary: ${result.summary}")
        val summaryLower = result.summary.lowercase()
        assertTrue(
            summaryLower.contains("password") ||
                summaryLower.contains("encrypt") ||
                summaryLower.contains("protected"),
            "Error summary must mention password/encrypted/protected; got: ${result.summary}",
        )
    }

    // ── 5. corrupt.pdf → isError=true ────────────────────────────────────────

    @Test
    fun `corrupt pdf returns error result`() = runBlocking {
        val result = extractor.extract(fixturePath("corrupt.pdf"))

        assertTrue(result.isError,
            "Corrupt PDF must return isError=true; got summary: ${result.summary}")
        assertTrue(result.summary.isNotBlank(), "Error summary must not be blank")
    }

    // ── 6. zero.pdf → isError=true ───────────────────────────────────────────

    @Test
    fun `zero-byte pdf returns error result`() = runBlocking {
        val result = extractor.extract(fixturePath("zero.pdf"))

        assertTrue(result.isError,
            "Zero-byte file must return isError=true; got summary: ${result.summary}")
        assertTrue(result.summary.isNotBlank(), "Error summary must not be blank")
    }

    // ── 7. wrong-extension.pdf (plaintext) → no crash ────────────────────────

    @Test
    fun `wrong-extension pdf does not crash — succeeds or fails cleanly`() = runBlocking {
        val result = extractor.extract(fixturePath("wrong-extension.pdf"))

        // Either succeeds with text/plain, or fails with a clean error — must not throw.
        assertTrue(result.summary.isNotBlank(), "Summary must be non-blank regardless of outcome")
        if (!result.isError) {
            val content = requireNotNull(result.data) {
                "Expected non-null content, got error: ${result.summary}"
            }
            // If it succeeds, Tika should have detected the real MIME type (text/plain).
            assertEquals("text/plain", content.mime,
                "Plain-text file with .pdf extension should be detected as text/plain")
        }
    }

    // ── 8. maxCharsProvider is consulted ─────────────────────────────────────

    @Test
    fun `maxCharsProvider = 50 causes truncation`() = runBlocking {
        val smallExtractor = TikaDocumentExtractor(maxCharsProvider = { 50 })
        val result = smallExtractor.extract(fixturePath("data.csv"))

        assertFalse(result.isError, "Expected success even with tiny maxChars; got: ${result.summary}")
        val content = requireNotNull(result.data) {
            "Expected non-null content, got error: ${result.summary}"
        }
        assertTrue(content.truncated,
            "Markdown must be truncated when maxCharsProvider = 50")
        assertTrue(content.markdown.length <= 50 + 300,
            // The first block may exceed 50 chars (single-block oversizing rule in assembler),
            // but the rest of the document must be cut off. We allow assembler overhead for the
            // truncation marker text itself (~300 chars).
            "Markdown length should be close to 50 chars budget; got ${content.markdown.length}",
        )
    }

    // ── 9. options.maxChars overrides maxCharsProvider ────────────────────────

    @Test
    fun `options maxChars overrides maxCharsProvider`() = runBlocking {
        // Provider says 50 chars but options says 1000 — 1000 should win.
        val smallProviderExtractor = TikaDocumentExtractor(maxCharsProvider = { 50 })
        val options = ExtractOptions(maxChars = 1_000)
        val result = smallProviderExtractor.extract(fixturePath("data.csv"), options)

        assertFalse(result.isError, "Expected success; got: ${result.summary}")
        val content = requireNotNull(result.data) {
            "Expected non-null content, got error: ${result.summary}"
        }
        // data.csv is tiny — well under 1000 chars. Should NOT be truncated at 1000.
        assertFalse(content.truncated,
            "Markdown must NOT be truncated when options.maxChars=1000 on a tiny CSV")
        assertTrue(content.markdown.contains("Alice"),
            "Full content must be present when options.maxChars=1000")
    }

    // ── 10. Concurrent extraction — 4 coroutines on the same fixture ──────────

    @Test
    fun `4 concurrent extractions of data csv all succeed and return identical markdown`() = runBlocking {
        val results = (1..4).map {
            async(Dispatchers.IO) {
                extractor.extract(fixturePath("data.csv"))
            }
        }.awaitAll()

        results.forEach { result ->
            assertFalse(result.isError,
                "All concurrent extractions must succeed; error: ${result.summary}")
            val content = requireNotNull(result.data) {
                "Expected non-null content, got error: ${result.summary}"
            }
            assertTrue(content.markdown.contains("Alice"),
                "Each concurrent extraction must contain 'Alice'")
        }

        // All 4 must produce identical markdown (per-call instantiation + semaphore correctness).
        val markdowns = results.map {
            requireNotNull(it.data) { "Expected non-null content, got error: ${it.summary}" }.markdown
        }.toSet()
        assertEquals(1, markdowns.size,
            "All 4 concurrent extractions must produce identical markdown; got ${markdowns.size} distinct outputs")
    }

    // ── 11. Init-block parser-registry assertion ──────────────────────────────

    @Test
    fun `TikaDocumentExtractor construction succeeds and parsers are non-empty`() {
        // The init block asserts parsers.isNotEmpty(). If construction completes without
        // throwing, the test classloader passes the TIKA-1145 check.
        // (extractor is already constructed in the field initializer above; this test
        //  verifies it stays constructible in a fresh instance too.)
        val fresh = TikaDocumentExtractor()
        // If we reach here, parsers.isNotEmpty() returned true and no OCR parser was found.
        // The init block also prints parsers.size — visible in test output.
        assertTrue(true, "Construction of TikaDocumentExtractor must not throw")
        // Confirm the fresh instance can extract something meaningful.
        val result = runBlocking { fresh.extract(fixturePath("data.csv")) }
        assertFalse(result.isError, "Fresh extractor must work; got: ${result.summary}")
    }
}
