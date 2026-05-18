# Session Handoff — feedback.md follow-ups (2026-05-18)

**Status:** 3 of 7 feedback items shipped in v0.85.31-alpha by the previous session.
The remaining 4 items are described below for a fresh session to pick up. Each is
self-contained and can be tackled independently.

## Context

`tools/feedback.md` collected new LLM agent feedback after v0.85.30-alpha shipped.
Of the seven new issues:

| # | Issue | Status |
|---|-------|--------|
| 1 | `debug_inspect.get_variables` 120s timeout (regression) | **Shipped in v0.85.31** |
| 2 | `debug_inspect.evaluate` returns "Collecting data&" as type (regression) | **Shipped in v0.85.31** |
| 3 | `debug_step.step_over/step_out` "did not pause within 5s" misleading | **Shipped in v0.85.31** |
| 4 | Exception breakpoint validation warning is noisy | Open — pick up here |
| 5 | `project_structure.refresh_external_project` says "no Maven import" on Maven projects | Open — pick up here |
| 6 | `build.project_modules` throws "Read access is allowed from inside read-action only" | Open — pick up here |
| 7 | `java_runtime_exec.compile_module` sees stale Maven artifacts after CLI `mvn install` | Open — pick up here |

The pattern that worked well in v0.85.30 and v0.85.31:
1. Read the failing tool's source + write a tight reproducer test.
2. Make the smallest fix that passes the test.
3. Add a regression test to `agent/src/test/kotlin/.../DebugFeedbackFixesTest.kt`
   (or a sibling) so the bug can't return silently.
4. Run `./gradlew :agent:compileKotlin :agent:test --tests "PathValidatorTest" --tests "DebugFeedbackFixesTest" --tests "DbQueryTruncationMarkerTest" --tests "AskQuestionsTimeoutTest"`.

---

## #4 — Exception breakpoint validation warning noise

**Feedback verbatim:**
> The tool returned a note "No validation that 'com.example.acme.exception.AcmeServiceException'
> exists in the classpath" but the breakpoint worked correctly when the exception was thrown.
> The warning was confusing since the class clearly exists and the breakpoint functioned as expected.

**Where to look:**
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugBreakpointsTool.kt`
- Search for `"No validation"` or `classpath` in the file.

**Root cause hypothesis:**
The pre-flight emits a speculative "we can't verify this class exists" warning *unconditionally*
when an exception breakpoint is added. The warning was added defensively (so the LLM doesn't
assume a typo'd FQN would silently fail) but it fires even when the class definitely exists
— and crucially, IntelliJ already attaches the breakpoint successfully regardless.

**Proposed fix:**
Either:
- (a) Drop the warning entirely. The IntelliJ XDebugger reports its own "exception class not
  found" diagnostic when the breakpoint actually fails to attach, which is more authoritative.
- (b) Only emit the warning when we have positive evidence the class is missing
  (e.g., a `JavaPsiFacade.findClass(fqn, GlobalSearchScope.allScope(project))` returns null).
  PSI is fast enough at session-start time that this is cheap to check.

Prefer (a) unless the user pushes back — option (b) adds a PSI dependency for very little
extra signal.

**Acceptance criteria:**
- An exception breakpoint added with a valid FQN returns success with NO speculative warning.
- An exception breakpoint added with a typo'd FQN still surfaces IntelliJ's own diagnostic
  (or, under option b, a clear "class not found" error).

---

## #5 — `refresh_external_project` doesn't detect Maven projects

**Feedback verbatim:**
> project_structure(action="refresh_external_project") - This tool consistently reported
> "No external project roots are linked (no Gradle or Maven import). Nothing to refresh."
> even though the project clearly has a root pom.xml with Maven modules.

**Where to look:**
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/project/ProjectStructureTool.kt`
- Find `executeRefreshExternalProject` (referenced at line ~160).

