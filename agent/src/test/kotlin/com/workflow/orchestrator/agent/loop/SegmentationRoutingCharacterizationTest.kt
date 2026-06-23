package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.core.ai.AssistantMessageParser
import com.workflow.orchestrator.core.ai.protocol.XmlToolProtocol
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SegmentationRoutingCharacterizationTest {
    @Test fun `streaming parse through protocol equals AssistantMessageParser on partial and full text`() {
        val tools = setOf("edit_file")
        val params = setOf("path", "content")
        val protocol = XmlToolProtocol()
        // Partial (mid-stream) and complete accumulations — both must match the parser exactly.
        val partial = StringBuilder("<edit_file>\n<path>/a.kt</path>\n<content>line")
        val full = StringBuilder("<edit_file>\n<path>/a.kt</path>\n<content>line one</content>\n</edit_file>")
        assertEquals(
            AssistantMessageParser.parse(partial, tools, params),
            protocol.parseToolCalls(partial, tools, params),
        )
        assertEquals(
            AssistantMessageParser.parse(full, tools, params),
            protocol.parseToolCalls(full, tools, params),
        )
    }

    @Test fun `UI-splitter helpers through protocol equal AssistantMessageParser (GAP1)`() {
        val protocol = XmlToolProtocol()
        // The three onChunk entry points (parse + stripPartialTag + endsWithIncompleteTag) must all
        // route identically — these pin the two helper substitutions at AgentLoop:1119 and :1137.
        val base = "visible text <read_fi"
        assertEquals(AssistantMessageParser.stripPartialTag(base), protocol.stripPartialTag(base))
        val acc = StringBuilder("visible text <read_fi")
        assertEquals(AssistantMessageParser.endsWithIncompleteTag(acc), protocol.endsWithIncompleteTag(acc))
    }
}
