# Streaming Lab — Session Context & Design Rationale

> Share this file with any new session that needs to understand what
> `streaming_lab.py` tests, why each probe exists, and what decisions
> are pending based on the results.

---

## What This Script Is

`streaming_lab.py` is a standalone Python diagnostic tool that probes a
Sourcegraph LLM gateway (`/.api/llm/chat/completions`) to determine what
the gateway supports, what it silently ignores, and what it rejects.

It was built to inform two decisions for an IntelliJ plugin agent:

1. **Should we re-enable real streaming?** (currently disabled — see below)
2. **Should we port Cline's XML tool call format** to replace our native
   function calling implementation?

Run it with:
```
pip install requests
python streaming_lab.py --url https://your-sg.example.com --token sgp_xxx
```

Results are saved to `streaming_lab_results.json`. Re-analyze without
re-running:
```
python streaming_lab.py --analyze streaming_lab_results.json
```

---

## Background: The Agent's Current Problems

### Problem 1 — Streaming is disabled, causing read timeouts

`OpenAiCompatBrain.chatStream()` in the plugin does NOT actually stream.
It calls `sendMessage()` (non-streaming) and emits a single fake SSE chunk
so the UI still updates. Streaming was disabled because of two known bugs:

- Sourcegraph sometimes sends `finish_reason=tool_calls` with zero tool
  call deltas → the plugin falls back to a second non-streaming call
- Usage tokens were missing from streamed responses → token tracking broken

**Why this causes timeouts:** As conversation context grows over multiple
turns, the LLM takes longer to respond. Non-streaming waits for the *full*
response before returning anything. Eventually the 120s OkHttp read timeout
fires. Opening a new conversation (resetting context) fixes it temporarily.

**The fix, if streaming is viable:** Re-enable streaming in
`OpenAiCompatBrain`. With streaming, the read timeout resets on every SSE
byte received — even a small first chunk prevents timeout on long
generations.

**What the lab tests for this:**
- `probe_streaming_reliability` — 5 runs, checks all complete cleanly
- `probe_streaming_vs_nonstreaming_latency` — if TTFB/total < 0.3, streaming
  prevents timeouts
- `probe_sse_keepalive` — does gateway send `: keep-alive` bytes during
  model thinking? If NO, even streaming can timeout during pre-generation
  silence
- `probe_pregeneration_silence` — how long before first byte at 3 prompt
  sizes? > 10s + no keepalive = guaranteed timeout on complex tasks
- `streaming_code_300_lines` / `streaming_code_500_lines` — do large
  outputs stream to completion without gaps > 30s?

### Problem 2 — Tool results rejected (multi-turn tool use broken)

`role: tool` messages (OpenAI format for sending tool results back) return
HTTP 400 from Sourcegraph. This means the agent cannot complete a tool call
round-trip:

```
user → model calls tool → agent executes tool → send result → model responds
                                                 ↑ BREAKS HERE (HTTP 400)
```

**What the lab tests for this:**
- `probe_tool_role` — confirms HTTP 400
- `probe_tool_result_formats` — tries 6 alternative formats (A–F) to find
  one that works
- `probe_multi_turn_tool_use` — full 2-turn round-trip test with auto-
  fallback from tool role to user-message format
- `probe_anthropic_tool_result_content_block` — tests Cline's exact format:
  `{"type": "tool_result", "tool_use_id": "..."}` in a user-role message.
  **This is the most important probe** — if it returns WORKS, it means
  Cline's architecture is compatible with Sourcegraph today

---

## Cline Port — What It Is and Why

The agent was ported from Cline (VS Code AI agent) but the **tool call
section was NOT ported**. Cline uses a fundamentally different approach:

### What Cline does (XML tool tags)

Instead of OpenAI native function calling (`tools: [...]` in the request),
Cline describes tools as XML schemas in the system prompt and the model
responds with XML tool tags embedded in the text stream:

```
<read_file>
  <path>src/main/kotlin/Foo.kt</path>
</read_file>
```

For file writing (the large code case):
```
<write_to_file>
  <path>src/model/UserProfile.kt</path>
  <content>
  package com.example.model
  ...500 lines of code...
  </content>
</write_to_file>
```

### Why XML is better for streaming large code

With native function calling, 500 lines of code arrives as a JSON-escaped
string fragment: `"{\"content\":\"class Foo {\\nfun bar() =\\n..."`. It
cannot be shown to the user incrementally and cannot be parsed until all
fragments arrive.

With XML, the code arrives as plain text. The parser sees `<content>`,
starts accumulating, and can show progress in real time. Closing tag
detection uses `lastIndexOf("</content>")` — not `indexOf` — because code
content may contain XML-looking strings like `</div>` that would fool a
naive search.

### Tool results in Cline's format

Cline sends tool results as Anthropic content blocks in user messages:
```json
{
  "role": "user",
  "content": [
    {
      "type": "tool_result",
      "tool_use_id": "toolu_01",
      "content": "file contents here"
    }
  ]
}
```

