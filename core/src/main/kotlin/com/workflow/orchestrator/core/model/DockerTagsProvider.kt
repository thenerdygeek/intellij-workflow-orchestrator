package com.workflow.orchestrator.core.model

/**
 * Interface for run configurations that provide Docker tag mappings.
 * Implemented by BambooBuildRunConfiguration in :bamboo module.
 * Used by TagValidationBeforeRunProvider in :automation module to avoid
 * cross-module dependencies (both depend only on :core).
 */
interface DockerTagsProvider {
    /** Returns the raw build variables string containing Docker tag config. */
    fun getDockerTagsJson(): String
}
