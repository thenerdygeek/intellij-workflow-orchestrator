# Sourcegraph Empty-Response Deep Research

**Date:** 2026-05-12
**Author:** Research note for `fix/automation-handover-quality-tabs`
**Symptom:** Agent loop hits "3 consecutive empty responses → fatal exit" against Sourcegraph Enterprise's `/.api/llm/chat/completions` with XML-tools mode, despite the existing retry layer (temperature → 1.0, jittered backoff, `EMPTY_RESPONSE_ERROR` nudge).

---

## Executive Summary

The "200 OK + empty content + empty `tool_calls`" pattern is **not a single bug** — it is the convergence of **at least six independent root causes**, each well-attested in upstream issue trackers. The current retry pattern (temperature escalation + nudge + backoff) addresses **only one** of those root causes (the "deterministic empty at temp=0" pattern OpenHands also fixes). The other five categories explain why retries keep coming back empty.

**Top three most-probable causes for this specific bug (ranked):**

1. **Anthropic "empty `end_turn` after tool_result" pattern** (CONFIRMED by Anthropic docs). The model emits `stop_reason="end_turn"` with no content blocks immediately after consuming a tool result. Retrying with the *same* empty turn re-sent does not help — Anthropic explicitly says "Don't retry empty responses without modification." Adding a *fresh user nudge* (which we do via `EMPTY_RESPONSE_ERROR`) *should* break the pattern — unless the conversation also contains other Anthropic-rejected sequences (text-block-after-tool-result, dangling tool_use without tool_result, empty text blocks) that the upstream gateway is silently truncating to empty.
2. **Upstream Cody Gateway "context deadline exceeded" frame swallowed by SSE parser** (PROBABLE for thinking models). Sourcegraph's Go gateway times out after 8 minutes by default; for Anthropic thinking models (Claude 3.7+, Opus 4.x with extended thinking) on long-context turns, the model can burn 50K+ tokens silently in the thinking block, then the gateway closes the SSE stream before any visible content was emitted. The current plugin code already detects `isUpstreamTimeoutFrame` — but only if Sourcegraph emits a recognizable frame. Cloudflare 524 / abrupt RST_STREAM closes the stream cleanly and the loop sees "200 OK + empty chunks". Mitigation should fall through to `upstream_timeout` handling, not the empty-response path.
3. **Streaming-mode "ghost tool call" / aggregator bug** (PROBABLE). `finish_reason="tool_calls"` arrives but no `delta.tool_calls` were ever emitted, or all tool deltas arrived with `index=null` / `index=0` and were aggregated into a single malformed tool call that fails the `isNotBlank()` filter and gets dropped. The plugin already has a non-streaming fallback for `finishReason=tool_calls && toolCallBuilders.isEmpty()`. But there are TWO additional empties this doesn't cover: (a) `finish_reason="stop"` with empty content and zero tool_calls — Anthropic returning empty after tool result, see #1, and (b) `finish_reason=null` or empty-string finish (SGLang-class providers, OpenAI gateways under heavy load).

The remaining lower-probability causes are documented further down.

---

## Background — The Existing Plugin Code

Located in `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt`:

```kotlin
// Case C: Empty response — provider error
else -> {
    consecutiveEmpties++
    brain.temperature = 1.0  // OpenHands pattern
    LOG.warn("[Loop] Empty response from LLM — provider error (attempt $consecutiveEmpties/$MAX_CONSECUTIVE_EMPTIES, temperature escalated to 1.0)")
    if (consecutiveEmpties >= MAX_CONSECUTIVE_EMPTIES) {
        return makeFailed(
            "Provider returned $MAX_CONSECUTIVE_EMPTIES consecutive empty responses. Check model/provider configuration.",
            iteration,
            FailureReason.EMPTY_RESPONSES
        )
    }
    contextManager.pruneAllNudgePairs(EMPTY_RESPONSE_ERROR)
    contextManager.addUserMessage(EMPTY_RESPONSE_ERROR)
    delay(computeBackoffMs(consecutiveEmpties))
}
```

SSE parser in `core/src/main/kotlin/com/workflow/orchestrator/core/ai/SourcegraphChatClient.kt`:

```kotlin
line.startsWith("data: ") && line != "data: [DONE]"
```

The plugin already has:
- `MessageStateHandler.addToApiConversationHistory` empty-assistant guard
- `GatewayErrorDetector.isUpstreamTimeoutFrame(line)` for mid-stream gateway timeout frames
- Streaming-tool-call drop detection (falls back to non-streaming when `finish_reason=tool_calls` but `toolCallBuilders.isEmpty()`)
- XML truncation detection → promotes to `upstream_timeout`
- Zero-width space placeholder for empty assistant tool-call messages
- Temperature escalation to 1.0 on empty response (OpenHands pattern)

These cover real failure modes but do **not** cover all of the documented root causes below.

---

## 1. Sourcegraph-specific Findings

### 1.1 Cody/Gateway "context deadline exceeded" (Go server timeout)

**Status:** CONFIRMED. Long-standing, multiple reports across VS Code and JetBrains plugins.

