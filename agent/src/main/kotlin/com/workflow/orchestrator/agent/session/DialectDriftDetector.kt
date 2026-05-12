package com.workflow.orchestrator.agent.session

/**
 * Detects and redacts off-dialect tool-call XML that the LLM occasionally emits
 * when it reverts to a pretraining format other than the registered
 * `<tool_name>...</tool_name>` shape `AssistantMessageParser` expects.
 *
 * Two dialects are targeted:
 *
 * 1. **Anthropic protocol** — `<function_calls><invoke name="X"><parameter name="Y">v</parameter></invoke></function_calls>`
 *    Surfaces when Claude is served via the legacy `/.api/completions/stream`
 *    endpoint or under long-context drift.
 *
 * 2. **Hermes / generic JSON-in-XML** — `<tool_call>{"tool_name":"X", "parameters":{...}}</tool_call>`
 *    Surfaces on Mistral / Qwen / Llama derivatives and occasionally on Claude
 *    after format reinforcement decays.
 *
 * The detector is **code-fence-aware**: matches inside ```fenced``` and `inline`
 * code blocks are ignored so prose discussing the dialects (this file, audit
 * docs, the agent's own /help output) is never false-positive flagged.
 *
 * Detection patterns are deliberately narrow — only the *attribute syntax*
 * (`name="…"`) and the *JSON-immediately-after-tag* shape (`<tool_call>\s*{`)
 * are matched, because those two are essentially impossible for a human to
 * write in prose by accident while remaining unambiguous as tool-call attempts.
 *
 * **Why redact instead of translate** — translating `<invoke name="X">` to
 * `<X>` in history would make the bad turn look successful to the next LLM
 * call, re-anchoring the dialect via in-context learning (JetBrains Koog
 * report: "structure beats instructions"). A redaction marker breaks the
 * mimicry chain by giving the model nothing format-like to copy.
 *
 * Spec: `docs/research/2026-05-12-dialect-contamination-research.md`
 */
object DialectDriftDetector {

    /** Anthropic `<invoke name="X" …>` — the `name="..."` attribute is the unique tell. */
    private val ANTHROPIC_INVOKE = Regex(
        "<invoke\\s+name\\s*=\\s*\"[^\"]+\""
    )

    /** Anthropic `<function_calls>` wrapper — only counts when followed by a `<` (i.e., has nested tags). */
    private val ANTHROPIC_FUNCTION_CALLS = Regex(
        "<function_calls>\\s*<"
    )

    /** Hermes `<tool_call>{json}</tool_call>` — the `<tool_call>` tag immediately followed by `{` is distinctive. */
    private val HERMES_TOOL_CALL = Regex(
        "<tool_call>\\s*\\{"
    )

    /** Triple-backtick fenced code blocks (non-greedy, multiline). */
    private val TRIPLE_FENCE = Regex("```[\\s\\S]*?```")

    /** Inline backtick spans (single-line). */
    private val INLINE_CODE = Regex("`[^`\\n]+`")

    /**
     * Detects dialect drift outside of code blocks. Inside `prose` only;
     * `code fences` are stripped before matching so the agent can discuss
     * the formats without false-positive flagging.
     */
    fun hasDialectMarker(text: String): Boolean {
        if (text.isEmpty()) return false
        val proseOnly = stripCodeBlocks(text)
        return ANTHROPIC_INVOKE.containsMatchIn(proseOnly) ||
            ANTHROPIC_FUNCTION_CALLS.containsMatchIn(proseOnly) ||
            HERMES_TOOL_CALL.containsMatchIn(proseOnly)
    }

    /**
     * Redacts dialect XML spans, replacing each with a single marker line.
     * Returns the (possibly-rewritten) text and whether anything changed.
     *
     * Redaction is span-based: only the dialect block itself is replaced,
     * surrounding prose is preserved.
     */
    fun redactDialectMarkers(text: String): RedactionResult {
        if (text.isEmpty()) return RedactionResult(text, modified = false)

        // Snapshot the code-block spans so we don't accidentally redact inside them
        val protectedRanges = collectCodeBlockRanges(text)

        var result = text
        var modified = false

        // Replace anything from `<function_calls>` through its closing tag (broadest container first)
        result = replaceOutsideRanges(
            result,
            Regex("<function_calls>[\\s\\S]*?</function_calls>", RegexOption.IGNORE_CASE),
            REDACTION_MARKER,
            protectedRanges,
        ) { wasReplaced -> if (wasReplaced) modified = true }

        // Then bare `<invoke name="…">…</invoke>` blocks the model emitted without the wrapper
        result = replaceOutsideRanges(
            result,
            Regex("<invoke\\s+name\\s*=\\s*\"[^\"]+\"[\\s\\S]*?</invoke>", RegexOption.IGNORE_CASE),
            REDACTION_MARKER,
            protectedRanges,
        ) { wasReplaced -> if (wasReplaced) modified = true }

        // Hermes `<tool_call>{…}</tool_call>` blocks
        result = replaceOutsideRanges(
            result,
            Regex("<tool_call>\\s*\\{[\\s\\S]*?\\}\\s*</tool_call>", RegexOption.IGNORE_CASE),
            REDACTION_MARKER,
            protectedRanges,
        ) { wasReplaced -> if (wasReplaced) modified = true }

        return RedactionResult(result, modified = modified)
    }

    data class RedactionResult(val text: String, val modified: Boolean)

    // ---- internals ----

    private fun stripCodeBlocks(text: String): String {
        return INLINE_CODE.replace(TRIPLE_FENCE.replace(text, ""), "")
    }

    private fun collectCodeBlockRanges(text: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        TRIPLE_FENCE.findAll(text).forEach { ranges.add(it.range) }
        INLINE_CODE.findAll(text).forEach { ranges.add(it.range) }
        return ranges
    }

    private fun replaceOutsideRanges(
        text: String,
        pattern: Regex,
        replacement: String,
        protectedRanges: List<IntRange>,
        onMatch: (Boolean) -> Unit,
    ): String {
        if (!pattern.containsMatchIn(text)) {
            onMatch(false)
            return text
        }
        val sb = StringBuilder()
        var cursor = 0
        var anyReplaced = false
        for (match in pattern.findAll(text)) {
            if (protectedRanges.any { match.range.first in it && match.range.last in it }) continue
            sb.append(text, cursor, match.range.first)
            sb.append(replacement)
            cursor = match.range.last + 1
            anyReplaced = true
        }
        sb.append(text, cursor, text.length)
        onMatch(anyReplaced)
        return if (anyReplaced) sb.toString() else text
    }

    const val REDACTION_MARKER =
        "[redacted: previous tool attempt used an incompatible XML format and was discarded — see system reminder]"
}
