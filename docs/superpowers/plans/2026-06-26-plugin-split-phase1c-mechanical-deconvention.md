# Plugin Split — Phase 1c (Mechanical De-convention Tail) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish Phase 1 of the open-source plugin split by removing the last company-convention assumptions in the agent/VCS/PSI/Sprint surfaces: delete dead branch-validation, make the AI commit-message format configurable (escape hatch for non-Conventional-Commits shops), feature-detect + hide the Sprint tab on non-Software Jira, give `PsiContextEnricher` a build-system-agnostic module fallback, and reserve the neutral `VcsHostClient` default-branch/reviewer ops.

**Architecture:** Five independent work items (scope-map Clusters A-residual + D). Each is behavior-preserving for the existing company configuration; the de-convention is *additive* (new opt-in settings/capabilities) or *removal of dead code*. No `@State` migration is needed anywhere in this phase (every new default equals current behavior). The Sprint-hide uses a neutral `EventBus` event so `:core`'s tool-window factory stays ignorant of Jira.

**Tech Stack:** Kotlin 2.1.10, IntelliJ Platform Plugin v2 (IDEA 2025.1+), Gradle, JUnit5 + mockk for unit tests, Kotlin UI DSL (`panel { }`) for settings.

## Global Constraints

- **Branch:** work on `feature/plugin-split` (current branch). Do NOT branch off `main`.
- **No `Co-Authored-By` trailer** in any commit (user override of the harness default — never add it).
- **`:core` ONE-`BasePlatformTestCase`-per-test-JVM invariant.** The `:core` test JVM is un-forked; a second `BasePlatformTestCase` causes a deterministic "Indexing timeout". **All new `:core` tests in this phase MUST be pure JUnit5 (+ mockk), never `BasePlatformTestCase`.** Same caution for `:jira`.
- **`MULTIPLE_DEFAULTS_INHERITED_FROM_SUPERTYPES` trap (Task 2).** The neutral `VcsHostClient` interface declares **NO default parameter values** on any method. The new methods are NOT on `BitbucketService`, so this is naturally satisfied — keep it that way.
- **Source-text test note.** `PsiContextEnricherSingleReadActionTest` reads the WHOLE enricher source (`enricherSource()` → `readText()`) and counts `readAction {` and `findFileByPath` (must stay == 1). The D4 fallback adds neither (it runs inside the existing read action and adds no file resolution), so the counts are preserved. (This is a whole-file read, not a sentinel-slice — but still run the FULL module test, not just the focused one.)
- **Build-cache trap.** When a commit changes a method's behavior that reused test stubs depend on, run `:<module>:clean :<module>:test --rerun-tasks` to defeat Gradle compile-avoidance.
- **detekt is RED on 73 pre-existing 0b-3 issues** (cache-masked, deferred by the user to an IDE-autocorrect cleanup). Phase 1c code itself must be detekt-clean; do NOT baseline, do NOT attempt to fix the 73 pre-existing ones here. Run module detekt with `--rerun-tasks` to be authoritative, and only fix issues introduced by THIS phase.
- **Service threading conventions.** `@Service` constructors take an injected `cs: CoroutineScope` (do NOT allocate `CoroutineScope(SupervisorJob()+…)` inside a `@Service`). API calls on `Dispatchers.IO`; UI on EDT. Never `runBlocking` on EDT.
- **Multi-round review (standing project rule).** Every task gets the two-stage review (implementer self-review + independent task reviewer) + controller full-suite verify; the whole branch gets a final independent opus review before declaring done.

---

## File Structure

| File | Module | Change |
|---|---|---|
| `jira/.../service/BranchNameValidator.kt` | :jira | Delete dead `isValidBranchName` (lines 77–87) |
| `jira/.../service/BranchNameValidatorTest.kt` | :jira | Delete the one test method (lines 50–55) |
| `core/.../services/VcsHostClient.kt` | :core | Add `getDefaultBranch` + `getDefaultReviewersForBranch` (no param defaults) |
| `pullrequest/.../service/BitbucketServiceImpl.kt` | :pullrequest | Implement the two new neutral methods (thin delegates) |
| `core/.../services/VcsHostClientDefaultBranchReviewerShapeTest.kt` | :core (test) | NEW — reflection shape-reservation test |
| `core/.../psi/PsiContextEnricher.kt` | :core | Rename `mavenModule`→`moduleName`; add `ModuleUtilCore` fallback |
| `core/.../vcs/GenerateCommitMessageAction.kt` | :core | Update `ctx.mavenModule`→`ctx.moduleName`; pass `commitMessageFormat` |
| `core/.../psi/PsiContextEnricherSingleReadActionTest.kt` | :core (test) | Extend: still-single-readAction + fallback-present assertions |
| `core/.../ai/prompts/CommitMessageFormat.kt` | :core | NEW — `CommitMessageFormat` enum + `fromSetting` |
| `core/.../ai/prompts/CommitMessagePromptBuilder.kt` | :core | Refactor to branch on format; extract shared context/diff tail |
| `core/.../settings/PluginSettings.kt` | :core | Add `commitMessageFormat by string("conventional")` |
| `core/.../ai/prompts/CommitMessagePromptBuilderTest.kt` | :core (test) | NEW — characterization + plain-mode tests |
| `jira/.../settings/JiraWorkflowConfigurable.kt` | :jira | Add "Commit Messages" group with format combo box |
| `core/.../toolwindow/WorkflowTabProvider.kt` | :core | Add `fun isAvailable(project): Boolean = true` default |
| `core/.../toolwindow/WorkflowToolWindowFactory.kt` | :core | Filter tabs by `isAvailable`; subscribe to `TabAvailabilityChanged` → rebuild |
| `core/.../events/WorkflowEvent.kt` | :core | Add `TabAvailabilityChanged(tabTitle)` event |
| `core/.../toolwindow/WorkflowTabProviderAvailabilityTest.kt` | :core (test) | NEW — default + filter-predicate test |
| `jira/.../service/JiraAgileCapabilityService.kt` | :jira | NEW — `@Service` cached async probe + tri-state verdict |
| `jira/.../ui/SprintTabProvider.kt` | :jira | Override `isAvailable` reading the capability verdict |
| `jira/.../service/JiraAgileCapabilityClassifyTest.kt` | :jira (test) | NEW — tri-state classify pure test |

---

## Task 1: Delete dead `isValidBranchName` (D1)

**Files:**
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/service/BranchNameValidator.kt:77-87`
- Modify (test): `jira/src/test/kotlin/com/workflow/orchestrator/jira/service/BranchNameValidatorTest.kt:50-55`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing (pure deletion). Confirmed dead: the ONLY caller of `isValidBranchName` is its own test method (4 call sites, all in `BranchNameValidatorTest`). The other methods (`generateBranchName`, `requiresAiSummary`, `issueTypeToPrefix`) stay — they have real prod callers (`BranchingService`, `SprintDashboardPanel`).

- [ ] **Step 1: Delete the test method first (TDD-by-deletion — remove the spec for the dead behavior)**

In `BranchNameValidatorTest.kt`, delete the entire test method (lines ~50–55):

```kotlin
    @Test
    fun `isValidBranchName accepts standard patterns`() {
        assertTrue(BranchNameValidator.isValidBranchName("feature/PROJ-123-login-fix"))
        assertTrue(BranchNameValidator.isValidBranchName("bugfix/PROJ-456-crash"))
        assertFalse(BranchNameValidator.isValidBranchName("feature/no ticket id"))
        assertFalse(BranchNameValidator.isValidBranchName(""))
    }
```

If `assertFalse` is now unused elsewhere in the file, remove its import too (check first — other tests may use `assertTrue` only).

- [ ] **Step 2: Run the test to confirm it still compiles & passes WITHOUT that method**

Run: `./gradlew :jira:test --tests "*BranchNameValidatorTest*"`
Expected: PASS (4 remaining tests). It still references `isValidBranchName` nowhere now.

- [ ] **Step 3: Delete the dead function**

In `BranchNameValidator.kt`, delete the whole `isValidBranchName` function (lines ~77–87):

```kotlin
    fun isValidBranchName(name: String): Boolean {
        if (name.isBlank()) {
            log.warn("[Jira:Branch] Validation failed: branch name is blank")
            return false
        }
        val valid = TICKET_PATTERN.containsMatchIn(name)
        if (!valid) {
            log.warn("[Jira:Branch] Validation failed: branch name '$name' does not contain a ticket ID pattern (e.g. PROJ-123)")
        }
        return valid
    }
