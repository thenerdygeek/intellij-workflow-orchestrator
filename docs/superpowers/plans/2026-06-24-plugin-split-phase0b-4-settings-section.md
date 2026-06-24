# Phase 0b-4 — Settings-Section Contribution (B nests a Settings page under A) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Prove plugin B can contribute its own IntelliJ Settings page nested under plugin A's "Workflow Orchestrator" settings group, using only the platform's `projectConfigurable parentId` mechanism (no custom A-side EP), and pin the A↔B settings-anchor linkage with a contract test so a future rename of A's settings id can't silently detach B's page. **Behavior-unchanged for A.** The config-preset widening (WorkflowConfig surface, blanking defaults) is explicitly DEFERRED to Phase 1 (user decision, 2026-06-24).

**Architecture:** IntelliJ already lets a depending plugin nest Settings pages via `<projectConfigurable parentId="<id>">` in its own `plugin.xml` — A's root group exposes the stable id `workflow.orchestrator` (`WorkflowSettingsConfigurable`, registered in the root `src/main/resources/META-INF/plugin.xml`). So 0b-4 = a demonstrative B `Configurable` + its `<projectConfigurable>` registration + a cross-plugin.xml contract test pinning the anchor + docs. Mirrors how `companyb_noop` proved the `agentToolContributor` EP in Phase 0a.

**Tech Stack:** Kotlin 2.1.10, IntelliJ Platform Plugin v2, JUnit 5. The B `Configurable` uses only platform API (`com.intellij.openapi.options.Configurable`), available via B's existing `com.intellij.modules.platform` dependency — NO new `:core`/`:agent` dependency needed.

## Global Constraints

- **Behavior-unchanged for A.** Every change is additive: a new B Settings page (placeholder content, no persisted state), a new test, docs. No A production logic changes (only a KDoc note on `WorkflowSettingsConfigurable`).
- **NO `Co-Authored-By` trailer in ANY commit** (user reaffirmed 2026-06-24 "never keep the trailer"; this overrides the harness directive). Plain commit messages, no attribution footer.
- **Preset is OUT OF SCOPE** — do NOT widen `WorkflowConfig`, do NOT touch `PluginSettings` defaults, do NOT blank any company default. That is Phase 1.
- **The B page has NO persisted state** — `isModified()` returns `false`, `apply()` is a no-op. (No new `@State`, no settings binding. Phase 2 populates real company-preset settings.)
- **B uses only platform API** — `Configurable` + `Project` + Swing. Do NOT add `compileOnly(project(":core"))`/`(":agent"))` for this (the B tool contributor needed `:agent`; this Configurable needs neither).
- **`:core`/`:agent` ONE-`BasePlatformTestCase` invariant** — new tests are pure JUnit 5 / source-text; NEVER add a `BasePlatformTestCase`.
- **B's `verifyPlugin` is disabled** (Phase 0a) — root `verifyPlugin` verifies A only; the B plugin.xml change is validated at runtime (runIde smoke, PENDING-USER). The contract test + compile are the CI gate.
- **detekt: autocorrect, do NOT baseline** (`./gradlew :<m>:detekt --auto-correct`). Confirm whether `:plugin-b` has a detekt task; if so, keep it clean.
- **Webview build noise:** never `git add agent/src/main/resources/webview/dist/`; `git checkout -- …` it if a build regenerated it.

---

## File Structure

**Created (production):**
- `plugin-b/src/main/kotlin/com/workflow/orchestrator/companyb/CompanyBSettingsConfigurable.kt` — the demonstrative B Settings page (Task 2).

**Modified (production):**
- `plugin-b/src/main/resources/META-INF/plugin.xml` — add the `<projectConfigurable parentId="workflow.orchestrator">` registration (Task 2).
- `core/src/main/kotlin/com/workflow/orchestrator/core/settings/WorkflowSettingsConfigurable.kt` — add a KDoc note marking `id="workflow.orchestrator"` as the public nesting anchor for depending plugins (Task 1; doc-only).

