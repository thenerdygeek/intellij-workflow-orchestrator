# Phase 0b-3 — AgentTool Safety Props + Thin ToolRegistrationService — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the hardcoded `WRITE_TOOLS` / `HOOK_EXEMPT` / approval tool-name sets in `AgentLoop`/`ApprovalPolicy` onto self-declared `AgentTool` properties so a depending plugin (B) can contribute a *safe write tool* through the existing `agentToolContributor` EP, and extract the contributor-EP iteration into a thin, project-scoped `ToolRegistrationService` (with per-contributor isolation). **Behavior-unchanged for all of A's own tools.**

**Architecture:** Inversion of control. Today `AgentLoop`/`ApprovalPolicy` hold *centralized* name-sets that classify tool behavior (write-tool, hook-exempt, approval-required). Those are closed sets B cannot extend. We move each fact onto a `get()`-default property on the `AgentTool` interface (`isMutating` already exists from Phase 0a; add `isHookExempt`, `requiresApproval`, `allowSessionApproval`), switch every consumer to read the property off the in-scope tool instance (or a registry-derived set), then delete the name-sets. Separately, the ~17-line contributor-EP loop buried in `AgentService.registerAllTools()` moves into a project-scoped `ToolRegistrationService` that runs each contributor under its OWN `runCatching` (Phase 0a deferred this per-contributor isolation) and returns diagnostics; `AgentService` delegates to it. A's `ToolRegistry` (57 references) **stays in `AgentService`** — no hot-path surgery.

**Tech Stack:** Kotlin 2.1.10, IntelliJ Platform Plugin v2, JUnit 5 + MockK, `@Service(Service.Level.PROJECT)`.

## Global Constraints

