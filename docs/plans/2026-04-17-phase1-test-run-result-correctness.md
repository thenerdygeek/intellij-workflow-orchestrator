# Phase 1 — Test/Run Result Correctness

**Fixes:** User incidents #1, #2, #3 — compile-error invisibility, empty-suite-reported-as-passed, zero-output-test silent-pass.
**Audit source:** `docs/research/2026-04-17-runtime-test-tool-audit.md`.
**Preconditions:** Branch `feature/tooling-architecture-enhancements`, clean working tree.
**Estimated:** 2–3 days. Medium complexity, high LLM-impact.

---

## Context

Four concrete user-reported incidents where the agent misreported test results:

1. Test with compile error treated as "TDD red phase, proceed to implementation" — because the tool returned `"1 errors, 0 warnings, aborted=false"` instead of the actual file/line/message.
2. IntelliJ UI showed "No tests found", tool reported `1 passed` (or `PASSED`) — shell fallback treats exit-0 as passed, and `RuntimeExecTool.get_test_results` duplicates pass/fail logic that maps 0/0/0/0 → PASSED.
3. Tests passed with near-zero duration and no stdout — tool reported `3 passed` without noting suspicious profile.
4. (Covered by Phase 2 — IDE state leaks.)

This phase fixes the result-fidelity problems (1, 2, 3). Phase 2 fixes the leak (4).

---

## Scope

