package com.workflow.orchestrator.agent.walkthrough

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** One step of a guided code tour. Lines are 1-based, inclusive. */
data class WalkthroughStep(
    val file: String,
    val startLine: Int,
    val endLine: Int,
    val title: String? = null,
    val bodyMarkdown: String,
)

/** Valid steps + itemized positional errors ("step 3: …") for the LLM to self-correct. */
data class StepsParse(val steps: List<WalkthroughStep>, val errors: List<String>)

/**
 * Parse the `steps` param. The XML-in-content tool path serializes every param as a
 * [JsonPrimitive] STRING (see BrainRouter / `parseModules` precedent), so the JSON
 * array arrives inside a string; a native [JsonArray] is accepted defensively.
 */
fun parseStepsJson(element: JsonElement?): StepsParse {
    val arrayHint = "steps must be a JSON array string like " +
        """[{"file":"path","start_line":1,"end_line":5,"body_md":"…"}]"""
    val arr: JsonArray = when (element) {
        null -> return StepsParse(emptyList(), listOf("steps parameter is missing"))
        is JsonArray -> element
        is JsonPrimitive -> {
            val s = element.content.trim()
            if (!s.startsWith("[")) return StepsParse(emptyList(), listOf(arrayHint))
            runCatching { Json.parseToJsonElement(s) as? JsonArray }.getOrNull()
                ?: return StepsParse(emptyList(), listOf("steps is not valid JSON — $arrayHint"))
        }
        else -> return StepsParse(emptyList(), listOf(arrayHint))
    }
    val steps = mutableListOf<WalkthroughStep>()
    val errors = mutableListOf<String>()
    arr.forEachIndexed { i, el ->
        val n = i + 1
        val obj = el as? JsonObject
            ?: run {
                errors += "step $n: not a JSON object"
                return@forEachIndexed
            }
        val file = (obj["file"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: run {
                errors += "step $n: missing required field 'file'"
                return@forEachIndexed
            }
        val start = (obj["start_line"] as? JsonPrimitive)?.intOrNull
            ?: run {
                errors += "step $n: missing or non-numeric 'start_line'"
                return@forEachIndexed
            }
        val end = (obj["end_line"] as? JsonPrimitive)?.intOrNull
            ?: run {
                errors += "step $n: missing or non-numeric 'end_line'"
                return@forEachIndexed
            }
        if (start < 1) {
            errors += "step $n: start_line must be >= 1 (got $start)"
            return@forEachIndexed
        }
        if (end < start) {
            errors += "step $n: end_line $end is before start_line $start"
            return@forEachIndexed
        }
        val body = (obj["body_md"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: run {
                errors += "step $n: missing or blank 'body_md'"
                return@forEachIndexed
            }
        val title = (obj["title"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        steps += WalkthroughStep(file, start, end, title, body)
    }
    return StepsParse(steps, errors)
}
