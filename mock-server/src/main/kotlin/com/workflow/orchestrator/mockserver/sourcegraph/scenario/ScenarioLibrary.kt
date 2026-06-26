package com.workflow.orchestrator.mockserver.sourcegraph.scenario

import com.workflow.orchestrator.mockserver.sourcegraph.scenario.Turn.Companion.FINISH_LENGTH
import com.workflow.orchestrator.mockserver.sourcegraph.scenario.Turn.Companion.FINISH_STOP
import com.workflow.orchestrator.mockserver.sourcegraph.scenario.Turn.Companion.FINISH_TOOL_CALLS
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The bundled scripted scenarios + a runtime registry for dynamic custom scenarios.
 *
 * Every BUILT-IN tool call uses a REAL agent tool name with valid JSON args so the loop executes it
 * and returns. The last turn of every non-error scenario calls `attempt_completion` (kind=done) so
 * the loop terminates. Usage numbers are realistic and monotonic so the token meter is verifiable.
 *
 * Custom scenarios (registered via [register] → `SourcegraphState.registerCustomScenario`) may use
 * ANY tool name — including unknown ones — so cowork can exercise the plugin's unknown-tool handling.
 *
 * Selection: a `[name]` tag in the latest user message selects + resets; else the admin-set default;
 * else [DEFAULT_SCENARIO].
 *
 * Thread-safe: the registry is a [ConcurrentHashMap] and insertion order is tracked in a
 * [CopyOnWriteArrayList] so [names]/[all] stay stable while a custom scenario is being registered.
 */
class ScenarioLibrary(initial: List<Scenario> = bundled()) {

    private val registry = ConcurrentHashMap<String, Scenario>()
    private val order = CopyOnWriteArrayList<String>()

    init {
        initial.forEach { register(it) }
    }

    /** Register (or overwrite) a scenario by name. New names are appended to the listing order. */
    fun register(scenario: Scenario) {
        if (registry.put(scenario.name, scenario) == null) {
            order.addIfAbsent(scenario.name)
        }
    }

    fun byName(name: String): Scenario? = registry[name]

    fun exists(name: String): Boolean = registry.containsKey(name)

    fun names(): List<String> = order.toList()

    /** The default scenario, falling back to the first registered one if the default is missing. */
    fun default(): Scenario = registry[DEFAULT_SCENARIO] ?: registry.values.first()

    /** All scenarios in insertion order (for invariant checks / iteration). */
    fun all(): List<Scenario> = order.mapNotNull { registry[it] }

