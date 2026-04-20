package com.workflow.orchestrator.core.insights

import com.workflow.orchestrator.core.ai.SourcegraphChatClient
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.insights.SessionRecord
import com.workflow.orchestrator.core.services.SessionHistoryReader
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Manual prototype: hits a real Sourcegraph endpoint with live session data.
 * Not runnable in CI — `@Disabled` by default. Remove the annotation locally
 * when working on the insights prompt; requires `SOURCEGRAPH_URL` and
 * `SOURCEGRAPH_TOKEN` env vars and actual session history on disk.
 */
@Disabled("Manual prototype — requires SOURCEGRAPH_URL/TOKEN and real session data; not a unit test")
class InsightsPromptPrototype {

    @Test
    fun run() = runBlocking {
        val url = System.getenv("SOURCEGRAPH_URL") ?: error("Set SOURCEGRAPH_URL")
        val token = System.getenv("SOURCEGRAPH_TOKEN") ?: error("Set SOURCEGRAPH_TOKEN")
        val model = System.getenv("SOURCEGRAPH_MODEL") ?: "anthropic::2024-10-22::claude-sonnet-4-latest"
        val projectFilter = System.getenv("WORKFLOW_PROJECT") // optional prefix match

        val workflowRoot = File(System.getProperty("user.home"), ".workflow-orchestrator")
        val baseDir = findAgentBaseDir(workflowRoot, projectFilter)
            ?: error("No sessions.json found under ${workflowRoot.absolutePath}. Set WORKFLOW_PROJECT if needed.")

        println("Reading sessions from: ${baseDir.absolutePath}")

        val reader = FileSessionHistoryReader()
        val collector = ReportDataCollector(reader, baseDir)
        val windowEndMs = System.currentTimeMillis()
        val windowStartMs = windowEndMs - 7L * 24 * 60 * 60 * 1000
        var mechanical = collector.collect(windowStartMs, windowEndMs)

        if (mechanical.sessionCount == 0) {
            // Timestamps may be outside the default 7-day window — fall back to all sessions
            println("No sessions in last 7 days, falling back to all sessions...")
            mechanical = collector.collect(0L, Long.MAX_VALUE)
        }

        println("Sessions in window: ${mechanical.sessionCount}")
        if (mechanical.sessionCount == 0) {
            println("No sessions found at all. Exiting.")
            return@runBlocking
        }

        val prettyJson = Json { prettyPrint = true; encodeDefaults = true }
        val compactMechanical = buildCompactMechanical(mechanical, prettyJson)
        val topDigests = prettyJson.encodeToString(mechanical.sessionDigests.take(20))

        val client = SourcegraphChatClient(baseUrl = url, tokenProvider = { token }, model = model)

        // Call 1: Classifier
        val prompt1 = buildClassifierPrompt(compactMechanical, prettyJson.encodeToString(mechanical.sessionDigests))
        println("Running Call 1 (classifier)...")
        val call1 = runLlmCall(client, prompt1, temperature = 0.0)
        println("Call 1 done (${call1.length} chars)")

        // Call 2: Narrative
        val prompt2 = buildNarrativePrompt(compactMechanical, call1)
        println("Running Call 2 (narrative)...")
        val call2 = runLlmCall(client, prompt2, temperature = 0.3)
        println("Call 2 done (${call2.length} chars)")

        // Call 3: Wins + Friction + Fun
        val prompt3 = buildWinsFrictionPrompt(compactMechanical, topDigests, call1)
        println("Running Call 3 (wins+friction+fun)...")
        val call3 = runLlmCall(client, prompt3, temperature = 0.3)
        println("Call 3 done (${call3.length} chars)")

        // Call 4: Suggestions
        val prompt4 = buildSuggestionsPrompt(compactMechanical, call1)
        println("Running Call 4 (suggestions)...")
        val call4 = runLlmCall(client, prompt4, temperature = 0.3)
        println("Call 4 done (${call4.length} chars)")

        val ts = System.currentTimeMillis()

        // Write prompts
        val promptFile = File("/tmp/insights-prompt-$ts.txt")
        promptFile.writeText(buildString {
            appendLine("=== CALL 1: Classifier ==="); appendLine(prompt1)
            appendLine("\n=== CALL 2: Narrative ==="); appendLine(prompt2)
            appendLine("\n=== CALL 3: Wins+Friction+Fun ==="); appendLine(prompt3)
            appendLine("\n=== CALL 4: Suggestions ==="); appendLine(prompt4)
        })

        // Write output — build JSON manually to avoid @Serializable requirement in test source set
        val outputJson = buildJsonObject {
            put("mechanicalStats", buildJsonObject {
                put("sessionCount", mechanical.sessionCount.toString())
                put("totalTokensIn", mechanical.totalTokensIn.toString())
                put("totalTokensOut", mechanical.totalTokensOut.toString())
                put("totalCostUsd", "%.4f".format(mechanical.totalCostUsd))
                put("distinctDays", mechanical.distinctDays.toString())
                put("models", mechanical.modelUsage.keys.joinToString(", "))
            })
            put("call1_classifier", call1)
            put("call2_narrative", call2)
            put("call3_winsFriction", call3)
            put("call4_suggestions", call4)
        }
        val outputFile = File("/tmp/insights-proto-$ts.json")
        outputFile.writeText(prettyJson.encodeToString(outputJson))

        println("\nPrompts: ${promptFile.absolutePath}")
        println("Output:  ${outputFile.absolutePath}")
    }

