package com.workflow.orchestrator.jira.tasks

import com.intellij.tasks.Task
import com.intellij.tasks.impl.BaseRepositoryImpl
import com.workflow.orchestrator.jira.api.dto.JiraIssue
import com.workflow.orchestrator.jira.api.dto.JiraIssueFields
import com.workflow.orchestrator.jira.api.dto.JiraIssueSearchResult
import com.workflow.orchestrator.jira.api.dto.JiraStatus
import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * IntelliJ Tasks integration for Jira Server.
 *
 * Extends [BaseRepositoryImpl] to appear in Tools > Tasks > Open Task.
 * Uses OkHttp directly (not the coroutine-based JiraApiClient)
 * because the Tasks framework invokes these methods on its own background threads.
 */
class JiraTaskRepository : BaseRepositoryImpl {

    private val log = Logger.getInstance(JiraTaskRepository::class.java)
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** No-arg constructor required by the Tasks framework for XML serialization. */
    constructor() : super()

    constructor(type: JiraTaskRepositoryType) : super(type)

    /** Copy constructor used by [clone]. */
    constructor(other: JiraTaskRepository) : super(other)

    override fun clone(): JiraTaskRepository = JiraTaskRepository(this)

    override fun findTask(id: String): Task? {
        val baseUrl = url?.trimEnd('/') ?: return null
        val path = "$baseUrl/rest/api/2/issue/$id"
        val request = Request.Builder()
            .url(path)
            .header("Authorization", "Bearer $password")
            .get()
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            response.use {
                if (it.isSuccessful) {
                    val body = it.body?.string() ?: return null
                    val issue = json.decodeFromString<JiraIssue>(body)
                    JiraTask(issue, baseUrl)
                } else null
            }
        } catch (e: Exception) {
            log.debug("[Jira:Tasks] HTTP error for $path: ${e.message}")
            null
        }
    }

    override fun getIssues(
        query: String?,
        offset: Int,
        limit: Int,
        withClosed: Boolean
    ): Array<Task> {
        val baseUrl = url?.trimEnd('/') ?: return emptyArray()

        val jql = buildJql(query ?: "", withClosed)
        val encodedJql = java.net.URLEncoder.encode(jql, "UTF-8")
        val requestUrl = "$baseUrl/rest/api/2/search?jql=$encodedJql&startAt=$offset&maxResults=$limit"

        val request = Request.Builder()
            .url(requestUrl)
            .header("Authorization", "Bearer $password")
            .get()
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            response.use {
                if (it.isSuccessful) {
                    val body = it.body?.string() ?: return emptyArray()
                    val searchResult = json.decodeFromString<JiraIssueSearchResult>(body)
                    searchResult.issues.map { issue -> JiraTask(issue, baseUrl) }.toTypedArray()
                } else emptyArray()
            }
        } catch (e: Exception) {
            log.debug("[Jira:Tasks] HTTP error for search: ${e.message}")
            emptyArray()
        }
    }

    override fun createCancellableConnection(): CancellableConnection {
        return object : CancellableConnection() {
            private var call: okhttp3.Call? = null

            override fun doTest() {
                val baseUrl = url?.trimEnd('/') ?: throw Exception("URL not configured")
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/2/myself")
                    .header("Authorization", "Bearer $password")
                    .get()
                    .build()

                call = httpClient.newCall(request)
                val response = call!!.execute()
                response.use {
                    if (!it.isSuccessful) {
                        throw Exception("Connection failed: HTTP ${it.code}")
                    }
                }
            }

            override fun cancel() {
                call?.cancel()
            }
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

    private fun escapeJql(text: String): String {
        val reserved = setOf('+', '-', '&', '|', '!', '(', ')', '{', '}', '[', ']', '^', '"', '~', '*', '?', '\\', '/')
        return buildString {
            for (c in text) {
                if (c in reserved) append('\\')
                append(c)
            }
        }
    }
}
