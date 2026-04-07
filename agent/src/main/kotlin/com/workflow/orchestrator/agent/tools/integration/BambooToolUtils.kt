package com.workflow.orchestrator.agent.tools.integration

import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Shared helpers for the Bamboo sub-tools (BambooBuildsTool, BambooPlansTool).
 */
internal object BambooToolUtils {

    /**
     * Result of parsing the `variables` JSON parameter.
     * Either a successfully parsed map or an error ToolResult to return to the caller.
     */
    sealed class VariablesParseResult {
        data class Success(val variables: Map<String, String>) : VariablesParseResult()
        data class Failure(val error: ToolResult) : VariablesParseResult()
    }

    /**
     * Parses the `variables` JSON parameter used by `trigger_build` and `trigger_stage`.
     *
     * Behaviour is intentionally identical to the previously duplicated inline block:
     * - `null` or blank → empty map
     * - Valid JSON object → map of string→string (each value read via `jsonPrimitive.content`)
     * - Invalid JSON / wrong shape → [VariablesParseResult.Failure] with an error ToolResult
     *   matching the original message verbatim
     */
    fun parseVariables(variablesStr: String?): VariablesParseResult {
        if (variablesStr.isNullOrBlank()) return VariablesParseResult.Success(emptyMap())
        return try {
            val obj = Json.parseToJsonElement(variablesStr).jsonObject
            VariablesParseResult.Success(obj.mapValues { it.value.jsonPrimitive.content })
        } catch (_: Exception) {
            VariablesParseResult.Failure(
                ToolResult(
                    "Invalid variables JSON: '$variablesStr'. Expected format: {\"key\":\"value\"}",
                    "Invalid variables",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
            )
        }
    }
}
