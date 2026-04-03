package com.workflow.orchestrator.agent.runtime

/**
 * Shared verification tool names used by [SelfCorrectionGate] and [BackpressureGate].
 *
 * Extracted to a single constant so both gates stay in sync when new
 * verification tools are added (e.g., a future `lint` or `typecheck` tool).
 */
object VerificationConstants {
    val VERIFICATION_TOOLS = setOf("diagnostics", "runtime_config", "runtime_exec", "run_inspections")
}
