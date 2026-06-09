# Context-Window Overrides Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the model-selector (132K) vs context-bar (96K) divergence with a single `EffectiveContextWindow` resolver both consumers read through, and add per-model/global max-token overrides.

**Architecture:** A pure `:agent` resolver layers user overrides (per-model > global) over the existing shared 1-hour catalog cache. Two keys use it: DISPLAY (bar/picker/TopBar) keys on the selected model; RUNTIME (compaction/sub-agent budget) keys on the running `currentBrainModelId`. Overrides persist as scalars in `AgentSettings` (`BaseState`).

**Tech Stack:** Kotlin, IntelliJ Platform (`BaseState`/`PersistentStateComponent`, Kotlin UI DSL, `JBTable`), JUnit5 + MockK, kotlinx.serialization, React/Vitest (webview).

**Spec:** `docs/superpowers/specs/2026-06-09-context-window-overrides-design.md`

**Conventions in this repo:**
- `AgentService`/`AgentController` are NOT unit-instantiable → their wiring is pinned by **source-text contract tests** (read the `.kt` as a String, assert it contains the wiring). Follow existing `*WiringTest`/parity tests.
- Build: `./gradlew :agent:test --tests "<pattern>"`. Detekt: `./gradlew :agent:detekt`. Run the FULL `:agent:test` before the final commit (source-text tests slice files — see PHASE-RUN-STATUS.md sentinel trap).
- Commit messages end with: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`

---

### Task 1: `EffectiveContextWindow` resolver (pure, TDD)

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/model/EffectiveContextWindow.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/model/EffectiveContextWindowTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.workflow.orchestrator.agent.model

import com.workflow.orchestrator.core.ai.ModelCatalogService
import com.workflow.orchestrator.core.ai.dto.ContextWindow
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EffectiveContextWindowTest {

    private fun catalogReturning(modelId: String, maxInput: Int?): ModelCatalogService {
        val svc = mockk<ModelCatalogService>()
        every { svc.getContextWindow(any(), any()) } returns null
        if (maxInput != null) {
            every { svc.getContextWindow(modelId, any()) } returns
                ContextWindow(maxInputTokens = maxInput, maxOutputTokens = 8000, maxUserInputTokens = null)
        }
        return svc
    }

    private fun resolver(
        catalog: ModelCatalogService? = null,
        overrides: MaxTokenOverrides = MaxTokenOverrides(global = null, perModel = emptyMap()),
        fallback: Int = 90_000,
    ) = EffectiveContextWindow(catalog = { catalog }, overrides = { overrides }, fallback = fallback)

    @Test
    fun `per-model override wins over global and catalog`() {
        val r = resolver(
            catalog = catalogReturning("m1", 132_000),
            overrides = MaxTokenOverrides(global = 50_000, perModel = mapOf("m1" to 70_000)),
        )
        assertEquals(70_000, r.maxInputTokens("m1"))
    }

    @Test
    fun `global override applies when no per-model entry`() {
        val r = resolver(
            catalog = catalogReturning("m1", 132_000),
            overrides = MaxTokenOverrides(global = 50_000, perModel = emptyMap()),
        )
        assertEquals(50_000, r.maxInputTokens("m1"))
    }

    @Test
    fun `catalog value used when no override`() {
        val r = resolver(catalog = catalogReturning("m1", 132_000))
        assertEquals(132_000, r.maxInputTokens("m1"))
    }

    @Test
    fun `override may exceed catalog`() {
        val r = resolver(
            catalog = catalogReturning("m1", 132_000),
            overrides = MaxTokenOverrides(global = null, perModel = mapOf("m1" to 200_000)),
        )
        assertEquals(200_000, r.maxInputTokens("m1"))
    }

    @Test
    fun `cache miss falls back when no override`() {
        val r = resolver(catalog = catalogReturning("m1", null), fallback = 90_000)
        assertEquals(90_000, r.maxInputTokens("unknown"))
    }

    @Test
    fun `null or blank modelId still applies global override else fallback`() {
        assertEquals(50_000, resolver(overrides = MaxTokenOverrides(50_000, emptyMap())).maxInputTokens(null))
        assertEquals(90_000, resolver(fallback = 90_000).maxInputTokens(""))
    }

    @Test
    fun `catalogMaxInputTokens ignores overrides`() {
        val r = resolver(
            catalog = catalogReturning("m1", 132_000),
            overrides = MaxTokenOverrides(global = 10, perModel = mapOf("m1" to 10)),
        )
        assertEquals(132_000, r.catalogMaxInputTokens("m1"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "*EffectiveContextWindowTest" --rerun-tasks`
