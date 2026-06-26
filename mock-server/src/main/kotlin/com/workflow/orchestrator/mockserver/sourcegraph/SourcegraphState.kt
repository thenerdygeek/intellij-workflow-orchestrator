package com.workflow.orchestrator.mockserver.sourcegraph

import com.workflow.orchestrator.mockserver.sourcegraph.scenario.CustomScenarioRequest
import com.workflow.orchestrator.mockserver.sourcegraph.scenario.CustomTurn
import com.workflow.orchestrator.mockserver.sourcegraph.scenario.ScenarioEngine
import com.workflow.orchestrator.mockserver.sourcegraph.scenario.ScenarioLibrary
import com.workflow.orchestrator.mockserver.sourcegraph.scenario.ScenarioState
import com.workflow.orchestrator.mockserver.sourcegraph.scenario.toScenario
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Holds the Sourcegraph mock's scenario engine + state. Unlike the Jira/Bamboo/Sonar states (which
 * are swapped wholesale on scenario load), this is mutated in place — the admin "scenario" action
 * only changes the default scenario name, and "reset" only clears the per-conversation turn indices.
 *
 * The admin helpers ([setDefaultScenario], [listScenarios], [resetTurnIndices], [registerCustomScenario],
 * [stateSummary]) are what `AdminRoutes` / the `startMockServer` admin machinery call — wired by
 * `MockServerMain` without editing `AdminRoutes` (see this module's return summary for the exact snippets).
 */
class SourcegraphState(
    val library: ScenarioLibrary = ScenarioLibrary(),
    defaultScenario: String = ScenarioLibrary.DEFAULT_SCENARIO,
) {
    val scenarioState = ScenarioState(defaultScenario)
    val engine = ScenarioEngine(library, scenarioState)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Set the default scenario served to untagged conversations. Returns false for unknown names. */
    fun setDefaultScenario(name: String): Boolean {
        if (!library.exists(name)) return false
        scenarioState.defaultScenario = name
        return true
    }

    /** All available scenario names (for the admin "scenarios" listing). */
    fun listScenarios(): List<String> = library.names()

    /** Clear every conversation's turn index so scenarios replay from the top. */
    fun resetTurnIndices() = scenarioState.reset()

    /** Outcome of a custom-scenario registration. */
    sealed interface RegisterResult {
        data class Ok(val name: String, val turnCount: Int) : RegisterResult
        data class Error(val message: String) : RegisterResult
    }

    /**
     * Parse a full `{name, turns}` body (see [CustomScenarioRequest]), register the scenario
     * (overwriting any same-name one), and activate it: it becomes the default AND all conversation
     * turn indices reset so a fresh run starts at turn 0. ANY tool name is accepted.
     */
    fun registerCustomScenario(requestJson: String): RegisterResult {
        val request = runCatching { json.decodeFromString<CustomScenarioRequest>(requestJson) }
            .getOrElse { return RegisterResult.Error("invalid scenario JSON: ${it.message}") }
        return registerCustomScenario(request)
    }

    /**
     * Convenience matching the suggested `(name, turnsJson)` signature, where [turnsJson] is the
     * JSON **array** of turns (not the whole envelope). Delegates to the body form.
     */
    fun registerCustomScenario(name: String, turnsJson: String): RegisterResult {
        val turns = runCatching { json.decodeFromString<List<CustomTurn>>(turnsJson) }
            .getOrElse { return RegisterResult.Error("invalid turns JSON: ${it.message}") }
        return registerCustomScenario(CustomScenarioRequest(name, turns))
    }

    private fun registerCustomScenario(request: CustomScenarioRequest): RegisterResult {
        if (request.name.isBlank()) return RegisterResult.Error("scenario name is required")
        if (request.turns.isEmpty()) return RegisterResult.Error("scenario must have at least one turn")
        val scenario = request.toScenario()
        library.register(scenario)               // overwrites a same-name scenario
        scenarioState.defaultScenario = scenario.name
        scenarioState.reset()                    // activate now: fresh conversations start at turn 0
        return RegisterResult.Ok(scenario.name, scenario.turns.size)
    }

    /** State dump for `GET /__admin/state`. */
    fun stateSummary(): JsonObject = buildJsonObject {
        put("defaultScenario", scenarioState.defaultScenario)
        put("activeConversations", scenarioState.conversationCount())
        putJsonArray("scenarios") { listScenarios().forEach { add(JsonPrimitive(it)) } }
    }
}
