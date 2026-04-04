package com.workflow.orchestrator.agent.loop

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Parses free-text LLM plan output into structured JSON for the JCEF plan card UI.
 *
 * The LLM's plan_mode_respond tool returns markdown with numbered steps, bullet points,
 * or headers. This parser extracts those into structured steps with IDs for the React
 * webview to render with per-step comment buttons.
 *
 * Handles:
 * - Numbered lists: "1. Do something", "2. Do another"
 * - Markdown headers: "### Step 1: Do something"
 * - Bullet points as fallback: "- Do something"
 * - Plain text as single step
 */
object PlanParser {

    @Serializable
    data class PlanStep(
        val id: String,
        val title: String,
        val description: String = "",
        val status: String = "pending"
    )

    @Serializable
    data class PlanJson(
        val summary: String,
        val steps: List<PlanStep>
    )

    private val json = Json { prettyPrint = false; encodeDefaults = true }

    /** Numbered list: "1. Title" or "1) Title" with optional bold markers */
    private val NUMBERED_PATTERN = Regex("""^(\d+)[.)]\s+(?:\*\*)?(.+?)(?:\*\*)?$""")

    /** Markdown header: "### Title" or "## Step N: Title" */
    private val HEADER_PATTERN = Regex("""^#{1,4}\s+(?:Step\s+\d+[:.]\s*)?(.+)$""")

    /** Bullet point: "- Title" or "* Title" */
    private val BULLET_PATTERN = Regex("""^[-*]\s+(?:\*\*)?(.+?)(?:\*\*)?$""")

    /**
     * Parse a free-text plan into structured JSON.
     *
     * @param planText the raw markdown plan from the LLM
     * @return JSON string: {"summary": "...", "steps": [{"id": "1", "title": "...", ...}]}
     */
    fun parseToJson(planText: String): String {
        val steps = parse(planText)
        val summary = extractSummary(planText, steps)
        val planJson = PlanJson(summary = summary, steps = steps)
        return json.encodeToString(planJson)
    }

    /**
     * Parse plan text into a list of steps.
     * Tries numbered lists first, then headers, then bullets, then single-step fallback.
     */
    internal fun parse(planText: String): List<PlanStep> {
        val lines = planText.lines()

        // Try numbered list parsing first (most common LLM output)
        val numberedSteps = parseNumbered(lines)
        if (numberedSteps.isNotEmpty()) return numberedSteps

        // Try header-based parsing
        val headerSteps = parseHeaders(lines)
        if (headerSteps.isNotEmpty()) return headerSteps

        // Try bullet points
        val bulletSteps = parseBullets(lines)
        if (bulletSteps.isNotEmpty()) return bulletSteps

        // Fallback: entire text as single step
        return listOf(
            PlanStep(
                id = "1",
                title = planText.lines().firstOrNull()?.take(120) ?: "Plan",
                description = planText
            )
        )
    }

    private fun parseNumbered(lines: List<String>): List<PlanStep> {
        val steps = mutableListOf<PlanStep>()
        var currentTitle: String? = null
        var currentId = 0
        val descriptionLines = mutableListOf<String>()

        for (line in lines) {
            val match = NUMBERED_PATTERN.find(line.trim())
            if (match != null) {
                // Flush previous step
                if (currentTitle != null) {
                    steps.add(PlanStep(
                        id = currentId.toString(),
                        title = currentTitle,
                        description = descriptionLines.joinToString("\n").trim()
                    ))
                    descriptionLines.clear()
                }
                currentId = match.groupValues[1].toInt()
                currentTitle = match.groupValues[2].trim()
            } else if (currentTitle != null && line.isNotBlank()) {
                descriptionLines.add(line.trim())
            }
        }

        // Flush last step
        if (currentTitle != null) {
            steps.add(PlanStep(
                id = currentId.toString(),
                title = currentTitle,
                description = descriptionLines.joinToString("\n").trim()
            ))
        }

        return steps
    }

    private fun parseHeaders(lines: List<String>): List<PlanStep> {
        val steps = mutableListOf<PlanStep>()
        var currentTitle: String? = null
        var stepCounter = 0
        val descriptionLines = mutableListOf<String>()

        for (line in lines) {
            val match = HEADER_PATTERN.find(line.trim())
            if (match != null) {
                // Flush previous step
                if (currentTitle != null) {
                    steps.add(PlanStep(
                        id = stepCounter.toString(),
                        title = currentTitle,
                        description = descriptionLines.joinToString("\n").trim()
                    ))
                    descriptionLines.clear()
                }
                stepCounter++
                currentTitle = match.groupValues[1].trim()
            } else if (currentTitle != null && line.isNotBlank()) {
                descriptionLines.add(line.trim())
            }
        }

        // Flush last step
        if (currentTitle != null) {
            steps.add(PlanStep(
                id = stepCounter.toString(),
                title = currentTitle,
                description = descriptionLines.joinToString("\n").trim()
            ))
        }

        return steps
    }

    private fun parseBullets(lines: List<String>): List<PlanStep> {
        val steps = mutableListOf<PlanStep>()
        var stepCounter = 0

        for (line in lines) {
            val match = BULLET_PATTERN.find(line.trim())
            if (match != null) {
                stepCounter++
                steps.add(PlanStep(
                    id = stepCounter.toString(),
                    title = match.groupValues[1].trim()
                ))
            }
        }

        return steps
    }

    /**
     * Extract a summary line from the plan text.
     * Looks for text before the first step, or generates one from step count.
     */
    private fun extractSummary(planText: String, steps: List<PlanStep>): String {
        // Look for a summary line before the first numbered/header/bullet step
        val firstStepLine = planText.lines().indexOfFirst { line ->
            val trimmed = line.trim()
            NUMBERED_PATTERN.matches(trimmed) ||
                HEADER_PATTERN.matches(trimmed) ||
                BULLET_PATTERN.matches(trimmed)
        }

        if (firstStepLine > 0) {
            val preamble = planText.lines().take(firstStepLine)
                .joinToString(" ") { it.trim() }
                .trim()
            if (preamble.isNotBlank() && preamble.length <= 300) {
                return preamble
            }
        }

        return "Plan with ${steps.size} step${if (steps.size != 1) "s" else ""}"
    }
}
