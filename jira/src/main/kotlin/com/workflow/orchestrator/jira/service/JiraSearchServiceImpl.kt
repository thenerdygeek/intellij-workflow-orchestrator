package com.workflow.orchestrator.jira.service

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.model.jira.ComponentSuggestion
import com.workflow.orchestrator.core.model.jira.FieldOption
import com.workflow.orchestrator.core.model.jira.GroupSuggestion
import com.workflow.orchestrator.core.model.jira.LabelSuggestion
import com.workflow.orchestrator.core.model.jira.UserSuggestion
import com.workflow.orchestrator.core.model.jira.VersionSuggestion
import com.workflow.orchestrator.core.services.ToolResult
import com.workflow.orchestrator.core.services.jira.JiraSearchService
import com.workflow.orchestrator.core.settings.ConnectionSettings
import com.workflow.orchestrator.jira.api.JiraApiClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Implementation of [JiraSearchService] that delegates to [JiraApiClient.getRawString]
 * and traverses the JSON manually via kotlinx.serialization.
 *
 * Versions and components are cached per project key with a 5-minute TTL to avoid
 * hammering the server on every keystroke in a picker.
 *
 * Registration in plugin.xml is deferred to task 18.
 */
class JiraSearchServiceImpl : JiraSearchService {

    private val log = Logger.getInstance(JiraSearchServiceImpl::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /** Injected in unit tests; null means [createClient] builds one from settings. */
    internal var testClient: JiraApiClient? = null

    @Volatile private var cachedClient: Pair<String, JiraApiClient>? = null

    private fun createClient(): JiraApiClient? {
        testClient?.let { return it }
        val url = ConnectionSettings.getInstance().state.jiraUrl.orEmpty().trimEnd('/')
        if (url.isBlank()) return null
        cachedClient?.let { (u, c) -> if (u == url) return c }
        val credentialStore = CredentialStore()
        val c = JiraApiClient(
            baseUrl = url,
            tokenProvider = { credentialStore.getToken(ServiceType.JIRA) }
        )
        cachedClient = url to c
        return c
    }

    // ── Cache support ──────────────────────────────────────────────────────────

    private data class CacheEntry<T>(val value: T, val expiresAt: Long)

    private val versionsCache = ConcurrentHashMap<String, CacheEntry<List<VersionSuggestion>>>()
    private val componentsCache = ConcurrentHashMap<String, CacheEntry<List<ComponentSuggestion>>>()

    private val cacheTtlMs = 5 * 60_000L

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Returns a ToolResult.Error with isError=true for any ApiResult.Error. */
    private fun <T> apiError(error: ApiResult.Error, defaultData: T): ToolResult<T> =
        ToolResult(
            data = defaultData,
            summary = "Jira error (${error.type}): ${error.message}",
            isError = true
        )

    private fun notConfigured(): String =
        "Jira not configured. Set up connection in Settings > Tools > Workflow Orchestrator > General."

    // ── 1. searchAssignableUsers ───────────────────────────────────────────────

    override suspend fun searchAssignableUsers(
        ticketKey: String,
        query: String,
        limit: Int
    ): ToolResult<List<UserSuggestion>> {
        val api = createClient() ?: return ToolResult(
            data = emptyList(),
            summary = notConfigured(),
            isError = true
        )
        val path = "/rest/api/2/user/assignable/search" +
            "?issueKey=${enc(ticketKey)}&query=${enc(query)}&maxResults=$limit"
        log.debug("[JiraSearch] GET $path")
        return when (val result = api.getRawString(path)) {
            is ApiResult.Error -> apiError(result, emptyList())
            is ApiResult.Success -> {
                val users = parseUsers(result.data)
                ToolResult.success(
                    data = users,
                    summary = "Found ${users.size} assignable user(s) for $ticketKey matching '$query'."
                )
            }
        }
    }

    // ── 2. searchUsers ─────────────────────────────────────────────────────────

    override suspend fun searchUsers(query: String, limit: Int): ToolResult<List<UserSuggestion>> {
        val api = createClient() ?: return ToolResult(
            data = emptyList(),
            summary = notConfigured(),
            isError = true
        )
        val path = "/rest/api/2/user/search?query=${enc(query)}&maxResults=$limit"
        log.debug("[JiraSearch] GET $path")
        return when (val result = api.getRawString(path)) {
            is ApiResult.Error -> apiError(result, emptyList())
            is ApiResult.Success -> {
                val users = parseUsers(result.data)
                ToolResult.success(
                    data = users,
                    summary = "Found ${users.size} user(s) matching '$query'."
                )
            }
        }
    }

    // ── 3. suggestLabels ──────────────────────────────────────────────────────

    override suspend fun suggestLabels(query: String, limit: Int): ToolResult<List<LabelSuggestion>> {
        val api = createClient() ?: return ToolResult(
            data = emptyList(),
            summary = notConfigured(),
            isError = true
        )
        val path = "/rest/api/1.0/labels/suggest?query=${enc(query)}"
        log.debug("[JiraSearch] GET $path")
        val statusCode = IntArray(1) { 0 }
        return when (val result = api.getRawString(path, statusCode)) {
            is ApiResult.Error -> {
                // 404 means label suggest endpoint is not available on this instance → empty list
                if (statusCode[0] == 404) {
                    log.debug("[JiraSearch] Label suggest endpoint not available (404) — returning empty list.")
                    ToolResult.success(data = emptyList(), summary = "Label suggestions not available on this Jira instance.")
                } else {
                    apiError(result, emptyList())
                }
            }
            is ApiResult.Success -> {
                val labels = parseLabelSuggestions(result.data)
                ToolResult.success(
                    data = labels,
                    summary = "Found ${labels.size} label suggestion(s) matching '$query'."
                )
            }
        }
    }

    // ── 4. searchGroups ───────────────────────────────────────────────────────

    override suspend fun searchGroups(query: String, limit: Int): ToolResult<List<GroupSuggestion>> {
        val api = createClient() ?: return ToolResult(
            data = emptyList(),
            summary = notConfigured(),
            isError = true
        )
        val path = "/rest/api/2/groups/picker?query=${enc(query)}&maxResults=$limit"
        log.debug("[JiraSearch] GET $path")
        return when (val result = api.getRawString(path)) {
            is ApiResult.Error -> apiError(result, emptyList())
            is ApiResult.Success -> {
                val groups = parseGroups(result.data)
                ToolResult.success(
                    data = groups,
                    summary = "Found ${groups.size} group(s) matching '$query'."
                )
            }
        }
    }

    // ── 5. listVersions ───────────────────────────────────────────────────────

    override suspend fun listVersions(projectKey: String): ToolResult<List<VersionSuggestion>> {
        val now = System.currentTimeMillis()
        versionsCache[projectKey]?.let { entry ->
            if (entry.expiresAt > now) {
                log.debug("[JiraSearch] versions cache hit for $projectKey")
                return ToolResult.success(
                    data = entry.value,
                    summary = "Found ${entry.value.size} version(s) for $projectKey (cached)."
                )
            }
        }

        val api = createClient() ?: return ToolResult(
            data = emptyList(),
            summary = notConfigured(),
            isError = true
        )
        val path = "/rest/api/2/project/${enc(projectKey)}/versions"
        log.debug("[JiraSearch] GET $path")
        return when (val result = api.getRawString(path)) {
            is ApiResult.Error -> apiError(result, emptyList())
            is ApiResult.Success -> {
                val versions = parseVersions(result.data)
                versionsCache[projectKey] = CacheEntry(versions, now + cacheTtlMs)
                ToolResult.success(
                    data = versions,
                    summary = "Found ${versions.size} version(s) for $projectKey."
                )
            }
        }
    }

    // ── 6. listComponents ────────────────────────────────────────────────────

    override suspend fun listComponents(projectKey: String): ToolResult<List<ComponentSuggestion>> {
        val now = System.currentTimeMillis()
        componentsCache[projectKey]?.let { entry ->
            if (entry.expiresAt > now) {
                log.debug("[JiraSearch] components cache hit for $projectKey")
                return ToolResult.success(
                    data = entry.value,
                    summary = "Found ${entry.value.size} component(s) for $projectKey (cached)."
                )
            }
        }

        val api = createClient() ?: return ToolResult(
            data = emptyList(),
            summary = notConfigured(),
            isError = true
        )
        val path = "/rest/api/2/project/${enc(projectKey)}/components"
        log.debug("[JiraSearch] GET $path")
        return when (val result = api.getRawString(path)) {
            is ApiResult.Error -> apiError(result, emptyList())
            is ApiResult.Success -> {
                val components = parseComponents(result.data)
                componentsCache[projectKey] = CacheEntry(components, now + cacheTtlMs)
                ToolResult.success(
                    data = components,
                    summary = "Found ${components.size} component(s) for $projectKey."
                )
            }
        }
    }

    // ── 7. followAutoCompleteUrl ──────────────────────────────────────────────

    override suspend fun followAutoCompleteUrl(url: String, query: String): ToolResult<List<FieldOption>> {
        val api = createClient() ?: return ToolResult(
            data = emptyList(),
            summary = notConfigured(),
            isError = true
        )
        val separator = if (url.contains('?')) '&' else '?'
        val fullUrl = "$url${separator}query=${enc(query)}"
        log.debug("[JiraSearch] followAutoCompleteUrl -> $fullUrl")
        return when (val result = api.getRawString(fullUrl)) {
            is ApiResult.Error -> apiError(result, emptyList())
            is ApiResult.Success -> {
                val options = parseFieldOptions(result.data)
                ToolResult.success(
                    data = options,
                    summary = "Found ${options.size} autocomplete option(s) for query '$query'."
                )
            }
        }
    }

    // ── JSON parsers ──────────────────────────────────────────────────────────

    /**
     * Parses a JSON array of user objects.
     * `[{"name":"...","displayName":"...","emailAddress":"...","avatarUrls":{"24x24":"..."},"active":true}]`
     */
    private fun parseUsers(body: String): List<UserSuggestion> = try {
        val arr = json.parseToJsonElement(body) as? JsonArray ?: return emptyList()
        arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            UserSuggestion(
                name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                displayName = obj["displayName"]?.jsonPrimitive?.content ?: "",
                email = obj["emailAddress"]?.jsonPrimitive?.content,
                avatarUrl = obj["avatarUrls"]?.jsonObject?.get("24x24")?.jsonPrimitive?.content,
                active = obj["active"]?.jsonPrimitive?.booleanOrNull ?: true
            )
        }
    } catch (e: Exception) {
        log.warn("[JiraSearch] Failed to parse users: ${e.message}")
        emptyList()
    }

