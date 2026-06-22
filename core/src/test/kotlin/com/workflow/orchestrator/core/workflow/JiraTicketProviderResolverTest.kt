package com.workflow.orchestrator.core.workflow

import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class JiraTicketProviderResolverTest {
    @Test fun `lowest-order JiraTicketProvider wins`() {
        val high = object : JiraTicketProvider {
            override val order: Int get() = 100
            override suspend fun getTicketDetails(ticketId: String): TicketDetails? = null
            override suspend fun getTicketContext(key: String): TicketContext? = null
        }
        val low = object : JiraTicketProvider {
            override val order: Int get() = 5
            override suspend fun getTicketDetails(ticketId: String): TicketDetails? = null
            override suspend fun getTicketContext(key: String): TicketContext? = null
        }
        assertSame(low, JiraTicketProvider.lowestOrderOf(listOf(high, low)))
    }
}
