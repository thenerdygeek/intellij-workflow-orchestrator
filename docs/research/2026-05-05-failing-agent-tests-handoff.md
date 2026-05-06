# Handoff — :agent:test failures after Bug 4 wiring

## Context

Branch: `feature/context-compaction`
Latest commit: `2e75d62a` (Bug 10 — task progress widget hides on completion)

The user just shipped a 10-bug fix series to this branch. After clean rebuild, `./gradlew :agent:test` reports **26 test failures out of 3254 tests**. The user wants you to investigate whether each failure is:

1. **A test-design deprecation** — the test asserts the OLD behavior we deliberately changed (update the test), or
2. **An actual regression** — the new code is wrong (fix the code).

Reach a verdict per failing test (or per failure cluster). Read-only investigation; if you decide to make fixes, do them in small atomic commits per cluster, but **stop and confirm with the user before any commit that changes production code rather than test code**.

## What the bug-fix series did that's likely related

The relevant commits are:

- `03a2181e` — **Bug 4 (VFS staleness)** — added `core/vfs/PostMutationRefresh.kt` with a `waitForSmartModeOrTimeout(project, timeoutMs)` suspend function. Wired it into 4 runners as a pre-launch barrier:
  - `JavaRuntimeExecTool.executeRunTests` (top of function, before any param validation)
  - `JavaRuntimeExecTool.executeRerunFailedTests` (after `coroutineContext.ensureActive()`)
  - `CoverageTool.executeRunWithCoverage` (top of function)
  - `PythonRuntimeExecTool.executeRunTests` (top of function)
  - `RuntimeExecTool.executeRunConfig` — REPLACED the existing `if (DumbService.isDumb(project)) return ToolResult(...)` hard-error with `waitForSmartModeOrTimeout` (wait then error after 60s).

The internal implementation:

```kotlin
suspend fun waitForSmartModeOrTimeout(project: Project, timeoutMs: Long = 60_000L): Boolean {
    val dumbService = DumbService.getInstance(project)
    if (!dumbService.isDumb) return true
    return withTimeoutOrNull(timeoutMs) {
        smartReadAction(project) { /* no-op */ }
        true
    } ?: false
}
```

File: `core/src/main/kotlin/com/workflow/orchestrator/core/vfs/PostMutationRefresh.kt:108-127`

- `a3b4a0c4` — **Bug 2+3** — added 3 new constructor params to `AgentLoop`:
  - `onCompactionState: ((Boolean, String) -> Unit)? = null`
  - `pendingModelChangeProvider: (() -> String?)? = null`
  - `onModelChangeApplied: ((String) -> Unit)? = null`
  All have null defaults. Should be backward-compatible.

- `df721c6c` — **Bug 5** — added `onCompactionState` parameter to `AgentService.executeTask` and `resumeSession`, both with null defaults.

## The 26 failures, clustered

### Cluster A — `ClassCastException: java.lang.Object cannot be cast to DumbService` (24 tests)

The most common failure. Stack:

```
java.lang.ClassCastException: class java.lang.Object cannot be cast to class com.intellij.openapi.project.DumbService
    at com.intellij.openapi.project.DumbService$Companion.getInstance(DumbService.kt:537)
    at com.workflow.orchestrator.core.vfs.PostMutationRefreshKt.waitForSmartModeOrTimeout(PostMutationRefresh.kt:125)
```

**Affected tests** (all clearly hit the runner entry-point barrier I added):

- `CoverageToolTest`: `run_with_coverage with too many methods returns too-many error`, `run_with_coverage with invalid method name returns invalid-name error`, `run_with_coverage without test_class returns error`, `run_with_coverage with method containing only separators returns invalid-value error`
- `JavaRuntimeExecToolTest` (most): `run_tests with invalid method name`, `T4 — malicious method name with backtick`, `T4 Gradle — multi-method`, `run_tests with single method`, `run_tests shell path — multi-method Maven`, `T4 — malicious method name with semicolon`, `run_tests with method containing only separators`, `shell path — finds Maven pom`, `shell path — returns error when project has no build file`, `shell path — returns error when project basePath is null`, `with too many methods`, `without class_name`, `T4 Maven — multi-method`, all 4 `RerunFailedTests` cases
- `PythonRuntimeExecToolTest`: `run_tests surfaces project base path error when missing`, `run_tests rejects unsafe -k pattern via delegate`
- `RuntimeExecRunConfigTest`: `run_config returns DUMB_MODE error when IDE is indexing`

**Root cause assessment:** the tests use a `mockk<Project>()` that returns null/Object for `getService(DumbService::class.java)`. My new barrier calls `DumbService.getInstance(project)` early in the runner, before any of the validations these tests exercise. Cluster A is therefore **test-design** failures — the tests were never written to pass through a `DumbService` lookup at the top of the runner because the previous code only called it inside `executeRunConfig` (and those specific tests stubbed it). When I lifted the dumb check to all 4 runners, every existing non-dumb-related test on those runners suddenly needs a `DumbService` stub.

