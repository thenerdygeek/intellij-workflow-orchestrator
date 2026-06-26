# Workflow Orchestrator — Mock Server

A standalone Ktor app that mocks the backends the plugin talks to, so the plugin can be
exercised **on a laptop with no corporate access** (Sourcegraph / Jira / Bitbucket / Bamboo / Sonar).

```bash
./gradlew :mock-server:run
```

Starts five servers, each on its own port:

| Backend | URL | Selection |
|---|---|---|
| Jira | `http://localhost:8180` | `POST /__admin/scenario/{name}` |
| Bamboo | `http://localhost:8280` | `POST /__admin/scenario/{name}` |
| SonarQube | `http://localhost:8380` | `POST /__admin/scenario/{name}` |
| **Bitbucket** | `http://localhost:8480` | `POST /__admin/scenario/{name}` |
| **Sourcegraph (Agent LLM)** | `http://localhost:8088` | scenarios — see below |

Each port also serves `GET /health`, `GET /__admin/state`, `GET /__admin/scenarios`,
`POST /__admin/reset`, and chaos toggles under `/__admin/chaos` (inject latency/failures).
Auth is permissive: any non-blank `Authorization` header passes.

## Point the plugin at it

Settings → Tools → Workflow Orchestrator → **Connections**:

| Field | Value |
|---|---|
| Sourcegraph URL | `http://localhost:8088` · token `mock` · **enable AI Agent features** |
| Bitbucket URL | `http://localhost:8480` · token `mock` · project `PROJ` · repo `my-repo` |
| Jira / Bamboo / Sonar URL | `http://localhost:8180` / `:8280` / `:8380` · token `mock` |

## Sourcegraph: scripted agent scenarios

The mock LLM plays a **scripted sequence of turns** (tool calls + text + token usage) that drives the
agent's ReAct loop deterministically. Every non-error scenario ends with `attempt_completion` so the
loop terminates.

**13 built-in scenarios:**
`read-and-finish` (default) · `run-command-stream` · `edit-scratch` · `multi-tool` · `plan-mode` ·
`long-stream` · `error-retry` · `spawn-subagents` · `ask-question` · `monitors` · `delegation` ·
`background-process` · `compaction`

**Select a scenario** two ways:
- **Prompt tag** — begin your Agent-chat message with `[scenario-name]` (e.g. `[plan-mode] do X`). Selects + resets to turn 0.
- **Admin route** — `curl -X POST http://localhost:8088/__admin/scenario/plan-mode` (sets the default for untagged chats).

### Author your own conversation (dynamic scenarios)

Make the mock return **exactly** the assistant turns you want, then watch the plugin react — ideal for
a Claude-cowork tester exercising arbitrary flows (monitors, delegation, error paths, unknown tools):

```bash
curl -X POST http://localhost:8088/__admin/sourcegraph/scenario/custom \
  -H 'Content-Type: application/json' -d '{
  "name": "my-flow",
  "turns": [
    { "thinking": "let me look", "text": "Reading the file…",
      "toolCalls": [ { "name": "read_file", "arguments": { "path": "SCRATCH.md" } } ],
      "finishReason": "tool_calls",
      "usage": { "promptTokens": 1200, "completionTokens": 40 } },
    { "text": "Done.",
      "toolCalls": [ { "name": "attempt_completion", "arguments": { "kind": "done", "result": "Summarized." } } ],
      "finishReason": "stop", "usage": { "promptTokens": 1300, "completionTokens": 20 } }
  ]
}'
```

Registering **activates** the scenario (becomes default + resets all turn indices).

**Turn schema:**
- `thinking?` — emitted wrapped in `<thinking>` on the wire.
- `text?` — single prose chunk; `textChunks?: string[]` overrides it for multi-chunk streaming.
- `toolCalls?: [{ name, arguments? (JSON object) | argumentsJson? (raw JSON string) }]` — **any** tool name is accepted (so you can test unknown-tool handling). Tool calls are emitted as XML-in-content (the dialect the plugin parses since 2026-05-13) **and** as native tool-call frames.
- `finishReason?` — `tool_calls` | `stop` | `length`. Defaults to `tool_calls` if the turn has tool calls, else `stop`.
- `usage?: { promptTokens, completionTokens }` — drives the on-screen token meter (use realistic, monotonic numbers).
- `error?` — emits a Cody `event: error` frame for this turn (for the offline/retry UI).

### Feature-area scenarios (tool names verified against `agent/.../tools/`)
- **`monitors`** — `monitor{action:"start", source:"shell", command:"date +%S", …}` → `monitor{action:"list"}` → finish. Observe the monitor registering + the idle-wake card when it fires (needs an active session).
- **`delegation`** — `delegation{action:"list_targets"}` → `delegation{action:"send", …}` → finish. On a single box this renders the empty-targets / offline-picker UI (observe-only).
- **`background-process`** — `run_command{…, background:true}` (→ BackgroundIndicator chip) + `background_process{action:"list"}` → finish.
- **`compaction`** — reports large `usage.promptTokens` (118K/128K) to nudge the context meter toward the ~88% compaction threshold (best-effort; depends on whether the plugin trusts reported usage vs. recounting history).

## Bitbucket scenarios
`default` (4 PRs: OPEN×2, MERGED, DECLINED) · `all-merged` · `empty` · `happy-path`. Write endpoints
(merge/approve/decline/comment) return success so the plugin's read-only "verify dialog then cancel"
flows still render.

## Tests
```bash
./gradlew :mock-server:test
```
