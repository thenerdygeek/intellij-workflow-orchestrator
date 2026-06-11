package com.workflow.orchestrator.agent.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Equivalence pin for the P1-17 perf rewrite of [JsEscape]: the original implementation
 * ran 8 (JS) / 9 (JSON) sequential `String.replace` passes — 8-9 full copies of multi-MB
 * session payloads on every webview push. The rewrite is a single-pass StringBuilder scan.
 *
 * The escaping contract MUST NOT change: this function feeds `executeJavaScript`, so any
 * divergence is XSS-adjacent breakage (malformed JS string literal → silently swallowed
 * bridge frame). The legacy implementations are preserved verbatim below as the oracle;
 * every corpus entry + a seeded fuzz loop must produce byte-identical output.
 */
@DisplayName("JsEscape single-pass rewrite: equivalence with the legacy 8-pass replace chain")
class JsEscapeSinglePassEquivalenceTest {

    // ── Legacy oracles: verbatim copies of the pre-rewrite implementations ──

    private fun legacyEscapeForJsString(s: String): String =
        s.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .replace(" ", "\\u2028")
            .replace(" ", "\\u2029")

    private fun legacyEscapeForJsonString(s: String): String =
        s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("", "\\f")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .replace(" ", "\\u2028")
            .replace(" ", "\\u2029")

    private fun legacyEscapeJsonForJsBridge(json: String): String =
        json.replace(" ", "\\u2028").replace(" ", "\\u2029")

    // ── Corpus: every escaped char, plus the nasty shapes the bridge actually sees ──

    private val corpus = listOf(
        "",
        "plain ascii text with no escapes at all",
        "'", "\"", "\\", "\n", "\r", "\t", "\b", "", " ", " ",
        "it's a 'quoted' string",
        """say "hi" to "them"""",
        "C:\\Users\\me\\file.kt",
        "line1\nline2\r\nline3\rline4",
        "col1\tcol2\tcol3",
        "back\bspace and formfeed",
        "a b c",
        "</script><script>alert(1)</script>",
        "<img src=x onerror=alert(1)>",
        // Literal backslash-escape text (must NOT be double-processed)
        "already escaped: \\n \\t \\u2028 \\' \\\"",
        // Replacement chars adjacent to the chars they escape
        "\\'", "\\\"", "\\\n", "'\\", "\"\\",
        // Unicode incl. surrogate pairs
        "日本語 — emoji 🎉🧪 and combining é",
        "😀👍",
        // JSON-ish payloads (the real bridge traffic shape)
        """{"key":"value with \"quotes\"","path":"a\\b\\c","text":"line1\nline2"}""",
        // Everything at once
        "mix: '\"\\\n\r\t\b  </script>日本語🎉",
        // Escapable chars at the very start and very end
        "\nstarts with newline", "ends with backslash\\",
        "'starts and ends'",
    )

    @Test
    fun `escapeForJsString matches the legacy 8-pass chain on the full corpus`() {
        for (s in corpus) {
            assertEquals(legacyEscapeForJsString(s), JsEscape.escapeForJsString(s), "input: ${dump(s)}")
        }
    }

    @Test
    fun `escapeForJsonString matches the legacy 9-pass chain on the full corpus`() {
        for (s in corpus) {
            assertEquals(legacyEscapeForJsonString(s), JsEscape.escapeForJsonString(s), "input: ${dump(s)}")
        }
    }

    @Test
    fun `escapeJsonForJsBridge matches the legacy 2-pass chain on the full corpus`() {
        for (s in corpus) {
            assertEquals(legacyEscapeJsonForJsBridge(s), JsEscape.escapeJsonForJsBridge(s), "input: ${dump(s)}")
        }
    }

    @Test
    fun `toJsString and toJsonString wrappers match legacy quoting on the full corpus`() {
        for (s in corpus) {
            assertEquals("'${legacyEscapeForJsString(s)}'", JsEscape.toJsString(s), "input: ${dump(s)}")
            assertEquals("\"${legacyEscapeForJsonString(s)}\"", JsEscape.toJsonString(s), "input: ${dump(s)}")
        }
    }

    @Test
    fun `seeded fuzz - 2000 random strings over a nasty alphabet match the legacy oracles`() {
        // Property-style: fixed seed so failures are reproducible.
        val rnd = Random(421_337)
        val alphabet =
            "abc XYZ 09'\"\\\n\r\t\b  日🎉<>/&;{}[]:,".toCharArray()
        repeat(2000) {
            val s = buildString {
                repeat(rnd.nextInt(0, 200)) { append(alphabet[rnd.nextInt(alphabet.size)]) }
            }
            assertEquals(legacyEscapeForJsString(s), JsEscape.escapeForJsString(s), "js input: ${dump(s)}")
            assertEquals(legacyEscapeForJsonString(s), JsEscape.escapeForJsonString(s), "json input: ${dump(s)}")
            assertEquals(legacyEscapeJsonForJsBridge(s), JsEscape.escapeJsonForJsBridge(s), "bridge input: ${dump(s)}")
        }
    }

    @Test
    fun `clean strings take the zero-copy fast path (same instance returned)`() {
        // Perf contract of the rewrite: a payload with nothing to escape must not be copied.
        val clean = "a perfectly ordinary multi-KB payload with no escapable characters".repeat(64)
        assertSame(clean, JsEscape.escapeForJsString(clean))
        assertSame(clean, JsEscape.escapeForJsonString(clean))
        assertSame(clean, JsEscape.escapeJsonForJsBridge(clean))
    }

    /** Codepoint dump so a failing input is readable in the assertion message. */
    private fun dump(s: String): String =
        s.take(80).map { c -> if (c.code in 32..126) c.toString() else "\\u%04X".format(c.code) }
            .joinToString("")
}
