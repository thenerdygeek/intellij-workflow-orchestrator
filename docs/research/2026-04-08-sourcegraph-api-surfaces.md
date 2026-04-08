# Sourcegraph LLM API Surfaces — Findings

Date: 2026-04-08
Sources investigated:
- https://github.com/sourcegraph/llmsp (experimental Go LSP, proof-of-concept)
- https://github.com/sourcegraph/openapi/blob/main/openapi.Sourcegraph.Latest.yaml (official OpenAPI spec)

There are **two distinct Sourcegraph LLM HTTP surfaces**. The `:agent` module currently
targets surface #1 (the OpenAI-compatible REST API). Surface #2 is legacy / experimental
and only useful as historical reference.

---

## Surface 1 — Modern OpenAI-compatible REST (`sourcegraph/openapi`)

Authoritative, supported, and what enterprise integrations should target.

### Endpoints

| Method | Path | Operation ID | Purpose |
|---|---|---|---|
| POST | `/.api/llm/chat/completions` | `LLMService_chatCompletions` | OpenAI-shaped chat completions |
| GET  | `/.api/llm/models` | `LLMService_list` | List available models |
| GET  | `/.api/llm/models/{modelId}` | `LLMService_retrieveModel` | Retrieve a single model |
| POST | `/.api/cody/context` | `CodyService_context` | Repo-aware context retrieval |

### Authentication

- Scheme name: `SourcegraphTokenAuth`
- Header: `Authorization: token <TOKEN>` (the literal word `token`, **not** `Bearer`)
- Token formats:
  - v3 (current): `sgp_(?:[a-fA-F0-9]{16}|local)_[a-fA-F0-9]{40}`
  - v2 (deprecated): `sgp_[a-fA-F0-9]{40}`
  - v1 (deprecated): `[a-fA-F0-9]{40}`

### Chat Completions request shape

| Field | Notes |
|---|---|
| `model` (required) | Format `${ProviderID}::${APIVersionID}::${ModelID}`, e.g. `anthropic::2023-06-01::claude-3.5-sonnet` |
| `messages` | OpenAI-compatible `ChatCompletionRequestMessage[]` |
| `max_tokens` | Spec says ≤ 8000 — **this is OUTDATED**. Live server accepts 100K (probe-verified). Don't propagate the 4000/8000 caps. |
| `temperature`, `top_p` | Standard sampling |
| `frequency_penalty`, `presence_penalty` | Standard penalties |
| `logprobs`, `top_logprobs` | Logprob exposure |
| `logit_bias` | Per-token bias |
| `seed` | Determinism hint |
| `response_format` | `text` or `json_object` |
| `stream`, `stream_options` | Streaming controls (spec lists as "Unsupported" but works in practice) |
| `stop`, `n`, `user`, `service_tier` | Standard OpenAI fields |

### Chat Completions response shape

`id`, `object`, `created`, `model`, `choices[]` (each: `ChatCompletionResponseMessage` + optional `ChatCompletionLogprobs`), `usage` (`CompletionUsage`), `system_fingerprint`, `service_tier`.

`object` value is the literal string `"object"` (not `"chat.completion"` like upstream OpenAI).

### Schemas
- Requests: `CodyContextRequest`, `CreateChatCompletionRequest`, `ChatCompletionRequestMessage`, `RepoSpec`, `MessageContentPart`, `ChatCompletionStreamOptions`
- Responses: `CodyContextResponse`, `CreateChatCompletionResponse`, `OAIListModelsResponse`, `OAIModel`, `ChatCompletionChoice`, `ChatCompletionResponseMessage`, `FileChunkContext`, `BlobInfo`, `RepositoryInfo`, `CommitInfo`, `CompletionUsage`, `Error`
- Supporting: `ChatCompletionLogprobs`, `ChatCompletionTokenLogprob`

### Cody Context request

| Field | Notes |
|---|---|
| `query` (required) | Natural-language query |
| `repos[]` (`RepoSpec`) | Repos to search |
| `codeResultsCount` | 0–100, default 15 |
| `textResultsCount` | 0–100, default 5 |
| `filePatterns[]` | Glob filters |
| `version` | `1.0` or `2.0`, default `1.0` |

### Cody Context response

`results[]` of `FileChunkContext { blob, startLine, endLine, chunkContent }` with associated `BlobInfo` / `RepositoryInfo` / `CommitInfo` metadata.

### Spec gaps observed in our integration

- `tools` and `tool_choice` are **not in the spec**. `tools` works on the live server; `tool_choice` does not.
- `tool` role messages aren't in the spec — must be remapped to `user` role.
- `stream: true` is labeled "Unsupported" in the spec but works.

---

## Surface 2 — Legacy GraphQL + SSE (`sourcegraph/llmsp`)

Marked **EXPERIMENTAL** in the README. No versioned releases, "API may change at any point". Useful only as fallback reference.

### Endpoints

- **GraphQL completions**: `POST {URL}/.api/graphql`
  - Query name: `GetCompletions`
  - Variables: `messages: [Message!]`, `temperature: Float`, `maxTokensToSample: Int`, `topK: Int`, `topP: Int`
  - `Message` shape: `{ speaker: "HUMAN" | "ASSISTANT", text: string }` (uppercase!)
  - Response: `data.completions` is a single string (not streamed)

- **SSE streaming**: `POST {URL}/.api/completions/stream`
  - Same body, but `speaker` is **lowercased** (`"human"`/`"assistant"`)
  - Response is SSE: `event:` / `data:` lines, terminator is `event: done`, payloads are `{ "completion": "..." }`

- **Embeddings / RepoID**: more GraphQL (`sourcegraph/embeddings` package). `GetRepoID(repoName)` is called after parsing `git remote get-url origin`.

### Auth

Same as surface 1: `Authorization: token <TOKEN>`. Independently confirms the `token` (not `Bearer`) prefix.

### Heuristics from llmsp source (informational only)

- `charsPerToken = 4`
- `maxPromptTokenLength = 7000`
- `maxCurrentFileTokens = 1000`
- Defaults: `temperature 0.2`, `maxTokensToSample 1000`, `topK -1`, `topP -1`

---

## What this means for `:agent`

1. **Auth header is correct** — `Authorization: token` is confirmed by both repos.
2. **`max_tokens` cap is folklore** — neither 4000 nor 8000 is enforced by the live gateway. Don't pin to either; use the model's context window.
3. **`/.api/cody/context` is a documented HTTP endpoint** — the agent could call it directly for `@repo` mention features without going through the Cody JSON-RPC agent.
4. **Speaker casing quirk** in the legacy SSE endpoint (`HUMAN` for GraphQL, `human` for SSE) is a Sourcegraph-side bug, not an llmsp bug — only matters if anyone ever falls back to surface 2.
5. **`tools` field works despite being undocumented** — keep this knowledge captured because the spec is misleading.
