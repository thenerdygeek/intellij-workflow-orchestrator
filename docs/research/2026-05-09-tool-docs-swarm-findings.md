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

---

## Batch 5 — 2026-05-09 (continue PSI audit + broaden)

Tools: `search_code`, `diagnostics`, `find_implementations`. Mixed cluster — heavy-use READ_ONLY content tool, IDE state tool, one more PSI tool to verify the bug pattern.

**Bug verdict (definitive):** the JAVA/kotlin hardcoded fallback is **isolated to `find_definition` + `find_references`**. The other 3 PSI tools surveyed (`call_hierarchy`, `type_hierarchy`, `find_implementations`) all use the correct `allProviders().firstNotNullOfOrNull` pattern. `find_implementations` is now the canonical reference template.

### `search_code` — commit `5bffc24cc`

- **Verdict:** KEEP STRONG.
- **Lines added:** 155 in `documentation()` (longest doc so far after `edit_file`).
- **Real role:** the only PSI-free content search; pairs with `glob_files` (paths) and `find_definition`/`find_references` (PSI). Counterfactual `run_command grep -r` is approval-gated per-call, format-divergent across BSD/GNU, bypasses PathValidator, and pollutes results with skip-list noise.

#### Surprising findings worth flagging
1. **🚨 Invalid regex silently degrades to literal match.** A typo'd pattern auto-falls-back to `Regex.escape(pattern)` literal-match with **no error surfaced to the LLM**. The LLM gets wrong results with no signal that its regex was broken. **Action item:** error on regex compile failure instead of silently degrading; OR include a "regex parse failed; falling back to literal match" line in the result so the LLM knows.
2. **Per-line matching only.** No multi-line patterns supported. Means cross-line constructs (e.g. function declarations that span lines) can't be matched.
3. **1MB per-file cap silently skips files.** Generated dumps, large fixtures, minified assets are invisible. **Action item:** surface skipped files in result metadata.
4. **Walk order is alphabetical, NOT mtime-sorted.** Unlike `glob_files` which mtime-sorts within the cap. Inconsistency between sister tools.
5. **Undocumented backward-compat aliases.** `query`→`pattern` and `scope`→`path` aliases exist but the LLM never sees them. Either remove or document.

### `diagnostics` — commit `cdf106934`

- **Verdict:** KEEP STRONG.
- **Lines added:** 64 in `documentation()`.
- **Real role:** replaces a 30-90s build cycle with sub-second PSI walk returning line-precise issues. Dominant reason edit-then-verify loops finish in seconds.

