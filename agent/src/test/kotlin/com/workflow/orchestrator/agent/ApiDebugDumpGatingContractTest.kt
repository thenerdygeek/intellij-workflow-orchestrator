package com.workflow.orchestrator.agent

import org.junit.jupiter.api.Test

/**
 * Pins the wiring contract that the api-debug request/response dumps are gated behind
 * [com.workflow.orchestrator.agent.settings.AgentSettings.State.writeApiDebugDumps] (default OFF).
 *
 * AgentService is not unit-instantiable, so these are source-text contracts — the same shape as
 * [AgentServiceSpawnWiringTest]. The gate lives in AgentService (the one place that reads the
 * setting): a single nullable `apiDebugDir` is computed and threaded to every api-debug consumer
 * (initial brain, recycled brain, and the sub-agent path). It deliberately does NOT live in
 * SpawnAgentTool — coupling that hot path to a project service regressed 18 spawn tests.
 * If the gate is removed (regressing to always-on disk dumps on every LLM call) these fail.
 */
class ApiDebugDumpGatingContractTest {

    private val agentServiceSrc by lazy {
        java.io.File("src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt").readText()
    }

    @Test
    fun `AgentService computes apiDebugDir gated on writeApiDebugDumps`() {
        assert("writeApiDebugDumps" in agentServiceSrc) {
            "AgentService must read writeApiDebugDumps to decide whether to write api-debug dumps."
        }
        // apiDebugDir is null unless the setting is on (sessionDebugDir.takeIf { ...writeApiDebugDumps }).
        val gated = Regex(
            """apiDebugDir[\s\S]{0,120}?takeIf\s*\{[\s\S]{0,80}?writeApiDebugDumps"""
        ).containsMatchIn(agentServiceSrc)
        assert(gated) {
            "AgentService must compute `apiDebugDir = sessionDebugDir.takeIf { ...writeApiDebugDumps }` " +
                "so a disabled setting yields null (no dumps)."
        }
    }

    @Test
    fun `the gated apiDebugDir is what reaches the brain and sub-agents`() {
        // The brain must receive the gated value, not the raw sessionDebugDir.
        assert("setApiDebugDir(apiDebugDir)" in agentServiceSrc) {
            "rawBrain/recycled brain must call setApiDebugDir(apiDebugDir) — the gated (nullable) dir."
        }
        // The sub-agent dump dir must also be the gated value (null when off → subagentDebugDir no-ops).
        assert("spawnAgentTool.sessionDebugDir = apiDebugDir" in agentServiceSrc) {
            "SpawnAgentTool.sessionDebugDir must be assigned the gated apiDebugDir, not sessionDebugDir."
        }
    }

    @Test
    fun `SpawnAgentTool stays decoupled from the setting`() {
        val spawnSrc = java.io.File(
            "src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt"
        ).readText()
        // The gate is upstream in AgentService; SpawnAgentTool must NOT reach into AgentSettings on
        // its hot path (doing so broke 18 spawn tests that use a bare mock Project).
        assert("writeApiDebugDumps" !in spawnSrc) {
            "SpawnAgentTool must not read writeApiDebugDumps — the api-debug gate is AgentService's job. " +
                "sessionDebugDir being null is the only signal it needs."
        }
    }
}
