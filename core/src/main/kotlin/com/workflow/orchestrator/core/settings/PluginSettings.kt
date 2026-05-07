package com.workflow.orchestrator.core.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(
    name = "WorkflowOrchestratorSettings",
    storages = [Storage("workflowOrchestrator.xml")]
)
class PluginSettings : SimplePersistentStateComponent<PluginSettings.State>(State()) {

    class State : BaseState() {
        // Polling intervals (seconds)
        var buildPollIntervalSeconds by property(30)

        // Bamboo plan key (auto-detected or user-configured)
        var bambooPlanKey by string("")

        // Automation: this repo's docker tag key in dockerTagsAsJson
        var dockerTagKey by string("")

        // Automation: this repo's CI build plan key (for docker tag extraction)
        var serviceCiPlanKey by string("")

        // SonarQube project key (auto-detected or user-configured)
        var sonarProjectKey by string("")

        // Branch naming pattern
        var branchPattern by string("feature/{ticketId}-{summary}")

        // Active ticket (persisted across restarts)
        var activeTicketId by string("")
        var activeTicketSummary by string("")
        var jiraBoardId by property(0)
        var jiraBoardName by string("")
        var boardFilterRegex by string("")

        // AI availability determined by LlmBrainFactory.isAvailable() (Sourcegraph URL + token)

        // Health check settings
        var healthCheckEnabled by property(true)
        var healthCheckBlockingMode by string("soft")
        var healthCheckCompileEnabled by property(true)
        var healthCheckTestEnabled by property(true)
        var healthCheckCopyrightEnabled by property(true)
        var healthCheckSonarGateEnabled by property(true)
        var healthCheckMavenGoals by string("clean compile test")
        var healthCheckSkipBranchPattern by string("")
        var healthCheckTimeoutSeconds by property(300)
        var copyrightHeaderPattern by string("")
        /**
         * Multiline template used to insert a copyright header into files that
         * don't have one (Handover → Copyright → Fix All). `{year}` is replaced
         * with the current year. Comment-wrapping for the file's language is
         * applied at write time by `CopyrightFixService.wrapForLanguage`.
         */
        var copyrightTemplate by string("")

        // Automation queue settings
        var queueAutoTriggerEnabled by property(true)
        var queueMaxDepthPerSuite by property(10)

        // Phase 2B: Handover settings
        var defaultTargetBranch by string("develop")
        var branchTargetOverrides by string("")
        var bitbucketProjectKey by string("")
        var bitbucketRepoSlug by string("")
        var startWorkTimestamp by property(0L)

        // --- Configurable values (Phase: Config Extraction) ---

        // Jira workflow mapping (serialized JSON of TransitionMapping list)
        var workflowMappings by string("")

        // Jira board type filter ("scrum", "kanban", or "" for all)
        var jiraBoardType by string("scrum")

        // HTTP timeouts (seconds) — applied to all API clients via HttpClientFactory
        var httpConnectTimeoutSeconds by property(10)
        var httpReadTimeoutSeconds by property(30)

        // Time tracking
        var maxWorklogHours by property(7.0f)
        var worklogIncrementHours by property(0.5f)

        // VCS commit handler toggles
        var autoTransitionOnCommit by property(false)

        // Branching & PRs
        var branchMaxSummaryLength by property(50)
        var prTitleFormat by string("{ticketId}: {summary}")
        /** When true, PR creation uses AI to generate a richer PR title from ticket + diff context. */
        var enableAiTitleGeneration by property(true)
        var maxPrTitleLength by property(120)
        var prDefaultReviewers by string("")

        // Jira custom fields
        var epicLinkFieldId by string("customfield_10014")
        /** Custom field ID for the Jira acceptance-criteria field (e.g. "customfield_10001"). Null = unused. */
        var jiraAcceptanceCriteriaFieldId by string("")

        // AI review
        var maxDiffLinesForReview by property(10000)

        // SonarQube
        var coverageGutterMarkersEnabled by property(false)
        var sonarIntentionActionEnabled by property(false)
        var sonarInlineAnnotationsEnabled by property(false)
        /**
         * Last user-chosen Sonar Quality tab mode (0=unset/follow heuristic,
         * 1=Overall, 2=NewCode). On unset, the panel defaults to NewCode for
         * non-main branches and Overall on main. On set, the user's choice wins
         * across IDE restarts. Stored as Int because BaseState's `property` doesn't
         * have a nullable Boolean delegate.
         */
        var sonarPreferredCodeMode by property(0)

        // Automation
        /** Canonical Bamboo plan variable carrying the Docker tags JSON payload.
         *  Probe-confirmed value: "DockerTagsAsJSON" (bundle-automation plan_variables_via_context.json).
         *  All readers must compare case-insensitively so user-configured variants still match. */
        var bambooBuildVariableName by string("DockerTagsAsJSON")

        // Sprint dashboard view preferences
        var sprintSortBy by string("Default")

        // Multi-repo configuration
        var repos by list<RepoConfig>()

        // ── Raw API trace (diagnostic feature) ──────────────────────────────
        // Controls verbatim LLM traffic capture. RawApiTraceConfig is the runtime
        // singleton; mode and retention are surfaced here for diagnostic bundle reporting.

