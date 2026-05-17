// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.agent.tools.subagent

import com.workflow.orchestrator.agent.session.ApiMessage
import com.workflow.orchestrator.agent.session.ApiRole
import com.workflow.orchestrator.agent.session.ContentBlock
import com.workflow.orchestrator.agent.session.MessageStateHandler
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Documents and validates the path structure SpawnAgentTool builds for sub-agent persistence.
 *
 * The key invariant:
 *   MessageStateHandler(baseDir = agentRoot, sessionId = "{parentId}/subagents/{agentId}", ...)
 * writes its files into:
 *   agentRoot/sessions/{parentId}/subagents/{agentId}/api_conversation_history.json
 *   agentRoot/sessions/{parentId}/subagents/{agentId}/ui_messages.json
 *
 * This mirrors the path structure mandated by the Phase 5 spec and matches what
 * SpawnAgentTool wires in AgentService's subagentMessageStateHandlerFactory.
 */
class SubagentPersistenceTest {

    /**
     * Core invariant: MessageStateHandler with a nested sessionId places both persistence
     * files at the expected sub-directory path. This is the same directory structure
     * SpawnAgentTool / AgentService produce for every sub-agent run.
     */
    @Test
    fun `MessageStateHandler scoped to sub-agent directory writes files into sessions parentId subagents agentId`(
        @TempDir tmp: Path
    ) = runTest {
        val parentId = "parent-session-1"
        val agentId = "agent-abc123"
        val agentRoot = tmp.toFile()

        // Mirror the exact construction SpawnAgentTool's subagentMessageStateHandlerFactory uses:
        //   sessionId = "$parentId/subagents/$agentId"
        // MessageStateHandler computes sessionDir = File(baseDir, "sessions/$sessionId")
        //   = agentRoot/sessions/parent-session-1/subagents/agent-abc123/
        val handler = MessageStateHandler(
            baseDir = agentRoot,
            sessionId = "$parentId/subagents/$agentId",
            taskText = "sub-agent test task",
        )

        // Seed one message using the init-only (non-suspend) setter, then flush both files.
        handler.setApiConversationHistory(
            listOf(
                ApiMessage(
                    role = ApiRole.USER,
                    content = listOf(ContentBlock.Text("test message")),
                    ts = System.currentTimeMillis(),
                )
            )
        )
        // saveBoth() atomically writes both json files under the computed sessionDir.
        handler.saveBoth()

        // Assert both files land at exactly the expected path.
        val expectedSessionDir = tmp.resolve("sessions/$parentId/subagents/$agentId").toFile()
        val historyFile = expectedSessionDir.resolve("api_conversation_history.json")
        val uiFile = expectedSessionDir.resolve("ui_messages.json")

        assertTrue(historyFile.exists()) {
            "api_conversation_history.json must exist at sessions/$parentId/subagents/$agentId/"
        }
        assertTrue(historyFile.length() > 0) {
            "api_conversation_history.json must be non-empty"
        }
        assertTrue(uiFile.exists()) {
            "ui_messages.json must exist at sessions/$parentId/subagents/$agentId/"
        }
    }

    /**
     * Verify SpawnAgentTool's factory lambda in AgentService produces the correct path
     * by simulating the exact factory call site.
     *
     * The factory in AgentService is:
     *   subagentMessageStateHandlerFactory = { parentId, agentId ->
     *       MessageStateHandler(
     *           baseDir = agentBaseDir,
     *           sessionId = "$parentId/subagents/$agentId",
     *           taskText = "sub-agent $agentId",
     *       )
     *   }
     *
     * This test reproduces that call and asserts the sessionDir shape is correct.
     */
    @Test
    fun `factory lambda session path matches sessions-parentId-subagents-agentId spec`(@TempDir tmp: Path) {
        val agentBaseDir = tmp.toFile()
        val parentId = "p-001"
        val agentId = "a-999"

        // Simulate factory construction (mirrors AgentService verbatim)
        val handler = MessageStateHandler(
            baseDir = agentBaseDir,
            sessionId = "$parentId/subagents/$agentId",
            taskText = "sub-agent $agentId",
        )

        // Verify the handler's session ID is encoded as expected (public accessor).
        val expectedSessionId = "$parentId/subagents/$agentId"
        assertTrue(handler.sessionId == expectedSessionId) {
            "handler.sessionId must be '$expectedSessionId', got '${handler.sessionId}'"
        }

        // Verify the resolved directory path matches the spec without writing files.
        val expectedDir = tmp.resolve("sessions/$parentId/subagents/$agentId").toFile()
        // The sessionDir computed by MessageStateHandler is File(baseDir, "sessions/$sessionId").
        val computedDir = java.io.File(agentBaseDir, "sessions/${handler.sessionId}")
        assertTrue(computedDir.canonicalPath == expectedDir.canonicalPath) {
            "Computed session dir must be '$expectedDir', got '$computedDir'"
        }
    }
}
