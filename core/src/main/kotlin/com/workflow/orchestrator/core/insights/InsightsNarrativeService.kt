package com.workflow.orchestrator.core.insights

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.ai.LlmBrainFactory
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class InsightsNarrativeService {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val prettyJson = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    suspend fun generate(
        project: Project,
        mechanical: ReportData.Mechanical,
        includeAI: Boolean,
        onProgress: (String) -> Unit,
    ): ReportData.Narrative {
        if (!includeAI) {
            return ReportData.Narrative()
        }

        val brain = LlmBrainFactory.create(project)
        val mechanicalJson = prettyJson.encodeToString(mechanical)
        val digestsJson = prettyJson.encodeToString(mechanical.sessionDigests)
        val topDigestsJson = prettyJson.encodeToString(mechanical.sessionDigests.take(20))

        // ── Call 1: Classifier (sequential, deterministic) ──────────────────────
        onProgress("Classifying sessions…")
        brain.temperature = 0.0
        val call1Result = withTimeoutOrNull(30_000L) {
            runLlmCall(brain, buildClassifierPrompt(mechanicalJson, digestsJson))
        } ?: ""

        // Parse call-1 result into a list for downstream calls
        val classifications: List<ReportData.PerSessionClassification> = runCatching {
            val stripped = stripFences(call1Result)
            json.decodeFromString<ClassifierResponse>(stripped).perSessionClassifications
        }.getOrElse { emptyList() }

        val call1Json = if (call1Result.isNotBlank()) call1Result else "{}"

        // ── Calls 2/3/4: parallel under a supervisor scope ───────────────────────
        brain.temperature = 0.3
        val supervisorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        onProgress("Generating narrative…")
        val narrative2Deferred = supervisorScope.async {
            withTimeoutOrNull(30_000L) {
                runLlmCall(brain, buildNarrativePrompt(mechanicalJson, call1Json))
            } ?: ""
        }

        onProgress("Analysing wins and friction…")
        val narrative3Deferred = supervisorScope.async {
            withTimeoutOrNull(30_000L) {
                runLlmCall(brain, buildWinsFrictionPrompt(mechanicalJson, topDigestsJson, call1Json))
            } ?: ""
        }

        onProgress("Generating suggestions…")
        val narrative4Deferred = supervisorScope.async {
            withTimeoutOrNull(30_000L) {
                runLlmCall(brain, buildSuggestionsPrompt(mechanicalJson, call1Json))
            } ?: ""
        }

        val call2 = narrative2Deferred.await()
        val call3 = narrative3Deferred.await()
        val call4 = narrative4Deferred.await()

        onProgress("Assembling report…")

        // ── Parse call 2 ─────────────────────────────────────────────────────────
        var atAGlance = ReportData.AtAGlance()
        var howYouUse = ReportData.HowYouUse()
        var projectAreaDescriptions: List<ReportData.ProjectAreaDescription> = emptyList()
        runCatching {
            val stripped = stripFences(call2)
            val r = json.decodeFromString<NarrativeResponse>(stripped)
            atAGlance = r.atAGlance ?: ReportData.AtAGlance()
            howYouUse = r.howYouUse ?: ReportData.HowYouUse()
            projectAreaDescriptions = r.projectAreaDescriptions ?: emptyList()
        }

        // ── Parse call 3 ─────────────────────────────────────────────────────────
        var bigWins: List<ReportData.BigWin> = emptyList()
        var frictionCategories: List<ReportData.FrictionCategory> = emptyList()
        var funEnding = ReportData.FunEnding()
        runCatching {
            val stripped = stripFences(call3)
            val r = json.decodeFromString<WinsFrictionResponse>(stripped)
            bigWins = r.bigWins ?: emptyList()
            frictionCategories = r.frictionCategories ?: emptyList()
            funEnding = r.funEnding ?: ReportData.FunEnding()
        }

        // ── Parse call 4 ─────────────────────────────────────────────────────────
        var authoringSnippets: List<ReportData.AuthoringSnippet> = emptyList()
        var featureCards: List<ReportData.FeatureCard> = emptyList()
        var patternCards: List<ReportData.PatternCard> = emptyList()
        var horizonCards: List<ReportData.HorizonCard> = emptyList()
        runCatching {
            val stripped = stripFences(call4)
            val r = json.decodeFromString<SuggestionsResponse>(stripped)
            authoringSnippets = r.authoringSnippets ?: emptyList()
            featureCards = r.featureCards ?: emptyList()
            patternCards = r.patternCards ?: emptyList()
            horizonCards = r.horizonCards ?: emptyList()
        }

        return ReportData.Narrative(
            atAGlance = atAGlance,
            howYouUse = howYouUse,
            projectAreaDescriptions = projectAreaDescriptions,
            bigWins = bigWins,
            frictionCategories = frictionCategories,
            funEnding = funEnding,
            authoringSnippets = authoringSnippets,
            featureCards = featureCards,
            patternCards = patternCards,
            horizonCards = horizonCards,
            perSessionClassifications = classifications,
        )
    }

    // ── LLM call helper ──────────────────────────────────────────────────────────

    private suspend fun runLlmCall(brain: com.workflow.orchestrator.core.ai.LlmBrain, prompt: String): String {
        val messages = listOf(ChatMessage(role = "user", content = prompt))
        return when (val result = brain.chat(messages = messages, maxTokens = 4000)) {
            is ApiResult.Success -> result.data.choices.firstOrNull()?.message?.content ?: ""
            is ApiResult.Error -> ""
        }
    }

    private fun stripFences(text: String): String =
        text.replace(Regex("```json\\s*|\\s*```"), "").trim()

    // ── Prompt builders (verbatim from InsightsPromptPrototype) ─────────────────

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

    // ── Intermediate deserialization wrappers ────────────────────────────────────

    @Serializable
    private data class ClassifierResponse(
        val perSessionClassifications: List<ReportData.PerSessionClassification> = emptyList(),
    )

    @Serializable
    private data class NarrativeResponse(
        val atAGlance: ReportData.AtAGlance? = null,
        val howYouUse: ReportData.HowYouUse? = null,
        val projectAreaDescriptions: List<ReportData.ProjectAreaDescription>? = null,
    )

    @Serializable
    private data class WinsFrictionResponse(
        val bigWins: List<ReportData.BigWin>? = null,
        val frictionCategories: List<ReportData.FrictionCategory>? = null,
        val funEnding: ReportData.FunEnding? = null,
    )

    @Serializable
    private data class SuggestionsResponse(
        val authoringSnippets: List<ReportData.AuthoringSnippet>? = null,
        val featureCards: List<ReportData.FeatureCard>? = null,
        val patternCards: List<ReportData.PatternCard>? = null,
        val horizonCards: List<ReportData.HorizonCard>? = null,
    )
}
