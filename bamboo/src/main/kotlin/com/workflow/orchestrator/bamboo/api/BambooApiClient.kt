package com.workflow.orchestrator.bamboo.api

import com.workflow.orchestrator.bamboo.api.dto.*
import com.workflow.orchestrator.core.http.HttpClientFactory
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.services.isSuccess
import com.workflow.orchestrator.core.services.looksLikeAuthRedirect
import com.workflow.orchestrator.core.services.postForm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.intellij.openapi.diagnostic.Logger
import java.io.IOException
import java.net.URLEncoder

class BambooApiClient(
    private val baseUrl: String,
    private val tokenProvider: () -> String?,
    private val connectTimeoutSeconds: Long = 10,
    private val readTimeoutSeconds: Long = 30
) {
    private val log = Logger.getInstance(BambooApiClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient: OkHttpClient by lazy {
        HttpClientFactory(
            tokenProvider = { _ -> tokenProvider() },
            connectTimeoutSeconds = connectTimeoutSeconds,
            readTimeoutSeconds = readTimeoutSeconds
        ).clientFor(ServiceType.BAMBOO)
    }

    suspend fun getPlans(): ApiResult<List<BambooPlanDto>> {
        log.debug("[Bamboo:API] Fetching all plans")
        return get<BambooPlanListResponse>("/rest/api/latest/plan?expand=plans.plan&max-results=100")
            .map { it.plans.plan }
    }

    suspend fun getProjects(): ApiResult<List<BambooProjectDto>> {
        log.debug("[Bamboo:API] Fetching all projects")
        return get<BambooProjectListResponse>("/rest/api/latest/project?max-results=100")
            .map { it.projects.project }
    }

    suspend fun getProjectPlans(projectKey: String): ApiResult<List<BambooPlanDto>> {
        log.debug("[Bamboo:API] Fetching plans for project $projectKey")
        return get<BambooProjectDetailResponse>("/rest/api/latest/project/$projectKey?expand=plans.plan")
            .map { it.plans.plan }
    }

    suspend fun searchPlans(query: String): ApiResult<List<BambooSearchEntity>> {
        log.debug("[Bamboo:API] Searching plans with query='$query'")
        val encoded = URLEncoder.encode(query, "UTF-8")
        return get<BambooSearchResponse>("/rest/api/latest/search/plans?searchTerm=$encoded&fuzzy=true&max-results=25")
            .map { it.searchResults.map { r -> r.searchEntity } }
    }

    /**
     * GET /rest/api/latest/plan/{key}/specs?format=YAML
     *
     * Returns the bamboo-specs YAML for a plan. Used by [PlanDetectionService] Tier 4 deep-scan
     * to match plans to repository URLs.
     *
     * NOTE: Returns 403 for non-admin PATs (requires **Plan View Configuration** permission).
     * The caller ([PlanDetectionService.legacyN1ScanPublic]) already handles 403 gracefully
     * via `specsResult.getOrNull() ?: continue` — 403 just skips that plan in the scan.
     * Bamboo 10.2.14 probe: confirmed 403 on the user's non-admin PAT (bundle-repo + bundle-automation).
     */
    suspend fun getPlanSpecs(planKey: String): ApiResult<String> {
        log.debug("[Bamboo:API] Fetching plan specs for planKey=$planKey")
        return getRaw("/rest/api/latest/plan/$planKey/specs?format=YAML")
    }

    suspend fun getBranches(planKey: String): ApiResult<List<BambooBranchDto>> =
        get<BambooBranchListResponse>("/rest/api/latest/plan/$planKey/branch?max-results=100")
            .map { it.branches.branch }

    suspend fun getLatestResult(planKey: String, branch: String? = null): ApiResult<BambooResultDto> {
        // If planKey already includes the branch number (e.g., PROJ-PLAN123), use it directly
        // Otherwise, use the branch path format
        val path = if (branch != null && !planKey.last().isDigit()) {
            val encodedBranch = URLEncoder.encode(branch, "UTF-8")
            "/rest/api/latest/result/$planKey/branch/$encodedBranch/latest?expand=stages.stage.results.result"
        } else {
            "/rest/api/latest/result/$planKey/latest?expand=stages.stage.results.result"
        }
        log.info("[Bamboo:API] getLatestResult: GET $path")
        return get(path)
    }

    /**
     * Fetches the VCS revision (commit SHA) recorded against a build result.
     *
     * Used by the Bamboo→Bitbucket bridge (R-ADD-5): when a build fails for plan
     * key X build N, we ask Bamboo for `?expand=vcsRevisions`, then call
     * `BitbucketBranchClient.getCommitPullRequests(sha)` to identify affected PRs.
     *
     * Returns the first repository VCS revision in the result, or `null` when
     * Bamboo has no VCS revisions recorded (e.g. plan with no source repo).
     *
     * Source: docs/research/2026-05-07-bitbucket-recommendations.md §2 B1.
     */
    suspend fun getResultVcsRevision(resultKey: String): ApiResult<String?> {
        log.info("[Bamboo:API] getResultVcsRevision: GET /rest/api/latest/result/$resultKey?expand=vcsRevisions")
        return get<BambooVcsRevisionsResponse>("/rest/api/latest/result/$resultKey?expand=vcsRevisions")
            .map { it.vcsRevisions.vcsRevision.firstOrNull()?.vcsRevisionKey }
    }

    suspend fun getBuildLog(resultKey: String): ApiResult<String> {
        log.debug("[Bamboo:API] Fetching build log for resultKey=$resultKey")
        // Use the download endpoint for plain text logs (not the REST API logEntries which returns XML)
        return getRaw("/download/$resultKey/build_logs/$resultKey.log")
    }

    /**
     * Fetches test results for a job, including failed test details.
     * GET /rest/api/latest/result/{jobResultKey}?expand=testResults.failedTests.testResult,testResults.successfulTests.testResult
     */
    suspend fun getTestResults(jobResultKey: String): ApiResult<BambooJobTestResultDto> {
        log.debug("[Bamboo:API] Fetching test results for jobResultKey=$jobResultKey")
        return get("/rest/api/latest/result/$jobResultKey?expand=testResults.failedTests.testResult,testResults.successfulTests.testResult")
    }

    /**
     * Plan variables via variableContext expand (validated on Bamboo 10.2.14).
     * Returns [BambooPlanContextVariableDto] — each item has `key`/`value`/`variableType`/`isPassword`.
     * Note: the build-level `?expand=variables` response uses `name`/`value` (different shape).
     * See: bundle-repo.unpacked/raw/plan_variables_via_context.json for the canonical shape.
     */
    suspend fun getPlanVariableContext(planKey: String): ApiResult<List<BambooPlanContextVariableDto>> {
        log.info("[Bamboo:API] getPlanVariableContext: GET /plan/$planKey?expand=variableContext")
        return get<BambooPlanDetailResponse>("/rest/api/latest/plan/$planKey?expand=variableContext")
            .map { it.variableContext.variable }
    }

    /**
     * Queue a build for a Bamboo plan.
     *
     * **Wire contract (validated 2026-05-07 against Bamboo DC 10.2.14 via
     * `tools/atlassian-probe/probe_bamboo.py --write-test`):** Bamboo's queue
     * endpoint accepts variables ONLY when the request body is form-encoded with
     * `bamboo.variable.<name>=<value>` pairs. The earlier JSON-body shape returned
     * 200 and queued the build, but silently dropped every variable — automation
     * suites' `dockerTagsAsJson` overrides went unused for months. The audit
     * (`docs/research/2026-05-07-write-ops-ux-audit.md` finding #1) traced the
     * silent drop to that body shape.
     *
     * `executeAllStages` and (optional) `stage` are URL query parameters, not
     * body fields, per Atlassian's REST docs.
     *
     * @param planKey the plan key (e.g. `PROJ-BUILD`).
     * @param variables map of `<name>` → `<value>` pairs. Each pair is sent as
     *   `bamboo.variable.<name>=<value>`. Empty map produces an empty body.
     * @param stageName when non-null, only this stage runs; `executeAllStages`
     *   defaults to false. URL-encoded into the query string.
     * @param executeAllStages defaults to true when `stageName` is null,
     *   false otherwise.
     */
    suspend fun queueBuild(
        planKey: String,
        variables: Map<String, String> = emptyMap(),
        stageName: String? = null,
        executeAllStages: Boolean = stageName == null,
    ): ApiResult<BambooQueueResponse> {
        log.debug("[Bamboo:API] queueBuild planKey=$planKey, stage=$stageName, executeAllStages=$executeAllStages, variables=${variables.keys}")
        val query = buildString {
            append("?executeAllStages=$executeAllStages")
            if (stageName != null) {
                append("&stage=")
                append(URLEncoder.encode(stageName, "UTF-8"))
            }
        }
        // Form fields use raw keys/values — `postForm` (FormBody.Builder) URL-encodes
        // both key and value on the wire, producing `bamboo.variable.<urlEnc(k)>=<urlEnc(v)>`.
        val formFields = variables.mapKeys { (k, _) -> "bamboo.variable.$k" }
        val url = "$baseUrl/rest/api/latest/queue/$planKey$query"
        return when (val raw = postForm(httpClient, url, formFields)) {
            is ApiResult.Success -> {
                try {
                    ApiResult.Success(json.decodeFromString<BambooQueueResponse>(raw.data))
                } catch (e: Exception) {
                    log.warn("[Bamboo:API] queueBuild parse failed: ${e.message}")
                    log.debug("[Bamboo:API] queueBuild response (first 200): ${raw.data.take(200)}")
                    ApiResult.Error(ErrorType.PARSE_ERROR, "Failed to parse queue response: ${e.message}")
                }
            }
            is ApiResult.Error -> raw
        }
    }

    suspend fun getRunningAndQueuedBuilds(planKey: String): ApiResult<List<BambooResultDto>> {
        return get<BambooBuildStatusResponse>(
            "/rest/api/latest/result/$planKey?includeAllStates=true&max-results=5&expand=stages.stage.results.result"
        ).map { response ->
            response.results.result.filter { dto ->
                dto.lifeCycleState in listOf("InProgress", "Queued", "Pending")
            }
        }
    }

    suspend fun getBuildVariables(resultKey: String): ApiResult<Map<String, String>> {
        return get<BambooBuildVariablesResponse>(
            "/rest/api/latest/result/$resultKey?expand=variables"
        ).map { response ->
            response.variables.variable.associate { it.name to it.value }
        }
    }

    suspend fun getRecentResults(
        planKey: String,
        maxResults: Int = 10
    ): ApiResult<List<BambooResultDto>> {
        // Collection endpoint requires results.result. prefix for nested expands.
        // Expand variables.variable to populate the inner variable list (not just the wrapper)
        // — needed by the Automation tab's variable-key dropdown for the automation Bamboo plan.
        val path = "/rest/api/latest/result/$planKey?max-results=$maxResults" +
            "&expand=results.result.stages.stage.results.result,results.result.variables.variable"
        log.info("[Bamboo:API] getRecentResults: GET $path")
        return get<BambooBuildStatusResponse>(path).also { result ->
            when (result) {
                is ApiResult.Success -> {
                    val results = result.data.results.result
                    val varCounts = results.take(3).joinToString { "#${it.buildNumber}:${it.variables.variable.size}vars" }
                    log.info("[Bamboo:API] getRecentResults: ${results.size} result(s) for $planKey ($varCounts)")
                }
                is ApiResult.Error -> log.info("[Bamboo:API] getRecentResults: FAILED for $planKey — ${result.type}: ${result.message}")
            }
        }.map { it.results.result }
    }

    /**
     * Rerun failed/incomplete jobs for a build via Bamboo's `restartBuild` Struts action.
     *
     * Routes through [postForm] (PR 7 audit P1 #3 + write-path lessons §1):
     *   - `X-Atlassian-Token: no-check` is set automatically — without this the
     *     Struts action 403s on XSRF before reaching the permission check.
     *   - Strict 200..299 success check via [isSuccess]. Bamboo's `restartBuild`
     *     returns a 302 to `/build/admin/restartBuild.action` on success when
     *     the request is non-XHR; with `X-Atlassian-Token: no-check` it returns
     *     200 with a JSON body. A 200 with `Content-Type: text/html` means the
     *     session expired and the server swapped in a login page — `postForm`
     *     maps that to [ErrorType.AUTH_REDIRECT].
     */
    suspend fun rerunFailedJobs(planKey: String, buildNumber: Int): ApiResult<Unit> {
        val url = "$baseUrl/build/admin/restartBuild.action" +
            "?planKey=${URLEncoder.encode(planKey, "UTF-8")}" +
            "&buildNumber=$buildNumber"
        log.debug("[Bamboo:API] Rerunning failed jobs for planKey=$planKey, buildNumber=$buildNumber")
        return when (val raw = postForm(httpClient, url, emptyMap())) {
            is ApiResult.Success -> ApiResult.Success(Unit)
            is ApiResult.Error -> raw
        }
    }

    /** Cancel a queued (not yet running) build. */
    suspend fun cancelBuild(resultKey: String): ApiResult<Unit> {
        log.debug("[Bamboo:API] Cancelling queued build resultKey=$resultKey")
        return delete("/rest/api/latest/queue/$resultKey")
    }

    /** Stop a running build. */
    suspend fun stopBuild(resultKey: String): ApiResult<Unit> {
        log.debug("[Bamboo:API] Stopping running build resultKey=$resultKey")
        return put("/rest/api/latest/result/$resultKey/stop")
    }

    private suspend inline fun <reified T> get(path: String): ApiResult<T> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url("$baseUrl$path").get()
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    log.debug("[Bamboo:API] GET $path -> ${it.code}")
                    when (it.code) {
                        in 200..299 -> {
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
                            try {
                                ApiResult.Success(json.decodeFromString<T>(bodyStr))
                            } catch (e: Exception) {
                                log.warn("[Bamboo:API] Parse failed: ${e.message}")
                                log.debug("[Bamboo:API] Response body (first 200): ${bodyStr.take(200)}")
                                ApiResult.Error(ErrorType.PARSE_ERROR, "Failed to parse response: ${e.message}")
                            }
                        }
                        401 -> { log.warn("[Bamboo:API] Authentication failed (401)"); ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bamboo token") }
                        403 -> { log.warn("[Bamboo:API] Forbidden (403)"); ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient Bamboo permissions") }
                        404 -> { log.warn("[Bamboo:API] Not found (404)"); ApiResult.Error(ErrorType.NOT_FOUND, "Bamboo resource not found") }
                        429 -> { log.warn("[Bamboo:API] Rate limited (429)"); ApiResult.Error(ErrorType.RATE_LIMITED, "Bamboo rate limit exceeded") }
                        else -> { log.warn("[Bamboo:API] Server error (${it.code})"); ApiResult.Error(ErrorType.SERVER_ERROR, "Bamboo returned ${it.code}") }
                    }
                }
            } catch (e: IOException) {
                log.warn("[Bamboo:API] Network error: ${e.message}")
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bamboo: ${e.message}", e)
            }
        }

    private suspend fun getRaw(path: String): ApiResult<String> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url("$baseUrl$path").get().build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    log.debug("[Bamboo:API] GET (raw) $path responded with status=${it.code}")
                    when (it.code) {
                        in 200..299 -> ApiResult.Success(it.body?.string() ?: "")
                        401 -> { log.warn("[Bamboo:API] Authentication failed (401)"); ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bamboo token") }
                        403 -> { log.warn("[Bamboo:API] Forbidden (403)"); ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient Bamboo permissions") }
                        404 -> { log.warn("[Bamboo:API] Not found (404)"); ApiResult.Error(ErrorType.NOT_FOUND, "Bamboo resource not found") }
                        else -> { log.warn("[Bamboo:API] Server error (${it.code})"); ApiResult.Error(ErrorType.SERVER_ERROR, "Bamboo returned ${it.code}") }
                    }
                }
            } catch (e: IOException) {
                log.warn("[Bamboo:API] Network error: ${e.message}")
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bamboo: ${e.message}", e)
            }
        }

    suspend fun getArtifacts(resultKey: String): ApiResult<List<BambooArtifact>> {
        log.debug("[Bamboo:API] Fetching artifacts for resultKey=$resultKey")
        return get<BambooArtifactResponse>(
            "/rest/api/latest/result/$resultKey?expand=artifacts.artifact"
        ).map { it.artifacts.artifact }
    }

    suspend fun downloadArtifact(artifactUrl: String, targetFile: java.io.File): Boolean =
        withContext(Dispatchers.IO) {
            try {
                // Only use authenticated client for same-origin URLs to prevent token leakage.
                // Cross-origin artifact URLs intentionally use the raw shared pool (no auth headers,
                // no caching interceptors) — this is a security boundary, not an oversight.
                val isInternal = artifactUrl.startsWith(baseUrl)
                val client = if (isInternal) httpClient else HttpClientFactory.sharedPool
                val request = Request.Builder().url(artifactUrl).get().build()
                val response = client.newCall(request).execute()
                response.use {
                    if (it.isSuccessful) {
                        it.body?.byteStream()?.use { input ->
                            targetFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        true
                    } else {
                        log.warn("[Bamboo:API] Download artifact failed with status ${it.code}")
                        false
                    }
                }
            } catch (e: IOException) {
                log.warn("[Bamboo:API] Download artifact network error: ${e.message}")
                false
            }
        }

    suspend fun getBuildResult(resultKey: String): ApiResult<BambooResultDto> {
        return get("/rest/api/latest/result/$resultKey?expand=stages.stage.results.result")
    }

    /**
     * GET /rest/api/latest/result/byChangeset/{sha}
     * Returns all build results for the given commit SHA. The plan key in each entry is
     * branch-aware (Bamboo returns the branch plan key when the result was built on a branch).
     */
    suspend fun getResultsByChangeset(sha: String): ApiResult<List<com.workflow.orchestrator.bamboo.api.dto.BambooChangesetResultEntry>> =
        get<com.workflow.orchestrator.bamboo.api.dto.BambooResultsByChangesetResponse>(
            "/rest/api/latest/result/byChangeset/$sha?expand=results.result.plan"
        ).map { it.results.result }

    /**
     * GET /rest/api/latest/repository
     * Returns all Linked Repositories configured in Bamboo.
     */
    suspend fun getLinkedRepositories(): ApiResult<List<com.workflow.orchestrator.bamboo.api.dto.BambooLinkedRepository>> =
        get<com.workflow.orchestrator.bamboo.api.dto.BambooLinkedRepositoryListResponse>(
            "/rest/api/latest/repository?max-results=200"
        ).map { it.searchResults.map { item -> item.searchEntity.copy(id = item.id) } }

    /**
     * GET /rest/api/latest/repository/{id}/usedBy
     * Returns all plans (and deployment projects) that use the given linked repository.
     */
    suspend fun getRepositoryUsedBy(id: Int): ApiResult<List<com.workflow.orchestrator.bamboo.api.dto.BambooRepositoryUsage>> =
        get<com.workflow.orchestrator.bamboo.api.dto.BambooRepositoryUsageListResponse>(
            "/rest/api/latest/repository/$id/usedBy"
        ).map { it.results }

    /**
     * GET /rest/api/latest/plan/{masterPlanKey}/branch?max-results={maxResults}
     * Returns all branch plans for the given master plan key.
     * [getBranches] delegates here with maxResults=100 for backward compatibility.
     */
    suspend fun getPlanBranches(
        masterPlanKey: String,
        maxResults: Int = 200
    ): ApiResult<List<com.workflow.orchestrator.bamboo.api.dto.BambooPlanBranch>> =
        get<com.workflow.orchestrator.bamboo.api.dto.BambooPlanBranchListResponse>(
            "/rest/api/latest/plan/$masterPlanKey/branch?max-results=$maxResults"
        ).map { it.branches.branch }

    /**
     * GET /rest/api/latest/result/{resultKey}?expand=changes.change
     *
     * Returns the list of commits included in this build since the last successful build.
     * Validated on Bamboo 10.2.14: bundle-repo.unpacked/raw/result_changes.json.
     * Adopted per §5 R-ADD-1 of the 2026-05-07 Bamboo audit recommendations.
     */
    suspend fun getBuildChanges(resultKey: String): ApiResult<List<com.workflow.orchestrator.bamboo.api.dto.BambooBuildChangeDto>> {
        log.info("[Bamboo:API] getBuildChanges: GET /result/$resultKey?expand=changes.change")
        return get<com.workflow.orchestrator.bamboo.api.dto.BambooBuildChangesResponse>(
            "/rest/api/latest/result/$resultKey?expand=changes.change"
        ).map { it.changes.change }
    }

    /**
     * Returns Success(true) if `GET /rest/api/latest/plan/{candidate}` returns 200,
     * Success(false) on 404, or Error on other status / network error.
     *
     * Used to drop wrong key candidates that survive the parsing waterfall (e.g.,
     * over-stripped keys from extractPlanKey).
     */
    suspend fun validatePlan(candidate: String): ApiResult<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/latest/plan/$candidate")
                    .get()
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    log.debug("[Bamboo:API] validatePlan $candidate -> ${it.code}")
                    when (it.code) {
                        in 200..299 -> ApiResult.Success(true)
                        404 -> ApiResult.Success(false)
                        401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bamboo token")
                        403 -> ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient Bamboo permissions")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Bamboo returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                log.warn("[Bamboo:API] validatePlan network error: ${e.message}")
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bamboo: ${e.message}", e)
            }
        }

    private suspend fun put(path: String): ApiResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url("$baseUrl$path")
                    .put("".toRequestBody("application/json".toMediaType()))
                    .header("Accept", "application/json")
                    // PR 7 / write-path lessons §1: every Bamboo write needs the
                    // XSRF bypass header, including `result/{key}/stop` (PUT). Probe
                    // confirmed Bamboo returns 403 on XSRF check before consulting
                    // BUILD permissions when this header is missing.
                    .header("X-Atlassian-Token", "no-check")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    when {
                        isSuccess(it.code) -> {
                            if (looksLikeAuthRedirect(it.headers)) {
                                ApiResult.Error(
                                    ErrorType.AUTH_REDIRECT,
                                    "Server returned HTML — your session may have expired. Re-authenticate in Settings."
                                )
                            } else ApiResult.Success(Unit)
                        }
                        it.code == 401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bamboo token")
                        it.code == 403 -> ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient Bamboo permissions")
                        it.code == 404 -> ApiResult.Error(ErrorType.NOT_FOUND, "Bamboo resource not found")
                        else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Bamboo returned ${it.code}")
                    }
                }
            } catch (e: IOException) {
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bamboo: ${e.message}", e)
            }
        }

    private suspend fun delete(path: String): ApiResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url("$baseUrl$path").delete()
                    .header("Accept", "application/json")
                    // Same XSRF rule applies to DELETE on the queue endpoint —
                    // see [put] above.
                    .header("X-Atlassian-Token", "no-check")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.use {
                    log.debug("[Bamboo:API] DELETE $path responded with status=${it.code}")
                    when {
                        isSuccess(it.code) -> {
                            if (looksLikeAuthRedirect(it.headers)) {
                                log.warn("[Bamboo:API] DELETE got HTML body — auth redirect")
                                ApiResult.Error(
                                    ErrorType.AUTH_REDIRECT,
                                    "Server returned HTML — your session may have expired. Re-authenticate in Settings."
                                )
                            } else ApiResult.Success(Unit)
                        }
                        it.code == 401 -> { log.warn("[Bamboo:API] Authentication failed (401)"); ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid Bamboo token") }
                        it.code == 403 -> { log.warn("[Bamboo:API] Forbidden (403)"); ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient Bamboo permissions") }
                        it.code == 404 -> { log.warn("[Bamboo:API] Not found (404)"); ApiResult.Error(ErrorType.NOT_FOUND, "Bamboo resource not found") }
                        else -> { log.warn("[Bamboo:API] Server error (${it.code})"); ApiResult.Error(ErrorType.SERVER_ERROR, "Bamboo returned ${it.code}") }
                    }
                }
            } catch (e: IOException) {
                log.warn("[Bamboo:API] Network error: ${e.message}")
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bamboo: ${e.message}", e)
            }
        }
}
