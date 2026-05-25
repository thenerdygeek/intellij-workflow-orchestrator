package com.workflow.orchestrator.sonar.api

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.core.http.HttpClientFactory
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.sonar.api.dto.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder

class SonarApiClient(
    private val baseUrl: String,
    private val tokenProvider: () -> String?,
    private val connectTimeoutSeconds: Long = 10,
    private val readTimeoutSeconds: Long = 30
) {
    companion object {
        /** Fetches both overall + new code metrics in one call. */
        @JvmField
        val DEFAULT_METRIC_KEYS: String = SonarMetricKey.csv(
            SonarMetricKey.COVERAGE, SonarMetricKey.LINE_COVERAGE, SonarMetricKey.BRANCH_COVERAGE,
            SonarMetricKey.UNCOVERED_LINES, SonarMetricKey.UNCOVERED_CONDITIONS, SonarMetricKey.LINES_TO_COVER,
            SonarMetricKey.NEW_COVERAGE, SonarMetricKey.NEW_BRANCH_COVERAGE, SonarMetricKey.NEW_UNCOVERED_LINES,
            SonarMetricKey.NEW_UNCOVERED_CONDITIONS, SonarMetricKey.NEW_LINES_TO_COVER,
            SonarMetricKey.BUGS, SonarMetricKey.VULNERABILITIES, SonarMetricKey.CODE_SMELLS,
            SonarMetricKey.NEW_BUGS, SonarMetricKey.NEW_VULNERABILITIES, SonarMetricKey.NEW_CODE_SMELLS,
            SonarMetricKey.SQALE_INDEX, SonarMetricKey.SQALE_RATING, SonarMetricKey.DUPLICATED_LINES_DENSITY,
            SonarMetricKey.COMPLEXITY, SonarMetricKey.COGNITIVE_COMPLEXITY,
            SonarMetricKey.RELIABILITY_RATING, SonarMetricKey.SECURITY_RATING,
        )

        /** Project-level health metrics (not file-level). */
        @JvmField
        val PROJECT_HEALTH_METRIC_KEYS: String = SonarMetricKey.csv(
            SonarMetricKey.SQALE_INDEX, SonarMetricKey.SQALE_RATING, SonarMetricKey.DUPLICATED_LINES_DENSITY,
            SonarMetricKey.COGNITIVE_COMPLEXITY, SonarMetricKey.RELIABILITY_RATING, SonarMetricKey.SECURITY_RATING,
            SonarMetricKey.COVERAGE, SonarMetricKey.BRANCH_COVERAGE,
        )

        /** SonarQube enforces ps ≤ 500 on /api/measures/component_tree and /api/issues/search. */
        const val MEASURES_PAGE_SIZE = 500

        /** Page size for issues and hotspots pagination (SonarQube max is 500). */
        const val ISSUES_PAGE_SIZE = 500

        /**
         * Cap on pagination loop in [getMeasures]. 10 pages × 500 ps = 5000
         * components — covers all but the largest monorepos in a single
         * refresh. Past this, we log a warning and return partial data
         * rather than make the IDE wait indefinitely.
         */
        const val MAX_MEASURES_PAGES = 10

        /**
         * Hard cap on issues/hotspots pagination: 20 pages × 500 = 10 000 items.
         * Prevents runaway requests on misconfigured or very large projects while
         * still covering the vast majority of real-world issue volumes.
         */
        const val MAX_ISSUES_PAGES = 20
    }
    private val log = Logger.getInstance(SonarApiClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient: OkHttpClient by lazy {
        HttpClientFactory(
            tokenProvider = { _ -> tokenProvider() },
            connectTimeoutSeconds = connectTimeoutSeconds,
            readTimeoutSeconds = readTimeoutSeconds
        ).clientFor(ServiceType.SONARQUBE)
    }

    /**
     * Release OkHttp resources held by this client: shuts down the dispatcher
     * thread pool and evicts all pooled connections. Must be called whenever this
     * client is replaced by a new one (e.g. the Sonar URL changed) so the
     * abandoned OkHttp pool does not leak threads or sockets.
     *
     * Safe to call even if [httpClient] was never initialized (lazy init means
     * the field is only populated on first use).
     */
    fun close() {
        if (!isHttpClientInitialized()) return
        try {
            httpClient.dispatcher.executorService.shutdown()
            httpClient.connectionPool.evictAll()
        } catch (e: Exception) {
            log.warn("[Sonar:API] close() — error evicting OkHttp resources: ${e.message}", e)
        }
    }

    /** Returns true only when the lazy [httpClient] has already been initialised. */
    private fun isHttpClientInitialized(): Boolean =
        try {
            // Accessing the backing field via reflection would be fragile; instead
            // we rely on the fact that `lazy` delegates implement `isInitialized()`
            // through the `Lazy` interface exposed on the property delegate.
            val delegate = SonarApiClient::class.java
                .getDeclaredField("httpClient\$delegate")
                .also { it.isAccessible = true }
                .get(this) as? Lazy<*>
            delegate?.isInitialized() == true
        } catch (_: Exception) {
            // Reflection unavailable (e.g. sealed JVM) — assume initialized and call close anyway.
            true
        }

    suspend fun validateConnection(): ApiResult<Boolean> {
        log.info("[Sonar:API] GET /api/authentication/validate — testing connection")
        return get<SonarValidationDto>("/api/authentication/validate").map { it.valid }
    }

    suspend fun searchProjects(query: String): ApiResult<List<SonarProjectDto>> {
        log.info("[Sonar:API] GET /api/components/search for query '$query'")
        val encoded = URLEncoder.encode(query, "UTF-8")
        return get<SonarComponentSearchResult>("/api/components/search?qualifiers=TRK&q=$encoded&ps=25")
            .map { it.components }
    }

    /**
     * Cheap existence probe used as a preflight before `/api/issues/search`, which
     * returns 200-with-empty-array for unknown project keys and is therefore
     * indistinguishable from "no open issues".
     */
    suspend fun componentExists(projectKey: String): ApiResult<Boolean> {
        log.info("[Sonar:API] GET /api/components/show for project '$projectKey'")
        val encoded = URLEncoder.encode(projectKey, "UTF-8")
        return when (val result = get<SonarComponentShowResponse>("/api/components/show?component=$encoded")) {
            is ApiResult.Success -> ApiResult.Success(true)
            is ApiResult.Error -> if (result.type == ErrorType.NOT_FOUND) ApiResult.Success(false) else result
        }
    }

    suspend fun getBranches(projectKey: String): ApiResult<List<SonarBranchDto>> {
        log.info("[Sonar:API] GET /api/project_branches/list for project '$projectKey'")
        val encoded = URLEncoder.encode(projectKey, "UTF-8")
        return get<SonarBranchListResponse>("/api/project_branches/list?project=$encoded")
            .map { it.branches }
    }

    suspend fun getQualityGateStatus(projectKey: String, branch: String? = null): ApiResult<SonarQualityGateDto> {
        log.info("[Sonar:API] GET /api/qualitygates/project_status for project '$projectKey' branch='${branch ?: "default"}'")
        val branchParam = branch?.let { "&branch=${URLEncoder.encode(it, "UTF-8")}" } ?: ""
        return get<SonarQualityGateResponse>(
            "/api/qualitygates/project_status?projectKey=${URLEncoder.encode(projectKey, "UTF-8")}$branchParam"
        ).map { it.projectStatus }
    }

    suspend fun getIssues(
        projectKey: String,
        branch: String? = null,
        filePath: String? = null,
        inNewCodePeriod: Boolean = false
    ): ApiResult<List<SonarIssueDto>> {
        log.info("[Sonar:API] GET /api/issues/search for project '$projectKey' branch='${branch ?: "default"}' file='${filePath ?: "all"}' newCode=$inNewCodePeriod")
        return getIssuesWithPaging(projectKey, branch, filePath, inNewCodePeriod).map { it.issues }
    }

    /**
     * Fetches ALL issues across multiple pages. SonarQube caps a single response at 500;
     * this loop uses `p`/`ps` parameters and `paging.total` to collect every page up to
     * [MAX_ISSUES_PAGES] × [ISSUES_PAGE_SIZE] = 10 000 items.
     *
     * The returned [SonarIssueSearchResult] has `paging.total` from the first page response
     * (server-authoritative count) and `issues` containing every item fetched so far.
     *
     * F-7 fix: previously only the first 500 issues were returned; annotations and the agent
     * silently missed anything beyond that position.
     */
    suspend fun getIssuesWithPaging(
        projectKey: String,
        branch: String? = null,
        filePath: String? = null,
        inNewCodePeriod: Boolean = false
    ): ApiResult<SonarIssueSearchResult> {
        log.info("[Sonar:API] GET /api/issues/search (paginated) for project '$projectKey' branch='${branch ?: "default"}' newCode=$inNewCodePeriod")
        return paginateSonarItems(
            logTag = "Issues",
            buildPagePath = { page -> buildIssuesSearchPath(projectKey, branch, filePath, inNewCodePeriod, page) },
            fetchPage = { path -> get<SonarIssueSearchResult>(path) },
            extractItems = { it.issues },
            extractTotal = { it.paging.total },
            combineResult = { firstResult, allItems ->
                firstResult.copy(issues = allItems, paging = firstResult.paging.copy(total = firstResult.paging.total))
            }
        )
    }

    /**
     * Fetches a SINGLE page of issues directly from the server (one HTTP request with
     * `&p=$page&ps=$pageSize`). Unlike [getIssuesWithPaging] this does NOT loop to collect
     * every page — it lets SonarQube paginate, so callers that only need one page (e.g. the
     * agent's `getIssuesPaged`) don't fetch (and discard) up to 10 000 issues.
     *
     * `paging.total` in the returned envelope is the server-authoritative total issue count,
     * so callers can report an accurate total alongside the single page they requested.
     *
     * F-15 fix: previously the agent's paged-issues path called [getIssuesWithPaging] (fetch-all)
     * and then sliced client-side.
     *
     * @param pageSize requested page size. SonarQube caps this at [ISSUES_PAGE_SIZE] (500);
     *   values above the cap are clamped so the request stays valid.
     */
    suspend fun getIssuesSinglePage(
        projectKey: String,
        page: Int,
        pageSize: Int,
        branch: String? = null,
        filePath: String? = null,
        inNewCodePeriod: Boolean = false
    ): ApiResult<SonarIssueSearchResult> {
        val effectivePage = page.coerceAtLeast(1)
        val effectivePageSize = pageSize.coerceIn(1, ISSUES_PAGE_SIZE)
        log.info(
            "[Sonar:API] GET /api/issues/search (single page) for project '$projectKey' " +
            "branch='${branch ?: "default"}' p=$effectivePage ps=$effectivePageSize newCode=$inNewCodePeriod"
        )
        val path = buildIssuesSearchPath(
            projectKey, branch, filePath, inNewCodePeriod, effectivePage, effectivePageSize
        )
        return get<SonarIssueSearchResult>(path)
    }

    // Sonar's /api/issues/search filters by component via `componentKeys=<projectKey>:<path>`
    // (or just `<projectKey>` for project-wide). The bare `&components=` parameter we used
    // pre-2026-05-18 was silently ignored, returning the full 500-issue project result.
    private fun buildIssuesSearchPath(
        projectKey: String,
        branch: String?,
        filePath: String?,
        inNewCodePeriod: Boolean,
        page: Int = 1,
        pageSize: Int = ISSUES_PAGE_SIZE,
    ): String = buildString {
        append("/api/issues/search?componentKeys=")
        val componentKey = if (filePath != null) "$projectKey:$filePath" else projectKey
        append(URLEncoder.encode(componentKey, "UTF-8"))
        append("&resolved=false&ps=$pageSize&p=$page")
        branch?.let { append("&branch=${URLEncoder.encode(it, "UTF-8")}") }
        if (inNewCodePeriod) append("&inNewCodePeriod=true")
    }

    /**
     * Generic SonarQube pagination helper. Fetches pages 1‥[MAX_ISSUES_PAGES], stopping
     * early when `items.size >= total` or the page comes back empty.
     *
     * Used by [getIssuesWithPaging] (F-7) and [getSecurityHotspots] (F-8) to avoid
     * duplicating the identical loop structure.
     *
     * @param logTag        short label used in log messages (e.g. "Issues" or "Hotspots")
     * @param buildPagePath builds the URL path for page N (N ≥ 1)
     * @param fetchPage     performs the actual HTTP call for a given URL path
     * @param extractItems  pulls the item list out of a page response
     * @param extractTotal  reads `paging.total` from a page response
     * @param combineResult assembles the final [ApiResult.Success] data from the first-page
     *                      envelope + all accumulated items
     */
    private suspend fun <T, R> paginateSonarItems(
        logTag: String,
        buildPagePath: (Int) -> String,
        fetchPage: suspend (String) -> ApiResult<T>,
        extractItems: (T) -> List<R>,
        extractTotal: (T) -> Int,
        combineResult: (T, List<R>) -> T
    ): ApiResult<T> {
        val allItems = mutableListOf<R>()
        var firstPageEnvelope: T? = null

        for (page in 1..MAX_ISSUES_PAGES) {
            when (val pageResult = fetchPage(buildPagePath(page))) {
                is ApiResult.Success -> {
                    if (firstPageEnvelope == null) firstPageEnvelope = pageResult.data
                    val items = extractItems(pageResult.data)
                    allItems += items
                    val total = extractTotal(pageResult.data)
                    if (allItems.size >= total || items.isEmpty()) {
                        log.info("[Sonar:API] $logTag: fetched ${allItems.size}/$total in $page page(s)")
                        return ApiResult.Success(combineResult(firstPageEnvelope!!, allItems))
                    }
                }
                is ApiResult.Error -> {
                    return if (page == 1) pageResult
                    else {
                        log.warn("[Sonar:API] $logTag: mid-pagination error on page $page — returning ${allItems.size} items collected so far")
                        ApiResult.Success(combineResult(firstPageEnvelope!!, allItems))
                    }
                }
            }
        }
        log.warn("[Sonar:API] $logTag: hit MAX_ISSUES_PAGES=$MAX_ISSUES_PAGES cap — returning ${allItems.size} items (project may have more)")
        return ApiResult.Success(combineResult(firstPageEnvelope!!, allItems))
    }

    suspend fun getMeasures(
        projectKey: String,
        branch: String? = null,
        metricKeys: String = DEFAULT_METRIC_KEYS
    ): ApiResult<List<SonarMeasureComponentDto>> {
        log.info("[Sonar:API] GET /api/measures/component_tree for project '$projectKey' branch='${branch ?: "default"}'")
        val metrics = metricKeys.ifBlank { DEFAULT_METRIC_KEYS }
        val branchParam = branch?.let { "&branch=${URLEncoder.encode(it, "UTF-8")}" } ?: ""
        // additionalFields=period is required for new_* metrics (SonarQube returns
        // them in period.value, not value).
        val needsPeriod = metrics.contains("new_")
        val additionalFields = if (needsPeriod) "&additionalFields=period" else ""
        // When new_lines_to_cover is in the metric set, sort by it descending so
        // files with the most new code surface in the earliest pages. Without
        // this, Sonar's default sort (alphabetical by name) can bury new-code
        // files past the page cutoff on large projects — verified against a
        // 531-file project where the Coverage tab showed an empty new-code
        // listing because the new-code files happened to sort late.
        val sortParams = if (metrics.contains("new_lines_to_cover")) {
            "&s=metric&metricSort=new_lines_to_cover&asc=false"
        } else ""
        // Paginate up to MAX_PAGES so projects with thousands of files still
        // get a complete set rather than a truncated one. The hard cap
        // protects against runaway requests on misconfigured projects.
        val all = mutableListOf<SonarMeasureComponentDto>()
        for (page in 1..MAX_MEASURES_PAGES) {
            val url = "/api/measures/component_tree?component=${URLEncoder.encode(projectKey, "UTF-8")}" +
                "&metricKeys=$metrics&qualifiers=FIL&ps=$MEASURES_PAGE_SIZE&p=$page" +
                "$branchParam$additionalFields$sortParams"
            when (val pageResult = get<SonarMeasureSearchResult>(url)) {
                is ApiResult.Success -> {
                    all += pageResult.data.components
                    val total = pageResult.data.paging.total
                    if (all.size >= total || pageResult.data.components.isEmpty()) {
                        log.info("[Sonar:API] Fetched ${all.size}/${total} components in $page page(s)")
                        return ApiResult.Success(all)
                    }
                }
                is ApiResult.Error -> {
                    // First-page failure → return error. Mid-pagination failure
                    // → return what we have rather than discard partial data.
                    return if (page == 1) pageResult else ApiResult.Success(all)
                }
            }
        }
        log.warn("[Sonar:API] Hit MAX_MEASURES_PAGES=$MAX_MEASURES_PAGES; returning ${all.size} components (project may have more)")
        return ApiResult.Success(all)
    }

    /**
     * Fetch project-level aggregate measures (not file-level).
     * Uses /api/measures/component (without _tree) to get project-level metrics
     * like technical debt, ratings, and duplication density.
     */
    suspend fun getProjectMeasures(
        projectKey: String,
        branch: String? = null,
        metricKeys: String = PROJECT_HEALTH_METRIC_KEYS
    ): ApiResult<List<SonarMeasureDto>> {
        log.info("[Sonar:API] GET /api/measures/component for project '$projectKey' branch='${branch ?: "default"}'")
        val branchParam = branch?.let { "&branch=${URLEncoder.encode(it, "UTF-8")}" } ?: ""
        // additionalFields=period is required for new_* metrics (SonarQube returns them in period.value, not value)
        val needsPeriod = metricKeys.contains("new_")
        val additionalFields = if (needsPeriod) "&additionalFields=period" else ""
        return get<SonarComponentMeasureResponse>(
            "/api/measures/component?component=${URLEncoder.encode(projectKey, "UTF-8")}" +
                "&metricKeys=$metricKeys$branchParam$additionalFields"
        ).map { it.component.measures }
    }

    suspend fun getAnalysisTasks(projectKey: String): ApiResult<List<SonarCeTaskDto>> {
        log.info("[Sonar:API] GET /api/ce/activity for project '$projectKey'")
        val encoded = URLEncoder.encode(projectKey, "UTF-8")
        return get<SonarCeActivityResponse>("/api/ce/activity?component=$encoded&ps=10")
            .map { it.tasks }
    }

    /** Poll a specific CE task by ID — used to wait for local scanner analysis to complete. */
    suspend fun getCeTask(taskId: String): ApiResult<SonarCeTaskDto> {
        log.info("[Sonar:API] GET /api/ce/task?id=$taskId")
        return get<SonarCeTaskResponse>("/api/ce/task?id=${URLEncoder.encode(taskId, "UTF-8")}")
            .map { it.task }
    }

    /**
     * Fetch full hotspot detail including the rule's risk descriptions and
     * fix recommendations HTML — the data the agent feeds the LLM for
     * autonomous remediation. List view (`/api/hotspots/search`) only carries
     * the location + severity; the rule's curated guidance lives here.
     */
    suspend fun getHotspotDetail(hotspotKey: String): ApiResult<SonarHotspotDetailDto> {
        log.info("[Sonar:API] GET /api/hotspots/show for hotspot '$hotspotKey'")
        val encoded = URLEncoder.encode(hotspotKey, "UTF-8")
        return get<SonarHotspotDetailDto>("/api/hotspots/show?hotspot=$encoded")
    }

    /**
     * Fetch issue facet counts — one round trip yields the breakdown by
     * severity, type, software quality, file, etc. for a project's issues.
     * Used by the agent to prioritize before walking the issue list.
     *
     * @param facets comma-separated facet names. Valid 25.x values:
     *   `severities, types, tags, impactSoftwareQualities, impactSeverities,
     *   cleanCodeAttributeCategories, assignees, files, rules, statuses,
     *   resolutions, author, directories, scopes, languages, codeVariants,
     *   issueStatuses, prioritizedRule, createdAt, sonarsourceSecurity` plus
     *   compliance facets (pciDss-3.2/4.0, owaspAsvs-4.0, owaspMobileTop10-2024,
     *   stig-ASD_V5R3, casa, sansTop25, cwe). Use `files` (NOT `fileUuids`).
     */
    suspend fun getIssueFacets(
        projectKey: String,
        branch: String? = null,
        inNewCodePeriod: Boolean = false,
        facets: String
    ): ApiResult<SonarIssueSearchResult> {
        log.info("[Sonar:API] GET /api/issues/search?facets=$facets for project '$projectKey' branch='${branch ?: "default"}' newCode=$inNewCodePeriod")
        val params = buildString {
            append("/api/issues/search?componentKeys=")
            append(URLEncoder.encode(projectKey, "UTF-8"))
            append("&resolved=false&ps=1")
            branch?.let { append("&branch=${URLEncoder.encode(it, "UTF-8")}") }
            if (inNewCodePeriod) append("&inNewCodePeriod=true")
            append("&facets=${URLEncoder.encode(facets, "UTF-8")}")
        }
        return get<SonarIssueSearchResult>(params)
    }

    /**
     * Fetch the authenticated user's identity + global permissions. Used by
     * the settings page identity badge and to gate admin-only hints.
     */
    suspend fun getCurrentUser(): ApiResult<SonarCurrentUserDto> {
        log.info("[Sonar:API] GET /api/users/current")
        return get<SonarCurrentUserDto>("/api/users/current")
    }

    /**
     * Fetch the list of all configured quality gates with their CaYC
     * compliance status and AI-Code-Fix support flag.
     */
    suspend fun listQualityGates(): ApiResult<SonarQualityGateListResponse> {
        log.info("[Sonar:API] GET /api/qualitygates/list")
        return get<SonarQualityGateListResponse>("/api/qualitygates/list")
    }

    /**
     * Fetches ALL security hotspots across multiple pages. SonarQube caps a single
     * response at 500; this delegates to [paginateSonarItems] with the same hard cap
     * used by [getIssuesWithPaging] (20 pages × 500 = 10 000 items).
     *
     * F-8 fix: previously only the first 500 hotspots were returned; large projects
     * silently dropped the remainder with no truncation warning.
     */
    suspend fun getSecurityHotspots(
        projectKey: String,
        branch: String? = null
    ): ApiResult<SonarHotspotSearchResult> {
        log.info("[Sonar:API] GET /api/hotspots/search (paginated) for project '$projectKey' branch='${branch ?: "default"}'")
        val buildPath: (Int) -> String = { page ->
            buildString {
                append("/api/hotspots/search?project=")
                append(URLEncoder.encode(projectKey, "UTF-8"))
                append("&ps=$ISSUES_PAGE_SIZE&p=$page")
                branch?.let { append("&branch=${URLEncoder.encode(it, "UTF-8")}") }
            }
        }
        return paginateSonarItems(
            logTag = "Hotspots",
            buildPagePath = buildPath,
            fetchPage = { path -> get<SonarHotspotSearchResult>(path) },
            extractItems = { it.hotspots },
            extractTotal = { it.paging.total },
            combineResult = { firstResult, allItems ->
                firstResult.copy(hotspots = allItems, paging = firstResult.paging.copy(total = firstResult.paging.total))
            }
        )
    }

    suspend fun getDuplications(
        componentKey: String,
        branch: String? = null
    ): ApiResult<SonarDuplicationsResponse> {
        log.info("[Sonar:API] GET /api/duplications/show for component '$componentKey' branch='${branch ?: "default"}'")
        val params = buildString {
            append("/api/duplications/show?key=")
            append(URLEncoder.encode(componentKey, "UTF-8"))
            branch?.let { append("&branch=${URLEncoder.encode(it, "UTF-8")}") }
        }
        return get<SonarDuplicationsResponse>(params)
    }

    suspend fun getRule(ruleKey: String): ApiResult<SonarRuleDto> {
        log.info("[Sonar:API] GET /api/rules/show for rule '$ruleKey'")
        val encoded = URLEncoder.encode(ruleKey, "UTF-8")
        return get<SonarRuleShowResponseDto>("/api/rules/show?key=$encoded").map { it.rule }
    }

    suspend fun getSourceLines(
        componentKey: String,
        from: Int? = null,
        to: Int? = null,
        branch: String? = null
    ): ApiResult<List<SonarSourceLineDto>> {
        log.debug("[Sonar:API] GET /api/sources/lines for component '$componentKey' branch='${branch ?: "default"}' from=$from to=$to")
        val params = buildString {
            append("/api/sources/lines?key=")
            append(URLEncoder.encode(componentKey, "UTF-8"))
            branch?.let { append("&branch=${URLEncoder.encode(it, "UTF-8")}") }
            from?.let { append("&from=$it") }
            to?.let { append("&to=$it") }
        }
        return get<SonarSourceLinesResponse>(params).map { it.sources }
    }

    private suspend inline fun <reified T> get(path: String): ApiResult<T> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url("$baseUrl$path").get()
                    .cacheControl(CacheControl.FORCE_NETWORK)
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when (it.code) {
                        in 200..299 -> {
                            log.debug("[Sonar:API] $path -> ${it.code}")
                            val contentType = it.header("Content-Type") ?: ""
                            if (contentType.isNotBlank() &&
                                !contentType.contains("application/json", ignoreCase = true) &&
                                !contentType.contains("text/json", ignoreCase = true)) {
                                return@withContext ApiResult.Error(
                                    ErrorType.PARSE_ERROR,
                                    "Unexpected response Content-Type: $contentType (expected JSON)"
                                )
                            }
                            val bodyStr = it.body?.string() ?: ""
                            ApiResult.Success(json.decodeFromString<T>(bodyStr))
                        }
                        401 -> {
                            log.error("[Sonar:API] $path -> 401 Auth failed")
                            ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid SonarQube token")
                        }
                        403 -> {
                            log.info("[Sonar:API] $path -> 403 (insufficient permissions — some endpoints require admin)")
                            ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient SonarQube permissions")
                        }
                        404 -> {
                            log.info("[Sonar:API] $path -> 404 Not found")
                            ApiResult.Error(ErrorType.NOT_FOUND, "SonarQube resource not found")
                        }
                        429 -> {
                            log.warn("[Sonar:API] $path -> 429 Rate limited")
                            ApiResult.Error(ErrorType.RATE_LIMITED, "SonarQube rate limit exceeded")
                        }
                        else -> {
                            log.error("[Sonar:API] $path -> ${it.code} Server error")
                            ApiResult.Error(ErrorType.SERVER_ERROR, "SonarQube returned ${it.code}")
                        }
                    }
                }
            } catch (e: IOException) {
                log.error("[Sonar:API] $path -> Network error: ${e.message}", e)
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach SonarQube: ${e.message}", e)
            }
        }
}
