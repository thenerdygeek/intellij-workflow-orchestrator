---
name: run-command-auto-approve-research
description: "Research + design decision for an opt-in AI auto-approve of safe run_command commands (harmless→auto, risky/unknown→prompt). Read before designing/building this feature."
metadata: 
  node_type: memory
  type: project
  originSessionId: 35f86bea-445a-49fe-a980-c0962c14a34c
---

2026-06-20: User wants a NEW feature — opt-in auto-approve for `run_command`: harmless commands auto-approve, write/harmful/unsure commands still require explicit user approval. Status = **researched only, NOT built**. User said the research "looks good"; next step is `brainstorming` the design.

**Internet prior art (all confirmed):** every major agent ships this. Three architectures:
1. LLM self-flags each call (`requires_approval`) — Cline. ⚠ weakest: prompt-injection can make the model mark a malicious cmd "safe"; no curated allowlist.
2. Deterministic allow/deny rules — Claude Code (`allow`/`ask`/`deny`, deny→ask→allow, `Bash(npm run *)` prefix globs), Roo Code (`allowedCommands`/`deniedCommands`, longest-prefix, deny wins ties).
3. Allowlist + LLM-classifier fallback — Cursor Auto-review, Claude Code "Auto Mode" (⚠ Anthropic measured 17% false-NEGATIVE rate on the AI classifier).

**THE security lesson (Backslash/Noma/Manifold 2025-26):** denylists are mathematically unwinnable (infinite equivalent cmds; bypassed via base64 / subshell / quote-split `"e"cho` / env-var poisoning). Use an **allowlist**, fail closed (unknown→prompt), never trust raw string matching. → pick **architecture #2 (deterministic allowlist)**, NOT Cline's #1.

**KEY FINDING — the engine already exists in-repo and is built correctly:** `agent/security/CommandSafetyAnalyzer.kt` already classifies SAFE/RISKY/DANGEROUS with a real shell tokenizer (quote-aware, per-pipeline-segment), allowlist-first, fail-closed (unknown→RISKY), and already flags the obfuscation vectors (pipe-to-sh/bash, backtick, `$(`, sudo, rm -rf, dd if=, fork bomb, SQL DROP/TRUNCATE incl. DB-CLI quoted args). Defense-in-depth siblings: `DefaultCommandFilter` (hard pre-spawn block) + `ProcessEnvironment` (strips 35+ secrets).

**THE GAP (what to build):** `agent/loop/ApprovalPolicy.kt` hardcodes `run_command` to `ALWAYS_PER_INVOCATION`, so the analyzer's SAFE verdict is computed but NOT wired to skip the prompt (its own doc-comment "SAFE executes without approval (configurable)" is aspirational). Feature ≈ add an `AgentSettings` toggle (default **OFF**) → checked in `AgentLoop`'s approval block → `classify()==SAFE` skips the gate, `RISKY` still prompts, `DANGEROUS` stays hard-blocked. Small wiring+settings job, not new safety research.

**Caveats to design around:** SAFE is per-command-string and the model controls the string (e.g. `npm run <anything>` is allowed → arbitrary); keep DANGEROUS hard-blocked regardless of toggle; default OFF; consider audit log/undo; real boundary would eventually be OS sandboxing. Open design Qs for brainstorming: default behavior, user-editable allow/deny lists (Roo pattern), audit logging, whether RISKY should ever be auto-approvable.

Related: [[project_unified_tool_stop_feature]] (most recent agent-tool work). Approval internals in `agent/CLAUDE.md` → "Tool Approval" (ApprovalPolicy, SessionApprovalStore, memory-write gate as the model for a settings-gated bypass).

---

## ✅ IMPLEMENTED 2026-06-21 → PR #65 (open)

Branch `feature/run-command-auto-approve` pushed; PR #65 → main (14 commits, head `8c300910b`). Executed via subagent-driven-development: 10 tasks, each implemented + spec/quality-reviewed (all Approved). Final whole-branch Opus review = READY-TO-MERGE, security invariants traced CLEAN.

