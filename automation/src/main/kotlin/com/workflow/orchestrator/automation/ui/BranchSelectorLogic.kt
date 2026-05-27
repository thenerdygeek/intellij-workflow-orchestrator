package com.workflow.orchestrator.automation.ui

/**
 * What the branch combo should do when a selection SETTLES — i.e. the user finished
 * choosing (dropdown closed or focus left the combo) — as opposed to the transient
 * model-selection changes that [com.intellij.ui.ComboboxSpeedSearch] fires on every
 * keystroke while type-filtering.
 *
 * Extracted as pure logic so the decision is unit-testable without Swing, mirroring
 * [com.workflow.orchestrator.automation.service.resolveTriggerDefaultAction].
 */
internal sealed interface BranchSettleAction {
    /** Nothing to do — still navigating, mid-enable, unchanged, or an enabled/default branch. */
    object Ignore : BranchSettleAction

    /** The user settled on a (new) disabled branch plan — prompt to enable it in Bamboo. */
    data class PromptEnable(val item: BranchComboItem) : BranchSettleAction
}

/**
 * Decide whether settling on [selected] should raise the "enable disabled branch" prompt.
 *
 * The enable prompt is reserved for a deliberate commit of a disabled branch. It must NOT
 * fire when:
 *  - [popupVisible] — the dropdown is open, so the selection is still transient (this is
 *    the keystroke-driven speed-search selection that caused the original bug);
 *  - [enableInFlight] — an enable call is already running, so a second settle would stack
 *    another dialog;
 *  - [selected] is null, the default branch (`branchKey == null`), or already enabled;
 *  - [selected] is the same branch already committed (the user didn't pick a new one).
 */
internal fun resolveBranchSettleAction(
    selected: BranchComboItem?,
    committed: BranchComboItem?,
    popupVisible: Boolean,
    enableInFlight: Boolean,
): BranchSettleAction {
    if (popupVisible || enableInFlight) return BranchSettleAction.Ignore
    if (selected == null) return BranchSettleAction.Ignore
    if (selected.branchKey == null || selected.enabled) return BranchSettleAction.Ignore
    if (selected.branchKey == committed?.branchKey) return BranchSettleAction.Ignore
    return BranchSettleAction.PromptEnable(selected)
}
