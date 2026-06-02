package com.workflow.orchestrator.core.http

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class IdeProxyTest {
    @Test
    fun `selector is available`() {
        val selector = IdeProxy.selector()
        assertNotNull(selector)
    }
}
