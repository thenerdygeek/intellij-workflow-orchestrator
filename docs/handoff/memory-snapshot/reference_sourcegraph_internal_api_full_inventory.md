---
name: Sourcegraph Internal OpenAPI spec — full inventory
description: Complete capability inventory of openapi.SourcegraphInternal.Latest.yaml — only 4 paths total but rich schemas (FilePart, RepoPart, ClientConfig, ModelCatalog with per-model context windows + tier + clientSideConfig). Use when deciding what to integrate beyond the public OpenAI-compat facade. Public and internal specs share ZERO paths.
type: reference
originSessionId: 3dcb348b-83b1-4f74-b596-2777b0366c45
---
# Sourcegraph Internal API — Full Inventory (captured 2026-04-22)

Spec source: `openapi.SourcegraphInternal.Latest.yaml` (739 lines).
Public spec for cross-reference: `openapi.Sourcegraph.Latest.yaml`.

## ⚠ PROBE-CONFIRMED CORRECTIONS (Sourcegraph 6.12.5040, 2026-04-22)

The published spec is partly wrong about what this user's instance actually exposes. Run `tools/sourcegraph-probe/capabilities_lab.py` to refresh. Baselines committed at `tools/sourcegraph-probe/baselines/{capabilities_lab,client_config,model_catalog}_2026-04-22_sourcegraph-6.12.json`.

**Spec-vs-reality deltas observed on the user's instance:**

| Spec / earlier memory said | Reality on Sourcegraph 6.12.5040 |
|---|---|
| `api-version` enum is `[1, 2, 3]` | `latestSupportedCompletionsStreamAPIVersion = 9` (undocumented bump). v8 currently used by `vision_lab` still works; v9 untested but available. |
| `ModelCapability` enum is `[autocomplete, chat]` only | Real `capabilities` arrays include `vision`, `tools`, `reasoning`, `edit` — the spec is stale, NOT incomplete by design. The agent CAN discover vision/tools support from the catalog. |
| `Model.contextWindow.maxInputTokens` is the limit to use | **TWO-LEVEL caps.** Top-level shows `45000` (artificially low), but `modelConfigAllTiers.<tier>.contextWindow.maxInputTokens` shows `132000` (non-thinking) / `93000` (thinking) for tier=enterprise. **Read the tier-override path** or you waste 65–87% of available context. Also: tier override exposes `maxUserInputTokens: 18000` cap separate from total input. |
| `tools` field on `/stream` was unknown | **SILENTLY DROPPED.** Probe P6: gateway accepts the request, returns 200, model never emits a tool call. Detected only by the absence of expected tool_calls. This is the single most important reason HYBRID routing is correct, not switch. |
| `tools` on `/.api/llm/chat/completions` works as undocumented courtesy | **Confirmed PASS** (P8 regression check). Same code path the agent's ReAct loop currently uses. |
| `FilePart` with URI-only might fetch from gitserver | **Does NOT fetch.** Probe P4: model literally said "no content visible — the `<CONTEXT_FILE>` tag is empty." So FilePart offers no token-savings vs. the existing inline-content pattern. Use `FilePart {uri,content}` (P3 PASS) but don't expect server-side fetch. |
| `RepoPart` should work for any indexed repo | Shape is **accepted** but only resolves repos this Sourcegraph instance has indexed. Probe P5 sent `github.com/sourcegraph/cody` and got `HTTP 400 prompt_rewrite_error: repo not found`. Re-test with a repo your instance actually indexes before relying on RepoPart. |
| Memory said vision is **NOT discoverable** from `ModelCapability` | **WRONG — it IS discoverable.** All 6 Claude 4.5 models on this instance advertise `capabilities: ["chat","vision","tools",...]`. The spec was stale; your agent can rely on `'vision' in model.capabilities` for gating. |

**Probe-confirmed routing decision (HYBRID is correct):**

| Turn content | Endpoint | Why |
|---|---|---|
| Text + tools (the dominant agent path) | `/.api/llm/chat/completions` (existing) | P8 PASS |
| Image attachment, no tools | `/.api/completions/stream?api-version=8` | vision_lab 24/24 PASS |
| Image + tools combined | Two-step: vision-summarize on /stream, then tools call on /chat/completions | P6 + P7 both SAW_NO — `tools` silently dropped on /stream |