- **Behavior-unchanged for A.** Every change is either additive (new default-`false` properties) or a behavior-preserving swap (read a property whose value equals the old set-membership). The set of tools where `isMutating==true` is **exactly** `WRITE_TOOLS`; where `requiresApproval==true` is **exactly** `APPROVAL_TOOLS`; where `isHookExempt==true` is **exactly** `HOOK_EXEMPT`. Each task proves this with a characterization test BEFORE deleting the corresponding set.
- **`:agent` ONE-`BasePlatformTestCase` invariant.** The `:agent` test JVM already has exactly one `BasePlatformTestCase` (`EditFilePersistenceFixtureTest`). A SECOND one → deterministic headless "Indexing timeout" (#51). ALL new tests in this plan are pure JUnit 5 or source-text contract tests. NEVER add a `BasePlatformTestCase`.
- **`AgentService` is not unit-instantiable** (heavy platform `init`). Structural invariants on it are pinned with **source-text contract tests** (read the `.kt` as a string, `assertTrue(src.contains(...))`) — the established pattern (`AgentServiceActiveTaskMutexTest`, `RunInvocationLeakTest`).
- **`@Service` default-ctor-param trap.** A `@Service` whose constructor has a Kotlin default-valued parameter crashes startup unless `@JvmOverloads` is used. `ToolRegistrationService`'s ctor is `(project: Project)` only — NO default params, so NO `@JvmOverloads` needed. Do not add an injectable-with-default dependency to it; keep testable logic in the pure `ToolContributionRunner`.
- **`--no-build-cache --rerun-tasks`** is required only for commits changing a lambda/fn type to/from `suspend` or adding a ctor param. This plan adds NO suspend changes and NO ctor params, so plain `./gradlew :agent:test` is fine — EXCEPT run the final full-suite gate with `--rerun-tasks` to be safe.
- **No `runBlocking` in `main/`** (pre-commit hook; use `runBlockingCancellable`). Not expected to arise here.
- **detekt: AUTOCORRECT, do NOT baseline.** `./gradlew :agent:detekt --auto-correct`. Adding/removing imports can unmask a pre-existing `ImportOrdering` baseline entry — fix it genuinely (reorder), don't baseline. CI `check` runs detekt; tests/`verifyPlugin` don't.
- **Webview build noise:** never `git add agent/src/main/resources/webview/dist/`; `git checkout -- agent/src/main/resources/webview/dist/` before committing if `verifyPlugin`/`buildPlugin` regenerated it.
- **EP/seam interfaces B implements are `public` + `@InternalApi` (NEVER `internal`).** Note: `ToolRegistrationService` is A-internal infrastructure consumed by `AgentService` (same module) — B contributes via the existing `AgentToolContributor` EP, NOT via this service — so `ToolRegistrationService` is a plain (non-`@InternalApi`) project service. It is NOT part of the B-facing API surface.

## Naming note (read before Task 1)

`AgentTool` already declares `suspend fun requestApproval(...)` (a *method*) — the per-action approval-routing hook that meta-tools (`project_structure` actions, `run_maven_goal`, `background_process` kill/stdin/attach) call internally; `AgentLoop`'s `ApprovalGatedTool` wrapper overrides it to route through the loop's gate. This plan adds a *property* `val requiresApproval: Boolean` (the loop-level "does this tool trip the approval gate" flag). **These are two different mechanisms and both stay.** The KDoc on the new property MUST disambiguate them. Do not touch `requestApproval()` (the method) or `ApprovalGatedTool`. (The name `requiresApproval` is kept deliberately — it matches the existing `ApprovalPolicy.requiresApproval` field so `forTool(tool) = ApprovalPolicy(tool.requiresApproval, …)` reads cleanly; the homograph with the method is mitigated by the verb-form difference + KDoc.)

## Revision log

**Rev 2 (post-review).** Three independent opus review rounds (bytecode-accuracy · completeness · skeptic) ran against rev 1. All three **independently confirmed the load-bearing behavior-preservation math is EXACTLY correct**: `{isMutating==true}` == `WRITE_TOOLS` (10 tools), `{requiresApproval==true}` == `APPROVAL_TOOLS` (incl. `delete_file`), `{isHookExempt==true}` == `HOOK_EXEMPT`. Folded-in fixes:
- **(blocker) Task 8 test:** `AgentToolContributor` is a plain `interface`, NOT `fun interface` → SAM-lambda stubs don't compile. Rewrote as anonymous objects.
- **(blocker) impersonating test stubs:** 5 behavioral tests build the real loop with stubs NAMED after approval/write tools but lacking the new props → the gate/plan-block would silently no-op. Added explicit stub-update steps (Task 4 Step 4b: AutoApprove/MemoryApproval/Repromping; Task 6a Step 3b: PlanModeLoop/PlanModeWriteActionGuard) + preflight greps.
- **(blocker) missed `WRITE_TOOLS` test consumers:** `AgentLoopTest.kt:455` + `AgentServiceToolFilterTest` completeness test would compile-break / become tautological → delete both (coverage re-locked by `SafetyPropsCharacterizationTest`).
- **(blocker) brittle source-text asserts:** the `AgentLoop.kt:1937` *comment* contains "WRITE_TOOLS" → reworded it (Task 6a) AND tightened the asserts to `val WRITE_TOOLS`/`in WRITE_TOOLS` (and `val/in HOOK_EXEMPT`, `.EP_NAME.extensionList`).
- **(trap) Task 2 anchor:** real decl is `override val isMutating: Boolean get() = true` (not `= true`) — fixed the anchor instruction.
- **(coverage) added `ContributedWriteToolClassificationTest`** — pure end-to-end proof a contributed mutating+approval tool is write-classified + gated (the loop the feature exists for).
- **(parity) `ContributionDiagnostics.contributorClasses`** added so the success log keeps the old contributor-class detail.
- **(governance) `isHookExempt` KDoc** now documents the trust boundary (a contributed tool setting it escapes user hooks; B is trusted-by-design; spec §5 mandates the property).
- **(doc-debt) stale `WRITE_TOOLS`/`HOOK_EXEMPT`/`APPROVAL_TOOLS` prose** (~25 refs incl. `AgentTool.kt` isWriteAction KDoc) acknowledged + the visible ones reworded; the rest is non-blocking, swept in Task 10.

**Skeptic's two design challenges, resolved (not folded):** (1) thin `ToolRegistrationService` vs in-place forEach — KEPT the service (the user explicitly chose "thin host" over "defer"; it gives a named project-scoped seam matching spec §8.2 language). (2) `requiresApproval` rename — KEPT the name (matches `ApprovalPolicy` field; see Naming note).

---

## File Structure

**Modified (production):**
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/AgentTool.kt` — add 3 default properties (Task 1).
- `agent/.../tools/builtin/{EditFileTool,CreateFileTool,DeleteFileTool,RevertFileTool,RunCommandTool}.kt` — override approval props (Task 2).
- `agent/.../tools/builtin/{TaskCreateTool,TaskUpdateTool,TaskListTool,TaskGetTool,AiReviewTool}.kt` — override `isHookExempt` (Task 3).
- `agent/.../loop/ApprovalPolicy.kt` — `forTool(name)` → `forTool(tool)`; delete name-sets (Task 4).
- `agent/.../loop/AgentLoop.kt` — approval gate (Task 4); hook gates + delete `HOOK_EXEMPT` (Task 5); write-tool sites + delete `WRITE_TOOLS` (Tasks 6a/6b).
- `agent/.../tools/docs/ToolDocPayloadBuilder.kt` — read props instead of sets (Tasks 4, 6b).
- `agent/.../AgentService.kt` — `writeToolNames` derive from registry (Task 6b); delegate contributor EP to `ToolRegistrationService` (Task 9).
- `agent/.../tools/builtin/SpawnAgentTool.kt` — `inferPlanMode` via `isMutating` (Task 6b).

**Created (production):**
- `agent/.../tools/contribution/ToolContributionRunner.kt` — pure per-contributor-isolated EP runner + `ContributionDiagnostics` (Task 8).
- `agent/.../tools/contribution/ToolRegistrationService.kt` — `@Service(PROJECT)` thin host (Task 9).

**Created (tests):**
- `agent/src/test/kotlin/.../tools/AgentToolSafetyPropsDefaultTest.kt` (Task 1)
- `.../tools/SafetyPropsCharacterizationTest.kt` (Tasks 2, 3 — the behavior-preservation lock)
- `.../loop/AgentLoopHookExemptionContractTest.kt` (Task 5, source-text)
- `.../loop/AgentLoopWriteToolPropContractTest.kt` (Task 6a/6b, source-text)
- `.../AgentServiceWriteToolDerivationContractTest.kt` (Task 6b, source-text)
- `.../tools/contribution/ToolContributionRunnerTest.kt` (Task 8, pure)
- `.../AgentServiceToolRegistrationWiringContractTest.kt` (Task 9, source-text)

**Modified (tests):**
- `.../loop/ApprovalPolicyTest.kt` (Task 4 — rewrite to `forTool(tool)`)
- `.../tools/ToolDefinitionFilterTest.kt`, `.../loop/AgentServiceToolFilterTest.kt`, `.../delegation/DelegatedActOnlyToolFilterTest.kt` (Task 6b — replace `AgentLoop.WRITE_TOOLS` with an explicit literal set)
- `.../walkthrough/WalkthroughRegistrationContractTest.kt` (Task 6b — the "not in WRITE_TOOLS" assertion must change to "isMutating == false")

---

## PART 1 — AgentTool Safety Properties

### Task 1: Add the three safety properties to the `AgentTool` interface

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/AgentTool.kt` (around the existing `isMutating` at line 117)
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/AgentToolSafetyPropsDefaultTest.kt`

**Interfaces:**
- Produces: `AgentTool.isHookExempt: Boolean` (default `false`), `AgentTool.requiresApproval: Boolean` (default `false`), `AgentTool.allowSessionApproval: Boolean` (default `false`). Existing `AgentTool.isMutating: Boolean` (default `false`) is unchanged and reused.

- [ ] **Step 1: Write the failing test**

```kotlin
// agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/AgentToolSafetyPropsDefaultTest.kt
package com.workflow.orchestrator.agent.tools

import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import com.workflow.orchestrator.core.ai.dto.FunctionParameters

/**
 * A bare AgentTool that overrides only the required members inherits safe defaults
 * for every safety property — so a B-contributed tool that declares none of them is
 * treated as a non-mutating, hook-observed, no-approval-needed read tool.
 */
class AgentToolSafetyPropsDefaultTest {
    private val bareTool = object : AgentTool {
        override val name = "bare"
        override val description = "bare"
        override val parameters = FunctionParameters(properties = emptyMap())
        override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR)
        override suspend fun execute(params: JsonObject, project: Project) =
            ToolResult(content = "ok", summary = "ok", tokenEstimate = 1)
    }

    @Test
    fun `defaults are all false`() {
        assertFalse(bareTool.isMutating, "isMutating default")
        assertFalse(bareTool.isHookExempt, "isHookExempt default")
        assertFalse(bareTool.requiresApproval, "requiresApproval default")
        assertFalse(bareTool.allowSessionApproval, "allowSessionApproval default")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "*AgentToolSafetyPropsDefaultTest*"`
Expected: COMPILE FAILURE — `isHookExempt`/`requiresApproval`/`allowSessionApproval` unresolved.

- [ ] **Step 3: Add the three properties to `AgentTool.kt`**

Immediately after the existing `isMutating` declaration (line 117), add:

```kotlin
    /**
     * True if this tool's PreToolUse/PostToolUse hooks should be SKIPPED. Internal
     * bookkeeping tools (task management, local-only staging) declare this so they are
     * not observable by — and cannot be blocked by — user hooks. Replaces the old
     * `AgentLoop.HOOK_EXEMPT` name set so a depending plugin can self-declare exemption.
     *
     * ⚠ TRUST BOUNDARY: a tool that sets this to true escapes ALL user governance hooks
     * (a user PreToolUse hook can neither observe nor block it). A depending plugin (B) is
     * trusted-by-design (it `<depends>` on A and runs in-process), so a B tool *may* set
     * this — but only trusted depending plugins should be installed. This is a deliberate,
     * documented decision (spec §5 mandates HOOK_EXEMPT move onto a self-declared property);
     * a future EP-validation layer could restrict it to A-owned tools if the trust model tightens.
     */
    val isHookExempt: Boolean get() = false

    /**
     * True if this tool must pass the loop-level approval gate before executing (the user
     * sees an approval card). Replaces `ApprovalPolicy`'s hardcoded name sets so a depending
     * plugin can contribute an approval-gated write tool.
     *
     * NOTE: This is distinct from [requestApproval] (the suspend method). [requiresApproval]
     * is the loop-level gate trigger consulted in `AgentLoop` BEFORE execution; [requestApproval]
     * is the per-action hook a meta-tool calls DURING execution to route an individual mutating
     * action through the same gate. A tool may use either, both, or neither.
     */
    val requiresApproval: Boolean get() = false

    /**
     * Only meaningful when [requiresApproval] is true. If true, the approval card offers
     * "Allow for the session" (the user can grant once); if false, every invocation must be
     * approved individually (e.g. `run_command`, where each command is arbitrarily different).
     */
    val allowSessionApproval: Boolean get() = false
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :agent:test --tests "*AgentToolSafetyPropsDefaultTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/AgentTool.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/AgentToolSafetyPropsDefaultTest.kt
git commit -m "feat(agent): add isHookExempt/requiresApproval/allowSessionApproval to AgentTool (0b-3, additive)"
```

---

### Task 2: Declare approval props on the five approval tools

**Files:**
- Modify: `EditFileTool.kt`, `CreateFileTool.kt`, `DeleteFileTool.kt`, `RevertFileTool.kt` (all have no-arg ctors), `RunCommandTool.kt` (has a ctor — source-text only).
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/SafetyPropsCharacterizationTest.kt` (new — approval section)

**Interfaces:**
- Consumes: `AgentTool.requiresApproval`, `AgentTool.allowSessionApproval` (Task 1).
- Produces: per-tool values — `edit_file`/`create_file`/`delete_file`/`revert_file` → `requiresApproval=true, allowSessionApproval=true`; `run_command` → `requiresApproval=true, allowSessionApproval=false`.

- [ ] **Step 1: Write the failing characterization test (approval section)**

```kotlin
// agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/SafetyPropsCharacterizationTest.kt
package com.workflow.orchestrator.agent.tools

import com.workflow.orchestrator.agent.tools.builtin.CreateFileTool
import com.workflow.orchestrator.agent.tools.builtin.DeleteFileTool
import com.workflow.orchestrator.agent.tools.builtin.EditFileTool
import com.workflow.orchestrator.agent.tools.builtin.RevertFileTool
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Behavior-preservation lock for the safety-prop migration. The property values declared on
 * the concrete tools must reproduce EXACTLY the old hardcoded name sets in ApprovalPolicy /
 * AgentLoop. No-arg tools are instantiated directly; tools with heavy ctors are pinned by
 * source-text. Together these prove deleting the sets changes no behavior.
 */
class SafetyPropsCharacterizationTest {

    // ── Approval: requiresApproval + allowSessionApproval ──────────────────
    @Test
    fun `session-approvable file tools require approval and allow session`() {
        for (tool in listOf(EditFileTool(), CreateFileTool(), DeleteFileTool(), RevertFileTool())) {
            assertTrue(tool.requiresApproval, "${tool.name} requiresApproval")
            assertTrue(tool.allowSessionApproval, "${tool.name} allowSessionApproval")
        }
    }

    private fun src(rel: String) = File("src/main/kotlin/com/workflow/orchestrator/agent/$rel").readText()

    @Test
    fun `run_command requires per-invocation approval`() {
        val s = src("tools/builtin/RunCommandTool.kt")
        assertTrue(s.contains("override val requiresApproval = true"),
            "RunCommandTool must declare requiresApproval = true")
        assertTrue(s.contains("override val allowSessionApproval = false"),
            "RunCommandTool must declare allowSessionApproval = false (per-invocation only)")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "*SafetyPropsCharacterizationTest*"`
Expected: FAIL — `requiresApproval` is `false` (default) on the file tools; source-text assert fails for `RunCommandTool`.

- [ ] **Step 3: Add the overrides**

⚠ ANCHOR: the existing declaration is `override val isMutating: Boolean get() = true` (a `get()` accessor — the string `override val isMutating = true` does NOT exist). Locate it with `grep -n isMutating <file>` and add the new lines beside it. Write the new overrides EXACTLY as shown below (initializer form, not `get() =`) — the Task-2 source-text characterization asserts `override val requiresApproval = true` verbatim for `RunCommandTool`.

In each of `EditFileTool.kt`, `CreateFileTool.kt`, `DeleteFileTool.kt`, `RevertFileTool.kt`, add:

```kotlin
    override val requiresApproval = true
    override val allowSessionApproval = true
```

In `RunCommandTool.kt` (beside `override val isMutating: Boolean get() = true`, ~line 45), add:

```kotlin
    override val requiresApproval = true
    override val allowSessionApproval = false
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :agent:test --tests "*SafetyPropsCharacterizationTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/EditFileTool.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/CreateFileTool.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/DeleteFileTool.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RevertFileTool.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RunCommandTool.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/SafetyPropsCharacterizationTest.kt
git commit -m "feat(agent): declare requiresApproval/allowSessionApproval on the 5 approval tools (0b-3, additive)"
```

---

### Task 3: Declare `isHookExempt` on the hook-exempt tools

**Files:**
- Modify: `TaskCreateTool.kt`, `TaskUpdateTool.kt`, `TaskListTool.kt`, `TaskGetTool.kt`, `AiReviewTool.kt` (all have ctors — source-text characterization).
- Test: extend `SafetyPropsCharacterizationTest.kt` (hook section).

**Interfaces:**
- Consumes: `AgentTool.isHookExempt` (Task 1).
- Produces: `task_create`/`task_update`/`task_list`/`task_get`/`ai_review` → `isHookExempt=true`.

- [ ] **Step 1: Add the failing hook-section test to `SafetyPropsCharacterizationTest.kt`**

```kotlin
    // ── Hook exemption ─────────────────────────────────────────────────────
    @Test
    fun `task tools and ai_review declare hook exemption`() {
        for (rel in listOf(
            "tools/builtin/TaskCreateTool.kt",
            "tools/builtin/TaskUpdateTool.kt",
            "tools/builtin/TaskListTool.kt",
            "tools/builtin/TaskGetTool.kt",
            "tools/builtin/AiReviewTool.kt",
        )) {
            assertTrue(src(rel).contains("override val isHookExempt = true"),
                "$rel must declare isHookExempt = true")
        }
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "*SafetyPropsCharacterizationTest*"`
Expected: FAIL — none declare `isHookExempt` yet.

- [ ] **Step 3: Add the overrides**

In each of `TaskCreateTool.kt`, `TaskUpdateTool.kt`, `TaskListTool.kt`, `TaskGetTool.kt`, `AiReviewTool.kt` — next to the `override val name = ...` line, add:

```kotlin
    override val isHookExempt = true
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :agent:test --tests "*SafetyPropsCharacterizationTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/TaskCreateTool.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/TaskUpdateTool.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/TaskListTool.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/TaskGetTool.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/AiReviewTool.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/SafetyPropsCharacterizationTest.kt
git commit -m "feat(agent): declare isHookExempt on task tools + ai_review (0b-3, additive)"
```

---

### Task 4: Switch the approval gate to read tool properties; delete the `ApprovalPolicy` name sets

**Files:**
- Modify: `agent/.../loop/ApprovalPolicy.kt` — `forTool(toolName: String)` → `forTool(tool: AgentTool)`; delete `ALWAYS_PER_INVOCATION`, `SESSION_APPROVABLE`, `APPROVAL_TOOLS`.
- Modify: `agent/.../loop/AgentLoop.kt` — call site at ~line 2000 `ApprovalPolicy.forTool(toolName)` → `ApprovalPolicy.forTool(tool)`; remove the `val APPROVAL_TOOLS = ApprovalPolicy.APPROVAL_TOOLS` alias (~line 781) and any now-broken KDoc reference.
- Modify: `agent/.../tools/docs/ToolDocPayloadBuilder.kt` — `describeApprovalPolicy(toolName)` → `describeApprovalPolicy(tool)`; call `ApprovalPolicy.forTool(tool)`.
- Modify (tests): `agent/.../loop/ApprovalPolicyTest.kt` — rewrite to construct tool stubs.
- Modify (doc): `agent/.../tools/docs/ToolDocumentation.kt:115` and `RuntimeConfigTool.kt:194` — update stale prose mentioning `APPROVAL_TOOLS` (comment-only; no logic).

**Interfaces:**
- Consumes: `AgentTool.requiresApproval`, `AgentTool.allowSessionApproval` (Task 2).
- Produces: `ApprovalPolicy.forTool(tool: AgentTool): ApprovalPolicy` (replaces the name overload). `ApprovalPolicy(requiresApproval, allowSessionApproval)` data-class ctor is UNCHANGED (the memory-write special case keeps constructing it directly).

**Behavior-preservation check:** the loop's special cases stay byte-identical: the `run_command` auto-approval bypass (`CommandApprovalDecision.evaluate`), and the `MemoryWriteClassifier`-forced `ApprovalPolicy(true, false)`. ONLY the base-policy *derivation* changes from name-lookup to property-read. Because Task 2 set the props to match the old sets exactly, the gate decision is identical for every A tool.

- [ ] **Step 1: Rewrite `ApprovalPolicyTest.kt` to drive `forTool(tool)` (failing)**

```kotlin
// agent/src/test/kotlin/com/workflow/orchestrator/agent/loop/ApprovalPolicyTest.kt
package com.workflow.orchestrator.agent.loop

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.core.ai.dto.FunctionParameters
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApprovalPolicyTest {
    private fun tool(
        n: String, requires: Boolean = false, session: Boolean = false,
    ) = object : AgentTool {
        override val name = n
        override val description = n
        override val parameters = FunctionParameters(properties = emptyMap())
        override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR)
        override val requiresApproval = requires
        override val allowSessionApproval = session
        override suspend fun execute(params: JsonObject, project: Project) =
            ToolResult(content = "", summary = "", tokenEstimate = 0)
    }

    @Test
    fun `run_command-style tool is per-invocation`() {
        val p = ApprovalPolicy.forTool(tool("run_command", requires = true, session = false))
        assertTrue(p.requiresApproval); assertFalse(p.allowSessionApproval)
    }

    @Test
    fun `edit_file-style tool allows session`() {
        val p = ApprovalPolicy.forTool(tool("edit_file", requires = true, session = true))
        assertTrue(p.requiresApproval); assertTrue(p.allowSessionApproval)
    }

    @Test
    fun `read-only tool needs no approval`() {
        val p = ApprovalPolicy.forTool(tool("read_file"))
        assertFalse(p.requiresApproval); assertFalse(p.allowSessionApproval)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "*ApprovalPolicyTest*"`
Expected: COMPILE FAILURE — `forTool` still takes a `String`.

- [ ] **Step 3: Rewrite `ApprovalPolicy.kt`**

```kotlin
package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.agent.tools.AgentTool

/**
 * Per-tool approval policy, derived from the tool's self-declared safety properties.
 *
 * `requiresApproval` trips the loop-level approval gate; `allowSessionApproval` decides whether
 * the user may "Allow for the session". (`run_command` declares `allowSessionApproval = false`
 * because each command is arbitrarily different — approving one `ls` must not auto-approve
 * `rm -rf /`.) The hardcoded ALWAYS_PER_INVOCATION / SESSION_APPROVABLE name sets were removed
 * in Phase 0b-3 — a tool now carries its own policy, so a depending plugin can contribute an
 * approval-gated write tool.
 */
data class ApprovalPolicy(
    val requiresApproval: Boolean,
    val allowSessionApproval: Boolean,
) {
    companion object {
        fun forTool(tool: AgentTool): ApprovalPolicy =
            ApprovalPolicy(
                requiresApproval = tool.requiresApproval,
                allowSessionApproval = tool.allowSessionApproval,
            )
    }
}
```

- [ ] **Step 4: Update the call sites**

In `AgentLoop.kt`:
- At the approval gate (~line 2000), change `ApprovalPolicy.forTool(toolName)` → `ApprovalPolicy.forTool(tool)`. (`tool` is the in-scope resolved `AgentTool` already used at the plan-mode guard ~line 1943. If `tool` is the `ApprovalGatedTool` wrapper, Kotlin interface delegation forwards `requiresApproval`/`allowSessionApproval` to the real tool — correct.)
- Remove the alias line `val APPROVAL_TOOLS = ApprovalPolicy.APPROVAL_TOOLS` (~line 781) and adjust the surrounding KDoc that referenced it (the comment block at ~lines 73-78 about `ApprovalPolicy.APPROVAL_TOOLS` — reword to "self-declared `requiresApproval`").

In `ToolDocPayloadBuilder.kt`:
- Change `private fun describeApprovalPolicy(toolName: String)` to `private fun describeApprovalPolicy(tool: AgentTool)`, body `val policy = ApprovalPolicy.forTool(tool)`.
- Update its caller (`val approvalPolicy = describeApprovalPolicy(tool.name)` ~line 90) to `describeApprovalPolicy(tool)`.

In `ToolDocumentation.kt:115` and `RuntimeConfigTool.kt:194`: reword the prose mentioning `APPROVAL_TOOLS` to reference the `requiresApproval` property (comment-only). Also reword the in-file KDoc at `AgentService.kt:342` ("Single source of truth is AgentLoop.WRITE_TOOLS") — but that line is migrated in Task 6b; leave it for then.

**Note (intentional coverage move):** the old `ApprovalPolicyTest` had three set-membership tests (`APPROVAL_TOOLS contains all approval-required tools`, `every tool in APPROVAL_TOOLS has requiresApproval true`, `only run_command has allowSessionApproval false`) that reference the deleted `ApprovalPolicy.APPROVAL_TOOLS`. The rewrite in Step 1 DELETES them; the per-tool-policy coverage they provided is re-locked by `SafetyPropsCharacterizationTest` (Task 2). This is a deliberate, equivalent move, not a coverage loss.

- [ ] **Step 4b: Update the approval-impersonating test stubs (CRITICAL — found in plan review)**

Several behavioral tests build the REAL `AgentLoop` with anonymous `object : AgentTool` stubs NAMED after approval tools (`"edit_file"`, `"run_command"`). Until now those stubs tripped the gate via NAME membership; after this task the gate reads `tool.requiresApproval` (default `false`), so the gate would silently become a NO-OP for them — turning real assertions green-for-the-wrong-reason or red. You MUST add the matching props to each stub's factory.

First confirm the set (do not trust line numbers blindly):
```bash
grep -rln "object : AgentTool\|: AgentTool {" agent/src/test/kotlin | xargs grep -l '"edit_file"\|"run_command"'
```
Known files + stub factories (verified in review): `AgentLoopAutoApproveTest.kt` (`tool()` ~line 90, `run_command` → add `override val requiresApproval = true; override val allowSessionApproval = false`), `AgentLoopMemoryApprovalTest.kt` (`tool()` ~line 72, `edit_file` → `requiresApproval = true; allowSessionApproval = true`), `ApprovalGateReprompingBugTest.kt` (`fakeTool()` ~line 108, `edit_file` → `requiresApproval = true; allowSessionApproval = true`). Add the overrides to each stub's anonymous-object body. If a stub is parameterized by name, gate the props on the name (e.g. `override val requiresApproval = name in setOf("edit_file","create_file","delete_file","revert_file","run_command")`).

- [ ] **Step 5: Run tests**

Run: `./gradlew :agent:test --tests "*ApprovalPolicyTest*" --tests "*ToolDocPayload*" --tests "*MemoryApproval*" --tests "*AutoApprove*" --tests "*ApprovalGateReprompingBug*"`
Expected: PASS. (The memory-approval + auto-approve + repromping tests confirm the gate still fires for the impersonating stubs and the special cases are untouched.)

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/ApprovalPolicy.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/docs/ToolDocPayloadBuilder.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/docs/ToolDocumentation.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/RuntimeConfigTool.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/loop/ApprovalPolicyTest.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/loop/AgentLoopAutoApproveTest.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/loop/AgentLoopMemoryApprovalTest.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/loop/ApprovalGateReprompingBugTest.kt
git commit -m "refactor(agent): approval gate reads AgentTool.requiresApproval; drop ApprovalPolicy name sets (0b-3, behavior-unchanged)"
```

---

### Task 5: Switch the hook gates to `tool.isHookExempt`; delete `HOOK_EXEMPT`

**Files:**
- Modify: `agent/.../loop/AgentLoop.kt` — PRE gate (~line 2045) and POST gate (~line 2356): `toolName !in HOOK_EXEMPT` → `!tool.isHookExempt`; delete the `HOOK_EXEMPT` set (~lines 760-770).
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/loop/AgentLoopHookExemptionContractTest.kt` (new, source-text).

**Interfaces:**
- Consumes: `AgentTool.isHookExempt` (Task 3).

**Behavior-preservation check:** `{tools where isHookExempt==true}` = `{task_create, task_update, task_list, task_get, ai_review}` = the old `HOOK_EXEMPT` (proven by Task 3's characterization). `tool` is in scope at both gates (same loop iteration as the plan-mode guard / approval gate). Verify scope while editing; if `tool` is somehow unavailable at the POST gate, resolve it once near the gate from the same `call`/registry the iteration already uses — do NOT reintroduce a name set.

- [ ] **Step 1: Write the failing source-text contract test**

```kotlin
// agent/src/test/kotlin/com/workflow/orchestrator/agent/loop/AgentLoopHookExemptionContractTest.kt
package com.workflow.orchestrator.agent.loop

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class AgentLoopHookExemptionContractTest {
    private val src = File("src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt").readText()

    @Test
    fun `hook gates consult the per-tool isHookExempt property`() {
        assertTrue(src.contains("!tool.isHookExempt"),
            "AgentLoop hook gates must read tool.isHookExempt")
    }

    @Test
    fun `the HOOK_EXEMPT name set and its usages are gone`() {
        // Target the declaration + usage tokens specifically, so a stray lowercase prose
        // mention ("hook-exempt") can't false-fail the assertion.
        assertFalse(src.contains("val HOOK_EXEMPT"), "AgentLoop.HOOK_EXEMPT declaration must be deleted")
        assertFalse(src.contains("in HOOK_EXEMPT"), "no usage of the HOOK_EXEMPT set may remain")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "*AgentLoopHookExemptionContractTest*"`
Expected: FAIL — `HOOK_EXEMPT` still present; `!tool.isHookExempt` absent.

- [ ] **Step 3: Edit `AgentLoop.kt`**

- PRE gate (~line 2045): `if (toolName !in HOOK_EXEMPT && hookManager != null ...` → `if (!tool.isHookExempt && hookManager != null ...`.
- POST gate (~line 2356): same substitution.
- Delete the `HOOK_EXEMPT` declaration (~lines 760-770).

- [ ] **Step 4: Run tests**

Run: `./gradlew :agent:test --tests "*AgentLoopHookExemptionContractTest*" --tests "*Hook*"`
Expected: PASS (and no other hook test regresses).

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/loop/AgentLoopHookExemptionContractTest.kt
git commit -m "refactor(agent): hook gates read AgentTool.isHookExempt; drop HOOK_EXEMPT set (0b-3, behavior-unchanged)"
```

---

### Task 6a: Switch the in-loop write-tool sites to `tool.isMutating`

**Files:**
- Modify: `agent/.../loop/AgentLoop.kt` — three internal sites that have a `tool` instance:
  1. Plan-mode guard (~line 1943): drop the `toolName in WRITE_TOOLS` term (redundant — every WRITE_TOOLS member has `isMutating=true`), leaving `tool.isMutating || tool.isWriteAction(planModeAction)`.
  2. PRE-hook payload `"isWriteTool" to (toolName in WRITE_TOOLS).toString()` (~line 2055) → `(tool.isMutating).toString()`.
  3. Checkpoint capture trigger `if (toolName in WRITE_TOOLS)` (~line 2078) → `if (tool.isMutating)`.

**Interfaces:**
- Consumes: `AgentTool.isMutating` (already on all write tools since Phase 0a).

**Note:** `WRITE_TOOLS` is NOT deleted in this task (the name-only consumers in Task 6b still reference it). This task only flips the three sites that have the `tool` instance, keeping each commit behavior-preserving and reviewable.

- [ ] **Step 1: Add the failing source-text contract (the first half of `AgentLoopWriteToolPropContractTest`)**

```kotlin
// agent/src/test/kotlin/com/workflow/orchestrator/agent/loop/AgentLoopWriteToolPropContractTest.kt
package com.workflow.orchestrator.agent.loop

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class AgentLoopWriteToolPropContractTest {
    private val src = File("src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt").readText()

    @Test
    fun `plan-mode guard no longer references the WRITE_TOOLS name set`() {
        assertFalse(src.contains("toolName in WRITE_TOOLS"),
            "in-loop write classification must use tool.isMutating, not the WRITE_TOOLS set")
    }

    @Test
    fun `checkpoint + hook payload read isMutating`() {
        assertTrue(src.contains("tool.isMutating"),
            "AgentLoop must read tool.isMutating for write classification")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "*AgentLoopWriteToolPropContractTest*"`
Expected: FAIL — `toolName in WRITE_TOOLS` still present.

- [ ] **Step 3: Edit the three sites** as described above. ALSO reword the comment block just above the plan-mode guard (~lines 1934-1937) — it currently reads "...the tool name is not in `WRITE_TOOLS`." Reword to "...the tool does not declare `isMutating`." so no `WRITE_TOOLS` substring survives in `AgentLoop.kt` (the Task 6b source-text contract checks for it).

- [ ] **Step 3b: Update the plan-mode-impersonating test stubs (CRITICAL — found in plan review)**

Dropping the `toolName in WRITE_TOOLS` term from the guard means a stub NAMED `"edit_file"` that does NOT override `isMutating` is no longer blocked in plan mode. Two behavioral tests build the real loop with such stubs:
- `PlanModeLoopTest.kt` (`fakeTool` ~line 107, used as `edit_file`) → add `override val isMutating = true` to the write-tool stub.
- `PlanModeWriteActionGuardTest.kt` (`edit_file` stub ~line 265) → add `override val isMutating = true`.

Confirm the set first: `grep -rln '"edit_file"\|"create_file"\|"run_command"' agent/src/test/kotlin/com/workflow/orchestrator/agent/loop | xargs grep -l ": AgentTool"` and add `isMutating = true` to any write-named stub whose plan-mode assertion depends on being blocked. (Stubs that only exercise the approval path were handled in Task 4 Step 4b.)

- [ ] **Step 4: Run tests**

Run: `./gradlew :agent:test --tests "*AgentLoopWriteToolPropContractTest*" --tests "*PlanMode*" --tests "*Checkpoint*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/loop/AgentLoopWriteToolPropContractTest.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/loop/PlanModeLoopTest.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/loop/PlanModeWriteActionGuardTest.kt
git commit -m "refactor(agent): in-loop write classification reads tool.isMutating (0b-3, behavior-unchanged)"
```

---

### Task 6b: Migrate the name-only write-tool consumers and delete `WRITE_TOOLS`

**Files:**
- Modify: `agent/.../AgentService.kt` — `writeToolNames` (~line 344) derive from the registry.
- Modify: `agent/.../tools/builtin/SpawnAgentTool.kt` — `inferPlanMode` (~line 797) via `resolvedTools.values`.
- Modify: `agent/.../tools/docs/ToolDocPayloadBuilder.kt` — `val isWriteTool = tool.name in AgentLoop.WRITE_TOOLS` (~line 74) → `tool.isMutating`.
- Modify: `agent/.../loop/AgentLoop.kt` — delete the `WRITE_TOOLS` declaration (~lines 772-778).
- Modify (tests): `ToolDefinitionFilterTest.kt`, `AgentServiceToolFilterTest.kt`, `DelegatedActOnlyToolFilterTest.kt` — replace `AgentLoop.WRITE_TOOLS` with an explicit literal set. `WalkthroughRegistrationContractTest.kt` — change the "not in WRITE_TOOLS" assertion.
- Test: `agent/src/test/kotlin/.../AgentServiceWriteToolDerivationContractTest.kt` (new, source-text) + extend `AgentLoopWriteToolPropContractTest` with a "WRITE_TOOLS gone" assert.

**Interfaces:**
- Consumes: `ToolRegistry.allTools(): Collection<AgentTool>` (existing), `AgentTool.isMutating`.
- Produces: `AgentService.writeToolNames` now equals `{registry tools where isMutating}` — identical to the old set for A, and inclusive of any B-contributed write tool.

**Behavior-preservation check:** `registry.allTools()` includes core + deferred + active-deferred. The only tools with `isMutating==true` are the 10 former `WRITE_TOOLS` members (3 of which — `format_code`/`optimize_imports`/`refactor_rename` — are deferred but still in the registry). So the derived name set equals the old `WRITE_TOOLS` exactly. The three meta-tools (`runtime_config`/`java_runtime_exec`/`project_structure`) keep `isMutating=false` + `isWriteAction(...)` and are intentionally NOT in the schema-filtered set (unchanged).

- [ ] **Step 1: Extend the contract tests (failing)**

Add to `AgentLoopWriteToolPropContractTest.kt`:

```kotlin
    @Test
    fun `the WRITE_TOOLS name set and its usages are gone`() {
        // Target declaration + usage tokens specifically (the guard comment at ~1937 was
        // reworded in Task 6a, but stay robust against any future prose mention).
        assertFalse(src.contains("val WRITE_TOOLS"), "AgentLoop.WRITE_TOOLS declaration must be deleted")
        assertFalse(src.contains("in WRITE_TOOLS"), "no usage of the WRITE_TOOLS set may remain")
    }
```

Create `AgentServiceWriteToolDerivationContractTest.kt`:

```kotlin
// agent/src/test/kotlin/com/workflow/orchestrator/agent/AgentServiceWriteToolDerivationContractTest.kt
package com.workflow.orchestrator.agent

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class AgentServiceWriteToolDerivationContractTest {
    private val src = File("src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt").readText()

    @Test
    fun `writeToolNames is derived from the registry by isMutating`() {
        assertTrue(
            src.contains("registry.allTools()") && src.contains("isMutating"),
            "AgentService.writeToolNames must derive from registry.allTools() filtered by isMutating " +
                "so B-contributed write tools are schema-filtered in plan mode",
        )
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :agent:test --tests "*AgentLoopWriteToolPropContractTest*" --tests "*AgentServiceWriteToolDerivationContractTest*"`
Expected: FAIL.

- [ ] **Step 3: Migrate the consumers**

`AgentService.kt` (~line 344):
```kotlin
    /** Tool names blocked in plan mode (write/mutating tools), derived from each tool's
     *  self-declared `isMutating` — includes any B-contributed write tool.
     *  Assumes the registry is fully populated (the sole consumer runs inside the per-iteration
     *  toolDefinitionProvider, long after registerAllTools()); do NOT read this during init. */
    private val writeToolNames: Set<String>
        get() = registry.allTools().filter { it.isMutating }.map { it.name }.toSet()
```
Also reword the now-stale in-file comment at `AgentService.kt:1069` ("...Hook-exempt (see AgentLoop.HOOK_EXEMPT).") to "...Hook-exempt (each declares `isHookExempt = true`)."

`SpawnAgentTool.kt` (~line 797):
```kotlin
    private fun inferPlanMode(resolvedTools: Map<String, AgentTool>): Boolean {
        return resolvedTools.values.none { it.isMutating }
    }
```

`ToolDocPayloadBuilder.kt` (~line 74):
```kotlin
        val isWriteTool = tool.isMutating
```
(and remove the now-unused `import ...AgentLoop` if it becomes unused — verify with detekt).

`AgentLoop.kt`: delete the `WRITE_TOOLS` declaration (~lines 772-778).

`AgentTool.kt` (~lines 106-116): the `isWriteAction` KDoc shows example code `toolName in WRITE_TOOLS` — reword that prose to reference the deleted set's replacement (`tool.isMutating`) so the most-visible doc example isn't stale.

**Stale-doc note (non-blocking):** ~20 other comment/KDoc/.md references to `WRITE_TOOLS`/`HOOK_EXEMPT`/`APPROVAL_TOOLS` exist (e.g. `AgentController.kt:3088`, `BackgroundEligibility.kt:6`, `ToolDocumentation.kt:130/132/140`, several tool `.md` docs). None compile-break. Sweep the most visible ones opportunistically here; the CLAUDE.md/spec sweep is Task 10. Do NOT let the doc-debt block the behavioral commits.

- [ ] **Step 4: Update the dependent tests**

**Preflight (do NOT skip):** `grep -rn "WRITE_TOOLS" agent/src/test` and handle EVERY hit. Known hits (verified in review):

1. `ToolDefinitionFilterTest.kt`, `DelegatedActOnlyToolFilterTest.kt`, and the FILTER references (~line 33/124) in `AgentServiceToolFilterTest.kt`: replace `AgentLoop.WRITE_TOOLS` with an explicit local literal:
```kotlin
private val WRITE_TOOLS = setOf(
    "edit_file", "create_file", "delete_file", "run_command", "revert_file",
    "send_stdin", "format_code", "optimize_imports", "refactor_rename", "background_process",
)
```
(declared local to each test file; these exercise the PURE `ToolDefinitionFilter.shouldInclude`, which takes a `Set<String>` — unaffected by where the set comes from in production).

2. `AgentServiceToolFilterTest.kt` (~line 201-211) has a SEPARATE completeness test (`WRITE_TOOLS contains all expected write tool names`) that asserts the *production set's contents*. After deletion there is no production set to assert against, and a literal-vs-literal rewrite would be a tautology. **DELETE this test** — its coverage (the canonical write-tool list) is re-locked by `SafetyPropsCharacterizationTest` (which proves each concrete tool's `isMutating`). Note this deletion in the commit message.

3. `AgentLoopTest.kt` (~line 450-455) has `assertEquals(expectedWriteTools, AgentLoop.WRITE_TOOLS, ...)` — a hard compile break on delete. **DELETE this test** (same rationale as #2; `SafetyPropsCharacterizationTest` covers it).

4. `WalkthroughRegistrationContractTest.kt`: the assertion that `walkthrough` is "not in `WRITE_TOOLS`" → reword to a property check: `assertFalse(walkthroughSrc.contains("override val isMutating = true"), "walkthrough must not be a mutating tool (plan-mode legal)")`. Keep the registration + sub-agent-name-filter assertions.

5. `PersonaToolsTest.kt` (~line 31) declares its OWN local `WRITE_TOOLS` literal (not `AgentLoop.WRITE_TOOLS`) — leave it ALONE; it does not reference the deleted set.

Re-run the preflight grep after editing — it must return only test-local `val WRITE_TOOLS` literals (#1, #5), no `AgentLoop.WRITE_TOOLS`.

- [ ] **Step 4b: Add the end-to-end "B-contributed write tool is classified safely" test (found in plan review)**

This closes the loop the whole feature exists for — pure, no platform. Create `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/ContributedWriteToolClassificationTest.kt`:

```kotlin
package com.workflow.orchestrator.agent.tools

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.loop.ApprovalPolicy
import com.workflow.orchestrator.core.ai.dto.FunctionParameters
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Proves a depending plugin (B) CAN now contribute a SAFE write tool: a contributed tool that
 * declares isMutating + requiresApproval is picked up by the SAME registry-derivation AgentService
 * uses for plan-mode write filtering AND trips the same approval policy. The blocker the spec named
 * ("until safety props land, B may not contribute write tools") is lifted.
 */
class ContributedWriteToolClassificationTest {
    private val bWriteTool = object : AgentTool {
        override val name = "companyb_write"
        override val description = "B write tool"
        override val parameters = FunctionParameters(properties = emptyMap())
        override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR)
        override val isMutating = true
        override val requiresApproval = true
        override val allowSessionApproval = true
        override suspend fun execute(params: JsonObject, project: Project) =
            ToolResult(content = "", summary = "", tokenEstimate = 0)
    }

    @Test
    fun `contributed mutating tool is write-classified by the registry derivation`() {
        val registry = ToolRegistry()
        registry.registerCore(bWriteTool)
        val writeNames = registry.allTools().filter { it.isMutating }.map { it.name }.toSet()
        assertTrue("companyb_write" in writeNames, "B write tool must be in the derived write-tool set")
    }

    @Test
    fun `contributed write tool trips the approval gate policy`() {
        val policy = ApprovalPolicy.forTool(bWriteTool)
        assertTrue(policy.requiresApproval)
        assertTrue(policy.allowSessionApproval)
    }
}
```

- [ ] **Step 5: Run tests**

Run: `./gradlew :agent:test --tests "*ToolDefinitionFilter*" --tests "*AgentServiceToolFilter*" --tests "*DelegatedActOnly*" --tests "*Walkthrough*" --tests "*SpawnAgent*" --tests "*AgentLoopWriteToolPropContractTest*" --tests "*AgentServiceWriteToolDerivationContractTest*" --tests "*ContributedWriteToolClassification*" --tests "*AgentLoopTest*"`
Expected: PASS. Then re-run the preflight `grep -rn "WRITE_TOOLS" agent/src` — only test-local `val WRITE_TOOLS` literals may remain (no `AgentLoop.WRITE_TOOLS`, no `val WRITE_TOOLS` or `in WRITE_TOOLS` in `agent/src/main`).

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/docs/ToolDocPayloadBuilder.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/
git commit -m "refactor(agent): derive write-tool set from registry isMutating; delete WRITE_TOOLS (0b-3, behavior-unchanged, unblocks B write tools)"
```

---

## PART 2 — Thin `ToolRegistrationService`

### Task 8: Pure `ToolContributionRunner` with per-contributor isolation

**Files:**
- Create: `agent/.../tools/contribution/ToolContributionRunner.kt`
- Test: `agent/src/test/kotlin/.../tools/contribution/ToolContributionRunnerTest.kt`

**Interfaces:**
- Consumes: `AgentToolContributor` (existing EP interface), `ToolRegistrationContext` (existing), `ToolRegistry` (existing).
- Produces:
  - `data class ContributionDiagnostics(val contributorCount: Int, val contributorClasses: List<String>, val addedToolNames: Set<String>, val failures: List<ContributorFailure>)`
  - `data class ContributorFailure(val contributorClass: String, val error: Throwable)`
  - `object ToolContributionRunner { fun run(contributors, context, registry): ContributionDiagnostics }`

**Why pure:** `ToolRegistry` and `ToolRegistrationContext` are plain classes (no platform), `Project` can be `mockk(relaxed=true)`, and contributors are test stubs — so per-contributor isolation is fully unit-testable without the EP/platform.

- [ ] **Step 1: Write the failing test**

```kotlin
// agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/contribution/ToolContributionRunnerTest.kt
package com.workflow.orchestrator.agent.tools.contribution

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolRegistry
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.core.ai.dto.FunctionParameters
import io.mockk.mockk
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolContributionRunnerTest {
    private val project = mockk<Project>(relaxed = true)

    private fun toolNamed(n: String) = object : AgentTool {
        override val name = n
        override val description = n
        override val parameters = FunctionParameters(properties = emptyMap())
        override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR)
        override suspend fun execute(params: JsonObject, project: Project) =
            ToolResult(content = "", summary = "", tokenEstimate = 0)
    }

    // NOTE: AgentToolContributor is a plain `interface` (NOT `fun interface`), so SAM-lambda
    // construction is unavailable — use anonymous objects (mirrors the existing ToolRegistrationContextTest).
    private fun contributor(body: (ToolRegistrationContext) -> Unit) = object : AgentToolContributor {
        override fun registerTools(context: ToolRegistrationContext) = body(context)
    }

    @Test
    fun `a throwing contributor does not block the others (per-contributor isolation)`() {
        val registry = ToolRegistry()
        val good = contributor { ctx -> ctx.registerCore(toolNamed("good_tool")) }
        val bad = contributor { throw IllegalStateException("boom") }
        val good2 = contributor { ctx -> ctx.registerCore(toolNamed("good_tool_2")) }

        val diag = ToolContributionRunner.run(listOf(good, bad, good2),
            ToolRegistrationContext(project, registry), registry)

        assertTrue(registry.has("good_tool"))
        assertTrue(registry.has("good_tool_2"), "a failure mid-list must not abort later contributors")
        assertEquals(3, diag.contributorCount)
        assertEquals(setOf("good_tool", "good_tool_2"), diag.addedToolNames)
        assertEquals(1, diag.failures.size)
        assertTrue(diag.failures.first().error.message!!.contains("boom"))
    }

    @Test
    fun `no contributors yields empty diagnostics`() {
        val registry = ToolRegistry()
        val diag = ToolContributionRunner.run(emptyList(),
            ToolRegistrationContext(project, registry), registry)
        assertEquals(0, diag.contributorCount)
        assertTrue(diag.addedToolNames.isEmpty())
        assertTrue(diag.failures.isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "*ToolContributionRunnerTest*"`
Expected: COMPILE FAILURE — `ToolContributionRunner` does not exist.

- [ ] **Step 3: Write `ToolContributionRunner.kt`**

```kotlin
package com.workflow.orchestrator.agent.tools.contribution

import com.workflow.orchestrator.agent.tools.ToolRegistry

/** Diagnostics from running the agent-tool contributors. Purely a return value for logging. */
data class ContributionDiagnostics(
    val contributorCount: Int,
    val contributorClasses: List<String>,
    val addedToolNames: Set<String>,
    val failures: List<ContributorFailure>,
)

data class ContributorFailure(val contributorClass: String, val error: Throwable)

/**
 * Runs each [AgentToolContributor] under its OWN `runCatching` so one misbehaving contributor
 * (e.g. from plugin B) cannot abort the others — the per-contributor isolation deferred from
 * Phase 0a (where a single `runCatching` wrapped the whole `forEach`). Pure: no platform/EP
 * access — the caller fetches the EP list and supplies the registry.
 */
object ToolContributionRunner {
    fun run(
        contributors: List<AgentToolContributor>,
        context: ToolRegistrationContext,
        registry: ToolRegistry,
    ): ContributionDiagnostics {
        val before = registry.getActiveTools().keys.toSet()
        val failures = mutableListOf<ContributorFailure>()
        for (c in contributors) {
            runCatching { c.registerTools(context) }
                .onFailure { failures += ContributorFailure(c::class.java.simpleName, it) }
        }
        val added = registry.getActiveTools().keys - before
        return ContributionDiagnostics(
            contributorCount = contributors.size,
            contributorClasses = contributors.map { it::class.java.simpleName },
            addedToolNames = added,
            failures = failures,
        )
    }
}
```

(`getActiveTools().keys` mirrors the diff the Phase-0a code used for its log line — behavior-preserving for the diagnostic. `contributorClasses` preserves the old success-log detail (Phase 0a logged `contributors.map { it::class.java.simpleName }`), so the migration loses no log information.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :agent:test --tests "*ToolContributionRunnerTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/contribution/ToolContributionRunner.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/contribution/ToolContributionRunnerTest.kt
git commit -m "feat(agent): pure ToolContributionRunner with per-contributor isolation (0b-3)"
```

---

### Task 9: `ToolRegistrationService` (@Service PROJECT) + AgentService delegates

**Files:**
- Create: `agent/.../tools/contribution/ToolRegistrationService.kt`
- Modify: `agent/.../AgentService.kt` — replace the inline contributor-EP block (~lines 1323-1340) with a delegating call.
- Test: `agent/src/test/kotlin/.../AgentServiceToolRegistrationWiringContractTest.kt` (new, source-text).

**Interfaces:**
- Consumes: `ToolContributionRunner.run` (Task 8), `AgentToolContributor.EP_NAME` (existing), `ToolRegistry` (existing).
- Produces: `ToolRegistrationService` with `fun contributeExternalTools(registry: ToolRegistry): ContributionDiagnostics` + `companion fun getInstance(project): ToolRegistrationService`.

- [ ] **Step 1: Write the failing source-text wiring contract**

```kotlin
// agent/src/test/kotlin/com/workflow/orchestrator/agent/AgentServiceToolRegistrationWiringContractTest.kt
package com.workflow.orchestrator.agent

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class AgentServiceToolRegistrationWiringContractTest {
    private val src = File("src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt").readText()

    @Test
    fun `AgentService delegates contributor EP iteration to ToolRegistrationService`() {
        assertTrue(
            src.contains("ToolRegistrationService") && src.contains("contributeExternalTools(registry)"),
            "AgentService must delegate the agentToolContributor EP iteration to ToolRegistrationService",
        )
    }

    @Test
    fun `AgentService no longer iterates the EP inline`() {
        assertFalse(src.contains(".EP_NAME.extensionList"),
            "the EP_NAME.extensionList iteration must live in ToolRegistrationService, not AgentService")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "*AgentServiceToolRegistrationWiringContractTest*"`
Expected: FAIL.

- [ ] **Step 3: Write `ToolRegistrationService.kt`**

```kotlin
package com.workflow.orchestrator.agent.tools.contribution

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolRegistry

/**
 * Project-scoped host for agent tools contributed by depending plugins via the
 * [AgentToolContributor] EP. Thin by design (Phase 0b-3): A's own [ToolRegistry] stays owned by
 * `AgentService`; this service only runs the EP iteration (with per-contributor isolation) against
 * that registry. A-internal infrastructure — B contributes through the EP, not through this service,
 * so this is NOT part of the @InternalApi B-facing surface.
 */
@Service(Service.Level.PROJECT)
class ToolRegistrationService(private val project: Project) {

    /** Run every registered [AgentToolContributor] against [registry]; never throws. */
    fun contributeExternalTools(registry: ToolRegistry): ContributionDiagnostics {
        val context = ToolRegistrationContext(project, registry)
        val contributors = AgentToolContributor.EP_NAME.extensionList
        return ToolContributionRunner.run(contributors, context, registry)
    }

    companion object {
        fun getInstance(project: Project): ToolRegistrationService = project.service()
    }
}
```

- [ ] **Step 4: Replace the inline block in `AgentService.registerAllTools()`**

Replace the existing `runCatching { ... AgentToolContributor.EP_NAME.extensionList ... }.onFailure { ... }` block (~lines 1323-1340) with:

```kotlin
        // Tools contributed by depending plugins (e.g. plugin B) via the agentToolContributor EP.
        // Delegated to the project-scoped ToolRegistrationService (per-contributor isolation).
        runCatching {
            val diag = com.workflow.orchestrator.agent.tools.contribution.ToolRegistrationService
                .getInstance(project).contributeExternalTools(registry)
            if (diag.contributorCount > 0) {
                log.info(
                    "[agentToolContributor] ${diag.contributorCount} contributor(s) " +
                        "${diag.contributorClasses} contributed tools: ${diag.addedToolNames}",
                )
            }
            diag.failures.forEach { log.warn("[Tools] contributor ${it.contributorClass} failed", it.error) }
        }.onFailure { log.warn("[Tools] agentToolContributor EP delegation failed", it) }
```

- [ ] **Step 5: Run tests**

Run: `./gradlew :agent:test --tests "*AgentServiceToolRegistrationWiringContractTest*" --tests "*ToolContributionRunnerTest*"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/contribution/ToolRegistrationService.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/AgentServiceToolRegistrationWiringContractTest.kt
git commit -m "feat(agent): thin project-scoped ToolRegistrationService; AgentService delegates contributor EP (0b-3)"
```

---

## PART 3 — Docs, detekt, full green gate

### Task 10: Docs + konsist + full-suite gate

**Files:**
- Modify: `agent/CLAUDE.md` — update "Tool Execution" / "Tool Approval" / "Plan Mode Enforcement" / "TaskStore" sections: replace `WRITE_TOOLS`/`HOOK_EXEMPT`/`APPROVAL_TOOLS` set descriptions with the self-declared `isMutating`/`isHookExempt`/`requiresApproval`/`allowSessionApproval` properties; note `ToolRegistrationService` + per-contributor isolation.
- Modify: `docs/superpowers/specs/2026-06-22-plugin-split-design.md` — add a §17-style resolved note for 0b-3 (what shipped, the thin-host decision, the property names B uses to contribute a safe write tool).
- Verify: `:konsist` still green (no new B-facing interface added; `ToolRegistrationService` is a plain `@Service`, not `@InternalApi`, so `PublicApiSurfaceTest`/`SeamApiStabilityTest` need no change — confirm).

- [ ] **Step 1: Update `agent/CLAUDE.md`** as above (prose only).

- [ ] **Step 2: Update the spec** with the 0b-3 resolved note.

- [ ] **Step 3: detekt autocorrect**

Run: `./gradlew :agent:detekt --auto-correct`
Fix any genuine `ImportOrdering` unmasking by reordering imports (do NOT baseline). Confirm `:agent` detekt clean.

- [ ] **Step 4: Full `:agent` suite (the emergent-failure gate)**

Run: `./gradlew :agent:clean :agent:test --rerun-tasks`
Expected: GREEN, zero "Indexing timeout". If a write-tool/approval/hook behavioral test outside the ones touched here fails, STOP — the migration was not behavior-preserving; diff the failing assertion against the property values.

- [ ] **Step 5: Plugin verify + konsist**

Run: `./gradlew :konsist:test verifyPlugin`
Expected: GREEN. Drop any regenerated `agent/src/main/resources/webview/dist/` churn (`git checkout -- ...`) before committing.

- [ ] **Step 6: Commit**

```bash
git add agent/CLAUDE.md docs/superpowers/specs/2026-06-22-plugin-split-design.md
git commit -m "docs(plugin-split): document 0b-3 AgentTool safety props + ToolRegistrationService"
```

---

## Self-Review (run before declaring the plan ready)

**1. Spec coverage (§5 / §8.2):** "Move `WRITE_TOOLS`/`HOOK_EXEMPT` onto self-declared props (`isWriteTool`, `requiresApproval`)" → Tasks 1-6b (`isMutating` reused for write classification — equivalent to the spec's `isWriteTool`; KDoc notes this). "Project-scoped `ToolRegistrationService` host" → Tasks 8-9 (thin variant per the user's decision). "Until safety props land, B may not contribute write tools" → after Task 6b, a B tool with `isMutating=true`/`requiresApproval=true` is correctly schema-filtered + approval-gated. ✓

**2. Placeholder scan:** every code step shows full code; every test step shows full test; commands have expected output. ✓

**3. Type consistency:** `forTool(tool: AgentTool)` (Task 4) consumed by AgentLoop + ToolDocPayloadBuilder; `ContributionDiagnostics`/`ContributorFailure` (Task 8) consumed by `ToolRegistrationService` + AgentService log (Task 9); `contributeExternalTools(registry)` name identical in service, call site, and wiring test. ✓

**4. Risk notes:** (a) `tool` scope at the POST hook gate (Task 5) — verify during edit. (b) `ToolDocPayloadBuilder` import of `AgentLoop` may go unused after Task 6b — detekt will flag. (c) `WalkthroughRegistrationContractTest`'s WRITE_TOOLS assertion must be migrated, not deleted blindly. (d) the `inferPlanMode` change (Task 6b) alters the SOURCE of the write-set for sub-agent plan-mode inference — value is identical for A but now also honors B write tools (intended).

## Execution Handoff

Per the standing multi-round-review rule, this plan must get **3 independent opus review rounds** (plan-accuracy/bytecode-verified · completeness/assumptions · skeptic) before execution, then **subagent-driven execution** (fresh subagent per task + independent task reviewer + controller build/test verify), then a **final whole-branch opus review**, then `finishing-a-development-branch` (ff-merge + push). Track progress in `.superpowers/sdd/progress.md`.