        /** Trace mode: "OFF", "ALWAYS_ON", or "BURST". */
        var rawApiTraceMode by string("OFF")

        /** How many days to keep dated raw-api trace directories on disk. */
        var rawApiTraceRetentionDays by property(3)

        // ── Telemetry & Logs settings ─────────────────────────────────────────────
        var logLevel by string("INFO")
        var diagnosticJsonlEnabled by property(true)
        var retentionDays by property(7)
        var includeCommandOutputInLogs by property(false)
        var costDisplayEnabled by property(true)

        // ── Insights: weekly digest ───────────────────────────────────────────────
        /** When true, a report is auto-generated every Monday morning at startup. */
        var weeklyDigestEnabled by property(false)

        // ── Ticket Transitions ───────────────────────────────────────────────
        /**
         * Preferred target status name when Start Work creates a branch. Drives the
         * dialog's pre-selection only — every transition is confirmed in
         * [com.workflow.orchestrator.jira.ui.TicketTransitionDialog]. Leave empty to
         * skip the post-Start-Work transition prompt entirely.
         */
        var ticketTransitionDefaultStartWorkStatusName by string("In Progress")

        /**
         * Preferred target status after PR creation. Drives the dialog's pre-selection
         * only — every transition is confirmed in
         * [com.workflow.orchestrator.jira.ui.TicketTransitionDialog]. Leave empty to
         * skip the post-PR transition prompt entirely.
         */
        var ticketTransitionDefaultPrCreateStatusName by string("In Review")

        // ── Bamboo auto-detection ────────────────────────────────────────────────
        /**
         * When true, plan auto-detection falls back to the full N+1 plan-listing scan
         * (Tier 4) after Tier 0 (local bamboo-specs) and Tier 1 (Bitbucket commit walk)
         * both miss. Disabled by default because the scan is slow (one HTTP call per plan).
         */
        var bambooDeepScanEnabled by property(false)

        /**
         * Validation cache for Bamboo plan keys, keyed by plan key.
         * Positive validations persist indefinitely (plan keys don't change once created).
         * Negative validations expire after 5 min.
         *
         * Format: "PROJ-PLAN=POSITIVE" or "PROJ-PLAN=NEGATIVE:<expiryEpochMs>"
         */
        var bambooPlanValidationCache by list<String>()

        // ── Document extraction settings (Phase 8) ───────────────────────────────────

        /**
         * Maximum number of characters extracted from a single document by [TikaDocumentExtractor].
         *
         * v1 semantics: a value ≤ 0 means "no cap" (translated to [Int.MAX_VALUE] at the
         * call site). The default of 200 000 matches the hard-coded fallback used before
         * Phase 8.
         */
        var documentMaxChars by property(200_000)

        /**
         * Per-call extraction timeout in milliseconds passed to [TikaDocumentExtractor].
         *
         * v1: consumed by [DocumentTool] via [ExtractOptions.timeoutMs].
         * Default of 30 000 ms (30 s) matches the [DocumentTool.timeoutMs] constant.
         */
        var documentTimeoutMs by property(30_000L)

        /**
         * When true, [TikaDocumentExtractor] requests Tabula's stream-mode fallback for
         * PDF table extraction.
         *
         * v1 default: false (lattice mode). Stream mode can improve whitespace-heavy tables
         * but may introduce phantom table rows on multi-column prose documents.
         */
        var documentEnableStreamMode by property(false)

        /**
         * v2 stub — OCR support for scanned PDFs is not yet implemented.
         *
         * When true, the user has opted in to OCR processing. The flag is persisted now so
         * v2 can honour previously-saved preferences without a migration. The settings UI
         * renders this control as disabled ("Coming in v2") in v1.
         */
        var documentOcrEnabled by property(false)

        // ── Multimodal image-attachment settings (Phase 5) ────────────────────────────
        //
        // User-visible controls for image input. UI lives at
        // Tools > Workflow Orchestrator > Multimodal (MultimodalSettingsConfigurable).
        // Defaults mirror the gateway's vision-capable model whitelist.

        /**
         * Per-attachment maximum size in bytes. Validated client-side in the
         * webview before any bridge round-trip and again server-side by
         * AttachmentUploadHandler. Default: 5 MB.
         */
        var imageMaxBytes by property(5_242_880L)

        /**
         * Maximum number of images attachable in a single user turn (mirrors
         * Cody's per-turn cap). Default: 2.
         */
        var imagesPerTurnCap by property(2)

        /**
         * Kill switch. When false, the paperclip menu hides the image action
         * and paste/drag-drop reject image content. Default: true.
         */
        var enableImageInput by property(true)

        /**
         * Token-cost estimate per image used for pre-send budget warnings.
         * Authoritative cost is `usage.prompt_tokens` from the response after
         * the call returns; this estimate is only consulted before the request.
         * Default: 1500 (Anthropic vision pricing model approximation).
         */
        var imageTokenEstimateDefault by property(1500)