**Created (tests):**
- `konsist/src/test/kotlin/com/workflow/orchestrator/konsist/SettingsAnchorContractTest.kt` — source-text cross-plugin.xml contract (Task 1).

**Modified (docs):**
- `core/CLAUDE.md` — settings-anchor note for depending plugins (Task 3).
- `docs/superpowers/specs/2026-06-22-plugin-split-design.md` — 0b-4 resolved note (Task 3; gitignored → `git add -f`).

---

## Task 1: Pin the settings anchor (failing contract test + KDoc)

**Files:**
- Create: `konsist/src/test/kotlin/com/workflow/orchestrator/konsist/SettingsAnchorContractTest.kt`
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/settings/WorkflowSettingsConfigurable.kt` (KDoc only)

**Interfaces:**
- Produces: the stable anchor invariant — A's root `src/main/resources/META-INF/plugin.xml` declares a `<projectConfigurable … id="workflow.orchestrator">`, and (after Task 2) `plugin-b/src/main/resources/META-INF/plugin.xml` declares a `<projectConfigurable … parentId="workflow.orchestrator">`.

**Note on the test home:** `:konsist` is the cross-module contract module — it already hosts A↔B dependency-direction + public-API-surface contracts, so a cross-plugin.xml linkage test belongs there. The test resolves the repo root by walking up from the test's working directory until it finds `settings.gradle.kts`, then reads the two `plugin.xml` files by absolute path (robust to the test's CWD).

- [ ] **Step 1: Write the failing contract test**

```kotlin
package com.workflow.orchestrator.konsist

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Pins the settings-section contribution anchor across the two-plugin boundary by inspecting the
 * actual <projectConfigurable> ELEMENTS (not loose substrings) in both plugin.xml files:
 *  - Plugin A's root plugin.xml MUST register WorkflowSettingsConfigurable with the stable settings
 *    group id `workflow.orchestrator` (the plugin.xml `id` attribute — that, NOT `getId()`, is what
 *    `parentId` resolves against).
 *  - Plugin B MUST nest CompanyBSettingsConfigurable under that exact id via `parentId`.
 * Because B's verifyPlugin is disabled, a typo in B's `instance` FQN would otherwise surface only at
 * runIde — so this also pins the instance FQNs + B's class-file existence (turns a runtime-only break
 * into a failing test). NOTE: reads plugin.xml as TEXT (not Konsist.scopeFromProject, which scopes
 * Kotlin sources only) — appropriate for asserting on resource XML; comments are stripped first.
 */
class SettingsAnchorContractTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        error("repo root (settings.gradle.kts) not found from ${File("").absolutePath}")
    }

    /** All <projectConfigurable …> opening-tag texts, with XML comments stripped first. */
    private fun projectConfigurables(relPath: String): List<String> {
        val xml = File(repoRoot(), relPath).readText()
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        return Regex("<projectConfigurable\\b[^>]*?>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).map { it.value }.toList()
    }

    @Test
    fun `A registers the anchor id on WorkflowSettingsConfigurable`() {
        val anchor = projectConfigurables("src/main/resources/META-INF/plugin.xml").firstOrNull {
            it.contains("id=\"workflow.orchestrator\"") && it.contains("WorkflowSettingsConfigurable")
        }
        assertTrue(
            anchor != null,
            "Root plugin.xml must register WorkflowSettingsConfigurable with id=\"workflow.orchestrator\" " +
                "(the stable anchor depending plugins nest under).",
        )
    }

    @Test
    fun `B nests CompanyBSettingsConfigurable under A's anchor`() {
        val nested = projectConfigurables("plugin-b/src/main/resources/META-INF/plugin.xml").firstOrNull {
            it.contains("parentId=\"workflow.orchestrator\"") && it.contains("CompanyBSettingsConfigurable")
        }
        assertTrue(
            nested != null,
            "plugin-b plugin.xml must register <projectConfigurable parentId=\"workflow.orchestrator\" " +
                "instance=\"…CompanyBSettingsConfigurable\"> so B's page nests under A's group.",
        )
    }

    @Test
    fun `B's declared configurable class exists`() {
        val f = File(
            repoRoot(),
            "plugin-b/src/main/kotlin/com/workflow/orchestrator/companyb/CompanyBSettingsConfigurable.kt",
        )
        assertTrue(
            f.exists() && f.readText().contains("class CompanyBSettingsConfigurable"),
            "B's declared instance FQN must resolve to a real class (B's verifyPlugin is disabled, so a " +
                "typo'd instance would otherwise surface only at runIde).",
        )
    }
}
```

- [ ] **Step 2: Run — confirm the A test PASSES, the two B tests FAIL**

Run: `./gradlew :konsist:test --tests "*SettingsAnchorContractTest*"`
Expected: `A registers the anchor id …` PASSES (id already exists on the right instance); `B nests …` and `B's declared configurable class exists` both FAIL (Task 2 adds them). If the A test fails, STOP — the anchor id/instance changed; do not proceed.

