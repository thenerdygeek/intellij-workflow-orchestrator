# IDE Tooling Architecture — 7-Phase Fix Plan

**Branch:** `feature/tooling-architecture-enhancements`
**Worktree:** `.worktrees/tooling-architecture/`
**Driving concern:** The AI agent's runtime/test/debug/inspection tools misreport results to the LLM, leak IntelliJ state, run doomed commands in multi-module projects, and hard-truncate outputs instead of spilling to disk. Four concrete user-visible incidents + ~25 contract gaps across four tool categories. See the four audit documents in `docs/research/2026-04-17-*.md`.

**Outcome goal:** The LLM receives high-fidelity, structured results from every IDE tool, with all per-item diagnostic data, correct classification of "nothing ran" vs "ran and passed", actionable errors in multi-module failure modes, zero IDE state leaks, and large outputs always available via disk spill.

---

## Audit documents (reference)

| Doc | Scope |
|---|---|
| `docs/research/2026-04-17-intellij-run-test-execution-contract.md` | Authoritative IntelliJ execution + SM test runner contract |
| `docs/research/2026-04-17-runtime-test-tool-audit.md` | runtime_exec, java_runtime_exec, python_runtime_exec, coverage |
| `docs/research/2026-04-17-debug-tools-audit.md` | debug_breakpoints, debug_step, debug_inspect, AgentDebugController |
| `docs/research/2026-04-17-inspection-tools-audit.md` | run_inspections, list_quickfixes, problem_view, diagnostics, format_code, optimize_imports, refactor_rename |
| `docs/research/2026-04-17-db-vcs-psi-tools-audit.md` | DB tools, changelist_shelve, PSI intelligence |

---

## Phase ordering & dependencies

```
Phase 1 ──────────────────────────────► Phase 7
   │                                       ▲
   ├──► Phase 2 (multi-module) ────────────┤
   │                                       │
   └──► Phase 3 (leaks) ──► Phase 4 ───────┤
                          │                │
                          ├──► Phase 5 ────┤
                          │                │
                          └──► Phase 6 ────┘
```

| Phase | Title | Depends on | Fixes | Est. |
|---|---|---|---|---|
| 1 | Test/run result correctness | — | Incidents #1, #2, #3 (compile-error invisibility, empty-suite → PASSED, zero-output pass) | 2–3 days |
| 2 | **Multi-module build validation** (NEW, inserted 2026-04-17) | — | Silently-wrong failures in multi-module projects: missing from settings.gradle, class in main sources, zero @Test methods | 1–2 days |
| 3 | IDE-state leak fixes (was Phase 2) | — | Incident #4 (initialization error on manual run) | 1–2 days |
| 4 | Pytest native-runner port (was Phase 3) | 3 | Replaces raw-subprocess pytest with ExecutionManager pipeline; interpreter/conftest fidelity | 3–4 days |
| 5 | Debug tool fixes (was Phase 4) | 3 | Callback timeout races, session-listener leaks, output truncation | 2 days |
| 6 | Inspection tool fixes (was Phase 5) | — | Per-item structured output, DumbService gaps, refactor-safety, quick-fix redundancy | 1–2 days |
| 7 | Wire ToolOutputSpiller everywhere (was Phase 6) | 1, 2, 3, 4, 5, 6 | **The worktree's original purpose** — 12K hard-truncation → 30K disk spill across all tools | 1 day |

**Parallelism:** Phases 1, 2, 3, 6 can all run in parallel — they touch disjoint code paths. Phases 4 and 5 depend on Phase 3's `RunInvocation` infrastructure. Phase 7 must be last because every prior phase refactors the code paths the spiller wires into.

---

## How to execute a phase in a separate Claude session

Each phase file is a self-contained brief. To run one:

```
cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/tooling-architecture
claude
> Read docs/plans/2026-04-17-phaseN-<name>.md and execute it using subagent-driven development.
```

Each plan has:
- **Preconditions** (branch state, prior phases, external deps)
- **Scope** (in/out)
- **Task list** with file:line citations
- **Validation** (tests, manual verification)
- **Exit criteria**
- **Follow-ups** (deferred to later phase)

---

## Cross-phase invariants

