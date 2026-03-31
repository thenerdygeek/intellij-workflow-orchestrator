package com.workflow.orchestrator.agent.context.condenser

import com.workflow.orchestrator.agent.api.dto.ChatMessage
import com.workflow.orchestrator.agent.context.ContextManagementConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CondenserFactoryTest {

    private val fakeLlmClient = object : SummarizationClient {
        override suspend fun summarize(messages: List<ChatMessage>): String? = "summary"
    }

    @Test
    fun `default config with llm client produces 4 condensers`() {
        val pipeline = CondenserFactory.create(
            config = ContextManagementConfig.DEFAULT,
            llmClient = fakeLlmClient
        )
        val condensers = pipeline.getCondensers()

        assertEquals(4, condensers.size)
        assertInstanceOf(SmartPrunerCondenser::class.java, condensers[0])
        assertInstanceOf(ObservationMaskingCondenser::class.java, condensers[1])
        assertInstanceOf(ConversationWindowCondenser::class.java, condensers[2])
        assertInstanceOf(LLMSummarizingCondenser::class.java, condensers[3])
    }

    @Test
    fun `no llm client produces 3 condensers without LLMSummarizing`() {
        val pipeline = CondenserFactory.create(
            config = ContextManagementConfig.DEFAULT,
            llmClient = null
        )
        val condensers = pipeline.getCondensers()

        assertEquals(3, condensers.size)
        assertInstanceOf(SmartPrunerCondenser::class.java, condensers[0])
        assertInstanceOf(ObservationMaskingCondenser::class.java, condensers[1])
        assertInstanceOf(ConversationWindowCondenser::class.java, condensers[2])
    }

    @Test
    fun `smartPruner disabled produces 3 condensers without SmartPruner`() {
        val config = ContextManagementConfig(smartPrunerEnabled = false)
        val pipeline = CondenserFactory.create(
            config = config,
            llmClient = fakeLlmClient
        )
        val condensers = pipeline.getCondensers()

        assertEquals(3, condensers.size)
        assertInstanceOf(ObservationMaskingCondenser::class.java, condensers[0])
        assertInstanceOf(ConversationWindowCondenser::class.java, condensers[1])
        assertInstanceOf(LLMSummarizingCondenser::class.java, condensers[2])
    }

    @Test
    fun `worker config produces valid pipeline`() {
        val pipeline = CondenserFactory.create(
            config = ContextManagementConfig.WORKER,
            llmClient = fakeLlmClient
        )
        val condensers = pipeline.getCondensers()

        assertEquals(4, condensers.size)
        assertInstanceOf(SmartPrunerCondenser::class.java, condensers[0])
        assertInstanceOf(ObservationMaskingCondenser::class.java, condensers[1])
        assertInstanceOf(ConversationWindowCondenser::class.java, condensers[2])
        assertInstanceOf(LLMSummarizingCondenser::class.java, condensers[3])
    }

    @Test
    fun `pipeline order is cheapest to most expensive`() {
        val pipeline = CondenserFactory.create(
            config = ContextManagementConfig.DEFAULT,
            llmClient = fakeLlmClient
        )
        val condensers = pipeline.getCondensers()

        // SmartPruner (zero-loss) < ObservationMasking (cheap) < ConversationWindow (reactive) < LLM (expensive)
        assertTrue(condensers[0] is SmartPrunerCondenser, "First should be SmartPruner")
        assertTrue(condensers[1] is ObservationMaskingCondenser, "Second should be ObservationMasking")
        assertTrue(condensers[2] is ConversationWindowCondenser, "Third should be ConversationWindow")
        assertTrue(condensers[3] is LLMSummarizingCondenser, "Fourth should be LLMSummarizing")
    }

    @Test
    fun `both smartPruner disabled and no llm client produces 2 condensers`() {
        val config = ContextManagementConfig(smartPrunerEnabled = false)
        val pipeline = CondenserFactory.create(
            config = config,
            llmClient = null
        )
        val condensers = pipeline.getCondensers()

        assertEquals(2, condensers.size)
        assertInstanceOf(ObservationMaskingCondenser::class.java, condensers[0])
        assertInstanceOf(ConversationWindowCondenser::class.java, condensers[1])
    }
}
