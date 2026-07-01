---
name: Sourcegraph image transport — /.api/completions/stream (probe-confirmed)
description: The actual working image-input wire format for the user's Sourcegraph 6.12 instance. /.api/llm/chat/completions is text-only by design; images go through /.api/completions/stream with Sourcegraph-specific field names. All 6 Claude 4.5 vision models PASS 24/24 cells. Use this when adding image input to the :agent module.
type: reference
originSessionId: 3dcb348b-83b1-4f74-b596-2777b0366c45
---
# Sourcegraph image-input transport (probed against the user's instance, 2026-04-22)

## ⚠ 2026-05-05 UPDATE — TOOLS-ON-STREAM BLOCKER LIFTED

The "tools field is silently dropped on /.api/completions/stream" claim below
is **NO LONGER TRUE** at api-version=9 (the user's instance now advertises 9
via `latestSupportedCompletionsStreamAPIVersion`). format_lab probe 2026-05-05
verified: 12/12 tools-on-stream cells PASS with real `tool_use`/`tool_calls`/
`delta_tool_calls` markers across all 6 vision-capable Claude 4.5 models.

The BrainRouter two-step image+tools workaround was DELETED on 2026-05-05.
Image+tools turns now make a single round-trip through `/stream` with the
tools field forwarded. The 'image-only' shape table entry below is still
accurate; the 'image+tools two-step workaround' entry is dead.

Baseline of new evidence: `tools/sourcegraph-probe/baselines/
capabilities_lab_2026-05-05_sourcegraph-6.12.json` (supersedes the
2026-04-22 baseline this memory was written from).

The rest of this memory is still useful as historical context for the
shape of the prior workaround, but DO NOT use it as a guide for new work.

---

## TL;DR

- Image input on the user's Sourcegraph **6.12.x** Enterprise instance works ONLY through `/.api/completions/stream`, NOT through `/.api/llm/chat/completions`.
- The public `/.api/llm/chat/completions` is text-only by Sourcegraph's design. The Go handler `internal/openapi/goapi/model_chat_completions.go` has no `image_url` field; the public OpenAPI spec's `MessageContentPart.type` is `enum: [text]` only. **Don't try to send images through it.**
- Probe results: `tools/sourcegraph-probe/vision_lab.py` ran 4 stream-endpoint scenarios × 6 vision-capable models = **24/24 PASS** on 2026-04-22. All variants accepted: api-version 2 AND 8, stream=true AND stream=false, content order [image,text] AND [text,image].
- Baseline: `tools/sourcegraph-probe/baselines/vision_lab_2026-04-22_sourcegraph-6.12.json`.

## ⚠ PROBE-CONFIRMED ROUTING (capabilities_lab 2026-04-22) — HYBRID, with image+tools workaround

**Hybrid is correct, NOT switch.** `capabilities_lab.py` confirmed: the `/.api/completions/stream` endpoint **silently drops** the `tools` field (P6 SAW_NO + P7 image+tools SAW_NO). The same `tools` field still works on `/.api/llm/chat/completions` (P8 PASS, 1 tool_call). Baseline at `tools/sourcegraph-probe/baselines/capabilities_lab_2026-04-22_sourcegraph-6.12.json`.

| Turn content | Endpoint | Why |
|---|---|---|
| Text only | `/.api/llm/chat/completions` (existing path) | text-only fast path |
| Text + tools (the dominant agent ReAct path) | `/.api/llm/chat/completions` (existing path) | only this facade honors `tools` |
| Image attachment, no tools | `/.api/completions/stream?api-version=8` | the path documented below |
| Image + tools combined | **Two-step workaround:** (1) send image to `/stream` asking for a textual description; (2) inject the description as a text part into the next `/chat/completions` turn that needs tools | `tools` is silently dropped if both are sent together to `/stream` |

The two-step workaround is non-obvious but cheap: most agent turns don't have both image AND tool intent simultaneously. When they do, treat the image as a sub-task: vision-summarize first, then resume normal tools-using ReAct on the public facade. The summarization round-trip costs ~1s + image-token budget, then the original tools turn runs unchanged.

## Vision discoverability — use the model catalog (probe-corrected)

Earlier memory said vision was NOT discoverable from any spec field. **That was wrong.** The live `/.api/modelconfig/supported-models.json` response on Sourcegraph 6.12 includes `capabilities` arrays with `vision`, `tools`, `reasoning`, `edit` — the spec just had a stale `[autocomplete, chat]` enum. Check `"vision" in model.capabilities` directly; no name-heuristic needed.

All 6 Claude 4.5 models on the user's instance advertise `["chat","vision","tools"]` (the thinking variants also include `"reasoning"`). Haiku 4.5 also includes `["autocomplete","edit"]`.

## Confirmed-working request shape (use this in the plugin)

```http
POST {sourcegraph}/.api/completions/stream?api-version=8
Authorization: token <sgp_...>
Content-Type: application/json; charset=utf-8
Accept: text/event-stream
```
```json
{
  "model": "anthropic::2024-10-22::claude-sonnet-4-5-latest",
  "messages": [{
    "speaker": "human",
    "content": [
      {"type": "image_url",
       "image_url": {"url": "data:image/png;base64,<base64-payload>"}},
      {"type": "text", "text": "<user prompt>"}
    ]
  }],
  "maxTokensToSample": 10000,
  "temperature": 0,
  "stream": true,
  "topK": -1,
  "topP": -1
}
```