#### Surprising findings worth flagging
1. **🚨 `isError=false` semantics is load-bearing across an ENTIRE TOOL FAMILY.** `diagnostics`, `RunInspectionsTool`, `ListQuickFixesTool`, `ProblemViewTool` all share the same contract: `isError=false` means "tool ran successfully, here's the problem list" — NOT "no problems found." LLM may misinterpret. **Action item:** unify the contract (e.g. add a `problemCount` to each result so the LLM doesn't have to interpret `isError`); OR rename the field to make the meaning explicit.
2. **DumbService blocks during indexing** — returns `isError=true`. LLM should wait, not retry immediately.
3. **`start_line`/`end_line` must be paired** — silent fallthrough otherwise.
4. **Unsupported languages return benign "not available" message** — JSON/YAML/Go/TS. LLMs sometimes retry interpreting it as a transient error.
5. **Phase 7 follow-ups pinned in source:** `column=-1` and `hasQuickFix=false` are placeholders for future enrichment. Documenting these prevents them from being treated as bugs.

### `find_implementations` — commit `72f9294e7`

- **Verdict:** KEEP STRONG.
- **Lines added:** 57 in `documentation()`.
- **Real role:** PSI's `OverridingMethodsSearch` + `ClassInheritorsSearch` are the index-backed answers to "who satisfies this contract." No name-based substitute gets close on accuracy or token cost.

#### ✅ Bug pattern absent — uses correct `allProviders` iteration

`FindImplementationsTool.kt:38-59` uses `registry.allProviders()` then `allProviders.firstNotNullOfOrNull { p -> p.findSymbol(project, symbolName)?.let { p to it } }`. Pure-Python works correctly. **This file is now the canonical reference template** for fixing `find_definition` + `find_references`.

#### Surprising findings worth flagging
1. **40-result hard cap with no pagination.** Same shape as the 30-entry cap pattern in `call_hierarchy` / `type_hierarchy`. Hub interfaces silently truncate.
2. **Project-only scope excludes JDK / library implementations.** Asking "who implements `Comparable`" returns project classes only, not the standard library. The LLM may not know.
3. **Python Protocol invisible** — same Python asymmetry as `type_hierarchy`. Duck-typed implementations don't surface.
4. **Silent element-kind filter** for non-method / non-class targets. Calling on a field or local variable returns empty — no error signal.

### Action items surfaced by Batch 5

- [ ] **🚨 Fix `search_code` silent regex-degrade.** Either error on invalid regex or surface "fell back to literal" in the result.
- [ ] **🚨 Unify `isError=false` semantics** across the diagnostics tool family (`diagnostics`, `run_inspections`, `list_quickfixes`, `problem_view`). Most impactful API-clarity fix.
- [ ] Make `search_code` walk order consistent with `glob_files` (either both alphabetical or both mtime-sorted).
- [ ] Surface skipped files (1MB cap) in `search_code` result metadata.
- [ ] Remove or document `search_code`'s backward-compat aliases (`query`→`pattern`, `scope`→`path`).
- [ ] Address the 30/40-entry caps pattern systematically across all PSI hierarchy tools — pagination or truncated-flag.
- [ ] Document Python Protocol asymmetry in `find_implementations` LLM-facing description (or filter for Python-only projects).

### Cross-cutting observations from Batch 5

- **PSI bug pattern is now closed.** 5 of 5 PSI tools surveyed; 2 have the bug, 3 don't. Single fix PR can close it.
- **Cross-tool API drift surfaced.** `isError=false` semantics is shared across 4 tools but not documented uniformly. The swarm forces the question.
- **`search_code`'s 155-line doc shows what mature documentation looks like.** Six commonLLMMistakes, six downsides, full param coverage — uses the DSL to its full extent. Future swarm dispatches for high-complexity tools should expect 100+ lines.

---

## Batch 6 — 2026-05-09 (task management cluster)

Tools: `task_create`, `task_update`, `task_list`. Cohesive AGENT_CONTROL cluster — all four task tools share `TaskStore`.

**⚠️ Process incident:** parallel staging race. `task_update` and `task_list` ended up bundled in commit `8339e5e4e` because both subagents staged + committed concurrently to the same working tree. The bundled commit's message only mentions `task_update`, but both DSL blocks landed (verified via `git show --stat`). **Work is not lost.** Future batches should expect this risk; the swarm prompt template should be updated to mitigate (see action items).

### `task_create` — commit `c20759a30`

- **Verdict:** KEEP STRONG.
- **Lines added:** 57 in `documentation()`.
- **Real role:** TaskStore is the only durable progress signal that survives `ContextManager` compaction — Stages 1-3 all preserve it and `renderTaskProgressMarkdown()` re-injects current tasks into system-prompt Section 2 on every rebuild.

#### Surprising findings worth flagging
1. **🚨 `task_create` cannot set DAG edges.** The parameter schema is `subject` / `description` / `activeForm` only — **no `blocks` / `blockedBy`**. Dependency edges only enter via `task_update`'s `addBlocks` / `addBlockedBy` arrays. **Cycle errors are detected on update only, never on create.** My dispatcher prompt was wrong about this; the subagent corrected it.
2. **🚨 CLAUDE.md status drift.** Project CLAUDE.md TaskStore section lists statuses as `TODO`/`IN_PROGRESS`/`DONE`/`BLOCKED`, but the source `TaskStatus` enum is `pending`/`in_progress`/`completed`/`deleted`. **Action item:** fix CLAUDE.md (this is a recurring class of drift).

### `task_update` — commit `8339e5e4e` (bundled with task_list)

- **Verdict:** KEEP STRONG.
- **Lines added:** 117 in `documentation()`.
- **Real role:** without `task_update`, multi-step plans cannot move past `pending` — the LLM either re-creates tasks (DAG churn, dangling `blockedBy` references) or loses progress visibility after compaction.

#### Surprising findings worth flagging
1. **🚨 NO BLOCKED status exists.** The four legal `TaskStatus` values are `pending` / `in_progress` / `completed` / `deleted`. **Blockage is purely structural** (the `blocks`/`blockedBy` edge graph). The "BLOCKED" status referenced in CLAUDE.md and earlier docs is fictitious.
2. **🚨 Status changes do NOT propagate.** Completing task A does not auto-unblock anything that listed A in its `blockedBy`. The LLM has to manually walk the graph and update dependents — easy to miss.
3. **`addBlocks`/`addBlockedBy` are additive only.** No remove or replace API. To break an edge, you have to delete the task and re-create it (which means re-creating downstream edges too).
4. **DFS cycle check is mid-flight hard fail.** No preview / dry-run mode. Subagent's recommendation: provide a `validate_only` flag.

### `task_list` — commit `8339e5e4e` (bundled with task_update due to race)

- **Verdict:** WEAK keep / NORMAL drop. **The first non-STRONG-keep verdict in the entire swarm.**
- **Lines added:** 116 in `documentation()`.
- **Real role:** **questionable** — see findings.

#### Surprising findings worth flagging
1. **🚨 `task_list` is partially redundant with the system-prompt task render.** `EnvironmentDetailsBuilder.appendTasks` (line 109) injects `# Tasks\n- [id] [status] subject` into every user turn, AND `ContextManager.renderTaskProgressMarkdown` re-renders a checkbox view in Section 2 after every compaction. The LLM sees id+status+subject **three different ways** without ever calling `task_list`.
2. **`task_list`'s only unique value is the `owner` and `blockedBy` fields.** Every other field is already in the model's context for free.
3. **🚨 MERGE_OPPORTUNITY:** a 3-line patch to `EnvironmentDetailsBuilder.appendTasks` that appends `(owner: X, blockedBy: 1,2)` when present would render `task_list` functionally redundant. **This is the first concrete drop candidate the swarm has surfaced.**
4. The tool's hook-exempt status, parameter-free schema, and lack of any filter param all reinforce: it's a pure summary affordance whose purpose has been partially absorbed by the system-prompt render.

### Action items surfaced by Batch 6

- [ ] **🚨 First concrete drop candidate identified — `task_list`.** Patch `EnvironmentDetailsBuilder.appendTasks` to include owner + blockedBy fields, then drop `task_list` from the registry. Saves a tool slot in the LLM's schema budget.
- [ ] **🚨 Sweep CLAUDE.md for task-system drift** — TaskStatus enum values (TODO/IN_PROGRESS/DONE/BLOCKED ↔ pending/in_progress/completed/deleted), missing BLOCKED status, etc. Combined with the `edit_file.lastEditLineRanges` drift from Batch 3, CLAUDE.md needs an audit pass.
- [ ] **Implement status-propagation in `task_update`** — completing task A should auto-mark dependents as no-longer-blocked-by-A.
- [ ] **Add `removeBlocks` / `removeBlockedBy` to `task_update`** — current API is additive only, requiring delete-and-recreate to break an edge.
- [ ] **Update the swarm prompt template** to mitigate parallel-staging races: either (a) instruct subagents to retry `git commit` if they see "nothing to commit" after their `git add` (suggesting another subagent committed in between), or (b) add a unique tag in commit message that lets us detect bundling, or (c) reduce parallel cap from 3 to 2 to reduce race probability.

### Cross-cutting observations from Batch 6

- **First non-STRONG-keep verdict.** `task_list` (WEAK keep / NORMAL drop) — the swarm is now finding actual drop candidates, which is the original motivation for the entire initiative.
- **Parallel-staging race is real.** 1 of 6 batches so far has had a bundled commit. Risk grows with parallelism × commit frequency. Tolerable given the work survives in git, but worth mitigating.
- **CLAUDE.md drift count: 3 instances now.** `edit_file.lastEditLineRanges` (Batch 3), task statuses (Batch 6), missing BLOCKED status (Batch 6). Pattern suggests CLAUDE.md was written aspirationally and then the implementation diverged. **Worth a dedicated audit pass after the swarm completes.**
- **Subagents push back on the dispatcher prompt when wrong.** `task_create` subagent corrected my "blocks/blockedBy params" claim. Same with `edit_file` subagent on the matcher being whitespace-tolerant. The audit-the-dispatcher dynamic is healthy.

---

## Batch 7 — 2026-05-09 (FILE_WRITE IDE + task_get)

Tools: `format_code`, `optimize_imports`, `task_get`. Two near-twin IDE write tools + the last of the task family.
No parallel-staging race this batch (mitigation prompt language seems to have helped, or just luck).

### `format_code` — commit `e9c011e1`

- **Verdict:** KEEP NORMAL.
- **Lines added:** 63 in `documentation()`.
- **Real role:** counterfactual is materially worse — approval-gated `spotlessApply`/`prettier` shell-outs with whole-project blast radius, plus error-prone hand-formatting in `edit_file`.

### `optimize_imports` — commit `36f214c58`

- **Verdict:** KEEP NORMAL.
- **Lines added:** 78 in `documentation()`.
- **Real role:** hand-rolled import management via `edit_file` is one of the highest-failure-rate operations in agentic coding (multi-line whitespace, project import-grouping rules, conflict resolution between same-named classes the IDE knows about).

#### 🚨 Strongest MERGE_OPPORTUNITY surfaced so far

`format_code` and `optimize_imports` are near-twins. They share ~80% scaffolding:
- Both take exactly one parameter (`path`)
- Both gate on `DumbService.isDumb(project)` with identical error semantics
- Both run inside `withContext(Dispatchers.EDT) { WriteCommandAction.runWriteCommandAction(...) }`
- Both implement no-op detection by comparing `textBefore == textAfter`
- Both return identical `ToolResult` shapes (`artifacts = listOf(path)`, same token estimate)
- Both belong to `WRITE_TOOLS` in `AgentLoop` and `CODER` worker only
- **The dispatch difference is one line:** `optimizer.processFile(psiFile).run()` vs `CodeStyleManager.getInstance(project).reformat(psiFile)`

**Action item:** unify into `transform(path, kind: imports|format|both)`. Halves schema cost (~50 tokens × 2). Lets LLM combine post-`edit_file` cleanup chain into one tool call. Trade-off (separate tools make LLM intent self-documenting in traces) is preserved by the `kind` parameter.

This is the **second concrete merge candidate** the swarm has surfaced — first was `task_list` (drop) in Batch 6.

#### Surprising findings (`optimize_imports`)
1. **DumbService check is load-bearing.** Without it, removing "unused" imports during indexing could remove imports the IDE just hasn't resolved yet — silent data loss.
2. **Side-effect imports get dropped.** Scala implicits, TS reflect-metadata, anything imported only for side effects (e.g. registering a parser) — the optimizer treats as unused. Worth a `commonLLMMistakes` entry in any project that uses these patterns.

### `task_get` — commit `7451af529`

- **Verdict:** STRONG keep.
- **Lines added:** 165 in `documentation()` (longest doc in the swarm so far).
- **Real role:** the **only** path to a task's `description`, `blocks` (downstream dependents), `activeForm`, `owner`, and timestamps.

#### Direct contrast with `task_list` — `task_get` is NOT redundant

`EnvironmentDetailsBuilder.appendTasks` (lines 109-124, format `"- [${t.id}] [$status] ${t.subject}"`) emits **only** `id + status + subject`. It deliberately omits:
- `description` (the full body the LLM wrote at task_create time)
- `blocks` (downstream dependents — `task_list` only carries `blockedBy`)
- `activeForm`
- `owner`
- timestamps

Inlining full descriptions into Section 2 would balloon every prompt rebuild linearly with task count — defeating the per-turn render's purpose. **An on-demand fetch is the right shape.** The `task_list` redundancy doesn't apply here.

#### Other findings
- Param naming inconsistency: `taskId` here vs `id` on `task_update`. Low-priority cosmetic, logged as `removableParam` audit note.

### Action items surfaced by Batch 7

- [ ] **🚨 Merge `format_code` + `optimize_imports` into `transform(path, kind: imports|format|both)`** — the dispatch difference is literally one line; sharing the rest is pure overhead. Halves schema cost.
- [ ] Document Scala implicits / TS reflect-metadata side-effect-import gotcha in `optimize_imports` LLM-facing description (or filter for those project shapes).
- [ ] Rename `task_get`'s `taskId` param to `id` for consistency with `task_update`.

### Cross-cutting observations from Batch 7

- **Two concrete cleanup PRs surfaced now:** drop `task_list` (Batch 6) + merge `format_code`/`optimize_imports` (Batch 7). Phase 7's Compare Tools view will need both to be acted on before publishing.
- **No race this batch.** Race mitigation language in the dispatch prompt seems to have helped — or it was probabilistic noise. Will keep the language in future batches.
- **Doc length distribution is widening.** 63 / 78 / 165 lines for this batch. The 165-line `task_get` is heavier than expected — the subagent leaned into the contrast-with-task_list framing, which produced a lot of useful prose. Worth keeping that pattern.

---

## Batch 8 — 2026-05-09 (agent-control mode tools)

Tools: `plan_mode_respond`, `enable_plan_mode`, `use_skill`. All AGENT_CONTROL.

### `plan_mode_respond` — commit `8f5a50ad2`

- **Verdict:** STRONG keep.
- **Lines added:** 68 in `documentation()`.
- **Real role:** the only primitive that combines (a) emitting a structured plan as a `ToolResultType.PlanResponse`, (b) suspending the loop on `userInputChannel.receive()` so only the user can transition out, and (c) persisting as a `PLAN_UPDATE` UI message so the plan card survives session resume.

#### Surprising findings worth flagging
1. **🚨 Loop suspension is one-way with no timeout.** If the user goes idle, the agent sits on the channel forever. No programmatic retry / self-rescue affordance.
2. **🚨 Boolean-coercion bug on `needs_more_exploration`.** Silently treats `"True"` as truthy but `"yes"` as false. Subtle string-to-boolean coercion edge case. **Action item:** tighten coercion to reject non-canonical bools or accept any truthy-string.

### `enable_plan_mode` — commit `42e1c6a4a`

- **Verdict:** STRONG keep.
- **Lines added:** 70 in `documentation()`.
- **Real role:** asymmetric on/off is intentional safety — LLM-callable enable, user-only disable enforces a human review checkpoint the LLM cannot route around when it hits the write-tool wall.

#### Surprising findings worth flagging
1. **Schema filter removes `enable_plan_mode` once plan mode is active.** No programmatic refresh / re-entry. Only `AgentLoop`'s execution guard sees attempted re-toggles via cached/stale tool calls.

### `use_skill` — commit `08f55dd01`

- **Verdict:** STRONG keep.
- **Lines added:** 69 in `documentation()` + 124 lines narrative MD.
- **Real role:** the only LLM-callable entry to the compaction-survivable procedure store. Without it, skills devolve to user-only slash commands.

#### Surprising findings worth flagging
1. **🚨 Substitution scaffolding silently fires empty on the LLM-tool path.** `$ARGUMENTS`, `$1`-`$N`, `${CLAUDE_SKILL_DIR}` were designed for chat slash-command invocation, but the LLM tool schema does **NOT** currently expose an `arguments` parameter — so when the LLM calls `use_skill(skill_name="tdd")`, all positional substitutions resolve to empty strings. **Skill authors must design defaults that work without args.** **Action item:** add an optional `arguments` parameter to the LLM schema OR document the gap explicitly.
2. **Only one skill is active at a time.** No stack. Re-activation silently replaces the previous active skill.
3. **Sub-agents cannot call `use_skill`** (orchestrator-only `allowedWorkers`). Subagent procedures must be wired through the persona's `skills:` YAML at config-load time.

### Action items surfaced by Batch 8

- [ ] **🚨 `use_skill` schema gap** — add an optional `arguments` parameter to the LLM-facing schema, OR document explicitly that the LLM path doesn't support positional args (forcing skill authors to design no-args-required skills).
- [ ] **🚨 `plan_mode_respond` boolean coercion** — tighten `needs_more_exploration` parsing (reject non-canonical bools or accept any truthy-string consistently).
- [ ] Consider a programmatic timeout on `plan_mode_respond`'s loop suspension to prevent indefinite hangs on user-idle.

### Cross-cutting observations from Batch 8

- **No race this batch (2 in a row).** Race mitigation in dispatch prompt is helping.
- **Two more tools surfacing schema design gaps** — `use_skill` lacks the `arguments` param the substitution machinery expects; `plan_mode_respond` has loose boolean coercion. These are the kind of issues only per-tool documentation review surfaces.

---

## Batch 9 — 2026-05-09 (sub-agent / handoff cluster)

Tools: `task_report`, `new_task`, `agent` (SpawnAgentTool). Three architectural-control tools — easily the heaviest batch by line count.

### `task_report` — commit `f6dbad6f5`

- **Verdict:** STRONG keep.
- **Lines added:** 99 in `documentation()`.
- **Real role:** the architectural mirror of `attempt_completion` at the sub-agent boundary. Without it, every sub-agent round-trip terminates with free-form text the parent must parse, collapsing the value of spawn-and-await delegation.

#### Surprising findings worth flagging
1. **🚨 Schema-level under-enforcement.** Only `summary` is required; `findings`/`files`/`next_steps`/`issues` are all optional. **The "structured report" contract is intent-only**, enforced solely by the persona prompt footer (`COMPLETING_YOUR_TASK_SECTION`). A sub-agent emitting `summary: "done"` and nothing else terminates cleanly — the **most common failure mode**. **Action item:** make `findings` required (or at least `findings || issues` required).
2. **🚨 Silent `attempt_completion` → `task_report` substitution.** `SpawnAgentTool.resolveConfigToolsTiered` filters `attempt_completion` out of `config.tools` and injects `task_report` with no log warning. Persona-YAML authors cannot tell from the file alone what completion tool the sub-agent actually has. **Action item:** log a warning when this substitution happens.

### `new_task` — commit `12b4a1abb`

- **Verdict:** NORMAL keep with real WEAK drop case.
- **Lines added:** 139 in `documentation()` + 183 lines narrative MD.
- **Real role:** clean handoff with focused brief vs ContextManager's degrade-to-summary-of-summaries. The implementation is ~100 lines but the niche is narrow.

#### Surprising findings worth flagging
1. **🚨 Naming collision is THE biggest source of LLM mistakes.** `task_create` adds an in-session Kanban card; `new_task` ENDS the session and starts a fresh one. **Same name root, opposite scope.** The Cline-faithful description ("Request to create a new task with preloaded context...") reinforces the confusion. **Action item:** add "NOT for adding a TODO item" to the LLM-facing description.
2. **`new_task` vs `agent` differentiation is real.** `agent` spawns a worker; orchestrator stays alive and receives `task_report`. `new_task` ENDS the orchestrator — fresh session IS the new orchestrator. Heuristic: same agent continues → `new_task`; different agent reports back → `agent`.

### `agent` (SpawnAgentTool) — commit `33cc59152`

- **Verdict:** STRONG keep tool-level.
- **Lines added:** 406 in `documentation()` + 194 lines narrative MD. **Longest doc in the swarm by far.**
- **Real role:** architectural keystone for context management at scale. Without sub-agent delegation, the orchestrator's 150K window is the entire session's budget, parallelism disappears, persona system collapses.

#### 🚨🚨🚨 MAJOR CLAUDE.md DRIFT — entire section is aspirational

**The `:agent` CLAUDE.md "Agent Tool" section describes 5 actions** — `spawn` (default), `run_in_background`, `resume`, `kill`, `send` — **NONE of which are exposed as parameters in the source.** The actual params are only:
- `description`, `prompt`
- `prompt_2..5`, `description_2..5` (parallel-fanout, plan-mode only)
- `agent_type`, `model`

`cancelAgent(agentId)` exists but is called from the UI Kill button via `AgentController`, **not by the LLM**. Either the docs are aspirational design notes that never landed, or the features were removed and the docs weren't updated. **This is the largest CLAUDE.md drift surfaced by the swarm so far.** **Action item:** rewrite the CLAUDE.md "Agent Tool" section to match the actual API.

#### Other surprising findings
1. **🚨 Silent parallel-fanout downgrade.** If the LLM sends `prompt_2..5` to a write-capable persona, `inferPlanMode()` returns false and parallel mode silently downgrades to single mode, discarding extra prompts with no warning in the result content.
2. **🚨 No wall-clock timeout** (`timeoutMs = Long.MAX_VALUE`). Only iteration cap (200), context budget (150K), and user Kill button can stop a stuck worker.
3. **File ownership is whole-file granularity.** Two parallel sub-agents editing different methods in the same file conflict.
4. **`WorkerMessageBus` uses `Channel(20, DROP_OLDEST)`.** Inter-agent messaging is best-effort lossy.
5. **🚨 Sub-agent default model hard-coded to `pickSonnetNonThinking`.** Drops a tier from an Opus orchestrator unless the persona's YAML overrides it. **Action item:** make sub-agent default model match orchestrator's tier (or document the asymmetry).

### Action items surfaced by Batch 9

- [ ] **🚨 Rewrite `:agent` CLAUDE.md "Agent Tool" section** — current text describes 5 actions that aren't actual params. Major drift.
- [ ] **🚨 Make `task_report.findings` (or `findings || issues`) required** — current schema-level under-enforcement defeats the structured-report contract.
- [ ] Log a warning when `SpawnAgentTool` silently substitutes `attempt_completion` → `task_report` in a config.
- [ ] Add "NOT for adding a TODO item" disambiguation to `new_task` LLM-facing description.
- [ ] Surface a result-content warning when `agent` silently downgrades parallel-fanout to single mode.
- [ ] Make sub-agent default model match orchestrator's tier (or document the asymmetry explicitly).
- [ ] Consider sub-method file ownership granularity for `agent` (instead of whole-file) to enable concurrent edits to different methods.

### Cross-cutting observations from Batch 9

- **CLAUDE.md drift count: 4 instances now.** `edit_file.lastEditLineRanges`, task-statuses, missing BLOCKED status, and now the entire "Agent Tool" section. **The CLAUDE.md audit is no longer optional — it's a real maintenance backlog item.**
- **Doc length tracks architectural load-bearing.** 406 lines for `agent` is justified by its complexity; 99 for `task_report` is exactly right; 139 for `new_task` reflects its dual-narrative (verdict + naming-collision callout).
- **The `agent` tool's silent failure modes are concerning.** Three separate "no warning when X" findings (parallel downgrade, attempt_completion substitution, model tier drop). Worth a dedicated reliability pass.
- **No race this batch (3 in a row since the mitigation prompt landed).**

---

## Batch 10 — 2026-05-09 (UI + interactive tools)

Tools: `ask_followup_question`, `render_artifact`, `background_process`. Mixed cluster — UI question tool, interactive React renderer, multi-action process manager.

### `ask_followup_question` — commit `1e42140da`

- **Verdict:** STRONG keep tool-level; WEAK drop case for wizard mode.
- **Lines added:** 112 in `documentation()`.
- **Real role:** load-bearing for course-correction flows; nothing else combines structured question payload + JCEF UI + loop suspension.

#### Surprising findings worth flagging
1. **🚨 `pendingQuestions` is a single-slot `@Volatile` companion field.** Concurrent calls would clobber each other. **Safe today because the agent loop serializes tool calls**; brittle if that invariant ever shifts (e.g. parallel tool execution for read-only tools includes ask_followup_question).
2. **~60 LOC of scaffolding duplicated** between `executeSimple` and `executeWizard` (CompletableDeferred / 10s render-watchdog Timer / 5-min `withTimeoutOrNull` / `[UI_RENDER_FAILED]` / `[SKIPPED]` / `cancelled:true` sentinel handling). MERGE_OPPORTUNITY for unification.
3. **Five terminal response shapes** the LLM has to handle: answered, skipped, cancelled (isError=false), 5-min timeout (error), 10s UI-render-failed (error). Each could be a separate failure mode the LLM doesn't know about until it sees one.

### `render_artifact` — commit `0d1695b59`

- **Verdict:** STRONG keep.
- **Lines added:** 248 in `documentation()` + narrative MD.
- **Real role:** the only path to interactive output. Without it, ASCII art and Markdown tables — no hover, sort, click-to-navigate.

#### Surprising findings worth flagging
1. **🚨 NOT fire-and-forget.** Despite the surface naming, `render_artifact` suspends the agent loop on a `CompletableDeferred` via `ArtifactResultRegistry.renderAndAwait` until the iframe round-trips a structured outcome. The self-repair loop is what powers the LLM's ability to land working renders within 1-2 retries.
2. **🚨 AGENT_CONTROL classification under-sells the surface.** The tool DOES introduce sandboxed JS execution generated by the LLM. Mitigated by no-network CSP + inline-data-only + 100KB cap, but worth flagging — it's not the same blast radius as a no-op AGENT_CONTROL tool.
3. **`description` + `SCOPE_HINT` must stay in sync with `sandbox-main.ts` `fullScope`.** Drift would create a particularly bad failure mode where the prompt advertises a symbol the sandbox can't bind. **Action item:** add a runtime check or test that pins these together.

### `background_process` — commit `7109508de`

- **Verdict:** STRONG keep.
- **Lines added:** 610 in `documentation()`. Largest single-file doc in the swarm. **6 actions** (`list`, `status`, `output`, `attach`, `send_stdin`, `kill`).
- **Real role:** entire background-process lifecycle in a single schema slot. Splitting into siblings would cost ~1200 tokens every iteration even when no processes are running.

#### 🚨 MERGE_OPPORTUNITY: standalone `send_stdin` tool ↔ `background_process(action=send_stdin)`

The standalone `send_stdin` and the action have semantically equal operations. From the LLM's perspective, two tools that both "send stdin" is confusing.

**But the merge is non-trivial:**
- Standalone `send_stdin` has guards the action lacks: **password-prompt detection** via `ShellResolver.isLikelyPasswordPrompt` + **per-process rate limiting** (`AgentSettings.maxStdinPerProcess`, default 10).
- Two id namespaces are still distinct: `ProcessRegistry` (for `run_command`'s synchronous-then-idle path, non-`bg_` ids) vs `BackgroundPool` (for explicit `run_command(background=true)`, `bg_` ids).
- Standalone tool enters a 60s monitor loop after writing; the action returns immediately.

**Path:** keep both, prioritize migrating `BackgroundPool` to subsume `ProcessRegistry`'s idle-prompt path, then delete standalone. Documented as `WEAK drop` on the `send_stdin` action and `mergeOpportunity` audit note tool-level.

#### Other surprising findings
1. Process state is per-session and lost on new chat; output buffer has limits.
2. No signal-other-than-kill API (no SIGHUP, SIGUSR1, etc.).
3. Action-enum + per-action `[…]` parameter tagging in the description gives the LLM enough hints to pick the right action and the right param subset.

### Action items surfaced by Batch 10

- [ ] Unify `ask_followup_question`'s simple/wizard scaffolding (~60 LOC dedup).
- [ ] Make `pendingQuestions` per-session or wrap in a coordinator that survives parallel-tool-execution if that invariant ever changes.
- [ ] Add a runtime/test check pinning `RenderArtifactTool.SCOPE_HINT` ↔ `sandbox-main.ts` `fullScope`.
- [ ] Migrate `BackgroundPool` to subsume `ProcessRegistry`'s idle-prompt path; then delete standalone `send_stdin` tool. Saves a schema slot.
- [ ] Consider expanding `background_process` signal API beyond just kill (SIGHUP / SIGUSR1 / SIGTERM nuance).

### Cross-cutting observations from Batch 10

- **Three more concrete cleanup items.** The swarm continues to surface real merge candidates: `format_code`+`optimize_imports` (Batch 7), `task_list` redundancy (Batch 6), `send_stdin`↔`background_process` (Batch 10).
- **Doc length record set:** `background_process` 610 lines for one tool's documentation. The 6-action surface and the merge-with-send_stdin analysis justify it; not padding.
- **No race this batch (4 in a row since mitigation language landed).**

---

## Batch 11 — 2026-05-09 (runtime cluster)

Tools: `run_command`, `runtime_exec`, `coverage`. The runtime/process tools — heaviest single batch by line count.

### `run_command` — commit `b31289605`

- **Verdict:** STRONG keep.
- **Lines added:** 408 in `documentation()` + 233 lines narrative MD.
- **Real role:** the only access to the long-tail CLI surface (git, docker, kubectl, jq, gh, find, …) that no dedicated tool covers.

#### Surprising findings worth flagging
1. **🚨 Two safety layers in same package, different layers.** `DefaultCommandFilter` (hard-block, runs in `RunCommandTool`) and `CommandSafetyAnalyzer` (risk classification, runs in `AgentLoop`) share the `agent/security/` package but execute at different points. Easy to mistake for redundancy; intentional defense-in-depth. **Worth a code comment.**
2. **🚨 Approval policy hardcoded `ALWAYS_PER_INVOCATION`.** Other write tools (edit_file, create_file, revert_file) can be allow-listed for-session; `run_command` cannot. Sound trade-off (one-time approval would silently authorize every future command), but worth surfacing — users may try to find a way to disable this.
3. **🚨 `OutputCollector.processOutputTailBiased` is run_command-specific.** Default tools use 60/40 middle-truncation, but `run_command` uses tail-biased because build/test output has exit summaries and failure traces at the tail. Different tool, different truncation strategy.
4. **`ProcessEnvironment` reconciles three concerns in one file** — sensitive (35+ stripped), blocked (25 rejected from LLM env), anti-interactive (15 forced overrides). Easy to keep in sync.

### `runtime_exec` — commit `8e61e45709`

- **Verdict:** STRONG keep.
- **Lines added:** 388 in `documentation()` + 269 lines narrative MD.
- **Real role:** five actions that all anchor on `RunContentManager` / `ExecutionManager` and share descriptor-resolution code. Splitting would cost ~750 schema tokens every iteration with zero capability gain.

#### Surprising findings worth flagging
1. **🚨 Static config parsing for ports was deliberately removed on 2026-04-21.** `run_config` uses ONLY OS commands (`lsof` / `ss` / `netstat`) for port discovery. Rationale: run-config overrides (VM options, env vars, profiles, `server.port=0`) make log-scraped or YAML-scraped ports unreliable. **Design principle:** "no info > wrong info."
2. **🚨 `mode=coverage` and `mode=profile` are dead schema entries.** Both exist in the `mode` enum but currently just redirect via `INVALID_CONFIGURATION`. Schematically harmless but could be removed. **Action item:** drop dead enum values.

### `coverage` — commit `f21e645d21`

- **Verdict:** STRONG keep.
- **Lines added:** 177 in `documentation()` (2 actions).
- **Real role:** the only path to branch-coverage + per-method rollups in a single tool result. Multi-method JUnit 5 PATTERNS path turns "cover this class with these three tests" into one round trip.

#### Surprising findings worth flagging
1. **🚨 Modal-suppression reflects into `com.intellij.coverage.CoverageOptionsProvider.setOptionsToReplace(int)`.** Fragile to Platform refactors — method rename or signature change silently makes the suppression a no-op (rest of the run still works, but the loop hangs on the first dialog). Pinned by source-contract test, but cannot validate the platform's contract.
2. **🚨 TestNG with 2+ methods is a hard-error path** — no shell fallback because splitting would lose snapshot aggregation.

### Action items surfaced by Batch 11

- [ ] Add code comment in `run_command` source explaining the two-safety-layer split (DefaultCommandFilter vs CommandSafetyAnalyzer) is intentional, not redundant.
- [ ] Drop `mode=coverage` and `mode=profile` from `runtime_exec`'s `mode` enum — they're dead schema entries that just return `INVALID_CONFIGURATION`.
- [ ] Add a build-time check pinning `com.intellij.coverage.CoverageOptionsProvider.setOptionsToReplace(int)` signature — current source-contract test only validates our call shape, not the platform method's existence.

### Cross-cutting observations from Batch 11

- **Doc length pattern continues to track surface area.** 408 / 388 / 177 lines for this batch. The first two have narratives totalling 233+269=502 lines — when a tool deserves architectural prose, the swarm is delivering it.
- **`run_command`'s "no allow-for-session" is the only tool with this constraint.** Worth highlighting in the Compare Tools view (Phase 7) — it's a unique design choice.
- **Reflection-into-platform-internals is a recurring fragility pattern.** `coverage` reflects into `CoverageOptionsProvider`; `java_runtime_exec` reflects into JUnit 5 `PATTERNS` field. Both are pinned by source-contract tests but neither validates the platform side. Worth a more systematic test pattern.
- **No race this batch (5 in a row).** Mitigation prompt is now battle-tested.

---

## Batch 12 — 2026-05-09 (database cluster)

Tools: `db_query`, `db_schema`, `db_list_databases`. Read-mostly database tools.

### `db_query` — commit `5d3a232b9` — STRONG keep, 121 lines

**🚨 SELECT-only enforcement is four-layered defense:**
1. Prefix allow-list (14 prefixes blocked) — but **first-keyword only**, so `WITH ... INSERT` slips THIS gate
2. `Connection.isReadOnly = true`
3. `autoCommit=false` + final `rollback()` in `finally`
4. `queryTimeout = 30s`

**Surprising findings:** `database` param silently ignored for SQLite/Generic profiles. Cell values truncated at 500 chars — LLM may parse truncated JSON as complete. No prepared statements, so SQL-injection from prompted user data is possible (mitigated to data-exfil only by read-only).

### `db_schema` — commit `b6ab95ddd` — STRONG keep, 98 lines

**Surprising findings:**
1. **🚨 Level 3 silent empty result** when `(schema, table)` doesn't exist — empty markdown columns table with no error. LLM may misread as "table has no columns".
2. **🚨 `NULLABLE` parsing reads JDBC column as `String == "1"`** — but spec returns int. Non-conformant driver could mislabel every column NOT NULL.
3. **System-schema filter has hand-rolled allowlist** with `lower.startsWith("db_")` — user schema named `db_audit` gets silently filtered out.
4. **FK/index queries wrapped in `runCatching {}`** — silent partial results.

### `db_list_databases` — commit `bbd27d4ad` — KEEP NORMAL with WEAK drop case, 102 lines

**🚨 MERGE_OPPORTUNITY with `db_schema`:** add level 0 ("databases on this server" when profile-is-server-type && schema/database both null) is a natural extension of db_schema's existing 3-level hierarchy.

**🚨 NOT mergeable with `db_list_profiles`** — they look similar but are fundamentally different concerns. Profiles are configured (static config read, no network); databases are discovered at runtime via network query. Merging would force db_list_profiles to become a network-IO tool with all the failure modes that come with it.

### Action items surfaced by Batch 12

- [ ] **🚨 Fix `db_query` prefix-only check** — currently `WITH ... INSERT` could slip past prefix gate, falls back to layered defenses (driver-level + autoCommit + rollback). Tighten the prefix check or document the layered defense as canonical.
- [ ] Surface explicit error for non-existent `(schema, table)` in `db_schema` instead of silent empty result.
- [ ] Fix NULLABLE int-vs-String parsing in `db_schema` — use `getInt()` not `getString()`.
- [ ] Make system-schema filter user-overridable in `db_schema`.
- [ ] **Merge `db_list_databases` into `db_schema`** as level-0 hierarchy. Saves a schema slot for the common case where profiles pin a single database.
- [ ] Add prepared-statement support to `db_query` for LLM-constructed queries (defense against SQL-injection from prompted user data).
- [ ] Increase or surface `db_query` 500-char-per-cell truncation to LLM (current silent truncation can mislead JSON-column reads).

### Cross-cutting observations from Batch 12

- **Every database tool surfaces silent-truncation or silent-error patterns.** Three of three. Worth a unified error-discipline pass on the database family.
- **Layered defense vs single check.** `db_query` exemplifies "defense in depth where the first layer is incomplete" — typical for security-critical paths but the LLM-facing description should explain the layered model.
- **Merge candidate count: 4 now** (`format_code`+`optimize_imports`, `task_list`-redundancy, `send_stdin`↔`background_process`, `db_list_databases`↔`db_schema`). Phase 7 cleanup PR backlog is filling out.

---

## Batch 13 — 2026-05-09 (jira + db_explain + changelist_shelve)

⚠️ **Process incident:** parallel-staging race recurred. `db_explain` (123 lines) and `changelist_shelve` (178 lines) both ended up in commit `f8062ff94`. Commit message references `changelist_shelve` only. Both DSL blocks are in git — work not lost.

### `jira` — commit `2d9c5f339` — STRONG keep, 785 lines doc + 257 lines narrative

**Real role:** the counterfactual is `run_command curl` with the user's bearer token in the command line — leaks tokens to shell history, bypasses `ProcessEnvironment`'s sensitive-vars stripper (env-only, not args). 17 actions in one slot saves ~3-5K tokens per iteration vs separate tools.

**🚨 Drop candidates surfaced (action-level):**
1. **`get_worklogs`** — NORMAL drop. Time-log readback rarely the LLM's job; users open Jira directly.
2. **`get_board_issues`** — NORMAL drop. Semantically dominated by `search_tickets` (JQL > board filter).
3. **`get_dev_branches` + `get_linked_prs`** — WEAK drop. Both are narrower slices of `get_ticket(include_dev_status=true)`. Foldable into the include flag.

**The `transition` action's `MissingFields` typed contract is the second-best argument to keep this tool.** Lets the LLM call `ask_followup_question` per missing field and retry, instead of parsing raw 400 bodies and guessing custom-field IDs.

### `db_explain` — commit `f8062ff94` (bundled with changelist_shelve) — NORMAL keep, 118 lines

**🚨 Surprising finding: PostgreSQL with `analyze=true` DOES execute the SELECT.** Safety is NOT from "EXPLAIN doesn't execute the query" but from `autoCommit=false` + always-rollback. **Sharp edge:** a side-effecting function call inside a SELECT (e.g. `SELECT my_logging_proc()`) would execute under `analyze=true` — and a function with non-transactional side effects (writes to a foreign DB, network calls, file I/O) would fire even though the rollback prevents committed writes.

### `changelist_shelve` — commit `f8062ff94` (bundled with db_explain) — NORMAL keep / WEAK drop, 178 lines

**Real role:** only IDE-native VCS operation the agent has. **But mostly redundant with `run_command git stash`.**

**🚨 Per-action analysis: `create` is the conceptually odd one out.** Changelists are organisational buckets for uncommitted changes; shelves are parked snapshots. Bundling under one schema slot is technically defensible (both go through `ChangeListManager`) but conceptually two tools wearing one hat.

**Storage divergence:** IDE-Shelf vs git-stash are different stores. A user who shelves via the agent and later runs `git stash list` sees nothing — and vice versa.

### Action items surfaced by Batch 13

- [ ] **🚨 Drop or fold these jira actions:** `get_worklogs` (drop), `get_board_issues` (drop, redirect to `search_tickets`), `get_dev_branches` + `get_linked_prs` (fold into `get_ticket(include_dev_status=true)`).
- [ ] Document the autoCommit-rollback safety model in `db_explain`'s LLM-facing description so the LLM knows side-effecting function calls in SELECTs can still fire.
- [ ] Consider dropping `changelist_shelve.create` action — it's conceptually a changelist op, not a shelf op. The other 4 actions form a coherent shelf workflow.
- [ ] Update the swarm prompt template to add stronger race mitigation: e.g. "wait 2s and retry if `git commit` shows no changes, since another subagent may have just committed."

### Cross-cutting observations from Batch 13

- **`jira`'s 785-line doc is the swarm's largest single-tool block.** Justified by 17 actions × per-action quality bar. Sets the bar for upcoming integration tools (`bitbucket_pr` 18 actions will be similar).
- **Drop candidate count keeps growing.** The swarm has now identified ~7 concrete drop/merge candidates across the documented tools. Phase 7's Compare Tools view will need a real prioritization scheme.
- **🚨 Race recurred (1 in 6 batches now).** The mitigation language was helpful but not bulletproof. Stronger mitigation: ask subagents to add a small randomized sleep before `git commit` (cheap, breaks tight collisions), or pre-acquire a lock file. Or accept it — work always lands in git, only commit messages drift.

---

## Batch 14 — 2026-05-10 (Bitbucket integration cluster — Sonnet model)

Tools: `bitbucket_pr`, `bitbucket_repo`, `bitbucket_review`. Switched to **`model: sonnet`** per user preference for well-planned mechanical execution. Quality on par with Opus output; faster + cheaper.

### `bitbucket_pr` — commit `b24e57697` — STRONG keep

- **Lines added:** 982 in `documentation()` + 176 lines narrative MD.
- **🚨 CLAUDE.md drift: 19 actions, not 18.** CLAUDE.md omitted `get_prs_for_branch`. Drift count: 5.
- **Per-action verdicts:** 6 STRONG keep, 13 NORMAL keep.

#### 🚨 Drop candidates (action-level)
1. **`get_blocker_comment_count`** — returns only an integer; fully superseded by `check_merge_status` which gives veto reasons too.
2. **`get_required_builds`** — repo-level merge-gate config, not PR state. Near-zero expected query frequency; teams set this up once.
3. **`update_pr_title`** — lowest-frequency housekeeping; titles are set correctly at `create_pr`. (`update_pr_description` is more justified — descriptions evolve with scope.)

### `bitbucket_repo` — commit `6cc9e7e54` — all actions keep (NORMAL except `get_commit_pull_requests` STRONG)

- **Lines added:** 532 in `documentation()`.
- `get_commit_pull_requests` STRONG keep — unique reverse-lookup (commit → PR) not available via any other tool.

#### Surprising findings
1. **`get_file_content` ↔ `read_file` overlap** — for local tracked files `read_file` is strictly better (line numbers, no network). `get_file_content` only justified for remote/historical refs or branches not checked out locally. **Documented as commonLLMMistake + WEAK drop note.**
2. **`get_build_statuses` ↔ `get_commit_build_stats` split** — token-cost guidance: call aggregate counter first, escalate to full per-build list only when needed. **MergeOpportunity audit note** flags this two-action split as consolidation candidate.

### `bitbucket_review` — commit `f72e7fcd1` — STRONG keep

- **Lines added:** 966 in `documentation()`.
- **🚨 CLAUDE.md drift: 12 actions, not 6.** Off by 100%. Drift count: 6.

#### Surprising findings
1. **Optimistic-locking contract on `edit_comment` / `delete_comment`** — requires `expected_version` from prior `get_comment` call. The most defensible differentiator vs raw curl — without it concurrent edits silently clobber.
2. **🚨 Asymmetric parameter surface = LLM footgun.** Write actions (`add_pr_comment`, `add_inline_comment`, `reply_to_comment`, reviewer mgmt) take only `pr_id` (active repo inferred); read/lifecycle actions (`list_comments`, `get_comment`, `edit_comment`, `delete_comment`, `resolve_comment`, `reopen_comment`) require all three of `project_key`, `repo_slug`, `pr_id`. LLM frequently passes/omits wrong set.
3. **Should `bitbucket_pr` + `bitbucket_review` merge?** **No.** 30 actions exceeds any sensible meta-tool size (Jira 17, bamboo_builds 11); both tools live in deferred tier so LLM loads only the one needed per session; semantic groupings clean (PR lifecycle vs review conversation). Only gap: LLM discoverability — add cross-reference hint in both descriptions.

### Action items surfaced by Batch 14

- [ ] **🚨 CLAUDE.md drift fixes:** `bitbucket_pr` 19 actions (not 18), `bitbucket_review` 12 actions (not 6). Sweep CLAUDE.md against all integration tools.
- [ ] Drop or fold `bitbucket_pr` actions: `get_blocker_comment_count` (superseded), `get_required_builds` (rare), `update_pr_title` (rare).
- [ ] Add cross-reference hint in `bitbucket_pr` and `bitbucket_review` descriptions so `tool_search` surfaces the companion.
- [ ] Consider unifying `bitbucket_repo`'s asymmetric param requirements (`bitbucket_review` style) — currently each action picks its own param surface.
- [ ] Merge `get_build_statuses` + `get_commit_build_stats` (token cost guidance).

### Cross-cutting observations from Batch 14

- **Sonnet works well for this workload.** Quality on par with Opus. Faster + cheaper. Confirmed feedback: well-planned mechanical execution → Sonnet.
- **CLAUDE.md drift count: 6 instances.** Now spans `edit_file.lastEditLineRanges`, task statuses, agent tool actions, bitbucket_pr action count, bitbucket_review action count, and the missing BLOCKED status. **The CLAUDE.md audit is now a real backlog item, not a side note.**
- **No race this batch (Sonnet sleep+commit pattern landed cleanly).**
- **Doc length record set:** `bitbucket_pr` 982 lines is the swarm's largest single-file doc. Justified by 19 actions × per-action quality bar.

---

## Batch 15 — 2026-05-10 (sonar + bamboo cluster, sonnet)

Tools: `sonar`, `bamboo_builds`, `bamboo_plans`. Closes the integration cluster.

### `sonar` — commit `f361e4de8` — STRONG keep, 1124 lines (new record)

- **🚨 CLAUDE.md drift: 18 actions actual, 13 in CLAUDE.md** (missed `issue_facets`, `current_user`, `quality_gates_list`, `hotspot_detail`, and undercounted `rule`).
- **`local_analysis` is ~350 lines of runtime alone** — branch resolution, Maven multi-module scoping, ProcessBuilder security (token in env, never in argv), CE-task polling, parallel per-file fan-out. Uniquely irreplaceable.
- `branch_quality_report` consolidates new-code quality in one call. Uniquely capable vs `run_command curl`.

### `bamboo_builds` — commit `349c88e1a` — STRONG keep, 680 lines

- **11 actions confirmed** (matches CLAUDE.md).
- **🚨 X-Atlassian-Token + form-body requirement is the load-bearing safety value.** LLM almost always omits both when falling back to `run_command curl`, producing 403 XSRF rejection.
- `download_artifact` silently writes to `java.io.tmpdir` when `target_path` omitted — flagged downside.
- `stop_build` + `cancel_build` accept legacy `result_key` alias — removable param.
- `get_artifacts` + `download_artifact` merge candidate (list-and-optionally-download in one action).

### `bamboo_plans` — commit `ac75fb34d` — KEEP NORMAL, 552 lines

- **🚨 CLAUDE.md drift: 10 actions actual, 8 in CLAUDE.md.**
- **`bamboo_plans` ↔ `bamboo_builds` should NOT merge.** 21 combined actions would exceed sensible meta-tool size; keeping split saves schema cost when LLM only needs one side.
- **`get_build_variables` is semantically wrong tool** — operates on `result_key`, belongs in `bamboo_builds`.
- `get_plans` only used internally by `auto_detect_plan` legacy path — drop candidate.

### Action items surfaced by Batch 15

- [ ] **🚨 CLAUDE.md drift fixes:** `sonar` 18 actions (not 13), `bamboo_plans` 10 actions (not 8). Sweep CLAUDE.md.
- [ ] Move `bamboo_plans.get_build_variables` to `bamboo_builds` (semantically belongs there).
- [ ] Drop `bamboo_plans.get_plans` (only used internally) or reduce visibility.
- [ ] Merge `bamboo_builds.get_artifacts` + `download_artifact` (list-and-optionally-download).
- [ ] Remove legacy `result_key` alias from `bamboo_builds.stop_build` + `cancel_build`.

### Cross-cutting observations from Batch 15

- **Sonnet+sleep is rock-solid.** No race in 2 batches now; Sonnet quality matches Opus for these tasks; per-batch wall time is shorter.
- **CLAUDE.md drift count: 8+ instances.** `edit_file.lastEditLineRanges`, task statuses, missing BLOCKED, agent tool 5-actions, bitbucket_pr action count, bitbucket_review action count, sonar action count, bamboo_plans action count. **The CLAUDE.md audit is now urgent.**
- **Doc length new high:** sonar 1124 lines for 18 actions. Tracks complexity faithfully — the swarm's "match the surface area" instinct is calibrated.
- **All 4 integration tool families now documented** (jira, bitbucket_pr/repo/review, sonar, bamboo_builds/plans). 9 integration tools, ~6800 lines of docs. Phase 5 integration coverage: ~100%.

---

## Batch 16 — 2026-05-10 (framework + project model, sonnet)

Tools: `spring`, `build`, `project_structure`.

### `spring` — commit `02a8e875f` — STRONG keep, READ_ONLY, 656 lines

- 16 actions actual (CLAUDE.md says 15+; close enough — the 16th is `boot_endpoints` only present when `includeEndpointActions=true`).
- **Drop candidates:** `scheduled_tasks` and `event_listeners` are zero-param convenience wrappers around `annotated_methods(annotation=@Scheduled)` and `annotated_methods(annotation=@EventListener)`.

### `build` — commit `416ad2ec` — STRONG keep, READ_ONLY, 933 lines

- **🚨 CLAUDE.md drift: 26 actions actual, 11 in CLAUDE.md (off by >2x).** Python ecosystem actions (pip, Poetry, uv, pytest) were added later and CLAUDE.md never updated. **The largest single drift instance.**
- **Merge opportunities:** the three `*_list`, three `*_outdated`, and two `*_lock_status` groups across pip/Poetry/uv are structurally identical. Collapsing into `python_list(manager)` / `python_outdated(manager)` / `python_lock_status(manager)` would reduce action count from 26 → 20 with no LLM capability loss.

### `project_structure` — commit `032f11507` — STRONG keep, 614 lines

- 14 actions confirmed (matches CLAUDE.md).
- **🚨 REAL BUG: 8 write actions are NOT in `AgentLoop.WRITE_TOOLS`** → not plan-mode-blocked, schema not filtered in plan mode. `requestApproval` is a no-op under `ALWAYS_APPROVE`. **Joins the swarm-surfaced bug list:** find_definition Python fallback, find_references mirror bug, revert_file PathValidator bypass, create_file charset asymmetry. **Action item:** add `project_structure` to `AgentLoop.WRITE_TOOLS`.
- **`scope` param overloaded** — means dependency scope for `set_module_dependency` but enumeration scope for `topology` / `list_*`. Documented as audit observation.
- Drop candidates: `add_content_root` + `remove_content_root` (only apply to pure IDE-managed modules, rare in Gradle/Maven projects).

### Action items surfaced by Batch 16

- [ ] **🚨 Add `project_structure` to `AgentLoop.WRITE_TOOLS`** — write actions are currently plan-mode-bypassable. Fifth real bug surfaced by the swarm.
- [ ] **🚨 CLAUDE.md drift fix:** `build` 26 actions (not 11). Largest single drift instance.
- [ ] **Collapse `build`'s pip/Poetry/uv triples** into `python_list(manager)` etc. — saves 6 schema slots, no capability loss.
- [ ] Drop `spring.scheduled_tasks` + `event_listeners` (convenience-wrapper redundancy with `annotated_methods`).
- [ ] Drop `project_structure.add_content_root` + `remove_content_root` if pure IDE-managed modules are rare.
- [ ] Disambiguate `project_structure.scope` param (overloaded between dependency and enumeration semantics).

### Cross-cutting observations from Batch 16

- **Bug count from swarm: 5 real defects.** find_definition (PSI), find_references (PSI mirror), revert_file (PathValidator bypass), create_file (charset), project_structure (plan-mode bypass). Each found via independent per-tool review — none would have surfaced via testing alone.
- **CLAUDE.md drift count: 9+ instances.** The `build` undercount (26 vs 11) is the new high-water mark. **CLAUDE.md audit is no longer optional.**
- **Per-batch wall time consistent at ~5-7 min** with sonnet + sleep-before-commit. Throughput: ~3 tools / 6 min = ~30 tools/hr. Remaining 34 tools → ~1.1 hours wall time.

---

## Batch 17 — 2026-05-10 (Python framework cluster, sonnet)

Tools: `django`, `fastapi`, `flask`. All 3 share `PythonFileScanner`, all READ_ONLY.

### `django` — commit `0276b1b7a` — STRONG keep on `models`/`settings`, NORMAL on others, 579 lines

- 14 actions confirmed.
- **🚨 `middleware` and `version_info` silently ignore `filter` param** — common LLM mistake.
- **🚨 `redactIfSensitive` is key-name heuristic** — custom keys like `MY_STRIPE_CREDENTIAL` are NOT redacted (downside).
- **`urls` doesn't follow `include()` chains** — shallow URL resolution only.
- `serializers` + `forms` are structurally identical scanner actions — merge candidate (but per-name clarity is preferred for LLM usability).

### `fastapi` — commit `71b3bb342` — KEEP, 441 lines

- 10 actions confirmed.
- `routes` STRONG keep — composes `APIRouter(prefix=...)` + `app.include_router(..., prefix=...)` into full URL paths. Only action `search_code` cannot replicate.
- **🚨 Cross-file router imports silently not fully composed.** Router A imported into router B then into app — only inner-most file's prefix applied. Multi-level composition silently incomplete.
- `database` action has fragile base-class detection (`Base`/`Model` literals only) — WEAK drop note.

### `flask` — commit `9a6914fd7` — KEEP, 709 lines

- 10 actions confirmed.
- **🚨🚨 `models` scans only files literally named `models.py`. `forms` scans only `forms.py`/`form.py`.** Projects with split models (e.g. `user_model.py`, `orders/schema.py`) get **empty results silently**. Both documented as action-level drop candidates. **Real bug surface.**
- **🚨 `extensions` uses hard-coded allow-list of ~25 extension class names.** Custom/uncommon extensions invisible. List drifts as Flask ecosystem evolves.
- Drop candidates: `templates` (can be replaced by `glob_files **/*.html`), `version_info` (search_code with specifier patterns equivalent).

### Action items surfaced by Batch 17

- [ ] **🚨 Fix `flask.models` / `flask.forms` filename hardcoding** — should scan all .py files for `class X(db.Model)` / `class Y(FlaskForm)` patterns regardless of filename.
- [ ] **🚨 Make `flask.extensions` extensible** — allow-list approach won't scale; use class-base detection or user-config override.
- [ ] **🚨 Make Django `redactIfSensitive` user-configurable** — current key-name heuristic misses custom sensitive keys.
- [ ] Make `django.urls` follow `include()` chains for full URL resolution.
- [ ] Make `fastapi` resolve cross-file router prefix chains.
- [ ] Drop or merge `flask.templates` / `flask.version_info` (low-value vs `glob_files` / `search_code`).

### Cross-cutting observations from Batch 17

- **Real bug count from swarm: 6 defects.** Added `flask.models` / `flask.forms` filename hardcoding to the list. These cause silent empty results — the worst kind of bug because the LLM gets a "no errors" signal.
- **Python framework tools share scaffolding but diverge on convention details.** `PythonFileScanner` is shared, but per-framework heuristics (Django app discovery, FastAPI route composition, Flask blueprint stacking) are tool-specific and vary in completeness.
- **No CLAUDE.md drift this batch** — all three matched. Drift count stays at 9.

---

## Batch 18 — 2026-05-10 (debug + send_stdin, sonnet)

Tools: `debug_inspect`, `debug_breakpoints`, `send_stdin`.

### `debug_inspect` — commit `43e5712c0` — STRONG keep, 736 lines + 215 narrative

- 9 actions confirmed (matches CLAUDE.md).
- **🚨 `drop_frame` rewinds program counter only — state NOT undone.** Name implies full rewind; implementation is PC-only. **Footgun.**
- **`memory_view` silently unavailable on remote/non-HotSpot VMs** (`canGetInstanceInfo=false`). LLM must read error and not retry.
- `evaluate` never spills (single value); `get_variables`/`thread_dump`/`memory_view` all spill on >30K.
- `set_value` two-path: XValueModifier primary + evaluate-with-assignment fallback.

### `debug_breakpoints` — commit `b5e815ecb` — STRONG keep, 607 lines

- 7 actions confirmed (matches CLAUDE.md).
- **🚨 CLAUDE.md/KDoc drift:** class KDoc still says "8 actions covering breakpoint CRUD and session lifecycle initiation" — predates `start_session` removal (now `runtime_exec(action=run_config, mode=debug)`). Drift count: 10.
- **🚨 `remove_breakpoint` CANNOT remove exception breakpoints.** Exception breakpoints have no `file:line` anchor in IntelliJ's data model. LLM gets silent "No breakpoint found". Future "remove by id" design needed.
- `method_breakpoint` closest to drop candidate — line breakpoint on the method's opening line achieves same with no perf overhead.

### `send_stdin` — commit `33cf5964a` — KEEP NORMAL / DROP NORMAL (balanced), 153 lines

- **MERGE_CANDIDATE with `background_process` explicitly flagged.** Migration path: absorb `ProcessRegistry` idle path into `BackgroundPool`, migrate guards, drop standalone.
- **🚨 `isLikelyPasswordPrompt` is keyword-heuristic** — fires on `password`, `passphrase`, `secret` etc. False positives block legitimate stdin writes.
- **🚨 `IDLE_AFTER_STDIN_MS = 10s` misfires on slow computing processes** — premature `[IDLE]` signal.

### Action items surfaced by Batch 18

- [ ] **🚨 Fix `remove_breakpoint`** to accept removal by id, not just file:line. Currently can't remove exception breakpoints at all.
- [ ] **🚨 Update `debug_breakpoints` KDoc** — remove "8 actions" + "session lifecycle initiation" (predates start_session removal).
- [ ] Document `drop_frame`'s PC-only behavior in LLM-facing description (or rename to make limit explicit).
- [ ] Tighten `send_stdin.isLikelyPasswordPrompt` heuristic (or make user-overridable).
- [ ] Tune `IDLE_AFTER_STDIN_MS` or make it configurable per-process — current 10s misfires.

### Cross-cutting observations from Batch 18

- **CLAUDE.md drift count: 10.** `debug_breakpoints` KDoc joins the list. Drift sources are now: agent CLAUDE.md (multiple), `:agent` CLAUDE.md (multiple), source-level KDoc comments (this one). The audit needs to cover both CLAUDE.md and source KDocs.
- **3 actionable real bugs added this batch:** `remove_breakpoint` exception-breakpoint silent failure, `drop_frame` state-not-rewound footgun, `send_stdin` password-prompt heuristic false-positives. Total real bug count: 9.
- **No race this batch.** Sleep mitigation continues to work.

---

## Batch 19 — 2026-05-10 (PSI intelligence cluster, sonnet)

Tools: `file_structure`, `type_inference`, `dataflow_analysis`. Continued PSI bug-pattern audit.

**Bug pattern verdict: still isolated to `find_definition` + `find_references` only.** All three tools in this batch correctly use `registry.forFile(psiFile)` — they always have a `PsiFile` in hand before provider lookup, so the no-element-context fallback path that bugs the two siblings doesn't apply.

### `file_structure` — commit `c39b4c4af` — STRONG keep, 229 lines

- 8-10x token saving vs `read_file` on a 1000-line file (signatures mode returns ~100 lines).
- ✅ Bug absent — line 290 uses `registry.forFile(psiFile)`.
- **Surprising:** `allowedWorkers` includes `ORCHESTRATOR`, unlike `find_definition`/`find_references` which restrict to ANALYZER+REVIEWER. Orchestrator legitimately needs file shape to route sub-tasks.

### `type_inference` — commit `2712a5847` — STRONG keep, 248 lines

- Resolves types invisible in source: Kotlin `val`/`var` inferred initializers, lambda `it` types in chained expressions, generic bound resolution, `PLATFORM` nullability on Java APIs from Kotlin.
- ✅ Bug absent — line 331 uses `registry.forFile(psiFile)`.
- **🚨 Latent ordering issue in `classifyElementKind`:** uses `PsiTreeUtil.getParentOfType` for Java PSI then a manual `while (current != null)` class-name loop for Kotlin. Subtle dependency: if a Kotlin element matches a Java PSI interface (e.g. compiled Kotlin forms matching `PsiLocalVariable`), it gets classified as Java local instead of `KtProperty`.

### `dataflow_analysis` — commit `7cba94ca8` — STRONG keep / WEAK drop, 253 lines

- Java-only scope. Kotlin explicitly rejected at lines 299-302; Python returns null (no equivalent API).
- ✅ Bug absent — line 336 uses `registry.forFile(psiFile)`.
- 5-15 manual tool calls saved per investigation for fields with multiple writers.
- **Note:** subagent found `documentation()` already present (likely added by a prior swarm agent in an earlier batch); verified all fields correct rather than re-authoring.

### Action items surfaced by Batch 19

- [ ] Investigate `type_inference.classifyElementKind` Kotlin-vs-Java traversal ordering — could misclassify Kotlin elements matching Java PSI interfaces.
- [ ] Document `dataflow_analysis` Python-on-position behavior more clearly: returns "No expression found at this position" instead of "Code intelligence not available" (misleading but not incorrect).

### Cross-cutting observations from Batch 19

- **PSI bug audit is now complete.** 8 PSI tools surveyed; 2 have the fallback bug (`find_definition`, `find_references`); 6 are correct (`call_hierarchy`, `type_hierarchy`, `find_implementations`, `file_structure`, `type_inference`, `dataflow_analysis`). The fix shape from `find_implementations` is the canonical reference. Single PR can close both bugs.
- **No new CLAUDE.md drift this batch.** Drift count holds at 10.
- **No race this batch (8 in a row).**

---

## Batch 20 — 2026-05-10 (PSI cluster — salvaged from interrupted dispatch)

Tools: `get_method_body`, `get_annotations`, `test_finder`. Subagent dispatches were rejected mid-flight but completed `documentation()` blocks landed on disk; committed manually 2026-05-11.

### `get_method_body` — commit `0aab8986d` — STRONG keep, 215 lines

- PSI-backed method-body extractor: resolves class via `LanguageIntelligenceProvider.findSymbol`, runs `findMethodsByName` with inherited fallback.
- **🚨 Surprising:** emits `// Source unavailable` on a null `getBody` result with NO secondary fallback. Compiled languages without sources surface as silent gaps.

### `get_annotations` — commit `8a65dac68` — STRONG keep, 215 lines

- **🚨 `include_inherited=true` on a FIELD member is silently overridden to false** (line 95 hardcodes `provider.getMetadata(field, false)`). LLM's explicit request is silently ignored. Technically correct (JVM fields don't inherit) but the silent override is the kind of behavior that surprises.

### `test_finder` — commit `a000e14e4` — STRONG keep, 156 lines

- ✅ Uses correct `registry.forFile` pattern — **closes the PSI cluster bug audit.** Bug stays isolated to `find_definition` + `find_references` only.

---

## Batch 21 — 2026-05-11 (IDE inspection cluster, sonnet)

Tools: `run_inspections`, `problem_view`, `list_quickfixes`. Closes the diagnostics tool family.

### `run_inspections` — commit `881809f56` — STRONG keep, 62 lines

- Unique access to IntelliJ's full inspection profile on a single file at sub-5s latency; no static analysis shell tool replicates this fidelity without a full project build.

### `problem_view` — commit `78af9e5567` — NORMAL keep, 167 lines

- **🚨 Distinct value confirmed.** The three diagnostics-family tools are complementary, not redundant: `problem_view` = cheap triage after batch edit (zero PSI walk, reads pre-existing IDE daemon state); `diagnostics` = precise per-file verifier; `run_inspections` = thoroughness audit.
- **Wolf-placeholder behavior:** synthetic WARNING at line 0 with `toolId='wolf'` requires `diagnostics` fallback for line-precise details.

### `list_quickfixes` — commit `8ee8ccbc7` — NORMAL keep, 188 lines

- **🚨 MERGE_OPPORTUNITY:** shares ~60 lines of identical inspection-walk boilerplate with `run_inspections` (`profile.getInspectionTools → LocalInspectionToolWrapper → HighlightDisplayKey.find → profile.isToolEnabled → buildVisitor → PsiRecursiveElementWalkingVisitor`). **Phase 8 refactor: extract shared `InspectionWalker` utility.**
- **Architectural decision documented:** `list_quickfixes` does NOT apply fixes — separation is intentional. Applying would force `FILE_WRITE` classification (approval gate on every call), eliminate LLM inspection before commit, break read/write parallel-execution boundary.

### Cross-cutting from Batch 21

- **The `isError=false` shared-API drift is now formally documented in all 4 family tools** (`diagnostics`, `run_inspections`, `problem_view`, `list_quickfixes`) — each has the same audit-note pattern, making the issue auditable for the eventual API-clarity fix.
- **No race in 9 consecutive batches.** Sleep mitigation is mature.

---

## Batch 22 — 2026-05-11 (close DB family + structural_search, sonnet)

Tools: `db_list_profiles`, `db_stats`, `structural_search`. Closes the database tool family.

### `db_list_profiles` — commit `c5fe81ad1` — STRONG keep, 102 lines

- Pure config read (no network); gateway tool for the entire `db_*` family. Without it, LLM has to guess profile ids and hits `NotFound` first.
- Batch 12 merge-rejection rationale (profiles vs databases — different IO models) preserved as audit observation.

### `db_stats` — commit `04e5ca35f` — STRONG keep, 211 lines

- Cross-engine table-size/row-count stats: avoids 4 different engine-specific catalog dialects (PG `pg_stat_user_tables`, MySQL `INFORMATION_SCHEMA.TABLES`, MSSQL `sys.dm_db_partition_stats`, SQLite `PRAGMA page_count`).
- **🚨 `table` without `schema` is silently ignored** — 4-param design has implicit-scope-by-params trap.
- **50-table cap with no overflow hint** — LLM doesn't know it's seeing a slice.

### `structural_search` — commit `429c5d0b8` — NORMAL keep / NORMAL drop, 256 lines

#### 🚨 REAL BUG #11 surfaced — Python misroute

`StructuralSearchTool.kt:91`:
```kotlin
val specific = registry.forLanguageId(langId)
if (specific != null) listOf(specific) else allProviders
```

When `file_type = "python"` AND Python plugin installed: `registry.forLanguageId("Python")` returns `PythonProvider` (non-null) → `providersToTry = [PythonProvider]`. `PythonProvider.structuralSearch()` always returns null. Result: `"Error: structural search failed — provider returned null"` — misleading generic error instead of "Python SSR not supported."

**Fix:** add a `supportsStructuralSearch()` capability flag to `LanguageIntelligenceProvider` OR check for null before including a provider in `providersToTry`.

#### Other findings
1. **🚨 `JavaKotlinProvider` hardwires `JavaFileType.INSTANCE` at line 708** — Kotlin patterns run against Java file type regardless of `file_type` param. Kotlin SSR may silently miss Kotlin files.
2. **Double cap:** provider internal cap of 50, then tool `max_results` (default 20). Effective max is always `min(max_results, 50)`.

### Action items surfaced by Batch 22

- [ ] **🚨 Fix `structural_search` Python misroute** — add `supportsStructuralSearch()` capability check or null-filter providers.
- [ ] **🚨 Fix `JavaKotlinProvider.structuralSearch()` Kotlin handling** — currently uses `JavaFileType.INSTANCE` for both langs.
- [ ] Surface `db_stats` 50-table truncation explicitly in result metadata.
- [ ] Make `db_stats` `table` param require `schema` (currently silent-ignore).

### Cross-cutting observations from Batch 22

- **Real bug count from swarm: 11 defects.** Two new in structural_search (Python misroute + Kotlin file type hardwire).
- **Database tool family complete.** 6 tools documented (`db_query`, `db_schema`, `db_list_databases`, `db_explain`, `db_list_profiles`, `db_stats`).
- **No race in 10 consecutive batches.**

---

## Batch 23 — 2026-05-11 (refactor + PSI + context, sonnet)

Tools: `refactor_rename`, `read_write_access`, `project_context`.

### `refactor_rename` — commit `e21e2cc71` — STRONG keep, 265 lines

- 3-phase pipeline: ReadAction.nonBlocking element find → ReadAction.nonBlocking findUsages + classifyUsage → EDT WriteCommandAction. PSI/VFS state stable across phases.
- **🚨 `RenameSafetyAnalyzer` hard library block is unconditional** — `confirm_cross_module=true` cannot bypass it. LLM must know this to avoid futile retries.
- **🚨 MERGE_OPPORTUNITY (3-way):** `refactor_rename` + `format_code` + `optimize_imports` share ~80% scaffold (DumbService → ReadAction → EDT WriteCommandAction → no-op detect → ToolResult). Could become a `refactor` meta-tool with `kind: rename|format|optimize_imports`.

### `read_write_access` — commit `5d4bd32e3` — STRONG keep, 228 lines

- ✅ No fallback bug — uses `registry.forFile(psiFile)`.
- Unique value: READ_WRITE compound bucket (compound assignments `+=`, `++` etc.) that `find_references` + manual classification systematically misses.

### `project_context` — commit `b98f891ba` — STRONG keep, 150 lines

- Collapses 6-12 tool calls (glob build files, git log via run_command, each integration tool separately) into one mission briefing with graceful degradation.
- **🚨 In-process cache never invalidated.** Stale-while-revalidate; LLM may get stale data after long sessions.
- **🚨 MERGE_OPPORTUNITY:** Bamboo/Sonar summary sections overlap with dedicated integration tools. Future `sections` parameter could let LLM skip those when integration tools will be called anyway.

### Cross-cutting from Batch 23

- **3-way merge candidate is the largest yet:** `refactor_rename` + `format_code` + `optimize_imports`. Single `refactor(kind)` would save 3 schema slots.
- **`project_context`'s never-invalidated cache** joins the growing list of long-session bugs (companion to `ask_followup_question`'s `pendingQuestions` single-slot field).