Commits: spec `09241c69f` → CommandShape `d4c314695`(+fix `7cbf77b24`) → CommandApprovalDecision `cd36c85db` → SessionCommandAllowlist `7f5c91025` → settings `d51730f30` → classifyCommandRisk `cdaf2f881` → AgentLoop skip `a0385794b` → AgentService wiring `0823f3bae` → sub-agent threading `b4a6bfc81` → controller/panel `3e18397d9` → webview `eb2f72c1f` → detekt-green+T6+T7 chore `93f082c92` → spec-correction `8c300910b`.

Gates ALL green: full clean `:agent:test --no-build-cache` (7m), `:agent:detekt`, `verifyPlugin` (dynamic-plugin eligible). Webview vitest 780/780.

⚠ ARCH CORRECTION (verified, spec amended `8c300910b`): there is ONE `AgentLoop(` build (AgentService.kt:2357, in executeTask); `resumeSession` DELEGATES to executeTask (no 2nd build) — the spec's original "two build sites + resume omission / BLOCKER-2" was a misread; the line mistaken for a 2nd build is the resume→executeTask call. resumeSession threads `sessionCommandAllowlist` into that call; `autoApproveSafeCommands` read from settings at the single build.

⚠ RECOVERY LESSON (reusable): TWO dispatched subagents died mid-run — Task 6 (API ConnectionRefused) and the detekt chore (session limit) — both AFTER editing files but BEFORE committing/reporting. Controller recovery that worked: inspect `git status`/`git diff`, confirm edits match the plan, RUN the gates (JUnit XML `failures=0`, detekt rerun), write the missing report, then commit. Do NOT reset+re-dispatch if the partial work verifies — cheaper and avoids half-applied-edit conflicts.

NEXT: CI on PR #65 + in-IDE smoke (toggle ON→`ls` silent+badge; `git push` prompts; "Approve all git push this session"→next silent; compound/redirect still prompts; new chat clears rules) — needs live IDE + Sourcegraph token. Then squash-merge. Deferred Minors (OK-TO-DEFER, in SDD ledger): T1/T2/T3 coverage nits, Task9 param-order hygiene.

## CI on PR #65 — 5/5 GREEN after a 1-commit fix (`3f5ff54ae`)

First CI run: 4/5 green; **Tests** job failed on ONE real miss (NOT the known flaky set):
`TerminalStreamingPipelineTest` source-text assertion that `AgentCefPanel.appendToolCall`
passes `toolCallId` as the FIRST JS arg (`callJs("appendToolCall(${JsEscape.toJsString(toolCallId)}…`).
ROOT CAUSE: Task 9 added badge args → the single-line `callJs` exceeded the line limit →
the detekt-green chore's ktlint autocorrect WRAPPED it (`callJs(\n  "appendToolCall(…",\n)`),
breaking the contiguous-substring check. ⚠ LESSON: after the chore reformatted production files
I only re-ran detekt + the 5 touched TEST files — NOT the full `:agent` suite — so a source-text
test in a DIFFERENT file slipped through. **Always re-run the FULL module suite after a
formatting/autocorrect pass, not just the files you edited.**
FIX (mirror sibling `updateToolResult`): revert to one-line `callJs` + update the existing
`ArgumentListWrapping` baseline ID to the new arg text (these long callJs lines are
baseline-suppressed, not wrapped — autocorrect over-corrected). 2 files, +2/-4.
Also confirmed: a full-suite `CallHierarchyCycleDetectionTest` 5s-TimeoutException was an
under-load flake (3 heavy gradle runs at once) — passes in isolation; unrelated.
Re-run CI 27891071510: Tests/Build&Verify/Kover/detekt/Konsist all PASS; PR MERGEABLE/CLEAN.

## /simplify quality pass (`01e5cd499`, head moved 3f5ff54ae→01e5cd499)
4 cleanup agents (reuse/simplification/efficiency/altitude) → applied 5, skipped the rest. Behavior-preserving; full clean :agent:test + detekt green; pushed to PR #65.
APPLIED: AgentLoop computes run_command CommandRisk once + reuses for gate riskLevel (companion `riskLabel`); CommandShape.coveringPrefixes tokenizes once + `isInlineEvalInterpreter`/`allSubsSimple` helpers; SessionCommandAllowlist→`ConcurrentHashMap.newKeySet()` (mirror SessionApprovalStore) + Regex companion val; setCefApprovalCallbacks onApproveCommandPrefix nullable-with-default.
SKIPPED (noted): AgentLoop FQN→imports (⚠ whole import block is ONE baselined ImportOrdering entry — the import-block-baseline trap; not worth baseline churn); ToolCallChain span→Badge (visual, unverifiable headless); remove classifyCommandRisk (intentional seam); flatten AutoApproveReason to webview (ripples); unify the 2 session stores (right altitude); chatStore spread + empty-snapshot (marginal/already-correct).

