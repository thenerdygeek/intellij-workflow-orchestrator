package com.workflow.orchestrator.core.http

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Project-scoped service that evicts dev-status cache entries whenever a
 * [WorkflowEvent] indicates that the Jira dev-status panel may have changed.
 *
 * **Why coarse-prefix invalidation?**
 * The dev-status keyspace is small (one key per recently-opened ticket) and the
 * reverse-mapping from `prId → ticketKey` would require a secondary index with
 * no measurable win. Evicting the whole `/rest/dev-status/1.0/issue/detail`
 * prefix on each relevant event is simpler, faster, and correct.
 *
 * **Interaction with [MutationInvalidationInterceptor]:**
 * [MutationInvalidationInterceptor] handles HTTP-mutation-driven eviction
 * (e.g. POST/PUT/DELETE responses from Jira). This service handles out-of-band
 * signals that the interceptor cannot see: git branch changes, Bitbucket PR
 * lifecycle events, and Jira ticket switches originating inside the IDE.
 *
 * **TTL floor for teammates' changes:**
 * Own-action freshness latency collapses to ~0ms because the event fires
 * synchronously with the action. Teammates' changes are still bounded by the
 * 60s TTL configured in [CachePolicyRegistry] for the dev-status route —
 * this service does not help with those, by design.
 */
/**
 * Accepts the platform-injected [CoroutineScope] (2024.1+ DI pattern) so the
 * service no longer needs to allocate its own scope or implement [Disposable].
 * The platform owns the lifecycle and cancels the scope when the project closes,
 * providing structured concurrency without manual teardown.
 *
 * Closes audit finding core:F-10.
 */
@Service(Service.Level.PROJECT)
class DevStatusCacheInvalidator(private val project: Project, private val cs: CoroutineScope) {

    fun start() {
        val eventBus = project.service<EventBus>()
        cs.launch {
            eventBus.events.collect { event ->
                handle(event)
            }
        }
    }

    private fun handle(event: WorkflowEvent) {
        if (shouldEvictTest(event)) {
            HttpResponseCache.invalidateByPrefix(DEV_STATUS_PREFIX)
        }
    }

    companion object {
        private const val DEV_STATUS_PREFIX = "/rest/dev-status/1.0/issue/detail"

        internal fun shouldEvictTest(event: WorkflowEvent): Boolean = when (event) {
            is WorkflowEvent.BranchChanged -> true
            is WorkflowEvent.PullRequestCreated -> true
            is WorkflowEvent.PullRequestMerged -> true
            is WorkflowEvent.PullRequestDeclined -> true
            is WorkflowEvent.PullRequestApproved -> true
            is WorkflowEvent.TicketChanged -> true
            else -> false
        }

        internal fun testInstance(
            events: SharedFlow<WorkflowEvent>,
            scope: CoroutineScope,
            invalidator: (String) -> Int
        ): TestableInvalidator = TestableInvalidator(events, scope, invalidator)
    }
}

/**
 * Testable variant that bypasses the [Project] fixture.
 * Collects [events] on [scope] and calls [invalidator] with [DEV_STATUS_PREFIX]
 * whenever [DevStatusCacheInvalidator.shouldEvictTest] returns true.
 */
internal class TestableInvalidator(
    private val events: SharedFlow<WorkflowEvent>,
    private val scope: CoroutineScope,
    private val invalidator: (String) -> Int
) {
    private val prefix = "/rest/dev-status/1.0/issue/detail"

    fun start() {
        scope.launch {
            events.collect { event ->
                if (DevStatusCacheInvalidator.shouldEvictTest(event)) {
                    invalidator(prefix)
                }
            }
        }
    }
}

/**
 * Wires [DevStatusCacheInvalidator] at project startup. Registered as a
 * `<postStartupActivity>` in `plugin.xml`.
 */
class DevStatusCacheInvalidatorActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<DevStatusCacheInvalidator>().start()
    }
}
