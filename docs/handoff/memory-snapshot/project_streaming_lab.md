---
name: Streaming Lab — results analyzed, 16 changes planned
description: Lab results from docs/Result_1/ fully analyzed (2026-04-10). 4 decisions answered. 16-item change list prioritized (Cline ports first). Re-enable streaming YES. XML tool port YES. Parallel native BROKEN. Tool result format already correct.
type: project
originSessionId: 5982a514-c1d3-4b52-9dc7-1ff3ffd44ffe
---
## Lab Results (analyzed 2026-04-10)

Results at `docs/Result_1/streaming_lab_results.json` + `docs/Result_1/raw_sse/`.
Model tested: `anthropic::2024-10-22::claude-sonnet-4-5-latest`. 75 scenario outcomes + 20 probes + 7 advanced probes.

## 4 Decisions — ANSWERED

### 1. Re-enable streaming? YES
- TTFB ratio: **0.23** (streaming TTFB 929ms vs non-streaming total 3974ms) — prevents 120s timeout
- Pregeneration silence: tiny 1403ms, medium 1256ms, large (8K chars) 1764ms — all well under timeout
- Reliability: 5/5 runs got content + usage. `[DONE]` never sent (code already handles via socket close)
- Max inter-chunk gap: 1.3s (500-line code) — no timeout risk
- **WARNING**: NO_KEEPALIVE confirmed. Lab only tested up to 8K prompt chars. Production prompts (50-150K tokens) may have longer pre-generation silence. Consider increasing OkHttp read timeout to 180-240s.

### 2. Tool result format? ALREADY CORRECT
- `role: tool` → HTTP 400 (confirmed)
- Anthropic `tool_result` content blocks → HTTP 500 (ALL rejected)
- **Format C (user msg with "TOOL RESULT:\n..." prefix) → WORKS** (recommended)
- Current `sanitizeMessages()` already does exactly this at `SourcegraphChatClient.kt:166-172`
- Multi-turn round-trip verified: step 2b (user-msg fallback) works

### 3. Cline XML port viable? YES — and MORE RELIABLE than native
- XML large content streaming: 50-line and 150-line both COMPLETE_XML, no corruption
- Mode B (XML) score: **22/25** pass vs Mode A (native): **17/25** pass vs Mode C (JSON): **15/25** pass
- Mode B handles ALL parallel counts (2, 3, 5) perfectly — Mode A fails all of them
- Mode B failures: only_tool_no_text, think_then_tool, xml_write_small_file (3 total, xml_write_small_file fails all modes)
- Truncation: Mode B gets `TRUNCATED_BEFORE_XML_STARTED` — needs Cline's `partial: true` handling

### 4. Safe parallel tool call count? ZERO for native, ANY for XML
- Native (Mode A): concat JSON bug at ALL counts (2, 3, 4, 5). Only 1st tool recovered.
- XML (Mode B): All counts pass perfectly. No limit found.
- Current workaround at `SourcegraphChatClient.kt:390` keeps only 1st of N tools — rest silently lost.

## 16 Changes to Adapt (Cline ports first)

### Cline Direct Ports
| # | Change | Cline Source | Effort |
|---|---|---|---|
| 1 | XML tool definitions in system prompt (replace `tools: [...]`) | `system.ts` tool sections | Medium |
| 2 | Streaming XML parser (`parseAssistantMessageV2`) | `src/core/assistant-message/` | High |
| 3 | Partial tool call detection (`partial: true`/`false`) | assistant message parser | Low |
| 4 | `lastIndexOf` for `</content>` detection | `write_to_file` parser | Low |

### Already Ported (no changes needed)
| # | What | Status |
|---|---|---|
| 5 | Tool result as user message (Format C) | Already in `sanitizeMessages()` |
| 6 | `\u200B` zero-width space for empty assistant content | Already implemented |
| 7 | System role → user message with XML wrapper | Already implemented |
| 8 | Consecutive same-role message merging | Already implemented |
| 9 | `finish_reason: tool_calls` + 0 deltas fallback | Already implemented |

### Our Own Adaptations
| # | Change | Effort |
|---|---|---|
| 10 | Re-enable streaming in `OpenAiCompatBrain.chatStream()` | Low |
| 11 | Stream termination: use usage chunk as signal, not `[DONE]` | Low |
| 12 | Tool definitions as compression-proof anchor in `EventSourcedContextBridge` | Low |
| 13 | Increase OkHttp read timeout (180-240s) for no-keepalive safety | Low |
| 14 | Drop `tools: [...]` from request body when using XML mode | Low (comes with #1) |
| 15 | Remove `ToolCallBuilder` accumulator and `}{` concat-bug workaround | Low (cleanup) |
| 16 | System prompt instruction for think-then-tool pattern | Low |

### Execution order
1. Item 10 (re-enable streaming) — independent, ships alone, fixes timeout bug
2. Items 1-4 + 14 + 16 (Cline XML port) — single coherent effort
3. Items 11-13 (hardening) — after streaming re-enabled
4. Item 15 (cleanup) — after XML port complete

## Updated Gateway Constraints (from lab)

| Feature | Status | New? |
|---|---|---|
| Anthropic `tool_result` content blocks | **REJECTED** HTTP 500 | NEW |
| Native parallel tool calls | **BROKEN** (concat JSON bug at all counts) | NEW |
| `[DONE]` SSE sentinel | **NEVER SENT** | NEW |
| `top_k` | **WORKS** (silently accepted) | NEW |
| Available models | 6 (sonnet/opus/haiku 4.5 + thinking variants) | NEW |
| Prompt caching (`cache_control`) | **NOT FUNCTIONAL** (cached_tokens always 0) | Confirmed |
| Thinking/reasoning (all 8 strategies) | **SILENTLY DROPPED** | Confirmed |
| Vision/images | **SILENTLY STRIPPED** | Confirmed |

## Things confirmed unavailable (don't invest in)
Prompt caching, thinking/reasoning, vision/images, `response_format: json_object`, `seed` determinism, `n > 1`, `service_tier`, Anthropic tool_result content blocks.

## Key files
- `docs/Result_1/streaming_lab_results.json` — full structured results
- `docs/Result_1/raw_sse/` — 75 raw SSE captures with timestamps
- `tools/sourcegraph-probe/streaming_lab.py` — the script
- `tools/sourcegraph-probe/STREAMING_LAB_CONTEXT.md` — design rationale
- `core/src/main/kotlin/.../OpenAiCompatBrain.kt` — streaming disabled (line 56-82)
- `core/src/main/kotlin/.../SourcegraphChatClient.kt` — sanitizeMessages (152-262), sendMessageStream (273-461), ToolCallBuilder (594-600)

**Why:** Timeout bug (non-streaming on growing context) and parallel tool call loss (concat JSON bug) are the two highest-impact agent issues. Both are solved by the changes above.
**How to apply:** Item 10 (re-enable streaming) is independent and can ship immediately. Items 1-4 (Cline XML port) is the next major effort. Always read this memory + STREAMING_LAB_CONTEXT.md before working on streaming, tool calls, or Sourcegraph API behavior.
