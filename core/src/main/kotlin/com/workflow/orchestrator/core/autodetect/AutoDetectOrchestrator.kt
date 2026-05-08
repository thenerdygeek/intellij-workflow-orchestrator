package com.workflow.orchestrator.core.autodetect

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.bitbucket.BitbucketBranchClient
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.services.BambooService
import com.workflow.orchestrator.core.services.SonarKeyDetectorService
import com.workflow.orchestrator.core.services.SonarProjectPickerLauncher
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.settings.RepoConfig
import com.workflow.orchestrator.core.settings.RepoContextResolver
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordinates project-key auto-detection across modules. Each detector is
 * independently triggerable. Writes to settings via the fill-only-empty rule
 * so user-set values are never overwritten.
 *
 * Multi-repo aware: when PluginSettings.repos has entries, detection writes
 * to per-repo RepoConfig fields. When empty, writes to global PluginSettings.State.
 * After per-repo writes, the primary repo's values mirror to global state as
 * a fallback for code paths that lack repo context.
 *
 * Uses [SonarKeyDetectorService] (a core interface) to avoid a compile-time
 * dependency on the :sonar module. The concrete [com.workflow.orchestrator.sonar.service.SonarKeyDetector]
 * implements that interface and is registered in plugin.xml under the interface name.
 */
@Service(Service.Level.PROJECT)
class AutoDetectOrchestrator(private val project: Project, private val cs: CoroutineScope) : Disposable {

    private val log = logger<AutoDetectOrchestrator>()
    // cs is service-injected: supervisor-scoped, cancelled on project/plugin dispose
    internal val scope: CoroutineScope get() = cs
    private val firstSweepNotified = AtomicBoolean(false)
    private val detectionMutex = Mutex()
    // Per-session set of repo labels that have already been notified about a missing
    // Sonar key — prevents balloon spam across multiple `detectAll()` sweeps (startup,
    // BranchChanged, manual Auto-Detect button, etc.).
    private val sonarMissNotifiedRepos = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    init {
        scope.launch {
            project.getService(EventBus::class.java).events.collect { event ->
                if (event is WorkflowEvent.BranchChanged) {
                    log.info("[AutoDetect] BranchChanged → re-running branch-sensitive detectors")
                    detectionMutex.withLock {
                        val filled = mutableListOf<String>()
                        try {
                            detectFromBambooSpecs(filled)
                        } catch (e: Exception) {
                            log.warn("[AutoDetect] bamboo-specs detector failed on branch change", e)
                        }
                        try {
                            detectBambooPlan(filled)
                        } catch (e: Exception) {
                            log.warn("[AutoDetect] bamboo-plan detector failed on branch change", e)
                        }
                    }
                }
            }
        }
    }

    suspend fun detectAll(): AutoDetectResult = coroutineScope {
        detectionMutex.withLock {
            log.info("[AutoDetect] Running full sweep")
            val filled = mutableListOf<String>()
            val errors = mutableListOf<String>()

            runDetector("bamboo-specs", filled, errors) { detectFromBambooSpecs(it) }
            runDetector("git-remote", filled, errors) { detectGitDerivable(it) }
            runDetector("sonar-key", filled, errors) { detectSonarKey(it) }

            // Suspend detectors — call directly with try/catch
            try {
                detectBambooPlan(filled)
            } catch (e: Exception) {
                log.warn("[AutoDetect] Detector 'bamboo-plan' failed", e)
                errors.add("bamboo-plan: ${e.message ?: "unknown"}")
            }

            try {
                validateBitbucketRepos(filled)
            } catch (e: Exception) {
                log.warn("[AutoDetect] Detector 'bitbucket-validate' failed", e)
                errors.add("bitbucket-validate: ${e.message ?: "unknown"}")
            }

            // After per-repo writes, mirror primary repo values to global as fallback
            val settings = PluginSettings.getInstance(project)
            val primary = settings.getPrimaryRepo()
            if (primary != null) {
                runDetector("mirror-primary", filled, errors) {
                    mirrorPrimaryToGlobal(settings.state, primary, it)
                }
            }

            val result = AutoDetectResult(filledFields = filled, errors = errors)
            if (result.anyFilled && firstSweepNotified.compareAndSet(false, true)) {
                showNotification(result)
            }
            result
        }
    }

