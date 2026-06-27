# Mock Server — Testing Report & Cowork Integration Handoff

> **Read this first — what is and isn't proven.**
> The `:mock-server` (Sourcegraph + Bitbucket additions) is **verified in isolation**: it compiles,
> 101 unit tests pass, and every endpoint was smoke-tested over real HTTP. It is **NOT yet verified
> against the live plugin** — i.e. nobody has pointed the running IntelliJ plugin at the mock and
> confirmed the Agent chat actually drives a scenario, tool cards render, or the PR tab populates.
> **Closing that gap is this handoff's job (for Claude cowork + the operator).**

Commit: `4b0237ea1` on `feature/plugin-split`. Usage reference: `mock-server/README.md`.

---

## Part A — What was tested (by me, automated/HTTP only)

### A1. Compile — ✅ PASS
```
./gradlew :mock-server:compileTestKotlin
→ BUILD SUCCESSFUL, 0 errors
```

### A2. Unit tests — ✅ 101 PASS / 0 FAIL (forced re-run, `--rerun-tasks`)
| Test class | tests |
|---|---|
| sourcegraph.ScenarioEngineTest | 17 |
| sourcegraph.SourcegraphMockRoutesTest | 6 |
| bitbucket.BitbucketMockRoutesTest | 32 |
| jira/bamboo/sonar/chaos/IntegrationTest (pre-existing) | 46 |
| **total** | **101 — 0 failures, 0 errors** |

The scenario-engine tests cover: turn advancement, selection (tag / admin / fallback),
terminates-on-`attempt_completion`, both SSE serializers, the **custom-scenario JSON round-trip**,
unknown-tool acceptance, and malformed-input rejection.

### A3. Live HTTP smoke — ✅ all 5 servers boot & respond
`./gradlew :mock-server:run` → Jira 8180 · Bamboo 8280 · Sonar 8380 · Bitbucket 8480 · Sourcegraph 8088.
Verified over `curl`:
- `GET /.api/llm/models` → `{"data":[{"id":"anthropic::2024-10-22::claude-sonnet-mock",…}]}`
- `POST /.api/llm/chat/completions` (default scenario) → streams `read_file` tool call + `usage` + `[DONE]`
- `POST /__admin/sourcegraph/scenario/custom` → `{"message":"registered+activated 'smoke' (1 turns)"}`
- `GET /__admin/scenarios` → all 13 scenarios
- `GET /rest/api/1.0/dashboard/pull-requests` (Bitbucket) → real PR JSON
- `GET /health` (each port) → `<name> mock is running`

### A4. Raw wire samples (so you can see exactly what the mock emits)

