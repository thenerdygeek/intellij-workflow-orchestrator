package com.workflow.orchestrator.core.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

/**
 * Neutral default target branch (Phase 1a de-convention). Replaced the company convention
 * "develop". Existing installs keep "develop" via [SettingsMigration] v1->v2 seeding.
 */
const val NEUTRAL_DEFAULT_TARGET_BRANCH = "main"

@Service(Service.Level.PROJECT)
@State(
    name = "WorkflowOrchestratorSettings",
    storages = [Storage("workflowOrchestrator.xml")]
)
class PluginSettings : SimplePersistentStateComponent<PluginSettings.State>(State()) {

    class State : BaseState() {
        /** Schema version for one-shot settings migrations (see SettingsMigration). 0 = pre-migration. */
        var settingsSchemaVersion by property(0)

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
        var defaultTargetBranch by string(NEUTRAL_DEFAULT_TARGET_BRANCH)
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

        /**
         * Comma-separated list of (case-insensitive) status names that the post-commit
         * transition check treats as "not started yet". When a successful commit's active
         * ticket is in one of these statuses, the plugin surfaces a notification offering to
         * open the transition dialog (see
         * [com.workflow.orchestrator.jira.vcs.PostCommitTransitionLogic]). Each entry is
         * trimmed, lowercased, and blank entries are ignored. Defaults to the original
         * hardcoded set so behavior is unchanged out of the box (audit jira:F-14).
         */
        var postCommitTransitionTriggerStatuses by string("to do,open,new,backlog,selected for development")

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
         * Wall-clock budget (ms) for a single background document extraction job, INCLUDING time
         * spent waiting on the extractor's concurrency semaphore. Distinct from [documentTimeoutMs],
         * which is the per-read serving timeout. Default 300 000 ms (5 min).
         */
        var documentExtractionJobTimeoutMs by property(300_000L)

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
        // Tools > Workflow Orchestrator > AI Agent > Multimodal (MultimodalSettingsConfigurable).
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
         * Master visual-support kill switch. When false, all five image-handling
         * surfaces are silenced: the view_image tool is unregistered from the
         * LLM's tool list, image uploads in chat are blocked, BrainRouter treats
         * every turn as text-only (routing to /messages rather than /stream),
         * legacy image blocks in session history are stripped at the request
         * boundary, and tool-output image auto-load is suppressed regardless of
         * [enableToolImageAutoload]. Default: false (panic-button posture).
         *
         * Sub-flags ([enableToolImageAutoload], MIME whitelists, [imageMaxBytes],
         * [imagesPerTurnCap]) are preserved in storage when this is false; they
         * reactivate automatically when the master is re-enabled.
         */
        var enableImageInput by property(false)

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

        /**
         * Per-attachment maximum size in bytes for NON-image files (documents,
         * text). Larger than [imageMaxBytes] because file attachments are stored
         * on disk and read lazily via read_file/read_document, not inlined into
         * a vision payload. Default: 50 MB.
         */
        var fileMaxBytes by property(52_428_800L)

        /**
         * Maximum number of NON-image file attachments per user turn. Independent
         * of [imagesPerTurnCap]. Default: 5.
         */
        var filesPerTurnCap by property(5)

        // ── Handover: Quick Clipboard chips ──────────────────────────────────────

        /**
         * Ordered list of chip keys shown in the Handover → Share quick-clipboard grid.
         * Each key maps to a runtime value provider (e.g. "docker.tag" → latest Docker tag).
         * The default 8-item list covers the most commonly shared artefacts.
         *
         * Settings UI for this field is added in T25. The list is persisted via
         * [by list<String>()][com.intellij.openapi.components.BaseState] so user
         * edits survive IDE restarts.
         *
         * Populated in the `init` block below on first instantiation.
         */
        var quickClipboardChips by list<String>()

        // ── Tool-produced image auto-load (Phase 4 of multimodal-agent plan) ─────
        //
        // Controls whether tool results that produce image bytes (e.g. Jira
        // download_attachment of a PNG) automatically materialize into the
        // active session's AttachmentStore so the next LLM turn routes through
        // the vision path.

        // ── Handover settings (T25) ─────────────────────────────────────────────

        /**
         * When true, the handover placeholder resolver will compute AI-generated
         * summaries for `{ai.changeSummary}` and `{ai.ticketSummary}` chips.
         * When false, those chips resolve to an empty string without invoking the LLM.
         * UI: Tools > Workflow Orchestrator > Handover > AI summaries.
         */
        var aiSummariesEnabled by property(true)

