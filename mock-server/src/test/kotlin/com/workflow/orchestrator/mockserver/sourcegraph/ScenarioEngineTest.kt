package com.workflow.orchestrator.mockserver.sourcegraph

import com.workflow.orchestrator.mockserver.sourcegraph.scenario.EngineMessage
import com.workflow.orchestrator.mockserver.sourcegraph.scenario.ScenarioEngine
import com.workflow.orchestrator.mockserver.sourcegraph.scenario.ScenarioLibrary
import com.workflow.orchestrator.mockserver.sourcegraph.scenario.ScenarioState
import com.workflow.orchestrator.mockserver.sourcegraph.scenario.Turn
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScenarioEngineTest {

    private fun engineWith(default: String = ScenarioLibrary.DEFAULT_SCENARIO): Pair<ScenarioEngine, ScenarioLibrary> {
        val library = ScenarioLibrary()
        val state = ScenarioState(default)
        return ScenarioEngine(library, state) to library
    }

    private fun user(text: String) = listOf(EngineMessage("user", text))

    @Test
    fun `advances turn index across successive requests in one conversation`() {
        val (engine, library) = engineWith()
        val scenario = library.byName("read-and-finish")!!

        // Turn 1: only the original user message.
        val t0 = engine.nextTurn(user("please review the project"))
        assertEquals(scenario.turns[0], t0)

        // Turn 2: a tool result was appended; the first user message (the conversation key) is stable.
        val t1 = engine.nextTurn(
            listOf(
                EngineMessage("user", "please review the project"),
                EngineMessage("assistant", "<read_file>...</read_file>"),
                EngineMessage("user", "RESULT of read_file: ..."),
            ),
        )
        assertEquals(scenario.turns[1], t1)
    }

    @Test
    fun `prompt tag selects and resets the scenario`() {
        val (engine, library) = engineWith()
        val expected = library.byName("multi-tool")!!.turns[0]
        val turn = engine.nextTurn(user("[multi-tool] please survey the repo"))
        assertEquals(expected, turn)
        assertEquals("read_file", turn.toolCalls.single().name)
        assertTrue(turn.toolCalls.single().argumentsJson.contains("README.md"))
    }

    @Test
    fun `re-tagging the same conversation resets to turn 0`() {
        val (engine, library) = engineWith()
        engine.nextTurn(user("[edit-scratch] go")) // turn 0
        engine.nextTurn(user("[edit-scratch] go")) // turn 1 (same key, tag re-resets to 0 then advances)
        // Both prior calls reset to 0 because the tag is present each time, so each serves turn 0.
        val again = engine.nextTurn(user("[edit-scratch] go"))
        assertEquals(library.byName("edit-scratch")!!.turns[0], again)
    }

    @Test
    fun `admin default scenario is used when no tag present`() {
        val (engine, library) = engineWith(default = "run-command-stream")
        val turn = engine.nextTurn(user("just do the thing"))
        assertEquals(library.byName("run-command-stream")!!.turns[0], turn)
        assertEquals("run_command", turn.toolCalls.single().name)
    }

    @Test
    fun `falls back to read-and-finish by default`() {
        val (engine, library) = engineWith()
        val turn = engine.nextTurn(user("hello"))
        assertEquals(library.byName(ScenarioLibrary.DEFAULT_SCENARIO)!!.turns[0], turn)
    }

    @Test
    fun `unknown tag falls through to the default scenario`() {
        val (engine, library) = engineWith()
        val turn = engine.nextTurn(user("[does-not-exist] hi"))
        assertEquals(library.byName(ScenarioLibrary.DEFAULT_SCENARIO)!!.turns[0], turn)
    }

    @Test
    fun `every scenario terminates with attempt_completion`() {
        val library = ScenarioLibrary()
        library.all().forEach { scenario ->
            val last = scenario.turns.last()
            assertTrue(
                last.toolCalls.any { it.name == "attempt_completion" },
                "Scenario '${scenario.name}' must end with attempt_completion",
            )
        }
    }

    @Test
    fun `error-retry begins with an error turn and still recovers to completion`() {
        val library = ScenarioLibrary()
        val scenario = library.byName("error-retry")!!
        assertTrue(scenario.turns.first().error != null)
        assertTrue(scenario.turns.first().toolCalls.isEmpty())
        assertTrue(scenario.turns.last().toolCalls.any { it.name == "attempt_completion" })
    }

    @Test
    fun `OpenAI serializer renders a well-formed tool-call turn`() {
        val library = ScenarioLibrary()
        val turn = library.byName("read-and-finish")!!.turns[0]
        val sse = OpenAiSseSerializer.serialize(turn, MOCK_MODEL_ID)
        assertTrue(sse.contains("data: "))
        assertTrue(sse.contains("\"role\":\"assistant\""))
        assertTrue(sse.contains("<read_file>"))            // XML-in-content (load-bearing)
        assertTrue(sse.contains("\"finish_reason\":\"tool_calls\""))
        assertTrue(sse.contains("\"prompt_tokens\":1200"))
        assertTrue(sse.contains("data: [DONE]"))
    }

    @Test
    fun `Cody serializer renders a well-formed tool-call turn`() {
        val library = ScenarioLibrary()
        val turn = library.byName("read-and-finish")!!.turns[0]
        val sse = CodySseSerializer.serialize(turn)
        assertTrue(sse.contains("\"deltaText\""))
        assertTrue(sse.contains("<read_file>"))
        assertTrue(sse.contains("\"stopReason\":\"tool_use\""))
        assertTrue(sse.contains("event: done"))
    }

    @Test
    fun `both serializers render a text-only turn`() {
        val turn = Turn(textChunks = listOf("hello world"), finishReason = Turn.FINISH_STOP)
        val openai = OpenAiSseSerializer.serialize(turn, MOCK_MODEL_ID)
        assertTrue(openai.contains("hello world"))
        assertTrue(openai.contains("\"finish_reason\":\"stop\""))
        assertTrue(openai.contains("data: [DONE]"))

        val cody = CodySseSerializer.serialize(turn)
        assertTrue(cody.contains("hello world"))
        assertTrue(cody.contains("\"stopReason\":\"end_turn\""))
        assertTrue(cody.contains("event: done"))
    }

    @Test
    fun `Cody serializer emits an in-band error frame for an error turn`() {
        val library = ScenarioLibrary()
        val errorTurn = library.byName("error-retry")!!.turns.first()
        val cody = CodySseSerializer.serialize(errorTurn)
        assertTrue(cody.contains("event: error"))
        assertTrue(cody.contains("\"error\""))
        assertTrue(cody.contains("event: done"))
        assertFalse(cody.contains("attempt_completion"))
    }

    @Test
    fun `built-in feature-area scenarios are registered with the expected first tool`() {
        val library = ScenarioLibrary()
        assertEquals("monitor", library.byName("monitors")!!.turns[0].toolCalls.single().name)
        assertEquals("delegation", library.byName("delegation")!!.turns[0].toolCalls.single().name)
        // Background is *started* via run_command(background:true); background_process manages it.
        val bg = library.byName("background-process")!!
        assertEquals("run_command", bg.turns[0].toolCalls.single().name)
        assertTrue(bg.turns[0].toolCalls.single().argumentsJson.contains("\"background\":true"))
        assertEquals("background_process", bg.turns[1].toolCalls.single().name)
        // Compaction reports near-window prompt usage to nudge the compaction marker.
        assertTrue(library.byName("compaction")!!.turns[0].usage.promptTokens >= 116_000)
    }

    // ── Dynamic custom scenarios ────────────────────────────────────────────────────

    @Test
    fun `custom scenario JSON round-trips - parse, activate, advance, terminate`() {
        val state = SourcegraphState()
        val body = """
            {
              "name": "cowork-flow",
              "turns": [
                {
                  "thinking": "let me look",
                  "text": "reading the file",
                  "toolCalls": [ { "name": "read_file", "arguments": { "path": "A.kt" } } ]
                },
                {
                  "text": "all done",
                  "toolCalls": [ { "name": "attempt_completion", "argumentsJson": "{\"kind\":\"done\",\"result\":\"ok\"}" } ]
                }
              ]
            }
        """.trimIndent()

        val result = state.registerCustomScenario(body)
        assertTrue(result is SourcegraphState.RegisterResult.Ok)
        val ok = result as SourcegraphState.RegisterResult.Ok
        assertEquals("cowork-flow", ok.name)
        assertEquals(2, ok.turnCount)

        // Registered + activated: an untagged conversation now plays the custom scenario.
        val t0 = state.engine.nextTurn(listOf(EngineMessage("user", "go")))
        assertEquals("read_file", t0.toolCalls.single().name)
        // finishReason was inferred (turn has tool calls → tool_calls).
        assertEquals(Turn.FINISH_TOOL_CALLS, t0.finishReason)
        // `arguments` object normalized to XML-in-content.
        assertTrue(OpenAiSseSerializer.serialize(t0, MOCK_MODEL_ID).contains("<path>A.kt</path>"))

        val t1 = state.engine.nextTurn(listOf(EngineMessage("user", "go"), EngineMessage("user", "RESULT: ...")))
        assertEquals("attempt_completion", t1.toolCalls.single().name)
        // `argumentsJson` string form also normalized.
        assertTrue(t1.toolCalls.single().argumentsJson.contains("\"result\":\"ok\""))
    }

    @Test
    fun `custom scenario accepts unknown tool names`() {
        val state = SourcegraphState()
        val body = """
            {"name":"weird","turns":[
              {"toolCalls":[{"name":"totally_made_up_tool","arguments":{"x":1}}]},
              {"toolCalls":[{"name":"attempt_completion","arguments":{"kind":"done","result":"k"}}]}
            ]}
        """.trimIndent()
        assertTrue(state.registerCustomScenario(body) is SourcegraphState.RegisterResult.Ok)
        val turn = state.engine.nextTurn(listOf(EngineMessage("user", "x")))
        assertEquals("totally_made_up_tool", turn.toolCalls.single().name)
        assertTrue(OpenAiSseSerializer.serialize(turn, MOCK_MODEL_ID).contains("<totally_made_up_tool>"))
    }

    @Test
    fun `custom scenario registration rejects malformed input`() {
        val state = SourcegraphState()
        assertTrue(state.registerCustomScenario("not json") is SourcegraphState.RegisterResult.Error)
        assertTrue(
            state.registerCustomScenario("""{"name":"empty","turns":[]}""")
                is SourcegraphState.RegisterResult.Error,
        )
    }

    @Test
    fun `name-plus-turnsJson overload registers from a bare turns array`() {
        val state = SourcegraphState()
        val turnsArray = """[ {"toolCalls":[{"name":"attempt_completion","arguments":{"kind":"done","result":"k"}}]} ]"""
        val result = state.registerCustomScenario("bare", turnsArray)
        assertTrue(result is SourcegraphState.RegisterResult.Ok)
        assertEquals("bare", (result as SourcegraphState.RegisterResult.Ok).name)
        assertTrue(state.listScenarios().contains("bare"))
    }
}