    companion object {
        const val DEFAULT_SCENARIO = "read-and-finish"

        /** Helper for a terminal completion turn. */
        private fun complete(result: String, prompt: Int, completion: Int): Turn = Turn(
            textChunks = listOf("Done — wrapping up."),
            toolCalls = listOf(
                ScenarioToolCall(
                    "attempt_completion",
                    """{"kind":"done","result":${jsonString(result)}}""",
                ),
            ),
            finishReason = FINISH_TOOL_CALLS,
            usage = TurnUsage(prompt, completion),
        )

        private fun jsonString(raw: String): String =
            "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

        fun bundled(): List<Scenario> = listOf(
            readAndFinish(),
            runCommandStream(),
            editScratch(),
            multiTool(),
            planMode(),
            longStream(),
            errorRetry(),
            spawnSubagents(),
            askQuestion(),
            monitors(),
            delegation(),
            backgroundProcess(),
            compaction(),
        )

        // ── read-and-finish (default) ───────────────────────────────────────────────
        private fun readAndFinish() = Scenario(
            "read-and-finish",
            listOf(
                Turn(
                    thinking = "The user wants me to look at the project. Let me read the main entry point first.",
                    textChunks = listOf(
                        "I'll start by reading the main file to understand how the app is wired.",
                    ),
                    toolCalls = listOf(
                        ScenarioToolCall("read_file", """{"path":"src/main/kotlin/Main.kt"}"""),
                    ),
                    finishReason = FINISH_TOOL_CALLS,
                    usage = TurnUsage(1200, 80),
                ),
                complete(
                    "Reviewed Main.kt — it's the application entry point that starts the server.",
                    prompt = 1500,
                    completion = 120,
                ),
            ),
        )

        // ── run-command-stream ──────────────────────────────────────────────────────
        private fun runCommandStream() = Scenario(
            "run-command-stream",
            listOf(
                Turn(
                    thinking = "Let me inspect the workspace by echoing a message and listing files.",
                    textChunks = listOf("Running a quick shell command to inspect the workspace."),
                    toolCalls = listOf(
                        ScenarioToolCall(
                            "run_command",
                            """{"command":"echo 'hello from the mock server' && ls -la","description":"List workspace files and print a greeting"}""",
                        ),
                    ),
                    finishReason = FINISH_TOOL_CALLS,
                    usage = TurnUsage(1100, 70),
                ),
                complete(
                    "Ran the shell command; the workspace contents were listed successfully.",
                    prompt = 1450,
                    completion = 95,
                ),
            ),
        )

        // ── edit-scratch ────────────────────────────────────────────────────────────
        private fun editScratch() = Scenario(
            "edit-scratch",
            listOf(
                Turn(
                    thinking = "I'll create a scratch file, then tweak one line to show the diff + approval flow.",
                    textChunks = listOf("Creating a scratch file to demonstrate the edit flow."),
                    toolCalls = listOf(
                        ScenarioToolCall(
                            "create_file",
                            """{"path":"scratch/notes.txt","content":"line one\nline two\nline three\n","description":"Scratch notes file for the demo"}""",
                        ),
                    ),
                    finishReason = FINISH_TOOL_CALLS,
                    usage = TurnUsage(1000, 110),
                ),
                Turn(
                    textChunks = listOf("Now I'll update the second line."),
                    toolCalls = listOf(
                        ScenarioToolCall(
                            "edit_file",
                            """{"path":"scratch/notes.txt","old_string":"line two","new_string":"line two (edited by the mock)","description":"Tweak the second line"}""",
                        ),
                    ),
                    finishReason = FINISH_TOOL_CALLS,
                    usage = TurnUsage(1300, 100),
                ),
                complete(
                    "Created scratch/notes.txt and edited the second line.",
                    prompt = 1650,
                    completion = 85,
                ),
            ),
        )

        // ── multi-tool (3+ tools) ───────────────────────────────────────────────────
        private fun multiTool() = Scenario(
            "multi-tool",
            listOf(
                Turn(
                    thinking = "I'll read the README, search for TODOs, then write a summary.",
                    textChunks = listOf("First, reading the README to get oriented."),
                    toolCalls = listOf(ScenarioToolCall("read_file", """{"path":"README.md"}""")),
                    finishReason = FINISH_TOOL_CALLS,
                    usage = TurnUsage(1200, 90),
                ),
                Turn(
                    textChunks = listOf("Now searching the tree for TODO markers."),
                    toolCalls = listOf(
                        ScenarioToolCall(
                            "run_command",
                            """{"command":"grep -rn TODO . || true","description":"Search the project for TODO markers"}""",
                        ),
                    ),
                    finishReason = FINISH_TOOL_CALLS,
                    usage = TurnUsage(1500, 100),
                ),
                Turn(
                    textChunks = listOf("Writing a short summary of what I found."),
                    toolCalls = listOf(
                        ScenarioToolCall(
                            "create_file",
                            """{"path":"scratch/summary.md","content":"# Summary\n\n- Reviewed the README\n- Searched the tree for TODO markers\n","description":"Summary of findings"}""",
                        ),
                    ),
                    finishReason = FINISH_TOOL_CALLS,
                    usage = TurnUsage(1800, 130),
                ),
                complete(
                    "Reviewed the README, searched for TODOs, and wrote scratch/summary.md.",
                    prompt = 2100,
                    completion = 90,
                ),
            ),
        )

        // ── plan-mode (engages the Approve gate) ────────────────────────────────────
        // Turn 1 enters plan mode AND presents the plan in ONE assistant turn. With
        // needs_more_exploration=false, AgentLoop's PlanResponse branch calls userInputChannel.receive()
        // and SUSPENDS — the plan card renders and the loop waits for the user to click Approve. There is
        // deliberately NO attempt_completion here, so the loop does NOT auto-terminate. finishReason is
        // "stop" (the client promotes it to tool_calls because tool calls are present, so the tools still
        // dispatch). Turn 2 is consumed only AFTER the user Approves and the loop resumes in ACT mode.
        private fun planMode() = Scenario(
            "plan-mode",
            listOf(
                Turn(
                    thinking = "This change spans a few files. I'll switch to plan mode and present a plan for approval.",
                    textChunks = listOf(
                        "This touches a few files — let me put together a plan for you to review before I edit anything.",
                    ),
                    toolCalls = listOf(
                        ScenarioToolCall(
                            "enable_plan_mode",
                            """{"reason":"The change spans several files; let me present a plan before editing."}""",
                        ),
                        ScenarioToolCall(
                            "plan_mode_respond",
                            """{"response":"## Plan\n\n### Task 1: Add a feature flag\n- File: `scratch/feature-config.txt`\n- Introduce `feature.enabled`.\n\n### Task 2: Wire it into the service\n- Read the flag at startup.\n\n### Task 3: Add a test\n- Cover both flag states.\n\nApprove to start implementing.","needs_more_exploration":false}""",
                        ),
                    ),
                    finishReason = FINISH_STOP,
                    usage = TurnUsage(1300, 180),
                ),
                Turn(
                    textChunks = listOf("Plan approved — implementing the first step now."),
                    toolCalls = listOf(
                        ScenarioToolCall(
                            "create_file",
                            """{"path":"scratch/feature-config.txt","content":"feature.enabled=true\n","description":"Create the feature config per the approved plan"}""",
                        ),
                        ScenarioToolCall(
                            "attempt_completion",
                            """{"kind":"done","result":"Implemented the approved plan: created scratch/feature-config.txt with the feature flag enabled."}""",
                        ),
                    ),
                    finishReason = FINISH_TOOL_CALLS,
                    usage = TurnUsage(1700, 120),
                ),
            ),
        )

        // ── long-stream (long markdown w/ code + table) ─────────────────────────────
        private fun longStream() = Scenario(
            "long-stream",
            listOf(
                Turn(
                    thinking = "Producing a long markdown document so the chat can be scrolled and copied.",
                    textChunks = LONG_MARKDOWN_CHUNKS,
                    toolCalls = listOf(
                        ScenarioToolCall(
                            "attempt_completion",
                            """{"kind":"done","result":"Generated a long markdown document with a code block and a table."}""",
                        ),
                    ),
                    finishReason = FINISH_TOOL_CALLS,
                    usage = TurnUsage(900, 1300),
                ),
            ),
        )

        // ── error-retry (pairs with ChaosMiddleware) ────────────────────────────────
        private fun errorRetry() = Scenario(
            "error-retry",
            listOf(
                Turn(
                    textChunks = listOf("Let me fetch that large file…"),
                    finishReason = FINISH_LENGTH,
                    usage = TurnUsage(1200, 5),
                    error = "Mock gateway: upstream context deadline exceeded",
                ),
                Turn(
                    thinking = "The previous response was truncated. Retrying with a smaller, focused read.",
                    textChunks = listOf("Retrying with a focused read of just the top of the file."),
                    toolCalls = listOf(
                        ScenarioToolCall(
                            "read_file",
                            """{"path":"src/main/kotlin/Config.kt","offset":"1","limit":"40"}""",
                        ),
                    ),
                    finishReason = FINISH_TOOL_CALLS,
                    usage = TurnUsage(1300, 80),
                ),
                complete(
                    "Recovered from the truncated response and read the configuration file.",
                    prompt = 1550,
                    completion = 70,
                ),
            ),
        )

        // ── spawn-subagents (the tool's registered name is `agent`) ─────────────────
        private fun spawnSubagents() = Scenario(
            "spawn-subagents",
            listOf(
                Turn(
                    thinking = "I'll delegate exploration to a sub-agent first.",
                    textChunks = listOf("Spawning a sub-agent to summarize the authentication flow."),
                    toolCalls = listOf(
                        ScenarioToolCall(
                            "agent",
                            """{"description":"Explore auth module","prompt":"Summarize the authentication flow in the auth package and report the key classes."}""",
                        ),
                    ),
                    finishReason = FINISH_TOOL_CALLS,
                    usage = TurnUsage(1300, 110),
                ),
                Turn(
                    textChunks = listOf("Now fanning out two read-only sub-agents in parallel."),
                    toolCalls = listOf(
                        ScenarioToolCall(
                            "agent",
                            """{"description":"Survey services","prompt":"List the core services and their responsibilities.","description_2":"Survey HTTP clients","prompt_2":"List the HTTP clients and their auth schemes."}""",
                        ),
                    ),
                    finishReason = FINISH_TOOL_CALLS,
                    usage = TurnUsage(1600, 160),
                ),
                complete(
                    "Spawned sub-agents to survey auth, services, and HTTP clients; consolidated their findings.",
                    prompt = 2000,
                    completion = 120,
                ),
            ),
        )

        // ── ask-question (question tool → QuestionView) ─────────────────────────────
        private fun askQuestion() = Scenario(
            "ask-question",
            listOf(
                Turn(
                    thinking = "I need a decision from the user before proceeding.",
                    textChunks = listOf("I need one clarification before I continue."),
                    toolCalls = listOf(
                        ScenarioToolCall(
                            "ask_followup_question",
                            """{"question":"Which database should the mock target?","options":"[\"Postgres\", \"MySQL\", \"SQLite\"]"}""",
                        ),
                    ),
                    finishReason = FINISH_TOOL_CALLS,
                    usage = TurnUsage(1100, 70),
                ),
                complete(
                    "Got the database choice from the user and proceeded accordingly.",
                    prompt = 1400,
                    completion = 75,
                ),
            ),
        )

        // ── monitors (proactive-event framework: register a shell monitor, then finish) ──
        private fun monitors() = Scenario(
            "monitors",
            listOf(
                Turn(
                    thinking = "I'll register a shell monitor so the user gets proactive notifications when it ticks.",
                    textChunks = listOf("Registering a shell monitor on a small command."),
                    toolCalls = listOf(
                        ScenarioToolCall(
                            "monitor",
                            """{"action":"start","source":"shell","command":"date +%S","description":"Tick monitor (prints the seconds)"}""",
                        ),
                    ),
                    finishReason = FINISH_TOOL_CALLS,
                    usage = TurnUsage(1200, 90),
                ),
                Turn(
                    textChunks = listOf("Listing the active monitors to confirm registration."),
                    toolCalls = listOf(ScenarioToolCall("monitor", """{"action":"list"}""")),
                    finishReason = FINISH_TOOL_CALLS,
                    usage = TurnUsage(1450, 70),
                ),
                complete(
                    "Registered a shell monitor; it will push a notification card each time it fires.",
                    prompt = 1700,
                    completion = 80,
                ),
            ),
        )

        // ── delegation (cross-IDE delegation tool → question/answer/result cards) ────
        private fun delegation() = Scenario(
            "delegation",
            listOf(
                Turn(
                    thinking = "Let me see which IDE instances are available to delegate to.",
                    textChunks = listOf("Checking available delegation targets."),
                    toolCalls = listOf(ScenarioToolCall("delegation", """{"action":"list_targets"}""")),
                    finishReason = FINISH_TOOL_CALLS,
                    usage = TurnUsage(1200, 60),
                ),
                Turn(
                    textChunks = listOf("Delegating the test run to another IDE instance."),
                    toolCalls = listOf(
                        ScenarioToolCall(
                            "delegation",
                            """{"action":"send","request":"Please run the full test suite and report any failures.","suggested_repo":"my-repo"}""",
                        ),
                    ),
                    finishReason = FINISH_TOOL_CALLS,
                    usage = TurnUsage(1500, 120),
                ),
                complete(
                    "Listed delegation targets and sent a delegated request to another IDE instance.",
                    prompt = 1800,
                    completion = 90,
                ),
            ),
        )

        // ── background-process (run_command background → BackgroundIndicator chip) ───
        private fun backgroundProcess() = Scenario(
            "background-process",
            listOf(
                Turn(
                    thinking = "I'll start a long-running command in the background so the chat stays responsive.",
                    textChunks = listOf("Starting a long-running task in the background."),
                    toolCalls = listOf(
                        ScenarioToolCall(
                            "run_command",
                            """{"command":"sleep 30 && echo finished","description":"Long-running background task","background":true}""",
                        ),
                    ),
                    finishReason = FINISH_TOOL_CALLS,
                    usage = TurnUsage(1200, 80),
                ),
                Turn(
                    textChunks = listOf("Listing background processes to confirm it's running."),
                    toolCalls = listOf(ScenarioToolCall("background_process", """{"action":"list"}""")),
                    finishReason = FINISH_TOOL_CALLS,
                    usage = TurnUsage(1450, 70),
                ),
                complete(
                    "Started a background process (the chip should be visible) and listed it.",
                    prompt = 1700,
                    completion = 75,
                ),
            ),
        )

        // ── compaction (inflate context toward the ~88% threshold) ──────────────────
        // NOTE: real compaction triggers off the plugin's own token accounting. The mock can only
        // *report* large `usage.prompt_tokens` and emit large content; whether that trips the marker
        // depends on whether the plugin keys off reported usage vs. re-counting the history. Best-effort.
        private fun compaction() = Scenario(
            "compaction",
            listOf(
                Turn(
                    thinking = "Reading a very large file; the context is going to grow a lot.",
                    textChunks = listOf("Reading a large file — this will use a big chunk of the window.\n\n", BIG_BLOCK),
                    toolCalls = listOf(ScenarioToolCall("read_file", """{"path":"src/main/kotlin/Huge.kt"}""")),
                    finishReason = FINISH_TOOL_CALLS,
                    usage = TurnUsage(118_000, 1_500),
                ),
                Turn(
                    textChunks = listOf("Reading another large section; we're near the context limit now.\n\n", BIG_BLOCK),
                    toolCalls = listOf(ScenarioToolCall("read_file", """{"path":"src/main/kotlin/AlsoHuge.kt"}""")),
                    finishReason = FINISH_TOOL_CALLS,
                    usage = TurnUsage(128_000, 1_800),
                ),
                complete(
                    "Read two large files; context should have crossed the compaction threshold.",
                    prompt = 40_000,
                    completion = 600,
                ),
            ),
        )

        private val BIG_BLOCK: String = buildString {
            repeat(40) { i ->
                append("Section ").append(i).append(": ")
                append("the quick brown fox jumps over the lazy dog. ".repeat(12))
                append("\n\n")
            }
        }

        private val LONG_MARKDOWN_CHUNKS: List<String> = listOf(
            "# Mock Long-Stream Report\n\n",
            "This document exists to exercise the chat's streaming, scrolling, and copy affordances. ",
            "It contains several paragraphs, a fenced code block, and a table.\n\n",
            "## Overview\n\nThe mock server replays scripted turns so the agent loop runs entirely offline. ",
            "Each scenario is a deterministic sequence of LLM replies.\n\n",
            "## Example code\n\n",
            "```kotlin\nfun greet(name: String): String {\n    return \"Hello, " + "\$name!\"\n}\n\n" +
                "fun main() {\n    println(greet(\"mock\"))\n}\n```\n\n",
            "## Comparison table\n\n",
            "| Scenario | Tools used | Terminates with |\n",
            "|---|---|---|\n",
            "| read-and-finish | read_file | attempt_completion |\n",
            "| run-command-stream | run_command | attempt_completion |\n",
            "| multi-tool | read_file, run_command, create_file | attempt_completion |\n\n",
            "## Closing notes\n\n",
            "All of the above streamed token-by-token from the mock. ",
            "You can scroll back up, select the code block, and copy it to verify the rich rendering path.\n",
        )
    }
}