---

## Batch 24 — 2026-05-11 (utility cluster, sonnet)

Tools: `ask_user_input`, `discard_plan`, `endpoints`.

### `ask_user_input` — commit `5bbde7671` — STRONG keep, 213 lines

**🚨 NOT redundant with `ask_followup_question`** despite name overlap. Different concerns:
- `ask_followup_question` logs the answer; `ask_user_input` PIPES to stdin and never stores (credential-safe).
- `ask_user_input` requires a live `process_id` validated against `ProcessRegistry`; the other doesn't.
- **`ask_user_input` is the ONLY tool that can handle a password prompt safely** without leaking the credential into conversation history.

**🚨 Findings:**
1. **`showInputCallback` null-check gap** — unlike `AskQuestionsTool`, no early-return guard; deferred hangs until timeout kills the process.
2. **`pendingInput` single-slot companion-object race** — same structural risk as `pendingQuestions`. Safe today under single-threaded loop.
3. **Post-write monitor loop is a copy of `SendStdinTool`'s** — drift risk.
4. **On timeout, the process is killed unconditionally** with no rollback.

### `discard_plan` — commit `a78535777` — NORMAL keep, 140 lines

- Only primitive that programmatically invalidates a stale plan: (a) rewrites prior `plan_mode_respond` entry in `api_conversation_history` to `"[Plan discarded — do not reference]"`, (b) fires UI callback to clear the card.
- **Does NOT flip `planModeActive`** — session stays in plan mode after call (correct: agent continues exploring).
- Schema-filtered out of act mode (paired with `plan_mode_respond`).

