# Agent — `get_build_problems` tool + Bamboo description disambiguation

**Status:** Plan, ready to execute
**Branch:** `refactor/cleanup-perf-caching`
**Owner:** Subhankar
**Date:** 2026-04-28

## Background

User reported that asking the agent "check the build logs for the error" after a failed Maven reload sent the agent to **Bamboo CI logs** instead of the local IDE Maven import errors. Two root causes:

1. **No tool exposes IDE build/import problems.** The plugin has reflective Maven access via `agent/tools/framework/MavenUtils.kt` and `agent/tools/runtime/BuildSystemValidator.kt`, but neither surfaces `MavenProjectsManager.problems` (the typed list of import failures — dependency resolution errors, missing parents, malformed POMs).
2. **Bamboo tools' descriptions match "build" too aggressively.** `BambooBuildsTool` description begins "Bamboo build lifecycle…" and `BambooPlansTool` description begins "Bamboo plan management…" — the LLM tool-selector ranks them top for any query containing "build" with no closer match available.

## Goals

- Give the agent a structured tool to read **local IDE build/import problems** (Maven + Gradle + compile).
- Disambiguate Bamboo tools' descriptions so they're never selected for local-IDE-build queries.
- Follow the plugin's `:agent depends ONLY on :core` rule: core interface → ToolResult<T> → feature impl → agent tool wrapper.

## Non-goals

- Surfacing arbitrary IDE log lines (`idea.log`) — different concern, separate plan.
- Capturing every historical build event (compile cascades, run-tests output) — V1 reads current snapshot only.
- Cross-project / aggregate views — single-project scope per call.

## Architecture

```
:core
  model/build/BuildProblem.kt        ← data class + 3 enums (BuildSource, ProblemType, Severity)
  services/BuildProblemsService.kt   ← interface
  services/impl/
    BuildProblemsServiceImpl.kt      ← @Service(PROJECT), reflective MavenProjectsManager probe

:agent
  tools/ide/BuildProblemsTool.kt     ← agent tool wrapper, schema + filter params
```

Reflective probe (no compile-time dependency on `org.jetbrains.idea.maven.project`) follows the existing pattern in `agent/tools/framework/MavenUtils.kt:22` and `agent/tools/integration/SonarTool.kt:668`.

**V1 surface** (Maven only):
- `MavenProjectsManager.getInstance(project).rootProjects` (reflective)
- For each root: `.problems` returns `List<MavenProjectProblem>`
- `MavenProjectProblem` exposes `description: String`, `path: String?`, `type: MavenProjectProblem.ProblemType` (enum: `SYNTAX`, `STRUCTURE`, `DEPENDENCY`, `PARENT`, `SETTINGS_OR_PROFILES`, `REPOSITORY`)
- Map to our `ProblemType` enum

**V1.1 (deferred):** Gradle import + compile-cascade event capture via `:core` listener service installing `ExternalSystemTaskNotificationListener` + `BuildProgressListener`. Out of scope for V1.

## Plan — 3 commits

### Commit 1 — F2: Tighten Bamboo tool descriptions

**Files:**
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BambooBuildsTool.kt`
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BambooPlansTool.kt`

**Change:** Prepend `REMOTE CI ONLY:` disambiguation paragraph at the top of each `description` string. Append `Do NOT use for local IDE Maven/Gradle reload errors — use get_build_problems for those.`

**Risk:** None — description is metadata for LLM tool selection. No behavior change.

**Test:** Existing tests pass unchanged. No new tests.

**Why first:** Routing fix lands instantly. Even before F1 ships, the LLM saying "no tool covers local IDE build errors" is strictly better than confidently grabbing Bamboo.

### Commit 2 — F1a: Core service + Maven impl

**New files:**
- `core/src/main/kotlin/com/workflow/orchestrator/core/model/build/BuildProblem.kt`
  ```kotlin
  data class BuildProblem(
      val source: BuildSource,
      val projectPath: String,
      val description: String,
      val type: ProblemType,
      val severity: Severity,
      val line: Int? = null,
      val artifactCoords: String? = null
  )
  enum class BuildSource { MAVEN_IMPORT, GRADLE_IMPORT, COMPILE }
  enum class ProblemType { DEPENDENCY, REPOSITORY, PARENT, STRUCTURE, COMPILE, OTHER }
  enum class Severity { ERROR, WARNING }
  ```

