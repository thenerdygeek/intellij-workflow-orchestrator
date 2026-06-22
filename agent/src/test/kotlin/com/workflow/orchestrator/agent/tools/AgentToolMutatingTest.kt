package com.workflow.orchestrator.agent.tools

import com.workflow.orchestrator.agent.tools.builtin.EditFileTool
import com.workflow.orchestrator.agent.tools.builtin.ReadFileTool
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentToolMutatingTest {
    @Test fun `read-only tool defaults to non-mutating`() { assertFalse(ReadFileTool().isMutating) }
    @Test fun `edit_file declares itself mutating`() { assertTrue(EditFileTool().isMutating) }
}