### `endpoints` — commit `fa972c267` — STRONG keep, 489 lines

**🚨 Overlap with `spring.endpoints` / `fastapi.routes` / `flask.routes` analyzed in depth — NOT redundant:**

- **`spring(action=endpoints)`**: PSI-based, requires Spring + Java plugin. When `endpoints` (the tool) is registered, `SpringTool.includeEndpointActions=false` and both Spring endpoint actions return an explicit redirect error. **Mutually exclusive at runtime.**
- **`fastapi/flask routes`**: file-scan regex via `PythonFileScanner`, zero IDE plugin dep, works in any IDE. `endpoints` uses Python microservices provider (requires PyCharm Pro). Different data sources, different availability gates.
- **Unique capabilities `endpoints` provides that no other tool replicates:** `find_usages` (URL→callers via `UrlResolverManager`), `list_async` (Kafka/RabbitMQ/JMS topology), `export_openapi` (OAS synthesis), HTTP-Client endpoint type.

### Cross-cutting from Batch 24

- **Two more long-session reliability concerns:** `ask_user_input`'s callback null-check gap + `pendingInput` single-slot. Joining `ask_followup_question`'s identical pattern. **Both could be fixed in one PR** by extracting a shared "request-from-user" coordinator.
- **`endpoints` is a multi-tool merge that's already done right** — the `includeEndpointActions` flag is a runtime kill-switch for the Spring sibling. Good pattern worth replicating.

