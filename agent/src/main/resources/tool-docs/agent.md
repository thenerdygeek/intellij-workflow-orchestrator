# `agent` (SpawnAgentTool) — extended notes

## Why this tool is the architectural keystone

The `agent` tool is the single highest-leverage tool in the registry. Every other
tool serves a contained purpose; `agent` reshapes the entire agent's relationship to
its context window and to the work it does. Without it:

- **The orchestrator's 150K input window is the entire session's context budget.**
  Every file read, every search result, every diff lands in the parent's history. A
  serious refactor or audit task burns through that in 20–40 read_file/search_code
  calls — long before the LLM has finished thinking through the problem.
- **There is no parallelism.** Sequential tool calls are the only mode of work.
- **Persona specialization disappears.** Without sub-agents, the orchestrator's one
  generic system prompt has to cover security review, performance analysis, Spring
  internals, and Python framework quirks all at once. The 8 bundled specialists +
  user customs collapse into nothing.

So when we say "STRONG keep" on this tool, we mean: deleting it would make the agent
roughly half as useful.

## What's actually in the tool, vs what the docs claim

A documentation audit caught a meaningful drift: `agent/CLAUDE.md` says this tool
supports five actions — `spawn` (default), `run_in_background=true`, `resume`,
`kill`, and `send`. **The source code does not expose any of these as parameters.**

The actual parameters are:

- `description` (required) — 3-5 word UI label
- `prompt` (required) — full task brief
- `prompt_2`, `prompt_3`, `prompt_4`, `prompt_5` (optional) — parallel fan-out
- `description_2..5` (optional) — per-worker labels
- `agent_type` (optional) — persona selection
- `model` (optional) — model override

That's it. There is `cancelAgent(agentId)` on the class, but it is called from the
UI Kill button via `AgentController` — the LLM cannot kill agents, send messages, or
resume detached background workers. Either the docs are aspirational design notes
that never landed, or these features were removed and the docs weren't updated. The
documentation block flags this as an `observation` audit note so it doesn't get lost
again.

## Two modes, one entry point

The tool dispatches between two execution paths based on two facts:

1. **Is the resolved persona read-only?** — `inferPlanMode()` returns true if no
   tool in the resolved set is in `AgentLoop.WRITE_TOOLS`.
2. **Did the LLM provide `prompt_2..5`?**

| Read-only? | prompt_2..5 present? | Path | Workers |
|---|---|---|---|
| No (write-capable) | irrelevant | `executeSingle` | 1 |
| Yes (read-only) | No | `executeSingle` | 1 |
| Yes | Yes | `executeParallel` | 2-5 |

The silent fallback (write-capable + extra prompts → single mode, extra prompts
discarded) is a UX trap. The LLM doesn't see a warning; the result content is just
the single worker's output. The description does say "For agents with write tools,
only the primary prompt is used (sequential)" — but the LLM doesn't always read it.

## Tool resolution: `resolveConfigToolsTiered`

Every sub-agent runs with a **tier-filtered** subset of tools, not the full registry:

1. Take the persona's `tools:` YAML field — drop `agent` (depth-1 enforcement) and
   `attempt_completion` (orchestrator-only).
2. Resolve each name via `ToolRegistry.get()`. Unknown names log a warning but don't
   error.
3. Inject `task_report` if not already present. **This is the auto-injection that
   forces every persona, including custom ones, to use the sub-agent completion
   signal.**
4. Resolve `deferredTools:` similarly into a separate tier.

The `attempt_completion` filter is interesting: any persona's YAML can list
`attempt_completion` in its `tools:` field, but `resolveConfigToolsTiered` silently
drops it. The replacement `task_report` is what flows back to the parent's tool
result. From the parent's perspective, sub-agents never "complete the task" — they
"file a report".

## Parallel mode internals

`executeParallel` uses `supervisorScope { ... mapIndexed { idx, p -> async { ... }
}.map { it.await() } }`:

- **supervisorScope** so one worker's exception doesn't cancel siblings.
- Each child gets its own UUID `agentId` (8 hex chars from `UUID.randomUUID()`),
  registered in `runningAgents` for UI cancellation, removed in `finally`.
- Each child gets its own `subagentDebugDir` numbered `subagent-{N}-{slug}` under
  the parent session's `subagents/` directory.
- One explicit "spawn" event per child (with status RUNNING + label) so the UI
  renders one card per child. Subsequent per-tool ticks suppress `RUNNING` to avoid
  re-spawning cards (the original 77-card bug).
- Stats aggregation: **sum** for additive fields (toolCalls, inputTokens,
  outputTokens, cost, cache reads/writes); **max** for context fields
  (contextTokens, contextWindow, contextUsagePercentage) since each child has its
  own window.

`isError` is true ONLY if every worker failed. Partial success returns a normal
`ToolResult` with the failed entries marked inline.

