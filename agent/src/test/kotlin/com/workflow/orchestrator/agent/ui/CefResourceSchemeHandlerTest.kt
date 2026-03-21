package com.workflow.orchestrator.agent.ui

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CefResourceSchemeHandlerTest {

    @Test
    fun `BASE_URL is correctly formed`() {
        assertEquals("http", CefResourceSchemeHandler.SCHEME)
        assertEquals("workflow-agent", CefResourceSchemeHandler.AUTHORITY)
        assertEquals("http://workflow-agent/", CefResourceSchemeHandler.BASE_URL)
    }

    @Test
    fun `URL construction for resources`() {
        val url = CefResourceSchemeHandler.BASE_URL + "lib/marked.min.js"
        assertTrue(url.startsWith("http://workflow-agent/"))
        assertTrue(url.endsWith("marked.min.js"))
    }
}