---

## Batch 25 — 2026-05-11 (runtime cluster, sonnet)

Tools: `runtime_config`, `java_runtime_exec`, `python_runtime_exec`. Closes the runtime tool family.

### `runtime_config` — commit `69ef75bdb` — STRONG keep, 69 lines added

#### 🚨🚨 REAL BUG #13: mutating actions bypass plan-mode + approval gate

`runtime_config.create_run_config / modify_run_config / delete_run_config` mutate IDE state but are **NOT in `AgentLoop.WRITE_TOOLS`** (line 579) and **NOT in `APPROVAL_TOOLS`**:
- Run in plan mode without being blocked (no execution guard at AgentLoop.run() line 1618).
- No user approval gate.
- **Only safety: hard-coded `[Agent]`-prefix check on `delete_run_config`** (and modify_run_config has asymmetric no-prefix-guard).

**Joins `project_structure` (Batch 16) as the second tool with this same defect — IDE-state-mutating actions outside `WRITE_TOOLS`.** Single fix PR can add both.

### `java_runtime_exec` — commit `b0053949c` — STRONG keep (run_tests), NORMAL (compile_module + rerun_failed_tests), 99 lines added

- 6 LLM-mistake patterns: simple class names, `#`-smuggled paths, running tests with compile errors present, calling rerun before run completes, >50 methods, TestNG 2+ silently routed to shell.
- 4 downsides: PATTERNS reflection fragility, 120s compile timeout, stale RunContentManager descriptors on rerun, smartReadAction latency on cold startup.
- Test runner is the highest-call-frequency action in agentic TDD — irreplaceable.

