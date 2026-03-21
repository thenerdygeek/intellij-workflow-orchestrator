package com.workflow.orchestrator.core.toolwindow

/**
 * Interface for tab panels that support incremental refresh
 * without full rebuild.
 */
interface Refreshable {
    fun refresh()
}