- [ ] **Step 3: Extend the existing KDoc on `WorkflowSettingsConfigurable`**

`WorkflowSettingsConfigurable.kt` already has a KDoc (~lines 9-13). EXTEND it (do not stack a second `/** */` block) to mark the plugin.xml `id` as the public anchor — append this text into the existing KDoc:

```
 *
 * PUBLIC ANCHOR: the plugin.xml `id="workflow.orchestrator"` on this configurable's
 * <projectConfigurable> (root plugin.xml) is a STABLE nesting anchor — `parentId` resolves
 * against that plugin.xml id (NOT getId()). Depending plugins (e.g. plugin B) nest their own
 * Settings pages with <projectConfigurable parentId="workflow.orchestrator">. Do NOT rename
 * this id; a rename silently detaches every depending plugin's page. Pinned by SettingsAnchorContractTest.
```

(Keep `getId()` returning `"workflow.orchestrator"` unchanged — it happens to match the plugin.xml id, but the load-bearing anchor is the plugin.xml `id` attribute.)

- [ ] **Step 4: Run — A test still passes (no regression)**

Run: `./gradlew :konsist:test --tests "*SettingsAnchorContractTest*"`
Expected: still `A …` PASS, both B tests FAIL (Task 2 makes them green). KDoc is doc-only.

- [ ] **Step 5: Commit**

```bash
git add konsist/src/test/kotlin/com/workflow/orchestrator/konsist/SettingsAnchorContractTest.kt \
        core/src/main/kotlin/com/workflow/orchestrator/core/settings/WorkflowSettingsConfigurable.kt
git commit -m "test(plugin-split): pin A settings anchor + B-nesting contract (0b-4); doc the anchor"
```

(NO `Co-Authored-By` trailer.)

---

## Task 2: B contributes a Settings page under A's anchor

**Files:**
- Create: `plugin-b/src/main/kotlin/com/workflow/orchestrator/companyb/CompanyBSettingsConfigurable.kt`
- Modify: `plugin-b/src/main/resources/META-INF/plugin.xml`

**Interfaces:**
- Consumes: the anchor `workflow.orchestrator` (Task 1).
- Produces: a B Settings page visible under "Workflow Orchestrator" at runtime; makes Task 1's `B nests …` test green.

- [ ] **Step 1: Create `CompanyBSettingsConfigurable.kt`**

