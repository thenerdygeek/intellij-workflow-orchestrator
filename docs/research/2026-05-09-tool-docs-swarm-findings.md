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