**Real client_config from the user's instance** (paste below as ground truth — replaces "we should query this someday"):
```json
{"codyEnabled": true, "chatEnabled": true, "autoCompleteEnabled": true,
 "customCommandsEnabled": true, "attributionEnabled": true,
 "attribution": "permissive", "smartContextWindowEnabled": true,
 "modelsAPIEnabled": true, "latestSupportedCompletionsStreamAPIVersion": 9}
```

**Real default models from the catalog:**
- chat: `anthropic::2024-10-22::claude-sonnet-4-5-latest`
- fastChat: `anthropic::2024-10-22::claude-haiku-4-5-latest`
- codeCompletion: `anthropic::2024-10-22::claude-haiku-4-5-latest`
- fallbackChat: `google::v1::gemini-2.0-flash`

The spec-derived inventory below remains useful for understanding the *theoretical* capability surface, but where it disagrees with the table above, the probes win.

---

## Key insight: ZERO path overlap with the public spec

The two specs are completely disjoint surfaces:

| Public (`/.api/llm/...`) | Internal (`/.api/completions/...` + others) |
|---|---|
| OpenAI-compat facade for BYO tooling (LiteLLM, LangChain) | Native Cody facade used by web/VSCode/JetBrains |
| `role` / `max_tokens` / OpenAI shape | `speaker` / `maxTokensToSample` / Cody shape |
| Text-only `MessageContentPart` | 4 variants: text / image_url / file / repo |
| `tools` field works (undocumented) | NO documented tool-use schema |
| 8000 max_tokens (per spec, not enforced) | `maxTokensToSample` no documented cap |

**Pick a lane per capability needed; you cannot mix request shapes across the two facades.**

## All 4 internal paths

| Method | Path | Returns | Purpose |
|---|---|---|---|
| GET | `/.api/client-config` | `ClientConfig` | Per-instance feature-flag snapshot. **No params.** |
| POST | `/.api/completions/code` | `CompletionResponse` | Single-shot non-streaming completion. **One human message only.** Skip — strictly less useful than `/stream`. |
| POST | `/.api/completions/stream` | SSE | Streaming chat. Multimodal-capable. Takes `api-version`, `client-name`, `client-version` query params. |
| GET | `/.api/modelconfig/supported-models.json` | `ModelCatalog` | Per-model contextWindow, tier, status, clientSideConfig. Replaces public `/.api/llm/models`. |

Every internal path inherits the same `Authorization: token <sgp_...>` auth scheme as the public spec. No per-endpoint license-tier or feature-flag headers.

## `MessageContentPart` — 4 discriminator variants (the headline feature)

```yaml
MessageContentPart:
  discriminator:
    propertyName: type
    mapping:
      text:      TextPart
      image_url: ImagePart    # vision — already validated
      file:      FilePart     # NEW — file attachment by URI
      repo:      RepoPart     # NEW — repo-as-context attachment
```

### `ImagePart` (already validated 2026-04-22, see reference_sourcegraph_image_transport.md)

```json
{"type": "image_url",
 "image_url": {"url": "data:image/png;base64,...",
               "detail": "low|high|auto"}}
```

### `FilePart` — NOT YET PROBED

```json
{"type": "file",
 "file": {"uri": "git+https://github.com/foo/bar/blob/main/Main.kt#L20-50",
          "language_id": "kotlin",  // optional, VS Code language id
          "content": "..."}}         // optional — server may fetch via gitserver if omitted
```

**Open question:** does Sourcegraph fetch the file when `content` is omitted? Spec is silent. If yes → huge token savings (server-side dedup across messages). Probe before relying on it.

### `RepoPart` — NOT YET PROBED

```json
{"type": "repo",
 "repo": {"name": "github.com/foo/bar",         // OR id (XOR — undocumented)
          "filePatterns": ["src/.*\\.kt$"]}}    // regex array, mirrors /.api/cody/context
```

Internally Sourcegraph runs the same retrieval as `/.api/cody/context` but binds chunks to *this* turn. Best fit: natural-language Q&A over a repo. Bad fit: deterministic file selection (use `FilePart` for that).