## Test-coverage expansion (`ffd55d43f`, head→ffd55d43f) — ~81 new tests
Asked "is it tested thoroughly?" → honest gap analysis → "add as much as possible". 4 parallel test-writers (file-disjoint), then full-suite + detekt + vitest verify.
- Pure cores: CommandShapeTest 8→33, CommandApprovalDecisionTest 4→14, SessionCommandAllowlistTest 4→13.
- Loop: AgentLoopAutoApproveTest 6→13 (Part B toggle-on, same-instance flow, DANGEROUS+covered, compound, redirect-on-covered, riskLevel-to-gate pins compute-once-reuse).
- NEW SubagentAutoApproveBehaviorTest (4): drives real SubagentRunner→AgentLoop — sub-agent path now BEHAVIORAL (was source-text-only). Closed the #1 gap.
- webview +26 (approval-card/chatStore/ToolCallChain incl. sibling regressions + guards).
STILL source-text-only (acceptable — classes not unit-instantiable per codebase convention): AgentServiceAutoApproveWiringTest, AgentControllerPrefixApprovalWiringTest. STILL untested: in-IDE smoke (needs live IDE+SG token).
⚠ LESSON: parallel test-writers share the MODULE compile — agent B's `gradle test` hit agent A's in-progress CommandShapeTest with JVM-illegal backtick names (`<`/`>` illegal in JVM method names) and "fixed" A's file → cross-file edit by a same-scoped agent. File-disjoint dispatch still shares the compile; verify the UNION with a full clean build (I did: grep illegal-names + compileTestKotlin + full suite). Also: backtick test names must avoid `< > . ; / : [ ]`.

## Rebased onto main/#66 (head→60abd21ac, force-pushed) — PR #65 MERGEABLE again
main advanced (PR #66 background-tool-execution `81c9d1b8a`) overlapping 13 files. Rebased: 17 commits replayed, only ONE conflict (detekt-baseline.xml at the detekt-chore commit) — resolved by union-keep-both (inert baseline entries are harmless), continued clean. 3-way auto-merge of AgentLoop/AgentService/panels/webview (#66 + this feature, both additive) compiled + behaved with no semantic conflict. Post-rebase: detekt flagged ONE drifted panel-Wrapping entry (snippet-drift from merged lambda, NOT a real defect — `}, b.cefBrowser)` identical on origin/main) → fixed by `:agent:detektBaseline` regen (contained: −6 entries, only AgentCefPanel/AgentDashboardPanel/RichStreamingPanel Wrapping/ParameterListWrapping). ALL GREEN: compile, detekt, full :agent:test (5574 tests, no failures), merged webview tsc+vitest 31, verifyPlugin. Backup branch: backup/run-command-auto-approve-pre-rebase-20260621-142643 (on origin). NEXT: CI on 60abd21ac + in-IDE smoke → squash-merge.
⚠ DIST NOW TRACKED on main (176 files, gitignore entry gone — changed since branch base; #66-era). Left committed dist as-is (build regenerates via buildWebview; not bloating PR with rebuilt bundles). If a 'dist up-to-date' gate ever appears, rebuild+commit.

CI on rebased head 60abd21ac (run 27899878420): 5/5 GREEN (Tests/Kover/Build&Verify/detekt/Konsist). PR #65 MERGEABLE/CLEAN. Only remaining gate: in-IDE smoke (human).

## ✅ MERGED to main 2026-06-21: PR #65 squash-merged `38e7ee9c2`. Spec doc now on main. Only post-merge follow-up: in-IDE smoke (human) + delete stale branches (feature/run-command-auto-approve + backup/...-20260621-142643).
