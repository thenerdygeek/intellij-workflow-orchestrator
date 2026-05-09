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

---

## Batch 2 — 2026-05-09 (mid-complexity)

Tools: `glob_files`, `find_definition`, `tool_search`.
All landed clean; one **real bug** surfaced (see `find_definition` below).

### `glob_files` — commit `d7c33172`

- **Verdict:** KEEP STRONG.
- **Lines added:** 63 in `documentation()`, 69 in diff.
- **Real role:** 3-5× cheaper per discovery call than `run_command find`, with hard-coded `.git`/`build`/`node_modules` skip-list, PathValidator gating, and mtime-DESC sort that matches what the LLM actually wants.

#### Surprising findings worth flagging
1. **Bug-shaped behavior in mtime sorting.** The `max_results * 2` early-termination logic means on a very broad pattern (`**/*` on a large repo), the walk stops collecting after `2 * cap` candidates and only THOSE get mtime-sorted — so the "newest first" guarantee is **local to whatever the file walker happened to visit first, not global**. Invisible from the description.
2. **Symlink loops silently swallowed** — no cycle protection.
3. **`.gitignore` is not honored** — only the hard-coded skip-list. Means scanning a project with custom build dirs misses them.
4. **No skip-list opt-out.** Can't scan `node_modules/` even when explicitly wanted (e.g. auditing a dependency's source).
5. **Counterfactual surfaces a security regression.** `run_command find` bypasses PathValidator entirely, so a glob_files-less LLM could exfiltrate `/etc/passwd`-style paths.

### `find_definition` — commit `6bf719d0`

- **Verdict:** KEEP STRONG.
- **Lines added:** 60 in diff (54 in `documentation()` body + 6 imports/spacing).
- **Real role:** PSI-backed go-to-definition; counterfactual (`search_code` for symbol name) balloons tool-call count 3-5× and frequently lands the LLM on the wrong file because regex can't distinguish definition from usage.

#### Surprising findings worth flagging
1. **🚨 REAL BUG surfaced:** `FindDefinitionTool.resolveProvider()` no-element-context fallback is hardcoded to `registry.forLanguageId("JAVA") ?: registry.forLanguageId("kotlin")`. In a pure-PyCharm / Python-only setup, `JavaKotlinProvider` is not registered (only `PythonProvider` is, gated by `shouldRegisterPythonPsiTools`), so the very first call returns "Code intelligence not available — no language provider registered" **even though `PythonProvider` IS in the registry**. `find_definition` is **effectively unusable in pure-Python projects today**. Fix: iterate `registry.allProviders()` for the fallback (matching the pattern the `:agent` CLAUDE.md already documents for the per-tool provider iteration).
2. **Same shape may exist in adjacent PSI tools.** Worth auditing `FindReferencesTool`, `CallHierarchyTool`, `TypeHierarchyTool`, etc. for identical hardcoded JAVA/kotlin fallbacks.
3. **Disambiguation only fires for methods.** The `class_name` hint disambiguates overloads only for `PsiMethod` via the short-names cache — fields, Kotlin top-level functions, and Python functions don't get disambiguation.
4. **Dumb-mode failure has no internal wait.** Calling during indexing returns an error immediately instead of waiting for index completion.
5. **Kotlin top-level / extension functions miss by bare name.** The short-names cache only indexes class members, so `find_definition("doStuff")` for a top-level Kotlin `fun doStuff()` fails.

### `tool_search` — commit `728289bb`

- **Verdict:** KEEP STRONG.
- **Lines added:** 51 in `documentation()`, 57 in diff.
- **Real role:** the architectural keystone of the three-tier registry. Without it, every deferred tool's full JSON schema would live in the system prompt, regressing per-call schema tokens from ~4K back to ~10K.

