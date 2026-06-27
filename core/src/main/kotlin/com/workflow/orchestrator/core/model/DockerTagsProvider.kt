package com.workflow.orchestrator.core.model

/**
 * Interface for run configurations that provide Docker tag mappings.
 * Implemented by BambooBuildRunConfiguration in :bamboo module.
 * Consumed by :automation (Plugin B) at runtime via the parent classloader to avoid
 * cross-module dependencies (both depend only on :core).
 * Note: TagValidationBeforeRunProvider was removed; tag validation now happens in Bamboo.
 */
interface DockerTagsProvider {
    /** Returns the raw build variables string containing Docker tag config. */
    fun getDockerTagsJson(): String
}