```

Then delete the now-dead members (verified: each is used ONLY by `isValidBranchName`):
- `private val log = Logger.getInstance(BranchNameValidator::class.java)` (line 7)
- `import com.intellij.openapi.diagnostic.Logger` (line 3)
- `private val TICKET_PATTERN = Regex("[A-Z][A-Z0-9]+-\\d+")` (line 9)

KEEP `private val INVALID_CHARS` (line 10) — it's used by `generateBranchName` (line 51). After the edits, `BranchNameValidator` keeps only `INVALID_CHARS`, `issueTypeToPrefix`, `generateBranchName`, `requiresAiSummary`. This avoids new detekt `UnusedPrivateProperty`/`UnusedImport` findings.

- [ ] **Step 4: Compile + full module test (catch any stray reference)**

Run: `./gradlew :jira:compileKotlin :jira:test`
Expected: PASS. Grep the whole repo first to be safe:
Run: `grep -rn "isValidBranchName" --include=*.kt .` → Expected: ZERO matches.

- [ ] **Step 5: Commit**

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/service/BranchNameValidator.kt \
        jira/src/test/kotlin/com/workflow/orchestrator/jira/service/BranchNameValidatorTest.kt
git commit -m "refactor(jira): delete dead isValidBranchName (plugin-split 1c/D1)

Zero production callers — only its own test referenced it. Other
BranchNameValidator methods (generateBranchName/requiresAiSummary/
issueTypeToPrefix) stay; they back BranchingService + SprintDashboardPanel."
```

---

## Task 2: VcsHostClient default-branch + default-reviewer shape-reservation (Cluster A residual)

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/services/VcsHostClient.kt` (add 2 methods)
- Modify: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/BitbucketServiceImpl.kt` (implement them as thin delegates)
- Create (test): `core/src/test/kotlin/com/workflow/orchestrator/core/services/VcsHostClientDefaultBranchReviewerShapeTest.kt`

**Interfaces:**
- Consumes: existing `BitbucketBranchClient.getDefaultBranch(projectKey, repoSlug): ApiResult<BitbucketBranch>` and `getDefaultReviewersForBranch(repo: RepoCoords, sourceBranch, targetBranch): ApiResult<List<BitbucketUser>>`; existing neutral DTOs `BranchData(id, displayId, latestCommit, isDefault)` and `BitbucketUserData(name, displayName, emailAddress)` (`VcsUserData` = typealias of `BitbucketUserData`); `BitbucketServiceImpl.resolveRepo(repoName): Pair<String,String>?` and its `client: BitbucketBranchClient?` field; `RepoCoords(projectKey, repoSlug)`.
- Produces (the new neutral seam surface, NO param defaults):
  - `suspend fun getDefaultBranch(repoName: String?): ToolResult<BranchData>`
  - `suspend fun getDefaultReviewersForBranch(sourceBranch: String, targetBranch: String, repoName: String?): ToolResult<List<VcsUserData>>`

**Background:** Deferred from Phase 0b-2 because these ops live on the lower `BitbucketBranchClient`, not on `BitbucketService`. Shape-reservation only — NO consumer resolves `VcsHostClient` yet (like `NativeProtocol` pre-Phase-4). The delegating impls are real (working) so they're not dead-throwing code, but nothing calls them, so behavior is unchanged.

**Scope note (intentional):** `BitbucketBranchClient` also has a legacy branch-AGNOSTIC `getDefaultReviewers(projectKey, repoSlug)` (@1268, "retained for admin/preview"). We deliberately reserve ONLY the branch-aware `getDefaultReviewersForBranch` on the neutral seam — it's the general/correct shape (default reviewers depend on the source→target ref pair). The legacy variant is intentionally not promoted to the neutral interface.

- [ ] **Step 1: Write the failing shape-reservation test**

Create `core/src/test/kotlin/com/workflow/orchestrator/core/services/VcsHostClientDefaultBranchReviewerShapeTest.kt`:

```kotlin
package com.workflow.orchestrator.core.services

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// NOTE: no ToolResult import — this test is reflection-only AND lives in the same
// package (com.workflow.orchestrator.core.services) as ToolResult and VcsHostClient.
// (ToolResult is in core.services, NOT core.model.)

/**
 * Phase 1c / Cluster A: pins the default-branch + default-reviewer ops onto the
 * neutral VcsHostClient seam (shape reservation; no consumer yet). Reflection-only
 * so it stays pure JUnit5 (the :core ONE-BasePlatformTestCase-per-JVM invariant).
 */
class VcsHostClientDefaultBranchReviewerShapeTest {

    @Test
    fun `VcsHostClient declares getDefaultBranch returning ToolResult`() {
        val m = VcsHostClient::class.java.methods.firstOrNull { it.name == "getDefaultBranch" }
        assertTrue(m != null, "VcsHostClient must declare getDefaultBranch")
        // suspend fun adds a Continuation param: (repoName, continuation)
        assertEquals(2, m!!.parameterCount, "getDefaultBranch(repoName: String?) + Continuation")
    }

    @Test
    fun `VcsHostClient declares getDefaultReviewersForBranch`() {
        val m = VcsHostClient::class.java.methods.firstOrNull { it.name == "getDefaultReviewersForBranch" }
        assertTrue(m != null, "VcsHostClient must declare getDefaultReviewersForBranch")
        // (sourceBranch, targetBranch, repoName) + Continuation = 4
        assertEquals(4, m!!.parameterCount, "getDefaultReviewersForBranch(source, target, repoName) + Continuation")
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./gradlew :core:test --tests "*VcsHostClientDefaultBranchReviewerShapeTest*"`
Expected: FAIL (methods not yet declared → assertion fails on `m != null`).

- [ ] **Step 3: Add the two methods to the neutral interface (NO param defaults)**

In `VcsHostClient.kt`, near the existing branch ops (after `getBranches`/`createBranch`, around line 86–92), add:

```kotlin
    /**
     * The repository's configured default branch. Neutral over VCS host.
     * Shape-reservation (Phase 1c): no consumer resolves VcsHostClient yet.
     * NO default param value — see the MULTIPLE_DEFAULTS note at the top of this file.
     */
    suspend fun getDefaultBranch(repoName: String?): ToolResult<BranchData>

    /**
     * Default reviewers that apply to a sourceBranch -> targetBranch pair.
     * Neutral over VCS host. Shape-reservation (Phase 1c).
     */
    suspend fun getDefaultReviewersForBranch(
        sourceBranch: String,
        targetBranch: String,
        repoName: String?
    ): ToolResult<List<VcsUserData>>
```

- [ ] **Step 4: Implement the thin delegates in BitbucketServiceImpl**

In `BitbucketServiceImpl.kt`, near the existing `getBranches`/`createBranch`/`searchUsers` impls (mirror their structure exactly), add. Add `import com.workflow.orchestrator.core.bitbucket.RepoCoords` if not already imported:

