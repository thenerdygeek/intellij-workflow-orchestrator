---
name: project-mock-server-sourcegraph-bitbucket
description: Local mock server for Sourcegraph (scripted agent) + Bitbucket — run plugin without corporate access
metadata: 
  node_type: memory
  type: project
  originSessionId: 0ed317b6-6756-4010-8ff1-8be639bc09fc
---

**`:mock-server` now mocks ALL backends the plugin calls**, so the plugin runs end-to-end on a
laptop with **no corporate access** (this laptop can't reach Sourcegraph; Jira/Bitbucket need
corporate, not individual). Committed `4b0237ea1` on `feature/plugin-split` (NOT pushed). Built
2026-06-26 via brainstorm→spec (`docs/superpowers/specs/2026-06-26-mock-server-sourcegraph-bitbucket-design.md`)
→ 2 parallel implementer subagents → my wiring. **101 mock-server tests pass + live smoke of all 5
servers verified.** Usage: `mock-server/README.md`.

It's a standalone Ktor app (`./gradlew :mock-server:run`), 5 servers each on its own port:
Jira 8180 · Bamboo 8280 · Sonar 8380 · **Bitbucket 8480** · **Sourcegraph 8088** (the two new ones).
Point the plugin: Settings→Connections, Sourcegraph URL `http://localhost:8088` (token=anything,
enable AI Agent), Bitbucket `http://localhost:8480` project `PROJ` repo `my-repo`. **This unblocks
the §4 suspected-bug hunt** that was BLOCKED on no-tokens (see [[project_runide_behavioral_test_plan]]).

**Sourcegraph = scripted-agent harness** (the interesting part): a dialect-agnostic scenario engine
serves both `/.api/llm/chat/completions` (OpenAI SSE) and `/.api/completions/stream` (Cody SSE), plus
`/.api/llm/models` + `/.api/modelconfig/supported-models.json`. **Tool calls are emitted as
XML-in-content** (the dialect the plugin parses since 2026-05-13 — NOT native `delta.tool_calls`;
load-bearing). 13 built-in scenarios (read-and-finish=default, run-command-stream, edit-scratch,
multi-tool, plan-mode, long-stream, error-retry, spawn-subagents, ask-question, monitors, delegation,
background-process, compaction) + **dynamic custom scenarios**: a tester/cowork POSTs a JSON
turn-sequence to `/__admin/sourcegraph/scenario/custom` to make the mock return exactly the
assistant turns they want, then observe plugin behavior. Select built-ins by `[name]` prompt tag or
`POST /__admin/scenario/{name}`. Real tool names verified: `agent` (not spawn_agent), `monitor`,
`delegation`, `run_command{background:true}` + `background_process`, `ask_followup_question`.

⚠ Two latent bugs fixed in the standalone app (both pre-existed, exposed by first real `:run`):
(1) the root build's plugin-dist `kotlin-stdlib` exclude from `runtimeClasspath` leaked onto
:mock-server → `NoClassDefFoundError Function1` on `:run`; fixed by exempting `project.name ==
"mock-server"` in the root `subprojects` exclude + explicit `implementation(kotlin("stdlib"))` +
relock. (2) `main()` started servers with `launch{ ...start(wait=true) }` on `runBlocking`'s single
thread → first server starved the rest (only one port bound); fixed with `launch(Dispatchers.IO)`.
See [[project_runide_smoke_campaign]].