    /**
     * Runs a targeted partial detection (sub-sweep) under the same mutex as detectAll().
     * Used by AutoDetectFileListener for incremental re-detection on file changes
     * without the cost of a full sweep. The block receives a `filled` list which is
     * discarded after execution (partial sweeps don't surface results to the user).
     */
    suspend fun runPartial(block: suspend AutoDetectOrchestrator.(MutableList<String>) -> Unit) {
        detectionMutex.withLock {
            val filled = mutableListOf<String>()
            try {
                block(filled)
            } catch (e: Exception) {
                log.warn("[AutoDetect] Partial detection failed", e)
            }
        }
    }

    /**
     * Fire-and-forget version of detectAll() that uses the orchestrator's own
     * coroutine scope. Safe for callers like startup activities and file listeners
     * that cannot or should not await the result.
     */
    fun launchDetectAll(): Job = scope.launch { detectAll() }

    private inline fun runDetector(
        name: String,
        filled: MutableList<String>,
        errors: MutableList<String>,
        block: (MutableList<String>) -> Unit
    ) {
        try {
            block(filled)
        } catch (e: Exception) {
            log.warn("[AutoDetect] Detector '$name' failed", e)
            errors.add("$name: ${e.message ?: "unknown"}")
        }
    }

    fun detectFromBambooSpecs(filled: MutableList<String>) {
        val settings = PluginSettings.getInstance(project)
        val repos = settings.getRepos()
        log.info("[AutoDetect:BambooSpecs] Starting. Saved repos count=${repos.size}")
        if (repos.isEmpty()) {
            val basePath = project.basePath ?: run {
                log.warn("[AutoDetect:BambooSpecs] No repos and project.basePath is null — skipping")
                return
            }
            log.info("[AutoDetect:BambooSpecs] No saved repos — using project.basePath='$basePath'")
            val constants = BambooSpecsParser.parseConstants(Paths.get(basePath))
            if (constants.isEmpty()) {
                log.info("[AutoDetect:BambooSpecs] No constants found at project root — nothing to fill")
                return
            }
            log.info("[AutoDetect:BambooSpecs] Found ${constants.size} constants, applying to global state")
            applyBambooSpecsToState(settings.state, constants, "global", filled)
            return
        }
        for (repo in repos) {
            val rootPath = repo.localVcsRootPath?.takeIf { it.isNotBlank() }
            if (rootPath == null) {
                log.info("[AutoDetect:BambooSpecs] Repo '${repo.displayLabel}' has blank localVcsRootPath — skipping")
                continue
            }
            log.info("[AutoDetect:BambooSpecs] Repo '${repo.displayLabel}' rootPath='$rootPath'")
            val constants = BambooSpecsParser.parseConstants(Paths.get(rootPath))
            if (constants.isEmpty()) {
                log.info("[AutoDetect:BambooSpecs] No constants found for repo '${repo.displayLabel}'")
                continue
            }
            log.info("[AutoDetect:BambooSpecs] Found ${constants.size} constants for repo '${repo.displayLabel}', applying")
            applyBambooSpecsToRepo(repo, constants, filled)
        }
    }

    fun detectGitDerivable(filled: MutableList<String>) {
        // RepoContextResolver.autoDetectRepos() already populates RepoConfig
        // entries with bitbucketProjectKey + bitbucketRepoSlug from git remote.
        // Kept as a hook for the file listener wiring in Task 7.
    }

    fun detectSonarKey(filled: MutableList<String>) {
        val detector = project.getService(SonarKeyDetectorService::class.java) ?: return
        val settings = PluginSettings.getInstance(project)
        val repos = settings.getRepos()

        if (repos.isEmpty()) {
            val detected = detector.detect() ?: return
            val state = settings.state
            val updated = fillIfEmpty(state.sonarProjectKey, detected)
            if (updated != state.sonarProjectKey && !updated.isNullOrBlank()) {
                state.sonarProjectKey = updated
                filled += "global.sonarProjectKey"
            }
            return
        }

        // Repos with missing Sonar keys after all local tiers — used to fire a single
        // actionable notification per sweep (capped to the first unresolved repo).
        val missingKeyRepos = mutableListOf<RepoConfig>()

        for (repo in repos) {
            val rootPath = repo.localVcsRootPath?.takeIf { it.isNotBlank() } ?: continue
            val detected = detector.detectForPath(rootPath) ?: run {
                // Local tiers all returned null; track this repo for the miss notification.
                if (repo.sonarProjectKey.isNullOrBlank()) missingKeyRepos += repo
                return@run null
            }
            val updated = fillIfEmpty(repo.sonarProjectKey, detected)
            if (updated != repo.sonarProjectKey && !updated.isNullOrBlank()) {
                repo.sonarProjectKey = updated
                filled += "${repoLabel(repo)}.sonarProjectKey"
            }
        }

        // Notify for the first unresolved repo, but only if we haven't already notified
        // about it this session (the per-repo dedup set prevents balloon spam on
        // every BranchChanged / Auto-Detect / startup re-sweep).
        val firstMissing = missingKeyRepos.firstOrNull { sonarMissNotifiedRepos.add(repoLabel(it)) } ?: return
        showSonarNotDetectedNotification(firstMissing)
    }

