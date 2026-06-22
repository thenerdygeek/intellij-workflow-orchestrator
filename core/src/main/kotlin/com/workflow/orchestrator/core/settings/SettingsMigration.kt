package com.workflow.orchestrator.core.settings

/**
 * One-shot, version-gated migrations for [PluginSettings.State].
 *
 * Phase 0a only STAMPS the sentinel. It does NOT materialize current defaults: BaseState omits a
 * field from XML when its value equals the field default (ObjectStoredProperty.setValue
 * short-circuits equal assignments; serialization is gated on isEqualToDefault), so a self-
 * assignment cannot force a value-equals-default field into XML. Instead, the sentinel records
 * "this install existed at/before schema v1", which lets a LATER phase that changes a convention
 * default tell upgraders (settingsSchemaVersion >= 1) apart from brand-new installs (== 0) and
 * write the OLD default literal (now != the NEW default, so it serializes) for upgraders only.
 */
object SettingsMigration {
    const val CURRENT_VERSION = 1

    /** @return true if [state] was mutated. Idempotent at [CURRENT_VERSION]. */
    fun migrate(state: PluginSettings.State): Boolean {
        if (state.settingsSchemaVersion >= CURRENT_VERSION) return false
        // (No default materialization here — see the class KDoc; deferred to Phase 1.)
        state.settingsSchemaVersion = CURRENT_VERSION
        return true
    }
}
