package com.workflow.orchestrator.mockserver.sourcegraph

import com.workflow.orchestrator.mockserver.sourcegraph.scenario.EngineMessage
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/**
 * Mock of Sourcegraph's LLM surface so the plugin's Agent chat runs offline.
 *
 * Routes (all under `/.api`):
 *  - `GET  /.api/llm/models`                          → model list (`ListModelsResponse`)
 *  - `GET  /.api/modelconfig/supported-models.json`   → catalog (`ModelCatalog`)
 *  - `POST /.api/llm/chat/completions`                → OpenAI SSE of the active scenario's next Turn
 *  - `POST /.api/completions/stream`                  → Cody SSE of the SAME Turn
 *
 * **Auth note for the integrator:** the shared `AuthPlugin` requires a non-blank `Authorization`
 * header on every non-`/__admin`, non-`/health` path. The plugin always sends
 * `Authorization: token <configured-token>` (any value), so these endpoints work with the existing
 * middleware as long as ANY token is configured. For truly token-less access, add
 * `|| path.startsWith("/.api")` to `AuthPlugin`'s early-return — see the return summary.
 *
 * The state is mutated in place (default-scenario + per-conversation indices), so this takes the
 * [SourcegraphState] directly rather than a `() -> State` provider lambda.
 */
fun Route.sourcegraphRoutes(state: SourcegraphState) {

    // GET /.api/llm/models — model discovery (BrainFactory / ModelCache.pickBest).
    get("/.api/llm/models") {
        call.respondText(
            buildJsonObject {
                putJsonArray("data") {
                    addJsonObject {
                        put("id", MOCK_MODEL_ID)
                        put("object", "model")
                        put("owned_by", "anthropic")
                        put("created", MOCK_MODEL_CREATED)
                    }
                }
            }.toString(),
            ContentType.Application.Json,
        )
    }

    // GET /.api/modelconfig/supported-models.json — capabilities catalog (ModelCatalogService).
    get("/.api/modelconfig/supported-models.json") {
        call.respondText(supportedModelsJson(), ContentType.Application.Json)
    }

    // POST /.api/llm/chat/completions — OpenAI-compatible completion.
    //   stream:true  → SSE of the active scenario's next Turn (advances the turn index).
    //   stream:false → SINGLE non-streaming chat.completion JSON, canned message, NO turn advancement.
    //                  (The plugin's out-of-band title/branch generation uses this path — REQ-2.)
    post("/.api/llm/chat/completions") {
        val body = parseBody(call.receiveText())
        val id = "chatcmpl-mock-${System.nanoTime()}"

        if (!isStreaming(body)) {
            // Out-of-band side request: respond with a single JSON object and do NOT touch the engine.
            call.respondText(
                OpenAiNonStreamingSerializer.serialize(model = MOCK_MODEL_ID, id = id),
                ContentType.Application.Json,
            )
            return@post
        }

        val messages = parseOpenAiMessages(body)
        val turn = state.engine.nextTurn(messages)
        call.respondTextWriter(ContentType.Text.EventStream, HttpStatusCode.OK) {
            OpenAiSseSerializer.frames(turn, MOCK_MODEL_ID, id).forEach { frame ->
                write(frame)
                flush()
            }
        }
    }

    // POST /.api/completions/stream — Cody-shape streaming completion (same Turn).
    post("/.api/completions/stream") {
        val body = parseBody(call.receiveText())
        val messages = parseCodyMessages(body)
        val turn = state.engine.nextTurn(messages)
        call.respondTextWriter(ContentType.Text.EventStream, HttpStatusCode.OK) {
            CodySseSerializer.frames(turn).forEach { frame ->
                write(frame)
                flush()
            }
        }
    }
}

/** Model id exposed by the mock — `provider::apiVersion::modelId` per BrainFactory's expectation. */
const val MOCK_MODEL_ID: String = "anthropic::2024-10-22::claude-sonnet-mock"
private const val MOCK_MODEL_CREATED: Long = 1_730_000_000L

private val LENIENT_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

private fun parseBody(raw: String): JsonObject =
    runCatching { LENIENT_JSON.parseToJsonElement(raw).jsonObject }.getOrDefault(JsonObject(emptyMap()))

/**
 * Streaming iff the request body has `"stream": true` (JSON boolean, or the string "true" defensively).
 * `false` or absent → non-streaming (REQ-2). The agent's main loop sends `stream:true`; out-of-band
 * title/branch generation sends `stream:false`.
 */
private fun isStreaming(body: JsonObject): Boolean {
    val prim = body["stream"]?.jsonPrimitive ?: return false
    return prim.booleanOrNull ?: prim.contentOrNull?.equals("true", ignoreCase = true) ?: false
}

/** Normalize an OpenAI request body's `messages:[{role,content}]` into [EngineMessage]s. */
private fun parseOpenAiMessages(body: JsonObject): List<EngineMessage> =
    body["messages"]?.jsonArrayOrNull()?.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val role = obj["role"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        EngineMessage(role, contentToText(obj["content"]))
    } ?: emptyList()

/** Normalize a Cody request body's `messages:[{speaker,content:[parts]}]` into [EngineMessage]s. */
private fun parseCodyMessages(body: JsonObject): List<EngineMessage> =
    body["messages"]?.jsonArrayOrNull()?.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val speaker = obj["speaker"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val role = if (speaker == "human") "user" else speaker
        EngineMessage(role, contentToText(obj["content"]))
    } ?: emptyList()

/** Content may be a plain string (OpenAI) or an array of `{type,text|image_url}` parts (both). */
private fun contentToText(content: JsonElement?): String = when (content) {
    null, is JsonNull -> ""
    is JsonPrimitive -> content.contentOrNull ?: ""
    is JsonArray -> content.joinToString(" ") { part ->
        (part as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull ?: ""
    }
    is JsonObject -> content["text"]?.jsonPrimitive?.contentOrNull ?: ""
}

private fun JsonElement.jsonArrayOrNull(): JsonArray? = this as? JsonArray

/** Shared catalog JSON — kept here so the route file is the single source for the mock model shape. */
private fun supportedModelsJson(): String = buildJsonObject {
    put("schemaVersion", "1.0")
    put("revision", "mock-1")
    putJsonArray("providers") {
        addJsonObject {
            put("id", "anthropic")
            put("displayName", "Anthropic")
        }
    }
    putJsonArray("models") {
        addJsonObject {
            put("modelRef", MOCK_MODEL_ID)
            put("displayName", "Claude Sonnet (Mock)")
            put("modelName", "claude-sonnet-mock")
            putJsonArray("capabilities") {
                add("chat")
                add("tools")
                add("vision")
                add("reasoning")
            }
            put("category", "balanced")
            put("status", "stable")
            put("tier", "enterprise")
            putJsonObject("contextWindow") {
                put("maxInputTokens", 132_000)
                put("maxOutputTokens", 18_000)
                put("maxUserInputTokens", 132_000)
            }
            putJsonObject("modelConfigAllTiers") {
                putJsonObject("enterprise") {
                    putJsonObject("contextWindow") {
                        put("maxInputTokens", 132_000)
                        put("maxOutputTokens", 18_000)
                    }
                }
            }
        }
    }
    putJsonObject("defaultModels") {
        put("chat", MOCK_MODEL_ID)
        put("fastChat", MOCK_MODEL_ID)
        put("codeCompletion", MOCK_MODEL_ID)
    }
}.toString()