```kotlin
    override suspend fun getDefaultBranch(repoName: String?): ToolResult<BranchData> {
        val api = client ?: return ToolResult(
            data = BranchData(id = "", displayId = "", latestCommit = null),
            summary = "Bitbucket not configured. Cannot fetch default branch.",
            isError = true, hint = "Set up Bitbucket connection in Settings."
        )
        val (projectKey, repoSlug) = resolveRepo(repoName) ?: return ToolResult(
            data = BranchData(id = "", displayId = "", latestCommit = null),
            summary = "Bitbucket project/repo not configured.", isError = true,
            hint = "Set Bitbucket project key and repo slug in Settings."
        )
        return when (val result = api.getDefaultBranch(projectKey, repoSlug)) {
            is ApiResult.Success -> {
                val b = result.data
                ToolResult.success(
                    BranchData(id = b.id, displayId = b.displayId, latestCommit = b.latestCommit, isDefault = b.isDefault),
                    "Default branch is '${b.displayId}'"
                )
            }
            is ApiResult.Error -> {
                log.warn("[BitbucketService] Failed to fetch default branch: ${result.message}")
                ToolResult(data = BranchData(id = "", displayId = "", latestCommit = null),
                    summary = "Error fetching default branch: ${result.message}", isError = true,
                    hint = "Check Bitbucket connection in Settings.")
            }
        }
    }

    override suspend fun getDefaultReviewersForBranch(
        sourceBranch: String,
        targetBranch: String,
        repoName: String?
    ): ToolResult<List<BitbucketUserData>> {
        val api = client ?: return ToolResult(
            data = emptyList(), summary = "Bitbucket not configured. Cannot fetch default reviewers.",
            isError = true, hint = "Set up Bitbucket connection in Settings."
        )
        val (projectKey, repoSlug) = resolveRepo(repoName) ?: return ToolResult(
            data = emptyList(), summary = "Bitbucket project/repo not configured.", isError = true,
            hint = "Set Bitbucket project key and repo slug in Settings."
        )
        return when (val result = api.getDefaultReviewersForBranch(RepoCoords(projectKey, repoSlug), sourceBranch, targetBranch)) {
            is ApiResult.Success -> {
                val users = result.data.map { u ->
                    BitbucketUserData(name = u.name, displayName = u.displayName, emailAddress = u.emailAddress)
                }
                ToolResult.success(users, "Found ${users.size} default reviewer(s) for $sourceBranch → $targetBranch")
            }
            is ApiResult.Error -> {
                log.warn("[BitbucketService] Failed to fetch default reviewers: ${result.message}")
                ToolResult(data = emptyList(), summary = "Error fetching default reviewers: ${result.message}", isError = true,
                    hint = "Check Bitbucket connection in Settings.")
            }
        }
    }
```

Note: the override return type uses the concrete `List<BitbucketUserData>` — identical to `List<VcsUserData>` because `VcsUserData` is a typealias of `BitbucketUserData`. Either spelling compiles; match the file's existing style (it returns `List<BitbucketUserData>` from `searchUsers`).

- [ ] **Step 5: Run the shape test + both module compiles**

Run: `./gradlew :core:test --tests "*VcsHostClientDefaultBranchReviewerShapeTest*" :pullrequest:compileKotlin`
Expected: PASS (test green) + `:pullrequest` compiles (proves the overrides satisfy the interface and no `MULTIPLE_DEFAULTS` error).

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/services/VcsHostClient.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/services/VcsHostClientDefaultBranchReviewerShapeTest.kt \
        pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/BitbucketServiceImpl.kt
git commit -m "feat(core): reserve VcsHostClient default-branch/reviewer ops (plugin-split 1c/A)

Neutral seam shape-reservation deferred from 0b-2 (these live on the lower
BitbucketBranchClient, not BitbucketService). Thin delegates in
BitbucketServiceImpl; no param defaults (MULTIPLE_DEFAULTS); no consumer yet."
```

---

## Task 3: PsiContextEnricher ModuleManager fallback (D4)

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/psi/PsiContextEnricher.kt`
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/vcs/GenerateCommitMessageAction.kt` (the one PRODUCTION consumer of the field)
- Modify (test): `core/src/test/kotlin/com/workflow/orchestrator/core/psi/PsiContextEnricherSingleReadActionTest.kt`
- Modify (test): `core/src/test/kotlin/com/workflow/orchestrator/core/psi/PsiContextEnricherTest.kt` (a SECOND test file that constructs `PsiContext` by field name — `mavenModule` at lines 14, 18, 30, 37 — MUST be renamed too or `:core:compileTestKotlin` fails)

⚠ **Cross-task coupling:** Task 3 and Task 4 both edit `GenerateCommitMessageAction.kt`. Run them SEQUENTIALLY (this plan orders 3 before 4) — never in parallel worktrees.
ℹ `PrService.kt` calls `enrich()` but reads only `psi.classAnnotations`, NOT the module field — so it needs no edit (confirms "one production consumer of the field").

**Interfaces:**
- Consumes: `ModuleUtilCore.findModuleForFile(vFile, project): Module?` (idiomatic here — already used in `WorkflowContextService`).
- Produces: `PsiContext.moduleName: String?` (renamed from `mavenModule`; now populated by Maven artifactId OR, as a fallback, the IDE module name for Gradle/other build systems).

**Invariants to preserve (pinned by `PsiContextEnricherSingleReadActionTest`):** ONE `readAction { }`; exactly one `findFileByPath`; `ProcessCanceledException` caught-and-rethrown; `PsiContext` is pure data (no `com.intellij.*` field types). The fallback runs INSIDE the existing read action (it's called from `detectMavenModule`, itself inside `enrich`'s `readAction`), so it adds neither a second read action nor a second file resolution.

- [ ] **Step 1: Extend the perf test with the new assertions (TDD)**

In `PsiContextEnricherSingleReadActionTest.kt`, add two assertions to the existing source-text test (find the test that uses `enricherSource()`), and add a field-rename guard. Append:

```kotlin
    @Test
    fun `enricher falls back to ModuleManager for non-Maven projects`() {
        val src = enricherSource()
        // The Gradle/other-build-system fallback must be wired via ModuleUtilCore.
        assertTrue(
            src.contains("findModuleForFile"),
            "detectModule must fall back to ModuleUtilCore.findModuleForFile when Maven detection yields null"
        )
        // Still exactly ONE read action and ONE file resolution after the change (B19/P2-22).
        assertEquals(1, Regex("""readAction\s*\{""").findAll(src).count(),
            "PsiContextEnricher must still contain exactly one readAction block")
        assertEquals(1, Regex(Regex.escape("findFileByPath")).findAll(src).count(),
            "PsiContextEnricher must still resolve the VirtualFile exactly once")
    }

    @Test
    fun `PsiContext exposes moduleName field`() {
        val fields = PsiContextEnricher.PsiContext::class.java.declaredFields.map { it.name }
        assertTrue("moduleName" in fields, "PsiContext.moduleName must exist (renamed from mavenModule)")
    }
```

(If the existing single-read-action / single-findFileByPath assertions live in their own `@Test`, leave them; the duplicates above are intentional regression guards for the change — they will still pass.)

- [ ] **Step 2: Run to confirm new assertions fail**

Run: `./gradlew :core:test --tests "*PsiContextEnricherSingleReadActionTest*"`
Expected: FAIL — `findModuleForFile` not in source; `moduleName` not a field.

- [ ] **Step 3: Rename the field and add the ModuleManager fallback**

In `PsiContextEnricher.kt`:

(a) Add import: `import com.intellij.openapi.module.ModuleUtilCore`

(b) Rename the DTO field `mavenModule` → `moduleName`:

```kotlin
    data class PsiContext(
        val className: String?,
        val classAnnotations: List<String>,
        val methodAnnotations: Map<String, List<String>>,
        val moduleName: String?,
        val isTestFile: Boolean
    )
```

(c) In `enrich`'s `readAction` body, update the call site `mavenModule = detectMavenModule(vFile)` → `moduleName = detectModule(vFile)`.

(d) Update `emptyContext()` field name `mavenModule = null` → `moduleName = null`.

(e) Replace `detectMavenModule` with `detectModule` (Maven first, then IDE-module fallback). Keep the PCE rethrow on BOTH branches:

```kotlin
    /**
     * Resolves the module this file belongs to. Maven artifactId when the project is
     * mavenized; otherwise the IDE module name (Gradle / other build systems / plain
     * IDEA modules) via ModuleUtilCore. Runs inside enrich()'s single read action.
     */
    private fun detectModule(vFile: VirtualFile): String? {
        val maven = try {
            val mavenManager = org.jetbrains.idea.maven.project.MavenProjectsManager.getInstance(project)
            if (!mavenManager.isMavenizedProject) {
                null
            } else {
                mavenManager.projects.find { mavenProject ->
                    VfsUtilCore.isAncestor(mavenProject.directoryFile, vFile, false)
                }?.mavenId?.artifactId
            }
        } catch (pce: ProcessCanceledException) {
            // Inside the single cancellable readAction (P2-22/B19): rethrow so the read
            // action machinery retries rather than completing with a null module.
            throw pce
        } catch (_: Exception) {
            // Maven plugin absent (optional dependency) or any Maven API failure -> fall through.
            null
        }
        if (maven != null) return maven
        return try {
            ModuleUtilCore.findModuleForFile(vFile, project)?.name
        } catch (pce: ProcessCanceledException) {
            throw pce
        } catch (_: Exception) {
            null
        }
    }