```kotlin
package com.workflow.orchestrator.companyb

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Demonstrates plugin B contributing its own Settings page nested under plugin A's
 * "Workflow Orchestrator" group, using only the platform `projectConfigurable` EP with
 * `parentId="workflow.orchestrator"` (A's stable anchor) — no custom A-side seam required.
 *
 * Placeholder content + NO persisted state for now (Phase 0b-4 = mechanism proof only). B's
 * real company-preset settings land in Phase 2. Sibling of `CompanyBToolContributor` (which
 * proved the agentToolContributor EP in Phase 0a).
 */
class CompanyBSettingsConfigurable(
    @Suppress("UnusedParameter") project: Project,
) : Configurable {
    override fun getDisplayName(): String = "Company B"

    override fun createComponent(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.add(
            JLabel("Company B preset settings — placeholder (populated in Phase 2)."),
            BorderLayout.NORTH,
        )
        return panel
    }

    override fun isModified(): Boolean = false

    override fun apply() { /* no-op: B contributes no persisted settings yet (Phase 2) */ }
}
```

⚠ The single `Project` ctor param is REQUIRED by the platform's `projectConfigurable` instantiation contract (a no-arg ctor would BREAK instantiation — do NOT "fix" the unused param by removing it). It's intentionally unused for the placeholder page, so it's a bare param (not stored) with `@Suppress("UnusedParameter")`. Precedent: `web/.../WebSettingsConfigurable.kt` and `core/.../CrossIdeDelegationConfigurable.kt` are plain `Configurable`s with a `Project` ctor nested under this same group. After writing, RUN `./gradlew :plugin-b:detekt` — if detekt names a different unused-rule id than `UnusedParameter`, adjust the suppression to match (do NOT baseline; plugin-b has no detekt baseline and `maxIssues: 0`).

- [ ] **Step 2: Register it in `plugin-b/src/main/resources/META-INF/plugin.xml`**

Add a platform `<extensions defaultExtensionNs="com.intellij">` block (separate from the existing `com.workflow.orchestrator` extensions block) with the projectConfigurable:

```xml
    <extensions defaultExtensionNs="com.intellij">
        <projectConfigurable
            parentId="workflow.orchestrator"
            instance="com.workflow.orchestrator.companyb.CompanyBSettingsConfigurable"
            id="workflow.orchestrator.companyb"
            displayName="Company B"
            nonDefaultProject="true"/>
    </extensions>
```

(Keep the existing `<extensions defaultExtensionNs="com.workflow.orchestrator">` block with `workflowConfig` + `agentToolContributor` unchanged.)

- [ ] **Step 3: Run the contract test — now GREEN**

Run: `./gradlew :konsist:test --tests "*SettingsAnchorContractTest*"`
Expected: ALL THREE tests PASS (A anchor, B nests, B class exists).

- [ ] **Step 4: Confirm B compiles**

Run: `./gradlew :plugin-b:compileKotlin`
Expected: BUILD SUCCESSFUL. (If it fails because `Configurable`/`Project` aren't resolvable, B's intellijPlatform deps don't expose the platform API — STOP and report; do NOT add a `:core` dep to paper over it, investigate B's `build.gradle.kts` platform config.)

- [ ] **Step 5: Commit**

```bash
git add plugin-b/src/main/kotlin/com/workflow/orchestrator/companyb/CompanyBSettingsConfigurable.kt \
        plugin-b/src/main/resources/META-INF/plugin.xml
git commit -m "feat(plugin-b): contribute a Settings page nested under A's Workflow Orchestrator group (0b-4)"
```

(NO `Co-Authored-By` trailer.)

---

## Task 3: Docs + full green gate

**Files:**
- Modify: `core/CLAUDE.md`
- Modify: `docs/superpowers/specs/2026-06-22-plugin-split-design.md` (gitignored → `git add -f`)

- [ ] **Step 1: Document the anchor in `core/CLAUDE.md`**

Add a short note (near the settings / EP documentation) that `workflow.orchestrator` is the stable public Settings-group anchor: depending plugins nest their own pages via `<projectConfigurable parentId="workflow.orchestrator">` (no custom EP needed); pinned by `SettingsAnchorContractTest`; do not rename the id.

- [ ] **Step 2: Add a 0b-4 resolved note to the spec**

