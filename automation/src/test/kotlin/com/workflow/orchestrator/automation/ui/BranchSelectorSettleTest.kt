package com.workflow.orchestrator.automation.ui

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests for [resolveBranchSettleAction] — the extracted decision for what the branch
 * combo should do when a selection SETTLES (the user finished choosing: dropdown closed
 * or focus left), as opposed to the transient model-selection changes that
 * `ComboboxSpeedSearch` fires on every keystroke.
 *
 * The original bug: the per-keystroke action listener prompted to enable a disabled
 * branch as soon as speed-search moved the selection onto it while the user was still
 * type-filtering. The fix defers the enable prompt to a deliberate commit and never
 * prompts while the user is still navigating (popup open) or while an enable is already
 * in flight. These cases pin that behaviour.
 */
class BranchSelectorSettleTest {

    private fun item(key: String?, label: String, enabled: Boolean) =
        BranchComboItem(branchKey = key, label = label, enabled = enabled)

    @Test
    fun `settling on a new disabled branch with popup closed prompts to enable`() {
        val action = resolveBranchSettleAction(
            selected = item("PROJ-PLAN0", "feature-x", enabled = false),
            committed = null,
            popupVisible = false,
            enableInFlight = false,
        )
        assertTrue(action is BranchSettleAction.PromptEnable, "Deliberate pick of a disabled branch should prompt, got $action")
        assertEquals("PROJ-PLAN0", (action as BranchSettleAction.PromptEnable).item.branchKey)
    }

    @Test
    fun `popup still visible never prompts (user is navigating or type-filtering)`() {
        val action = resolveBranchSettleAction(
            selected = item("PROJ-PLAN0", "feature-x", enabled = false),
            committed = null,
            popupVisible = true,
            enableInFlight = false,
        )
        assertEquals(BranchSettleAction.Ignore, action,
            "While the dropdown is open the selection is transient — must NOT prompt (the reported bug)")
    }

    @Test
    fun `enable already in flight never prompts again`() {
        val action = resolveBranchSettleAction(
            selected = item("PROJ-PLAN0", "feature-x", enabled = false),
            committed = null,
            popupVisible = false,
            enableInFlight = true,
        )
        assertEquals(BranchSettleAction.Ignore, action,
            "A second settle while the async enable is running must not stack another dialog")
    }

    @Test
    fun `enabled branch never prompts`() {
        val action = resolveBranchSettleAction(
            selected = item("PROJ-PLAN1", "feature-y", enabled = true),
            committed = null,
            popupVisible = false,
            enableInFlight = false,
        )
        assertEquals(BranchSettleAction.Ignore, action)
    }

    @Test
    fun `default branch (null key) never prompts`() {
        val action = resolveBranchSettleAction(
            selected = item(null, "default", enabled = true),
            committed = null,
            popupVisible = false,
            enableInFlight = false,
        )
        assertEquals(BranchSettleAction.Ignore, action)
    }

    @Test
    fun `disabled branch identical to the committed selection does not re-prompt`() {
        val committed = item("PROJ-PLAN0", "feature-x", enabled = false)
        val action = resolveBranchSettleAction(
            selected = item("PROJ-PLAN0", "feature-x", enabled = false),
            committed = committed,
            popupVisible = false,
            enableInFlight = false,
        )
        assertEquals(BranchSettleAction.Ignore, action,
            "If the settled branch is already the committed one, the user didn't pick a new disabled branch")
    }

    @Test
    fun `no selection is ignored`() {
        val action = resolveBranchSettleAction(
            selected = null,
            committed = null,
            popupVisible = false,
            enableInFlight = false,
        )
        assertEquals(BranchSettleAction.Ignore, action)
    }
}
