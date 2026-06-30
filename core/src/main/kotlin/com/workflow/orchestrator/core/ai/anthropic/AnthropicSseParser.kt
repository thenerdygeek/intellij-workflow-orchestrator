package com.workflow.orchestrator.core.ai.anthropic

import com.workflow.orchestrator.core.ai.protocol.AnthropicNativeProtocol
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Pure (no OkHttp) line-by-line SSE state machine for the Anthropic Messages streaming API.
 *
 * Phase 4a Task 6. Consumes a [Sequence<String>] of raw SSE lines and drives [parse]'s
 * [emitText] callback for each piece of content. Tool-use input JSON is accumulated
 * per block index and serialized via [ToolUseXmlSerializer] into canonical XML at
 * `message_stop`. Thinking blocks are wrapped in one contiguous `<thinking>…</thinking>`
 * span per block (open on first delta, close on `content_block_stop`).
 *
 * Event routing:
 *  - `text_delta`         → `emitText(delta.text)`
 *  - `thinking_delta`     → open `<thinking>` tag on first delta; emit raw text;
 *                           close at `content_block_stop`
 *  - `input_json_delta`   → accumulate `partial_json` per block index
 *  - `message_delta`      → capture `stop_reason` + `usage.output_tokens`
 *  - `message_stop`       → emit `ToolUseXmlSerializer.toXml` for each tool-use
 *  - `event: error`       → classify via [AnthropicNativeProtocol.classifyStreamLine]
 *  - `ping`, `message_start`, `signature_delta`, unknown → ignored gracefully
 */
object AnthropicSseParser {

    /**
     * Result returned by [parse].
     *
     * @property finishReason  Anthropic `stop_reason` from `message_delta`
     *                         (e.g. `"tool_use"`, `"end_turn"`); null if the
     *                         stream ended without a `message_delta`.
     * @property usageOutputTokens  Output token count from `message_delta.usage`.
     * @property errorClass  Inner `error.type` from an `event: error` frame
     *                       (e.g. `"overloaded_error"`); null when no error seen.
     */
    data class Result(
        val finishReason: String?,
        val usageOutputTokens: Int,
        val errorClass: String?,
    )

    // ── Internal DTOs ──────────────────────────────────────────────────────────

    @Serializable
    private data class SseFrame(
        val type: String = "",
        val index: Int? = null,
        @SerialName("content_block") val contentBlock: SseContentBlock? = null,
        val delta: SseDelta? = null,
        val usage: SseUsage? = null,
    )

    @Serializable
    private data class SseContentBlock(
        val type: String = "",
        val id: String? = null,
        val name: String? = null,
    )

    @Serializable
    private data class SseDelta(
        val type: String = "",
        val text: String? = null,
        val thinking: String? = null,
        @SerialName("stop_reason") val stopReason: String? = null,
        @SerialName("partial_json") val partialJson: String? = null,
    )

    @Serializable
    private data class SseUsage(
        @SerialName("output_tokens") val outputTokens: Int? = null,
    )

    private data class ToolUseState(val name: String, val json: StringBuilder)