### `python_runtime_exec` — commit `f2c6eedda3` — STRONG keep, 68 lines added

#### 🚨 `method` param naming inconsistency

`python_runtime_exec.method` uses pytest `-k` boolean expression syntax (`test_foo or test_bar`, `test_foo and not slow`). **But `java_runtime_exec.method` uses comma-separated list (`testFoo,testBar`).** Same param name, opposite semantics. **Root cause of the most common Python test-running LLM mistake.**

**Action item:** rename `python_runtime_exec.method` to `k_expr` or `pytest_keyword` to match the underlying CLI flag.

### Cross-cutting from Batch 25

- **Real bug count: 13 defects.** `runtime_config` plan-mode bypass joins `project_structure` as the second instance of "IDE-state-mutating actions not in WRITE_TOOLS." Single PR can fix both.
- **Per-language `method` param semantics divergence** is a confusing-by-design API. Standard cross-language toolkits usually unify on one convention (typically comma-separated since `-k` is pytest-specific).

---

## Batch 26 — 2026-05-11 (final batch — close-out, sonnet)

Tools: `build_problems`, `read_document` (the binary doc reader), `ai_review`. **Closes Phase 5: 80 of 80 tools documented (100% coverage).**

### `build_problems` — commit `6339edcd8` — NORMAL keep, 188 lines

