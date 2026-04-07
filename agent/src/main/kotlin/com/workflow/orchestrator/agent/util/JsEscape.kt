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
 */
object JsEscape {

    /**
     * Escape [s] and wrap it in single quotes so it can be embedded as a JS string
     * literal. Escapes the same set as the legacy `AgentCefPanel.jsonStr` helper:
     * backslash, single quote, double quote, newline, carriage return, tab.
     */
    fun toJsString(s: String): String = "'${escapeForJsString(s)}'"

    /**
     * Same escape set as [toJsString] but returns just the escaped contents
     * without surrounding quotes — useful when the caller already controls the
     * surrounding quote characters in a string template.
     */
    fun escapeForJsString(s: String): String =
        s.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    /**
     * Escape [s] and wrap it in double quotes so it forms a valid JSON string
     * literal. Escapes the JSON-permitted control sequences (backslash, double
     * quote, backspace, form feed, newline, carriage return, tab). Single quotes
     * are NOT escaped because `\'` is not a valid JSON escape — the resulting
     * JSON will contain literal apostrophes, which is fine because JSON parsers
     * accept them in string contents.
     */
    fun toJsonString(s: String): String = "\"${escapeForJsonString(s)}\""

    /**
     * Same escape set as [toJsonString] but returns just the escaped contents
     * without surrounding quotes.
     */
    fun escapeForJsonString(s: String): String =
        s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\u000C", "\\f")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
}