```

- [ ] **Step 4: Update the field consumers (production + the 2nd test file)**

In `GenerateCommitMessageAction.kt` (`buildCodeContext`), update both references:
- `if (ctx.mavenModule != null)` → `if (ctx.moduleName != null)`
- `append(" (module: ${ctx.mavenModule})")` → `append(" (module: ${ctx.moduleName})")`

In `PsiContextEnricherTest.kt`, rename all four occurrences:
- line 14: `mavenModule = null` → `moduleName = null`
- line 18: `assertNull(ctx.mavenModule)` → `assertNull(ctx.moduleName)`
- line 30: `mavenModule = "user-service"` → `moduleName = "user-service"`
- line 37: `assertEquals("user-service", ctx.mavenModule)` → `assertEquals("user-service", ctx.moduleName)`

Grep to be sure no reference to the old field name survives (the named-arg sites use `mavenModule =`, the read sites use `.mavenModule` — the word-boundary catches both while excluding unrelated locals like `mavenModuleDir` in `JavaRuntimeExecTool`):
Run: `grep -rnE "mavenModule\b" --include=*.kt core/` → Expected: ZERO matches after the edits. (`-E` makes `\b` a reliable word boundary across grep variants; `core/` scope + `\b` exclude unrelated `mavenModuleDir`/`mavenModuleRelPath` locals in `:agent`.)

- [ ] **Step 5: Run the perf test + module compile**

Run: `./gradlew :core:compileKotlin :core:test --tests "*PsiContextEnricherSingleReadActionTest*"`
Expected: PASS (all assertions, including the pre-existing pure-data / single-read-action ones).

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/psi/PsiContextEnricher.kt \
        core/src/main/kotlin/com/workflow/orchestrator/core/vcs/GenerateCommitMessageAction.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/psi/PsiContextEnricherSingleReadActionTest.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/psi/PsiContextEnricherTest.kt
git commit -m "feat(core): PSI module detection falls back to ModuleManager (plugin-split 1c/D4)

Maven-only detectMavenModule -> detectModule: Maven artifactId first, then
ModuleUtilCore.findModuleForFile for Gradle/other build systems. Field renamed
mavenModule -> moduleName. Single-readAction + PCE-rethrow invariants preserved."
```

---

## Task 4: Configurable commit-message format — engine + setting (D2, part 1 of 2)

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/prompts/CommitMessageFormat.kt`
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/prompts/CommitMessagePromptBuilder.kt`
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/settings/PluginSettings.kt`
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/vcs/GenerateCommitMessageAction.kt`
- Create (test): `core/src/test/kotlin/com/workflow/orchestrator/core/ai/prompts/CommitMessagePromptBuilderTest.kt`

**Interfaces:**
- Produces:
  - `enum class CommitMessageFormat { CONVENTIONAL, PLAIN }` with `companion fun fromSetting(value: String?): CommitMessageFormat` (`"plain"`→PLAIN, else CONVENTIONAL).
  - `CommitMessagePromptBuilder.buildMessages(..., format: CommitMessageFormat = CommitMessageFormat.CONVENTIONAL): List<ChatMessage>` (new trailing param, default = current behavior).
  - `PluginSettings.State.commitMessageFormat: String` (default `"conventional"`).
- Consumes: `com.workflow.orchestrator.core.ai.dto.ChatMessage`.

**No migration needed:** default `"conventional"` == current behavior for fresh installs AND upgraders (the field is simply absent from old XML and defaults to `"conventional"`). Conventional Commits is a generic industry standard, not a company convention; the de-convention is the new opt-in PLAIN escape hatch.

- [ ] **Step 1: Characterization test — pin the current Conventional output BEFORE refactor**

Create `CommitMessagePromptBuilderTest.kt`. First test pins that the default (Conventional) output is stable and carries its key markers; second test pins the default param is CONVENTIONAL:

```kotlin
package com.workflow.orchestrator.core.ai.prompts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommitMessagePromptBuilderTest {

    private val sampleDiff = "diff --git a/Foo.kt b/Foo.kt\n+fun bar() {}"

    @Test
    fun `conventional is the default format and keeps its markers and exact recent-commits wording`() {
        // Pass recentCommits so the shared context/diff tail is exercised — this pins
        // that the CONVENTIONAL prompt's RECENT COMMITS wording is byte-identical to
        // the pre-refactor text (the silent-regression guard the reviewers flagged).
        val msgs = CommitMessagePromptBuilder.buildMessages(
            diff = sampleDiff, ticketId = "PROJ-1",
            recentCommits = listOf("abc123 feat(x): something")
        )
        assertEquals(2, msgs.size)
        val system = msgs[0].content ?: ""
        val user = msgs[1].content ?: ""
        assertTrue(system.contains("Conventional Commits"), "default system message frames Conventional Commits")
        assertTrue(user.contains("TYPES:"), "conventional user message lists TYPES")
        assertTrue(user.contains("ISSUE TYPE → COMMIT TYPE"), "conventional user message has issue-type map")
        assertTrue(user.contains("PROJ-1 type(scope): imperative summary"), "conventional prepends the ticket id")
        // Verbatim — the CONVENTIONAL recent-commits line must NOT drift to the plain phrasing.
        assertTrue(
            user.contains("RECENT COMMITS (for tone and vocabulary only — the FORMAT and TYPES sections above are authoritative):"),
            "conventional keeps its exact RECENT COMMITS wording (no silent prompt drift)"
        )
        assertTrue(user.contains("DIFF:"), "diff section present")
    }

    @Test
    fun `plain format drops the type-scope prefix and issue-type map`() {
        val msgs = CommitMessagePromptBuilder.buildMessages(
            diff = sampleDiff, ticketId = "PROJ-1",
            format = CommitMessageFormat.PLAIN
        )
        val system = msgs[0].content ?: ""
        val user = msgs[1].content ?: ""
        assertFalse(system.contains("Conventional Commits"), "plain system message does not mention Conventional Commits")
        assertFalse(user.contains("TYPES:"), "plain user message omits the TYPES reference")
        assertFalse(user.contains("ISSUE TYPE → COMMIT TYPE"), "plain omits the issue-type map")
        assertFalse(user.contains("type(scope)"), "plain forbids the conventional type(scope) prefix anywhere")
        assertTrue(user.contains("DIFF:"), "diff section still present in plain mode")
    }

    @Test
    fun `fromSetting maps plain and defaults conventional`() {
        assertEquals(CommitMessageFormat.PLAIN, CommitMessageFormat.fromSetting("plain"))
        assertEquals(CommitMessageFormat.CONVENTIONAL, CommitMessageFormat.fromSetting("conventional"))
        assertEquals(CommitMessageFormat.CONVENTIONAL, CommitMessageFormat.fromSetting(null))
        assertEquals(CommitMessageFormat.CONVENTIONAL, CommitMessageFormat.fromSetting("garbage"))
    }
}
```

- [ ] **Step 2: Run to confirm it fails to compile (no `CommitMessageFormat`, no `format` param)**

Run: `./gradlew :core:test --tests "*CommitMessagePromptBuilderTest*"`
Expected: FAIL (compile error: unresolved `CommitMessageFormat`).

- [ ] **Step 3: Add the enum**

Create `CommitMessageFormat.kt`:

```kotlin
package com.workflow.orchestrator.core.ai.prompts

/**
 * Commit-message generation style. CONVENTIONAL keeps the Conventional-Commits +
 * issue-type-driven shape (the historical default); PLAIN is the de-convention
 * escape hatch for teams that don't use Conventional Commits.
 */
enum class CommitMessageFormat {
    CONVENTIONAL,
    PLAIN;

    companion object {
        /** Maps the [PluginSettings.State.commitMessageFormat] string; unknown/null -> CONVENTIONAL. */
        fun fromSetting(value: String?): CommitMessageFormat =
            if (value?.trim()?.equals("plain", ignoreCase = true) == true) PLAIN else CONVENTIONAL
    }
}
```

- [ ] **Step 4: Refactor the builder to branch on format (Conventional kept verbatim)**

In `CommitMessagePromptBuilder.kt`:

(a) Add a PLAIN system message constant next to `SYSTEM_MESSAGE`:

```kotlin
    private const val PLAIN_SYSTEM_MESSAGE = """You are an expert at writing clear, concise git commit messages. You analyze diffs and produce accurate messages that help teams understand what changed and why.

Output ONLY the raw commit message. No commentary, no markdown code blocks, no explanation."""
```