- Unique access to IDE's structured Build tool window model (typed problem categories: DEPENDENCY, REPOSITORY, PARENT, STRUCTURE, SYNTAX, SETTINGS, COMPILE, OTHER).
- **✅ Shared `isError=false` contract VERIFIED** (line 272). Joins the diagnostics family — 5 tools now have the same audit-note pattern.
- Artifact coordinate extraction for dependency errors is unique vs shell build output.

### `read_document` — commit `074a0da86` — STRONG keep, 160 lines

- Only path to read binary documents (PDF, DOCX, XLSX). `read_file` explicitly rejects these.
- **🚨 No pagination cursor.** `max_chars` is a total extraction cap, not a page size. LLM cannot read "next chunk" — must re-extract from page 1 with larger cap. **Common LLM mistake.**
- Tabula↔Tika page mismatch is the documented v1.1 audit edge case (accepted limitation).

### `ai_review` — commit `3e6a9161e` — STRONG keep, 537 lines

- `sideEffect = FILE_WRITE` confirmed (writes to `~/.workflow-orchestrator/{proj}/agent/pr-review-findings/`).
- **HOOK_EXEMPT but NOT in `WRITE_TOOLS`** — same design as task_*. Intentional: it's an internal staging store, not a user-facing write. Hook-exempt comment in `AgentLoop.kt:575` documents this.
- Only write path to the staging store backing the AI Review sub-tab.