## File ownership: whole-file granularity

`FileOwnershipRegistry` (in `SubagentModels.kt`) prevents two workers from editing
the same file. Important nuances:

- **Granularity is whole-file, not method or hunk.** Two workers editing different
  methods in the same `.kt` file conflict.
- **Write tools acquire ownership; reads only WARN.** A parallel reviewer can read
  what a parallel writer is mid-edit. The result may include partial state.
- **Released on COMPLETED / FAILED / KILL.** Crash recovery relies on `finally`
  blocks; a process kill leaves stale entries that are cleaned up at next spawn.

This is why parallel mode is gated to read-only personas — write conflicts at
whole-file granularity would deadlock.

## Inter-agent messaging: best-effort, lossy

`WorkerMessageBus` uses `Channel(capacity=20, DROP_OLDEST)`. Messages between parent
and children, or between sibling workers, are not durable — bursts above 20 silently
lose the oldest. The bus is consumed at ReAct-loop iteration boundaries on the
receiving side.

This is intentional: durable bidirectional channels would impose synchronization
costs that don't fit the agent's mental model (each sub-agent is supposed to be a
mostly-independent worker that reports a result, not a long-lived service). But
it's worth knowing — code that assumes message delivery is broken.

## No wall-clock timeout

`override val timeoutMs: Long get() = Long.MAX_VALUE`. Sub-agents are bounded by:

1. **Iteration cap (200)** — same as the orchestrator. Hits this if the LLM keeps
   calling tools without converging.
2. **Context budget (150K default)** — runs out when the sub-agent's history can't
   compact below the window.
3. **User-initiated abort** — Kill button → `cancelAgent(agentId)` →
   `runner.abort()` → loop exits at next iteration boundary.

A pathologically slow LLM provider can pin a worker for hours. The user always has
the Kill button as escape, but the orchestrator can't time out a sub-agent
programmatically.

## Persona pipeline: a separate document's worth of complexity

The persona system is documented at length in `agent/CLAUDE.md` under "Custom
Subagents", "Unified Sub-agent Prompt Pipeline", and "Agent Persona Filtering".
The short version:

- 8 bundled YAML files in `agent/src/main/resources/agents/`.
- 5 built-in types (general-purpose, explorer, coder, reviewer, tooler) — actually
  realized as YAML configs nowadays, not hard-coded.
- User customs in `~/.workflow-orchestrator/agents/{name}.md`.
- Project customs in `.workflow/agents/{name}.md` (project overrides user).
- Loaded via `AgentConfigLoader` with 300ms hot-reload watcher.
- IdeContext filter via `getFilteredConfigs(ideContext)` — `spring-boot-engineer`
  hidden in PyCharm; `python-engineer` hidden in IntelliJ-without-Python.
- System prompt built by `SubagentSystemPromptBuilder` calling shared
  `SystemPrompt.build()` with sub-agent-scoped flags + persona `prompt-sections:`
  overrides.

The complexity sits behind a single `agent_type` parameter, which is a decent
trade-off — but it does mean a single string typo silently picks `general-purpose`
(or errors with "Unknown agent type").

## Cost / benefit at a glance

| Aspect | Cost | Benefit |
| --- | --- | --- |
| Schema tokens | ~13 properties (description + prompt + 4×prompt + 4×description + agent_type + model) | Full delegation primitive + 5x parallel fan-out |
| Persona system | 8 bundled + customs need maintenance, hot-reload threads | Specialization without orchestrator prompt bloat |
| Hidden-mode complexity | Read-only-vs-write detection is implicit; silent fallback when wrong | One unified entry point — LLM doesn't have to pick `agent_single` vs `agent_parallel` |
| File ownership | Whole-file granularity is coarse | Prevents corruption from concurrent edits |
| No wall-clock timeout | Stuck workers can pin context for a long time | Long-running legitimate work isn't artificially cut off |

## Drop-decision summary

**Net verdict: STRONG keep the tool.** The architecture treats this tool as the
centerpiece of the context-management strategy and the persona system. Removing it
would make 60-80% of non-trivial agent sessions unworkable.

**Per-mode opinion is more nuanced.** Single mode is unambiguously load-bearing.
Parallel mode pays a meaningful schema cost (10 extra params) for what is plausibly
a rarely-used path. If telemetry shows fan-out is <5% of `agent` calls, splitting
parallel mode into a separate deferred-tier `agent_parallel` tool would cut the Core
schema by ~10 properties without losing functionality. That call is a WEAK drop on
the parallel mode specifically — defer until usage data is in.

**The biggest action item is reconciling the docs.** `agent/CLAUDE.md` describes
`resume`, `kill`, `send`, and `run_in_background` actions that don't exist. Either
build them (genuine value for long-running background research) or delete the docs.
The current state — phantom features in the canonical module doc — is the worst of
both worlds.
