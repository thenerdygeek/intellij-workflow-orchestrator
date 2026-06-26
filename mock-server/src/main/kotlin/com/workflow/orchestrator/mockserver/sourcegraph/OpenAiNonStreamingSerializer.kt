package com.workflow.orchestrator.mockserver.sourcegraph

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.putJsonObject

/**
 * Renders a SINGLE non-streaming OpenAI `chat.completion` JSON object — the shape the plugin's
 * NON-streaming callers decode via `Json.decodeFromString<ChatCompletionResponse>(body)`.
 *
 * The plugin issues `stream:false` chat/completions calls out-of-band of the agent loop — e.g.
 * `HaikuPhraseGenerator.evaluateTitleFromCompletion` (session-title generation) and branch-name
 * generation. Those callers expect a single `application/json` object, NOT SSE. Returning SSE to them
 * makes `Json.decodeFromString<ChatCompletionResponse>` choke on the leading `data:` token (the REQ-2
 * SEVERE + "IDE error" balloon). This serializer produces the canned object for that path.
 *
 * IMPORTANT: these calls are side requests and must NOT advance the active scenario's turn index — the
 * route returns this WITHOUT touching `ScenarioState`. The content here is a fixed canned message, not
 * a scenario turn.
 */
object OpenAiNonStreamingSerializer {

    /** A short, generic assistant reply for out-of-band non-streaming calls (title/branch gen). */
    const val CANNED_CONTENT: String = "Mock: generated title"

    fun serialize(
        content: String = CANNED_CONTENT,
        model: String,
        id: String,
        promptTokens: Int = 40,
        completionTokens: Int = 6,
    ): String = buildJsonObject {
        put("id", id)
        put("object", "chat.completion")
        put("created", System.currentTimeMillis() / 1000)
        put("model", model)
        putJsonArray("choices") {
            addJsonObject {
                put("index", 0)
                putJsonObject("message") {
                    put("role", "assistant")
                    put("content", content)
                }
                put("finish_reason", "stop")
            }
        }
        putJsonObject("usage") {
            put("prompt_tokens", promptTokens)
            put("completion_tokens", completionTokens)
            put("total_tokens", promptTokens + completionTokens)
        }
    }.toString()
}
