package com.workflow.orchestrator.core.autodetect

/**
 * Summary of an auto-detect sweep. Each list contains the human-readable names
 * of fields that were filled (i.e. were blank before, non-blank after).
 */
data class AutoDetectResult(
    val filledFields: List<String> = emptyList(),
    val errors: List<String> = emptyList()
) {
    val anyFilled: Boolean get() = filledFields.isNotEmpty()
}
