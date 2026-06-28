package com.workflow.orchestrator.core.config

/** A's no-op preset (order = MAX). Returns null for every field → A keeps its neutral defaults. */
class DefaultConfigPreset : ConfigPreset {
    override val order: Int get() = Int.MAX_VALUE
}