    /**
     * Posts a single INFORMATION balloon when no local tier resolved the Sonar key for [repo].
     * The action opens Settings so the user can use the "Find" button, or they can click
     * "Search Sonar Server" to pick directly from the notification (if :sonar is loaded).
     */
    private fun showSonarNotDetectedNotification(repo: RepoConfig) {
        val label = repoLabel(repo)
        val msg = "Sonar key not auto-detected for repository '$label'. " +
                  "Open Settings to validate or search the server."
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("workflow.autodetect")
            .createNotification(msg, NotificationType.INFORMATION)
        notification.addAction(NotificationAction.createSimple("Open Repositories Settings") {
            ShowSettingsUtil.getInstance()
                .showSettingsDialog(project, "workflow.orchestrator.repositories")
            notification.expire()
        })
        notification.addAction(NotificationAction.createSimple("Search Sonar Server") {
            val launcher = SonarProjectPickerLauncher.getInstance()
            if (launcher == null) {
                ShowSettingsUtil.getInstance()
                    .showSettingsDialog(project, "workflow.orchestrator.repositories")
                notification.expire()
                return@createSimple
            }
            scope.launch {
                val picked = launcher.pick(project, repo.sonarProjectKey)
                if (!picked.isNullOrBlank()) {
                    repo.sonarProjectKey = picked
                    log.info("[AutoDetect] Sonar key set via notification picker for '$label': $picked")
                    // Pick is in-memory only until the user applies Settings. Nudge them
                    // explicitly so they don't think the picker silently failed when the
                    // key disappears on next IDE restart.
                    showSonarKeyStagedNotification(label, picked)
                }
                notification.expire()
            }
        })
        notification.notify(project)
    }

    /**
     * Follow-up balloon shown after the user picks a Sonar key from the picker action of
     * [showSonarNotDetectedNotification]. The pick is staged in memory only; the user must
     * open Settings and click Apply to persist. This nudge points them at the Settings page
     * directly instead of leaving them to wonder if the pick silently failed.
     */
    private fun showSonarKeyStagedNotification(label: String, picked: String) {
        val msg = "Sonar key '$picked' staged for repository '$label'. " +
                  "Open Settings and click Apply to save."
        val followup = NotificationGroupManager.getInstance()
            .getNotificationGroup("workflow.autodetect")
            .createNotification(msg, NotificationType.INFORMATION)
        followup.addAction(NotificationAction.createSimple("Open Settings") {
            ShowSettingsUtil.getInstance()
                .showSettingsDialog(project, "workflow.orchestrator.repositories")
            followup.expire()
        })
        followup.notify(project)
    }

    /**
     * Validates parsed Bitbucket project/slug values against the server and persists the
     * server-canonical HTTPS clone URL on each [RepoConfig]. Runs after [detectGitDerivable]
     * has populated [RepoConfig.bitbucketProjectKey] and [RepoConfig.bitbucketRepoSlug].
     *
     * Silently skips repos with missing keys, and skips the entire sweep if Bitbucket is
     * not configured (blank base URL).
     */
    suspend fun validateBitbucketRepos(filled: MutableList<String>, targetRepos: List<RepoConfig>? = null) {
        val client = BitbucketBranchClient.fromConfiguredSettings() ?: run {
            log.info("[AutoDetect] Bitbucket not configured — skipping canonical URL validation")
            return
        }
        val resolver = RepoContextResolver.getInstance(project)
        val repos = targetRepos ?: PluginSettings.getInstance(project).getRepos()
        if (repos.isEmpty()) return

        for (repo in repos) {
            if (repo.bitbucketProjectKey.isNullOrBlank() || repo.bitbucketRepoSlug.isNullOrBlank()) continue
            if (!repo.canonicalCloneUrl.isNullOrBlank()) continue  // already validated
            resolver.validateAndPersistCanonicalUrl(repo, client)
            if (!repo.canonicalCloneUrl.isNullOrBlank()) {
                filled += "${repoLabel(repo)}.canonicalCloneUrl"
            }
        }
    }