---

# 🎉 Phase 5 Swarm — CLOSE-OUT SUMMARY

## Coverage

**80 of 80 tools = 100%** have `documentation()` blocks. (Per `:agent` CLAUDE.md's "~30 core + ~50 deferred = ~80 total" estimate.)

## Commit footprint

- **2 pilot commits** (read_file, debug_step from initial Phase 2)
- **5 Phase 3 commits** (schema, JCEF editor, React UI, ToolTestingPanel, swarm prompt template)
- **80 swarm commits** (one per tool documented, all on `fix/automation-handover-quality-tabs`)
- **26 findings-log commits** (one per batch, plus consolidated)
- **Total: ~110+ commits** of swarm-related work over 3 sessions

## Real bugs surfaced (13)

The most valuable Phase 5 output beyond the docs themselves:

1. `find_definition` Java/kotlin hardcoded fallback breaks pure-Python projects (`FindDefinitionTool.kt:113`)
2. `find_references` same bug pattern (`FindReferencesTool.kt:151`)
3. `revert_file` bypasses `PathValidator` entirely (security regression)
4. `revert_file` no VFS/Document refresh after git checkout (stale editor view)
5. `create_file` charset asymmetry (VFS path vs I/O fallback diverge on non-UTF-8 projects)
6. `create_file` `overwrite=true` shows misleading approval diff
7. `project_structure` 8 write actions NOT in `AgentLoop.WRITE_TOOLS` (plan-mode bypassable)
8. `flask.models` / `flask.forms` filename hardcoding (silent empty results)
9. `debug_breakpoints.remove_breakpoint` cannot remove exception breakpoints (silent failure)
10. `debug_inspect.drop_frame` rewinds PC only — state NOT undone (footgun)
11. `structural_search` misroutes Python via `forLanguageId` (misleading error)
12. `JavaKotlinProvider.structuralSearch` hardwires `JavaFileType.INSTANCE` (Kotlin patterns may miss Kotlin files)
13. `runtime_config` mutating actions bypass `WRITE_TOOLS` + approval gate (joins #7)

**Two are CRITICAL security/correctness defects** (#3, #5). **Six are silent-failure bugs** that wouldn't show up in tests — only surfaced via per-tool doc review.

## Drop / merge candidates (8+)

For Phase 7 cleanup PR:

1. **`format_code` + `optimize_imports` → `transform(kind:imports|format)`** — one-line dispatch difference, ~80% shared scaffolding.
2. **`format_code` + `optimize_imports` + `refactor_rename` → `refactor(kind)`** — the 3-way version. ~80% shared scaffold (DumbService → ReadAction → EDT WriteCommandAction → no-op detect → ToolResult).
3. **`task_list` → fold into `EnvironmentDetailsBuilder.appendTasks`** — partially redundant; 3-line patch adds `owner` + `blockedBy` to the per-turn render.
4. **`send_stdin` ↔ `background_process(action=send_stdin)`** — guard migration needed first.
5. **`db_list_databases` → `db_schema` level-0** — natural hierarchy extension.
6. **`bitbucket_pr` action drops:** `get_blocker_comment_count` (superseded), `get_required_builds` (rare), `update_pr_title` (rare).
7. **`jira` action drops:** `get_worklogs`, `get_board_issues`, `get_dev_branches` + `get_linked_prs`.
8. **`spring.scheduled_tasks` + `event_listeners`** — convenience wrappers around `annotated_methods`.
9. **`bamboo_plans.get_build_variables`** → belongs in `bamboo_builds` (operates on result_key).
10. **`build`'s pip/Poetry/uv triples** → `python_list(manager)` / `python_outdated(manager)` / `python_lock_status(manager)`.
11. **`run_inspections` + `list_quickfixes`** → extract shared `InspectionWalker` utility (~60 LOC).

## CLAUDE.md drift count: 10 instances

Largest drift: **`build` is 26 actions, CLAUDE.md said 11** (Python ecosystem actions added but never doc-updated). Other notable: `sonar` 18 vs 13, `bitbucket_review` 12 vs 6, `bitbucket_pr` 19 vs 18, `bamboo_plans` 10 vs 8, `agent` doesn't have the 5-actions API CLAUDE.md describes, `edit_file.lastEditLineRanges` doesn't exist in source, missing `BLOCKED` task status, `debug_breakpoints` KDoc mentions removed `start_session`.

**CLAUDE.md audit is a real backlog item** — recommend a dedicated sweep PR.

## Process learnings

- **Sonnet quality matches Opus** for well-planned mechanical execution. Faster + cheaper.
- **3 in parallel + sleep-before-commit** is the working pattern. Race rate: 2/26 batches (both bundled correctly, work survived).
- **Per-batch wall time:** ~5-7 min. Throughput: ~30 tools/hr.
- **The dispatcher prompt has bugs too** — subagents corrected me 4 times (e.g. edit_file matcher is char-strict not whitespace-tolerant; task_create has no blocks/blockedBy params). Healthy dynamic.

## Doc length stats

- **Longest single-tool doc:** `bitbucket_pr` at 982 lines + 176 narrative MD.
- **Longest meta-tool:** `sonar` at 1124 lines.
- **Total lines of `documentation()` blocks added across the swarm:** ~17,000 lines.
- **Total lines of narrative MD added:** ~2,500 lines across 12 narratives.

## Next session entry points

1. **Phase 6 verification swarm:** dispatch read-only subagents to cross-check each `documentation()` block against the source. Each verifier flags claims that don't match source.
2. **Phase 7 cleanup PR:** execute the drop/merge candidates above. Start with the 3-way `format_code + optimize_imports + refactor_rename → refactor(kind)` merge (largest schema savings).
3. **Bug-fix PR:** the 13 real bugs above. Two security/correctness defects (`revert_file` PathValidator bypass, `create_file` charset asymmetry) are the most urgent.
4. **CLAUDE.md audit sweep:** 10+ instances of source/doc drift documented.
5. **Compare Tools view:** consume the JSON aggregate of all `ToolDocumentation` blocks for the cross-tool heatmap + drop-candidate page (planned in Phase 7 of the original initiative).