    /**
     * Parses label suggestions: `{"suggestions":[{"label":"..."}]}`
     */
    private fun parseLabelSuggestions(body: String): List<LabelSuggestion> = try {
        val obj = json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
        val suggestions = obj["suggestions"]?.jsonArray ?: return emptyList()
        suggestions.mapNotNull { el ->
            val label = (el as? JsonObject)?.get("label")?.jsonPrimitive?.content
            label?.let { LabelSuggestion(it) }
        }
    } catch (e: Exception) {
        log.warn("[JiraSearch] Failed to parse label suggestions: ${e.message}")
        emptyList()
    }

    /**
     * Parses group picker response: `{"groups":[{"name":"..."}]}`
     */
    private fun parseGroups(body: String): List<GroupSuggestion> = try {
        val obj = json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
        val groups = obj["groups"]?.jsonArray ?: return emptyList()
        groups.mapNotNull { el ->
            val name = (el as? JsonObject)?.get("name")?.jsonPrimitive?.content
            name?.let { GroupSuggestion(it) }
        }
    } catch (e: Exception) {
        log.warn("[JiraSearch] Failed to parse groups: ${e.message}")
        emptyList()
    }

    /**
     * Parses project versions array: `[{"id":"...","name":"...","released":false,"archived":false}]`
     */
    private fun parseVersions(body: String): List<VersionSuggestion> = try {
        val arr = json.parseToJsonElement(body) as? JsonArray ?: return emptyList()
        arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            VersionSuggestion(
                id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                released = obj["released"]?.jsonPrimitive?.booleanOrNull ?: false,
                archived = obj["archived"]?.jsonPrimitive?.booleanOrNull ?: false
            )
        }
    } catch (e: Exception) {
        log.warn("[JiraSearch] Failed to parse versions: ${e.message}")
        emptyList()
    }

    /**
     * Parses project components array: `[{"id":"...","name":"...","description":"..."}]`
     * The description field may be absent or explicitly `null` in the JSON.
     */
    private fun parseComponents(body: String): List<ComponentSuggestion> = try {
        val arr = json.parseToJsonElement(body) as? JsonArray ?: return emptyList()
        arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val descEl = obj["description"]
            ComponentSuggestion(
                id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                description = if (descEl == null || descEl is JsonNull) null
                              else descEl.jsonPrimitive.content
            )
        }
    } catch (e: Exception) {
        log.warn("[JiraSearch] Failed to parse components: ${e.message}")
        emptyList()
    }

    /**
     * Parses generic autocomplete array. Elements may have:
     * - `"value"` as both id and display (single-key form)
     * - `"id"` + `"value"` or `"id"` + `"name"`
     */
    private fun parseFieldOptions(body: String): List<FieldOption> = try {
        val arr = json.parseToJsonElement(body) as? JsonArray ?: return emptyList()
        arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.content
                ?: obj["value"]?.jsonPrimitive?.content
                ?: return@mapNotNull null
            val value = obj["value"]?.jsonPrimitive?.content
                ?: obj["name"]?.jsonPrimitive?.content
                ?: id
            FieldOption(id = id, value = value)
        }
    } catch (e: Exception) {
        log.warn("[JiraSearch] Failed to parse field options: ${e.message}")
        emptyList()
    }
}