        /**
         * Persisted log of [com.workflow.orchestrator.core.events.WorkflowEvent.HandoverOverride]
         * timestamps, stored as ISO-8601 strings (e.g. "2026-04-01T14:32:00Z").
         *
         * Written by `HandoverOverrideTracker` in `:handover` on every override event.
         * Read by [HandoverConfigurable] to compute the 30-day rolling count without
         * requiring a cross-module service reference. Entries older than 30 days are pruned
         * by [HandoverConfigurable.count30d] and by the tracker on every write.
         */
        var handoverOverrideLog by list<String>()

        /**
         * Master toggle for tool-produced image auto-loading. When false, tool
         * results are emitted text-only and image bytes never reach the
         * AttachmentStore even if a tool would otherwise auto-load them.
         */
        var enableToolImageAutoload: Boolean = true

        /**
         * Live streaming-diff preview for `edit_file` tool calls. When true (default),
         * the chat panel renders the unified diff for an in-flight `edit_file` while
         * the LLM is still emitting `<new_string>` — the diff refreshes on 100ms ticks
         * so the user watches lines appear in the preview. When false, no preview is
         * shown until the tool call completes and (where applicable) the approval card
         * appears with the final diff.
         *
         * Kill switch — gate evaluated in `AgentLoop.onChunk`. Default true since the
         * feature only improves UX; flip to false if it ever turns out to be laggy.
         */
        var enableStreamingEditPreview: Boolean = true

        /**
         * When true (default), `create_file` and `delete_file` automatically sync `MEMORY.md`
         * whenever a memory file under `{agentDir}/memory/` is created or deleted.
         *
         * Kill switch — flip to false to disable the hook entirely. The underlying file
         * operation (create / delete) still proceeds; only the index update is suppressed.
         */
        var memoryAutoIndexEnabled: Boolean = true

        /**
         * MIME types eligible for tool-produced image auto-load. Default mirrors
         * the user-paste whitelist surface (PNG, JPEG, WebP, GIF) — the user-paste
         * path uses [imageMimeWhitelist] for input validation, this mirror is the
         * dual filter for tool output.
         */
        var toolImageAutoloadMimeWhitelist: MutableSet<String> = mutableSetOf(
            "image/png", "image/jpeg", "image/webp", "image/gif"
        )

        // Cross-IDE agent delegation (default off — feature is opt-in).
        // Spec: docs/superpowers/specs/2026-05-22-cross-ide-agent-delegation-design.md §3.3.
        var enableOutboundCrossIdeDelegation by property(false)
        var enableInboundCrossIdeDelegation by property(false)
        // When on, Agent-A's proposed answer to Agent-B's clarifying questions
        // is forwarded without an IDE-A human confirmation prompt.
        // Default off — the safe default per spec §6.3.
        var autoApproveDelegationAnswers by property(false)

        /**
         * Idle timeout for cross-IDE delegation channels, in minutes. A delegated
         * session is closed with [DelegationException.IdleTimedOut] when no IPC
         * traffic has been observed (including the periodic Heartbeat from IDE-B)
         * for longer than this many minutes. 0 disables idle detection entirely.
         *
         * Default: 30 minutes. Read at idle-check time so changes take effect
         * for the next check on every existing channel.
         *
         * Plan 3 spec §3.3.
         */
        var delegationIdleTimeoutMinutes by property(30)

        /**
         * How long (seconds) the target IDE waits for the human to click Start on
         * the busy-takeover prompt before declining an incoming delegation.
         *
         * Default: 55 s — matches [com.workflow.orchestrator.agent.ui.AgentController.ACCEPT_WINDOW_MS]
         * / 1000 (55_000 ms), which is the single source of truth for the default value.
         *
         * Invariant: [effectiveAcceptWindowMs] must stay STRICTLY LESS than IDE-A's
         * `connectAndAwaitAccept` acceptTimeoutMillis (60_000 ms). Values that would
         * violate this are clamped at 59 s inside [effectiveAcceptWindowMs].
         *
         * Range surfaced in the settings UI: 10–600 s (operator-facing; the 59 s
         * invariant ceiling is enforced silently at runtime by [effectiveAcceptWindowMs]).
         */
        var delegationAcceptWindowSeconds by property(55)