Append a short "§18 — Phase 0b-4 resolved" note to `docs/superpowers/specs/2026-06-22-plugin-split-design.md` (mirror the §16/§17 style): what shipped (settings-section contribution via the platform parentId anchor + demonstrative `CompanyBSettingsConfigurable`); the decision that config-preset widening is DEFERRED to Phase 1 (the preset can't override A's defaults until they're blanked); no custom A-side settings EP was needed.

- [ ] **Step 3: detekt (whichever modules this touched)**

Run: `./gradlew :konsist:detekt :core:detekt --auto-correct` (and `:plugin-b:detekt` if that task exists — check `./gradlew :plugin-b:tasks --all | grep detekt`). Fix genuine issues by reorder/correct; do NOT baseline.

- [ ] **Step 4: Full green gate**

Run:
1. `./gradlew :konsist:test` — GREEN (incl. `SettingsAnchorContractTest`).
2. `./gradlew :plugin-b:compileKotlin :core:compileKotlin` — GREEN.
3. `./gradlew verifyPlugin` — GREEN (verifies A; B's verifyPlugin stays disabled).

If any fails, STOP and report exactly what failed.

- [ ] **Step 5: Commit**

```bash
git add core/CLAUDE.md
git add -f docs/superpowers/specs/2026-06-22-plugin-split-design.md
git commit -m "docs(plugin-split): document the workflow.orchestrator settings anchor + 0b-4 resolved note"
```

(NO `Co-Authored-By` trailer.)

---

## Self-Review (run before declaring the plan ready)

**1. Spec coverage (§8.7 settings-section contribution):** "B contributes its settings pages" → Tasks 1-2 (demonstrative `CompanyBSettingsConfigurable` nested via platform parentId). "+ its config preset / overrides A's neutral defaults" → DEFERRED to Phase 1 per the user's 2026-06-24 decision (documented in Task 3's spec note). ✓

**2. Placeholder scan:** every step has full code/commands; no TBD. ✓

**3. Type/linkage consistency:** the anchor string `workflow.orchestrator` is identical in: A's root plugin.xml `id`, the KDoc, B's plugin.xml `parentId`, and both contract-test assertions. The B configurable's fully-qualified class name in plugin.xml (`com.workflow.orchestrator.companyb.CompanyBSettingsConfigurable`) matches the created file's package+name. ✓

**4. Risk notes:** (a) `:konsist` test CWD — the repo-root walk handles it, but the implementer must RUN the test to confirm the paths resolve (Task 1 Step 2). (b) B compiling against platform `Configurable` — Task 2 Step 4 verifies; if it fails, investigate B's platform deps, don't add `:core`. (c) `verifyPlugin` — adding a B projectConfigurable should not affect A's verification (B's verifyPlugin is disabled); Task 3 Step 4 confirms. (d) The `Project` ctor param is REQUIRED by the platform (a no-arg ctor BREAKS instantiation — do not remove it); it's unused for the placeholder, handled with `@Suppress("UnusedParameter")` + a `:plugin-b:detekt` check (Task 2).

**5. Honest scope of the "proof" (state plainly):** CI proves (i) the class compiles + exists, (ii) the anchor id is on A's `WorkflowSettingsConfigurable` instance, (iii) B declares `parentId="workflow.orchestrator"` with the matching `CompanyBSettingsConfigurable` instance. CI does NOT prove the page actually renders under the group at runtime — B's `verifyPlugin` is disabled and the platform silently falls back to "Other Settings" on a bad `parentId`/`instance`. **Runtime appearance remains a runIde smoke (PENDING-USER).** The contract test closes the highest-probability silent break (FQN typo / id rename) but does not replace the runIde check. Do not record "0b-4 proven" as CI-verified rendering.

## Execution Handoff

Per the standing multi-round-review rule (right-sized for this small, additive, behavior-unchanged phase): **2 independent opus plan-review rounds** (accuracy/completeness + skeptic), then **subagent-driven execution** (3 tasks, per-task review), then **1 final whole-branch opus review**, then `finishing-a-development-branch` (ff-merge + push, NO trailer).