1. **Never hard-truncate at tool-level.** Truncation is the spiller's job (Phase 7). Until Phase 7 lands, existing `truncateOutput()` calls stay where they are.
2. **All new listeners/Disposables MUST be tied to a session-scoped `Disposable`.** Template in Phase 3.
3. **Structured data first.** New result types: return data classes serialised to prose, not prose assembled in-line. This makes Phase 7's spill-preview trivial (head 20 + tail 10 of prose).
4. **No reflection beyond what's already there.** Don't add new reflective paths **except** where the target plugin is optional (Gradle, Maven, Python) — those must be reflective to preserve zero-compile-time-dependency on optional plugins. Phase 2 explicitly does this.
5. **Tests before fixes.** Every phase delivers a test suite that would have caught the bug it fixes (TDD per the project's existing feedback memory).

---

## Execution log

| Phase | Status | Started | Finished | Notes |
|---|---|---|---|---|
| 1 | DONE | 2026-04-17 | 2026-04-17 | Tasks 1.1–1.7 landed. Commits `2fa48dc3`→`f698e88f`→`3c1ca0c3`→`ab5acf6c`→`6af26a4c`→`c0674734`→`d630e907`→`40aab31c`→`ce4459b1`→`2590eaab`. Per-file compile errors in run_tests build gate, unified `interpretTestRoot` classification across RuntimeExecTool/CoverageTool/JavaRuntimeExecTool, Surefire/Gradle XML parsing in shell fallback, pytest summary-line reconciliation + zero-output heuristic, `InterpretTestRootTest` (30KB) + `SurefireXmlParseTest` (16KB) + `FormatCompileErrorsTest` (15KB) + `ShellFallbackTest` GREEN. `BUILD FAILED / COMPILE FAILED` rule added to `SystemPrompt.kt:525`. |
| 2 | DONE | 2026-04-17 | 2026-04-17 | Tasks 2.1–2.10 landed. Commits `e5e93e88`→`269605d5`→`d3c7a13a`→`f4062eab`→`8decb7b6`. `BuildSystemValidator` with Gradle/Maven reflective probes + filesystem fallback + test-source-root + @Test count pre-check. Wired into `java_runtime_exec.run_tests` before both native and shell paths. `BuildSystemValidatorTest` (24KB, 7 scenarios) GREEN. |
| 3 | DONE | 2026-04-17 | 2026-04-17 | RunInvocation + session Disposable + tool refactors. Commits `eff98f66`→`503f34df`→`86ac174c`→`8f90baa5`→`9c67e4d6`→`b69efac5`→`5204ef37`. All 9 leak regression tests GREEN. Manual heap-dump verification in runIde still TODO — cannot run from CLI. |
| 4 | DONE | 2026-04-17 | 2026-04-17 | Tasks 3.1–3.6 landed. Commits `eb944a63`→`a73e7945`→`6a25b788`→`9459c7d7`→`f686c64f`→`2ba0c3b4`→`7f58e171`. `PyTestConfigurationType` detection in IdeContext, `PytestNativeLauncher` (reflective config setup), `python_runtime_exec.run_tests` routed through native runner with shell fallback, `collectTestResults` URL filter extended for `python://` + `file://` schemes, `PytestNativeLauncherTest` + SMTestProxy URL scheme tests GREEN, pytest fixture project for manual runIde testing. |
| 5 | DONE | 2026-04-17 | 2026-04-17 | DebugInvocation + awaitCallback + UUID session handles + breakpoint-type tests + Phase-7 TODO markers. Commits `c1995f76`→`4681f639`→`67baea87`→`baf9976b`→`c7fedb35`→`9aafb0e8`→`1f79c3f9`. 70 Debug + 11 IdeStateProbe tests GREEN. verifyPlugin GREEN (3 IDE versions). Task 4.4 spiller-wiring tagged `TODO(phase7)` at 4 sites — 2 handlers (thread_dump, memory_view) currently un-truncated, silent-overflow risk for Phase 7 to address. Manual runIde verification checklist: `docs/plans/2026-04-17-phase5-manual-verification.md`. |
| 6 | DONE | 2026-04-17 | 2026-04-17 | F1 structured DiagnosticEntry + F3 DumbService + F4 rename safety + F5 deferred + F6 isError. 23 commits (T2 `7eb4bd74`→T6 `788026a9`). All T2–T5 (98) + T6 (22) tests GREEN. Full `:agent:test` suite GREEN. F2 spiller wiring deferred to Phase 7 via 17+ `TODO(phase7)` markers at cap sites. F5 (`LocalInspectionToolWrapper.processFile`) and F6 (`DaemonCodeAnalyzerImpl.getFileHighlightingRanges`) deferred with `@Disabled` future-upgrade tests citing `docs/superpowers/research/2026-03-20-intellij-api-signatures.md §4`. |
| 7 | DONE | 2026-04-18 | 2026-04-18 | Tasks 6.1–6.13 landed. Commits (triage → foundation → wiring → docs): `594b3483` (triage doc) → `25e11202`→`9d53fa7f`→`eb0f07bf` (foundation: COMMAND 30K→100K, ToolResult.spillPath, spillOrFormat helper, AgentService.outputSpiller session-scoped) → `f320b28c`→`75d8cede` (T6.4 runtime) → `7066ace4` (T6.5 debug) → `28078698` (T6.6 inspection — structured preview) → `70d079b7` (T6.7+T6.8 DB + PSI) → `8e80a04b` (T6.10 COMMAND override on java_runtime_exec/coverage/run_inspections) → `13d933c4` (T6.11 SpillingWiringTest, 12 tests). All Phase 7 TODO markers resolved. Full :agent:test suite green. RunCommandTool's effective cap raised from 30K → 100K (was double-gating). |

---

## Deferred / out of scope

These are NOT covered by this 7-phase plan — track for later:

- **Integration tools** (jira, bamboo, sonar, bitbucket) — already use the ToolResult<T> typed-data pattern. Separate audit needed.
- **Framework tools** (spring, django, fastapi, flask, build) — file-scan-based; no runtime execution.
- **Agent architecture at the ToolRegistry layer** (3-tier deferred loading, tool approval gate) — out of scope.
- **`run_inspections` over large scopes** (full-project) — needs backgroundable task + progress indicator; separate spike.
- **Cross-IDE debug tool** (service↔simulator agent IPC via UDS) — already tracked separately in memory.
- **Gradle composite builds** (`includeBuild`) — Phase 2 defers; separate spike if a user reports it.
