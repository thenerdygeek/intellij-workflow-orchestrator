package com.workflow.orchestrator.agent.tools.integration

import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Shared helpers for the Bitbucket sub-tools (PR, Review, Repo).
 */
object BitbucketToolUtils {

    fun parsePrId(params: JsonObject): Int? =
        params["pr_id"]?.jsonPrimitive?.content?.toIntOrNull()

    fun invalidPrId(): ToolResult = ToolResult(
        "Error: 'pr_id' must be a valid integer",
        "Error: invalid pr_id",
        ToolResult.ERROR_TOKEN_ESTIMATE,
        isError = true
    )

    fun missingParam(name: String): ToolResult = ToolValidation.missingParam(name)
}