**In:**
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/JavaRuntimeExecTool.kt` — compile-error capture, shell-fallback Surefire XML parsing.
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/RuntimeExecTool.kt` — unify through `interpretTestRoot`.
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/RuntimeExecShared.kt` — extend to emit structured `TestRunSummary` data class alongside prose.
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/CoverageTool.kt` — replace inline `formatTestResults` with `interpretTestRoot`.
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/build/PytestActions.kt` — summary-line reconciliation + zero-output heuristic.
- Test files under `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/runtime/`.

**Out:**
- Descriptor/listener cleanup → Phase 2.
- Pytest native-runner port → Phase 3.
- Output spilling → Phase 6.

---

## Task list

### Task 1.1 — Capture per-file compile errors in `run_tests` (fixes Incident #1)

**File:** `JavaRuntimeExecTool.kt:216–295` (the `ProjectTaskManager.build` branch of `executeWithNativeRunner`).

Currently: reflective `CompilerTopics.COMPILATION_STATUS` listener stores only counts in a `String`. On build failure, tool returns `"Compilation result: 1 errors, 0 warnings, aborted=false"`.

Change:
1. Replace the reflective proxy with a real subscription to `CompilerTopics.COMPILATION_STATUS` (import `com.intellij.openapi.compiler.CompilationStatusListener` directly — no reflection needed; the class is public in `intellij.java.compiler`).
2. In `compilationFinished(aborted, errors, warnings, context)`, capture the `CompileContext` into an `AtomicReference<CompileContext?>`.
3. On `buildResult.hasErrors()`, walk `context.getMessages(CompilerMessageCategory.ERROR)` and format per-file:
   ```
   BUILD FAILED — 3 compile error(s) prevented tests from starting:

   src/test/java/com/example/MyTest.java:42:5 — cannot find symbol: method asserT(boolean)
   src/test/java/com/example/MyTest.java:58:12 — ';' expected
   src/main/java/com/example/MyService.java:108:9 — incompatible types: String cannot be converted to int
   ```
4. Use `compile_module`'s existing formatter (`JavaRuntimeExecTool.kt:827–847`) — extract it to a shared helper `formatCompileErrors(context: CompileContext, target: String)` in a new file `RuntimeExecShared.kt` so both use-sites call the same code.
5. Update the `summary` field to lead with the first error: `"COMPILE FAILED: MyTest.java:42 cannot find symbol: method asserT"` — so an LLM skim-read can't conflate it with a red test.

### Task 1.2 — Unify through `interpretTestRoot` (fixes Incident #2, part a)

**File:** `RuntimeExecTool.kt:367–497` (`executeGetTestResults`).

Currently: duplicates the entire pass/fail classification inline. Maps 0/0/0/0 → `PASSED`.

Change:
1. Delete the inline classification (lines ~425–497).
2. Call `interpretTestRoot(testRoot, descriptor.displayName ?: "unknown")` from `RuntimeExecShared.kt:221` — which correctly handles empty-suite/terminated/defect cases.
3. If `statusFilter` is set, apply it AFTER `interpretTestRoot` returns — parse the already-formatted result or (better) have `interpretTestRoot` accept an optional `statusFilter` parameter.

**File:** `CoverageTool.kt:785–819` (`formatTestResults`).

Currently: has a third classifier using `isErrorProxy()` with brittle string matching (`errorMessage?.startsWith("java.lang.")`).

Change:
1. Delete `formatTestResults` and `isErrorProxy`.
2. Call `interpretTestRoot(root, testTarget)` — returns a `ToolResult`. Adapt the coverage code to work with a ToolResult (wrap its content in the larger coverage result).

### Task 1.3 — Parse Surefire/Gradle XML in shell fallback (fixes Incident #2, part b)

**File:** `JavaRuntimeExecTool.kt:634–787` (`executeWithShell`).

Currently: binary pass/fail based on exit code; `BUILD FAILURE` string matching.

Change:
1. After `process.waitFor()` completes, read test-report XML files:
   - Maven: `{moduleDir}/target/surefire-reports/TEST-*.xml` (and `failsafe-reports` for IT tests).
   - Gradle: `{moduleDir}/build/test-results/*/TEST-*.xml`.
2. Parse each XML via `javax.xml.parsers.DocumentBuilderFactory`. JUnit XML schema fields:
   - `testsuite` attributes: `tests`, `failures`, `errors`, `skipped`, `time`, `name`.
   - `testcase` children with `name`, `classname`, `time`.
   - Inner `<failure>`, `<error>`, `<skipped>` elements carry `message`, `type`, and element text (stack trace).
3. Build a `List<TestResultEntry>` in the same format as `RuntimeExecShared` so `interpretTestRoot` can format it.
4. When no XML is found AND no `Tests run:` / `> Task :test` markers appear in stdout → return `NO_TESTS_FOUND` explicitly (NOT `Tests PASSED`).
5. Create a new helper in `RuntimeExecShared.kt`: `parseJUnitXmlReports(moduleDir: File, tool: String): List<TestResultEntry>?` — returns null if no reports found, empty list if reports found but empty.

### Task 1.4 — Pytest summary-line reconciliation (fixes Incident #3, part a)

**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/build/PytestActions.kt:375–409`.

Currently: `parsePytestRunOutput` regex-matches `::name PASSED` lines with no cross-check.

Change:
1. Parse the summary line (`=== N passed, M failed, K skipped in X.XXs ===`) into a `Summary(passed, failed, skipped, errors)` struct.
2. Compare `tests.count { it.status == "PASSED" }` against `summary.passed`, and so on.
3. If they disagree, prepend a warning to the result: `"[PARSE MISMATCH] Verbose output parsed as N tests but pytest summary reports M. Raw output included below for verification."`
4. Include the summary line in the structured `.summary` of the `ToolResult` so it's always visible in logs.

### Task 1.5 — Pytest zero-output heuristic (fixes Incident #3, part b)

**File:** Same (`PytestActions.executePytestRun`, line ~148).

Change: After building the `results` object, compute:
- `totalDurationMs` = sum of per-test durations (need to extend regex to capture duration; pytest verbose format is `PASSED [ 33%]` not duration, so may need `--durations=0` flag — deferred, for now use process wall time).
- `stdoutVolumeKB` = output size minus the test-status lines.

If `passed > 0 && totalDurationMs / passed < 1 && stdoutVolumeKB < 1` → prepend:
```
[NOTE] All tests passed in near-zero time with minimal stdout. Consider verifying tests actually exercise the code under test (overmocked / empty body / wrong assertion target all produce this pattern).
```

This is a soft warning; LLM decides whether to investigate.

### Task 1.6 — Unit tests against fake SMTestProxy trees

**New file:** `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/runtime/InterpretTestRootTest.kt`.

Fixtures to cover (matches the failure-mode matrix in `2026-04-17-intellij-run-test-execution-contract.md` §6):

1. All tests pass — 3 leaves, `isDefect=false`, `isIgnored=false`.
2. Some fail — 2 pass + 1 `FAILED_INDEX`.
3. Empty suite — root has 0 children; `interpretTestRoot` must return `isError=true` with content "No test methods were found".
4. Engine defect — root has `isDefect=true`, 0 children with valid URLs.
5. Runner terminated — root `wasTerminated=true`, some children completed.
6. Initialization error — leaf node name is "initializationError", `isDefect=true`.
7. Comparison failure — `TestComparisonFailedState` magnitude → `FAILED` status, extract expected/actual.
8. Parameterized tests — multiple leaves with same name, different param suffixes.

Construct `SMTestProxy` instances via the public constructor + `addChild()`. Use `SMRootTestProxy()` for root. Assert against the formatted `ToolResult.content` and `ToolResult.summary`.

### Task 1.7 — Integration test for shell fallback XML parsing

**New file:** `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/runtime/SurefireXmlParseTest.kt`.

Fixtures: sample Surefire XML reports (paste actual Surefire output into `src/test/resources/surefire-samples/`) covering:
- 1 testsuite with 3 tests (2 pass, 1 fail).
- 1 testsuite with 0 tests (attribute `tests="0"`).
- Mixed passed + skipped + error.
- Malformed XML (missing closing tag).

Test `parseJUnitXmlReports` returns the right `List<TestResultEntry>`.

---

## Validation

Run:
```bash
./gradlew :agent:test --tests "*Runtime*" --tests "*Pytest*" --tests "*InterpretTestRoot*" --tests "*SurefireXml*"
./gradlew :agent:test --tests "*Coverage*"
./gradlew verifyPlugin
```

All must pass. New tests should fail on the pre-fix code (verify by `git stash` the implementation and re-running tests).

## Manual verification

In a sandbox IDE (`./gradlew runIde`):

1. **Incident #1 reproduction:** Create a failing compile test, ask agent to "run the MyTest test class". Before fix: reports "BUILD FAILED, 1 errors, 0 warnings, aborted=false". After fix: reports "BUILD FAILED — MyTest.java:42: cannot find symbol: method asserT".
2. **Incident #2 reproduction:** Ask agent to "run tests for com.example.NotActuallyATestClass" in a Maven multi-module project. Before fix: `Tests PASSED`. After fix: `NO_TESTS_FOUND — Surefire ran successfully but matched no test methods. Verify the class has @Test methods and is in a test source root.`
3. **Incident #3 reproduction:** Write a trivial empty-body pytest `def test_nothing(): pass`, run it. Before fix: `1 tests: 1 passed, 0 failed`. After fix: same + `[NOTE] All tests passed in near-zero time with minimal stdout...`.

## Exit criteria

- All four audit incidents verifiable as fixed in a sandbox IDE.
- Unit + integration tests green.
- `RuntimeExecTool`, `CoverageTool`, and `JavaRuntimeExecTool` all route through `interpretTestRoot`. Grep for `overallStatus = when` should return only one site (in `interpretTestRoot` or its helpers).
- `executeWithShell` returns `NO_TESTS_FOUND` explicitly when Surefire/Gradle report 0 tests.
- New system-prompt rule added to `SystemPrompt.kt` (section 7 Rules): *"BUILD FAILED / COMPILE FAILED in a tool result is an agent error (usually a syntax error in the code you just wrote). Do NOT proceed as if a test has red-phased. Fix the compile error first, then re-run."*

## Follow-ups (deferred)

- Streaming per-test events (`SMTRunnerEventsListener.TEST_STATUS` subscription) → Phase 3.
- Structured `ToolResult.data` JSON payload → out of scope; track separately.
- Extending XML parsing to cover TestNG XML → add when a user reports TestNG misreporting.
- Surefire config option `<useFile>true</useFile>` breaks per-test XML (default is per-suite). Accept this limitation; surface via a diagnostic note if parsing yields fewer testcases than the summary.