(b) Change `buildMessages` to take the format and select system + user builders. Rename the existing `buildUserMessage` to `buildConventionalUserMessage` (body unchanged except the shared tail extraction in (d)):

```kotlin
    fun buildMessages(
        diff: String,
        ticketId: String = "",
        filesSummary: String = "",
        recentCommits: List<String> = emptyList(),
        codeContext: String = "",
        candidateTickets: List<TicketCandidate> = emptyList(),
        format: CommitMessageFormat = CommitMessageFormat.CONVENTIONAL
    ): List<ChatMessage> {
        val systemMessage = if (format == CommitMessageFormat.PLAIN) PLAIN_SYSTEM_MESSAGE else SYSTEM_MESSAGE
        val userMessage = when (format) {
            CommitMessageFormat.CONVENTIONAL ->
                buildConventionalUserMessage(diff, ticketId, filesSummary, recentCommits, codeContext, candidateTickets)
            CommitMessageFormat.PLAIN ->
                buildPlainUserMessage(diff, ticketId, filesSummary, recentCommits, codeContext, candidateTickets)
        }
        return listOf(
            ChatMessage(role = "system", content = systemMessage),
            ChatMessage(role = "user", content = userMessage)
        )
    }
```

(c) Add the PLAIN user-message builder (no type(scope), no issue-type map):

```kotlin
    private fun buildPlainUserMessage(
        diff: String,
        ticketId: String,
        filesSummary: String,
        recentCommits: List<String>,
        codeContext: String,
        candidateTickets: List<TicketCandidate>
    ): String = buildString {
        appendLine("Generate a commit message for these changes.")
        appendLine()
        appendLine("FORMAT:")
        appendLine("<concise imperative summary, max 72 chars>")
        appendLine()
        appendLine("- Optional body: one bullet per logical change, explaining WHAT changed and WHY")
        appendLine("- Group related edits into one bullet; trivial changes need just a summary line")
        appendLine()
        appendLine("RULES:")
        appendLine("- Imperative mood: 'add' not 'added', 'fix' not 'fixed'")
        appendLine("- Do NOT use a Conventional-Commits type prefix such as 'feat:' or 'fix:'")
        appendLine("- Body explains WHY the change was made, not just what lines changed")
        appendLine("- AVOID: passive voice, 'This commit/change' phrasing")
        if (ticketId.isNotBlank() || candidateTickets.isNotEmpty()) {
            appendLine("- You MAY reference the ticket if it clarifies the change, but do not force any prefix format.")
        }
        appendLine()
        appendContextAndDiff(recentCommits, codeContext, filesSummary, diff, "the FORMAT/RULES above are authoritative")
    }
```

> ⚠ Note (deliberate): PLAIN omits the CANDIDATE TICKETS / TICKET CONTEXT block that CONVENTIONAL builds — PLAIN loses ticket summary/description context, not just the Conventional coupling. This is an accepted trade-off for the escape-hatch mode; the soft "you MAY reference the ticket" line is intentional.

(d) Extract the shared tail (RECENT COMMITS / CODE CONTEXT / CHANGED FILES / DIFF) from the existing Conventional builder into a private helper, and call it at the end of `buildConventionalUserMessage` in place of the inline tail (replace the existing lines that append RECENT COMMITS through DIFF). **The helper takes an `authoritativeRef` param so the CONVENTIONAL prompt stays byte-identical** (it passes its verbatim "the FORMAT and TYPES sections above are authoritative"; PLAIN passes "the FORMAT/RULES above are authoritative"):

```kotlin
    private fun StringBuilder.appendContextAndDiff(
        recentCommits: List<String>,
        codeContext: String,
        filesSummary: String,
        diff: String,
        authoritativeRef: String
    ) {
        if (recentCommits.isNotEmpty()) {
            appendLine("RECENT COMMITS (for tone and vocabulary only — $authoritativeRef):")
            recentCommits.forEach { appendLine("  $it") }
            appendLine()
        }
        if (codeContext.isNotBlank()) {
            appendLine("CODE CONTEXT:")
            appendLine(codeContext)
            appendLine()
        }
        if (filesSummary.isNotBlank()) {
            appendLine("CHANGED FILES: $filesSummary")
            appendLine()
        }
        appendLine("DIFF:")
        append(diff)
    }
```

In `buildConventionalUserMessage`, the final call MUST preserve the original wording verbatim:

```kotlin
        appendContextAndDiff(recentCommits, codeContext, filesSummary, diff, "the FORMAT and TYPES sections above are authoritative")
```

**Behavior-preservation is required, not optional:** the CONVENTIONAL prompt bytes the LLM sees must be unchanged for existing users. The strengthened characterization test (Step 1) passes `recentCommits` and asserts the exact "...the FORMAT and TYPES sections above are authoritative..." line, so any drift is caught. PLAIN uses the "FORMAT/RULES above" phrasing because it has no TYPES section.

- [ ] **Step 5: Run the builder tests**

Run: `./gradlew :core:test --tests "*CommitMessagePromptBuilderTest*"`
Expected: PASS (Conventional markers intact, Plain drops them, fromSetting maps correctly).

- [ ] **Step 6: Add the setting**

In `PluginSettings.kt`, in the "Branching & PRs" cluster (after `enableAiTitleGeneration`, ~line 105), add:

```kotlin
        /** AI commit-message style: "conventional" (Conventional Commits, default) or "plain". */
        var commitMessageFormat by string("conventional")
```

- [ ] **Step 7: Thread the format into the action**

In `GenerateCommitMessageAction.kt` `generateMessage(...)`, where it calls `CommitMessagePromptBuilder.buildMessages(...)` (~line 300), add the format argument (it already holds `settings = PluginSettings.getInstance(project)`):

```kotlin
        val messages = CommitMessagePromptBuilder.buildMessages(
            diff = attemptDiff,
            ticketId = ticketId,
            filesSummary = filesSummary,
            recentCommits = recentCommits,
            codeContext = codeContext,
            candidateTickets = candidateTickets,
            format = CommitMessageFormat.fromSetting(settings.state.commitMessageFormat)
        )
```

Add import: `import com.workflow.orchestrator.core.ai.prompts.CommitMessageFormat`.

- [ ] **Step 8: Full :core compile + test**