    private fun findAgentBaseDir(workflowRoot: File, filter: String?): File? {
        if (!workflowRoot.exists()) return null
        return workflowRoot.listFiles()
            ?.filter { it.isDirectory }
            ?.filter { filter == null || it.name.startsWith(filter, ignoreCase = true) }
            ?.map { File(it, "agent") }
            ?.firstOrNull { File(it, "sessions.json").exists() }
    }

    private suspend fun runLlmCall(client: SourcegraphChatClient, prompt: String, temperature: Double): String {
        val messages = listOf(ChatMessage(role = "user", content = prompt))
        return when (val result = client.sendMessage(messages = messages, tools = null, maxTokens = 4000, temperature = temperature)) {
            is ApiResult.Success -> result.data.choices.firstOrNull()?.message?.content ?: "(empty response)"
            is ApiResult.Error -> "ERROR [${result.type}]: ${result.message}"
        }
    }

    private fun buildCompactMechanical(m: ReportData.Mechanical, json: Json): String {
        return json.encodeToString(m)
    }

    private fun buildClassifierPrompt(mechanicalJson: String, digestsJson: String): String = """
You are a session classifier. Given a list of agent sessions, classify each one with structured labels.

MECHANICAL DATA (7-day window):
$mechanicalJson

SESSIONS (compact digest per session):
$digestsJson

For each session, output ONLY valid JSON matching this schema exactly (no markdown fences, no commentary):
{
  "perSessionClassifications": [
    {
      "sessionId": "<id>",
      "taskType": "<Bug Fix|Feature Implementation|Code Review|Git Workflow|Release Management|Explanation|Refactor|Investigation>",
      "outcome": "<Fully Achieved|Mostly Achieved|Partially Achieved|Not Achieved|Unclear>",
      "whatHelped": "<Multi-file Changes|Good Debugging|Good Explanations|Correct Code Edits|Proactive Help|Cross-Module Coordination|Persona Tuning|None>",
      "frictionType": "<Wrong Approach|Buggy Code|Excessive Changes|Misunderstood Request|User Rejected Action|Output Token Limit|Tool Failure|Context Exhausted|None>",
      "satisfaction": "<Frustrated|Dissatisfied|Likely Satisfied|Satisfied>",
      "sessionType": "<Single-Turn Q&A|Plan-then-Execute|Multi-Subagent|Long Debugging|Release Cut|Interactive Refactor>",
      "projectArea": "<brief 3-5 word area name>"
    }
  ]
}
Classify every session. Output ONLY the JSON object. No markdown fences.
    """.trimIndent()

    private fun buildNarrativePrompt(mechanicalJson: String, call1Result: String): String = """
You are writing an insights report for a developer using the Workflow Orchestrator IntelliJ plugin.
The plugin has an AI coding agent the developer uses to: write code, debug, review PRs, cut releases, manage Jira tickets.

MECHANICAL DATA (7-day window):
$mechanicalJson

SESSION CLASSIFICATIONS (from prior analysis):
$call1Result

Output ONLY valid JSON with this schema (no markdown fences, no commentary):
{
  "atAGlance": {
    "working": "<2-3 sentences about what is working well, end with concrete evidence from the data>",
    "hindering": "<2-3 sentences about what is slowing the user down, specific not generic>",
    "quickWins": "<2-3 concrete one-session-scoped suggestions the user can try immediately>",
    "ambitious": "<1-2 multi-week workflow aspirations based on observed patterns>"
  },
  "howYouUse": {
    "paragraphs": ["<paragraph 1 about workflow patterns>", "<paragraph 2 about usage signature>", "<paragraph 3 about standout behaviors>"],
    "keyInsight": "<Single most important observation about how this user works with the agent>"
  },
  "projectAreaDescriptions": [
    { "name": "<area name>", "description": "<2 sentences: what was done + how agent was used>" }
  ]
}
    """.trimIndent()