Expected: FAIL — `EffectiveContextWindow` / `MaxTokenOverrides` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.workflow.orchestrator.agent.model

import com.workflow.orchestrator.core.ai.ModelCatalogService

/** Snapshot of the user's max-token overrides. `global == null` means no global override. */
data class MaxTokenOverrides(val global: Int?, val perModel: Map<String, Int>)

/**
 * Single resolution path for a model's max INPUT tokens, layering user overrides over the shared,
 * cached [ModelCatalogService]. Precedence: per-model override > global override > catalog > fallback.
 *
 * Used with TWO different keys (see spec "Two-key architecture"):
 *   - DISPLAY (bar/picker/TopBar) → keyed on the SELECTED model.
 *   - RUNTIME (compaction/sub-agent budget) → keyed on the running `currentBrainModelId`.
 *
 * Synchronous: [ModelCatalogService.getContextWindow] is a non-suspend in-memory cache read.
 */
class EffectiveContextWindow(
    private val catalog: () -> ModelCatalogService?,
    private val overrides: () -> MaxTokenOverrides,
    private val fallback: Int = FALLBACK,
) {
    fun maxInputTokens(modelId: String?): Int {
        val ov = overrides()
        if (!modelId.isNullOrBlank()) ov.perModel[modelId]?.let { return it }
        ov.global?.let { return it }
        val catVal = modelId?.takeIf { it.isNotBlank() }?.let { catalog()?.getContextWindow(it)?.maxInputTokens }
        return catVal ?: fallback
    }

    fun catalogMaxInputTokens(modelId: String): Int? =
        catalog()?.getContextWindow(modelId)?.maxInputTokens

    companion object {
        /** Mirrors ContextManager.FALLBACK_MAX_INPUT_TOKENS (90_000). */
        const val FALLBACK = 90_000
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :agent:test --tests "*EffectiveContextWindowTest" --rerun-tasks`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/model/EffectiveContextWindow.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/model/EffectiveContextWindowTest.kt
git commit -m "feat(agent): EffectiveContextWindow resolver (override-aware context window)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: `AgentSettings` override fields + snapshot (TDD round-trip)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/AgentSettings.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/settings/AgentSettingsOverridesTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.workflow.orchestrator.agent.settings

import com.workflow.orchestrator.agent.model.MaxTokenOverrides
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentSettingsOverridesTest {

    @Test
    fun `default snapshot has no overrides`() {
        val s = AgentSettings.State()
        assertEquals(MaxTokenOverrides(global = null, perModel = emptyMap()), s.maxTokenOverridesSnapshot())
    }

    @Test
    fun `global zero maps to null, positive maps through`() {
        val s = AgentSettings.State().apply { maxTokenGlobalOverride = 0 }
        assertEquals(null, s.maxTokenOverridesSnapshot().global)
        s.maxTokenGlobalOverride = 50_000
        assertEquals(50_000, s.maxTokenOverridesSnapshot().global)
    }

    @Test
    fun `per-model json round-trips`() {
        val s = AgentSettings.State().apply { maxTokenPerModelOverrideJson = """{"m1":70000,"m2":120000}""" }
        assertEquals(mapOf("m1" to 70_000, "m2" to 120_000), s.maxTokenOverridesSnapshot().perModel)
    }

    @Test
    fun `malformed json yields empty map (never throws)`() {
        val s = AgentSettings.State().apply { maxTokenPerModelOverrideJson = "not json" }
        assertEquals(emptyMap<String, Int>(), s.maxTokenOverridesSnapshot().perModel)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "*AgentSettingsOverridesTest" --rerun-tasks`
Expected: FAIL — fields / `maxTokenOverridesSnapshot` unresolved.

- [ ] **Step 3: Add fields + snapshot to `AgentSettings.State`**

Inside `class State : BaseState()` (after `sourcegraphChatModel`), add:

```kotlin
        /** Global max-input-tokens override for ALL models. 0 = no override. */
        var maxTokenGlobalOverride by property(0)
        /** JSON-encoded {modelId: maxInputTokens} per-model overrides. "{}" = none. */
        var maxTokenPerModelOverrideJson by string("{}")
```

Add at the end of `class State` (still inside it), the snapshot helper:

```kotlin
        fun maxTokenOverridesSnapshot(): com.workflow.orchestrator.agent.model.MaxTokenOverrides {
            val perModel = try {
                kotlinx.serialization.json.Json.decodeFromString<Map<String, Int>>(
                    maxTokenPerModelOverrideJson ?: "{}",
                )
            } catch (_: Exception) {
                emptyMap()
            }
            return com.workflow.orchestrator.agent.model.MaxTokenOverrides(
                global = maxTokenGlobalOverride.takeIf { it > 0 },
                perModel = perModel,
            )
        }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :agent:test --tests "*AgentSettingsOverridesTest" --rerun-tasks`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/AgentSettings.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/settings/AgentSettingsOverridesTest.kt
git commit -m "feat(agent): AgentSettings max-token override fields + snapshot

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: `ContextManager` routes through resolver (running-model key) (TDD)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/ContextManager.kt:36-49` (ctor) and `effectiveMaxInputTokens()` (~`:460-465`)
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/loop/ContextManagerEffectiveWindowTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.agent.model.EffectiveContextWindow
import com.workflow.orchestrator.agent.model.MaxTokenOverrides
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ContextManagerEffectiveWindowTest {

    @Test
    fun `effectiveMaxInputTokens uses resolver keyed on the running model`() {
        val resolver = EffectiveContextWindow(
            catalog = { null },
            overrides = { MaxTokenOverrides(global = null, perModel = mapOf("running-model" to 111_000)) },
            fallback = 90_000,
        )
        val cm = ContextManager(
            maxInputTokens = 150_000,
            currentModelRef = { "running-model" },
            effectiveContextWindow = resolver,
        )
        assertEquals(111_000, cm.effectiveMaxInputTokens())
    }

    @Test
    fun `without resolver falls back to existing behavior`() {
        val cm = ContextManager(maxInputTokens = 123_000)
        assertEquals(123_000, cm.effectiveMaxInputTokens())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "*ContextManagerEffectiveWindowTest" --rerun-tasks`
Expected: FAIL — `effectiveContextWindow` ctor param unresolved.

- [ ] **Step 3: Add ctor param + route the method**

In the `ContextManager(...)` constructor, after `private val currentModelRef: (() -> String?)? = null,` add:

```kotlin
    private val effectiveContextWindow: com.workflow.orchestrator.agent.model.EffectiveContextWindow? = null,
```

Replace the body of `effectiveMaxInputTokens()` (currently lines ~460-465) with:

```kotlin
    fun effectiveMaxInputTokens(): Int {
        // RUNTIME key = the running model (currentModelRef = currentBrainModelId). Honors overrides
        // for the model the brain is actually calling — correct under L2 tier escalation.
        effectiveContextWindow?.let { return it.maxInputTokens(currentModelRef?.invoke()) }
        val ref = currentModelRef?.invoke() ?: return maxInputTokens
        val catalog = modelCatalogService ?: return maxInputTokens
        val window = catalog.getContextWindow(ref) ?: return maxInputTokens
        return window.maxInputTokens
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :agent:test --tests "*ContextManagerEffectiveWindowTest" --rerun-tasks`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/ContextManager.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/loop/ContextManagerEffectiveWindowTest.kt
git commit -m "feat(agent): ContextManager routes effectiveMaxInputTokens through resolver (running-model key)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Construct + inject resolver in `AgentService` (source-contract test)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt` (construct resolver near the shared-catalog wiring; pass `effectiveContextWindow = …` into every `ContextManager(...)` construction it owns at lines ~800-804, ~1916, ~2896; add `fun getEffectiveContextWindow()`)
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/AgentServiceEffectiveWindowWiringTest.kt`

- [ ] **Step 1: Write the failing source-contract test**

```kotlin
package com.workflow.orchestrator.agent

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class AgentServiceEffectiveWindowWiringTest {
    private val src = File("src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt").readText()

    @Test
    fun `constructs an EffectiveContextWindow from the shared catalog and settings snapshot`() {
        assertTrue(src.contains("EffectiveContextWindow("), "AgentService must construct EffectiveContextWindow")
        assertTrue(
            Regex("overrides\\s*=\\s*\\{[^}]*maxTokenOverridesSnapshot").containsMatchIn(src),
            "resolver overrides must read AgentSettings.maxTokenOverridesSnapshot()",
        )
    }

    @Test
    fun `injects the resolver into ContextManager construction`() {
        assertTrue(src.contains("effectiveContextWindow ="), "ContextManager(...) must receive effectiveContextWindow")
    }

    @Test
    fun `exposes the resolver to the controller`() {
        assertTrue(src.contains("fun getEffectiveContextWindow()"), "must expose getEffectiveContextWindow()")
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :agent:test --tests "*AgentServiceEffectiveWindowWiringTest" --rerun-tasks`
Expected: FAIL.

- [ ] **Step 3: Implement the wiring**

In `AgentService`, where the shared `ModelCatalogService` is available (the `getOrCreateSharedCatalog`/`SharedCatalogHolder` area; the same provider used by `ContextManager`'s `modelCatalogService`), add a lazily-built resolver field:

```kotlin
    private val effectiveContextWindow: com.workflow.orchestrator.agent.model.EffectiveContextWindow by lazy {
        com.workflow.orchestrator.agent.model.EffectiveContextWindow(
            catalog = { getOrCreateSharedCatalog() },   // same shared, cached catalog the bar/picker use
            overrides = { com.workflow.orchestrator.agent.settings.AgentSettings.getInstance(project).state.maxTokenOverridesSnapshot() },
        )
    }

    fun getEffectiveContextWindow(): com.workflow.orchestrator.agent.model.EffectiveContextWindow = effectiveContextWindow
```

> NOTE for implementer: confirm the exact accessor that returns the shared `ModelCatalogService?` (search `getOrCreateSharedCatalog` / `SharedCatalogHolder` / `getSharedModelCatalog`). Use whichever returns the cached instance synchronously (nullable). If only a suspend getter exists, capture the already-warmed instance into a `@Volatile var` set on warm-up and read that here.

For EACH `ContextManager(...)` constructed in `AgentService` (search `ContextManager(`), add the argument:

```kotlin
            effectiveContextWindow = effectiveContextWindow,
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :agent:test --tests "*AgentServiceEffectiveWindowWiringTest" --rerun-tasks`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/AgentServiceEffectiveWindowWiringTest.kt
git commit -m "feat(agent): construct + inject EffectiveContextWindow in AgentService

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: `AgentControllerRegistry.getController()` + `AgentController` display rewiring (source-contract test)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentControllerRegistry.kt` (add explicit getter)
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt` — selector row (~`:1968`), bar provider (~`:853-858`), `onTokenUpdate` (~`:2849-2854`), add `selectedModelId()` + `notifyContextWindowOverridesChanged()`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/ui/AgentControllerDisplayWindowWiringTest.kt`

- [ ] **Step 1: Write the failing source-contract test**

```kotlin
package com.workflow.orchestrator.agent.ui

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class AgentControllerDisplayWindowWiringTest {
    private val ctrl = File("src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt").readText()
    private val registry = File("src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentControllerRegistry.kt").readText()

    @Test
    fun `registry exposes an explicit getController()`() {
        assertTrue(registry.contains("fun getController()"), "AgentControllerRegistry must expose fun getController()")
    }

    @Test
    fun `selector and bar route DISPLAY through the resolver keyed on the selected model`() {
        assertTrue(ctrl.contains("fun selectedModelId()"), "must define selectedModelId()")
        assertTrue(
            ctrl.contains("getEffectiveContextWindow().maxInputTokens(selectedModelId())"),
            "the usage-bar provider must key the resolver on the selected model",
        )
        assertTrue(
            Regex("getEffectiveContextWindow\\(\\)\\.maxInputTokens\\(m\\.id\\)").containsMatchIn(ctrl),
            "the model-picker row must read the override-aware window via the resolver",
        )
    }

    @Test
    fun `exposes the settings-apply notification entry point`() {
        assertTrue(
            ctrl.contains("fun notifyContextWindowOverridesChanged()"),
            "must expose notifyContextWindowOverridesChanged()",
        )
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :agent:test --tests "*AgentControllerDisplayWindowWiringTest" --rerun-tasks`
Expected: FAIL.

- [ ] **Step 3a: `AgentControllerRegistry` explicit getter**

Add inside `AgentControllerRegistry` (alongside `var controller`):

```kotlin
    fun getController(): AgentController? = controller
```

- [ ] **Step 3b: `AgentController` — add helpers**

Add private helper (near other model helpers, e.g. around the `changeModel` area):

```kotlin
    private fun selectedModelId(): String? =
        com.workflow.orchestrator.agent.settings.AgentSettings.getInstance(project).state.sourcegraphChatModel
            ?.takeIf { it.isNotBlank() }
            ?: service.getCurrentBrainModelId()
```

Add a public notification method (near other public bridge methods):

```kotlin
    fun notifyContextWindowOverridesChanged() {
        loadModelList(force = true)               // re-emit updateModelList with new effective per-row windows
        dashboard.refreshContextUsage()           // fire the usage-refresh bridge event (bar updates at once)
    }
```

> NOTE for implementer: use the existing usage-refresh path. If `dashboard.refreshContextUsage()` does not exist, reuse whatever event `UsageIndicator.tsx:55-57` already listens for (the post-compaction/handoff refresh). Search the dashboard/CEF panel for the method that triggers `getContextUsage` re-read and call that. If none, the 1-second poll is the backstop and a follow-up can add the push.

- [ ] **Step 3c: `AgentController` — selector row**

At `loadModelList` (~`:1968`), replace:

```kotlin
                val cw = catalog?.getContextWindow(m.id, tier = "enterprise")
```
with:
```kotlin
                val effMax = service.getEffectiveContextWindow().maxInputTokens(m.id)
                val cwReal = catalog?.getContextWindow(m.id, tier = "enterprise")
                val cw = cwReal?.let { it.copy(maxInputTokens = effMax) }
                    ?: com.workflow.orchestrator.core.ai.dto.ContextWindow(maxInputTokens = effMax, maxOutputTokens = 0, maxUserInputTokens = null)
```

> This keeps `maxUserInputTokens` from the real catalog while the displayed context number reflects overrides. Confirm `ContextWindow` is a `data class` (it is — `ModelCatalogDtos.kt:50`) so `.copy(...)` is available.

- [ ] **Step 3d: `AgentController` — usage bar provider**

At the `setContextUsageProvider { … }` block (~`:853-858`), replace:

```kotlin
            val max = cm.effectiveMaxInputTokens()
```
with:
```kotlin
            // DISPLAY key = selected model (keeps the bar identical to the selected picker row).
            val max = service.getEffectiveContextWindow().maxInputTokens(selectedModelId())
```

- [ ] **Step 3e: `AgentController` — TopBar onTokenUpdate**

At `onTokenUpdate` (~`:2849-2854`), replace the `contextManager?.effectiveMaxInputTokens()` read used for the TopBar max with:

```kotlin
            service.getEffectiveContextWindow().maxInputTokens(selectedModelId())
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :agent:test --tests "*AgentControllerDisplayWindowWiringTest" --rerun-tasks`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentControllerRegistry.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/ui/AgentControllerDisplayWindowWiringTest.kt
git commit -m "feat(agent): bar/picker/TopBar display routes through resolver keyed on selected model

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: `AgentCefPanel` hardcoded fallback → constant

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt:729`

- [ ] **Step 1: Edit**

Replace:
```kotlin
        val (used, max) = contextUsageProvider?.invoke() ?: (0 to 132_000)
```
with:
```kotlin
        val (used, max) = contextUsageProvider?.invoke()
            ?: (0 to com.workflow.orchestrator.agent.model.EffectiveContextWindow.FALLBACK)
```

- [ ] **Step 2: Compile**

Run: `./gradlew :agent:compileKotlin --rerun-tasks`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt
git commit -m "refactor(agent): use shared FALLBACK constant for pre-wire usage fallback

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: Settings UI — global field + per-model table + apply→notify

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/AgentAdvancedConfigurable.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/MaxTokenOverrideTableModel.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/settings/MaxTokenOverrideTableModelTest.kt`

- [ ] **Step 1: Write the failing test for the (pure) table model**

```kotlin
package com.workflow.orchestrator.agent.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MaxTokenOverrideTableModelTest {

    @Test
    fun `toJson encodes only positive overrides`() {
        val m = MaxTokenOverrideTableModel()
        m.setRows(listOf(
            MaxTokenOverrideTableModel.Row("m1", real = 132_000, override = 70_000),
            MaxTokenOverrideTableModel.Row("m2", real = 93_000, override = 0),    // 0 = no override → dropped
        ))
        assertEquals("""{"m1":70000}""", m.toJson())
    }

    @Test
    fun `fromJson seeds overrides onto catalog rows`() {
        val m = MaxTokenOverrideTableModel()
        m.seed(modelReals = linkedMapOf("m1" to 132_000, "m2" to 93_000), overridesJson = """{"m2":80000}""")
        assertEquals(0, m.rowAt("m1").override)
        assertEquals(80_000, m.rowAt("m2").override)
    }

    @Test
    fun `aboveCatalog flags overrides exceeding real`() {
        val m = MaxTokenOverrideTableModel()
        m.setRows(listOf(MaxTokenOverrideTableModel.Row("m1", real = 132_000, override = 200_000)))
        assertEquals(true, m.rowAt("m1").aboveCatalog())
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :agent:test --tests "*MaxTokenOverrideTableModelTest" --rerun-tasks`
Expected: FAIL.

- [ ] **Step 3: Implement the table model**

```kotlin
package com.workflow.orchestrator.agent.settings

import javax.swing.table.AbstractTableModel

/**
 * Swing table model for per-model max-input-token overrides. Pure data + JSON (de)serialization so
 * the precedence/round-trip is unit-testable without the Configurable. Column 2 (Override) editable;
 * 0 = "use model default" and is omitted from the persisted JSON.
 */
class MaxTokenOverrideTableModel : AbstractTableModel() {

    data class Row(val modelId: String, val real: Int?, var override: Int) {
        fun aboveCatalog(): Boolean = real != null && override > real
    }

    private val rows = mutableListOf<Row>()
    private val columns = arrayOf("Model", "Real (catalog)", "Override (0 = default)")

    fun setRows(newRows: List<Row>) { rows.clear(); rows.addAll(newRows); fireTableDataChanged() }
    fun rows(): List<Row> = rows.toList()
    fun rowAt(modelId: String): Row = rows.first { it.modelId == modelId }

    fun seed(modelReals: Map<String, Int?>, overridesJson: String?) {
        val ov = try {
            kotlinx.serialization.json.Json.decodeFromString<Map<String, Int>>(overridesJson ?: "{}")
        } catch (_: Exception) { emptyMap() }
        setRows(modelReals.map { (id, real) -> Row(id, real, ov[id] ?: 0) })
    }

    fun toJson(): String {
        val map = rows.filter { it.override > 0 }.associate { it.modelId to it.override }
        return kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.MapSerializer(
                kotlinx.serialization.builtins.serializer<String>(),
                kotlinx.serialization.builtins.serializer<Int>(),
            ),
            map,
        )
    }

    override fun getRowCount() = rows.size
    override fun getColumnCount() = columns.size
    override fun getColumnName(c: Int) = columns[c]
    override fun isCellEditable(r: Int, c: Int) = c == 2
    override fun getColumnClass(c: Int): Class<*> = if (c == 1 || c == 2) Integer::class.java else String::class.java

    override fun getValueAt(r: Int, c: Int): Any = when (c) {
        0 -> rows[r].modelId
        1 -> rows[r].real ?: -1
        else -> rows[r].override
    }

    override fun setValueAt(value: Any?, r: Int, c: Int) {
        if (c == 2) { rows[r].override = (value as? Int ?: (value as? String)?.toIntOrNull() ?: 0).coerceAtLeast(0); fireTableRowsUpdated(r, r) }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :agent:test --tests "*MaxTokenOverrideTableModelTest" --rerun-tasks`
Expected: PASS (3 tests).

- [ ] **Step 5: Wire the UI into `AgentAdvancedConfigurable`**

Read the existing `AgentAdvancedConfigurable` to match its `panel { group("…") { … } }` structure and its `isModified`/`apply`/`reset` delegation. Add a "Context Window" group:

- A global override field: `row("Max input tokens for all models (0 = model default):") { intTextField().bindIntText(agentSettings.state::maxTokenGlobalOverride) }`
- A `JBTable(maxTokenOverrideTableModel)` hosted via `cell(JBScrollPane(table))`.
- A soft-warning `JBLabel` updated on table edits: if any row `aboveCatalog()`, show "⚠ Some overrides exceed the model's real window — the API may reject requests."

Configurable lifecycle (do NOT rely on `dialogPanel.isModified()` for the table):
- `reset()`: after `dialogPanel?.reset()`, call `tableModel.seed(modelReals = currentCatalogReals(), overridesJson = agentSettings.state.maxTokenPerModelOverrideJson)`. Source `currentCatalogReals()` from `AgentControllerRegistry.getController()?.…`/the shared catalog — map each catalog model id → `getEffectiveContextWindow().catalogMaxInputTokens(id)`. If the catalog is not warmed, seed reals as `null` (UI shows "—").
- `isModified()`: `super/dialogPanel modified` OR `tableModel.toJson() != (agentSettings.state.maxTokenPerModelOverrideJson ?: "{}")`.
- `apply()`: `dialogPanel?.apply()` (persists the global int) THEN `agentSettings.state.maxTokenPerModelOverrideJson = tableModel.toJson()` THEN notify:

```kotlin
        runCatching {
            val reg = com.workflow.orchestrator.agent.ui.AgentControllerRegistry
            reg.getController()?.notifyContextWindowOverridesChanged()
        }
```

Follow `project_intellij_configurable_dialog_panel_pattern`: the Configurable holds the `DialogPanel` reference and delegates `isModified`/`apply`/`reset`.

- [ ] **Step 6: Compile + detekt + manual smoke note**

Run: `./gradlew :agent:compileKotlin :agent:detekt --rerun-tasks`
Expected: BUILD SUCCESSFUL.
Manual (in `runIde`): Settings ▸ Tools ▸ Workflow Orchestrator ▸ AI Agent ▸ Advanced ▸ Context Window — set a per-model override, Apply, confirm the picker row + context bar both reflect it immediately.

- [ ] **Step 7: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/
git commit -m "feat(agent): settings UI for per-model + global context-window overrides

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: Webview verification + final full-suite gate

**Files:**
- Verify: `agent/webview/src/components/input/UsageIndicator.tsx`, model-picker row in `InputBar.tsx`

- [ ] **Step 1: Confirm no webview change needed**

The picker payload already carries `contextWindow.maxInputTokens` (now override-aware from Task 5) and `UsageIndicator` already reads `getContextUsage()` (now selected-model + override aware). Run the existing webview tests:

Run: `cd agent/webview && npm run test`
Expected: existing suite PASS (no behavior change in TS). If a snapshot encodes a hardcoded window, update it.

- [ ] **Step 2: Full agent suite + detekt (the real gate — source-text tests slice whole files)**

Run: `./gradlew :agent:test :agent:detekt --rerun-tasks`
Expected: ALL PASS. (Run the FULL module test, not just `--tests`, per PHASE-RUN-STATUS.md sentinel-slice trap.)

- [ ] **Step 3: verifyPlugin**

Run: `./gradlew verifyPlugin`
Expected: SUCCESS.

- [ ] **Step 4: Commit (if webview snapshots changed) + open PR**

```bash
git add -A
git commit -m "test(agent): webview snapshot updates for override-aware window (if any)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-review notes

- **Spec coverage:** resolver (T1), overrides persistence (T2), runtime key (T3), service wiring (T4), display rewiring incl. selector/bar/TopBar + refresh method + registry getter (T5), hardcoded-fallback (T6), settings UI incl. soft-warning + table (T7), webview + full gate (T8). Two-key design honored: T3 uses `currentModelRef` (running), T5 uses `selectedModelId()` (selected).
- **Sub-agent budget** intentionally unchanged (uses the running-model `effectiveMaxInputTokens()` from T3).
- **Type consistency:** `MaxTokenOverrides(global, perModel)`, `EffectiveContextWindow.maxInputTokens(String?)` / `catalogMaxInputTokens(String)`, `maxTokenGlobalOverride: Int`, `maxTokenPerModelOverrideJson: String?`, `getEffectiveContextWindow()`, `getController()`, `notifyContextWindowOverridesChanged()`, `selectedModelId()` — used consistently across tasks.
- **Implementer NOTEs** flag the two runtime-confirmation points (shared-catalog accessor name in T4; usage-refresh event name in T5) that depend on exact existing symbols.
