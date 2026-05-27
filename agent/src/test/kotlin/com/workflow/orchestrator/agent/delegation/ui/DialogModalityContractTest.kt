package com.workflow.orchestrator.agent.delegation.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Source-contract guard for IntelliJ's `DialogWrapper` modality rule.
 *
 * `DialogWrapper.showAndGet()` is implemented as:
 * ```java
 * public boolean showAndGet() {
 *   if (!isModal()) throw new IllegalStateException("The showAndGet() method is for modal dialogs only");
 *   show();
 *   return isOK();
 * }
 * ```
 * A dialog constructed with `IdeModalityType.MODELESS` returns `isModal() == false`, so calling
 * `showAndGet()` on it throws **before the dialog is ever shown**. This is a RUNTIME contract
 * enforced by the platform — it cannot be reached by MockK/headless unit tests because it needs a
 * live `Application` + EDT + window manager. That is exactly why no existing delegation test caught
 * it: `DelegationOutboundService` exposes a `pickTargetOverride` seam and every test injects a fake
 * `PickerEntry`, so the real `pickTarget()` (which constructs `DelegationPicker` and calls
 * `showAndGet()`) has zero execution coverage.
 *
 * These source-text assertions pin the modal/modeless invariant so the mismatch can't silently
 * regress again. They reproduce the 2026-05-24 regression (commit `15b835d69`) where
 * `DelegationPicker` was switched to `IdeModalityType.MODELESS` "so auto-launch works" without
 * updating `DelegationOutboundService.pickTarget`, which still calls `showAndGet()` — crashing
 * EVERY delegation attempt with the IllegalStateException above.
 */
class DialogModalityContractTest {

    // ── Source location ──────────────────────────────────────────────────────

    private fun agentMainRoot(): File {
        val userDir = System.getProperty("user.dir") ?: error("user.dir system property is not set")
        val root = File(userDir)
        val moduleRooted = File(root, "src/main/kotlin")        // user.dir == <repo>/agent
        val repoRooted = File(root, "agent/src/main/kotlin")    // user.dir == <repo>
        return when {
            moduleRooted.isDirectory -> moduleRooted
            repoRooted.isDirectory -> repoRooted
            else -> error("agent main sources not found at either layout; user.dir=$userDir")
        }
    }

    private fun source(relPathFromKotlinRoot: String): String {
        val f = File(agentMainRoot(), relPathFromKotlinRoot)
        assertTrue(f.isFile, "Expected source file not found: ${f.absolutePath} — module layout may have changed.")
        return f.readText()
    }

    private fun allMainKtFiles(): List<File> =
        agentMainRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** Simple-names of every `DialogWrapper` subclass declared with `IdeModalityType.MODELESS`. */
    private fun modelessDialogClassNames(): Set<String> {
        val marker = Regex(""":\s*DialogWrapper\([^)]*MODELESS""")
        val classDecl = Regex("""\bclass\s+(\w+)""")
        val names = mutableSetOf<String>()
        for (f in allMainKtFiles()) {
            val text = f.readText()
            for (m in marker.findAll(text)) {
                // The nearest `class X` declaration before the MODELESS supertype marker is the
                // owning class (handles files with preceding data classes / helper classes).
                val cls = classDecl.findAll(text.substring(0, m.range.first)).lastOrNull()?.groupValues?.get(1)
                if (cls != null) names.add(cls)
            }
        }
        return names
    }

    // ── Test 1 — specific reproduction ───────────────────────────────────────

    @Test
    fun `DelegationPicker must be modal because pickTarget shows it via showAndGet`() {
        val picker = source("com/workflow/orchestrator/agent/delegation/ui/DelegationPicker.kt")
        val outbound = source("com/workflow/orchestrator/agent/delegation/DelegationOutboundService.kt")

        // Anchor: confirm the call path the assertion depends on still exists.
        val constructsPicker = Regex("""\bDelegationPicker\(""").containsMatchIn(outbound)
        val callsShowAndGet = outbound.contains(".showAndGet()")
        assertTrue(
            constructsPicker && callsShowAndGet,
            "Test anchor moved: expected DelegationOutboundService to construct DelegationPicker and " +
                "call showAndGet(). If pickTarget was rewritten to show()+await, update this test.",
        )

        val pickerIsModeless = Regex(""":\s*DialogWrapper\([^)]*MODELESS""").containsMatchIn(picker)
        assertFalse(
            pickerIsModeless,
            "DelegationPicker is declared IdeModalityType.MODELESS but DelegationOutboundService.pickTarget " +
                "shows it via showAndGet(), which throws " +
                "IllegalStateException(\"The showAndGet() method is for modal dialogs only\") at runtime — " +
                "breaking every delegation attempt. Fix: make the picker modal (drop MODELESS), or change " +
                "pickTarget to show() + await-on-close.",
        )
    }

    // ── Test 2 — module-wide invariant (catches the picker + any future case) ─

    @Test
    fun `no MODELESS dialog is shown via showAndGet anywhere in the agent module`() {
        val modeless = modelessDialogClassNames()
        assertTrue(
            modeless.isNotEmpty(),
            "Expected at least one MODELESS dialog (e.g. DelegationInboundConsentDialog). " +
                "If none remain, delete this assertion — but more likely the scan regex broke.",
        )

        val violations = mutableListOf<String>()
        for (f in allMainKtFiles()) {
            val text = f.readText()
            for (cls in modeless) {
                // `val <v> = <Cls>(` ... and somewhere in the same file `<v>.showAndGet()`.
                for (ctor in Regex("""\b(\w+)\s*=\s*$cls\(""").findAll(text)) {
                    val varName = ctor.groupValues[1]
                    if (Regex("""\b${Regex.escape(varName)}\.showAndGet\(\)""").containsMatchIn(text)) {
                        violations.add("${f.name}: `$varName = $cls(...)` then `$varName.showAndGet()`")
                    }
                }
            }
        }
        assertTrue(
            violations.isEmpty(),
            "MODELESS DialogWrapper subclasses must be shown via show() (then read isOK), never showAndGet() " +
                "— showAndGet() throws for non-modal dialogs. Offending sites:\n  " +
                violations.joinToString("\n  "),
        )
    }

    // ── Test 3 — positive contract for the known-good MODELESS dialog ─────────

    @Test
    fun `DelegationInboundConsentDialog stays MODELESS and is shown via show not showAndGet`() {
        // The consent dialog is intentionally MODELESS (it must not block the doorbell EDT while the
        // delegator's open-project request is dispatched via invokeLater(NON_MODAL)). It correctly
        // uses show(); this pins the good pattern so a "simplification" to showAndGet can't sneak in.
        val consent = source("com/workflow/orchestrator/agent/delegation/ui/DelegationInboundConsentDialog.kt")
        assertTrue(
            Regex(""":\s*DialogWrapper\([^)]*MODELESS""").containsMatchIn(consent),
            "DelegationInboundConsentDialog is expected to remain IdeModalityType.MODELESS.",
        )
        assertTrue(
            "DelegationInboundConsentDialog" in modelessDialogClassNames(),
            "Scanner should classify DelegationInboundConsentDialog as MODELESS.",
        )
    }
}
