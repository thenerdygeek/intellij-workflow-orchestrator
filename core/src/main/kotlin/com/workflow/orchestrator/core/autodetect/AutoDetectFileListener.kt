package com.workflow.orchestrator.core.autodetect

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Listens for VFS changes to files that affect auto-detection results:
 *   - `.git/config`              → re-run git-derivable detection
 *   - `pom.xml`                  → re-run sonar key detection
 *   - `bamboo-specs/` Java files   → re-run bamboo-specs constant walk
 *
 * 500ms debounce per detector so a `git checkout` touching many files only
 * fires the walk once.
 */
class AutoDetectFileListener : BulkFileListener {

    private val log = logger<AutoDetectFileListener>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var bambooSpecsJob: Job? = null
    @Volatile private var pomJob: Job? = null
    @Volatile private var gitConfigJob: Job? = null

    override fun after(events: MutableList<out VFileEvent>) {
        if (events.isEmpty()) return

        var bambooSpecsTouched = false
        var pomTouched = false
        var gitConfigTouched = false

        for (event in events) {
            val path = event.path
            if (path.endsWith("/.git/config") || path.endsWith("\\.git\\config")) gitConfigTouched = true
            if (path.endsWith("/pom.xml") || path.endsWith("\\pom.xml")) pomTouched = true
            if ((path.contains("/bamboo-specs/") || path.contains("\\bamboo-specs\\")) && path.endsWith(".java")) {
                bambooSpecsTouched = true
            }
        }

        if (!bambooSpecsTouched && !pomTouched && !gitConfigTouched) return

        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            val orchestrator = project.getService(AutoDetectOrchestrator::class.java) ?: continue

            if (bambooSpecsTouched) {
                bambooSpecsJob = debounce(bambooSpecsJob) {
                    log.info("[AutoDetect:FileListener] bamboo-specs changed → re-running")
                    try {
                        orchestrator.detectFromBambooSpecs(mutableListOf())
                    } catch (e: Exception) {
                        log.warn("[AutoDetect:FileListener] bamboo-specs re-run failed", e)
                    }
                }
            }
            if (pomTouched) {
                pomJob = debounce(pomJob) {
                    log.info("[AutoDetect:FileListener] pom.xml changed → re-running sonar")
                    try {
                        orchestrator.detectSonarKey(mutableListOf())
                    } catch (e: Exception) {
                        log.warn("[AutoDetect:FileListener] sonar re-run failed", e)
                    }
                }
            }
            if (gitConfigTouched) {
                gitConfigJob = debounce(gitConfigJob) {
                    log.info("[AutoDetect:FileListener] .git/config changed → re-running git-derivable")
                    try {
                        orchestrator.detectGitDerivable(mutableListOf())
                    } catch (e: Exception) {
                        log.warn("[AutoDetect:FileListener] git-derivable re-run failed", e)
                    }
                }
            }
        }
    }

    private fun debounce(existing: Job?, block: suspend () -> Unit): Job {
        existing?.cancel()
        return scope.launch {
            delay(500)
            block()
        }
    }
}