    private val LENIENT = Json { ignoreUnknownKeys = true }
    private val PROTOCOL = AnthropicNativeProtocol()

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Parse a sequence of raw SSE lines emitted by the Anthropic Messages API.
     *
     * Each SSE message is an `event: X` line followed by a `data: {...}` line.
     * Blank lines and unrecognised prefixes are ignored gracefully.
     *
     * @param lines    Raw SSE lines (this method strips `event: ` / `data: ` prefixes).
     * @param emitText Callback invoked for each chunk of content: prose text, thinking
     *                 block open/close wrappers, thinking text, and serialized XML for
     *                 tool-use blocks.
     * @return [Result] with finishReason, output-token count, and optional errorClass.
     */
    fun parse(lines: Sequence<String>, emitText: (String) -> Unit): Result {
        var currentEvent: String? = null

        // Indices of blocks whose type is "thinking".
        val thinkingBlocks = mutableSetOf<Int>()
        // Indices of thinking blocks for which we have emitted the opening <thinking> tag.
        val thinkingOpen = mutableSetOf<Int>()

        // Accumulated state for each tool-use block, keyed by content_block index.
        val toolUseBlocks = mutableMapOf<Int, ToolUseState>()

        var finishReason: String? = null
        var usageOutputTokens = 0
        var errorClass: String? = null

        for (raw in lines) {
            when {
                raw.startsWith("event: ") -> currentEvent = raw.removePrefix("event: ").trim()
                raw.startsWith("data: ") -> processDataLine(
                    event = currentEvent,
                    data = raw.removePrefix("data: ").trim(),
                    emitText = emitText,
                    thinkingBlocks = thinkingBlocks,
                    thinkingOpen = thinkingOpen,
                    toolUseBlocks = toolUseBlocks,
                    onFinishReason = { finishReason = it },
                    onOutputTokens = { usageOutputTokens = it },
                    onErrorClass = { errorClass = it },
                )
            }
        }

        return Result(finishReason, usageOutputTokens, errorClass)
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    @Suppress("LongParameterList")
    private fun processDataLine(
        event: String?,
        data: String,
        emitText: (String) -> Unit,
        thinkingBlocks: MutableSet<Int>,
        thinkingOpen: MutableSet<Int>,
        toolUseBlocks: MutableMap<Int, ToolUseState>,
        onFinishReason: (String) -> Unit,
        onOutputTokens: (Int) -> Unit,
        onErrorClass: (String?) -> Unit,
    ) {
        when (event) {
            "content_block_start" -> handleBlockStart(data, thinkingBlocks, toolUseBlocks)
            "content_block_delta" -> handleBlockDelta(data, emitText, thinkingBlocks, thinkingOpen, toolUseBlocks)
            "content_block_stop" -> handleBlockStop(data, emitText, thinkingOpen)
            "message_delta" -> handleMessageDelta(data, onFinishReason, onOutputTokens)
            "message_stop" -> handleMessageStop(toolUseBlocks, emitText)
            "error" -> onErrorClass(PROTOCOL.classifyStreamLine(data))
            // ping, message_start, signature_delta, unknown → ignored
        }
    }

    private fun handleBlockStart(
        data: String,
        thinkingBlocks: MutableSet<Int>,
        toolUseBlocks: MutableMap<Int, ToolUseState>,
    ) {
        decodeOrNull<SseFrame>(data)?.let { frame ->
            val idx = frame.index ?: return
            val block = frame.contentBlock ?: return
            when (block.type) {
                "thinking" -> thinkingBlocks.add(idx)
                "tool_use" -> block.name?.let { name ->
                    toolUseBlocks[idx] = ToolUseState(name, StringBuilder())
                }
            }
        }
    }

    private fun handleBlockDelta(
        data: String,
        emitText: (String) -> Unit,
        thinkingBlocks: MutableSet<Int>,
        thinkingOpen: MutableSet<Int>,
        toolUseBlocks: MutableMap<Int, ToolUseState>,
    ) {
        decodeOrNull<SseFrame>(data)?.let { frame ->
            val idx = frame.index ?: return
            val delta = frame.delta ?: return
            when (delta.type) {
                "text_delta" -> emitText(delta.text ?: "")
                "thinking_delta" -> {
                    if (idx in thinkingBlocks && idx !in thinkingOpen) {
                        emitText("<thinking>")
                        thinkingOpen.add(idx)
                    }
                    emitText(delta.thinking ?: "")
                }
                "input_json_delta" ->
                    toolUseBlocks[idx]?.json?.append(delta.partialJson ?: "")
            }
        }
    }

    private fun handleBlockStop(
        data: String,
        emitText: (String) -> Unit,
        thinkingOpen: MutableSet<Int>,
    ) {
        decodeOrNull<SseFrame>(data)?.let { frame ->
            val idx = frame.index ?: return
            if (idx in thinkingOpen) {
                emitText("</thinking>")
                thinkingOpen.remove(idx)
            }
        }
    }

    private fun handleMessageDelta(
        data: String,
        onFinishReason: (String) -> Unit,
        onOutputTokens: (Int) -> Unit,
    ) {
        decodeOrNull<SseFrame>(data)?.let { frame ->
            frame.delta?.stopReason?.let(onFinishReason)
            frame.usage?.outputTokens?.let(onOutputTokens)
        }
    }

    private fun handleMessageStop(
        toolUseBlocks: Map<Int, ToolUseState>,
        emitText: (String) -> Unit,
    ) {
        for ((_, state) in toolUseBlocks) {
            runCatching {
                val parsed = LENIENT.parseToJsonElement(state.json.toString()).jsonObject
                emitText(ToolUseXmlSerializer.toXml(state.name, parsed))
            }
        }
    }

    private inline fun <reified T> decodeOrNull(json: String): T? = runCatching {
        LENIENT.decodeFromJsonElement<T>(LENIENT.parseToJsonElement(json))
    }.getOrNull()
}
