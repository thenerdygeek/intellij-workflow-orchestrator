# Mock Server — Sourcegraph (scripted agent) + Bitbucket

**Date:** 2026-06-26 · **Module:** `:mock-server` (standalone Ktor app, NOT a plugin module)
**Goal:** let the plugin be exercised end-to-end on a laptop with **no corporate access** to Sourcegraph / Bitbucket. The existing mock-server already covers **Jira / Bamboo / Sonar**; this adds the two missing backends so the **Agent chat** and the **PR tab** work locally.

## Scope (nothing deferred)
- **Sourcegraph — all 4 endpoints** (incl. the Cody vision stream + supported-models.json).
- **Bitbucket** — full `/rest/api/1.0/…` PR surface.
- **Scripted scenario engine** drives the agent's ReAct loop deterministically.
- Selection = **both** prompt-keyword tag and admin route.

## Existing pattern to mirror (read these first)
- `mock-server/src/main/kotlin/.../jira/{JiraMockRoutes,JiraDataFactory,JiraState}.kt`
- `.../bamboo/*`, `.../sonar/*` — same triad shape
- `.../admin/AdminRoutes.kt`, `.../admin/AuthMiddleware.kt`, `.../chaos/ChaosMiddleware.kt`
- `.../config/MockConfig.kt`, `.../MockServerMain.kt`
- Tests: `.../{jira,bamboo,sonar}/*Test.kt`, `.../IntegrationTest.kt`

## Plugin-side contracts to honor (read for exact JSON shapes)
- OpenAI-compatible chat: `core/.../ai/SourcegraphChatClient.kt`, `OpenAiCompatBrain.kt`, `core/.../ai/dto/ToolCallModels.kt`; agent side `agent/.../api/SourcegraphChatClient.kt` + `agent/.../api/dto/ToolCallModels.kt`
- Cody stream: `core/.../ai/SourcegraphCompletionsStreamClient.kt` + `core/.../ai/CodyStreamSseParser.kt` (events: `{"deltaText"}`, `{"completion"}`, `delta_tool_calls:[{id,type:"function",function:{name,arguments}}]`, `event: done`, `event: error`)
- Models / catalog: `core/.../ai/ModelCatalogService.kt`, `agent/.../brain/BrainFactory.kt` (model id format `provider::apiVersion::modelId`)
- Bitbucket DTOs: `core/.../bitbucket/*` (`BitbucketServiceImpl` + models), `core/.../BitbucketBranchClient*`, `pullrequest/.../service/*` (PrListService etc.)
- URL/auth: Sourcegraph URL = `ConnectionSettings.state.sourcegraphUrl`; Bitbucket Bearer token via `AuthMiddleware`.

## Endpoints

### Sourcegraph (`sourcegraph/SourcegraphMockRoutes.kt`)
| Path | Behavior |
|---|---|
| `GET /.api/llm/models` | ≥1 model, id `anthropic::2024-10-22::claude-sonnet-mock` (+ format BrainFactory accepts) |
| `GET /.api/modelconfig/supported-models.json` | catalog `ModelCatalogService` parses |
| `POST /.api/llm/chat/completions` | OpenAI SSE; plays active scenario's current Turn (`delta.content`, `delta.tool_calls`, `finish_reason`, `usage`) |
| `POST /.api/completions/stream` | Cody SSE of the **same** Turn (`deltaText` / `delta_tool_calls` / `event: done` / `event: error`) |

### Bitbucket (`bitbucket/BitbucketMockRoutes.kt`, `BitbucketDataFactory.kt`, `BitbucketState.kt`)
`/rest/api/1.0/`: `dashboard/pull-requests`, `users`, `projects/{p}/repos/{r}`, `…/branches`, `…/default-branch`, `…/pull-requests` (list), `…/pull-requests/{id}` (detail) + `/activities /changes /comments /commits /diff`, write endpoints `/merge /approve /decline /participants/{u}` (return success), `…/browse/{file}`, `…/settings/pull-requests/git`. Bearer auth.

## Scenario engine (`sourcegraph/scenario/`)
- **`Turn`** = one LLM reply: ordered chunks (`thinking?`, `text?`), optional `toolCalls: List<ToolCall(name, argsJson)>`, `finishReason` (`tool_calls` | `stop`), `usage(promptTokens, completionTokens)`.
- **`Scenario`** = `name` + `List<Turn>`. Tool names MUST be real agent tools so the loop executes them and returns; the final Turn calls `attempt_completion` → loop ends.
- **`ScenarioEngine`** is dialect-agnostic; `OpenAiSerializer` and `CodySerializer` render a `Turn` to each wire format. Per-conversation turn index in `ScenarioState` (keyed by a stable conversation hash or a monotonic counter reset on selection).
- **Selection:** prompt tag `[name]` in the latest user message → select + reset; else the admin-set default; else `read-and-finish`.
- `usage` numbers are realistic and monotonic so the **token meter** is verifiable.

### Scenario library (initial)
`read-and-finish` (default: thinking+text → `read_file` → `attempt_completion`) · `run-command-stream` (`run_command echo`/`ls` → live stdout/ANSI → finish) · `edit-scratch` (`create_file`/`edit_file` on a scratch path → diff + approval → finish) · `multi-tool` (3+ tools → tool-chain) · `plan-mode` (`enable_plan_mode` → `plan_mode_respond` → [Approve] → act → finish) · `long-stream` (long markdown w/ code+table → scroll/copy) · `error-retry` (Turn 1 = `event: error` / empty choices / `finish_reason:"length"` → recovery; pairs with ChaosMiddleware) · `spawn-subagents` (`spawn_agent` ×N) · `ask-question` (question tool → QuestionView).

## Admin
`AdminRoutes` += `POST /admin/sourcegraph/scenario {name}` (set default), `GET /admin/sourcegraph/scenarios` (list), `POST /admin/sourcegraph/reset` (clear turn indices). Bitbucket reuses the existing admin reset convention.

## Wiring
`MockServerMain` registers `sourcegraphRoutes()` + `bitbucketRoutes()` alongside the existing three; `MockConfig` gains a default port (8088) + scenario default. Auth/Chaos middleware apply uniformly.

## Pointing the plugin
Settings → Connections: Sourcegraph URL `http://localhost:8088` (token = anything), enable AI Agent; Bitbucket URL same host + configure a repo (`PROJ`/`my-repo` from the data factory); Jira/Bamboo/Sonar already supported. Run `./gradlew :mock-server:run`.

## Testing
`SourcegraphMockRoutesTest` (models + both completion dialects emit a parseable Turn), `ScenarioEngineTest` (turn advancement, selection, both serializers, terminates on `attempt_completion`), `BitbucketMockRoutesTest` (list/detail/diff/comments/write-success), extend `IntegrationTest`. Run `./gradlew :mock-server:test`.

## Non-goals
No real LLM; no Nexus/Docker-registry mock (plugin paths exist but out of scope for agent+connectors); no persistence beyond in-memory state.
