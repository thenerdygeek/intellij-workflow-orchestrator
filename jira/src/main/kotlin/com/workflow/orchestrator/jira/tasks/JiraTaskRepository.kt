package com.workflow.orchestrator.jira.tasks

import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.tasks.Task
import com.intellij.tasks.impl.BaseRepositoryImpl
import com.workflow.orchestrator.jira.api.JiraApiClient

/**
 * IntelliJ Tasks integration for Jira Server.
 *
 * Extends [BaseRepositoryImpl] to appear in Tools > Tasks > Open Task. The Tasks
 * framework persists this repository in IDE-level settings (Tools > Tasks > Servers),
 * so no [com.intellij.openapi.project.Project] reference is available — that rules out
 * `project.getService(JiraServiceImpl::class.java)`.
 *
 * Instead we instantiate [JiraApiClient] directly with `(baseUrl, tokenProvider)`. The
 * client internally uses the same `HttpClientFactory.clientFor(ServiceType.JIRA)` pool
 * the rest of the plugin uses, so all funnel cross-cutting concerns are inherited:
 * auth interceptor, retry, metrics, sensitive-endpoint protection, and the HTML
 * content-type guard (which maps a 200-with-HTML auth-redirect to AUTH_FAILED).
 *
 * All HTTP work is delegated to [JiraTaskFunnel] (a plain class that doesn't extend the
 * platform's heavy `BaseRepositoryImpl`), which is what tests exercise directly with
 * `MockWebServer`. Tasks framework callbacks run on background threads, so we bridge
 * to suspending client methods via [runBlockingCancellable] — that propagates the
 * framework's `ProgressIndicator.cancel()` into coroutine cancellation.
 */
class JiraTaskRepository : BaseRepositoryImpl {

    private val funnel: JiraTaskFunnel by lazy {
        val client = JiraApiClient(
            baseUrl = url?.trimEnd('/') ?: "",
            tokenProvider = { password }
        )
        JiraTaskFunnel(
            apiClient = client,
            baseUrlProvider = { url },
            bridge = { block -> runBlockingCancellable { block() } }
        )
    }

    /** No-arg constructor required by the Tasks framework for XML serialization. */
    constructor() : super()

    constructor(type: JiraTaskRepositoryType) : super(type)

    /** Copy constructor used by [clone]. */
    constructor(other: JiraTaskRepository) : super(other)

    override fun clone(): JiraTaskRepository = JiraTaskRepository(this)

    override fun findTask(id: String): Task? = funnel.findTask(id)

    override fun getIssues(
        query: String?,
        offset: Int,
        limit: Int,
        withClosed: Boolean
    ): Array<Task> = funnel.getIssues(query, offset, limit, withClosed)

    override fun createCancellableConnection(): CancellableConnection {
        return object : CancellableConnection() {
            override fun doTest() = funnel.testConnection()

            // runBlockingCancellable propagates ProgressIndicator cancellation through
            // coroutine cancellation, so explicit cancel() is best-effort and not needed
            // for typical "test connection" timeouts.
            override fun cancel() = Unit
        }
    }

    override fun isConfigured(): Boolean {
        return super.isConfigured() && !url.isNullOrBlank() && !password.isNullOrBlank()
    }

    override fun extractId(taskName: String): String? {
        // Match Jira-style keys like PROJ-123
        val match = Regex("[A-Z][A-Z0-9]+-\\d+").find(taskName)
        return match?.value
    }
}
