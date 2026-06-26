package com.workflow.orchestrator.mockserver.sourcegraph

import com.workflow.orchestrator.mockserver.sourcegraph.scenario.Turn
import com.workflow.orchestrator.mockserver.sourcegraph.scenario.contentFragments
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Renders one [Turn] to the OpenAI-compatible SSE shape consumed by `SourcegraphChatClient`
 * (`POST /.api/llm/chat/completions`, `stream:true`).
 *
 * The plugin accumulates `choices[].delta.content` and extracts tool calls from that text via
 * `AssistantMessageParser` (XML-in-content). So tool calls are emitted as XML inside `delta.content`;
 * native `delta.tool_calls` frames are ALSO emitted to match a real Sourcegraph gateway's documented
 * shape, but the current plugin ignores them. `finish_reason` and a final `usage` chunk follow, then
 * the `data: [DONE]` sentinel.
 */
object OpenAiSseSerializer {

    /** Each element is a complete SSE event (ends with a blank line); the route flushes per event. */
    fun frames(turn: Turn, model: String, id: String): List<String> {
        val created = System.currentTimeMillis() / 1000
        val frames = mutableListOf<String>()

        fun dataFrame(buildChoicesAndUsage: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String {
            val obj = buildJsonObject {
                put("id", id)
                put("object", "chat.completion.chunk")
                put("created", created)
                put("model", model)
                buildChoicesAndUsage()
            }
            return "data: $obj\n\n"
        }

        // 1. Opening frame establishes the assistant role.
        frames += dataFrame {
            putJsonArray("choices") {
                addJsonObject {
                    put("index", 0)
                    putJsonObject("delta") { put("role", "assistant") }
                    put("finish_reason", JsonNull)
                }
            }
        }

        // 2. Text / thinking / tool-call XML — one content chunk per fragment.
        turn.contentFragments().forEach { fragment ->
            frames += dataFrame {
                putJsonArray("choices") {
                    addJsonObject {
                        put("index", 0)
                        putJsonObject("delta") { put("content", fragment) }
                        put("finish_reason", JsonNull)
                    }
                }
            }
        }

        // 3. Native tool_calls frames (belt-and-suspenders; current plugin parses the XML above).
        turn.toolCalls.forEachIndexed { i, tc ->
            frames += dataFrame {
                putJsonArray("choices") {
                    addJsonObject {
                        put("index", 0)
                        putJsonObject("delta") {
                            putJsonArray("tool_calls") {
                                addJsonObject {
                                    put("index", i)
                                    put("id", "call_mock_$i")
                                    put("type", "function")
                                    putJsonObject("function") {
                                        put("name", tc.name)
                                        put("arguments", tc.argumentsJson)
                                    }
                                }
                            }
                        }
                        put("finish_reason", JsonNull)
                    }
                }
            }
        }

        // 4. Finish frame.
        frames += dataFrame {
            putJsonArray("choices") {
                addJsonObject {
                    put("index", 0)
                    putJsonObject("delta") {}
                    put("finish_reason", turn.finishReason)
                }
            }
        }

        // 5. Final usage frame (OpenAI sends usage with empty choices when stream_options.include_usage).
        frames += dataFrame {
            putJsonArray("choices") {}
            putJsonObject("usage") {
                put("prompt_tokens", turn.usage.promptTokens)
                put("completion_tokens", turn.usage.completionTokens)
                put("total_tokens", turn.usage.totalTokens)
            }
        }

        // 6. Sentinel.
        frames += "data: [DONE]\n\n"
        return frames
    }

    /** Convenience: the full SSE body as one string (used by tests). */
    fun serialize(turn: Turn, model: String, id: String = "chatcmpl-mock"): String =
        frames(turn, model, id).joinToString("")
}
