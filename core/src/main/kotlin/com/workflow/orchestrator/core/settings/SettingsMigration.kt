package com.workflow.orchestrator.core.settings

/**
 * One-shot, version-gated migrations for [PluginSettings.State].
 *
 * BaseState omits a field from XML when its value equals the field default
 * (ObjectStoredProperty serialization is gated on isEqualToDefault), so a self-assignment cannot
 * force a value-equals-default field into XML. Phase 0a exploited this by STAMPING a sentinel
 * (settingsSchemaVersion: existing installs -> 1, fresh installs -> 0) so a later phase that
 * blanks a convention default can tell upgraders apart from brand-new installs.
 *
 * Phase 1a (this version, v1 -> v2) blanks [PluginSettings.State.defaultTargetBranch] from the
 * company convention "develop" to the neutral [NEUTRAL_DEFAULT_TARGET_BRANCH]. For an upgrader
 * (settingsSchemaVersion >= 1) whose field still equals the NEW neutral default — i.e. it was
 * omitted from their XML because they never set it — we seed the OLD literal "develop". That
 * literal now differs from the default, so it serializes and persists; the upgrade is behavior-
 * preserving. A fresh install (== 0) is left on the neutral default.
 *
 * Known edge (documented, accepted): an install that jumps straight from a PRE-0a build (which
 * never stamped the sentinel) to this build presents as v0 and is treated as fresh -> it would
 * flip to "main". This does not occur in the internal-first rollout (every real install passed
 * through 0a, so it is at v1), and there are no pre-existing open-source installs.
 */
object SettingsMigration {
    const val CURRENT_VERSION = 2

    /** The company default for [PluginSettings.State.defaultTargetBranch] before Phase 1a. */
    private const val LEGACY_DEFAULT_TARGET_BRANCH = "develop"

    /** @return true if [state] was mutated. Idempotent at [CURRENT_VERSION]. */
    fun migrate(state: PluginSettings.State): Boolean {
        if (state.settingsSchemaVersion >= CURRENT_VERSION) return false

        // v1 -> v2: seed legacy convention defaults for installs that existed before the blanking.
        if (state.settingsSchemaVersion in 1 until CURRENT_VERSION) {
            seedLegacyConventionDefaults(state)
        }

        state.settingsSchemaVersion = CURRENT_VERSION
        return true
    }

    /**
     * Seeds pre-Phase-1a company defaults onto fields the upgrader never explicitly set.
     *
     * Seed only when the field still equals the NEW neutral default — that is the signal it was
     * omitted from the upgrader's XML (relying on the old code default) rather than explicitly
     * chosen. The global defaultTargetBranch has no settings-UI editor today, so "explicitly set
     * to the neutral value" is not reachable; the guard documents intent and future-proofs a UI.
     */
    private fun seedLegacyConventionDefaults(state: PluginSettings.State) {
        if (state.defaultTargetBranch == NEUTRAL_DEFAULT_TARGET_BRANCH) {
            state.defaultTargetBranch = LEGACY_DEFAULT_TARGET_BRANCH
        }
    }
}
