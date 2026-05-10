package com.workflow.orchestrator.automation.service

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.xmlb.annotations.MapAnnotation
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Application-level persistence for automation suites + per-suite variables.
 *
 * **Roaming (PR 7 #7).** `roamingType = RoamingType.DEFAULT` lets IntelliJ's
 * Settings Sync feature carry suites and their custom variables across the
 * user's machines — so a developer who configures `featureFlag=true` on
 * Mac sees the same value pre-filled on their Windows test box. The XML
 * filename was stable (`workflowAutomationSuites.xml`) before this change;
 * we keep it identical so existing on-disk state still loads on upgrade.
 *
 * **Why APP scope.** Suites are organisational artefacts — every project the
 * user opens against the same Bamboo instance shares them. Project-scoping
 * would force the user to re-add every suite per project, which they
 * explicitly pushed back on.
 */
@Service(Service.Level.APP)
@State(
    name = "AutomationSuiteSettings",
    storages = [Storage("workflowAutomationSuites.xml", roamingType = RoamingType.DEFAULT)]
)
class AutomationSettingsService : PersistentStateComponent<AutomationSettingsService.SettingsState> {

    private val log = Logger.getInstance(AutomationSettingsService::class.java)

    /**
     * Per-session deduplication set for stale-stage notifications (Should-fix 3).
     *
     * Populated when [getSuiteDefaultStages] finds that a suite's saved default stages
     * no longer match the current plan and fires the user-visible balloon. Cleared when
     * [setSuiteDefaultStages] is called for that suite (so a fresh save followed by a
     * later re-stalening re-fires the notification as expected).
     */
    private val staleStagedNotifiedSuites: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Tag("suite")
    data class SuiteConfig(
        var planKey: String = "",
        var displayName: String = "",
        /**
         * Plan-scoped variables: keys MUST exist in the suite's Bamboo plan
         * `?expand=variableContext` response. Used by the existing variable
         * picker dropdown in [com.workflow.orchestrator.automation.ui.SuiteConfigPanel].
         */
        @MapAnnotation(surroundWithTag = false, entryTagName = "variable", keyAttributeName = "key", valueAttributeName = "value")
        var variables: MutableMap<String, String> = mutableMapOf(),
        var enabledStages: MutableList<String> = mutableListOf(),
        @MapAnnotation(surroundWithTag = false, entryTagName = "mapping", keyAttributeName = "docker", valueAttributeName = "service")
        var serviceNameMapping: MutableMap<String, String>? = null,
        var lastModified: Long = 0,
        /**
         * Per-suite default stage selection (Phase H, H3).
         *
         * When non-null, the default Trigger button click uses this set instead of
         * opening the customize dialog. Persisted as a list of stage names so the
         * IntelliJ XML serializer can round-trip it.
         *
         * Null means "no default configured" — the customize dialog will open on
         * every default-click. Empty list is equivalent to null on read.
         *
         * Never falls back silently to "all stages" or "first stage" — see H2 /
         * [com.workflow.orchestrator.automation.ui.AutomationPanel.onTriggerDefault].
         */
        var defaultStages: MutableList<String>? = null
    )

    data class SettingsState(
        @XMap(entryTagName = "suite", keyAttributeName = "planKey")
        var suites: MutableMap<String, SuiteConfig> = mutableMapOf()
    )

    private var myState = SettingsState()

    override fun getState(): SettingsState = myState

    override fun loadState(state: SettingsState) {
        myState = state
    }

    fun getSuiteConfig(planKey: String): SuiteConfig? = myState.suites[planKey]

    fun saveSuiteConfig(config: SuiteConfig) {
        myState.suites[config.planKey] = config
    }

    fun getAllSuites(): List<SuiteConfig> = myState.suites.values.toList()

    /**
     * Returns the saved default stages for [suitePlanKey], after applying the
     * stale-stage filter (H7).
     *
     * The filter intersects the saved set against [currentPlanStages]. If the
     * intersection is empty and the raw saved set was non-empty, the saved
     * default is considered stale — logs a warning and returns `null` so the
     * caller opens the customize dialog.
     *
     * @param currentPlanStages the current stage names for this plan, fetched
     *   from Bamboo immediately before calling this. Pass `null` to skip the
     *   stale filter (stages not yet loaded).
     * @return the intersection of saved stages with current plan stages, or
     *   `null` when no default is configured or the saved set is entirely stale.
     */
    fun getSuiteDefaultStages(
        suitePlanKey: String,
        currentPlanStages: Set<String>? = null
    ): Set<String>? {
        val raw = myState.suites[suitePlanKey]?.defaultStages
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .takeIf { !it.isNullOrEmpty() }
            ?: return null

        if (currentPlanStages == null) {
            // No stage list available yet — return raw saved set without filtering.
            // The caller is responsible for re-filtering once stages are loaded.
            return raw
        }

        val intersection = raw.intersect(currentPlanStages)
        return if (intersection.isEmpty()) {
            // Saved stages are entirely stale — none match the current plan.
            log.warn(
                "[Automation:Settings] Saved default stages for $suitePlanKey no longer match " +
                    "current plan stages. Saved=$raw, Current=$currentPlanStages. " +
                    "Treating as 'no default configured'. Reconfigure in Settings."
            )
            notifyStaleStagesOnce(suitePlanKey)
            null
        } else {
            if (intersection.size < raw.size) {
                log.info(
                    "[Automation:Settings] Some saved default stages for $suitePlanKey are stale and were removed. " +
                        "Stale=${raw - intersection}. Using intersection=$intersection."
                )
            }
            intersection
        }
    }

    /**
     * Persists the per-suite default stage selection for [suitePlanKey].
     *
     * Clears the stale-stage notification dedup entry so that if the newly saved
     * default goes stale in a future session the user receives a fresh notification.
     *
     * @param stages the stage names to save as the default selection, or `null` to
     *   clear the default (next default-click will open the customize dialog).
     */
    fun setSuiteDefaultStages(suitePlanKey: String, stages: Set<String>?) {
        val existing = myState.suites[suitePlanKey]
        val updated = (existing ?: SuiteConfig(planKey = suitePlanKey, displayName = suitePlanKey))
            .also {
                it.defaultStages = stages?.toMutableList()
                it.lastModified = System.currentTimeMillis()
            }
        myState.suites[suitePlanKey] = updated
        // Reset dedup so a future stale check re-fires the notification.
        staleStagedNotifiedSuites.remove(suitePlanKey)
        log.info("[Automation:Settings] setSuiteDefaultStages: planKey=$suitePlanKey, stages=$stages")
    }

    /**
     * Fires a one-time-per-session balloon notification telling the user that
     * the saved default stages for [suitePlanKey] no longer match the current
     * Bamboo plan stages. The notification is suppressed for subsequent calls
     * in the same IDE session to avoid repeating the same balloon on every
     * Trigger click until the user reconfigures.
     *
     * The dedup entry is cleared by [setSuiteDefaultStages] so a fresh save
     * followed by a later re-stalening re-fires as expected.
     */
    private fun notifyStaleStagesOnce(suitePlanKey: String) {
        if (!staleStagedNotifiedSuites.add(suitePlanKey)) {
            // Already notified this session for this suite — suppress.
            return
        }
        try {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("workflow.automation")
                .createNotification(
                    "Saved default stages no longer match plan",
                    "$suitePlanKey's saved default stages are no longer present in Bamboo. " +
                        "Reconfigure in Settings → Automation.",
                    NotificationType.WARNING
                )
                .notify(null) // null = broadcast to all open project windows
        } catch (e: Exception) {
            // NotificationGroupManager may return null during unit tests
            // (no IntelliJ Application context). Swallow gracefully.
            log.warn("[Automation:Settings] Could not fire stale-stages notification: ${e.message}")
        }
    }

    companion object {
        fun getInstance(): AutomationSettingsService =
            ApplicationManager.getApplication().getService(AutomationSettingsService::class.java)
    }
}
