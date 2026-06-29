package com.workflow.orchestrator.agent.session

/**
 * Pure, dependency-free sanitizer applied at the conversation-title boundary.
 *
 * A title can be seeded from model output (a provisional title derived from the
 * user message, a Haiku-generated refinement, or — historically, via a bug — the
 * raw first assistant message). Raw assistant prose can carry `<thinking>…</thinking>`
 * reasoning blocks and fenced ```code``` blocks, which must never leak into a
 * History-list card title (the QA-observed
 * "`<thinking> … </thinking> Here is the first analysis block: ```kotlin …`" title).
 *
 * This strips:
 *  - `<thinking>…</thinking>` reasoning blocks (closed and unclosed/truncated),
 *  - fenced ```code``` blocks (closed and unclosed/truncated),
 *  - inline `` `code` `` backticks (the text inside is kept),
 *  - markdown heading markers and `*`/`_` emphasis markers,
 *
 * then collapses whitespace. Conservative on purpose — angle-bracket generics such
 * as `List<String>` are preserved (only the literal lowercase `<thinking>` tag is
 * recognised), so a legitimate title is left intact.
 */
object TitleSanitizer {

    // Closed reasoning block, non-greedy so multiple blocks each collapse.
    private val THINKING_BLOCK = Regex("(?is)<thinking>.*?</thinking>")

    // Unclosed/truncated reasoning block — strip the open tag and everything after it.
    private val DANGLING_THINKING = Regex("(?is)<thinking>.*$")

    // Closed fenced code block.
    private val FENCED_CODE = Regex("(?s)```.*?```")

    // Unclosed/truncated fenced code block — strip the fence and everything after it.
    private val DANGLING_FENCE = Regex("(?s)```.*$")

    // Inline code — keep the text, drop the backticks.
    private val INLINE_CODE = Regex("`([^`]*)`")

    // Leading markdown heading markers (#, ##, …).
    private val MD_HEADING = Regex("(?m)^\\s{0,3}#{1,6}\\s*")

    // Markdown emphasis / bold markers.
    private val MD_EMPHASIS = Regex("[*_]{1,3}")

    private val WHITESPACE = Regex("\\s+")

    /**
     * Sanitize a candidate title. Returns a single-line, marker-free string. May
     * return `""` when the input collapses to nothing (caller decides the fallback).
     */
    fun sanitize(raw: String): String {
        if (raw.isBlank()) return ""
        var s = raw
        s = THINKING_BLOCK.replace(s, " ")
        s = DANGLING_THINKING.replace(s, " ")
        s = FENCED_CODE.replace(s, " ")
        s = DANGLING_FENCE.replace(s, " ")
        s = INLINE_CODE.replace(s) { it.groupValues[1] }
        s = MD_HEADING.replace(s, "")
        s = MD_EMPHASIS.replace(s, "")
        s = WHITESPACE.replace(s, " ").trim()
        return s
    }
}
