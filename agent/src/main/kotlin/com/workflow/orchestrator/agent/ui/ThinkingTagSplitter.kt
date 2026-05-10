package com.workflow.orchestrator.agent.ui

/**
 * Splits a streaming assistant text into prose vs `<thinking>...</thinking>` blocks
 * so the dashboard can route prose through the regular token batcher and route
 * thinking content live through `appendToThinking` / `endThinking` (rendered by the
 * webview as a streaming prompt-kit `<Reasoning>` collapsible).
 *
 * Stateful across calls — holds a tail buffer of bytes that could be the prefix
 * of an opening or closing tag (e.g. `<thi` arrives at the end of one chunk and
 * `nking>` arrives in the next), so partial tags never leak as literal text.
 *
 * Tag matching is exact-bytes lowercase (`<thinking>` and `</thinking>`).
 * Anything that looks like the tag with attributes or different casing falls
 * through as plain text — that is the safer default; the system prompt
 * (`SystemPrompt.kt:791`) instructs the LLM to use the bare lowercase form.
 *
 * **Streaming contract.** Bytes inside a thinking block are emitted as
 * `Part.ThinkingDelta` incrementally as they arrive — the same cadence as
 * `Part.Text` for prose — so the webview can render the reasoning live with a
 * shimmer. A single `Part.ThinkingEnd` marks the close of each non-empty block
 * (suppressed for empty `<thinking></thinking>` to match the prior contract).
 * On [flush], any held-back partial bytes are emitted as a final delta + end
 * (better to surface partial reasoning than swallow it).
 */
class ThinkingTagSplitter {

    sealed interface Part {
        @JvmInline value class Text(val text: String) : Part
        @JvmInline value class ThinkingDelta(val text: String) : Part
        data object ThinkingEnd : Part
    }

    private val pending = StringBuilder()
    private var inThinking = false
    private var hasEmittedThinkingDelta = false

    /**
     * Feed the next stream chunk. Returns ordered parts that are safe to emit
     * downstream. Bytes that could be the prefix of an open/close tag are held
     * back for the next call.
     */
    fun consume(chunk: String): List<Part> {
        if (chunk.isEmpty()) return emptyList()
        pending.append(chunk)
        val parts = mutableListOf<Part>()
        while (true) {
            val target = if (inThinking) CLOSE else OPEN
            val idx = pending.indexOf(target)
            if (idx >= 0) {
                val before = pending.substring(0, idx)
                if (inThinking) {
                    if (before.isNotEmpty()) {
                        parts.add(Part.ThinkingDelta(before))
                        hasEmittedThinkingDelta = true
                    }
                    if (hasEmittedThinkingDelta) parts.add(Part.ThinkingEnd)
                    hasEmittedThinkingDelta = false
                    inThinking = false
                } else {
                    if (before.isNotEmpty()) parts.add(Part.Text(before))
                    inThinking = true
                }
                pending.delete(0, idx + target.length)
                continue
            }
            // No complete tag — emit what's safe, hold back any tail bytes that
            // might be the start of the target tag.
            val partialLen = partialPrefixLength(pending, target)
            val emitLen = pending.length - partialLen
            if (emitLen > 0) {
                val text = pending.substring(0, emitLen)
                if (inThinking) {
                    parts.add(Part.ThinkingDelta(text))
                    hasEmittedThinkingDelta = true
                } else {
                    parts.add(Part.Text(text))
                }
                pending.delete(0, emitLen)
            }
            break
        }
        return parts
    }

    /**
     * Flush any held-back state at stream-end. Trailing partial-tag bytes are
     * treated as content for the current state. An unclosed thinking block is
     * still surfaced (delta + end) — better to render the user's reasoning than
     * swallow it.
     */
    fun flush(): List<Part> {
        val parts = mutableListOf<Part>()
        if (pending.isNotEmpty()) {
            if (inThinking) {
                parts.add(Part.ThinkingDelta(pending.toString()))
                hasEmittedThinkingDelta = true
            } else {
                parts.add(Part.Text(pending.toString()))
            }
            pending.setLength(0)
        }
        if (inThinking) {
            if (hasEmittedThinkingDelta) parts.add(Part.ThinkingEnd)
            hasEmittedThinkingDelta = false
            inThinking = false
        }
        return parts
    }

    /** Reset all state — used on new task / cancel / clear. */
    fun reset() {
        pending.setLength(0)
        inThinking = false
        hasEmittedThinkingDelta = false
    }

    /**
     * Length of the longest suffix of [buf] that is also a prefix of [target].
     * Used to hold back potential tag-start bytes across chunk boundaries.
     */
    private fun partialPrefixLength(buf: CharSequence, target: String): Int {
        val maxCheck = minOf(buf.length, target.length - 1)
        outer@ for (len in maxCheck downTo 1) {
            for (i in 0 until len) {
                if (buf[buf.length - len + i] != target[i]) continue@outer
            }
            return len
        }
        return 0
    }

    companion object {
        private const val OPEN = "<thinking>"
        private const val CLOSE = "</thinking>"
    }
}
