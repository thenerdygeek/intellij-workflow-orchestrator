package com.workflow.orchestrator.core.autodetect

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordinates project-key auto-detection across modules. Each detector is
 * independently triggerable. Writes to settings via the fill-only-empty rule
 * so user-set values are never overwritten.
 *
 * Triggers (wired in plugin.xml or in the activity / listeners):
 *   - Project open: WorkflowAutoDetectStartupActivity calls detectAll()
 *   - Branch change: subscribed to EventBus.BranchChanged in init
 *   - File changes: AutoDetectFileListener routes to specific detectors
 *   - Credentials updated: PluginSettings change listener calls detectBambooPlan()
 */
@Service(Service.Level.PROJECT)
class AutoDetectOrchestrator(private val project: Project) : Disposable {

    private val log = logger<AutoDetectOrchestrator>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val firstSweepNotified = AtomicBoolean(false)

    suspend fun detectAll(): AutoDetectResult {
        // TODO Task 5: wire detectors
        return AutoDetectResult()
    }

    override fun dispose() {
        scope.cancel()
    }

    companion object {
        /** Returns `detected` only when `current` is null/blank AND `detected` is non-blank. */
        fun fillIfEmpty(current: String?, detected: String?): String? =
            if (current.isNullOrBlank() && !detected.isNullOrBlank()) detected else current
    }
}
