package com.workflow.orchestrator.core.autodetect

/**
 * Core-side interface for Sonar project-key detection.
 * Implemented by [com.workflow.orchestrator.sonar.service.SonarKeyDetector] in :sonar.
 * Registered as a project service in plugin.xml so that AutoDetectOrchestrator
 * can call it from :core without a compile-time dependency on :sonar.
 */
interface SonarKeyDetectorService {
    /** Single-repo detection: returns key from the first Maven root, or null. */
    fun detect(): String?

    /** Multi-repo detection: returns key for the Maven root at [repoRootPath], or null. */
    fun detectForPath(repoRootPath: String): String?
}
