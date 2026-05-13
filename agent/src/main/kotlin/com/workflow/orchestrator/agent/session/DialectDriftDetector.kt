package com.workflow.orchestrator.agent.session

import com.workflow.orchestrator.core.ai.AssistantMessageParser

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
 * The detector is also **canonical-tool-boundary-aware**: values of code-carrying
 * parameter tags (`content`, `new_string`, `old_string`, `diff`, `code`) that
 * appear inside a recognised canonical tool call block (`<word>…</word>` where
 * `word` is not a known dialect-marker tag name) are stripped before regex
 * matching and treated as protected ranges during redaction. This prevents
 * corruption when the model writes files or diffs that legitimately reference
 * dialect-shaped strings (e.g. documentation about function-calling formats).
 * Uses the same set as [AssistantMessageParser.CODE_CARRYING_PARAMS].
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

    /** Generic `<tool>` wrapper — flagged only when followed by content (not empty `<tool/>`). */
    private val GENERIC_TOOL = Regex("<tool>\\s*[^<\\s]")

    /** Generic `<tool_use>` wrapper — same shape, distinct from Hermes `<tool_call>`. */
    private val GENERIC_TOOL_USE = Regex("<tool_use>\\s*<")

    /** Generic `<function>` wrapper. */
    private val GENERIC_FUNCTION = Regex("<function>\\s*[^<\\s]")

    /** Generic `<function_use>` wrapper. */
    private val GENERIC_FUNCTION_USE = Regex("<function_use>\\s*<")

    /** Anthropic singular `<function_call name="...">` (cousin of `<invoke name=...>`). */
    private val ANTHROPIC_FUNCTION_CALL_SINGULAR = Regex("<function_call\\s+name\\s*=\\s*\"[^\"]+\"")

    /** Triple-backtick fenced code blocks (non-greedy, multiline). */
    private val TRIPLE_FENCE = Regex("```[\\s\\S]*?```")

    /** Inline backtick spans (single-line). */
    private val INLINE_CODE = Regex("`[^`\\n]+`")

    /**
     * Tag names that are known dialect-marker tags and must NOT be treated
     * as canonical tool wrappers in [collectCanonicalCodeParamRanges].
     */
    private val DIALECT_TAG_NAMES = setOf(
        "tool_call", "tool_use", "function", "function_use",
        "invoke", "function_call", "function_calls", "tool"
    )

    /**
     * Matches any `<word>…</word>` pair where `word` is a lowercase Latin word
     * with underscores (canonical tool name shape). Non-greedy inner content.
     * Used to find candidate canonical tool call blocks.
     */
    private val CANONICAL_TOOL_BLOCK = Regex("<([a-z_]+)>([\\s\\S]*?)</\\1>")

    /**
     * Detects dialect drift outside of code blocks and outside canonical
     * code-carrying param values. `code fences` are stripped before matching
     * so the agent can discuss the formats without false-positive flagging.
     * Values of code-carrying params inside canonical tool call blocks are
     * also stripped — see [stripCanonicalCodeParams].
     */
    fun hasDialectMarker(text: String): Boolean {
        if (text.isEmpty()) return false
        val proseOnly = stripCodeBlocks(stripCanonicalCodeParams(text))
        return ANTHROPIC_INVOKE.containsMatchIn(proseOnly) ||
            ANTHROPIC_FUNCTION_CALLS.containsMatchIn(proseOnly) ||
            ANTHROPIC_FUNCTION_CALL_SINGULAR.containsMatchIn(proseOnly) ||
            HERMES_TOOL_CALL.containsMatchIn(proseOnly) ||
            GENERIC_TOOL.containsMatchIn(proseOnly) ||
            GENERIC_TOOL_USE.containsMatchIn(proseOnly) ||
            GENERIC_FUNCTION.containsMatchIn(proseOnly) ||
            GENERIC_FUNCTION_USE.containsMatchIn(proseOnly)
    }

    /**
     * Redacts dialect XML spans, replacing each with a single marker line.
     * Returns the (possibly-rewritten) text and whether anything changed.
     *
     * Redaction is span-based: only the dialect block itself is replaced,
     * surrounding prose is preserved.
     *
     * Protected ranges include both markdown code fences and the values of
     * code-carrying params inside canonical tool call blocks (via
     * [collectCanonicalCodeParamRanges]) — so dialect substrings that appear
     * inside `<content>`, `<diff>`, `<old_string>`, `<new_string>`, or `<code>`
     * within a canonical tool call are never redacted.
     */
    fun redactDialectMarkers(text: String): RedactionResult {
        if (text.isEmpty()) return RedactionResult(text, modified = false)

        // Snapshot the code-block spans AND canonical code-param spans so we
        // don't accidentally redact inside either protected region
        val protectedRanges = collectCodeBlockRanges(text) + collectCanonicalCodeParamRanges(text)

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

        // Anthropic singular `<function_call name="...">…</function_call>` blocks
        result = replaceOutsideRanges(result, Regex("<function_call\\s+name\\s*=\\s*\"[^\"]+\"[\\s\\S]*?</function_call>", RegexOption.IGNORE_CASE), REDACTION_MARKER, protectedRanges) { if (it) modified = true }

        // Generic `<tool>…</tool>` blocks
        result = replaceOutsideRanges(result, Regex("<tool>[\\s\\S]*?</tool>", RegexOption.IGNORE_CASE), REDACTION_MARKER, protectedRanges) { if (it) modified = true }

        // Generic `<tool_use>…</tool_use>` blocks
        result = replaceOutsideRanges(result, Regex("<tool_use>[\\s\\S]*?</tool_use>", RegexOption.IGNORE_CASE), REDACTION_MARKER, protectedRanges) { if (it) modified = true }

        // Generic `<function>…</function>` blocks
        result = replaceOutsideRanges(result, Regex("<function>[\\s\\S]*?</function>", RegexOption.IGNORE_CASE), REDACTION_MARKER, protectedRanges) { if (it) modified = true }

        // Generic `<function_use>…</function_use>` blocks
        result = replaceOutsideRanges(result, Regex("<function_use>[\\s\\S]*?</function_use>", RegexOption.IGNORE_CASE), REDACTION_MARKER, protectedRanges) { if (it) modified = true }

        return RedactionResult(result, modified = modified)
    }

    data class RedactionResult(val text: String, val modified: Boolean)

    // ---- internals ----

    private fun stripCodeBlocks(text: String): String {
        return INLINE_CODE.replace(TRIPLE_FENCE.replace(text, ""), "")
    }

    /**
     * Strips the values of code-carrying param tags that appear inside a
     * recognised canonical tool call block, replacing each value with an
     * empty string. This prevents dialect patterns embedded in file content
     * (e.g. `<content>` of a `create_file` call) from triggering the regex
     * matchers in [hasDialectMarker].
     *
     * A canonical tool block is any `<word>…</word>` pair where `word` is a
     * lowercase Latin + underscore name that is NOT in [DIALECT_TAG_NAMES].
     * Inside such a block, the values of [AssistantMessageParser.CODE_CARRYING_PARAMS]
     * tags are blanked.
     *
     * Order of application: `text → stripCanonicalCodeParams → stripCodeBlocks → regex`.
     */
    private fun stripCanonicalCodeParams(text: String): String {
        // Collect all protected value ranges first, then apply replacements in
        // reverse order so that earlier offsets remain valid as the string shrinks.
        val valuesToBlank = mutableListOf<IntRange>()
        for (toolMatch in CANONICAL_TOOL_BLOCK.findAll(text)) {
            val toolName = toolMatch.groupValues[1]
            // Skip known dialect tags
            if (toolName in DIALECT_TAG_NAMES) continue
            // Also skip code-carrying param names: a bare <content>…</content> block
            // floating in prose is NOT a canonical tool call — only when <content>
            // is a child of a real tool wrapper does protection apply.
            if (toolName in AssistantMessageParser.CODE_CARRYING_PARAMS) continue
            for (paramName in AssistantMessageParser.CODE_CARRYING_PARAMS) {
                val escapedParam = Regex.escape(paramName)
                val paramPattern = Regex("<$escapedParam>([\\s\\S]*?)</$escapedParam>")
                for (paramMatch in paramPattern.findAll(text)) {
                    if (paramMatch.range.first >= toolMatch.range.first &&
                        paramMatch.range.last <= toolMatch.range.last
                    ) {
                        valuesToBlank.add(paramMatch.groups[1]!!.range)
                    }
                }
            }
        }
        if (valuesToBlank.isEmpty()) return text
        // Apply in reverse order to preserve offsets
        var result = text
        for (range in valuesToBlank.sortedByDescending { it.first }) {
            result = result.replaceRange(range, "")
        }
        return result
    }

    /**
     * Collects the character ranges of code-carrying param VALUES that sit
     * inside recognised canonical tool call blocks. These ranges are added to
     * the protected list in [redactDialectMarkers] so [replaceOutsideRanges]
     * skips matches inside them.
     *
     * Uses the same canonical-tool detection as [stripCanonicalCodeParams].
     */
    private fun collectCanonicalCodeParamRanges(text: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        for (toolMatch in CANONICAL_TOOL_BLOCK.findAll(text)) {
            val toolName = toolMatch.groupValues[1]
            if (toolName in DIALECT_TAG_NAMES) continue
            // Same guard as stripCanonicalCodeParams: bare code-carrying param tags
            // are NOT canonical tool wrappers.
            if (toolName in AssistantMessageParser.CODE_CARRYING_PARAMS) continue
            for (paramName in AssistantMessageParser.CODE_CARRYING_PARAMS) {
                val escapedParam = Regex.escape(paramName)
                val paramPattern = Regex("<$escapedParam>([\\s\\S]*?)</$escapedParam>")
                for (paramMatch in paramPattern.findAll(text)) {
                    if (paramMatch.range.first >= toolMatch.range.first &&
                        paramMatch.range.last <= toolMatch.range.last
                    ) {
                        // Protect the entire param tag (including open/close tags)
                        // so patterns that span the opening tag boundary are also caught
                        ranges.add(paramMatch.range)
                    }
                }
            }
        }
        return ranges
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
