---
name: project_tool_param_schema_parser_whitelist_trap
description: Trap — a tool/action param read in execute() but missing from parameters.properties is silently dropped by the XML parser; array params arrive as strings
metadata: 
  node_type: memory
  type: project
  originSessionId: 3bb97f63-44bb-4312-990c-f3dfa9bad821
---

**⚠ TRAP (root-caused + fixed 2026-06-05, branch perf/token-context-optimization, committed `16d3dd20d`, unpushed):** Agent tool params have TWO non-obvious failure modes on the XML-in-content path (the only path post-2026-05-13; `tools:[]` is gone):

1. **Schema is the parser whitelist.** `AssistantMessageParser.parse` only recognizes a `<param>` tag if `param` is in `allParamNames()` (`ToolRegistry.kt:265`), which is built solely from every tool's declared `parameters.properties.keys`. A param read in `execute()` but **not declared in the schema is silently dropped** — the tag is treated as prose. Unit tests that pass a `JsonObject` straight into the action bypass the parser, so they stay green while the live LLM path is 100% broken.

2. **Every XML param value is serialized as a STRING.** `BrainRouter.kt:~252` does `put(k, JsonPrimitive(v))` for every parsed param regardless of schema type. So an `array`-typed param (e.g. `modules`) arrives as a `JsonPrimitive` string, and `params["x"]?.jsonArray` **throws**. Array params must parse string forms (`"a,b"`, `"a b"`, `["a","b"]` literal) — see `parseModules` in `RunMavenGoalAction.kt`.

**Surfaced by:** `run_maven_goal` ("goals is blank" despite goals provided) — the action/enum/dispatch/description were added but `goals`/`modules`/`offline`/`extra_args` were never added to `JavaRuntimeExecTool.parameters`. Fix = declare them + add `action("run_maven_goal")` to the `toolDoc` DSL (parity test `ToolDslSchemaParityTest` enforces every schema param has DSL coverage) + string-tolerant `parseModules`. Pinned by a parser round-trip test in `JavaRuntimeExecToolTest`.

Same session also fixed (TDD, full :agent green): `compile_module` hard 120s → configurable `timeout` (default 300, max 900) + actionable timeout message (`resolveCompileTimeoutSeconds`/`compileTimeoutMessage`); `runtime_exec run_config` EXITED_BEFORE_READY now appends process tail output (`withTailOutput`) at BOTH return sites like TIMEOUT_WAITING_FOR_READY. All from agent tool-feedback triage (repo_A). Uncommitted.

**Checklist when adding any tool/action param:** declare it in `parameters.properties` (not just `execute()` + description); if `type=array`, parse string forms not `.jsonArray`; add a `toolDoc` DSL `params{}` entry with `llmSeesIt` matching the schema description verbatim (or `ToolDslSchemaParityTest` fails).
