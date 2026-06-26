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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Caches whether the connected Jira exposes the Agile (/rest/agile/1.0) board API.
 * Used to hide the Sprint tab on non-Software (Core/Service-Management) Jira.
 *
 * Tri-state verdict: true = available, false = unavailable (404 → hide),
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
        val url = PluginSettings.getInstance(project).connections.jiraUrl
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
                val apiClient = JiraServiceImpl.getInstance(project).getApiClient()
                val result = if (apiClient != null) classifyProbe(apiClient.getBoards()) else null
                if (result != null) {
                    // Write the DATA before the lookup KEY: a concurrent agileAvailableOrProbe()
                    // reader that sees the new probedUrl is then guaranteed to see this verdict
                    // too (otherwise it could match the key but read a stale null verdict).
                    verdict = result
                    probedUrl = url
                    log.info("[Jira:Agile] Agile capability for '$url' = $result")
                    if (result == false) { // true never changes visibility (fail-open already shows the tab)
                        project.service<EventBus>().emit(WorkflowEvent.TabAvailabilityChanged("Sprint"))
                    }
                } else {
                    log.debug("[Jira:Agile] Agile probe inconclusive for '$url' (transient); will retry")
                }
            } catch (e: CancellationException) {
                // Honor structured concurrency: never swallow scope cancellation (project/IDE
                // dispose). finally { probing = false } still runs before this propagates.
                throw e
            } catch (e: Exception) {
                log.debug("[Jira:Agile] Agile probe for '$url' threw unexpectedly: ${e.message}")
            } finally {
                // Reset probing flag even if emit/service-resolution fails (e.g. during disposal),
                // so future probes are not permanently blocked for this instance.
                probing = false
            }
        }
    }

    companion object {
        fun getInstance(project: Project): JiraAgileCapabilityService =
            project.service()

        /** Pure: Success → true; NOT_FOUND → false; any other error → null (unknown). */
        fun classifyProbe(result: ApiResult<*>): Boolean? = when (result) {
            is ApiResult.Success -> true
            is ApiResult.Error -> if (result.type == ErrorType.NOT_FOUND) false else null
        }
    }
}
