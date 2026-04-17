package com.workflow.orchestrator.agent.tools.ide

import com.intellij.codeInspection.ProblemHighlightType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiagnosticModelsTest {

    private val json = Json { encodeDefaults = true; prettyPrint = false }

    private fun sampleEntry(
        file: String = "src/Main.kt",
        line: Int = 42,
        column: Int = 7,
        severity: String = "ERROR",
        toolId: String = "UnusedDeclaration",
        description: String = "Property 'foo' is never used",
        hasQuickFix: Boolean = true,
        category: String? = "Probable bugs",
    ) = DiagnosticEntry(
        file = file,
        line = line,
        column = column,
        severity = severity,
        toolId = toolId,
        description = description,
        hasQuickFix = hasQuickFix,
        category = category,
    )

    @Test
    fun `DiagnosticEntry serializes with all fields`() {
        val entry = sampleEntry()
        val encoded = json.encodeToString(entry)

        // All fields should appear in the JSON (encodeDefaults = true keeps them even if default).
        assertTrue(encoded.contains("\"file\":\"src/Main.kt\""), "missing file: $encoded")
        assertTrue(encoded.contains("\"line\":42"), "missing line: $encoded")
        assertTrue(encoded.contains("\"column\":7"), "missing column: $encoded")
        assertTrue(encoded.contains("\"severity\":\"ERROR\""), "missing severity: $encoded")
        assertTrue(encoded.contains("\"toolId\":\"UnusedDeclaration\""), "missing toolId: $encoded")
        assertTrue(
            encoded.contains("\"description\":\"Property 'foo' is never used\""),
            "missing description: $encoded",
        )
        assertTrue(encoded.contains("\"hasQuickFix\":true"), "missing hasQuickFix: $encoded")
        assertTrue(encoded.contains("\"category\":\"Probable bugs\""), "missing category: $encoded")
    }

    @Test
    fun `DiagnosticEntry round-trips through JSON without loss`() {
        val original = sampleEntry()
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<DiagnosticEntry>(encoded)

        assertEquals(original, decoded)
    }

    @Test
    fun `DiagnosticEntry round-trips with null category`() {
        val original = sampleEntry(category = null)
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<DiagnosticEntry>(encoded)

        assertEquals(original, decoded)
        assertNull(decoded.category)
    }

    @Test
    fun `DiagnosticEntry round-trips with unknown column`() {
        val original = sampleEntry(column = -1)
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<DiagnosticEntry>(encoded)

        assertEquals(-1, decoded.column)
        assertEquals(original, decoded)
    }

    @Test
    fun `renderDiagnosticBody with empty list returns prose unchanged`() {
        val prose = "No problems detected."
        val rendered = renderDiagnosticBody(prose, emptyList())

        assertEquals(prose, rendered)
        assertFalse(rendered.contains(DIAGNOSTIC_STRUCTURED_DATA_MARKER))
    }

    @Test
    fun `renderDiagnosticBody embeds marker and JSON payload`() {
        val prose = "Found 1 problem."
        val entries = listOf(sampleEntry())
        val rendered = renderDiagnosticBody(prose, entries)

        assertTrue(rendered.startsWith(prose), "prose should come first: $rendered")
        assertTrue(
            rendered.contains("\n$DIAGNOSTIC_STRUCTURED_DATA_MARKER\n"),
            "marker should be newline-bracketed: $rendered",
        )
        // JSON portion should be present and parseable.
        val markerIdx = rendered.indexOf("\n$DIAGNOSTIC_STRUCTURED_DATA_MARKER\n")
        val jsonPart = rendered.substring(markerIdx + "\n$DIAGNOSTIC_STRUCTURED_DATA_MARKER\n".length)
        val decoded = json.decodeFromString<List<DiagnosticEntry>>(jsonPart)
        assertEquals(entries, decoded)
    }

    @Test
    fun `parseDiagnosticBody round-trips prose and entries`() {
        val prose = "Found 2 problems.\nSee below:"
        val entries = listOf(
            sampleEntry(),
            sampleEntry(
                file = "src/Other.kt",
                line = 10,
                column = -1,
                severity = "WARNING",
                toolId = "wolf",
                description = "Dead code",
                hasQuickFix = false,
                category = null,
            ),
        )
        val rendered = renderDiagnosticBody(prose, entries)
        val (parsedProse, parsedEntries) = parseDiagnosticBody(rendered)

        assertEquals(prose, parsedProse)
        assertEquals(entries, parsedEntries)
    }

    @Test
    fun `parseDiagnosticBody without marker returns body as prose with empty entries`() {
        val body = "No problems detected in this module."
        val (prose, entries) = parseDiagnosticBody(body)

        assertEquals(body, prose)
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `parseDiagnosticBody with malformed JSON returns body up to marker with empty entries`() {
        val prose = "Found something."
        val garbage = "this-is-not-json-at-all"
        val body = "$prose\n$DIAGNOSTIC_STRUCTURED_DATA_MARKER\n$garbage"

        val (parsedProse, parsedEntries) = parseDiagnosticBody(body)

        assertEquals(prose, parsedProse)
        assertTrue(
            parsedEntries.isEmpty(),
            "malformed JSON payload should yield empty entry list: $parsedEntries",
        )
    }

    @Test
    fun `parseDiagnosticBody ignores marker appearing mid-text when not newline-bracketed`() {
        // If the marker string happens to appear inside the prose (e.g. because the
        // LLM quoted it in a description), and it isn't newline-bracketed, it should
        // be treated as ordinary prose. This is the contract documented on the constant.
        val body =
            "We mention the marker $DIAGNOSTIC_STRUCTURED_DATA_MARKER in-line but do not terminate with it."
        val (parsedProse, parsedEntries) = parseDiagnosticBody(body)

        assertEquals(body, parsedProse)
        assertTrue(parsedEntries.isEmpty())
    }

    @Test
    fun `DIAGNOSTIC_STRUCTURED_DATA_MARKER has the exact expected value`() {
        // This marker is a contract with Phase 7's ToolOutputSpiller. Locking the
        // exact string in a test prevents accidental renames from silently breaking
        // the downstream consumer.
        assertEquals("---DIAGNOSTIC-STRUCTURED-DATA---", DIAGNOSTIC_STRUCTURED_DATA_MARKER)
    }

    @Test
    fun `renderDiagnosticBody preserves prose with trailing newline in first segment`() {
        val prose = "Found 1 problem."
        val entries = listOf(sampleEntry())
        val rendered = renderDiagnosticBody(prose, entries)

        // Sanity check that we use exactly one newline before the marker, so parsing is unambiguous.
        assertNotNull(rendered)
        val (parsedProse, parsedEntries) = parseDiagnosticBody(rendered)
        assertEquals(prose, parsedProse)
        assertEquals(entries, parsedEntries)
    }

    @Test
    fun `renderDiagnosticBody emits default field values in JSON (encodeDefaults)`() {
        val entries = listOf(
            DiagnosticEntry(
                file = "x.kt", line = 1, column = 1,
                severity = "WARNING", toolId = "t", description = "d",
                hasQuickFix = false, // category defaulted to null
            ),
        )
        val body = renderDiagnosticBody(prose = "p", entries = entries)
        assertTrue(
            body.contains("\"category\":null"),
            "encodeDefaults=true must emit defaulted null category so Phase 7 readers see a stable shape",
        )
    }

    @Test
    fun `parseDiagnosticBody tolerates unknown fields (forward-compat)`() {
        val bodyWithExtra = "prose\n$DIAGNOSTIC_STRUCTURED_DATA_MARKER\n" +
            """[{"file":"a","line":1,"column":1,"severity":"ERROR","toolId":"t","description":"d","hasQuickFix":true,"category":null,"extraFieldFromFuture":42}]"""
        val (prose, entries) = parseDiagnosticBody(bodyWithExtra)
        assertEquals("prose", prose)
        assertEquals(1, entries.size)
        assertEquals("a", entries[0].file)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // normalizeSeverity — shared canonical mapper used by T2/T3 (and future T4/T5).
    //
    // Contract: every ProblemHighlightType maps to one of the three strings
    // "ERROR" | "WARNING" | "INFO". ("WEAK_WARNING" is reserved in the kdoc
    // for a future vocabulary extension, but is not emitted today.) The
    // exhaustive test pins the invariant so future upstream IntelliJ enum
    // additions safely fall through to "INFO" under the current `else`
    // default.
    //
    // Grouping matches the prior T2 RunInspectionsTool.mapHighlightType
    // behaviour verbatim — T2 collapses ERROR+GENERIC_ERROR into "ERROR",
    // WARNING+GENERIC_ERROR_OR_WARNING into "WARNING", and everything else
    // (including WEAK_WARNING, LIKE_*, INFORMATION) into "INFO". Any change
    // to these groupings is a cross-tool-visible behaviour change and must
    // be reflected in a regression scenario here.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `normalizeSeverity emits only the currently-supported 3-value vocabulary`() {
        val supported = setOf("ERROR", "WARNING", "INFO")
        for (type in ProblemHighlightType.values()) {
            val result = normalizeSeverity(type)
            assertTrue(
                result in supported,
                "normalizeSeverity($type) returned '$result'; WEAK_WARNING is reserved but not yet supported by the 3-value vocabulary",
            )
        }
    }

    @Test
    fun `normalizeSeverity pins ERROR and GENERIC_ERROR to 'ERROR'`() {
        assertEquals("ERROR", normalizeSeverity(ProblemHighlightType.ERROR))
        assertEquals("ERROR", normalizeSeverity(ProblemHighlightType.GENERIC_ERROR))
    }

    @Test
    fun `normalizeSeverity pins WARNING and GENERIC_ERROR_OR_WARNING to 'WARNING'`() {
        assertEquals("WARNING", normalizeSeverity(ProblemHighlightType.WARNING))
        assertEquals("WARNING", normalizeSeverity(ProblemHighlightType.GENERIC_ERROR_OR_WARNING))
    }

    @Test
    fun `normalizeSeverity collapses WEAK_WARNING to 'INFO' (matches prior T2 behavior)`() {
        // Regression lock: the prior T2 RunInspectionsTool.mapHighlightType
        // used `else -> Severity.INFO`, which collapsed WEAK_WARNING into
        // "INFO". Cross-tool consistency requires T3 (and future T4/T5) to
        // emit the same value. If WEAK_WARNING should ever be surfaced as a
        // distinct bucket, update ALL callers together and flip this test.
        assertEquals("INFO", normalizeSeverity(ProblemHighlightType.WEAK_WARNING))
    }

    @Test
    fun `normalizeSeverity collapses LIKE_DEPRECATED LIKE_UNUSED_SYMBOL LIKE_UNKNOWN_SYMBOL LIKE_MARKED_FOR_REMOVAL to 'INFO'`() {
        // Regression lock: all LIKE_* hints are INFO under the prior T2
        // behaviour. These are visual decorators (strikethrough, greyed-out)
        // rather than true warnings — surfacing them as "WARNING" would
        // spam Phase 7 consumers with UI-only signals.
        assertEquals("INFO", normalizeSeverity(ProblemHighlightType.LIKE_DEPRECATED))
        assertEquals("INFO", normalizeSeverity(ProblemHighlightType.LIKE_UNUSED_SYMBOL))
        assertEquals("INFO", normalizeSeverity(ProblemHighlightType.LIKE_UNKNOWN_SYMBOL))
        assertEquals("INFO", normalizeSeverity(ProblemHighlightType.LIKE_MARKED_FOR_REMOVAL))
    }

    @Test
    fun `normalizeSeverity pins INFORMATION to 'INFO'`() {
        assertEquals("INFO", normalizeSeverity(ProblemHighlightType.INFORMATION))
    }

    @Test
    fun `normalizeSeverity defaults unknown-to-this-code types to 'INFO'`() {
        // Forward-compat: if upstream IntelliJ adds a new ProblemHighlightType
        // enum value, the `else` branch in normalizeSeverity surfaces it as
        // "INFO" rather than throwing or returning a non-vocabulary string.
        // The exhaustive test above catches any non-vocabulary leak; this
        // test pins the conservative default explicitly for any
        // not-yet-covered-by-a-named-case types that may exist today.
        val namedCases = setOf(
            ProblemHighlightType.ERROR,
            ProblemHighlightType.GENERIC_ERROR,
            ProblemHighlightType.WARNING,
            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
        )
        for (type in ProblemHighlightType.values()) {
            if (type in namedCases) continue
            assertEquals(
                "INFO", normalizeSeverity(type),
                "Expected $type to collapse to 'INFO' under the prior T2 " +
                    "grouping; if you intentionally surfaced it as a new " +
                    "bucket, update T2/T3/T4/T5 callers together.",
            )
        }
    }
}