        /**
         * How long (seconds) the `render_artifact` tool waits for the sandbox iframe to
         * report a render outcome before returning a Timeout. Data-heavy components (deep
         * nesting, large inline datasets, many stat computations) can legitimately exceed
         * the original 30 s. Range clamped to 5–300 s at read time. Default: 60 s.
         */
        var artifactRenderTimeoutSeconds by property(60)

        // ── Sub-agent personas ────────────────────────────────────────────────
        /**
         * Enables the bundled `research` sub-agent persona. When disabled, both the
         * `/research` slash command and direct `agent(agent_type="research", ...)` calls
         * return `RESEARCH_SUBAGENT_DISABLED` from `SpawnAgentTool` (no silent failure).
         * Project-level; default ON matches the house style for web_fetch / web_search.
         */
        var enableResearchSubagent by property(true)

        // ── Web tools (added 2026-05-23) ─────────────────────────────────────
        var enableWebFetch by property(true)
        var enableWebSearch by property(true)
        var webPlanModeAllow by property(false)

        // Search-specific
        /** Which search provider to use. One of: NONE | SEARXNG | CUSTOM_HTTP */
        var webSearchProviderType by string("NONE")
        /** Maximum snippet length (chars) returned per search hit after sanitization. */
        var webSearchSnippetMaxChars by property(500)
        /** Maximum number of search results returned to the agent. */
        var webSearchMaxResults by property(10)

        // fetch — allowlist
        var webAllowlistJson by string("[]")         // serialized List<DomainAllowlistEntry>
        var webUnlistedPolicy by string("PROMPT")    // REJECT | PROMPT
        var webApprovalTimeoutSec by property(60)

        // fetch — content limits
        // Raw download cap. 2 MB: modern article pages (e.g. Baeldung) routinely exceed the
        // old 256 KB cap with bloated HTML, hitting RESPONSE_TOO_LARGE before extraction.
        // What the LLM sees is bounded separately by webMaxExtractedChars after jsoup strips
        // scripts/styles/nav; this only governs the bytes pulled off the socket.
        var webMaxBytes by property(2_097_152)
        var webMaxExtractedChars by property(32_768)
        var webConnectTimeoutSec by property(10)
        var webReadTimeoutSec by property(30)
        var webRequireHttps by property(true)
        var webAllowIpLiteral by property(false)
        var webResolveShorteners by property(true)

        // fetch — sanitizer
        var webSanitizerBrainId by string("")        // blank = use cheapest available
        // Note: content-sanitizer fail-closed is now mandatory (no toggle) — a sanitizer
        // timeout always blocks the content rather than passing it raw.

        // fetch/search — egress filter (added 2026-05-24)
        /** JSON-encoded `List<String>` of user-supplied deny-list entries (force-substituted). */
        var webEgressDenyListJson by string("[]")
        // Note: the LLM egress screener is now mandatory (no toggle) — it always runs on
        // every web_search query and rewrites sensitive data to dummy values.
        /** When true, auto-derived terms (service hostnames, module names) augment the deny-list. */
        var webEgressIncludeAutoDerivedTerms by property(true)
        /** Per-call timeout for the mandatory LLM egress screener. */
        var webEgressTimeoutMs by property(15_000)

        // fetch — response cache (added 2026-05-24)
        /** When true, an in-memory LRU+TTL cache short-circuits repeat fetches of the same URL. */
        var webFetchCacheEnabled by property(true)
        /** Cache entry lifetime in minutes. Matches Claude Code's default. */
        var webFetchCacheTtlMinutes by property(15)
        /** Maximum number of cached entries (LRU eviction beyond this). */
        var webFetchCacheMaxEntries by property(100)

