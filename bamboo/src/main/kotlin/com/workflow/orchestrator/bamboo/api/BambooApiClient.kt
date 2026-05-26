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
        return paginate(pageSize = 100, maxPages = 50, label = "plans") { start ->
            get<BambooPlanListResponse>(
                "/rest/api/latest/plan?expand=plans.plan&max-results=100&start-index=$start"
            ).map { page -> page.plans.plan }
        }
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
        paginate(pageSize = 100, maxPages = 50, label = "branches/$planKey") { start ->
            get<BambooBranchListResponse>(
                "/rest/api/latest/plan/$planKey/branch?max-results=100&start-index=$start"
            ).map { page -> page.branches.branch }
        }

    suspend fun getLatestResult(planKey: String, branch: String? = null): ApiResult<BambooResultDto> {
        // A branch plan key looks like PROJ-PLAN-7 (three dash-separated segments where
        // the last segment is a digit string). PlanDetectionService.resolveBranchKey uses
        // the same regex to skip resolution when the key is already a branch plan key.
        //
        // The previous heuristic (!planKey.last().isDigit()) was fragile: any master plan
        // key ending in a digit (e.g. PROJ-BUILD2) would skip the branch path even when
        // a branch was specified, always showing the master plan's build instead.
        //
        // Correct logic: if the key already matches the branch-plan-key pattern, use it
        // directly (the caller has already resolved the branch key). Only use the
        // /branch/{name}/latest URL form when the key is a master plan key.
        val isBranchPlanKey = planKey.matches(BRANCH_PLAN_KEY_REGEX)
        val path = if (branch != null && !isBranchPlanKey) {
            val encodedBranch = URLEncoder.encode(branch, "UTF-8")
            "/rest/api/latest/result/$planKey/branch/$encodedBranch/latest?expand=stages.stage.results.result"
        } else {
            "/rest/api/latest/result/$planKey/latest?expand=stages.stage.results.result"
        }
        log.info("[Bamboo:API] getLatestResult: GET $path (isBranchPlanKey=$isBranchPlanKey)")
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
     * Plan metadata (key, name, shortName) via the basic `/plan/{key}` endpoint.
     * Used by the Automation tab to refresh stored suite display names against
     * Bamboo's canonical short name when the saved value still carries the old
     * long `Project — Plan` form (legacy suites added before v0.85.0). Returns
     * an empty `shortName` when Bamboo's response omits the field — caller is
     * responsible for falling back to the saved displayName in that case.
     */
    suspend fun getPlanInfo(planKey: String): ApiResult<BambooPlanDetailResponse> {
        log.debug("[Bamboo:API] getPlanInfo: GET /plan/$planKey")
        return get<BambooPlanDetailResponse>("/rest/api/latest/plan/$planKey")
    }

    /**
     * Queue a build for a Bamboo plan with optional stage selection.
     *
     * This is the single Bamboo trigger primitive. All callers must route through here.
     *
     * **Wire contract (documented + empirically verified by `--probe-stage-bound`
     * against the user's Bamboo on 2026-05-10):**
     *
     * - `selectedStages == null` → run all stages. Uses the REST queue endpoint with
     *   `executeAllStages=true`. Explicit "run everything" escape hatch, not a default.
     * - `selectedStages != null && selectedStages.isEmpty()` → rejected with an error.
     *   An empty selection is invalid (Bamboo would default to all stages, silently
     *   violating the caller's intent).
     * - `selectedStages != null && selectedStages.isNotEmpty()` → form POST to the
     *   REST queue endpoint with `executeAllStages=false&stage=<lastSelectedInPlanOrder>`.
     *   Per Atlassian's REST docs (literal: *"name of the stage that should be executed
     *   even if manual stage. Execution will follow to the next manual stage after this
     *   or end of plan if no subsequent manual stage"*) and the accepted Steffen Opel
     *   answer on community.atlassian.com (`?stage=X&executeAllStages=false` runs
     *   *up to and including* X), the `stage` param is the UPPER BOUND. Bamboo runs
     *   every plan stage from the start through the named stage and stops.
     *
     * **Why `.last()` and not `.first()`.** The set iterates in plan order (production
     * callers build it from the dialog's checkbox list — a `LinkedHashSet` preserving
     * plan stage order). The user's checked stages form a contiguous-or-not subset; the
     * latest plan-order stage they checked is the upper bound we want Bamboo to run to.
     * v0.84.10 sent `.first()` which made Bamboo run only up to the FIRST checked stage —
     * the user reported "I select two stages, only one runs". `.last()` honours their
     * intent: selecting {Build, Test} runs Build then Test (and stops), which is what
     * the dialog promises.
     *
     * **Why REST and not the Struts action endpoint.** Commit `c3a38117a` switched to
     * `/build/admin/ajax/runChainAction.action` for non-contiguous subset selection;
     * that path 404'd in production. Bamboo 10.0+ removed Struts DMI (the `!method.action`
     * URL form) per the 10.0 EAP release notes, so that endpoint is officially
     * deprecated. REST is the only sanctioned path forward.
     *
     * **Architectural limitation (Bamboo design, not REST API).** Per Atlassian docs,
     * manual stages must execute in plan order — a manual stage can only be triggered
     * if its predecessor completed. Non-contiguous selection (e.g. {Build, Deploy}
     * skipping Test) is not expressible in Bamboo, period. The dialog's checkbox UI
     * lets the user select arbitrary subsets, but the wire layer collapses that to
     * "the latest checked stage as the bound" — Bamboo will run every stage up to
     * that bound regardless of which intermediate stages the user unchecked.
     *
     * **Form encoding.** Uses [postForm] which sets `X-Atlassian-Token: no-check`
     * (DC XSRF bypass — empirically required, otherwise the queue endpoint 403s) and
     * sends variables as `bamboo.variable.<k>=<v>` form fields. Bamboo silently drops
     * variables sent as JSON, so form encoding is mandatory — see PR 2 of the
     * write-ops fix plan and the `project_bamboo_write_path_lessons` memory note.
     *
     * @param chainKey the resolved chain/plan key (e.g. `PROJ-BUILD` or `PROJ-BUILD523`).
     * @param variables map of variable name → value. Sent as `bamboo.variable.<k>=<v>`.
     * @param selectedStages set of stage names to run. null = all stages; empty = error;
     *   non-empty → REST `?stage=<last in plan order>` (Bamboo runs every stage from
     *   the plan start up to and including that stage).
     */
    suspend fun queueBuildWithStageSelection(
        chainKey: String,
        variables: Map<String, String> = emptyMap(),
        selectedStages: Set<String>? = null,
    ): ApiResult<BambooQueueResponse> {
        // Reject empty selection — ambiguous intent, not a silent fallback.
        if (selectedStages != null && selectedStages.isEmpty()) {
            log.warn("[Bamboo:API] queueBuildWithStageSelection: selectedStages is empty — rejecting")
            return ApiResult.Error(
                ErrorType.VALIDATION_ERROR,
                "Stage selection is empty. Select at least one stage or pass null to run all stages."
            )
        }

        val varFields = variables.mapKeys { (k, _) -> "bamboo.variable.$k" }

        val query = if (selectedStages == null) {
            log.debug("[Bamboo:API] queueBuildWithStageSelection chainKey=$chainKey, stages=ALL, variables=${variables.keys}")
            "?executeAllStages=true"
        } else {
            // Bamboo's `stage` param is the UPPER BOUND — execution runs every plan
            // stage from the start through the named stage and stops there. The set
            // iterates in plan order (LinkedHashSet built from the dialog's plan-
            // ordered checkbox list), so .last() returns the latest plan-order stage
            // the user checked, which is the bound we want Bamboo to run to.
            val boundStage = selectedStages.last()
            log.debug("[Bamboo:API] queueBuildWithStageSelection chainKey=$chainKey, stages=$selectedStages, boundStage=$boundStage, variables=${variables.keys}")
            "?executeAllStages=false&stage=${URLEncoder.encode(boundStage, "UTF-8")}"
        }

        val url = "$baseUrl/rest/api/latest/queue/$chainKey$query"
        return when (val raw = postForm(httpClient, url, varFields)) {
            is ApiResult.Success -> {
                try {
                    ApiResult.Success(json.decodeFromString<BambooQueueResponse>(raw.data))
                } catch (e: Exception) {
                    log.warn("[Bamboo:API] queueBuildWithStageSelection parse failed: ${e.message}")
                    log.debug("[Bamboo:API] queueBuildWithStageSelection response (first 200): ${raw.data.take(200)}")
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

    /**
     * Enable a disabled Bamboo plan branch so its jobs/stages can run.
     *
     * In Bamboo a branch IS a plan with its own key (e.g. `PROJ-AUTOMATIONTEST-3`),
     * so enabling a branch = `POST /rest/api/latest/plan/{branchPlanKey}/enable` with
     * an empty form body.
     *
     * Routes through [postForm] so [X-Atlassian-Token: no-check] is set automatically
     * (write-path lessons §1 — Bamboo Struts/REST write operations 403 without it).
     */
    suspend fun enablePlanBranch(branchPlanKey: String): ApiResult<Unit> {
        val url = "$baseUrl/rest/api/latest/plan/${URLEncoder.encode(branchPlanKey, "UTF-8")}/enable"
        log.debug("[Bamboo:API] Enabling plan branch $branchPlanKey")
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
        paginate(pageSize = 200, maxPages = 50, label = "repositories") { start ->
            get<com.workflow.orchestrator.bamboo.api.dto.BambooLinkedRepositoryListResponse>(
                "/rest/api/latest/repository?max-results=200&start-index=$start"
            ).map { page -> page.searchResults.map { item -> item.searchEntity.copy(id = item.id) } }
        }

    /**
     * GET /rest/api/latest/repository/{id}/usedBy
     * Returns all plans (and deployment projects) that use the given linked repository.
     */
    suspend fun getRepositoryUsedBy(id: Int): ApiResult<List<com.workflow.orchestrator.bamboo.api.dto.BambooRepositoryUsage>> =
        get<com.workflow.orchestrator.bamboo.api.dto.BambooRepositoryUsageListResponse>(
            "/rest/api/latest/repository/$id/usedBy"
        ).map { it.results }

    /**
     * GET /rest/api/latest/plan/{masterPlanKey}/branch
     * Returns all branch plans for the given master plan key. Paginates with 50-page cap.
     */
    suspend fun getPlanBranches(
        masterPlanKey: String,
        maxResults: Int = 200
    ): ApiResult<List<com.workflow.orchestrator.bamboo.api.dto.BambooPlanBranch>> =
        paginate(pageSize = maxResults, maxPages = 50, label = "planBranches/$masterPlanKey") { start ->
            get<com.workflow.orchestrator.bamboo.api.dto.BambooPlanBranchListResponse>(
                "/rest/api/latest/plan/$masterPlanKey/branch?max-results=$maxResults&start-index=$start"
            ).map { page -> page.branches.branch }
        }

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

    /**
     * Generic Bamboo offset-pagination loop (50-page cap, shared by [getPlans],
     * [getBranches], [getPlanBranches], and [getLinkedRepositories]).
     *
     * The next offset is derived from the offset we *requested* plus the number of items
     * the page *actually contained* — never from the server-echoed `start-index`/`size`
     * fields. Some Bamboo endpoints (notably `/plan/{key}/branch`) echo `start-index: 0`
     * on every page while still honouring the requested offset; trusting that echoed
     * value stalled the cursor and re-fetched the same window up to the page cap, so the
     * same branch was aggregated repeatedly. A page shorter than [pageSize] is the last one.
     *
     * @param pageSize   Number of items requested per page (same value used in the URL).
     * @param maxPages   Safety cap — stops after this many pages even if more exist.
     * @param label      Human-readable description for the debug log.
     * @param fetchPage  Suspending fetcher that accepts a start-index and returns that page's items.
     */
    private suspend fun <T> paginate(
        pageSize: Int,
        maxPages: Int,
        label: String,
        fetchPage: suspend (startIndex: Int) -> ApiResult<List<T>>,
    ): ApiResult<List<T>> {
        val aggregated = mutableListOf<T>()
        var startIndex = 0
        var pages = 0
        while (pages < maxPages) {
            when (val result = fetchPage(startIndex)) {
                is ApiResult.Error -> return result
                is ApiResult.Success -> {
                    val items = result.data
                    aggregated += items
                    pages++
                    if (items.size < pageSize) {
                        // Short page (incl. empty) ⇒ no more results.
                        log.debug("[Bamboo:API] paginate($label): $pages page(s), ${aggregated.size} total items")
                        return ApiResult.Success(aggregated)
                    }
                    startIndex += items.size
                }
            }
        }
        log.warn("[Bamboo:API] paginate($label): cap of $maxPages pages hit (${aggregated.size} items collected)")
        return ApiResult.Success(aggregated)
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

    companion object {
        /**
         * Regex that matches a Bamboo branch plan key: three or more dash-separated segments
         * where the final segment is a pure digit string (e.g. PROJ-PLAN-7, MYPROJ-BUILD-123).
         *
         * Master plan keys (e.g. PROJ-PLAN, PROJ-BUILD2) do NOT match this pattern.
         *
         * Mirrors [PlanDetectionService.resolveBranchKey] and
         * [PlanDetectionService.resolveBranchKeyOrNull] so the two code paths stay in lockstep.
         */
        internal val BRANCH_PLAN_KEY_REGEX = Regex("^.+-.+-\\d+$")
    }
}
