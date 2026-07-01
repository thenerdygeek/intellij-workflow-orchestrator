---
name: intellij-configurable-dialog-panel-pattern
description: IntelliJ Configurable + Kotlin UI DSL v2 — must hold DialogPanel reference and delegate isModified/apply/reset to it. Manual var comparison is a foot-gun because bindSelected setter lambdas only fire during DialogPanel.apply().
metadata: 
  node_type: memory
  type: project
  originSessionId: 04039310-37bc-465a-9e33-d42118392402
---

# IntelliJ Configurable + Kotlin UI DSL v2 — must delegate to DialogPanel

## The rule

When building an IntelliJ Settings page via `createComponent(): JComponent = panel { ... }`, you MUST:

1. Hold a reference to the returned `DialogPanel`
2. Delegate `isModified()` → `dialogPanel?.isModified() ?: false` (or `... == true ||` your-own-extras)
3. Delegate `apply()` → call `dialogPanel?.apply()` BEFORE persisting your local vars to settings
4. Delegate `reset()` → call `dialogPanel?.reset()` AFTER refreshing local vars from settings
5. Clear the reference in `disposeUIResources()`

## Why

`bindSelected({ getter }, { setter })` and similar DSL bind helpers register a binding in the panel's graph. The **setter lambdas are NOT invoked when the user toggles the checkbox** — they're only invoked during `DialogPanel.apply()`. Same for `reset()` calling the getter lambdas to repaint UI from current var values.

So if your Configurable does:

```kotlin
private var enabled = settings.someToggle  // initialized at construction

override fun createComponent() = panel {
    checkBox("Foo").bindSelected({ enabled }, { enabled = it })
}

override fun isModified() = enabled != settings.someToggle  // BUG
```

Then toggling the checkbox does NOT update `enabled`. `enabled` stays at the construction-time value. `isModified()` always returns false. Apply button stays disabled. Settings never persist. Re-opening Settings shows the checkbox at its original state.

## The fix (verified working pattern)

```kotlin
class MyConfigurable(private val project: Project) : Configurable {
    private val settings get() = project.getService(PluginSettings::class.java).state
    private var enabled = settings.someToggle
    private var dialogPanel: DialogPanel? = null

    override fun createComponent(): JComponent {
        val p = panel {
            row {
                checkBox("Foo").bindSelected({ enabled }, { enabled = it })
            }
        }
        dialogPanel = p
        return p
    }

    override fun isModified(): Boolean =
        dialogPanel?.isModified() == true

    override fun apply() {
        dialogPanel?.apply()  // updates `enabled` from the UI via the setter lambda
        settings.someToggle = enabled
        // ... fire any change events ...
    }

    override fun reset() {
        enabled = settings.someToggle
        dialogPanel?.reset()  // refreshes UI from the getter lambda
    }

    override fun disposeUIResources() {
        dialogPanel = null
    }
}
```

## Mixed bindings (live-listener + DSL-bound)

If you have BOTH:
- DSL-bound checkboxes via `bindSelected({get}, {set})` (lazy — only synced during apply/reset)
- Raw Swing components with `addChangeListener { var = it.value }` (eager — updated live)

…then your `isModified()` must check BOTH:

```kotlin
override fun isModified(): Boolean =
    (dialogPanel?.isModified() == true) ||
        // eager-listener-backed vars must be compared manually:
        idleTimeoutMinutes != settings.delegationIdleTimeoutMinutes
```

And `reset()` must refresh the raw Swing component manually since `dialogPanel.reset()` only touches DSL-bound components:

```kotlin
override fun reset() {
    enabled = settings.someToggle
    idleTimeoutMinutes = settings.delegationIdleTimeoutMinutes
    dialogPanel?.reset()
    idleSpinner?.value = idleTimeoutMinutes  // raw component refresh
}
```

## Why: pattern-matching against the working code

`core/src/main/kotlin/com/workflow/orchestrator/core/settings/TelemetryConfigurable.kt` is the canonical reference for this pattern. `CrossIdeDelegationConfigurable.kt` was the broken example until commit `63a58ea1e` fixed it. The bug was latent since Plan 1 (the cross-IDE delegation feature shipped) because no automated test exercises Configurable round-trip — it requires a real Swing component tree, so it only surfaces in interactive use (manual smoke test).

## How to apply

When writing a new Configurable in `:core/settings/` or anywhere else in this project, mirror the TelemetryConfigurable shape. When reviewing a Configurable PR: search for `panel { ... }` returning directly from `createComponent` without storing the result — that's the signature of the bug.