- [sourcegraph/cody #5858 — Request to ... failed with 500: context deadline exceeded](https://github.com/sourcegraph/cody/issues/5858)
- [sourcegraph/jetbrains #2462 — o1-preview unusable: 500 Internal Server Error: context deadline exceeded](https://github.com/sourcegraph/jetbrains/issues/2462)
- [Sourcegraph Forum thread — context deadline exceeded after 7.10.1](https://community.sourcegraph.com/t/error-request-failed-context-deadline-exceeded-since-update-to-version-7-10-1-7-11-1/1888)
- [Sourcegraph troubleshooting docs](https://sourcegraph.com/docs/cody/troubleshooting)

**Behavior:** Sourcegraph's Go LLM gateway has a default 8-minute total-request deadline. On slow models (o1, Claude thinking, Opus with extended thinking on long context), the model itself or the upstream Anthropic/OpenAI call exceeds that deadline. The gateway *can* emit a mid-stream error frame (which the plugin's `GatewayErrorDetector` already catches), but it can also:

1. Close the SSE stream with **no frame at all** (TCP FIN after partial data), or
2. Emit a 500 over HTTP (not the streaming path — applies to non-streaming fallback)

The Sourcegraph troubleshooting doc says: "This error occurs with OpenAI o1 when input exceeds optimal size. Solutions include keeping context under 200 lines."  In practice the same fires for *any* slow Anthropic thinking turn at high context.

**Confidence:** HIGH — multiple confirmed reports, official Sourcegraph response, mitigations documented.

### 1.2 Cloudflare 524 and "Connection closed without receiving any events"

**Status:** CONFIRMED. Same family as 1.1, different layer.

- [sourcegraph/cody #5152 — Connection closed without receiving any events](https://github.com/sourcegraph/cody/issues/5152)
- [sourcegraph/jetbrains #1985 — Connection closed without receiving any events](https://github.com/sourcegraph/jetbrains/issues/1985)
- [sourcegraph/jetbrains #2020 — same on Claude 3 Opus specifically](https://github.com/sourcegraph/jetbrains/issues/2020)
- [Sourcegraph forum on Cloudflare 524](https://sourcegraph.com/docs/cody/troubleshooting) — "Cloudflare has flagged your IP" / 524 timeout family

**Behavior:** Cloudflare in front of `sourcegraph.com` or the customer's Sourcegraph Enterprise instance enforces its own ~100s request timeout on the WAF / connection layer. When tripped, it closes the TLS connection. Whether the SSE parser sees this as "stream ended" depends on whether any bytes were received first. If headers + `message_start` were already flushed, OkHttp's SSE source closes cleanly and the iterator simply ends — looking identical to a 200 + empty stream to the upper layer.

**Confidence:** HIGH for self-hosted Sourcegraph behind Cloudflare; MEDIUM for `sourcegraph.com` (Sourcegraph confirmed they front their LLM gateway with Cloudflare).

### 1.3 `/.api/llm/...` deprecation / model routing

**Status:** AMBIGUOUS. Public search returns "Cody API endpoints (anything under `/.api/llm`) are no longer available" but Sourcegraph's official docs for Enterprise still show the `/.api/llm/*` family operational for current SG versions. The deprecation appears to apply to Sourcegraph.com (public) Cody, not the Enterprise endpoints the plugin hits.

- [Sourcegraph technical changelog](https://sourcegraph.com/docs/technical-changelog)
- [Sourcegraph Cody OpenAI-compatible API discussion](https://sourcegraph.com/docs/cody/clients/install-jetbrains)

**Risk:** If a customer's Sourcegraph instance is at a version where `/.api/llm/chat/completions` returns 200 + empty body for an unknown model ID, the symptom matches. This is rare in practice — most SG instances return 4xx with a clear error.

**Confidence:** LOW. Surface-level signal, not a confirmed reproduction.

### 1.4 Model deprecation and routing fallback

**Status:** CONFIRMED for `claude-3-5-sonnet-20240620` and others.

- [Anthropic model deprecations doc](https://platform.claude.com/docs/en/about-claude/model-deprecations) — `claude-3-5-sonnet-20240620` retired 2025-10-22.
- [Vercel AI Gateway listing for claude-3.5-sonnet-20240620](https://vercel.com/ai-gateway/models/claude-3.5-sonnet-20240620)

**Behavior:** If a customer's Sourcegraph instance was configured with a now-retired Anthropic model ID, the upstream call returns an error. Sourcegraph's response depends on the version — some gateways translate this into a 200 + empty stream + immediate `[DONE]`, others into a 4xx. The plugin uses `ModelCache` to resolve from `/.api/llm/models`, so this is largely mitigated *unless* the model was hand-pinned in settings.

**Confidence:** MEDIUM. Real failure mode but partially mitigated by the existing model cache.

---

## 2. Anthropic-specific Empty-Response Patterns

These apply *through* Sourcegraph because Sourcegraph forwards Anthropic-shaped responses translated into OpenAI-shape. Bugs in Anthropic itself surface to the plugin via the gateway.

### 2.1 The "empty `end_turn` after tool_result" pattern

**Status:** CONFIRMED in Anthropic docs.

- [Handling stop reasons — Anthropic](https://platform.claude.com/docs/en/build-with-claude/handling-stop-reasons) — quoted:

  > "Sometimes Claude returns an empty response (exactly 2-3 tokens with no content) with `stop_reason: 'end_turn'`. This typically happens when Claude interprets that the assistant turn is complete, particularly after tool results.
  >
  > **Common causes:**
  > - Adding text blocks immediately after tool results (Claude learns to expect the user to always insert text after tool results, so it ends its turn to follow the pattern)
  > - Sending Claude's completed response back without adding anything"
  >
  > "**Don't retry empty responses without modification** — Simply sending the empty response back won't help."

- [langchain-ai/langgraph #3168 — Anthropic API error when using prebuilt create_react_agent after model ends turn with empty content](https://github.com/langchain-ai/langgraph/issues/3168)
- [agno-agi/agno #3137 — Empty anthropic responses generated but invalid when sent back to model](https://github.com/agno-agi/agno/issues/3137)

**Implication for our plugin:**
- The existing retry **already** modifies the request (adds the `EMPTY_RESPONSE_ERROR` user nudge), so on paper we *should* break this pattern.
- BUT if the conversation history accidentally contains a *text block immediately after a tool_result* (e.g. our XML-tools mode injects a "RESULT of {toolName}:" prefix or adds extra text after tool output), Claude learns the pattern of "tool → end_turn → wait for user text" and keeps repeating it.
- **Action item:** audit how tool results are formatted in our XML-tools mode — does anything append text after a tool result before the next assistant turn?

**Confidence:** HIGH that this is involved; CRITICAL to confirm.

### 2.2 Empty text block during streaming with thinking models

**Status:** CONFIRMED.

- [anthropics/claude-code #24662 — Session becomes permanently unrecoverable after empty text content block during streaming](https://github.com/anthropics/claude-code/issues/24662)
- [anthropics/claude-code #26870 — Anthropic API Error: Empty text content blocks in messages](https://github.com/anthropics/claude-code/issues/26870)

**Behavior:** Auto-compaction can downgrade from Opus to Haiku. Haiku produces:
- block 0: thinking (346 words)
- **block 1: text with empty string `""`** ← poison
- block 2: thinking (156 words)
- block 3: text (real)
- block 4: tool_use

When this assistant message is sent back on the next turn, Anthropic rejects with `"messages: text content blocks must be non-empty"`. The bug is that *the streaming response itself produced the empty block*.

**Implication for our plugin:**
- If Sourcegraph translates this Anthropic response into the OpenAI-compatible shape, the `content` field becomes empty after assembling all `delta.content` chunks (because all the deltas referenced the empty block as the "text" portion).
- We see: 200 OK, finish_reason=`stop` or `tool_calls`, content="", tool_calls=[]. The empty-response branch fires.

**Confidence:** HIGH. This is a strong candidate for the actual bug pattern.

### 2.3 Extended Thinking budget exhaustion / silent token consumption

**Status:** CONFIRMED.

- [anthropics/claude-code #51568 — Extended Thinking causes 7-8 minute stalls with no output and silent token consumption](https://github.com/anthropics/claude-code/issues/51568)
- [anthropics/claude-code #18028 — API Streaming Stalls Causing 59-138 Second Delays and Timeouts](https://github.com/anthropics/claude-code/issues/18028)
- [anthropics/claude-code #49708 — Opus 4.7: thinking content empty despite `showThinkingSummaries: true`](https://github.com/anthropics/claude-code/issues/49708)

**Behavior:** With extended thinking enabled, Claude can burn all `max_tokens` inside the thinking block and emit *zero* text content. With Opus 4.7 specifically, the default `display: omitted` means the streaming response contains thinking blocks with empty `thinking` fields. Combined with `stop_reason="max_tokens"` arriving in the streaming `message_delta`, the assembled content is empty.

The Anthropic SDK docs ([Handling stop reasons](https://platform.claude.com/docs/en/build-with-claude/handling-stop-reasons)) say:

> "If Claude's response is cut off due to hitting the `max_tokens` limit, and the truncated response contains an incomplete tool use block, you'll need to retry the request with a higher `max_tokens` value."

But our plugin's retry doesn't change `max_tokens` — and our existing `upstream_timeout` path is only triggered by gateway frames, not by `finish_reason=max_tokens` arriving cleanly. So if Claude exhausts thinking budget, we see "200 OK + empty content + stop_reason=max_tokens" and fall through to the empty-response branch.

**Confidence:** HIGH for thinking model users on long-context turns.

### 2.4 Streaming silent abort (mid-stream TCP/connection close)

**Status:** CONFIRMED.

- [anthropics/claude-code #38905 — Silent stream abort causes Claude to stop mid-task without error](https://github.com/anthropics/claude-code/issues/38905)
- [anthropics/anthropic-sdk-typescript #842 — Streaming responses consistently interrupted mid-transmission without `message_stop` event](https://github.com/anthropics/anthropic-sdk-typescript/issues/842)
- [anthropics/anthropic-sdk-typescript #867 / proposal — idleTimeout for hanging streams](https://github.com/anthropics/anthropic-sdk-typescript/issues/867)
- [anthropics/claude-code #33949 — SSE streaming hangs indefinitely (no timeout) + ESC cannot fully cancel](https://github.com/anthropics/claude-code/issues/33949)
- [anthropics/claude-code #54472 — Stream idle timeout: partial response received](https://github.com/anthropics/claude-code/issues/54472)

**Behavior:** The SDK's SSE iterator silently swallows `AbortError`/`FetchRequestCanceledException`. When the network drops mid-stream, the `for await` loop completes normally, the upper layer sees "stream ended with partial data", and depending on what arrived: empty content + empty tool_calls.

**Implication for our plugin:** OkHttp's `EventSourceListener.onClosed` fires both on clean `[DONE]` and on TCP FIN with no warning to upper layers. We don't distinguish them. Our `isUpstreamTimeoutFrame` detector only fires when Sourcegraph injects a recognizable error frame.

**Recommended mitigation:** add SSE *idle timeout* (no event for N seconds → treat as `upstream_timeout`, not as empty content). Anthropic SDK proposal #867 recommends `idleTimeout: 60_000-120_000` ms.

**Confidence:** HIGH.

### 2.5 LiteLLM-style "stream completes silently on upstream error" (Anthropic provider bug)

**Status:** CONFIRMED, occurs at ~1% rate per LiteLLM testing.

- [BerriAI/litellm #20347 — Anthropic streaming silently completes with empty content instead of raising exception on upstream errors](https://github.com/BerriAI/litellm/issues/20347)

> "When an upstream error happens during streaming, the client receives a `message_start` event but no content chunks, and the stream terminates normally instead of propagating the error."

This is exactly our symptom. Marked **Closed as not planned** — the LiteLLM team did not fix it. Per the issue: ~1% reproduction rate against `claude-sonnet-4-20250514`. Since Sourcegraph uses similar upstream-call patterns (Go → Anthropic v1/messages → translate to OpenAI shape → SSE forward), the same class of bug almost certainly exists at the Sourcegraph gateway.

**Confidence:** HIGH that this is a real intermittent failure mode at the gateway layer. The 3-consecutive-empties trigger suggests the rate is **higher** than 1% in our case — meaning likely a specific consistent condition (model/context size/feature flag combo) rather than pure randomness.

### 2.6 Tool-result mismatch / dangling tool_use sanitization

**Status:** CONFIRMED.

- [BerriAI/litellm #19061 — Anthropic Protocol Violation: "unexpected tool_use_id" & "empty text content" (Fix Included)](https://github.com/BerriAI/litellm/issues/19061)

Three failure modes the issue documents:
1. **Dangling tool_use** → Anthropic rejects with `"tool_use ids were found without tool_result blocks immediately after"`
2. **Orphan tool_result** → Anthropic rejects with `"unexpected tool_use_id found in tool_result blocks"`
3. **Empty text** → Anthropic rejects with `"text content blocks must contain non-whitespace text"`

When Sourcegraph translates these Anthropic errors into the OpenAI shape, it can map to either an empty 200 stream (silent swallow, our symptom) or a 500 error.

**Implication for our plugin:** the existing `pruneTrailingEmptyAssistants()` and zero-width-space placeholder address parts of this. But:
- If our XML-tools mode generates an assistant turn with empty content + zero tool_calls AND we persist it before the empty-response branch fires, the next API call sends a dangling empty assistant message.
- If a tool call ID mismatch creeps in (rare but happens with parallel tool dispatch), the upstream returns 200+empty.

**Confidence:** MEDIUM. The plugin already has guards, but the conversation-history sanitization is not as robust as LiteLLM's monkey-patch.

---

## 3. OpenAI-Compatible SSE Pitfalls

### 3.1 `"data: "` prefix space variation

**Status:** CONFIRMED edge case, low risk for Sourcegraph specifically.

- [openai/openai-python #498 — SSE Stream parser expects additional space after "data:"](https://github.com/openai/openai-python/issues/498)
- [firebase/genkit #3728 — streamFlow fails to parse events without space after "data:"](https://github.com/firebase/genkit/issues/3728)

**The SSE spec** ([WHATWG HTML](https://html.spec.whatwg.org/multipage/server-sent-events.html#parsing-an-event-stream)): space after colon is *optional*. Both `data:foo` and `data: foo` are identical.

**Our plugin's parser uses `line.startsWith("data: ")`** (line 440 in `SourcegraphChatClient.kt`) — this is non-spec-compliant. **If Sourcegraph (or anything upstream) emits `data:{...}` without the space**, every chunk is dropped silently and we see "200 OK + empty content".

This is unlikely from Sourcegraph itself (their tested gateway uses `data: `), but a proxy/middleware between the plugin and the gateway (corporate proxy, Cloudflare, etc.) *can* re-encode SSE.

**Confidence:** LOW–MEDIUM. Easy to fix as belt-and-suspenders even if unlikely root cause.

### 3.2 Empty `choices` array on final chunk with `stream_options.include_usage=true`

**Status:** CONFIRMED spec behavior.

- [OpenAI streaming docs](https://developers.openai.com/api/docs/guides/streaming-responses)
- [sashabaranov/go-openai #1021 — stream_options.include_usage: false still returns usage in streamed chunks](https://github.com/sashabaranov/go-openai/issues/1021)

The plugin sends `streamOptions = StreamOptions(includeUsage = true)` (line 373). The final chunk has `choices: []` and only usage info. The plugin's parser checks `chunk.choices.firstOrNull()?.delta` — `firstOrNull()` returns null, so the chunk is skipped, which is correct.

**HOWEVER:** if the *only* chunk received before stream end is the usage-only final chunk (i.e. the server sent `message_start` → `usage chunk` → `[DONE]` with no content deltas in between), the assembled content is empty and we hit the empty-response branch. This is the LiteLLM bug pattern (#20347) again.

**Confidence:** SUPPORTING. Not a root cause but explains why the symptom can appear cleanly.

### 3.3 `finish_reason="tool_calls"` with no tool deltas — "ghost tool call"

**Status:** CONFIRMED multiple vLLM and OpenAI-compat providers.

- [vllm-project/vllm #38603 — Streaming last chunk contains non-empty tool_calls with empty fields](https://github.com/vllm-project/vllm/issues/38603)
- [vllm-project/vllm #18412 — tool_calls.id Missing in Streaming Responses but Present in Non-Streaming](https://github.com/vllm-project/vllm/issues/18412)
- [vllm-project/vllm #27572 — chat/completions stream intermittently returns null as finish_reason](https://github.com/vllm-project/vllm/issues/27572)
- [openai/openai-python #1266 — AsyncStream returning only empty choices](https://github.com/openai/openai-python/issues/1266)
- [Microsoft Q&A — Azure OpenAI streaming with tool_calls empty choices array](https://learn.microsoft.com/en-us/answers/questions/1533887/does-streaming-work-with-tool-calls-yet-on-azureop)

**Our plugin already handles this** (lines 474-479 in `SourcegraphChatClient.kt`) by falling back to non-streaming. Good. But note: in **XML-tools mode** (`tools=null`), the LLM emits XML in content, so finish_reason should be `stop`, not `tool_calls`. If Sourcegraph returns `tool_calls` despite `tools=null`, our fallback fires — and we should log this aggressively because it indicates Sourcegraph is hallucinating a tool-call finish without us providing tools.

### 3.4 `tool_calls` delta `index` field missing or all set to 0

**Status:** CONFIRMED.

- [BerriAI/litellm #15962 — Fix index field not populated in streaming mode with n>1 and tool calls](https://github.com/BerriAI/litellm/pull/15962)
- [BerriAI/litellm #21331 — Responses API streaming bridge emits all parallel tool calls on index=0](https://github.com/BerriAI/litellm/issues/21331)
- [community OpenAI thread — Streaming with tools - missing tool id, name](https://community.openai.com/t/streaming-with-tools-missing-tool-id-name/997791)
- [vllm-project/vllm #16340 — Missing "type":"function" in OpenAI-Compatible Streaming Tool Calls](https://github.com/vllm-project/vllm/issues/16340)

Less relevant for our XML-tools mode (`tools=null`), since we're not getting native tool_calls. But if Sourcegraph *internally* uses Anthropic's native tools API and then translates to OpenAI shape, an aggregation bug at the gateway could produce: tool_calls present but with empty `name` → filtered by our `it.function.name.isNotBlank()` → empty tool calls → empty-response branch.

### 3.5 Fragmentation: `parse_partial_json("")` returns `{}` and triggers premature tool execution

**Status:** CONFIRMED.

- [langchain-ai/langchain #35514 — Streaming tool call executed with empty args {} due to SSE fragmentation](https://github.com/langchain-ai/langchain/issues/35514)

The first delta arrives with `name="my_tool"` and `args=""`. If we parse it eagerly, we get a tool call with empty args. The plugin's `JSONParser` from `@streamparser/json` (Cline source) handles this in the TS port but the Kotlin port may not.

**Confidence:** LOW for XML-tools mode (no streaming tool deltas) but worth verifying.

### 3.6 Cline/OpenHands/Aider — how they classify empty responses

**Cline** (verified in `cline/cline:src/core/task/index.ts`):

```typescript
// if there's no assistant_responses, that means we got no text or tool_use content blocks from API which we should assume is an error
telemetryService.captureProviderApiError({
    ulid: this.ulid,
    errorMessage: "empty_assistant_message",
    requestId: reqId,
    ...
})

const baseErrorMessage = "Invalid API Response: The provider returned an empty or unparsable response. This is a provider-side issue where the model failed to generate valid output or returned tool calls that Cline cannot process. Retrying the request may help resolve this issue."
```

Cline's retry: 3 auto-retries with **2s, 4s, 8s** delays (exponential `2 * 2^attempt`). At 3, it asks the user. **No temperature escalation.** Source: `cline/cline:src/core/task/index.ts:~3258`.

Our plugin already matches this pattern AND adds temperature escalation — strictly better than Cline. The Cline pattern still fails on the same root causes we're hitting.

**OpenHands** (verified in `openhands/llm/retry_mixin.py`):

```python
if isinstance(exception, LLMNoResponseError):
    current_temp = retry_state.kwargs.get('temperature', 0)
    if current_temp == 0:
        retry_state.kwargs['temperature'] = 1.0
        logger.warning('LLMNoResponseError detected with temperature=0, setting temperature to 1.0 for next attempt.')
    else:
        logger.warning(f'LLMNoResponseError detected with temperature={current_temp}, keeping original temperature')
```

OpenHands does the temperature bump **only** if temp was 0 — they explicitly say "keep original if non-zero". Our plugin always bumps to 1.0. This is more aggressive but consistent with the goal.

OpenHands retries: 4 attempts, exponential, 5-30s wait. Comments say actual total is ~18s (tenacity binary backoff quirk).

- [OpenHands PR #6557 — Better LLM retry behavior](https://github.com/All-Hands-AI/OpenHands/pull/6557)

**Aider** doesn't have a documented empty-response retry; relies on liteLLM's exception handling.

**Continue.dev** has the upstream-timeout detection pattern we already ported (`continue/gui/src/redux/thunks/streamNormalInput.ts:257-291`).

**Cody (Sourcegraph's own client)**: archived repository (sourcegraph/cody, sourcegraph/jetbrains both archived 2026-03-05). The codebase historical behavior for empty responses: shows a generic "Request Failed" toast, asks the user to retry. **No automatic retry, no temperature adjustment, no model fallback.** Our plugin is significantly more robust than Cody's own client was.

### 3.7 The "downgrade to non-streaming on consecutive empties" pattern — NOT FOUND in any tool

I searched explicitly for tools that switch endpoints on repeated empties (e.g., `/.api/llm/chat/completions` → `/.api/completions/stream`, or streaming → non-streaming). **None of Cline, OpenHands, Aider, Continue.dev, or Goose do this** — they all retry the same endpoint or fail. The plugin **already does this** when `finish_reason=tool_calls && empty toolCallBuilders` — but only in that specific case.

**Opportunity:** on the 2nd or 3rd consecutive empty, the plugin could downgrade to non-streaming for that one retry. This isn't implemented in any other tool I found, and it directly addresses LiteLLM bug #20347 and similar gateway-side stream-completion bugs.

---

## 4. Thinking-Model Specific

### 4.1 Opus 4.7 changed default `display` from `summarized` to `omitted`

- [anthropics/claude-code #49708 — Opus 4.7 thinking content empty](https://github.com/anthropics/claude-code/issues/49708)
- [Anthropic Opus 4.7 release notes](https://platform.claude.com/docs/en/about-claude/models/whats-new-claude-4-7)

If we hit Opus 4.7 (or anything that defaults to `display: omitted`) via Sourcegraph and our code expects thinking content for downstream logic, we get empty thinking deltas. Combined with `max_tokens` exhaustion in the thinking block, we get empty content overall.

The plugin's `ThinkingTagSplitter` reads `<thinking>...</thinking>` from prose output (not API thinking blocks), so this mostly doesn't apply to us. But if Sourcegraph's translation of an Anthropic response with thinking blocks + empty text blocks produces an empty `content` field in the OpenAI-shape, we hit the empty-response branch.

### 4.2 Thinking budget exhaustion

- [Anthropic extended thinking docs](https://platform.claude.com/docs/en/build-with-claude/extended-thinking) — "At high and max effort levels, Claude may think more extensively and can be more likely to exhaust the max_tokens budget."

**Mitigation:** detect `finish_reason == "max_tokens"` in addition to gateway timeout frames, and route it through the same `upstream_timeout` recovery path (with a *higher* `max_tokens` on retry, per Anthropic's recommendation).

### 4.3 Reasoning content streamed to wrong field

- [vllm-project/vllm #40816 — Qwen3.6 streaming emits final answer in delta.reasoning instead of delta.content](https://github.com/vllm-project/vllm/issues/40816)
- [Anthropic thinking content missing in streaming for Sonnet/Opus 4](https://github.com/spring-projects/spring-ai/issues/4407)

Some upstream models put their final answer in `delta.reasoning_content` rather than `delta.content`. If Sourcegraph forwards this verbatim, our parser only reads `delta.content` and gets empty.

**Confidence:** LOW for Sourcegraph + Anthropic specifically; HIGH for vLLM/local models.

---

## 5. Context Window Edge Cases

- [Anthropic context windows](https://platform.anthropic.com/docs/en/docs/build-with-claude/context-windows)
- [anthropics/claude-code #12319 — Output token limit exceeded](https://github.com/anthropics/claude-code/issues/12319)
- [anthropics/claude-code #6158 — Response Exceeds Maximum Output Token Limit](https://github.com/anthropics/claude-code/issues/6158)

Anthropic Sonnet 3.7+ returns a **validation error** (4xx) when input+output exceeds the context window — not a silent empty. So context overflow shouldn't produce our exact symptom on a 200-status response, *unless* Sourcegraph swallows the 4xx and emits a 200+empty stream (the LiteLLM #20347 pattern again).

The plugin already compacts at 85% threshold, which is a good defense.

---

## 6. What Other Agentic Tools Do Better

| Tool | Empty-response retry | Temperature escalation | Endpoint downgrade | Model fallback | SSE idle timeout |
|---|---|---|---|---|---|
| **Our plugin** | 3 attempts, exponential backoff + nudge | ✅ Yes, always to 1.0 | ✅ Only for `tool_calls`+empty | ✅ Opt-in via `ModelFallbackManager` | ❌ |
| Cline | 3 attempts, 2s/4s/8s | ❌ | ❌ | ❌ | ❌ |
| OpenHands | 4 attempts via tenacity | ✅ Only if temp was 0 | ❌ | ❌ | ❌ |
| Continue.dev | Upstream timeout detection | ❌ | ❌ | ❌ | ❌ |
| Aider | LiteLLM exception bubble | ❌ | ❌ | ❌ | ❌ |
| Anthropic Claude Code | Silent abort, no retry | ❌ | ❌ | ✅ "fallbackModel" config | ❌ (open issue #867) |
| Cody (archived) | User-driven retry only | ❌ | ❌ | ❌ | ❌ |

**Our plugin is already best-in-class.** The biggest gaps:
1. **SSE idle timeout** — no tool does this in production yet, but Anthropic's SDK has an open proposal (#867) recommending 60-120s.
2. **Endpoint downgrade beyond the `tool_calls` case** — none of the tools surveyed do this; would be a novel mitigation for LiteLLM #20347-class bugs.
3. **`max_tokens` bumping on retry** — Anthropic recommends it; nobody implements it automatically.

---

## 7. Sourcegraph-Specific Quirks to Handle

These differ from generic OpenAI clients:

1. **Auth scheme:** `Authorization: token <...>` (not `Bearer`). Already correct in plugin.
2. **No `system` role:** must convert to user with `<system_instructions>` tags. Already handled in `sanitizeMessages()`.
3. **No `tool_choice`:** field is silently ignored. Plugin handles this.
4. **Strict user/assistant alternation:** consecutive same-role messages get merged. Plugin handles this.
5. **Empty content rejection:** Anthropic upstream rejects empty content; plugin uses `​` zero-width-space placeholder. Already handled.
6. **No native `max_tokens` mirror:** plugin uses configured `maxOutputTokens` per model. Risk: if Sourcegraph silently caps below this, response gets truncated. Detect via `finish_reason=length` + retry with smaller op (already implemented).
7. **Gateway timeout frame:** Sourcegraph emits a specific mid-stream error frame for "context deadline exceeded". Plugin has `GatewayErrorDetector.isUpstreamTimeoutFrame`. But the gateway *also* sometimes closes the SSE cleanly without a frame — that case is not currently mapped to `upstream_timeout`.
8. **Cloudflare 524:** any client behind Cloudflare can have its TLS connection closed at ~100s. Plugin doesn't currently distinguish this from a clean stream end.
9. **HTTP/2 RST_STREAM:** Go gateways at high load emit RST_STREAM with CANCEL code. OkHttp should surface this as an exception, not as clean close — but the plugin's chunked SSE reader may swallow it. Worth verifying.
10. **`/.api/llm/chat/completions` vs `/.api/completions/stream`:** the plugin's `BrainRouter` already routes image-bearing turns to `/stream` and text-only to `/chat/completions`. **No empty-response endpoint downgrade** between these two endpoints exists today. Could be a 4th-attempt fallback (try the same request on `/stream` if `/chat/completions` returned empty 3x).

---

## 8. Concrete Action Items

Ranked by expected impact on the 3-empties fatal exit:

### A. **Add SSE idle-timeout detection (HIGH impact)**
Track time since last SSE event. If > 90s elapses with no event, abort the stream with `finishReason = upstream_timeout` instead of completing it with empty content. References: [anthropics/anthropic-sdk-typescript #867 proposal](https://github.com/anthropics/anthropic-sdk-typescript/issues/867). This catches LiteLLM #20347-class bugs, Cloudflare 524, RST_STREAM, and silent TCP drops simultaneously.

### B. **Detect `finish_reason=max_tokens` with empty content and route through `upstream_timeout` recovery (HIGH impact)**
Currently we only route mid-stream gateway frames. Add: if assembled content is empty AND `finish_reason in {"max_tokens", "length"}`, treat as `upstream_timeout` (not empty-response), and on retry bump `max_tokens` per Anthropic's official recommendation. References: [Anthropic handling stop reasons](https://platform.claude.com/docs/en/build-with-claude/handling-stop-reasons).

### C. **Endpoint downgrade on 2nd consecutive empty (MEDIUM impact, novel)**
When `consecutiveEmpties == 2`, swap `/.api/llm/chat/completions` → `/.api/completions/stream` (Cody-native) for that one retry. The two endpoints have different gateway code paths in Sourcegraph's Go server; if one is buggy, the other often works. The plugin already supports both via `BrainRouter`. References: existing plugin code, [BrainRouter.kt routing logic](../../agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/BrainRouter.kt).

### D. **Audit tool-result formatting in XML-tools mode (HIGH impact, low cost)**
Confirm we do *not* append any text after a tool result before the next assistant turn. Anthropic explicitly warns this causes empty `end_turn` responses. Check `ContextManager`'s message assembly and `SourcegraphChatClient.sanitizeMessages()`. References: [Handling stop reasons — Anthropic](https://platform.claude.com/docs/en/build-with-claude/handling-stop-reasons).

### E. **Make SSE parser tolerate `data:` without space (LOW impact, defensive)**
Change `line.startsWith("data: ")` to `line.startsWith("data:")` and trim the leading space optionally. References: [openai/openai-python #498](https://github.com/openai/openai-python/issues/498), [WHATWG SSE spec](https://html.spec.whatwg.org/multipage/server-sent-events.html#parsing-an-event-stream).

### F. **Log the empty-response request ID and add a "report this" affordance (LOW impact, observability)**
On the 3rd empty before failing, log everything: request ID, model, total context size, last 5 message roles, finish_reason from the streaming partial. This makes future bugs diagnosable without speculation.

### G. **Try `max_tokens` doubling on each empty retry (MEDIUM impact)**
If the cause is thinking-budget exhaustion (#51568, #18028), simply bumping `max_tokens` from e.g. 8K → 16K → 32K across the 3 retries would directly fix it. Currently we don't change `max_tokens`. References: [Anthropic recommendation](https://platform.claude.com/docs/en/build-with-claude/handling-stop-reasons), [#51568](https://github.com/anthropics/claude-code/issues/51568).

### H. **Disable extended thinking on 2nd consecutive empty (MEDIUM impact)**
If the model is a thinking-capable Anthropic model and we just retried once with no result, drop thinking for the next attempt. We can re-enable on Case A success. References: [#49708](https://github.com/anthropics/claude-code/issues/49708), [#51568](https://github.com/anthropics/claude-code/issues/51568).

---

## 9. Confidence Levels Summary

| Root cause | Confidence | Mitigated today | Action item |
|---|---|---|---|
| Anthropic empty `end_turn` after tool_result | HIGH | Partially (nudge) | D |
| Gateway silent close (no error frame) | HIGH | No | A, F |
| Streaming "ghost tool call" / aggregator bugs | HIGH | Yes for `tool_calls`+empty | E (broader) |
| Thinking budget exhaustion (max_tokens hit silently) | HIGH | No | B, G, H |
| Cloudflare 524 / TLS close mid-stream | HIGH | Partially (gateway frame only) | A |
| LiteLLM #20347-class gateway silent failure | HIGH | No | A, C |
| Empty text block inside response (Haiku auto-downgrade) | MEDIUM-HIGH | Partially (empty-assistant guard on persist) | D, F |
| Tool-result mismatch / dangling tool_use | MEDIUM | Partially | D |
| SSE `data:` prefix space variation | LOW | No | E |
| Model deprecation / unknown ID | LOW | Partially (model cache) | F |
| `/.api/llm/...` route disabled | LOW | No | C |
| `delta.tool_calls` index aggregation bug | LOW for XML mode | N/A | — |
| `delta.reasoning_content` content split | LOW | No | F |
| HTTP/2 RST_STREAM with CANCEL | MEDIUM | No | A |
| Opus 4.7 `display: omitted` default | LOW (we use prose `<thinking>`) | N/A | — |

---

## Sources

### Sourcegraph / Cody Issues
- [sourcegraph/cody #5858 — context deadline exceeded](https://github.com/sourcegraph/cody/issues/5858)
- [sourcegraph/cody #6698 — request failed](https://github.com/sourcegraph/cody/issues/6698)
- [sourcegraph/cody #5152 — Connection closed without receiving any events](https://github.com/sourcegraph/cody/issues/5152)
- [sourcegraph/cody #5421 — same](https://github.com/sourcegraph/cody/issues/5421)
- [sourcegraph/cody #2223 — Streaming APIs should not use gzip](https://github.com/sourcegraph/cody/issues/2223)
- [sourcegraph/cody #5966 — Cody does not fetch any context](https://github.com/sourcegraph/cody/issues/5966)
- [sourcegraph/cody #7429 — Unsupported API Version](https://github.com/sourcegraph/cody/issues/7429)
- [sourcegraph/jetbrains #2462 — o1-preview context deadline exceeded](https://github.com/sourcegraph/jetbrains/issues/2462)
- [sourcegraph/jetbrains #2212 — no parseable response data from Anthropic](https://github.com/sourcegraph/jetbrains/issues/2212)
- [sourcegraph/jetbrains #2214 — same](https://github.com/sourcegraph/jetbrains/issues/2214)
- [sourcegraph/jetbrains #1985 — Connection closed without receiving any events](https://github.com/sourcegraph/jetbrains/issues/1985)
- [sourcegraph/jetbrains #2020 — same on Claude 3 Opus](https://github.com/sourcegraph/jetbrains/issues/2020)
- [Sourcegraph forum — Request Failed: received no parseable response from Anthropic](https://community.sourcegraph.com/t/request-failed-received-no-parseable-response-data-from-anthropic/2420)
- [Sourcegraph forum — context deadline exceeded after 7.10.x](https://community.sourcegraph.com/t/error-request-failed-context-deadline-exceeded-since-update-to-version-7-10-1-7-11-1/1888)
- [Sourcegraph forum — Cody response delays](https://community.sourcegraph.com/t/cody-response-delays-and-code-application-issues/1627)
- [Sourcegraph forum — Request Failed](https://community.sourcegraph.com/t/request-failed/1026)
- [Sourcegraph forum — Agentic Chat output limitation](https://community.sourcegraph.com/t/agentic-chat-output-limitation/2514)
- [Sourcegraph troubleshooting docs](https://sourcegraph.com/docs/cody/troubleshooting)
- [Sourcegraph technical changelog](https://sourcegraph.com/docs/technical-changelog)
- [Sourcegraph agentic chat docs](https://sourcegraph.com/docs/cody/capabilities/agentic-chat)
- [Sourcegraph Cody Gateway docs](https://sourcegraph.com/docs/cody/core-concepts/cody-gateway)
- [Sourcegraph stream API docs](https://sourcegraph.com/docs/api/stream_api)
- [Sourcegraph model configuration](https://sourcegraph.com/docs/cody/enterprise/model-configuration)
- [Sourcegraph supported models](https://sourcegraph.com/docs/cody/capabilities/supported-models)
- [Sourcegraph 6.8 release](https://sourcegraph.com/changelog/releases/6.8)

### Anthropic Issues and Docs
- [Anthropic handling stop reasons](https://platform.claude.com/docs/en/build-with-claude/handling-stop-reasons)
- [Anthropic streaming messages](https://platform.claude.com/docs/en/build-with-claude/streaming)
- [Anthropic extended thinking](https://platform.claude.com/docs/en/build-with-claude/extended-thinking)
- [Anthropic adaptive thinking](https://platform.claude.com/docs/en/build-with-claude/adaptive-thinking)
- [Anthropic streaming refusals](https://platform.claude.com/docs/en/test-and-evaluate/strengthen-guardrails/handle-streaming-refusals)
- [Anthropic model deprecations](https://platform.claude.com/docs/en/about-claude/model-deprecations)
- [Anthropic errors reference](https://platform.claude.com/docs/en/api/errors)
- [Anthropic Opus 4.7 release notes](https://platform.claude.com/docs/en/about-claude/models/whats-new-claude-4-7)
- [Anthropic fine-grained tool streaming](https://platform.claude.com/docs/en/agents-and-tools/tool-use/fine-grained-tool-streaming)
- [Claude Code error reference](https://code.claude.com/docs/en/errors)
- [anthropics/claude-code #24662 — Session unrecoverable after empty text content block](https://github.com/anthropics/claude-code/issues/24662)
- [anthropics/claude-code #26870 — Empty text content blocks in messages](https://github.com/anthropics/claude-code/issues/26870)
- [anthropics/claude-code #38905 — Silent stream abort](https://github.com/anthropics/claude-code/issues/38905)
- [anthropics/claude-code #18028 — Streaming stalls causing 59-138s delays](https://github.com/anthropics/claude-code/issues/18028)
- [anthropics/claude-code #33949 — SSE streaming hangs indefinitely](https://github.com/anthropics/claude-code/issues/33949)
- [anthropics/claude-code #51568 — Extended Thinking 7-8 minute stalls](https://github.com/anthropics/claude-code/issues/51568)
- [anthropics/claude-code #54472 — Anthropic API timeout during streaming](https://github.com/anthropics/claude-code/issues/54472)
- [anthropics/claude-code #49708 — Opus 4.7 thinking content empty](https://github.com/anthropics/claude-code/issues/49708)
- [anthropics/claude-code #12319 — Output token limit exceeded](https://github.com/anthropics/claude-code/issues/12319)
- [anthropics/claude-code #6158 — Response Exceeds Max Output Token Limit](https://github.com/anthropics/claude-code/issues/6158)
- [anthropics/anthropic-sdk-typescript #842 — Stream interrupted mid-transmission](https://github.com/anthropics/anthropic-sdk-typescript/issues/842)
- [anthropics/anthropic-sdk-typescript #867 — Stream idle timeout proposal](https://github.com/anthropics/anthropic-sdk-typescript/issues/867)
- [anthropics/anthropic-sdk-typescript #893 — docx/pptx skills silent failure](https://github.com/anthropics/anthropic-sdk-typescript/issues/893)
- [anthropics/anthropic-sdk-typescript #913 — Opus 4.6 returns empty content with json_schema](https://github.com/anthropics/anthropic-sdk-typescript/issues/913)
- [anthropics/anthropic-sdk-typescript #346 — Error: [object Object] during message streaming](https://github.com/anthropics/anthropic-sdk-typescript/issues/346)
- [langchain-ai/langgraph #3168 — Anthropic API error after model ends turn with empty content](https://github.com/langchain-ai/langgraph/issues/3168)

### OpenAI-Compat / LiteLLM / vLLM
- [BerriAI/litellm discussion #3440 — AnthropicError: No content in response](https://github.com/BerriAI/litellm/discussions/3440)
- [BerriAI/litellm #20347 — Anthropic streaming silently completes with empty content](https://github.com/BerriAI/litellm/issues/20347)
- [BerriAI/litellm #19061 — Anthropic Protocol Violation: unexpected tool_use_id & empty text content](https://github.com/BerriAI/litellm/issues/19061)
- [BerriAI/litellm #15962 — Fix index field not populated in streaming](https://github.com/BerriAI/litellm/pull/15962)
- [BerriAI/litellm #21331 — Responses API streaming bridge emits parallel tool calls on index=0](https://github.com/BerriAI/litellm/issues/21331)
- [BerriAI/litellm #25561 — Anthropic /v1/messages drops tool_use args for vertex_ai/gemini](https://github.com/BerriAI/litellm/issues/25561)
- [BerriAI/litellm #11158 — ollama stream final chunk missing empty text block](https://github.com/BerriAI/litellm/issues/11158)
- [BerriAI/litellm #9551 — 504 Gateway Time-out from Proxy for Long Non-Streaming Requests](https://github.com/BerriAI/litellm/issues/9551)
- [BerriAI/litellm #12854 — Invalid/empty API response in fake stream using claude code](https://github.com/BerriAI/litellm/issues/12854)
- [BerriAI/litellm PR #12463 — Streaming + response_format + tools bug](https://github.com/BerriAI/litellm/pull/12463)
- [google/adk-python #4482 — LiteLLM streaming silently drops tool calls when finish_reason=length](https://github.com/google/adk-python/issues/4482)
- [vllm-project/vllm #38603 — Streaming last chunk non-empty tool_calls with empty fields](https://github.com/vllm-project/vllm/issues/38603)
- [vllm-project/vllm #18412 — tool_calls.id missing in streaming](https://github.com/vllm-project/vllm/issues/18412)
- [vllm-project/vllm #27572 — chat/completions stream returns null finish_reason](https://github.com/vllm-project/vllm/issues/27572)
- [vllm-project/vllm #41182 — kimi-k2.5 streaming returns only content before tool call](https://github.com/vllm-project/vllm/issues/41182)
- [vllm-project/vllm #18006 — OpenAI Chat Completions API: NULL response](https://github.com/vllm-project/vllm/issues/18006)
- [vllm-project/vllm #19650 — Missing finish_reason: null in streaming chat completion](https://github.com/vllm-project/vllm/issues/19650)
- [vllm-project/vllm #40816 — Qwen3.6 emits final answer in delta.reasoning](https://github.com/vllm-project/vllm/issues/40816)
- [vllm-project/vllm #16340 — Missing type:function in OpenAI-Compatible Streaming Tool Calls](https://github.com/vllm-project/vllm/issues/16340)
- [vllm-project/vllm #17161 — tool_calls list empty, value in content (non-standard)](https://github.com/vllm-project/vllm/issues/17161)
- [vllm-project/vllm #30904 — Empty content on NVIDIA-Nemotron-3-Nano-30B-A3B-FP8](https://github.com/vllm-project/vllm/issues/30904)
- [vllm-project/vllm PR #9209 — Fix tool call finish reason in streaming](https://github.com/vllm-project/vllm/pull/9209)
- [sgl-project/sglang #3912 — finish_reason as empty string instead of null](https://github.com/sgl-project/sglang/issues/3912)
- [openai/openai-python #498 — SSE Stream parser expects additional space after data:](https://github.com/openai/openai-python/issues/498)
- [openai/openai-python #1266 — AsyncStream returning only empty choices](https://github.com/openai/openai-python/issues/1266)
- [openai/openai-python #649 — Streaming chunk generator: incomplete JSON](https://github.com/openai/openai-python/issues/649)
- [openai/openai-python #1677 — delta in ChatCompletionChunk is None](https://github.com/openai/openai-python/issues/1677)
- [OpenAI community — Chat completions response empty sporadically](https://community.openai.com/t/chat-completions-response-empty-sporadically/731419)
- [OpenAI community — Empty text in the response after few calls](https://community.openai.com/t/empty-text-in-the-response-from-the-api-after-few-calls/2067)
- [OpenAI community — Incomplete stream chunks for completions API](https://community.openai.com/t/incomplete-stream-chunks-for-completions-api/383520)
- [OpenAI community — Streaming and newlines and empty responses](https://community.openai.com/t/streaming-and-newlines-and-empty-responses/689375)
- [OpenAI community — Streaming with tools missing tool id, name](https://community.openai.com/t/streaming-with-tools-missing-tool-id-name/997791)
- [OpenAI community — finish_reason stop with tool_calls and no content](https://community.openai.com/t/finish-reason-stop-but-have-a-tool-calls-and-no-content/820316)
- [OpenAI community — Usage stats with stream chunks](https://community.openai.com/t/usage-stats-now-available-when-using-streaming-with-the-chat-completions-api-or-completions-api/738156)
- [Microsoft Q&A — Azure OpenAI streaming with tool_calls empty choices](https://learn.microsoft.com/en-us/answers/questions/1533887/does-streaming-work-with-tool-calls-yet-on-azureop)
- [open-webui/open-webui #21768 — finish_reason incorrectly returned as stop after tool_calls](https://github.com/open-webui/open-webui/issues/21768)
- [deepset-ai/haystack #8780 — OpenAI Generator Assertion Error due to empty chunks](https://github.com/deepset-ai/haystack/issues/8780)
- [ollama/ollama PR #7963 — finish streaming tool calls as tool_calls](https://github.com/ollama/ollama/pull/7963)
- [vercel/ai #6687 — Tool Calls Fail with Empty Arguments](https://github.com/vercel/ai/issues/6687)
- [sashabaranov/go-openai #1021 — stream_options.include_usage behavior](https://github.com/sashabaranov/go-openai/issues/1021)
- [langchain4j/langchain4j #1853 — Tool not called if streaming](https://github.com/langchain4j/langchain4j/issues/1853)
- [langchain-ai/langchain #35514 — Streaming tool call executed with empty args due to SSE fragmentation](https://github.com/langchain-ai/langchain/issues/35514)
- [ggml-org/llama.cpp #14566 — OpenAI HTTP interface returns HTTP-200 with error in streamed chunk](https://github.com/ggml-org/llama.cpp/issues/14566)
- [router-for-me/CLIProxyAPI #3076 — Streaming token usage always zero with OpenAI-compatible](https://github.com/router-for-me/CLIProxyAPI/issues/3076)
- [openai/codex #3229 — stream disconnected before completion: idle timeout](https://github.com/openai/codex/issues/3229)
- [agno-agi/agno #3137 — Empty anthropic responses invalid when sent back](https://github.com/agno-agi/agno/issues/3137)
- [lbjlaq/Antigravity-Manager #1408 — OpenAI-compatible API returns null tool_calls for non-streaming](https://github.com/lbjlaq/Antigravity-Manager/issues/1408)

### Cline / OpenHands / Continue / Aider
- [cline/cline #5645 — Auto-retry on empty model response](https://github.com/cline/cline/issues/5645)
- [cline/cline #4321 — Anthropic API Provider error reading large file](https://github.com/cline/cline/issues/4321)
- [cline/cline #7526 — Constantly get error with Claude Sonnet 4.5](https://github.com/cline/cline/issues/7526)
- [cline/cline #4724 — CSP & API Errors with Anthropic Claude Models](https://github.com/cline/cline/issues/4724)
- [cline/cline #7464 — Anthropic API key required when using LiteLLM proxy](https://github.com/cline/cline/issues/7464)
- [cline/cline discussion #871 — Automatically Wait For Anthropic Rate Limit](https://github.com/cline/cline/discussions/871)
- [cline/cline source — task/index.ts empty_assistant_message handling](https://github.com/cline/cline/blob/main/src/core/task/index.ts)
- [All-Hands-AI/OpenHands PR #6557 — Better LLM retry behavior](https://github.com/All-Hands-AI/OpenHands/pull/6557)
- [OpenHands source — retry_mixin.py LLMNoResponseError temperature bump](https://github.com/All-Hands-AI/OpenHands/blob/main/openhands/llm/retry_mixin.py)
- [OpenHands #9208 — LiteLLM Gateway Timeout](https://github.com/OpenHands/OpenHands/issues/9208)
- [OpenHands #8768 — Improve timeout handling for slow local LLMs](https://github.com/OpenHands/OpenHands/issues/8768)
- [OpenHands #4131 — temperature does not support 0.0 with O1-mini](https://github.com/OpenHands/OpenHands/issues/4131)
- [block/goose #3571 — Severe API Authentication and 404 Errors](https://github.com/block/goose/issues/3571)
- [charmbracelet/crush #960 — Anthropic overloaded_error immediately terminates](https://github.com/charmbracelet/crush/issues/960)

### SSE Spec / Transport
- [WHATWG SSE spec](https://html.spec.whatwg.org/multipage/server-sent-events.html#parsing-an-event-stream)
- [MDN Server-sent events](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events/Using_server-sent_events)
- [firebase/genkit #3728 — streamFlow fails to parse events without space after data:](https://github.com/firebase/genkit/issues/3728)
- [tokio-rs/axum discussion #2146 — SSE:Event with leading Whitespace](https://github.com/tokio-rs/axum/discussions/2146)
- [EventSource/eventsource #18 — blank lines parsing](https://github.com/EventSource/eventsource/issues/18)
- [OkHttp EventSourceListener](https://square.github.io/okhttp/5.x/okhttp-sse/okhttp3.sse/-event-source-listener/index.html)
- [OkHttp RealEventSource source](https://github.com/square/okhttp/blob/master/okhttp-sse/src/main/kotlin/okhttp3/sse/internal/RealEventSource.kt)
- [LaunchDarkly okhttp-eventsource](https://github.com/launchdarkly/okhttp-eventsource)
- [golang/go #52845 — HTTP/2 client sending RST_STREAM at high TPS](https://github.com/golang/go/issues/52845)
- [grpc/grpc-go #2886 — Server should send RST_STREAM when deadline is exceeded](https://github.com/grpc/grpc-go/issues/2886)
- [RFC 9113 HTTP/2](https://www.rfc-editor.org/rfc/rfc9113.html)

### Misc / Background
- [Cody for JetBrains GA announcement](https://sourcegraph.com/blog/cody-for-jetbrains-is-generally-available)
- [Cody JetBrains v5.4.358 release](https://sourcegraph.com/blog/cody-jetbrains-5-4-358-release)
- [Cody agentic chat introduction](https://sourcegraph.com/blog/introducing-agentic-chat)
- [Cody supports MCP](https://sourcegraph.com/blog/cody-supports-anthropic-model-context-protocol)
- [Cody for VS Marketplace](https://marketplace.visualstudio.com/items?itemName=sourcegraph.cody-ai)
- [Cody JetBrains plugin page](https://plugins.jetbrains.com/plugin/9682-cody-ai-by-sourcegraph)
- [Sourcegraph Cody FAQs](https://sourcegraph.com/docs/cody/faq)
- [Sourcegraph "Cody for Enterprise" docs](https://sourcegraph.com/docs/cody/clients/enable-cody-enterprise)
- [DeepWiki — OpenHands Retry and Error Handling](https://deepwiki.com/OpenHands/OpenHands/7.5-plugins-and-extensions)
- [Cline AI Provider Integration DeepWiki](https://deepwiki.com/cline/cline/4-tools-and-integrations)
- [Cline Anthropic provider docs](https://docs.cline.bot/provider-config/anthropic)
- [OpenAI streaming completions cookbook](https://cookbook.openai.com/examples/how_to_stream_completions)
- [Streaming Tool Calls deep dive — dev.to](https://dev.to/gabrielanhaia/streaming-tool-calls-parse-anthropic-sse-without-loading-the-whole-message-2on)
- [Server-Sent Events Deep Dive — Panaversity](https://agentfactory.panaversity.org/docs/TypeScript-Language-Realtime-Interaction/async-patterns-streaming/server-sent-events-deep-dive)
- [Upstash blog — Resumable LLM Streams](https://upstash.com/blog/resumable-llm-streams)
- [Building real-time AI APIs with Go (DSi blog)](https://www.dsinnovators.com/blog/golang/ai-apis-golang-concurrency-llm-2026/)
- [Vercel AI Gateway — claude-3.5-sonnet-20240620](https://vercel.com/ai-gateway/models/claude-3.5-sonnet-20240620)
- [AWS Bedrock — Anthropic Claude Messages API](https://docs.aws.amazon.com/bedrock/latest/userguide/model-parameters-anthropic-claude-messages-request-response.html)

### Plugin internal references
- `core/src/main/kotlin/com/workflow/orchestrator/core/ai/SourcegraphChatClient.kt` (SSE parser, lines 400-600)
- `core/src/main/kotlin/com/workflow/orchestrator/core/ai/OpenAiCompatBrain.kt`
- `core/src/main/kotlin/com/workflow/orchestrator/core/ai/SourcegraphCompletionsStreamClient.kt`
- `core/src/main/kotlin/com/workflow/orchestrator/core/ai/GatewayErrorDetector.kt`
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt` (lines 530-545, 1370-1500)
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/BrainRouter.kt`
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/ModelFallbackManager.kt`
- `agent/CLAUDE.md` (project memory)
