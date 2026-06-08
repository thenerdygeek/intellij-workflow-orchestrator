package com.workflow.orchestrator.agent.loop

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins which tools are streaming-enabled. `AgentLoop` only sets
 * `RunCommandTool.currentToolCallId` for tools in `STREAMING_TOOLS`; a tool that spawns a
 * long-running external process but is NOT listed reads a null toolCallId, so the
 * `if (toolCallId != null)` gate suppresses ALL live output (and the heartbeat) — the UI shows a
 * silent "executing" spinner for the whole run.
 *
 * Regression: `java_runtime_exec` (run_tests Maven/Gradle shell fallback, compile_module),
 * `python_runtime_exec` (pytest shell), `runtime_exec` (run_config) and `coverage` all spawn shell
 * processes and read `currentToolCallId`, but were missing from the set — so a `use_native_runner=false`
 * test run looked stuck.
 */
class StreamingToolsTest {

    @Test
    fun `runtime exec tools that spawn shell processes are streaming-enabled`() {
        listOf("run_command", "sonar", "java_runtime_exec", "python_runtime_exec", "runtime_exec", "coverage")
            .forEach { tool ->
                assertTrue(
                    tool in AgentLoop.STREAMING_TOOLS,
                    "'$tool' must be in STREAMING_TOOLS so AgentLoop sets currentToolCallId and its " +
                        "live output + heartbeat reach the UI (else the run shows a silent spinner)",
                )
            }
    }
}