**Critical field-name differences from `/.api/llm/chat/completions`:**

| OpenAI-shape (chat/completions, text-only) | Cody stream (image-capable) |
|---|---|
| `role: "user"` | `speaker: "human"` |
| `max_tokens` | `maxTokensToSample` |
| JSON response with `choices[0].message.content` | SSE: `event: completion` blocks with `data: {"deltaText":"..."}` (or JSON when `stream:false`) |

## Server-version requirements

- **Sourcegraph 5.8.0+**: `image_url` content parts first supported (api-version=2 minimum)
- **Sourcegraph 6.2.0+**: api-version=8 supported (current Cody clients use this)
- User's instance is **6.12.x** → both v2 and v8 work; prefer v8 (matches what the Cody UI sends)
- Confirmed in `lib/shared/src/sourcegraph-api/siteVersion.ts` (mapping table)

## Vision gating — check the model `capabilities` array

- Vision is **NOT** a server feature flag. It's a per-model **tag** in `/.api/modelconfig/supported-models.json`.
- Sending an image to a non-vision model = client-side guard, not server contract; the upstream LLM will reject.
- Cody's web UI grays out the upload button when `selectedModel.tags.includes('vision')` is false (`vscode/webviews/.../Toolbar.tsx`). Mirror this in the JCEF chat.
- **All 6 Claude 4.5 models on the user's instance ARE vision-capable** (sonnet/opus/haiku × normal/thinking variants).

## Allowed MIME types (Cody client whitelist)

`image/png`, `image/jpeg`, `image/webp`, `image/heic`, `image/heif`. Source: `vscode/webviews/chat/cells/messageCell/human/editor/toolbar/MediaUploadButton.tsx:11`. Cody web UI also caps at 5 MB per image, max 2 images per turn.

## Response shape

**With `stream: true`** (SSE) — assemble by accumulating `deltaText` (api-version ≥ 2) or replacing with cumulative `completion` field (api-version 1):

```
event: completion
data: {"deltaText":"red"}

event: completion
data: {"deltaText":" colored"}

event: done
data: {}
```

**With `stream: false`** (JSON, also accepted on the user's instance — surprising but useful for one-shot calls):

```json
{
  "completion": "Red",
  "stopReason": "end_turn",
  "usage": {
    "completion_tokens": 4,
    "prompt_tokens": 26,
    "total_tokens": 30,
    "credits": 1,
    "prompt_tokens_details": {
      "cached_tokens": 0,
      "cache_creation_input_tokens": 0
    }
  }
}
```

Note: `usage.credits = 1` per call observed across all 6 models. `prompt_tokens_details` is the same shape as the OpenAI-compat path — the agent's existing token accounting can ingest it directly.

## How to apply (when wiring into `:agent`)

1. **Add a new client class** alongside `OpenAiCompatBrain.kt` (don't overload it). Suggested name: `CodyStreamBrain` or `SourcegraphMultimodalClient`. Hits `/.api/completions/stream`, builds the `speaker`/`maxTokensToSample` body, parses SSE.
2. **Route by attachment presence:** if the turn has any image content, use `CodyStreamBrain`; otherwise stay on the existing `OpenAiCompatBrain` (text-only chat continues unchanged).
3. **Vision-tag filter:** at chat-time, check `/.api/modelconfig/supported-models.json` for the selected model's `capabilities`. Disable the image-attach UI when vision isn't advertised.
4. **MIME + size caps client-side:** mirror Cody's whitelist (png/jpeg/webp/heic/heif) and 5 MB cap. Surface as `PluginSettings` (per `feedback_settings_ui.md`).
5. **Auth header is unchanged:** same `Authorization: token <sgp_...>` the rest of the plugin already sends.
6. **SSE parser:** Cody-stream frames are `event: completion` + `data: {"deltaText":"..."}` — **different from the OpenAI-shape `data: {"choices":[{"delta":{...}}]}`** the existing streaming code parses. Reuse the logic but write a separate parser; don't try to unify with the chat/completions parser.

## Probe artifacts (for re-running / regression checks)

- Probe script: `tools/sourcegraph-probe/vision_lab.py`
- Run: `py -3 vision_lab.py --url '<sg-url>' --token '<sgp_...>' --no-url-tests --only cody_stream_v8_image_first,cody_stream_v8_text_first,cody_stream_v2_text_first,cody_stream_v8_no_stream`
- Last result: `vision_lab_results.json` (24/24 PASS, ~800–2000ms latency per call)
- The 13 baseline scenarios in the probe (against `/.api/llm/chat/completions`) are kept as negative-control evidence — they SHOULD all fail; if they ever start passing, Sourcegraph added multimodal to the public LLM API and the plugin can simplify.

## Cross-reference

- The older `reference_sourcegraph_http_apis.md` memory calls `/.api/llm/chat/completions` "the **authoritative, supported** API for enterprise integrations." That claim is correct for **text-only** chat but **wrong for vision** — for image input, `/.api/completions/stream` is the only supported transport. This memory supersedes that one for any image-bearing call.
- See also project memory `project_cody_enterprise_only.md` for max_tokens/thinking-model constraints (still applies to text-only path).