**Root cause hypothesis:**
The current detection probably calls `ExternalSystemApiUtil.getSettings(project, MAVEN_SYSTEM_ID)`
and checks if any linked project settings are present. On a project that **has** a `pom.xml`
but was opened without the Maven import being completed (or after a fresh checkout where IntelliJ
hasn't been told "this is a Maven project yet"), this returns empty.

The intuitive contract for the LLM is: "if there's a `pom.xml`, treat it as Maven and trigger
a fresh import." That's exactly what `MavenProjectsManager.getInstance(project).addManagedFilesOrUnignore(...)`
does — it both registers the POM AND triggers the resolve.

**Proposed fix:**
1. Detect Maven via the priority chain:
   - `MavenProjectsManager.getInstance(project).hasProjects()` — already-imported case.
   - Walk `project.basePath` for `pom.xml` files (capped at top 2 levels) — not-yet-imported case.
   - Same for Gradle: `GradleSettings.getInstance(project).linkedProjectsSettings.isNotEmpty()`
     OR `build.gradle` / `build.gradle.kts` / `settings.gradle*` at project root.
2. When `pom.xml` is found but not yet imported, call
   `MavenProjectsManager.getInstance(project).addManagedFilesOrUnignore(listOf(pomVirtualFile))`
   which both registers AND triggers resolve. Then wait briefly (~30s) for
   `isImportingInProgress` to flip false, return success.
3. Update the docstring to say "auto-detects Maven/Gradle from project files; triggers
   addManagedFilesOrUnignore if not yet imported."

**API references:**
- `org.jetbrains.idea.maven.project.MavenProjectsManager` — `:agent` already has this via the
  bundled Maven plugin dependency (`platformBundledPlugins` includes `org.jetbrains.idea.maven`).
- `org.jetbrains.plugins.gradle.settings.GradleSettings` — confirm dependency, may need a
  reflection-based call if Gradle isn't bundled.

**Acceptance criteria:**
- On a fresh-clone Maven project (pom.xml present, never imported), this action triggers
  the import and returns success.
- On a Gradle project, the same flow works.
- On a project with NEITHER pom.xml NOR build.gradle, returns the existing
  "Nothing to refresh" message.

---

## #6 — `build.project_modules` threading crash

**Feedback verbatim:**
> build(action="project_modules") - Returned a threading error:
> "Read access is allowed from inside read-action only". This seems like an internal IDE
> threading issue that prevented the tool from working.

**Where to look:**
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/BuildTool.kt`
- Search for `project_modules` or `module_dependency_graph` (both list modules).
- Likely calling `ModuleManager.getInstance(project).modules` or
  `OrderEnumerator.orderEntries(module)` outside of a read action.

**Root cause hypothesis:**
Standard IntelliJ rule: any access to module / library / SDK / dependency state requires
either a read action or the EDT. Most agent tools already wrap their PSI/model access with
`readAction { ... }` (the suspend-coroutine friendly form, per `:agent` module conventions).
The `project_modules` and `module_dependency_graph` paths are likely the lone exceptions.

**Proposed fix:**
Wrap the offending body in `com.intellij.openapi.application.readAction { ... }` (suspend form).
Per the project's threading conventions documented in `core/CLAUDE.md`:
> Read actions. `ReadAction.compute / ReadAction.run / runReadAction` are deprecated for
> 2026.1. From suspend code use `readAction { }` (writes-may-cancel) or `smartReadAction(project) { }`
> (waits for indexing) or `readActionBlocking { }` (write-priority blocking).

Prefer `readAction { }` here — module enumeration doesn't need indexing to be ready.

**Test:**
Existing tests in `agent/src/test/kotlin/.../tools/framework/` probably already exercise this
path with `installReadActionInlineShim()` masking the issue — the production call site is
what needs the fix.

**Acceptance criteria:**
- `build(action="project_modules")` on a real Java/Gradle/Maven project returns a list of
  modules without throwing.

---

## #7 — `compile_module` doesn't see Maven CLI changes

**Feedback verbatim:**
> After running Maven install from command line successfully, IntelliJ's compile still showed
> stale library versions (acme-common-core:1.83.0 and acme-sp-interface:1.64.0 instead of the
> new SNAPSHOT versions). The IDE didn't pick up the newly installed Maven artifacts even
> after Maven command line build succeeded.

**Where to look:**
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/RuntimeExecTool.kt` —
  the `java_runtime_exec(action=compile_module)` path. Look for `CompilerManager.compile` call.

**Root cause:**
This is a genuine IntelliJ project-model gap, not a bug in our tool. When `mvn install` writes
new artifacts to `~/.m2/repository/`, IntelliJ's in-memory project model is NOT automatically
refreshed — it keeps the previously-resolved JAR paths and versions cached. A subsequent
`CompilerManager.compile()` recompiles against the stale classpath.

The native workaround in IntelliJ is the "Reload Maven Projects" button (which is exactly
what fix #5 above will programmatically expose via `refresh_external_project`).

**Proposed fix:**
Add an optional `refresh_maven_first: boolean` parameter (default `false` to preserve
existing behavior) to the `compile_module` action. When `true`:
1. Detect Maven via the same chain as #5.
2. Call `MavenProjectsManager.scheduleImportAndResolve()` and `awaitImport()` to refresh
   classpaths against `~/.m2`.
3. Then proceed with `CompilerManager.compile`.

Document the flag heavily — the LLM should be encouraged to set it whenever it has just
finished a `run_command mvn install`. Consider adding the flag-suggestion to the post-mvn-install
heuristics in `RunCommandTool`'s system-prompt hints if one exists.

**Alternative (more aggressive):**
Always run a Maven reimport before `compile_module`. Risk: slow on large projects, may
disrupt user-initiated incremental builds. Probably not the right default — make it opt-in.

**Acceptance criteria:**
- `compile_module(refresh_maven_first=true)` after `run_command mvn install` picks up the
  newly-installed SNAPSHOT.
- The default behavior (no flag) is unchanged.

**Depends on:** #5 ideally landing first, so the shared Maven-reimport helper exists.

---

## Suggested order

1. **#6 first** — it's a crash, smallest fix, no dependencies. Probably 30 minutes.
2. **#4 second** — drop a defensive warning. Trivial. ~15 minutes.
3. **#5 third** — Maven import bridge. Probably 2-3 hours including testing.
4. **#7 last** — depends on #5's helper. ~1 hour after #5 is in.

Bundle as a single PR + release as `v0.85.32-alpha`.

## Verification before releasing

Per the v0.85.30 / v0.85.31 release pattern:
```bash
./gradlew :agent:test --tests "DebugFeedbackFixesTest" --tests "PathValidatorTest" \
                      --tests "DbQueryTruncationMarkerTest" --tests "AskQuestionsTimeoutTest"
./gradlew clean buildPlugin verifyPlugin
git push origin bugfix
gh release delete v0.85.32-alpha --yes --cleanup-tag  # if you had to retry
gh release create v0.85.32-alpha --target bugfix --title "v0.85.32-alpha" \
   --notes "..." build/distributions/intellij-workflow-orchestrator-0.85.32-alpha.zip
```

## Files most likely to need edits

| File | Likely change for which item |
|---|---|
| `agent/src/main/kotlin/.../tools/debug/DebugBreakpointsTool.kt` | #4 (drop warning) |
| `agent/src/main/kotlin/.../tools/project/ProjectStructureTool.kt` | #5 (Maven detection + import) |
| `agent/src/main/kotlin/.../tools/framework/BuildTool.kt` | #6 (readAction wrap) |
| `agent/src/main/kotlin/.../tools/runtime/RuntimeExecTool.kt` | #7 (refresh_maven_first param) |

## What v0.85.31-alpha already shipped

- `AgentDebugController.computeChildren` — 90s cumulative wall budget with `WalkBudget`
  data class. Trips when deadline hits; `getVariables` appends a `<budget>` sentinel.
- `AgentDebugController.resolvePresentation` — placeholder check now gates on BOTH `type`
  and `value` slots; timeout fallback emits an explicit "value not ready" message instead
  of leaking the "Collecting data…" string.
- `DebugStepUtils.executeStep` — on `waitForPause` timeout, re-check `session.isSuspended`
  + `currentPosition` and emit specific "actually paused at X" vs "still running" messages
  (matches the `run_to_cursor` pattern from v0.85.30).
- Tests extended in `DebugFeedbackFixesTest.kt`.

If anything in v0.85.31 looks suspicious or doesn't fully solve the regression in production,
the failing case + a tiny reproducer test is worth more than a wholesale revert. The fix
shape (placeholder gating on both slots + budget on cumulative walks) is the right model;
edge cases may need additional placeholder patterns added to `isPlaceholderValue`.