**Spec bug:** neither `repo.id` nor `repo.name` is marked `required`, but the public `RepoSpec` says exactly-one-of. Almost certainly XOR.

## `ClientConfig` (response from `/.api/client-config`) — capability discovery

```yaml
required (all booleans except the int):
  codyEnabled               # admin master switch
  chatEnabled               # chat sub-feature
  autoCompleteEnabled       # autocomplete sub-feature
  customCommandsEnabled
  attributionEnabled        # code-attribution feature
  smartContextWindowEnabled
  modelsAPIEnabled          # use /modelconfig/supported-models.json vs old way
  latestSupportedCompletionsStreamAPIVersion: int  # max api-version=N for /stream
```

**Use this to drop probe-based feature detection at startup.** Single GET call.

**No `visionEnabled` boolean.** ⚠ Vision IS discoverable per-model — see top addendum: real `Model.capabilities` arrays on Sourcegraph 6.12 contain `vision` / `tools` / `reasoning` / `edit`, NOT just `[autocomplete, chat]` as the spec claims. Use `"vision" in model.capabilities` from `/.api/modelconfig/supported-models.json` instead of name-heuristics or probes.

## `ModelCatalog` (response from `/.api/modelconfig/supported-models.json`) — strict upgrade over `/.api/llm/models`

```yaml
schemaVersion: string
revision: string
providers: [{id, displayName}]
defaultModels: {chat, fastChat, codeCompletion}    # all required
models: [Model]
```

```yaml
Model:
  modelRef: "anthropic::2023-06-01::claude-3.5-sonnet"  # canonical mref
  displayName: "Claude 3.5 Sonnet"
  modelName: string
  capabilities: [autocomplete | chat | vision | tools | reasoning | edit]   # spec stale: real wire response includes vision/tools/reasoning/edit
  category: balanced | speed | other | accuracy
  status: experimental | beta | stable | deprecated
  tier: free | pro | enterprise
  contextWindow:                                        # ⚠ TWO-LEVEL: see top addendum
    maxInputTokens: int                                  # top-level may be artificially low (45K)
    maxOutputTokens: int                                 # — read modelConfigAllTiers.<tier>.contextWindow
                                                         # for the real cap (132K non-thinking on user's instance)
  modelConfigAllTiers:                                   # not in the spec but PRESENT in the wire response
    enterprise:                                          # tier-keyed override
      contextWindow:
        maxInputTokens: int                              # the REAL cap to use
        maxOutputTokens: int
        maxUserInputTokens: int                          # separate per-message cap (18K observed)
  clientSideConfig:                                      # admin-injected hints; respect these
    chatPreInstruction: string?                          # e.g. "Answer in Spanish"
    editPostInstruction: string?
    stopSequences: [string]?
    chatTemperature, chatMaxTokens, etc.
```

Public `/.api/llm/models` returns only `{id, object, created, owned_by}` — switching to this is strictly more information.

## `CompletionRequest` body shape (for `/.api/completions/stream`)

```yaml
model: string                    # mref form
messages: [{speaker, content}]   # speaker NOT role; content = string OR [MessageContentPart]
maxTokensToSample: int           # NOT max_tokens; no documented cap
temperature: float
stream: boolean                  # true → SSE; false → JSON (probe-confirmed both work)
topK: int                        # -1 = unset
topP: int                        # -1 = unset
stopSequences: [string]?
```

Plus query params: `api-version` (enum [1,2,3]), `client-name`, `client-version`.

## `CompletionResponse` shape (single response type, codegen-duplicated in spec)

```yaml
completion: string?    # full text — only on /code OR api-version <= 1
deltaText: string?     # incremental — only on /stream with api-version >= 2
stopReason: string?    # provider-specific (Anthropic: stop_sequence|max_tokens; OpenAI: stop|length|content_filter)
logprobs: Logprobs?    # nullable; parallel-arrays Fireworks-style (different from public chat-style)
```

**SSE wire format:** `event: completion\ndata: <JSON>\n\n` per chunk. **Spec does NOT document a terminator.** Cody source emits `event: done\ndata: {}` as courtesy — but rely on connection close as authoritative end-of-stream signal.

## Capabilities NOT in the public spec (cross-referenced exhaustively)

