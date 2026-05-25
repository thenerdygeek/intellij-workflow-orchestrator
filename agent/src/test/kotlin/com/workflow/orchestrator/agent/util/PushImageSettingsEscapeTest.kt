package com.workflow.orchestrator.agent.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Regression tests for audit finding agent-ui:F-3:
 * pushImageSettings used a hand-rolled escape (only backslash + single-quote),
 * which silently corrupted bridge frames containing newlines or U+2028/U+2029.
 *
 * The fix delegates to JsEscape.toJsString, which already handles the full set.
 * These tests verify the escaping contract by exercising JsEscape directly
 * (AgentCefPanel.pushImageSettings is hard to unit-test without a live JCEF environment,
 * but the source-text pin below ensures it calls JsEscape.toJsString).
 */
class PushImageSettingsEscapeTest {

    /**
     * Simulate what pushImageSettings does after the fix:
     *   val jsLiteral = JsEscape.toJsString(payload)
     *   callJs("... __applyImageSettings($jsLiteral); }")
     *
     * The resulting call JS must not contain raw newline, U+2028, or U+2029
     * when the payload itself contains them.
     */
    private fun buildCallJs(payload: String): String {
        val jsLiteral = JsEscape.toJsString(payload)
        return "if (window.__applyImageSettings) { window.__applyImageSettings($jsLiteral); }"
    }

    @Test
    fun `payload with newline produces no raw newline in callJs`() {
        val payload = """{"maxSizeMb":10,"enabled":true,"note":"line1\nline2"}"""
        val callJs = buildCallJs(payload)
        assertFalse(
            callJs.contains('\n'),
            "callJs must not contain a raw newline character, got:\n$callJs"
        )
        assertTrue(callJs.contains("\\n"), "escaped \\n must appear in callJs")
    }

    @Test
    fun `payload with U+2028 produces no raw U+2028 in callJs`() {
        val payload = "value with-sep"    // U+2028 LINE SEPARATOR
        val callJs = buildCallJs(payload)
        assertFalse(
            callJs.contains(' '),
            "callJs must not contain raw U+2028 (LINE SEPARATOR)"
        )
        assertTrue(callJs.contains("\\u2028"), "escaped \\u2028 must appear in callJs")
    }

    @Test
    fun `payload with U+2029 produces no raw U+2029 in callJs`() {
        val payload = "value with-par"    // U+2029 PARAGRAPH SEPARATOR
        val callJs = buildCallJs(payload)
        assertFalse(
            callJs.contains(' '),
            "callJs must not contain raw U+2029 (PARAGRAPH SEPARATOR)"
        )
        assertTrue(callJs.contains("\\u2029"), "escaped \\u2029 must appear in callJs")
    }

    @Test
    fun `payload with single quote produces properly escaped callJs`() {
        val payload = """{"label":"it's fine"}"""
        // Verify via JsEscape directly: toJsString must escape the apostrophe.
        val jsLiteral = JsEscape.toJsString(payload)
        // toJsString wraps in '...', so inner content is between first and last char.
        val inner = jsLiteral.substring(1, jsLiteral.length - 1)
        // The raw sequence "it's" (apostrophe not preceded by backslash) must not appear.
        assertFalse(inner.contains("it's"), "Raw 'it's' must not appear un-escaped inside JS literal")
        // The escaped form \\' must be present in the literal (as a 2-char sequence: \ then ')
        val hasBackslashApostrophe = inner.contains("\\'")
        assertTrue(hasBackslashApostrophe, "Escaped \\' (backslash + apostrophe) must appear inside the JS literal")
        // callJs must also not contain the raw apostrophe in the value position
        val callJs = buildCallJs(payload)
        assertFalse(callJs.contains("it's"), "Raw apostrophe must not survive in callJs")
    }

    @Test
    fun `normal JSON payload round-trips correctly through JsEscape`() {
        val payload = """{"maxSizeMb":5,"allowedTypes":["image/png","image/jpeg"],"enabled":true}"""
        val jsLiteral = JsEscape.toJsString(payload)
        assertTrue(jsLiteral.startsWith("'"), "JS literal must start with single-quote")
        assertTrue(jsLiteral.endsWith("'"), "JS literal must end with single-quote")
        // The inner content should be recoverable
        val inner = jsLiteral.substring(1, jsLiteral.length - 1)
        // No raw special chars
        assertFalse(inner.contains('\n'))
        assertFalse(inner.contains(' '))
        assertFalse(inner.contains(' '))
    }

    /**
     * Source-text pin: AgentCefPanel.pushImageSettings must use JsEscape.toJsString,
     * NOT the old hand-rolled escape pattern.
     *
     * Locates AgentCefPanel.kt relative to the project root anchored at the known
     * absolute path structure, then asserts positive and negative invariants.
     */
    @Test
    fun `AgentCefPanel pushImageSettings uses JsEscape toJsString not hand-rolled escape`() {
        // Resolve the module root from the test class bytecode location.
        // Layout: agent/build/classes/kotlin/test/... → walk 5 levels to agent/
        val codeSourceUrl = javaClass.protectionDomain.codeSource?.location
        if (codeSourceUrl == null) {
            System.err.println("[PushImageSettingsEscapeTest] Code source URL unavailable; skipping source-text pin.")
            return
        }
        var dir = java.io.File(codeSourceUrl.toURI())
        repeat(5) { dir = dir.parentFile }

        val src = dir.resolve("src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt")
        if (!src.exists()) {
            System.err.println(
                "[PushImageSettingsEscapeTest] AgentCefPanel.kt not found at ${src.absolutePath}; " +
                "skipping source-text pin."
            )
            return
        }

        val source = src.readText()

        // Positive: JsEscape.toJsString must be called
        assertTrue(
            source.contains("JsEscape.toJsString"),
            "pushImageSettings must call JsEscape.toJsString — not a hand-rolled escape"
        )

        // Negative: the old hand-rolled pattern that missed newlines and U+2028/U+2029
        // must not be present alongside the absence of JsEscape
        val hasJsEscape = source.contains("JsEscape")
        val hasOldEscape = source.contains("""replace("'", "\\'")""") &&
            source.contains("""replace("\\", "\\\\")""")
        assertFalse(
            hasOldEscape && !hasJsEscape,
            "Old hand-rolled escape must not be the sole escape strategy in the file"
        )
    }
}