    private fun buildWinsFrictionPrompt(mechanicalJson: String, digestsJson: String, call1Result: String): String = """
You are analyzing an AI coding agent's sessions to surface wins and friction points.
Be specific — reference actual task titles and token counts from the data below.

MECHANICAL DATA:
$mechanicalJson

TOP SESSION DIGESTS:
$digestsJson

SESSION CLASSIFICATIONS:
$call1Result

Output ONLY valid JSON with this schema (no markdown fences, no commentary):
{
  "bigWins": [
    { "title": "<short win title>", "description": "<2-3 sentences referencing specific session evidence>" }
  ],
  "frictionCategories": [
    {
      "title": "<friction category name>",
      "description": "<2 sentence description>",
      "examples": ["<specific example 1>", "<specific example 2>"]
    }
  ],
  "funEnding": {
    "headline": "<amusing or characteristic moment from this window>",
    "detail": "<1 sentence context>"
  }
}
Include 3-5 bigWins and 2-4 frictionCategories.
    """.trimIndent()

    private fun buildSuggestionsPrompt(mechanicalJson: String, call1Result: String): String = """
You are generating actionable suggestions for a developer using the Workflow Orchestrator IntelliJ plugin.
The plugin has an AI coding agent embedded in IntelliJ IDEA.

AVAILABLE PLUGIN-NATIVE SURFACES (ONLY suggest these — nothing else):
- Personas: YAML files at ~/.workflow-orchestrator/agents/{name}.yaml — customize the agent personality/focus
- Skills: Markdown files at ~/.workflow-orchestrator/skills/{name}/SKILL.md — encode repeatable sequences
- Plan Mode: prefix task with "Plan:" — agent plans before acting, reduces wasted edits
- Sub-agents: spawn parallel workers via the agent tool for independent tasks
- Core Memory: use core_memory_append to persist project facts across sessions
- Archival Memory: use archival_memory_insert to store reusable findings and decisions

DO NOT SUGGEST: CLAUDE.md additions, auto-allow rules, .agent-hooks.json, or any feature not listed above.

MECHANICAL DATA:
$mechanicalJson

SESSION CLASSIFICATIONS:
$call1Result

Output ONLY valid JSON with this schema (no markdown fences, no commentary):
{
  "authoringSnippets": [
    {
      "kind": "<persona|skill>",
      "path": "<exact file path>",
      "content": "<full ready-to-save file content>",
      "why": "<one sentence citing session count or pattern>"
    }
  ],
  "featureCards": [
    {
      "title": "<feature name>",
      "oneLiner": "<one-line description>",
      "why": "<why for this specific user, cite a number>",
      "example": "<chat prompt they can paste right now>"
    }
  ],
  "patternCards": [
    {
      "title": "<pattern name>",
      "summary": "<one line>",
      "detail": "<one paragraph with session evidence>",
      "promptToCopy": "<concrete chat prompt the user can paste>"
    }
  ],
  "horizonCards": [
    {
      "title": "<ambitious multi-week workflow>",
      "possible": "<2 sentence aspiration>",
      "tip": "<first step to get started>",
      "promptToCopy": "<concrete multi-step prompt>"
    }
  ]
}
Include 1-2 authoringSnippets, 2-3 featureCards, 2-3 patternCards, 1-2 horizonCards.
    """.trimIndent()

    private class FileSessionHistoryReader : SessionHistoryReader {
        private val json = Json { ignoreUnknownKeys = true }
        override fun loadSessions(baseDir: File): List<SessionRecord> {
            val file = File(baseDir, "sessions.json")
            if (!file.exists()) return emptyList()
            return try {
                json.decodeFromString<List<SessionRecord>>(file.readText())
            } catch (_: Exception) { emptyList() }
        }
    }
}