This is NOT `role: tool` (OpenAI format). The `probe_anthropic_tool_result_content_block`
probe tests whether Sourcegraph accepts this Anthropic format.

### What needs to be ported (future work)

| Component | Description |
|---|---|
| XML system prompt | Tool definitions as XML schemas in the system prompt |
| `parseAssistantMessageV2` | Kotlin port of Cline's index-based streaming XML parser |
| Streaming accumulator | `assistantMessage += chunk.text` → re-parse entire string on each chunk |
| Tool executor wiring | Execute only when `partial: false` (closing tag found) |
| Tool result format | Switch from `role: tool` to `{"type": "tool_result", ...}` in user message |

---

## What the Modes (A / B / C) Test

Every scenario is run in 3 modes to compare approaches:

| Mode | Strategy | Relevance |
|---|---|---|
| **A** | Native function calling — `tools: [...]` in request, parse `delta.tool_calls` | Current agent implementation |
| **B** | XML tool tags in system prompt — Cline's approach | Target implementation after port |
| **C** | JSON in fenced code block — `\`\`\`json {"tool_calls": [...]}` | Fallback / model-agnostic |

---

## Complete Probe Inventory and Purpose

### Side Probes (always run unless `--no-probes`)

| Probe | Purpose |
|---|---|
| `probe_tool_choice_variants` | Does gateway honor `tool_choice: forced/none/auto`? Detects silent ignoring (HTTP 200 but directive ignored) |
| `probe_cache_control` | Does `cache_control: ephemeral` actually create prompt cache hits? |
| `probe_max_tokens` | Is there a real cap at 8K/16K/64K? (Spec says 8K but empirically no cap) |
| `probe_response_format_json` | Does `response_format: json_object` work? (Confirmed: HTTP 400) |
| `probe_system_role` | Does `role: system` in messages array work? (Confirmed: HTTP 400 — use top-level `system` field) |
| `probe_tool_role` | Does `role: tool` for tool results work? (Confirmed: HTTP 400 — root cause of multi-turn breakage) |
| `probe_include_usage_isolation` | Does streaming emit usage even WITHOUT `include_usage: true`? |
| `probe_tool_result_formats` | Tests 6 formats (A–F) for sending tool results. Finds working alternative to `role: tool` |
| `probe_multi_turn_tool_use` | Full 2-turn round-trip. Step 2a: `role: tool`. Step 2b: user-msg fallback |
| `probe_streaming_reliability` | 5 identical streaming runs. Measures TTFB variance, missing [DONE], missing usage |
| `probe_streaming_vs_nonstreaming_latency` | Compares streaming TTFB vs non-streaming total. Ratio < 0.3 = streaming prevents timeouts |
| `probe_anthropic_tool_result_content_block` | **KEY PROBE**: Tests Cline's `tool_result` content block format. If WORKS → Cline port viable today |
| `probe_conversation_growth` | Simulates 10-turn growing conversation. Finds latency spike / failure point |
| `probe_cline_message_format` | Validates all 4 `sanitizeMessages()` transforms used by the plugin |
| `probe_stream_options_include_usage` | 3 streaming runs with `include_usage: true`. Is usage chunk reliably present? |
| `probe_parallel_tool_call_index_assembly` | Streams 2/3/4/5 parallel tool calls. Detects concatenated-JSON bug and index gaps |
| `probe_sse_keepalive` | **CRITICAL**: Detects `: keep-alive` SSE comment lines. Absent = timeouts during thinking |
| `probe_pregeneration_silence` | **CRITICAL**: Measures silence before first byte at 3 prompt sizes (tiny/2K/8K chars) |
| `probe_truncated_tool_call` | Forces `max_tokens` to cut off mid-tool-call. Maps to Cline's `partial: true` case |
| `probe_xml_large_content_streaming` | Tests XML `<content>` blocks with 50 and 150 lines. Verifies Cline write_to_file pattern |

### Advanced Probes (run unless `--no-advanced`)

| Probe | Purpose |
|---|---|
| `probe_thinking_strategies` | 8 thinking/reasoning activation strategies. All currently SILENTLY_DROPPED |
| `probe_model_metadata` | `/models` endpoint — does it expose context_window / capability fields? |
| `probe_response_headers` | Rate-limit headers, request-id, served-by, Anthropic passthrough |
| `probe_vision_input` | Multimodal image input — currently IMAGE_SILENTLY_STRIPPED |
| `probe_anthropic_native_fields` | `top_level_system`, `top_k`, seed, n, service_tier, logprobs |
| `probe_cache_hit_verification` | 2 identical calls with `cache_control` — checks if 2nd call gets cache hits |
| `probe_endpoint_discovery` | Tries 8 undocumented paths to find hidden endpoints |

---

## Complete Scenario Inventory

