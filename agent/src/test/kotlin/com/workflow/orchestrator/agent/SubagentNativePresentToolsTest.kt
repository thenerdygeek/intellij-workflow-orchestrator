package com.workflow.orchestrator.agent

import com.workflow.orchestrator.core.ai.protocol.AnthropicNativeProtocol
import com.workflow.orchestrator.core.ai.protocol.XmlToolProtocol
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Phase 4a Task 11 (C1) — behavioral pin on the seam the whole threading exists to honor: on the
 * native path the sub-agent system prompt must OMIT the XML tool-doc block, because tools live only
 * in the wire `tools:[]` field. `AnthropicNativeProtocol.presentTools` returns null; `SubagentRunner`
 * builds the §6c block from `toolProtocol.presentTools(coreDefinitions)`, so a null there means the
 * builder omits the block. The XML protocol still returns a non-null block (back-compat default).
 */
class SubagentNativePresentToolsTest {

    @Test
    fun `native protocol presentTools returns null so the sub-agent prompt omits XML tool docs`() {
        assertNull(AnthropicNativeProtocol().presentTools(emptyList()))
    }

    @Test
    fun `XML protocol still presents a non-null tool-doc block (back-compat default)`() {
        assertNotNull(XmlToolProtocol().presentTools(emptyList()))
    }
}