#### Surprising findings worth flagging
1. **`getRelatedToolsHint` deliberately omits `git`.** Source comment: suggesting a non-existent `git` meta-tool sent the LLM into a dead-end search returning false-positive matches on the substring `git` in bitbucket/bamboo/sonar descriptions. Surfaces the fragility of keyword-overlap matching.
2. **Activation is one-way per session.** `resetActiveDeferred()` exists but is only called on new chat — once `jira` is activated, the schema overhead persists for the rest of the session even if Jira is never touched again. **Action item:** consider TTL-based deactivation for unused active-deferred tools mid-session.
3. **`select:` silently drops typos.** `mapNotNull` returns null for unknown names instead of erroring — the LLM gets no signal that one of its names was wrong, can't course-correct.
4. **Search has no minimum score threshold.** `ToolRegistry.searchDeferred()` does case-insensitive substring matching with no synonym handling and no minimum score, so a query like `"a"` would match basically everything with score 1.

### Action items surfaced by Batch 2

- [ ] **🚨 Fix `FindDefinitionTool.resolveProvider()` Java/Kotlin hardcoded fallback** — iterate `registry.allProviders()` instead. Real bug breaking pure-Python projects.
- [ ] **Audit other PSI tools** (`FindReferencesTool`, `CallHierarchyTool`, `TypeHierarchyTool`, `FindImplementationsTool`, etc.) for the same hardcoded-JAVA-fallback shape.
- [ ] Consider mtime-sort scope: should `glob_files` scan exhaustively before sorting, or document the cap-vs-globalness tradeoff?
- [ ] Consider symlink-cycle protection in `glob_files` walker.
- [ ] Consider `.gitignore` honoring (or document explicitly that it isn't).
- [ ] Consider TTL-based deactivation of unused active-deferred tools in `ToolRegistry`.
- [ ] Consider error signal on `tool_search select:` typos instead of silent drop.

### Cross-cutting observations from Batch 2

- **Subagents found a real bug.** `find_definition`'s pure-Python failure is the kind of thing that would be invisible without per-tool documentation review — the surfaced finding pays for the swarm cost on its own.
- **Doc length scales with surface area.** 51-63 lines for these tools vs 27 for `current_time`. Consistent ramp; no padding observed.
- **Adjacent-tool flags continue to be valuable.** Two of three subagents independently noted the dirty working tree (BuildLogCache et al.) and correctly left it alone.
- **Counterfactual is doing real work.** Two of three counterfactuals surfaced security or correctness regressions (PathValidator bypass for `glob_files`, regex misclassification for `find_definition`). This field is justifying its weight.

---

## Batch 3 — 2026-05-09 (FILE_WRITE cluster)

Tools: `edit_file`, `create_file`, `revert_file`. All FILE_WRITE, all WRITE_TOOLS, all approval-gated.
Findings include a **real bug** in `revert_file` (PathValidator bypass) and a **CLAUDE.md ↔ source mismatch** on `edit_file`.

### `edit_file` — commit `60dab5070`

- **Verdict:** KEEP STRONG.
- **Lines added:** 106 in `documentation()` + 138 lines narrative MD at `tool-docs/edit_file.md` (most extensive doc so far).
- **Real role:** the agent's primary code-modification primitive. Counterfactual is genuinely bad: `create_file` overwrites whole files, `run_command sed` bypasses VFS undo + editor sync + PathValidator (sed will edit `~/.ssh/`) + trips on macOS-vs-GNU sed flag differences.

#### Surprising findings worth flagging
1. **🚨 Matcher is character-strict, NOT whitespace-tolerant.** The DSL author corrected my (dispatcher's) wrong description in the prompt — `String.indexOf` / `String.replaceFirst`, no fuzzy fallback. Single different space/tab/CRLF returns 0 occurrences. **The swarm prompt template should be corrected** so future PSI / file-tool dispatches don't propagate this misconception.
2. **🚨 CLAUDE.md ↔ source mismatch.** `:agent` CLAUDE.md mentions `EditFileTool.lastEditLineRanges` "keyed by `sessionId:canonicalPath` to prevent cross-session contamination" — this **does not exist** in the actual source (`grep -rn` confirmed). CLAUDE.md is documenting something not implemented. **Action item:** sweep CLAUDE.md for other phantom references, or remove this one.
3. **Format clarification.** `edit_file` is **Claude Code-style `old_string`/`new_string`**, NOT Cline-style SEARCH/REPLACE blocks (the class KDoc explicitly calls this out). Documenting the format clearly in the swarm prompt would prevent future drift.
4. **Syntax validation is warn-not-block.** Kotlin/Java edits run through `SyntaxValidator` after writing, but errors are reported as a `WARNING:` block alongside the success result. The file is already on disk; the LLM must follow up with a fix or `revert_file`. Worth surfacing in the LLM-facing `description`.
5. **Three-tier write fallback.** Document API + WriteCommandAction → VFS `setBinaryContent` → `java.io.File.writeText`. Captured in mermaid flowchart.

### `create_file` — commit `85b761290`

- **Verdict:** KEEP STRONG.
- **Lines added:** 83 in `documentation()`.
- **Real role:** the only path combining PathValidator's project-root sandbox + WriteCommandAction (IDE undo) + parent-dir auto-creation + VFS refresh + unified-diff approval artifact. Counterfactual is `run_command` heredoc, regressing on every axis.

#### Surprising findings worth flagging
1. **🚨 Charset asymmetry — same call, different bytes.** The VFS write path encodes via `VirtualFile.charset`, but the I/O fallback hardcodes `Charsets.UTF_8`. On a non-UTF-8 project (e.g. Shift_JIS, CP1252), the same call writes different bytes depending on which path triggered. **Real correctness bug** for non-UTF-8 projects. **Action item:** unify on `VirtualFile.charset` (or document the asymmetry).
2. **🚨 `overwrite=true` produces a misleading approval diff.** The unified diff is always `""` → new content (full-file addition), so reviewers can't see what's actually changing on a rewrite. **Action item:** consider splitting into `overwrite_file` with a real diff, or compute the actual diff for the approval gate.
3. **Param name shadowing.** The `description` parameter (approval-dialog blurb) shares its name with the tool's LLM-facing `description` field — genuinely confusing in source. Worth renaming to `intent` or `reason`.

### `revert_file` — commit `bf41eabc0`

- **Verdict:** KEEP NORMAL (with a WEAK drop case noted — articulated below).
- **Lines added:** 56 in `documentation()`.
- **Real role:** thin wrapper around `git checkout -- <path>` via `ProcessBuilder`. Buys a session-approvable approval gate (vs `run_command`'s per-invocation gate) and a hook point for the deferred destructive-git-policy layer.

#### Surprising findings worth flagging
1. **🚨 `revert_file` does NOT use `PathValidator`.** Does its own naive `startsWith("/")` resolution + canonicalisation, then hands the path straight to `git checkout`. **Every other file tool in the codebase routes through `PathValidator.resolveAndValidateForRead` / `…ForWrite` — `revert_file` is the lone exception.** This is a real **security regression** vs the rest of the file-tool suite: `revert_file ../../../etc/foo` could potentially escape the project sandbox if git checkout follows it. **Action item:** route `revert_file` through `PathValidator.resolveAndValidateForWrite`.
2. **🚨 No VFS / Document refresh after checkout.** Open editor tabs may show stale pre-revert content until the IDE notices the on-disk change. The agent's own subsequent `read_file` may also see Document-cached pre-revert text via `FileDocumentManager.getCachedDocument()` in `ReadFileTool`. **Action item:** add explicit `LocalFileSystem.refreshAndFindFileByPath()` + `FileDocumentManager.reloadFromDisk()` after the git checkout.
3. **Required `description` parameter.** Unusual for write tools (edit_file/create_file have no analogous required justification). Embedded into the audit trail. Positive pattern worth keeping for destructive tools — possibly worth back-porting to `edit_file` / `create_file`.
4. **Behaviorally has zero IDE integration.** No VFS refresh, no editor reload, no LocalHistory, no PathValidator. The case for a WEAK drop is real if `run_command git checkout` could grow a session-approval modifier — but that's a bigger change.

### Action items surfaced by Batch 3

- [ ] **🚨 Fix `revert_file` PathValidator bypass** — route through `PathValidator.resolveAndValidateForWrite`. Real security regression.
- [ ] **🚨 Add VFS / Document refresh in `revert_file`** after the checkout completes.
- [ ] **🚨 Fix `create_file` charset asymmetry** — unify on `VirtualFile.charset` or document the divergence.
- [ ] **🚨 Fix `create_file` `overwrite=true` diff misleading display** — show actual diff in approval modal.
- [ ] **Sweep CLAUDE.md for phantom references** — `edit_file.lastEditLineRanges` is one example; there may be others.
- [ ] **Correct the swarm prompt template** — clarify `edit_file` is Claude-Code style `old_string`/`new_string`, character-strict matcher (not whitespace-tolerant). Prevents future doc drift.
- [ ] Consider renaming `create_file`'s `description` param to `intent` or `reason` to avoid shadowing the tool's `description` field.
- [ ] Consider surfacing `edit_file`'s warn-not-block syntax validation behavior in the LLM-facing `description`.
- [ ] Consider adding required `description` / `intent` param to `edit_file` and `create_file` (back-port from `revert_file`).

### Cross-cutting observations from Batch 3

- **Subagents found two more real bugs.** `revert_file` PathValidator bypass + `create_file` charset asymmetry. Combined with Batch 2's `find_definition` Java/Kotlin fallback bug, the swarm has now surfaced **three actionable defects** in 9 documented tools.
- **CLAUDE.md ↔ source drift is a real problem.** Catching it once (in `edit_file`) suggests there are other instances. Worth a dedicated audit pass.
- **The dispatcher prompt has bugs too.** I told the `edit_file` subagent the matcher was whitespace-tolerant — wrong. The subagent corrected it. Future dispatches should be lighter on prescriptive claims about behavior; let the subagent discover it from source.
- **Doc length keeps tracking complexity.** 106 + 138 (narrative) for `edit_file`, 83 for `create_file`, 56 for `revert_file`. The tool's load-bearingness is reflected in the doc surface.

---

## Batch 4 — 2026-05-09 (PSI cluster — bug-pattern verification)

Tools: `find_references`, `call_hierarchy`, `type_hierarchy`. Goal: verify whether the `find_definition` Java/Kotlin hardcoded-fallback bug spread to sibling PSI tools.

**Result:** bug is **isolated to `find_definition` + `find_references`**. Two siblings (`call_hierarchy`, `type_hierarchy`) use the correct pattern. The fix shape is now clear: switch to `registry.allProviders().firstNotNullOfOrNull { ... }` (which `call_hierarchy:41` and `type_hierarchy:35-48` already do).

### `find_references` — commit `fc2cf016d`

- **Verdict:** KEEP STRONG.
- **Lines added:** 69 in `documentation()`.
- **Real role:** the inverse-direction half of the find_definition pair. Counterfactual is unusually compelling — `search_code` for symbol name pollutes results with imports, comments, string literals, same-named-different-symbol matches that PSI references-search excludes by construction.

#### 🚨 Hardcoded fallback bug — CONFIRMED PRESENT

`FindReferencesTool.kt:151-155`, `resolveSearchTarget()` no-file-context branch:
```kotlin
// Global resolution via provider (fall back to hardcoded language IDs when no file context)
val provider = registry.forLanguageId("JAVA") ?: registry.forLanguageId("kotlin")
if (provider != null) {
    provider.findSymbol(project, symbol)?.let { return it }
}
```

In a pure-Python project with `PythonProvider` registered but no Java plugin, the global lookup skips `PythonProvider` entirely. Results come back only via the `PsiShortNamesCache` last-resort fallback (lines 158-160) — incidental, not designed.

**Workaround for users today:** pass `file` parameter to route through the file-scoped resolver (which iterates correctly).

### `call_hierarchy` — commit `ee0c8c5f4`

- **Verdict:** KEEP NORMAL.
- **Lines added:** 83 in `documentation()`.
- **Real role:** two genuine wins over repeated `find_references` calls — (1) IdentityHashMap-keyed cycle detection an LLM cannot reliably reproduce by hand on mutual call chains (A→B→A), and (2) the callee direction, for which `find_references` has no substitute (call-OUT vs reference-IN).

#### ✅ Bug pattern absent — uses correct `allProviders` iteration

`CallHierarchyTool.kt:41` uses `registry.allProviders()`. Lines 53-55 pick the first via `allProviders.firstNotNullOfOrNull { p -> p.findSymbol(...)?.let { p to it } }`. Pure-Python works correctly.

#### Surprising findings worth flagging
1. **🚨 Silent 30-entry cap in BOTH directions, with no truncation signal.** Caller chain and callee chain are both hardcoded-capped at 30 entries. Hub methods (logger.info, etc.) get 30 random callers and the LLM has no way to know whether that's "all" or "0.001%". **Action item:** either page (`offset`/`limit`) or return `truncated: true` flag.
2. **Callee direction is not recursive.** Returns direct callees only — to walk further, the LLM has to chain calls per node. Inverse of the caller direction's transitive walk.
3. **Lambda / method-reference resolution gaps.** Indirect calls via `::method` or lambda-captured methods are missed.

### `type_hierarchy` — commit `be5d27cc9`

- **Verdict:** KEEP STRONG (Java/Kotlin) / NORMAL or weak-drop (pure-Python).
- **Lines added:** 52 in `documentation()`.
- **Real role:** hierarchy queries are a daily navigation primitive in OO codebases. Counterfactual (parsing `extends`/`implements` across many files) scales 10-20× worse for deep trees.

#### ✅ Bug pattern absent — uses correct `allProviders` iteration

`TypeHierarchyTool.kt:35-48` uses `registry.allProviders().firstNotNullOfOrNull { p -> p.findSymbol(...)?.let { p to it } }`. Pure-Python works correctly.

#### Surprising findings worth flagging
1. **🚨 Asymmetric Java vs Python value.** Static PSI hierarchy is a *lower bound* in Python — metaclasses, `__init_subclass__`, dataclass-synthesized bases are invisible. The provider's declaration-walk does **not** match Python's C3 MRO, which the LLM may misuse when reasoning about method resolution order. **Action item:** either document this asymmetry in the LLM-facing `description`, or filter type_hierarchy out of the schema for Python-only projects.
2. **🚨 Subtype list silently capped at 30 with no paging.** Hub interfaces (`Runnable`, `Serializable`, popular event listeners) silently truncate. Same shape as `call_hierarchy`'s cap. **Action item:** likely the same fix shape — paging or truncated-flag.

### Action items surfaced by Batch 4

- [ ] **🚨 Fix the JAVA/kotlin hardcoded fallback in `FindDefinitionTool` AND `FindReferencesTool`** — switch to `registry.allProviders().firstNotNullOfOrNull { ... }`. The fix shape is canonical (`call_hierarchy` and `type_hierarchy` already do this correctly). Single PR can fix both tools.
- [ ] **Audit remaining PSI tools** for the same bug pattern: `find_implementations`, `file_structure`, `type_inference`, `dataflow_analysis`, `get_method_body`, `get_annotations`, `test_finder`, `structural_search`, `read_write_access`. Batch 5 should hit at least `find_implementations` and `file_structure`.
- [ ] **🚨 Address the silent 30-entry caps in `call_hierarchy` and `type_hierarchy`.** Either page (offset/limit) or return a `truncated: true` flag so the LLM knows when results are partial.
- [ ] Document (or filter) `type_hierarchy`'s asymmetric Java/Kotlin vs Python value.

### Cross-cutting observations from Batch 4

- **Bug pattern is isolated, not endemic.** 2 of 4 PSI tools so far have the bug. The fix shape is canonical and can be applied uniformly.
- **The "verify the bug" instruction in the swarm prompt worked.** All three subagents explicitly confirmed presence/absence with line cites — much more useful than a generic "look for bugs" instruction.
- **Tool-family clustering pays off.** Reading `find_definition`'s sibling docs gave subagents a concrete reference for tone + structure, accelerating their work. Tighter clusters → faster batches.
- **The 30-entry cap is a recurring pattern.** It's now showed up in `call_hierarchy` (callers, callees) and `type_hierarchy` (subtypes). Likely also in `find_references`, `find_implementations`. Worth a systematic audit.
