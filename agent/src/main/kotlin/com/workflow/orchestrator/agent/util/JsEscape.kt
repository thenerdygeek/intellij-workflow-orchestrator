package com.workflow.orchestrator.agent.util

/**
 * Helpers for escaping Kotlin strings when embedding them into JavaScript code
 * generated for the JCEF webview bridge.
 *
 * Two distinct contracts are supported:
 *
 *  - [toJsString] returns a single-quote-wrapped JS string literal. Use this when
 *    you are constructing a `callJs("foo(${JsEscape.toJsString(s)})")` style call,
 *    or otherwise need an arbitrary Kotlin string to appear as a JS string literal
 *    inside generated source.
 *
 *  - [toJsonString] returns a double-quote-wrapped JSON string literal. Use this
 *    when assembling a JSON document by hand whose result will later be parsed by
 *    `JSON.parse` on the JS side. JSON does NOT permit `\'`, so single quotes are
 *    intentionally NOT escaped here — the surrounding [toJsString] (or equivalent
 *    JS-string wrapping) is responsible for re-escaping them when the JSON travels
 *    inside a JS string literal.
 *
 * Performance (P1-17): all escaping is a single StringBuilder pass. The previous
 * implementation chained 8-9 sequential `String.replace` calls, copying multi-MB
 * session payloads once per pass on every webview push. Strings that need no
 * escaping are returned as the SAME instance (zero-copy fast path). The escape
 * set is byte-identical to the old chain — pinned by
 * `JsEscapeSinglePassEquivalenceTest`, which keeps the legacy implementation as
 * its oracle.
 */
object JsEscape {

    /**
     * Escape [s] and wrap it in single quotes so it can be embedded as a JS string
     * literal. Escapes the same set as the legacy `AgentCefPanel.jsonStr` helper:
     * backslash, single quote, double quote, newline, carriage return, tab.
     * Also escapes U+2028 (LINE SEPARATOR) and U+2029 (PARAGRAPH SEPARATOR) — both
     * are valid JSON whitespace but JS parsers treat them as line terminators inside
     * string literals, causing silent bridge frame corruption on any LLM output that
     * happens to contain them (audit finding agent-ui:F-2).
     */
    fun toJsString(s: String): String = "'${escapeForJsString(s)}'"

    /**
     * Same escape set as [toJsString] but returns just the escaped contents
     * without surrounding quotes — useful when the caller already controls the
     * surrounding quote characters in a string template.
     */
    fun escapeForJsString(s: String): String = escapeSinglePass(s, ::jsReplacementFor)

    /**
     * Escape [s] and wrap it in double quotes so it forms a valid JSON string
     * literal. Escapes the JSON-permitted control sequences (backslash, double
     * quote, backspace, form feed, newline, carriage return, tab). Single quotes
     * are NOT escaped because `\'` is not a valid JSON escape — the resulting
     * JSON will contain literal apostrophes, which is fine because JSON parsers
     * accept them in string contents.
     * Also escapes U+2028/U+2029 so the JSON is safe to embed inside a JS literal.
     */
    fun toJsonString(s: String): String = "\"${escapeForJsonString(s)}\""

    /**
     * Same escape set as [toJsonString] but returns just the escaped contents
     * without surrounding quotes.
     */
    fun escapeForJsonString(s: String): String = escapeSinglePass(s, ::jsonReplacementFor)

    /**
     * Post-process a [kotlinx.serialization.json.Json.encodeToString] result that will be
     * injected as a JavaScript literal (e.g. via [callJs] string interpolation). Standard
     * JSON serializers do not escape U+2028 / U+2029 because they are valid JSON whitespace,
     * but JS parsers treat them as line terminators inside string literals, which breaks the
     * bridge frame.
     *
     * Only call this when the JSON is embedded as a JS literal. If the JSON travels via a
     * binary [JBCefJSQuery] message (no JS literal boundary), escaping is not needed.
     */
    fun escapeJsonForJsBridge(json: String): String = escapeSinglePass(json, ::bridgeReplacementFor)

    /**
     * Single pass over [s]: scan for the first character [replacementFor] maps, return [s]
     * unchanged (same instance — no copy) when nothing matches, otherwise build the escaped
     * result in one StringBuilder sweep.
     */
    private inline fun escapeSinglePass(s: String, replacementFor: (Char) -> String?): String {
        var first = -1
        for (i in s.indices) {
            if (replacementFor(s[i]) != null) {
                first = i
                break
            }
        }
        if (first == -1) return s
        val sb = StringBuilder(s.length + ESCAPE_HEADROOM)
        sb.append(s, 0, first)
        for (i in first until s.length) {
            val c = s[i]
            val rep = replacementFor(c)
            if (rep != null) sb.append(rep) else sb.append(c)
        }
        return sb.toString()
    }

    /** Escape set for JS string literals — equivalent to the legacy 8-pass replace chain. */
    private fun jsReplacementFor(c: Char): String? = when (c) {
        '\\' -> "\\\\"
        '\'' -> "\\'"
        '"' -> "\\\""
        '\n' -> "\\n"
        '\r' -> "\\r"
        '\t' -> "\\t"
        ' ' -> "\\u2028"
        ' ' -> "\\u2029"
        else -> null
    }

    /** Escape set for JSON string literals — equivalent to the legacy 9-pass replace chain. */
    private fun jsonReplacementFor(c: Char): String? = when (c) {
        '\\' -> "\\\\"
        '"' -> "\\\""
        '\b' -> "\\b"
        '' -> "\\f"
        '\n' -> "\\n"
        '\r' -> "\\r"
        '\t' -> "\\t"
        ' ' -> "\\u2028"
        ' ' -> "\\u2029"
        else -> null
    }

    /** Escape set for already-serialized JSON embedded as a JS literal (separators only). */
    private fun bridgeReplacementFor(c: Char): String? = when (c) {
        ' ' -> "\\u2028"
        ' ' -> "\\u2029"
        else -> null
    }

    private const val ESCAPE_HEADROOM = 16
}