**OpenAI SSE — `/.api/llm/chat/completions`, default `read-and-finish`** (the agent's main path).
Note the tool call is emitted **as XML inside `delta.content`** (the dialect the plugin parses since
2026-05-13) *and* as a native `tool_calls` frame (harmlessly ignored by the current plugin):
```
data: {"choices":[{"index":0,"delta":{"role":"assistant"},"finish_reason":null}]}
data: {"choices":[{"index":0,"delta":{"content":"<thinking>\n…\n</thinking>\n\n"},"finish_reason":null}]}
data: {"choices":[{"index":0,"delta":{"content":"I'll start by reading the main file…"},…}]}
data: {"choices":[{"index":0,"delta":{"content":"\n<read_file>\n<path>src/main/kotlin/Main.kt</path>\n</read_file>\n"},…}]}
data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_mock_0","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"src/main/kotlin/Main.kt\"}"}}]},…}]}
data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}
data: {"choices":[],"usage":{"prompt_tokens":1200,"completion_tokens":80,"total_tokens":1280}}
data: [DONE]
```

**Cody SSE — `/.api/completions/stream`** (image/vision turns only):
```
event: completion
data: {"deltaText":"<thinking>…</thinking>\n\n"}
event: completion
data: {"deltaText":"…\n<read_file>\n<path>…</path>\n</read_file>\n"}
data: {"delta_tool_calls":[{"id":"toolu_mock_0","type":"function","function":{"name":"read_file","arguments":"{…}"}}]}
data: {"stopReason":"tool_use"}
event: done
```

### A5. Two latent bugs found & fixed while bringing the server up
1. **`NoClassDefFoundError: Function1` on `:run`** — the root build strips `kotlin-stdlib` from
   `runtimeClasspath` (a plugin-ZIP optimization) and that leaked onto the standalone app. Fixed by
   exempting `:mock-server` from the exclude + declaring stdlib + relock.
2. **Only one port bound** — `main()` started servers with `launch{ …start(wait=true) }` on
   `runBlocking`'s single thread; the first blocked the rest. Fixed with `launch(Dispatchers.IO)`.

---

## Part B — What was NOT tested (cowork must verify this)

**The live plugin ↔ mock integration.** I cannot click IDE Settings or drive the Agent chat, so
none of the following is proven:
- That `BrainFactory` accepts the mock's model and the Agent session actually starts.
- That the plugin's SSE parser consumes the mock's stream and **the XML tool call triggers real tool
  execution** (the load-bearing assumption — if the dialect is even slightly off, the agent gets text
  but never runs a tool).
- That tool cards, the token meter, plan-mode, the completion card, etc. render from mock-driven turns.
- That the Bitbucket PR tab populates from the mock and PR detail/diff/comments render.

The wire format **matches** the plugin's parser (verified by reading `SourcegraphChatClient` /
`CodyStreamSseParser` and confirmed in A4), so the contract is sound — but only a live run proves it.

---

## Part C — Cowork integration-test procedure

### C0. Start the mock
```bash
./gradlew :mock-server:run     # leave running in its own terminal
```

### C1. Point the plugin at it (in the runIde sandbox)
Settings → Tools → Workflow Orchestrator → **Connections**:

| Field | Value |
|---|---|
| Sourcegraph URL | `http://localhost:8088` · token `mock` · **enable AI Agent features** |
| Bitbucket URL | `http://localhost:8480` · token `mock` · project `PROJ` · repo `my-repo` |
| Jira / Bamboo / Sonar | `http://localhost:8180` / `:8280` / `:8380` · token `mock` |

Apply. Confirm the AI-Agent "Sourcegraph needs to be set up" banner clears and a model appears.

> **First gate:** open the Agent chat and send a plain message. If you see streaming text + a
> `read_file` tool card + a completion card, the integration works. If you see text but **no tool
> card**, the XML-tool-call dialect isn't being parsed — capture `…/sandbox/IU-2025.1.7/log/idea.log`
> + the agent JSONL and report (this is the highest-risk unknown).

### C2. Drive each built-in scenario (select via `[tag]` at the START of your prompt)
Cross-reference the behavioral test plan (`docs/qa/behavioral-test-plan/`) — these scenarios were
designed to exercise its checks:

| Prompt | Exercises (test-plan section) |
|---|---|
| `[read-and-finish] summarize` | streaming, READ tool card, completion card, token meter (§03/§04) |
| `[run-command-stream] run it` | live stdout streaming, ANSI, timeout cap (TOOL-13…20) |
| `[edit-scratch] edit` | diff preview, +/- stats, approval gate (TOOL-21…27, HDR-25) ⛔ scratch file only |
| `[multi-tool] do several` | tool-chain grouping (TOOL-1…3) |
| `[plan-mode] plan X` | plan card → **Approve** → act → PlanApprovedBubble (HDR-19…24) |
| `[long-stream] explain` | long markdown/code, **scroll stick/release**, code-copy (MSG §F, MSG-14) |
| `[error-retry] go` | offline/retry banner, recovery (HDR / P1-A5) |
| `[spawn-subagents] parallel` | sub-agent cards ≤5 (HIS-22…26) |
| `[ask-question] ask me` | QuestionView options/text (HDR-30) |
| `[monitors] watch` | monitor registers; idle-wake card when it fires (S9) |
| `[delegation] delegate` | delegation cards (single box = empty-targets/observe-only) |
| `[background-process] background` | BackgroundIndicator chip (HDR-33) |
| `[compaction] big` | context meter toward 88% + compaction marker/reset (HDR-9/10) — best-effort |