**Recommended fix per test:** they need either (a) a `mockk<DumbService>` registered for `project.getService(DumbService::class.java)` returning `isDumb = false`, or (b) a test helper that bypasses `waitForSmartModeOrTimeout` (e.g. a `mockkStatic(...)` that makes it return `true`).

The CLEANEST fix is probably to extract a tiny helper in test-only code that mocks the `DumbService` lookup once per test class, then have each test class use it in `@BeforeEach`. There may already be a `ReadActionTestShim` (mentioned in `agent/CLAUDE.md`) that does similar — check `agent/src/test/kotlin/.../testutil/`.

The `RuntimeExecRunConfigTest > run_config returns DUMB_MODE error when IDE is indexing` test is the one I expected to need an *assertion* update — its old assertion was "returns DUMB_MODE error immediately when isDumb=true." My fix changed that to "wait 60s, then return DUMB_MODE: timeout waiting for indexing." If the test is asserting on the error message text, the message string changed too. Look at the exact assertion.

**Likely verdict for Cluster A:** test-design deprecation. The new code's contract is the right one — production code should NOT be reverted. Update the tests to stub `DumbService` and (for the `run_config` indexing test) update the assertion to match the new "wait then time out" message.

### Cluster B — `ToolSearchRelatedTest > bitbucket suggests git` and `> jira suggests git` (2 tests)

Failure type: plain `AssertionFailedError: expected: <true> but was: <false>`. No exception, no class-cast.

**Affected tests:**
- `ToolSearchRelatedTest > bitbucket suggests git()`
- `ToolSearchRelatedTest > jira suggests git()`

**Likely cause:** these tests probably check whether `tool_search "bitbucket"` / `tool_search "jira"` returns a related-tool suggestion of `"git"`. Nothing in my changes touched `ToolSearchTool`, the related-tool hint table, or anything in that area. The `git` tool is not a real tool in the registry — these tests look like they're asserting that a *suggestion* is included, and the suggestion table may have drifted (or these tests may have been failing on the branch before I started).

**Likely verdict for Cluster B:** **pre-existing, NOT caused by my changes.** Confirm by:
```bash
git stash    # or temporarily check out the commit before my changes
git checkout 66a3f560     # commit before my work began
./gradlew :agent:test --tests "*ToolSearchRelatedTest*"
```

If they fail there too, the failures pre-date my work. Hand back to the user with that confirmation.

## How to investigate efficiently

For each failure cluster, the question is:

1. **Read the test source.** What does it assert? What was the OLD behavior?
2. **Read the production code change in the matching commit.** What did the user change? Why?
3. **Decide: is the new behavior correct?** (Bug 4's research lives at `docs/research/vfs-staleness-after-shell-mutation.md` — read sections 5 (Layer C) and 6.4 to understand the design intent.)
4. **Update the test if the behavior change is intentional and correct.** Update production code only if you're certain the behavior is wrong AND the user agrees.

Useful commands:

```bash
# Re-run only the failing tests:
./gradlew :agent:test --tests "*JavaRuntimeExecToolTest*" --console=plain
./gradlew :agent:test --tests "*ToolSearchRelatedTest*" --console=plain

# See full failure detail:
cat agent/build/test-results/test/TEST-com.workflow.orchestrator.agent.tools.runtime.JavaRuntimeExecToolTest.xml | grep -A 30 "<failure"

# Check if a failure is pre-existing on the branch:
git stash
git checkout 66a3f560   # commit just before bug-fix series
./gradlew :agent:test --tests "*<failing-test>*"
git checkout feature/context-compaction
git stash pop
```

## Key files to read

- `core/src/main/kotlin/com/workflow/orchestrator/core/vfs/PostMutationRefresh.kt` — the new barrier
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/JavaRuntimeExecTool.kt:198-220` — where I inserted the barrier in `executeRunTests` (Bug 4 marker)
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/RuntimeExecTool.kt:642` — the lifted hard-error → wait pattern
- `agent/CLAUDE.md` (search for "ReadActionTestShim") — there's an existing pattern for bypassing platform service lookups in tests; the same trick may apply to `DumbService`.

## Checklist for handing back

Produce a per-test (or per-cluster) verdict:

- [ ] Cluster A — Cluster verdict: test-design (recommend) / production bug
  - [ ] Sub-verdict per test (most should be the same answer)
  - [ ] If test-design: write a `DumbService`-mock helper or update each test
  - [ ] If `RuntimeExecRunConfigTest` indexing-error message changed, update the assertion
- [ ] Cluster B — confirm pre-existing or new
  - [ ] If pre-existing: report back with the commit SHA where it first started failing
  - [ ] If new: investigate further

Aim to leave the branch with `:agent:test` passing — that unblocks the user pushing.

## What NOT to do

- Don't revert the new `waitForSmartModeOrTimeout` behavior — it's the correct fix for Bug 4 Layer C per the research doc, validated by the user.
- Don't push the branch yourself — the user will push after they review your test fixes.
- Don't amend any of the existing 10 bug-fix commits. Add new commits on top.
