package com.workflow.orchestrator.core.config

import com.workflow.orchestrator.core.model.ServiceType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WorkflowConfigOverrideTest {
    @Test fun `lowest-order WorkflowConfig wins over a higher-order provider`() {
        val highOrderCompetitor = object : WorkflowConfig {
            override val order: Int get() = Int.MAX_VALUE
            override fun baseUrl(service: ServiceType): String =
                if (service == ServiceType.JIRA) "https://jira.default.example/" else ""
        }
        val override = object : WorkflowConfig {
            override val order: Int get() = 0
            override fun baseUrl(service: ServiceType): String =
                if (service == ServiceType.JIRA) "https://jira.company-b.example/" else ""
        }
        // Order of the list must not matter — the ordering rule, not position, decides.
        val winner = WorkflowConfig.lowestOrderOf(listOf(highOrderCompetitor, override))
        assertEquals("https://jira.company-b.example/", winner!!.baseUrl(ServiceType.JIRA))
    }
}