| Scenario | What it tests | max_tokens |
|---|---|---|
| `single_tool` | Baseline single tool call | 2048 |
| `parallel_2` | 2 parallel tool calls — known bug repro | 2048 |
| `parallel_3` | 3 parallel tool calls — delta index assembly | 2048 |
| `text_then_tool` | Text before tool call (interleaving) | 2048 |
| `tool_then_text` | Tool before text (hardest for XML parser) | 2048 |
| `special_chars_args` | Regex with quotes/backslashes in arg | 2048 |
| `multiline_arg` | Newline embedded in tool argument | 2048 |
| `text_only` | No tool call — pure text, finish=stop | 2048 |
| `large_arg` | Long regex pattern — delta assembly at size | 2048 |
| `tool_then_parallel` | Reasoning text + parallel tool calls | 2048 |
| `streaming_medium_text` | 500+ word prose — stream stays alive | 2048 |
| `streaming_code_100_lines` | ~100 line Kotlin LRU cache | 2048 |
| `streaming_code_300_lines` | ~300 line OkHttp retry client | 6144 |
| `streaming_code_500_lines` | ~500 line event sourcing framework | 12288 |
| `think_then_tool` | Reasoning text then tool (Cline think-before-act) | 2048 |
| `parallel_5` | 5 concurrent tools — agent research scope max | 2048 |
| `parallel_mixed_types` | All 3 tool types in parallel | 2048 |
| `only_tool_no_text` | Pure tool call, zero text | 2048 |
| `tool_with_xml_content_in_arg` | XML-like text in arg — Mode B parser collision | 2048 |
| `sequential_investigation` | 2 parallel tools without result dependency | 2048 |
| `attempt_completion_pattern` | Text-only final answer (agent completion turn) | 2048 |
| `unicode_in_arg` | Unicode chars in tool arg — UTF-8 through SSE | 2048 |
| `max_tokens_truncation` | Deliberate finish_reason=length | 2048 |
| `xml_write_small_file` | Mode B write_to_file ~50 lines Kotlin | 2048 |
| `xml_write_large_file` | Mode B write_to_file ~200 lines Kotlin | 6144 |

---

## Known Gateway Constraints (Confirmed)

| Feature | Status | Notes |
|---|---|---|
| `role: system` in messages | **REJECTED** HTTP 400 | Use top-level `system` field or XML wrapper in user message |
| `role: tool` for results | **REJECTED** HTTP 400 | Root cause of multi-turn breakage |
| `tool_choice` | **SILENTLY IGNORED** | HTTP 200 but directive not honored |
| `response_format: json_object` | **REJECTED** HTTP 400 | |
| `seed` | **REJECTED** HTTP 400 | |
| `n > 1` | **REJECTED** HTTP 400 | |
| `service_tier` | **REJECTED** HTTP 400 | |
| `cache_control: ephemeral` | **SILENTLY IGNORED** | cached_tokens always 0 |
| Vision / image input | **SILENTLY STRIPPED** | HTTP 200 but model sees no image |
| Thinking / reasoning params | **SILENTLY DROPPED** | All 8 strategies — model responds normally |
| `tools: [...]` native calling | **WORKS** (undocumented) | Not in OpenAPI spec but functional |
| `stream: true` | **WORKS** (labeled unsupported in spec) | |
| `stream_options.include_usage` | **WORKS** | Usage chunk emitted at end |
| `max_tokens` up to 64K+ | **WORKS** | No hard cap despite spec claiming 8K |
| `top_level_system` field | **WORKS** | Use instead of `role: system` |

---

## Key Files to Share When Reporting Results

After running, share these for analysis:

1. **`streaming_lab_results.json`** — all structured probe results
2. **`raw_sse/streaming_code_500_lines_A.txt`** — raw SSE with timestamps for largest scenario
3. **`raw_sse/streaming_code_300_lines_A.txt`** — same for 300-line scenario
4. **stdout / terminal output** (capture with `| tee lab_output.txt`)

The auto-analysis runs automatically after all tests. To re-run analysis:
```
python streaming_lab.py --analyze streaming_lab_results.json
```

---

## Pending Decisions (Awaiting Lab Results)

1. **Re-enable streaming?**
   - Check `probe_sse_keepalive` verdict and `probe_pregeneration_silence` large prompt ms
   - Check `streaming_reliability` overall verdict (STABLE / FLAKY)
   - Check `streaming_vs_nonstreaming` ratio (< 0.3 = safe to enable)

2. **Which tool result format to use?**
   - Check `probe_anthropic_tool_result_content_block` working_formats
   - Check `probe_tool_result_formats` recommended format
   - If Anthropic content blocks work → implement in `sanitizeMessages()`

3. **Is Cline XML port viable on Sourcegraph?**
   - Check `probe_xml_large_content_streaming` verdicts (COMPLETE_XML = good)
   - Check Mode B scenario results (especially xml_write_large_file)
   - Check `probe_truncated_tool_call` mode_b_xml verdict (need partial:true handling?)

4. **Safe parallel tool call count?**
   - Check `probe_parallel_tool_call_index_assembly` results for 2/3/4/5
   - First count where concat_bug=True or assembled < expected = the limit