For each: tick the matching ✅ checks in the test plan; note any 🐞 bug-signals; record PASS/FAIL in
`docs/qa/behavioral-test-plan/RESULTS-2026-06-26.md` using the README §3 report format.

### C3. Author custom flows (the powerful part)
To exercise anything not in the library, POST your own turn-sequence (no token needed):
```bash
curl -X POST http://localhost:8088/__admin/sourcegraph/scenario/custom \
  -H 'Content-Type: application/json' -d '{
    "name":"my-flow",
    "turns":[
      {"thinking":"…","text":"Reading…",
       "toolCalls":[{"name":"read_file","arguments":{"path":"SCRATCH.md"}}],
       "finishReason":"tool_calls","usage":{"promptTokens":1200,"completionTokens":40}},
      {"text":"Done.","toolCalls":[{"name":"attempt_completion","arguments":{"kind":"done","result":"ok"}}],
       "finishReason":"stop","usage":{"promptTokens":1300,"completionTokens":20}}
    ]}'
```
Registering activates it (becomes default + resets turn indices). Then send any message in the Agent
chat — the plugin plays your turns. Any tool name is accepted (so you can test unknown-tool handling).
Full schema: `mock-server/README.md`.

### C4. Bitbucket / connector tabs
PR tab → confirm the 4 PRs render (OPEN×2/MERGED/DECLINED), open a PR → detail/diff/comments/commits;
**stop at every write control** (Merge/Approve/Decline) per the read-only rule — verify the dialog, do
not execute. Swap data via `POST http://localhost:8480/__admin/scenario/{all-merged|empty|happy-path}`.

---

## Part D — Known limitations & risks to watch

- **[HIGHEST RISK] Dialect parsing is unverified live.** If tool cards never appear, the agent is
  getting the stream but not extracting the XML tool call — report immediately with logs.
- **Scenario `[tag]` selection works on the OpenAI path only**, not the Cody (`/.api/completions/stream`)
  path (it reads `content`, not Cody's `{speaker,text}`). Cody is image-turns only; for those, select
  via `POST /__admin/scenario/{name}` instead. Admin-route selection works on both paths.
- **The mock ignores the actual conversation** (except the leading `[tag]`): it plays the scripted
  turns regardless of what the agent's real prompt/history says. That's by design — you're scripting
  the LLM — but it means the "assistant" won't react to the tool *results*; it just advances to the
  next scripted turn.
- **`compaction` is best-effort** — it reports large `usage` numbers (118K/128K); whether the marker
  trips depends on whether the plugin trusts reported usage vs. recounting history.
- **`monitors` / `delegation`** need live IDE state (an active session; reachable IDE instances). On a
  single box, `delegation` renders the empty-targets/offline UI — that's the observe-only path.
- **Vision turns**: image attachments route to `/.api/completions/stream`; the mock serves it, but
  end-to-end image handling wasn't smoke-tested.

## References
- `mock-server/README.md` — run + scenario + custom-JSON reference
- `docs/qa/behavioral-test-plan/` — the 285-scenario UI test plan these scenarios feed
- `docs/superpowers/specs/2026-06-26-mock-server-sourcegraph-bitbucket-design.md` — design spec
- Logs to attach on failure: `…/sandbox/IU-2025.1.7/log/idea.log`, agent JSONL
  (`~/.workflow-orchestrator/<proj>-<sha6>/logs/agent-*.jsonl`), `~/.workflow-orchestrator/diagnostics/plugin-0.log`
