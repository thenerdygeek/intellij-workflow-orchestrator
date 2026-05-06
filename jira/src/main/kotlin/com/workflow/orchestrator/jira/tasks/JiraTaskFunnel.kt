package com.workflow.orchestrator.jira.tasks

import com.intellij.openapi.diagnostic.Logger
import com.intellij.tasks.Task
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.jira.api.JiraApiClient
import com.workflow.orchestrator.jira.api.escapeJql

/**
 * Pure-Kotlin delegate that owns all funnel-routed Jira calls used by [JiraTaskRepository].
 *
 * Why split this out? `BaseRepositoryImpl`'s constructor reaches into `HttpConfigurable`
 * (a service that requires the IntelliJ `Application` to be initialised), so any test
 * that constructs [JiraTaskRepository] needs `BasePlatformTestCase` — and that fixture
 * fights with other platform tests in the same module over the indexing slot
 * (see `StartWorkDialogActivateOnlyTest` for the precedent). Funnel correctness — that
 * we route through [JiraApiClient] and inherit auth, retry, and the HTML content-type
 * guard — is entirely captured by this class, so we test it directly with `MockWebServer`
 * and skip the heavy fixture.
 *
 * The bridge to suspend is parameterised: production passes
 * `com.intellij.openapi.progress.runBlockingCancellable`; tests pass plain
 * `kotlinx.coroutines.runBlocking`.
 */
internal class JiraTaskFunnel(
    private val apiClient: JiraApiClient,
    private val baseUrlProvider: () -> String?,
    private val bridge: (suspend () -> Any?) -> Any?,
) {
    private val log = Logger.getInstance(JiraTaskFunnel::class.java)

    @Suppress("UNCHECKED_CAST")
    private fun <T> run(block: suspend () -> T): T = bridge(block) as T

    fun findTask(id: String): Task? {
        val baseUrl = baseUrlProvider()?.trimEnd('/') ?: return null
        return when (val result = run { apiClient.getIssue(id) }) {
            is ApiResult.Success -> JiraTask(result.data, baseUrl)
            is ApiResult.Error -> {
                log.debug("[Jira:Tasks] findTask($id) failed: ${result.type} ${result.message}")
                null
            }
        }
    }

    fun getIssues(query: String?, offset: Int, limit: Int, withClosed: Boolean): Array<Task> {
        val baseUrl = baseUrlProvider()?.trimEnd('/') ?: return emptyArray()
        val jql = buildJql(query ?: "", withClosed)

        val result = run { apiClient.searchByJqlPaged(jql, startAt = offset, maxResults = limit) }
        return when (result) {
            is ApiResult.Success -> result.data.map { JiraTask(it, baseUrl) }.toTypedArray()
            is ApiResult.Error -> {
                log.debug("[Jira:Tasks] getIssues failed: ${result.type} ${result.message}")
                emptyArray()
            }
        }
    }

    /** Throws on failure so the Tasks framework's "Test connection" surfaces the message. */
    fun testConnection() {
        if (baseUrlProvider().isNullOrBlank()) throw Exception("URL not configured")
        val result = run { apiClient.getMyselfExpanded() }
        if (result is ApiResult.Error) throw Exception(result.message)
    }

    private fun buildJql(query: String, withClosed: Boolean): String {
        val parts = mutableListOf<String>()

        if (query.isNotBlank()) {
            // If query looks like a Jira key, search by key; otherwise text search
            if (Regex("[A-Z][A-Z0-9]+-\\d+").matches(query)) {
                parts.add("key = \"${escapeJql(query)}\"")
            } else {
                parts.add("summary ~ \"${escapeJql(query)}\"")
            }
        }

        if (!withClosed) {
            parts.add("statusCategory != Done")
        }

        val jql = parts.joinToString(" AND ")
        return if (jql.isNotBlank()) "$jql ORDER BY updated DESC" else "ORDER BY updated DESC"
    }
}
