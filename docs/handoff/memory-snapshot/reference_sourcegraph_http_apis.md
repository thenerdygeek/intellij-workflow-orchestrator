---
name: Sourcegraph HTTP APIs (OpenAPI spec + llmsp)
description: Authoritative endpoints, auth, request/response shapes for /.api/llm/* and /.api/cody/context, plus the legacy llmsp GraphQL/SSE surface
type: reference
---

Two distinct Sourcegraph LLM HTTP surfaces exist. The agent module should target the OpenAI-compatible REST one; the GraphQL one is legacy/experimental.

## 1. Modern OpenAI-compatible REST — `sourcegraph/openapi` (openapi.Sourcegraph.Latest.yaml)

This is the **authoritative, supported** API for enterprise integrations.

### Endpoints
| Method | Path | Operation |
|---|---|---|
| POST | `/.api/llm/chat/completions` | Chat completions (OpenAI-shaped) |
| GET  | `/.api/llm/models` | List models |
| GET  | `/.api/llm/models/{modelId}` | Retrieve one model |
| POST | `/.api/cody/context` | Repo-aware context retrieval |

### Auth
- Scheme: `SourcegraphTokenAuth`
- Header: `Authorization: token <TOKEN>` (NOT `Bearer`)
- Token formats: `sgp_<16hex|local>_<40hex>` (v3), `sgp_<40hex>` (v2 deprecated), `<40hex>` (v1 deprecated)

### Chat completions request fields
`model` (required, format `${ProviderID}::${APIVersionID}::${ModelID}` e.g. `anthropic::2023-06-01::claude-3.5-sonnet`),
`messages`, `max_tokens` (**spec says 8000 but this is OUTDATED — live server has no such cap; probes pass 100K**), `temperature`, `top_p`, `frequency_penalty`, `presence_penalty`,
`logprobs`, `top_logprobs`, `logit_bias`, `seed`, `response_format` (`text`|`json_object`),
`stream`, `stream_options`, `stop`, `n`, `user`, `service_tier`.

### Chat completions response fields
`id`, `object`, `created`, `model`, `choices[]` (each with `ChatCompletionResponseMessage` + `ChatCompletionLogprobs`),
`usage` (CompletionUsage), `system_fingerprint`, `service_tier`.

### Cody context request fields
`query` (required), `repos[]` (RepoSpec), `codeResultsCount` (0–100, default 15),
`textResultsCount` (0–100, default 5), `filePatterns[]`, `version` (`1.0`|`2.0`, default `1.0`).

### Cody context response
`results[]` of `FileChunkContext { blob, startLine, endLine, chunkContent }`,
plus `BlobInfo` / `RepositoryInfo` / `CommitInfo`.

### Schemas
Requests: `CodyContextRequest`, `CreateChatCompletionRequest`, `ChatCompletionRequestMessage`, `RepoSpec`, `MessageContentPart`, `ChatCompletionStreamOptions`.
Responses: `CodyContextResponse`, `CreateChatCompletionResponse`, `OAIListModelsResponse`, `OAIModel`, `ChatCompletionChoice`, `ChatCompletionResponseMessage`, `FileChunkContext`, `BlobInfo`, `RepositoryInfo`, `CommitInfo`, `CompletionUsage`, `Error`.
Supporting: `ChatCompletionLogprobs`, `ChatCompletionTokenLogprob`.

## 2. Legacy GraphQL + SSE — `sourcegraph/llmsp` (experimental, deprecated-feeling)

Used by the proof-of-concept Go LSP. Not what enterprise agent integrations should target, but useful as a fallback reference if the REST surface ever lacks something.

### Endpoints
- **GraphQL completions:** `POST {URL}/.api/graphql`
  - Query name: `GetCompletions`
  - Variables: `messages: [Message!]`, `temperature: Float`, `maxTokensToSample: Int`, `topK: Int`, `topP: Int`
  - `Message` = `{ speaker: "HUMAN" | "ASSISTANT", text: string }`
  - Response: `data.completions` (single string, no streaming)
- **SSE streaming:** `POST {URL}/.api/completions/stream`
  - Same body, but `speaker` is **lowercased** (`"human"`/`"assistant"`)
  - Response: `event:`/`data:` SSE lines, terminator `event: done`, payload `{ "completion": "..." }`
- **Embeddings/RepoID:** `GraphQL` again (`sourcegraph/embeddings` package), uses `GetRepoID(repoName)` from `git remote get-url origin`.

### Auth
Same `Authorization: token <TOKEN>` (independently confirmed by both repos).

### Heuristics from llmsp source (informational)
- `charsPerToken = 4`
- `maxPromptTokenLength = 7000`
- `maxCurrentFileTokens = 1000`
- Defaults: `temperature 0.2`, `maxTokensToSample 1000`, `topK -1`, `topP -1`

## Why this matters for `:agent`

- The plugin's existing `Authorization: token` choice is correct on both surfaces.
- The published OpenAPI spec declares `max_tokens` ≤ 8000, but the live server accepts 100K (probe-verified). Both 4000 and 8000 caps are outdated documentation artifacts. Don't pin to either number — use what the model context supports.
- `/.api/cody/context` is a documented HTTP route for repo-aware retrieval. The `:agent` module could use it directly for `@repo` mention features without going through the Cody JSON-RPC agent.
- The two speaker-casing conventions in llmsp (`HUMAN` vs `human`) are a Sourcegraph quirk, not an llmsp bug — only relevant if anyone ever falls back to the legacy endpoints.