        init {
            // Populate default whitelist on first instantiation. Persisted lists
            // round-trip independently — if the user clears the list it stays
            // empty (which the UI surfaces as "no MIME types accepted").
            if (imageMimeWhitelist.isEmpty()) {
                imageMimeWhitelist.addAll(
                    listOf("image/png", "image/jpeg", "image/webp")
                )
            }
            // Populate quick-clipboard chip defaults on first instantiation.
            if (quickClipboardChips.isEmpty()) {
                quickClipboardChips.addAll(
                    listOf(
                        "docker.tag", "docker.tagsJson", "pr.url", "build.url",
                        "automation.url", "ticket.id", "ai.changeSummary", "ai.ticketSummary"
                    )
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

/**
 * Returns the effective accept-window duration in milliseconds, clamping
 * [PluginSettings.State.delegationAcceptWindowSeconds] to the range [10, 59] seconds.
 *
 * **Why 59 s, not 600 s?**
 * The UI allows up to 600 s as a convenient operator range, but the runtime
 * MUST stay strictly below IDE-A's `connectAndAwaitAccept` acceptTimeoutMillis (60 000 ms).
 * A value ≥ 60 s would mean IDE-B's reply could arrive after IDE-A has already timed out
 * and torn down the accept channel. The ceiling is therefore silently clamped to 59 s.
 *
 * **Why 10 s minimum?**
 * Anything below 10 s is too short for a human to read the prompt and click Start in
 * practice; treat pathologically small values (incl. 0, negatives) as misconfiguration
 * and raise to the 10 s floor.
 */
fun PluginSettings.State.effectiveAcceptWindowMs(): Long {
    val clamped = delegationAcceptWindowSeconds.coerceIn(10, 59)
    return clamped * 1_000L
}

// ── Web tools accessors ──────────────────────────────────────────────────────

private val webAllowlistLog = com.intellij.openapi.diagnostic.Logger.getInstance("WebAllowlist")

/**
 * Deserializes the stored JSON into a typed list of [DomainAllowlistEntry].
 *
 * Returns an empty list on parse error or blank/missing JSON. I7 fix: silent-empty
 * return is intentional (throwing would break the agent loop on a stale config rewrite),
 * but the parse failure is now logged at WARN so a corrupted allowlist is surfaced
 * rather than disappearing without trace.
 */
fun PluginSettings.getWebAllowlist(): List<com.workflow.orchestrator.core.model.web.DomainAllowlistEntry> {
    val json = state.webAllowlistJson?.ifBlank { "[]" } ?: "[]"
    return try {
        WebAllowlistJson.adapter.fromJson(json) ?: emptyList()
    } catch (e: Exception) {
        // I7: surface corruption rather than silently dropping entries. We still return
        // empty (fail-soft) so the agent loop keeps running, but the user sees a log line.
        webAllowlistLog.warn("Failed to parse webAllowlistJson; returning empty list. JSON length=${json.length}", e)
        emptyList()
    }
}

/**
 * Serializes [entries] and persists them as JSON in [PluginSettings.State.webAllowlistJson].
 */
fun PluginSettings.setWebAllowlist(entries: List<com.workflow.orchestrator.core.model.web.DomainAllowlistEntry>) {
    state.webAllowlistJson = WebAllowlistJson.adapter.toJson(entries)
}

// ── Web egress deny-list accessors (added 2026-05-24) ────────────────────────

private val webEgressLog = com.intellij.openapi.diagnostic.Logger.getInstance("WebEgressDenyList")

private val egressDenyListAdapter by lazy {
    com.squareup.moshi.Moshi.Builder().build().adapter<List<String>>(
        com.squareup.moshi.Types.newParameterizedType(List::class.java, String::class.java)
    )
}

/**
 * Returns the user-supplied egress deny-list. Empty list when JSON is blank or malformed
 * (fail-soft, matches `getWebAllowlist` behavior — corruption is logged at WARN so a
 * silently-empty list does not look like deliberate user clearing).
 */
fun PluginSettings.getWebEgressDenyList(): List<String> {
    val json = state.webEgressDenyListJson?.ifBlank { "[]" } ?: "[]"
    return try {
        egressDenyListAdapter.fromJson(json) ?: emptyList()
    } catch (e: Exception) {
        webEgressLog.warn("Failed to parse webEgressDenyListJson; returning empty list. JSON length=${json.length}", e)
        emptyList()
    }
}

fun PluginSettings.setWebEgressDenyList(entries: List<String>) {
    state.webEgressDenyListJson = egressDenyListAdapter.toJson(entries)
}

/**
 * Returns the configured sanitizer brain ID, or null when the field is blank
 * (meaning "use cheapest available").
 */
fun PluginSettings.resolveSanitizerBrainId(): String? =
    state.webSanitizerBrainId?.takeIf { it.isNotBlank() }

private object WebAllowlistJson {
    val adapter = com.squareup.moshi.Moshi.Builder()
        .add(com.workflow.orchestrator.core.util.InstantMoshiAdapter())
        .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
        .build()
        .adapter<List<com.workflow.orchestrator.core.model.web.DomainAllowlistEntry>>(
            com.squareup.moshi.Types.newParameterizedType(
                List::class.java,
                com.workflow.orchestrator.core.model.web.DomainAllowlistEntry::class.java,
            )
        )
}