    suspend fun detectBambooPlan(filled: MutableList<String>) {
        val settings = PluginSettings.getInstance(project)
        val repos = settings.getRepos()
        val gitRepos = GitRepositoryManager.getInstance(project).repositories
        val bambooService = project.getService(BambooService::class.java) ?: return

        if (repos.isEmpty()) {
            val state = settings.state
            if (!state.bambooPlanKey.isNullOrBlank()) return
            val gitRepo = gitRepos.firstOrNull() ?: return
            val remoteUrl = gitRepo.remotes.firstOrNull()?.firstUrl ?: return
            val repoRoot = Paths.get(gitRepo.root.path)
            val branchName = gitRepo.currentBranch?.name
            val result = bambooService.autoDetectPlan(repoRoot, remoteUrl, branchName)
            if (result.isError || result.data.isNullOrBlank()) return
            state.bambooPlanKey = result.data
            filled += "global.bambooPlanKey"
            return
        }

        for (gitRepo in gitRepos) {
            val rootPath = gitRepo.root.path
            val repoConfig = settings.getRepoForPath(rootPath) ?: continue
            if (!repoConfig.bambooPlanKey.isNullOrBlank()) continue
            val remoteUrl = gitRepo.remotes.firstOrNull()?.firstUrl ?: continue
            val repoRoot = Paths.get(gitRepo.root.path)
            val branchName = gitRepo.currentBranch?.name
            val result = bambooService.autoDetectPlan(repoRoot, remoteUrl, branchName)
            if (result.isError || result.data.isNullOrBlank()) continue
            repoConfig.bambooPlanKey = result.data
            filled += "${repoLabel(repoConfig)}.bambooPlanKey"
        }
    }

    private fun showNotification(result: AutoDetectResult) {
        val msg = "Auto-detected project keys: ${result.filledFields.joinToString(", ")}. " +
                  "Review in Settings → Tools → Workflow Orchestrator → Repositories."
        NotificationGroupManager.getInstance()
            .getNotificationGroup("workflow.autodetect")
            .createNotification(msg, NotificationType.INFORMATION)
            .notify(project)
    }

    override fun dispose() {
        // cs is cancelled automatically by the IntelliJ service lifecycle
    }