Run: `./gradlew :core:compileKotlin :core:test --tests "*CommitMessagePromptBuilderTest*"`
Expected: PASS. (Defer the full `:core:test` to the controller's per-task verify.)

- [ ] **Step 9: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/ai/prompts/CommitMessageFormat.kt \
        core/src/main/kotlin/com/workflow/orchestrator/core/ai/prompts/CommitMessagePromptBuilder.kt \
        core/src/main/kotlin/com/workflow/orchestrator/core/settings/PluginSettings.kt \
        core/src/main/kotlin/com/workflow/orchestrator/core/vcs/GenerateCommitMessageAction.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/ai/prompts/CommitMessagePromptBuilderTest.kt
git commit -m "feat(core): configurable commit-message format (plugin-split 1c/D2)

CommitMessageFormat {CONVENTIONAL, PLAIN}; builder branches on it. Default
conventional == current behavior (no migration). PLAIN drops the type(scope)
prefix + Jira issue-type map — escape hatch for non-Conventional-Commits shops.
Setting: PluginSettings.commitMessageFormat (UI wired in the next task)."
```

---

## Task 5: Commit-format settings UI (D2, part 2 of 2)

**Files:**
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/settings/JiraWorkflowConfigurable.kt`

**Interfaces:**
- Consumes: `PluginSettings.State.commitMessageFormat` (Task 4).
- Produces: a user-facing combo box. (No new test — this Configurable has no headless test harness; the binding is verified by compile + the runIde smoke. The setting's parsing/behavior is already covered by Task 4's tests.)

**Design note:** `JiraWorkflowConfigurable` is the established home for generic VCS-AI settings (`prTitleFormat`, `enableAiTitleGeneration`) and ships inside A. Add a dedicated `group("Commit Messages")` rather than overloading the "Pull Requests" group.

- [ ] **Step 1: Add the group with the format combo box**

In `JiraWorkflowConfigurable.kt`, add a new `group(...)` inside the `panel { ... }` block (place it after the "Pull Requests" group; mirror the existing `comboBox(...).bindItem(...)` pattern used elsewhere in this codebase, e.g. `TelemetryConfigurable`'s log-level combo):

```kotlin
            group("Commit Messages") {
                row("AI commit message format:") {
                    comboBox(listOf("conventional", "plain"))
                        .bindItem(
                            { settings.state.commitMessageFormat ?: "conventional" },
                            { settings.state.commitMessageFormat = it ?: "conventional" }
                        )
                        .comment(
                            "conventional = Conventional Commits (type(scope): summary, issue-type aware). " +
                                "plain = concise imperative summary with no type prefix."
                        )
                }
            }
```

(Confirm `settings` is the `PluginSettings.getInstance(project)` handle already in scope — it is, per the existing `prTitleFormat` binding in this file.)

- [ ] **Step 2: Compile :jira**

Run: `./gradlew :jira:compileKotlin`
Expected: PASS (combo box binds `commitMessageFormat`).

- [ ] **Step 3: Sanity-run the settings/serialization tests if any reference this configurable**

Run: `./gradlew :jira:test`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/settings/JiraWorkflowConfigurable.kt
git commit -m "feat(jira): settings UI for commit-message format (plugin-split 1c/D2)

Adds a Commit Messages group with a conventional/plain combo bound to
PluginSettings.commitMessageFormat."
```

---

## Task 6: Sprint feature-detect — core plumbing (D3, part 1 of 2)

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/toolwindow/WorkflowTabProvider.kt` (add `isAvailable` default)
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/events/WorkflowEvent.kt` (add event)
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/toolwindow/WorkflowToolWindowFactory.kt` (filter + subscribe)
- Create (test): `core/src/test/kotlin/com/workflow/orchestrator/core/toolwindow/WorkflowTabProviderAvailabilityTest.kt`

**Interfaces:**
- Produces:
  - `WorkflowTabProvider.isAvailable(project: Project): Boolean = true` (default — non-breaking for all 8 implementors; MUST be cheap/non-blocking, it's called on the EDT during tab build).
  - `WorkflowEvent.TabAvailabilityChanged(tabTitle: String)` (neutral signal — a tab's availability changed; rebuild).
  - `WorkflowToolWindowFactory.isTabAvailable(provider, project): Boolean` (extracted internal predicate for unit-testing the filter).
- Consumes: `EventBus` (project `@Service`, `SharedFlow<WorkflowEvent>`); the existing `rebuildTabs` lambda.

**This task is inert on its own:** with every provider defaulting `isAvailable=true`, nothing is hidden, and with no emitter of `TabAvailabilityChanged` yet, the subscription never fires. Task 7 (jira) activates it. This keeps the core change independently reviewable + behavior-preserving.

- [ ] **Step 1: Write the failing core test**

Create `WorkflowTabProviderAvailabilityTest.kt` (pure JUnit5 + mockk — NO `BasePlatformTestCase`):

```kotlin
package com.workflow.orchestrator.core.toolwindow

import com.intellij.openapi.project.Project
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.JComponent
import javax.swing.JPanel

class WorkflowTabProviderAvailabilityTest {

    private val project = mockk<Project>(relaxed = true)

    private fun provider(title: String, available: Boolean) = object : WorkflowTabProvider {
        override val tabTitle = title
        override val order = 0
        override fun createPanel(project: Project): JComponent = JPanel()
        override fun isAvailable(project: Project) = available
    }

    @Test
    fun `isAvailable defaults to true when not overridden`() {
        val p = object : WorkflowTabProvider {
            override val tabTitle = "X"
            override val order = 0
            override fun createPanel(project: Project): JComponent = JPanel()
        }
        assertTrue(p.isAvailable(project))
    }

    @Test
    fun `isTabAvailable null provider shows, false provider hides`() {
        // A default tab with no matching provider should remain visible.
        assertTrue(WorkflowToolWindowFactory.isTabAvailable(null, project))
        assertTrue(WorkflowToolWindowFactory.isTabAvailable(provider("Sprint", true), project))
        assertEquals(false, WorkflowToolWindowFactory.isTabAvailable(provider("Sprint", false), project))
    }

    @Test
    fun `first visible default tab is promoted to eager when the first is hidden`() {
        // Mirrors buildTabs' selection of the eager tab: filter by availability, take index 0.
        // Guards the bug where hiding Sprint (order 0) left NO eagerly-materialized tab.
        val providers = mapOf(
            "Sprint" to provider("Sprint", false),
            "PR" to provider("PR", true),
        )
        val orderedTitles = listOf("Sprint", "PR")
        val visible = orderedTitles.filter { WorkflowToolWindowFactory.isTabAvailable(providers[it], project) }
        assertEquals(listOf("PR"), visible)
        assertEquals("PR", visible.first(), "PR becomes the eagerly-materialized first tab when Sprint is hidden")
    }
}
```

- [ ] **Step 2: Run to confirm it fails**

Run: `./gradlew :core:test --tests "*WorkflowTabProviderAvailabilityTest*"`
Expected: FAIL (`isAvailable` / `isTabAvailable` unresolved).

- [ ] **Step 3: Add the `isAvailable` default to the EP interface**

In `WorkflowTabProvider.kt`:

```kotlin
interface WorkflowTabProvider {
    val tabTitle: String
    val order: Int
    fun createPanel(project: Project): JComponent

    /**
     * Whether this tab should be shown for the given project. Default true.
     * MUST be cheap and non-blocking — called on the EDT during tab building.
     * Implementations that depend on a remote capability must read a cached
     * verdict and trigger any probe asynchronously (see JiraAgileCapabilityService),
     * then emit WorkflowEvent.TabAvailabilityChanged to request a rebuild.
     */
    fun isAvailable(project: Project): Boolean = true

    companion object {
        val EP_NAME = ExtensionPointName.create<WorkflowTabProvider>(
            "com.workflow.orchestrator.tabProvider"
        )
    }
}
```

- [ ] **Step 4: Add the neutral event**

In `WorkflowEvent.kt`, add a subtype (mirror the existing `data class … : WorkflowEvent()` style):

```kotlin
    /**
     * A tool-window tab's availability changed (e.g. an async capability probe resolved).
     * The tool-window factory rebuilds its tabs in response. tabTitle is advisory.
     */
    data class TabAvailabilityChanged(val tabTitle: String) : WorkflowEvent()
```

- [ ] **Step 5: Add the extracted predicate + apply the filter + subscribe in the factory**

In `WorkflowToolWindowFactory.kt`:

(a) Add the testable predicate (companion so the unit test can call it without a factory instance):

```kotlin
    companion object {
        /** A default tab with no provider stays visible; otherwise honor the provider's isAvailable. */
        @JvmStatic
        fun isTabAvailable(provider: WorkflowTabProvider?, project: Project): Boolean =
            provider?.isAvailable(project) ?: true
    }
```

(b) In `buildTabs`, filter the default-tabs loop (line ~360) and the extension-tabs filter (line ~387):

⚠ **CRITICAL — eager-materialize the first VISIBLE tab, not `order == 0`.** The current loop eagerly materializes `tab.order == 0` (Sprint). If you merely `return@forEach` on a hidden Sprint, NO default tab is materialized eagerly — the auto-selected first content (PR) is a `LazyTabPlaceholder`, and because the `selectionChanged` listener early-returns during `rebuildInProgress`, PR stays stuck on "Loading…" forever (the exact non-Software-Jira scenario this feature targets). Instead, FILTER first, then materialize index 0 of the survivors:

```kotlin
        // Add default tabs (matched with providers by title). Hide any whose provider
        // reports unavailable (e.g. Sprint on non-Software Jira), and eagerly materialize
        // the FIRST VISIBLE tab so the tool window never opens on a perpetual "Loading…"
        // placeholder when the natural first tab (Sprint, order 0) is hidden.
        val visibleDefaults = defaultTabs.filter { isTabAvailable(providers[it.title], project) }
        visibleDefaults.forEachIndexed { index, tab ->
            val panel = if (index == 0) {
                materializeTab(project, tab, providers, materializedTabs)
            } else {
                LazyTabPlaceholder()
            }
            val content = ContentFactory.getInstance().createContent(panel, tab.title, false)
            content.isCloseable = false
            if (panel is Disposable) {
                content.setDisposer(panel)
            }
            contentManager.addContent(content)
        }
```

(This replaces the entire existing `defaultTabs.forEach { tab -> … }` block — the `isFirstTab = tab.order == 0` logic is gone, replaced by `index == 0` over the filtered list.)

Extension tabs — add `isAvailable` to the existing filter:

```kotlin
        providers.filter { it.key !in defaultTitles && it.value.isAvailable(project) }
            .values
            .sortedBy { it.order }
            .forEach { provider ->
                // ... unchanged body ...
            }
```

(c) In `createToolWindowContent`, register the `EventBus` subscription that rebuilds on `TabAvailabilityChanged`. ⚠ **Place this BEFORE the first `rebuildTabs()` call (line 58).** `EventBus` has `replay = 0`; the first `rebuildTabs()` triggers `SprintTabProvider.isAvailable` → the async probe. If the collector were registered later, a fast probe `emit` could be dropped and the Sprint hide lost until the next settings rebuild. Subscribing first guarantees the collector is live before any probe can emit. Mirror the local-scope-registered-to-disposable pattern already used by `setupActiveTicketBar`:

```kotlin
        // Rebuild tabs when a tab's availability is resolved asynchronously
        // (e.g. the Jira-Agile capability probe completes -> hide/show Sprint).
        // Registered BEFORE the first rebuildTabs() so the replay=0 EventBus can't
        // drop a probe result emitted during that first build.
        val availabilityScope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
        availabilityScope.launch {
            project.getService(com.workflow.orchestrator.core.events.EventBus::class.java)
                .events
                .collect { event ->
                    if (event is com.workflow.orchestrator.core.events.WorkflowEvent.TabAvailabilityChanged) {
                        val cm = toolWindow.contentManager
                        val selectedTab = cm.selectedContent?.displayName
                        rebuildTabs()
                        // Restore the previous selection if it still exists; otherwise select
                        // the first surviving content so we never sit on a removed/blank tab.
                        val restored = selectedTab?.let { prev -> cm.contents.firstOrNull { it.displayName == prev } }
                        if (restored != null) {
                            cm.setSelectedContent(restored)
                        } else {
                            cm.contents.firstOrNull()?.let { cm.setSelectedContent(it) }
                        }
                    }
                }
        }
        com.intellij.openapi.util.Disposer.register(toolWindow.disposable) { availabilityScope.cancel() }

        rebuildTabs()  // first build — collector above is already live
```

Concretely: move the existing `rebuildTabs()` call (currently at line 58) to AFTER this subscription block, OR insert the subscription block immediately after the `rebuildTabs` lambda is defined (line ~56) and before the existing `rebuildTabs()` invocation. Do NOT leave two `rebuildTabs()` calls — there is exactly one initial build.

(`CoroutineScope`, `SupervisorJob`, `Dispatchers`, `launch` are already imported via `import kotlinx.coroutines.*` at the top.)

ℹ Adding the `WorkflowEvent.TabAvailabilityChanged` sealed subtype does NOT break any exhaustive `when` — every existing consumer (`DevStatusCacheInvalidator`, `WorkflowEventMirror`, etc.) has an `else` branch. No other edit needed for the new event.

- [ ] **Step 6: Run the test + compile**

Run: `./gradlew :core:compileKotlin :core:test --tests "*WorkflowTabProviderAvailabilityTest*"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/toolwindow/WorkflowTabProvider.kt \
        core/src/main/kotlin/com/workflow/orchestrator/core/events/WorkflowEvent.kt \
        core/src/main/kotlin/com/workflow/orchestrator/core/toolwindow/WorkflowToolWindowFactory.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/toolwindow/WorkflowTabProviderAvailabilityTest.kt
git commit -m "feat(core): WorkflowTabProvider.isAvailable + reactive tab rebuild (plugin-split 1c/D3)

Adds isAvailable (default true) to the tab EP; factory filters tabs by it and
rebuilds on the new neutral WorkflowEvent.TabAvailabilityChanged. Inert until a
provider overrides isAvailable (jira activates it next)."
```

---

## Task 7: Sprint feature-detect — Jira capability probe (D3, part 2 of 2)

**Files:**
- Create: `jira/src/main/kotlin/com/workflow/orchestrator/jira/service/JiraAgileCapabilityService.kt`
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/SprintTabProvider.kt`
- Create (test): `jira/src/test/kotlin/com/workflow/orchestrator/jira/service/JiraAgileCapabilityClassifyTest.kt`
- Possibly modify: `src/main/resources/META-INF/plugin.xml` ONLY if `@Service` auto-registration requires it (project-level `@Service` classes are auto-registered by annotation — no plugin.xml entry needed; do NOT add one).

**Interfaces:**
- Consumes: `JiraServiceImpl.getInstance(project).getApiClient(): JiraApiClient?`; `JiraApiClient.getBoards(boardType, nameFilter): ApiResult<List<JiraBoard>>`; `ApiResult.{Success,Error}`; `ErrorType.NOT_FOUND`; `ConnectionSettings`/`PluginSettings.connections.jiraUrl`; `EventBus.emit(WorkflowEvent.TabAvailabilityChanged("Sprint"))`; injected `cs: CoroutineScope`.
- Produces:
  - `JiraAgileCapabilityService` (`@Service(Service.Level.PROJECT)`) with `fun agileAvailableOrProbe(): Boolean?` (cached tri-state, non-blocking, launches probe on unknown) and `companion fun getInstance(project)`.
  - `JiraAgileCapabilityService.classifyProbe(result: ApiResult<*>): Boolean?` — pure: `Success`→true, `Error(NOT_FOUND)`→false, any other error→null.
  - `SprintTabProvider.isAvailable` override.

**Tri-state rationale (the transient-error trap):** only a genuine 404 (`ErrorType.NOT_FOUND`) means "Agile not on this deployment" → hide. A network/auth/5xx error is transient → return `null` (unknown) → keep the tab shown and re-probe later. Never hide on a transient failure.

- [ ] **Step 1: Write the pure classify test (TDD)**

Create `JiraAgileCapabilityClassifyTest.kt` (pure JUnit5 — NO fixture):

```kotlin
package com.workflow.orchestrator.jira.service

import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class JiraAgileCapabilityClassifyTest {

    @Test
    fun `success means agile available`() {
        assertEquals(true, JiraAgileCapabilityService.classifyProbe(ApiResult.Success(emptyList<Any>())))
    }

    @Test
    fun `not found means agile unavailable`() {
        assertEquals(false, JiraAgileCapabilityService.classifyProbe(ApiResult.Error(ErrorType.NOT_FOUND, "no agile")))
    }

    @Test
    fun `transient errors are unknown, not unavailable`() {
        assertNull(JiraAgileCapabilityService.classifyProbe(ApiResult.Error(ErrorType.NETWORK_ERROR, "down")))
        assertNull(JiraAgileCapabilityService.classifyProbe(ApiResult.Error(ErrorType.AUTH_FAILED, "401")))
        assertNull(JiraAgileCapabilityService.classifyProbe(ApiResult.Error(ErrorType.SERVER_ERROR, "500")))
    }
}
```

(Confirm the exact `ErrorType` member names by reading `core/.../model/ErrorType.kt`; use the real ones — `NOT_FOUND`, `NETWORK_ERROR`, `AUTH_FAILED`, `SERVER_ERROR` are referenced in `JiraApiClient`.)

- [ ] **Step 2: Run to confirm it fails**

Run: `./gradlew :jira:test --tests "*JiraAgileCapabilityClassifyTest*"`
Expected: FAIL (`JiraAgileCapabilityService` unresolved).

- [ ] **Step 3: Create the capability service**

Create `JiraAgileCapabilityService.kt`:

```kotlin
package com.workflow.orchestrator.jira.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Caches whether the connected Jira exposes the Agile (/rest/agile/1.0) board API.
 * Used to hide the Sprint tab on non-Software (Core/Service-Management) Jira.
 *
 * Tri-state verdict: true = available, false = unavailable (404 -> hide),
 * null = unknown (never probed for this URL, or a transient error). Hidden ONLY
 * on a definitive false. The probe runs async on the injected scope; on a
 * definitive verdict it emits TabAvailabilityChanged so the tool window rebuilds.
 */
@Service(Service.Level.PROJECT)
class JiraAgileCapabilityService(
    private val project: Project,
    private val cs: CoroutineScope,
) {
    private val log = Logger.getInstance(JiraAgileCapabilityService::class.java)

    @Volatile private var probedUrl: String? = null
    @Volatile private var verdict: Boolean? = null
    @Volatile private var probing: Boolean = false

    /**
     * Non-blocking. Returns the cached verdict for the current Jira URL, or null
     * (unknown) while launching an async probe. Safe to call on the EDT.
     */
    fun agileAvailableOrProbe(): Boolean? {
        val url = PluginSettings.getInstance(project).connections.jiraUrl.orEmpty()
        if (url.isBlank()) return null
        if (url == probedUrl) return verdict
        launchProbe(url)
        return null
    }

    @Synchronized
    private fun launchProbe(url: String) {
        if (probing) return
        probing = true
        cs.launch(Dispatchers.IO) {
            try {
                val result = runCatching {
                    val apiClient = JiraServiceImpl.getInstance(project).getApiClient()
                    if (apiClient == null) null else classifyProbe(apiClient.getBoards())
                }.getOrNull()
                if (result != null) {
                    probedUrl = url
                    verdict = result
                    log.info("[Jira:Agile] Agile capability for '$url' = $result")
                    project.service<EventBus>().emit(WorkflowEvent.TabAvailabilityChanged("Sprint"))
                } else {
                    log.debug("[Jira:Agile] Agile probe inconclusive for '$url' (transient); will retry")
                }
            } finally {
                // finally so an emit/service-resolution failure (e.g. during disposal) can't
                // pin probing=true and block all future probes for this instance.
                probing = false
            }
        }
    }

    companion object {
        fun getInstance(project: Project): JiraAgileCapabilityService =
            project.service()

        /** Pure: Success -> true; NOT_FOUND -> false; any other error -> null (unknown). */
        fun classifyProbe(result: ApiResult<*>): Boolean? = when (result) {
            is ApiResult.Success -> true
            is ApiResult.Error -> if (result.type == ErrorType.NOT_FOUND) false else null
        }
    }
}
```

(Confirm the `ApiResult.Error` field name for the error type — the test/JiraApiClient construct `ApiResult.Error(ErrorType.NOT_FOUND, "...")`; read `core/.../model/ApiResult.kt` to confirm the property is `type` and fix the `when` accordingly.)

- [ ] **Step 4: Run the classify test**

Run: `./gradlew :jira:test --tests "*JiraAgileCapabilityClassifyTest*"`
Expected: PASS.

- [ ] **Step 5: Override `isAvailable` in SprintTabProvider**

In `SprintTabProvider.kt`, add the override (hide only on a definitive false; unconfigured Jira keeps the existing "connect to Jira" empty state, so return true there):

```kotlin
    override fun isAvailable(project: Project): Boolean {
        val jiraUrl = PluginSettings.getInstance(project).connections.jiraUrl
        // Unconfigured Jira: show the tab so the user sees the "connect to Jira" empty state.
        if (jiraUrl.isNullOrBlank()) return true
        // Hide ONLY when the Agile API is definitively unavailable (404). Unknown/transient -> show.
        return JiraAgileCapabilityService.getInstance(project).agileAvailableOrProbe() != false
    }
```

Add the import: `import com.workflow.orchestrator.jira.service.JiraAgileCapabilityService` — `SprintTabProvider` is in `jira.ui`, the new service is in `jira.service` (NOT same package; it already imports siblings like `JiraServiceImpl` from `.service`). `PluginSettings` import is already present.

- [ ] **Step 6: Compile :jira + run jira tests**

Run: `./gradlew :jira:compileKotlin :jira:test --tests "*JiraAgileCapability*"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/service/JiraAgileCapabilityService.kt \
        jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/SprintTabProvider.kt \
        jira/src/test/kotlin/com/workflow/orchestrator/jira/service/JiraAgileCapabilityClassifyTest.kt
git commit -m "feat(jira): hide Sprint tab on non-Agile Jira via cached async probe (plugin-split 1c/D3)

JiraAgileCapabilityService probes /rest/agile/1.0/board off-EDT, caches a
tri-state verdict per Jira URL (404 -> unavailable; transient -> unknown/show),
and emits TabAvailabilityChanged so the tool window rebuilds. SprintTabProvider
hides only on a definitive 404."
```

---

## Final Verification (controller, after all 7 tasks)

- [ ] **Full green gate (authoritative — defeat task caching):**

```bash
./gradlew :jira:clean :jira:test :core:clean :core:test :pullrequest:test --rerun-tasks
./gradlew :konsist:test
./gradlew verifyPlugin
```
Expected: all PASS. (`:core:test` must show ZERO "Indexing timeout" — proves no stray `BasePlatformTestCase` crept in.)

- [ ] **detekt (this-phase-clean check):**

```bash
./gradlew :core:detekt :jira:detekt :pullrequest:detekt --rerun-tasks
```
Expected: any failures are ONLY the 73 pre-existing 0b-3 issues. NO new issues attributable to Phase 1c files. (If a 1c file is flagged, fix it — do not baseline.)

- [ ] **Grep cleanliness:**
```bash
grep -rn "isValidBranchName" --include=*.kt .        # expect 0
grep -rnE "mavenModule\b" --include=*.kt core/       # expect 0 (-E + \b + core/ scope avoids false-positive on mavenModuleDir in :agent + worktree copies)
```

- [ ] **PENDING-USER runIde smoke (Windows; Mac can't runIde — Ultimate license):** (1) commit-format combo appears under Settings → Workflow Orchestrator → Commit Messages and persists; (2) `plain` produces a message with no `type(scope):` prefix; (3) on a non-Software Jira project the Sprint tab disappears shortly after tool-window open (and reappears for a Software project); (4) a transient Jira outage does NOT hide the Sprint tab. Add these to the existing Windows smoke checklist.

---

## Self-Review (run after writing; fix inline)

1. **Spec coverage:** D1 (Task 1) ✓, D2 (Tasks 4+5) ✓, D3 (Tasks 6+7) ✓, D4 (Task 3) ✓, Cluster-A residual (Task 2) ✓. All five Phase 1c items mapped.
2. **Placeholders:** none — every code step shows real code; every run step shows the command + expected result.
3. **Type consistency:** `CommitMessageFormat` (Task 4) used identically in Tasks 4 & 5 & the action; `TabAvailabilityChanged(tabTitle: String)` defined in Task 6, emitted in Task 7; `isAvailable(project)` signature identical across interface (Task 6) + override (Task 7); `moduleName` field (Task 3) renamed consistently across DTO + production consumer + BOTH test files (`PsiContextEnricherSingleReadActionTest` AND `PsiContextEnricherTest`); `getDefaultBranch`/`getDefaultReviewersForBranch` signatures identical between interface (Task 2 Step 3) and impl (Task 2 Step 4); `appendContextAndDiff(..., authoritativeRef)` called with verbatim Conventional text + neutral Plain text.

## Review history

Round 1 (3 independent opus lenses — code-accuracy / completeness / skeptic) ran against the actual code. Verdicts: READY-WITH-FIXES / READY-WITH-FIXES / NOT-READY. Consensus fixes folded into this revision:
- **CRITICAL (all 3):** hiding Sprint (order 0) left no eager tab → perpetual "Loading…" for non-Software Jira → buildTabs now eager-materializes the first VISIBLE tab + unit test added.
- **CRITICAL (all 3):** field rename broke a 2nd test file `PsiContextEnricherTest.kt` → added to Task 3 + grep gate fixed.
- **CRITICAL (2):** PLAIN test asserted absence of `type(scope)` while the builder emitted it → reworded the PLAIN rule.
- **CRITICAL (1):** wrong `ToolResult` import in Task 2 test → removed (same-package).
- **CRITICAL (1):** missing `JiraAgileCapabilityService` import in SprintTabProvider → added.
- **IMPORTANT:** Conventional prompt wording would silently drift → parameterized `authoritativeRef`, Conventional kept byte-identical, characterization test strengthened to pin it.
- **IMPORTANT:** dead `log`/`Logger`/`TICKET_PATTERN` after Task 1 → explicit deletion (detekt-clean).
- **MINOR folded:** `probing=false` in `finally`; EventBus collector subscribed before first build (replay=0 race); defensive select-first-content; documented branch-agnostic `getDefaultReviewers` non-reservation, PLAIN ticket-context drop, and `WorkflowEvent` exhaustiveness safety.
