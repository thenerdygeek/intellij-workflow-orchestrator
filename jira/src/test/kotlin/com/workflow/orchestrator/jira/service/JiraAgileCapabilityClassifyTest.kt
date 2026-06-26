package com.workflow.orchestrator.jira.service

import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class JiraAgileCapabilityClassifyTest {

    @Test
    fun `success means agile available`() {
        assertEquals(true, JiraAgileCapabilityService.classifyProbe(ApiResult.Success(emptyList<Any>())))
    }

    @Test
    fun `not found means agile unavailable`() {
        assertEquals(false, JiraAgileCapabilityService.classifyProbe(ApiResult.Error(ErrorType.NOT_FOUND, "no agile")))
    }

    @Test
    fun `transient errors are unknown, not unavailable`() {
        assertNull(JiraAgileCapabilityService.classifyProbe(ApiResult.Error(ErrorType.NETWORK_ERROR, "down")))
        assertNull(JiraAgileCapabilityService.classifyProbe(ApiResult.Error(ErrorType.AUTH_FAILED, "401")))
        assertNull(JiraAgileCapabilityService.classifyProbe(ApiResult.Error(ErrorType.SERVER_ERROR, "500")))
    }
}