| Capability | Internal | Public |
|---|---|---|
| All 4 internal paths | ✓ | ❌ |
| `image_url` content parts (vision) | ✓ | ❌ — `MessageContentPart.type` enum is `[text]` only |
| `FilePart` URI/content attachment | ✓ | ❌ |
| `RepoPart` repo-as-context | ✓ | ❌ (standalone `/.api/cody/context` exists but can't embed in chat) |
| `api-version` query param | ✓ (spec enum [1,2,3]; live instance reports v9 supported via `latestSupportedCompletionsStreamAPIVersion`) | ❌ — public has no API versioning |
| `client-name` / `client-version` query params | ✓ | ❌ |
| `ClientConfig` capability negotiation | ✓ | ❌ — no public capability-discovery endpoint |
| `ModelCatalog` with contextWindow + tier + status + clientSideConfig | ✓ | ❌ — public is just OpenAI-shape `OAIModel` |
| `maxTokensToSample` with no documented cap | ✓ | ❌ — public `max_tokens` capped at 8000 (not enforced empirically) |
| Per-instance prompt-engineering hints (`chatPreInstruction`, etc.) | ✓ | ❌ |
| `Versions` enum range | V5_5..V5_8 + Latest | V5_7..V5_8 + Latest (internal supports 2 more older versions) |

## Capabilities NOT in EITHER spec (gotchas)

- **Tool / function calling.** Neither facade documents `tools` / `tool_choice` / `tool_calls`. The public `/.api/llm/chat/completions` accepts `tools` as an undocumented courtesy (per memory `project_cody_enterprise_only.md`). The internal `/.api/completions/stream` is **unproven for tools** — if switching the agent's text+tools turns to it, MUST probe `tools` acceptance first.
- **Vision discoverability** ⚠ spec said "stuck with heuristics" — **probe-corrected: real `Model.capabilities` on Sourcegraph 6.12 includes `vision`/`tools`/`reasoning`/`edit`.** The agent CAN discover vision support cleanly via `"vision" in model.capabilities`. Older instances may differ; defaulting to the heuristic when capabilities array is empty/missing is safest.

## Spec bugs to know

1. **`TextPart.text` is not in `required`** — only `type` is. Defensive code: always send `text`.
2. **`RepoPart.repo` doesn't mark `id` or `name` required** — XOR contract is undocumented but inherited from public `RepoSpec`.
3. **Both `/code` and `/stream` declare `responses` as `anyOf: [CompletionResponse, CompletionResponse]`** — codegen artifact; treat as single response type.
4. **No SSE terminator in spec for `/stream`** — rely on connection close, not on `event: done`.

## Recommended integration priority for `:agent`

1. **`/.api/client-config` + `/.api/modelconfig/supported-models.json`** — low-risk capability discovery. Replaces hard-coded model lists, context windows, tier gates. Two GET calls at startup. Drop `latestSupportedCompletionsStreamAPIVersion` into version negotiation. Surface `Model.status: deprecated` in the model picker. Respect `Model.clientSideConfig.chatPreInstruction`.
2. **`/.api/completions/stream` for image-bearing turns ONLY** — proven by `vision_lab.py` (24/24 PASS on user's 6.12 instance).
3. **`FilePart` for file attachments** — needs probing for `content`-omitted server-fetch behavior. If yes, replaces current "stuff file body in prompt" pattern with cleaner schema + dedup.
4. **`RepoPart` for repo-Q&A flows** — collapses two API calls (`/.api/cody/context` + `/.api/llm/chat/completions`) into one. Bad fit for deterministic file selection.

## Skip these

- `/.api/completions/code` — single-shot constraint, no advantage over `/stream` with one message.
- `Logprobs` field — exposed but no plugin use case.
- `api-version=3` upgrade — no concrete capability difference from v2/v8 documented.

## Cross-references

- `reference_sourcegraph_image_transport.md` — proven vision transport via `/.api/completions/stream`.
- `reference_sourcegraph_http_apis.md` — older inventory of public `/.api/llm/*` endpoints (still correct for text-only / tool-using chat).
- `project_cody_enterprise_only.md` — public-facade constraints (max_tokens behavior, undocumented `tools` field, no `tool_choice`).