- `core/src/main/kotlin/com/workflow/orchestrator/core/services/BuildProblemsService.kt`
  ```kotlin
  interface BuildProblemsService {
      suspend fun getRecentBuildProblems(): ToolResult<List<BuildProblem>>
      companion object { fun getInstance(project: Project): BuildProblemsService = project.service() }
  }
  ```

- `core/src/main/kotlin/com/workflow/orchestrator/core/services/impl/BuildProblemsServiceImpl.kt`
  - `@Service(Service.Level.PROJECT)`
  - Reflective `MavenProjectsManager.getInstance(project).rootProjects`
  - For each root, reflectively read `.problems`
  - Map to `List<BuildProblem>` with `source = MAVEN_IMPORT`
  - V1: returns empty list for GRADLE_IMPORT and COMPILE sources (TODO marker for V1.1)
  - PSI/document reads wrapped in `readAction { }`
  - Returns `ToolResult.success(data, summary = "<count> Maven import problems")` or empty success

**Plugin XML:**
- `core/src/main/resources/META-INF/core.xml` (or wherever core declares services): add `<projectService serviceInterface="...services.BuildProblemsService" serviceImplementation="...services.impl.BuildProblemsServiceImpl"/>`

**Tests** (`core/src/test/kotlin/.../BuildProblemsServiceImplTest.kt`):
- Reflection-fake pattern (same as `BuildSystemValidatorTest`): inject a fake `MavenProjectsManager` via Class.forName overlay → returns canned `MavenProjectProblem` list
- Assert: empty input → empty output; multiple roots → combined list; type mapping (DEPENDENCY/REPOSITORY/PARENT/STRUCTURE/SYNTAX/SETTINGS_OR_PROFILES → our enum); severity always ERROR for V1
- `runTest` + `installReadActionInlineShim()` pattern

**Size estimate:** ~200 LOC src + ~120 LOC tests.

### Commit 3 — F1b: Agent tool wrapper

**New file:**
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/BuildProblemsTool.kt`
  - `name = "get_build_problems"`
  - `description` — explicit "local IDE only, NOT Bamboo CI" framing in primacy zone
  - Params: `source` (`maven`/`gradle`/`compile`/`all`, default `all`), `severity` (`error`/`warning`/`all`, default `all`)
  - Calls `BuildProblemsService.getInstance(project).getRecentBuildProblems()`
  - Filters in-memory by params, formats summary for LLM consumption

**Registration:**
- Add to `AgentService.registerAllTools()` core-tool registration block (alongside `find_definition`, `diagnostics`, etc.) — this is a useful-on-every-debug-task tool, justifies CORE tier.

**Tests** (`agent/src/test/kotlin/.../BuildProblemsToolTest.kt`):
- Fake `BuildProblemsService` returning canned problems
- Assert formatting (multi-problem summary, single-problem summary, empty case)
- Filter param behaviour
- `runTest` + read-action shim

**Size estimate:** ~100 LOC src + ~80 LOC tests.

## Test strategy

- Unit tests on the service (reflection-fake) and tool wrapper (service mock).
- No integration test that opens a real broken POM in `BasePlatformTestCase` for V1 — the reflective probe is the integration boundary; mocking it gives full coverage.
- Snapshot tests for system prompt: adding a new core tool causes 7 system-prompt snapshots in `agent/src/test/resources/prompt-snapshots/` to drift. Regenerate via `./gradlew :agent:test --tests "*generate all golden*"` and commit the diff alongside the tool.

## Risks

| Risk | Mitigation |
|---|---|
| `MavenProjectProblem` API differs across IntelliJ Platform builds | Reflective access already common in this codebase; wrap in try/catch returning empty list with hint |
| LLM ignores the new tool and still picks `bamboo_*` | F2 description tightening + tool ordering in `registerAllTools` |
| Snapshot test churn | Documented regeneration step in commit 3 description |

## Self-delete trigger

Delete this doc when:
- Commit 3 lands (V1 is complete), and
- A V1.1 follow-up plan (Gradle + compile event capture) is written elsewhere, or the V1.1 work is dropped.

## Cross-references

- `docs/architecture/auto-detection-improvements-handoff.md` — similar agent-tool-gap pattern.
- Agent CLAUDE.md → "Tool Registration Filter" — context for why we add to core not deferred.
- Memory `project_new_tools_plan.md` — broader "21 tools missing" plan; this fix adds tool #1 of 21.
