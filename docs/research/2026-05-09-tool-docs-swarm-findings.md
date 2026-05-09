# Tool Documentation Swarm — Findings Log

Append-only log of subagent findings from the Phase 5 tool-docs swarm (`docs/plans/2026-05-09-tool-docs-swarm-prompt.md`). Each batch reports per-tool verdict + surprising findings + audit-note items + adjacent-tool observations the subagent flagged. Findings beyond the documentation block itself are captured here so they aren't lost when the subagent's context is discarded.

---

## Batch 1 — 2026-05-09 (smoke test)

Tools: `think`, `current_time`, `attempt_completion`.
Goal: validate the swarm prompt template before scaling. All three landed cleanly; pattern is ready for higher-volume batches.

### `think` — commit `00b73e5312`

- **Verdict:** KEEP STRONG.
- **Lines added:** 37 in `documentation()`, 43 in diff (including imports).
- **Real role:** satisfies `AgentLoop`'s tool-call cadence so reasoning isn't punished by `MAX_NO_TOOL_NUDGES=4` escalation, and isn't smuggled into args of side-effecting tools.

#### Surprising findings worth flagging
1. **Thought string lifecycle is fragile.** It survives in history only via the assistant turn that carried the `tool_call`, NOT via the tool's reply. Stage 2 conversation truncation can erase the deliberation entirely while the loop keeps running — meaning post-compaction, the LLM has no record of why it took a path.
2. **Token estimate under-reports cost.** `tokenEstimate = 2` (hardcoded) but the real spend is the assistant turn that emitted the call, not the reply.
3. **No per-session disable lever.** `think` is allowlisted to all 5 worker types and exempt from approval gates. If a user decides it's being used as a stalling tactic, there's no toggle.

### `current_time` — commit `d0b2a81a17`

- **Verdict:** KEEP STRONG.
- **Lines added:** 27 in `documentation()`, 33 in diff.
- **Real role:** fills a real gap — LLM training-cutoff drift causes sprint/deadline math to be off by months; `run_command date` is gated per-invocation and format-divergent across OSes.

#### Surprising findings worth flagging
1. **Registration tier mismatch (audit candidate).** `current_time` is registered as a **deferred** tool (`AgentService.kt:1043`, `safeRegisterDeferred("Utilities")`) despite essentially zero schema cost (no params, one-sentence description). It's invisible until the LLM `tool_search`es for it. **Action item:** consider promoting to core tier — discovery friction likely outweighs the schema-budget cost.
2. **UTC vs Local picking.** The tool exposes both `Local:` and `UTC:` fields. In practice the LLM tends to pick `Local:` for comparisons against Jira/Bamboo timestamps that are UTC-anchored — a recurring mistake. Captured in `commonLLMMistakes`.
3. **No monotonic clock warning.** The `description` doesn't warn against using results for elapsed-time/duration measurements. Captured as a downside; if Phase 4 ever revisits the description, a one-line hedge would help.

### `attempt_completion` — commit `af7ad50779`

- **Verdict:** STRONG keep.
- **Lines added:** 76 in `documentation()`, 82 in diff.
- **Real role:** the ReAct loop's only clean exit. Without it, every successful task degrades into `TEXT_ONLY_NUDGE` escalation (3 rounds of "[ERROR] You did not use a tool" in the orchestrator, or hard `NO_TOOLS_USED` failure in sub-agents).

#### Surprising findings worth flagging
1. **Silent batch-guard stripping.** `AgentLoop` (`loop/AgentLoop.kt:1334-1368`) silently removes `attempt_completion` from any multi-tool batch (e.g. `read_file + attempt_completion` in the same LLM turn), on the rationale that the summary would be speculation on un-observed tool results. The LLM is then nudged to re-issue completion alone in the next turn — **an invisible-to-LLM contract** worth surfacing in the docs (and possibly in the `description` itself).
2. **`next_step` is the only forward-looking field.** It leaks state INTO the next session by pre-filling the chat input as ghost-text. All other completion params are read-only artefacts of the just-finished task. This asymmetry isn't documented elsewhere.
3. **UI affordances depend on structured fields.** `kind` / `discovery` / `verify_how` / `next_step` drive ghost-text autocomplete, kind-coloured CompletionCard, and verify CTA. Can't be reconstructed from raw model text — strong argument against ever loosening the schema.
4. **Sub-agent symmetry pinned.** `attempt_completion` has `allowedWorkers = {ORCHESTRATOR}`; `task_report` excludes `ORCHESTRATOR`; `SpawnAgentTool.resolveConfigToolsTiered()` auto-injects `task_report` and silently drops any `attempt_completion` from a sub-agent config's `tools:` list. The two tools are a tightly coupled pair.

### Cross-cutting observations from Batch 1

- **Pattern works.** Three subagents in parallel, all compiled green, all committed cleanly with no schema drift, no `--no-verify`, no Co-Authored-By trailers.
- **Doc length tracks tool complexity** — 27 lines for the trivial `current_time`, 37 for the no-op `think`, 76 for the architecturally-load-bearing `attempt_completion`. No obvious padding; subagents calibrate naturally.
- **Pre-existing dirty tree noise** (BuildLogCache, AutomationPanel, bamboo tests) was correctly left untouched by all three subagents. The "do NOT touch tools other than {TOOL_NAME}" guard is doing its job.
- **Adjacent-tool flags are valuable.** The `current_time` subagent's note about its tier registration mismatch is the kind of audit-note insight a single-tool-focused human would miss. Future batches should preserve this signal in this file.

### Action items surfaced by Batch 1 (not blocking)

- [ ] Consider promoting `current_time` from deferred-Utilities to Core tier (audit note from Batch 1).
- [ ] Consider adding "not a monotonic clock" hedge to `current_time`'s `description` if it gets touched.
- [ ] Consider documenting the `attempt_completion` batch-stripping behavior in the LLM-facing `description` so the model doesn't waste a turn discovering the constraint.
- [ ] Consider adding a `commonLLMMistakes` entry on `think` once we observe one (current docs leave the list empty per the "concrete-or-empty" rule).