    companion object {
        /** Returns `detected` only when `current` is null/blank AND `detected` is non-blank. */
        fun fillIfEmpty(current: String?, detected: String?): String? =
            if (current.isNullOrBlank() && !detected.isNullOrBlank()) detected else current

        /** Returns a human-readable label for [repo]: name, slug, or "unnamed". */
        internal fun repoLabel(repo: RepoConfig): String =
            repo.name?.takeIf { it.isNotBlank() }
                ?: repo.bitbucketRepoSlug?.takeIf { it.isNotBlank() }
                ?: "unnamed"

        private val log = Logger.getInstance(AutoDetectOrchestrator::class.java)

        /**
         * Builds the full Bamboo plan key from PROJECT_KEY and PLAN_KEY constants.
         * Bamboo API requires "PROJECT-PLAN" format (e.g., "ACME-DEMOAPPSERVICE").
         * If PLAN_KEY already contains a hyphen (already full format), uses it as-is.
         */
        internal fun buildFullPlanKey(constants: Map<String, String>): String? {
            val planKey = constants["PLAN_KEY"]?.takeIf { it.isNotBlank() } ?: return null
            if ('-' in planKey) return planKey // already full format
            val projectKey = constants["PROJECT_KEY"]?.takeIf { it.isNotBlank() } ?: return planKey
            return "$projectKey-$planKey"
        }

        /**
         * Like [fillIfEmpty] but also overwrites if the current value is just the short
         * PLAN_KEY (no hyphen) and the new value is the full PROJECT-PLAN format.
         * This corrects stale values from earlier versions that stored only PLAN_KEY.
         */
        internal fun fillOrUpgradePlanKey(current: String?, fullKey: String?): String? {
            if (fullKey.isNullOrBlank()) return current
            if (current.isNullOrBlank()) return fullKey
            // Overwrite if current is the short key and fullKey is the proper PROJECT-PLAN format
            if ('-' !in current && '-' in fullKey && fullKey.endsWith(current)) return fullKey
            return current
        }

        /** Visible for testing. Writes constants into a state object via fill-only-empty. */
        internal fun applyBambooSpecsToState(
            state: PluginSettings.State,
            constants: Map<String, String>,
            label: String,
            filled: MutableList<String>
        ) {
            val fullPlanKey = buildFullPlanKey(constants)
            log.info("[AutoDetect:Apply] Applying to $label state. DOCKER_TAG_NAME='${constants["DOCKER_TAG_NAME"]}', fullPlanKey='$fullPlanKey' (PROJECT_KEY='${constants["PROJECT_KEY"]}', PLAN_KEY='${constants["PLAN_KEY"]}')")
            val newDocker = fillIfEmpty(state.dockerTagKey, constants["DOCKER_TAG_NAME"])
            if (newDocker != state.dockerTagKey && !newDocker.isNullOrBlank()) {
                state.dockerTagKey = newDocker
                filled += "$label.dockerTagKey"
                log.info("[AutoDetect:Apply]   SET $label.dockerTagKey = '$newDocker'")
            }
            val newPlan = fillOrUpgradePlanKey(state.bambooPlanKey, fullPlanKey)
            if (newPlan != state.bambooPlanKey && !newPlan.isNullOrBlank()) {
                state.bambooPlanKey = newPlan
                filled += "$label.bambooPlanKey"
                log.info("[AutoDetect:Apply]   SET $label.bambooPlanKey = '$newPlan'")
            }
        }

        /** Visible for testing. Writes constants into a repo via fill-only-empty. */
        internal fun applyBambooSpecsToRepo(
            repo: RepoConfig,
            constants: Map<String, String>,
            filled: MutableList<String>
        ) {
            val label = repoLabel(repo)
            val fullPlanKey = buildFullPlanKey(constants)
            log.info("[AutoDetect:Apply] Applying to repo '$label'. DOCKER_TAG_NAME='${constants["DOCKER_TAG_NAME"]}', fullPlanKey='$fullPlanKey' (PROJECT_KEY='${constants["PROJECT_KEY"]}', PLAN_KEY='${constants["PLAN_KEY"]}')")
            val newDocker = fillIfEmpty(repo.dockerTagKey, constants["DOCKER_TAG_NAME"])
            if (newDocker != repo.dockerTagKey && !newDocker.isNullOrBlank()) {
                repo.dockerTagKey = newDocker
                filled += "$label.dockerTagKey"
                log.info("[AutoDetect:Apply]   SET $label.dockerTagKey = '$newDocker'")
            }
            val newPlan = fillOrUpgradePlanKey(repo.bambooPlanKey, fullPlanKey)
            if (newPlan != repo.bambooPlanKey && !newPlan.isNullOrBlank()) {
                repo.bambooPlanKey = newPlan
                filled += "$label.bambooPlanKey"
                log.info("[AutoDetect:Apply]   SET $label.bambooPlanKey = '$newPlan'")
            }
        }

        /** Visible for testing. Mirrors primary repo's values to global state via fill-only-empty. */
        internal fun mirrorPrimaryToGlobal(
            state: PluginSettings.State,
            primary: RepoConfig,
            filled: MutableList<String>
        ) {
            val newSonar = fillIfEmpty(state.sonarProjectKey, primary.sonarProjectKey)
            if (newSonar != state.sonarProjectKey && !newSonar.isNullOrBlank()) {
                state.sonarProjectKey = newSonar
                filled += "global.sonarProjectKey"
            }
            val newPlan = fillIfEmpty(state.bambooPlanKey, primary.bambooPlanKey)
            if (newPlan != state.bambooPlanKey && !newPlan.isNullOrBlank()) {
                state.bambooPlanKey = newPlan
                filled += "global.bambooPlanKey"
            }
            val newDocker = fillIfEmpty(state.dockerTagKey, primary.dockerTagKey)
            if (newDocker != state.dockerTagKey && !newDocker.isNullOrBlank()) {
                state.dockerTagKey = newDocker
                filled += "global.dockerTagKey"
            }
        }
    }
}