        /**
         * MIME types accepted for image attachments. Default reflects the
         * gateway-verified set probed by `tools/sourcegraph-probe/format_lab.py`
         * on 2026-05-05 (api-version=9): only PNG, JPEG, and WebP round-trip
         * cleanly through every vision-capable Claude 4.5 model.
         *
         * HEIC and HEIF were previously in this list because they appear in
         * Cody's web UI whitelist (`MediaUploadButton.tsx`), but the upstream
         * provider on this Sourcegraph instance rejects them with an SSE
         * `event: error` frame for 6/6 models — they look advertised but die
         * on the wire. GIF works on 3/6 models inconsistently and is therefore
         * not in the user-paste whitelist either (the tool-output autoload
         * path retains GIF separately via [toolImageAutoloadMimeWhitelist]).
         *
         * Populated in the `init` block below on first instantiation.
         */
        var imageMimeWhitelist by list<String>()

        // ── Tool-produced image auto-load (Phase 4 of multimodal-agent plan) ─────
        //
        // Controls whether tool results that produce image bytes (e.g. Jira
        // download_attachment of a PNG) automatically materialize into the
        // active session's AttachmentStore so the next LLM turn routes through
        // the vision path.

        /**
         * Master toggle for tool-produced image auto-loading. When false, tool
         * results are emitted text-only and image bytes never reach the
         * AttachmentStore even if a tool would otherwise auto-load them.
         */
        var enableToolImageAutoload: Boolean = true

        /**
         * MIME types eligible for tool-produced image auto-load. Default mirrors
         * the user-paste whitelist surface (PNG, JPEG, WebP, GIF) — the user-paste
         * path uses [imageMimeWhitelist] for input validation, this mirror is the
         * dual filter for tool output.
         */
        var toolImageAutoloadMimeWhitelist: MutableSet<String> = mutableSetOf(
            "image/png", "image/jpeg", "image/webp", "image/gif"
        )

        init {
            // Populate default whitelist on first instantiation. Persisted lists
            // round-trip independently — if the user clears the list it stays
            // empty (which the UI surfaces as "no MIME types accepted").
            if (imageMimeWhitelist.isEmpty()) {
                imageMimeWhitelist.addAll(
                    listOf("image/png", "image/jpeg", "image/webp")
                )
            }
        }
    }

    /**
     * Convenience accessor for global connection URLs.
     */
    val connections: ConnectionSettings.State
        get() = ConnectionSettings.getInstance().state

    val isAnyServiceConfigured: Boolean
        get() {
            val gs = ConnectionSettings.getInstance().state
            return gs.jiraUrl.isNotBlank() ||
                    gs.bambooUrl.isNotBlank() ||
                    gs.bitbucketUrl.isNotBlank() ||
                    gs.sonarUrl.isNotBlank() ||
                    gs.sourcegraphUrl.isNotBlank()
        }

    // ---- Multi-repo convenience accessors ----

    fun getRepos(): List<RepoConfig> = state.repos.toList()

    fun getPrimaryRepo(): RepoConfig? = state.repos.find { it.isPrimary } ?: state.repos.firstOrNull()

    fun getRepoForPath(vcsRootPath: String): RepoConfig? = state.repos.find { it.localVcsRootPath == vcsRootPath }

    fun getRepoByName(name: String): RepoConfig? = state.repos.find { it.name == name }

    companion object {
        fun getInstance(project: Project): PluginSettings {
            return project.service<PluginSettings>()
        }
    }
}

/**
 * Records a validation result in the persistent Bamboo plan validation cache.
 *
 * Positive entries persist indefinitely (plan keys don't change once created).
 * Negative entries are stored with an expiry timestamp 5 minutes in the future.
 */
fun PluginSettings.recordPlanValidation(key: String, valid: Boolean) {
    val cacheList = state.bambooPlanValidationCache
    cacheList.removeAll { it.startsWith("$key=") }
    if (valid) {
        cacheList.add("$key=POSITIVE")
    } else {
        cacheList.add("$key=NEGATIVE:${System.currentTimeMillis() + 5 * 60 * 1000L}")
    }
}

/**
 * Looks up a validation result from the persistent Bamboo plan validation cache.
 *
 * Returns:
 * - `true`  — plan key is known-valid (positive cache hit)
 * - `false` — plan key is known-invalid and the negative entry has not yet expired
 * - `null`  — unknown / missing / negative entry has expired (caller must re-validate)
 */
fun PluginSettings.lookupPlanValidation(key: String): Boolean? {
    val now = System.currentTimeMillis()
    val entry = state.bambooPlanValidationCache.firstOrNull { it.startsWith("$key=") } ?: return null
    return when {
        entry == "$key=POSITIVE" -> true
        entry.startsWith("$key=NEGATIVE:") -> {
            val expiry = entry.substringAfter("$key=NEGATIVE:").toLongOrNull() ?: return null
            if (expiry > now) false else null
        }
        else -> null
    }
}

/**
 * Clears all entries in the Bamboo plan-validation cache. Used by Settings UI when a
 * plan is renamed or deleted on the server and the persistent POSITIVE entry would
 * otherwise stick forever.
 */
fun PluginSettings.clearPlanValidationCache() {
    state.bambooPlanValidationCache.clear()
}
