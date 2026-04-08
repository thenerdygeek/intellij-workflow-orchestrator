package com.workflow.orchestrator.core.autodetect

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.services.BambooService
import com.workflow.orchestrator.core.services.SonarKeyDetectorService
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.settings.RepoConfig
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
class AutoDetectOrchestrator(private val project: Project) : Disposable {

    private val log = logger<AutoDetectOrchestrator>()
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val firstSweepNotified = AtomicBoolean(false)
    private val detectionMutex = Mutex()

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

            // Suspend detector — call directly with try/catch
            try {
                detectBambooPlan(filled)
            } catch (e: Exception) {
                log.warn("[AutoDetect] Detector 'bamboo-plan' failed", e)
                errors.add("bamboo-plan: ${e.message ?: "unknown"}")
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
        if (repos.isEmpty()) {
            val basePath = project.basePath ?: return
            val constants = BambooSpecsParser.parseConstants(Paths.get(basePath))
            if (constants.isEmpty()) return
            applyBambooSpecsToState(settings.state, constants, "global", filled)
            return
        }
        for (repo in repos) {
            val rootPath = repo.localVcsRootPath?.takeIf { it.isNotBlank() } ?: continue
            val constants = BambooSpecsParser.parseConstants(Paths.get(rootPath))
            if (constants.isEmpty()) continue
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

        for (repo in repos) {
            val rootPath = repo.localVcsRootPath?.takeIf { it.isNotBlank() } ?: continue
            val detected = detector.detectForPath(rootPath) ?: continue
            val updated = fillIfEmpty(repo.sonarProjectKey, detected)
            if (updated != repo.sonarProjectKey && !updated.isNullOrBlank()) {
                repo.sonarProjectKey = updated
                filled += "${repoLabel(repo)}.sonarProjectKey"
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
            val remoteUrl = gitRepos.firstOrNull()?.remotes?.firstOrNull()?.firstUrl ?: return
            val result = bambooService.autoDetectPlan(remoteUrl)
            if (result.isError || result.data.isBlank()) return
            state.bambooPlanKey = result.data
            filled += "global.bambooPlanKey"
            return
        }

        for (gitRepo in gitRepos) {
            val rootPath = gitRepo.root.path
            val repoConfig = settings.getRepoForPath(rootPath) ?: continue
            if (!repoConfig.bambooPlanKey.isNullOrBlank()) continue
            val remoteUrl = gitRepo.remotes.firstOrNull()?.firstUrl ?: continue
            val result = bambooService.autoDetectPlan(remoteUrl)
            if (result.isError || result.data.isBlank()) continue
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
        scope.cancel()
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

        /** Visible for testing. Writes constants into a state object via fill-only-empty. */
        internal fun applyBambooSpecsToState(
            state: PluginSettings.State,
            constants: Map<String, String>,
            label: String,
            filled: MutableList<String>
        ) {
            val newDocker = fillIfEmpty(state.dockerTagKey, constants["DOCKER_TAG_NAME"])
            if (newDocker != state.dockerTagKey && !newDocker.isNullOrBlank()) {
                state.dockerTagKey = newDocker
                filled += "$label.dockerTagKey"
            }
            val newPlan = fillIfEmpty(state.bambooPlanKey, constants["PLAN_KEY"])
            if (newPlan != state.bambooPlanKey && !newPlan.isNullOrBlank()) {
                state.bambooPlanKey = newPlan
                filled += "$label.bambooPlanKey"
            }
        }

        /** Visible for testing. Writes constants into a repo via fill-only-empty. */
        internal fun applyBambooSpecsToRepo(
            repo: RepoConfig,
            constants: Map<String, String>,
            filled: MutableList<String>
        ) {
            val label = repoLabel(repo)
            val newDocker = fillIfEmpty(repo.dockerTagKey, constants["DOCKER_TAG_NAME"])
            if (newDocker != repo.dockerTagKey && !newDocker.isNullOrBlank()) {
                repo.dockerTagKey = newDocker
                filled += "$label.dockerTagKey"
            }
            val newPlan = fillIfEmpty(repo.bambooPlanKey, constants["PLAN_KEY"])
            if (newPlan != repo.bambooPlanKey && !newPlan.isNullOrBlank()) {
                repo.bambooPlanKey = newPlan
                filled += "$label.bambooPlanKey"
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
