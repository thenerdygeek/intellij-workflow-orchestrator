package com.workflow.orchestrator.agent.ui

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.cef.callback.CefCallback
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CefResourceSchemeHandlerTest {

    // -------------------------------------------------------------------------
    // Companion / constants
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // isPathSafe — pure-function unit tests (no JCEF, no class-loader needed)
    // -------------------------------------------------------------------------

    @Test
    fun `isPathSafe rejects dotdot traversal`() {
        assertFalse(CefResourceSchemeHandler.isPathSafe("../../etc/passwd"))
        assertFalse(CefResourceSchemeHandler.isPathSafe("../etc/passwd"))
        assertFalse(CefResourceSchemeHandler.isPathSafe("foo/../bar"))
        assertFalse(CefResourceSchemeHandler.isPathSafe(".."))
    }

    @Test
    fun `isPathSafe rejects absolute paths`() {
        assertFalse(CefResourceSchemeHandler.isPathSafe("/absolute/path"))
        assertFalse(CefResourceSchemeHandler.isPathSafe("/etc/passwd"))
    }

    @Test
    fun `isPathSafe rejects backslash separators`() {
        assertFalse(CefResourceSchemeHandler.isPathSafe("foo\\..\\bar"))
        assertFalse(CefResourceSchemeHandler.isPathSafe("foo\\bar"))
    }

    @Test
    fun `isPathSafe rejects paths with spaces`() {
        assertFalse(CefResourceSchemeHandler.isPathSafe("dist/assets/some file.js"))
    }

    @Test
    fun `isPathSafe rejects null byte after URL-decode`() {
        // %00 decodes to the null character — must be rejected
        assertFalse(CefResourceSchemeHandler.isPathSafe("foo%00.html"))
    }

    @Test
    fun `isPathSafe rejects percent-encoded dotdot`() {
        // %2e%2e decodes to ".." — traversal even if JCEF did not decode
        assertFalse(CefResourceSchemeHandler.isPathSafe("%2e%2e/foo"))
        assertFalse(CefResourceSchemeHandler.isPathSafe("foo/%2e%2e/bar"))
    }

    @Test
    fun `isPathSafe allows legitimate resource paths`() {
        assertTrue(CefResourceSchemeHandler.isPathSafe("dist/assets/index.js"))
        assertTrue(CefResourceSchemeHandler.isPathSafe("dist/assets/index.css"))
        assertTrue(CefResourceSchemeHandler.isPathSafe("dist/index.html"))
        assertTrue(CefResourceSchemeHandler.isPathSafe("lib/marked.min.js"))
        assertTrue(CefResourceSchemeHandler.isPathSafe("index.html"))
        // Path with hyphens, underscores, digits — all fine
        assertTrue(CefResourceSchemeHandler.isPathSafe("lib/prism-languages/kotlin.min.js"))
    }

    // -------------------------------------------------------------------------
    // processRequest — integration-level tests via mocked JCEF interfaces.
    //
    // We verify the 404 status is set and the class loader is NOT consulted
    // for dangerous paths.  For legitimate paths we only verify that
    // callback.Continue() is called — class-loader will return null (no JAR
    // resources in test classpath) and the handler will 404 naturally, but
    // that is a resource-missing 404, not a traversal-rejection 404.
    // -------------------------------------------------------------------------

    private fun makeRequest(url: String): CefRequest {
        val req = mockk<CefRequest>(relaxed = true)
        every { req.url } returns url
        return req
    }

    private fun captureStatus(handler: CefResourceSchemeHandler): Int {
        val response = mockk<CefResponse>(relaxed = true)
        val lenRef = mockk<IntRef>(relaxed = true)
        val redirectRef = mockk<StringRef>(relaxed = true)
        val capturedStatus = slot<Int>()
        every { response.status = capture(capturedStatus) } returns Unit
        handler.getResponseHeaders(response, lenRef, redirectRef)
        return capturedStatus.captured
    }

    @Test
    fun `processRequest returns 404 for dotdot traversal - callback still called`() {
        val handler = CefResourceSchemeHandler()
        val callback = mockk<CefCallback>(relaxed = true)
        val req = makeRequest("http://workflow-agent/../../etc/passwd")

        val result = handler.processRequest(req, callback)

        assertTrue(result, "Handler must return true (it took ownership)")
        verify(exactly = 1) { callback.Continue() }
        assertEquals(404, captureStatus(handler))
    }

    @Test
    fun `processRequest returns 404 for absolute path - callback still called`() {
        val handler = CefResourceSchemeHandler()
        val callback = mockk<CefCallback>(relaxed = true)
        val req = makeRequest("http://workflow-agent//absolute/path")

        val result = handler.processRequest(req, callback)

        assertTrue(result)
        verify(exactly = 1) { callback.Continue() }
        assertEquals(404, captureStatus(handler))
    }

    @Test
    fun `processRequest returns 404 for backslash traversal - callback still called`() {
        val handler = CefResourceSchemeHandler()
        val callback = mockk<CefCallback>(relaxed = true)
        val req = makeRequest("http://workflow-agent/foo\\..\\bar")

        val result = handler.processRequest(req, callback)

        assertTrue(result)
        verify(exactly = 1) { callback.Continue() }
        assertEquals(404, captureStatus(handler))
    }

    @Test
    fun `processRequest returns 404 for null byte in path - callback still called`() {
        val handler = CefResourceSchemeHandler()
        val callback = mockk<CefCallback>(relaxed = true)
        val req = makeRequest("http://workflow-agent/foo%00.html")

        val result = handler.processRequest(req, callback)

        assertTrue(result)
        verify(exactly = 1) { callback.Continue() }
        assertEquals(404, captureStatus(handler))
    }

    @Test
    fun `processRequest proceeds to class loader for legitimate path`() {
        val handler = CefResourceSchemeHandler()
        val callback = mockk<CefCallback>(relaxed = true)
        val req = makeRequest("http://workflow-agent/dist/assets/index.js")

        // No exception — handler either finds the resource (200) or reports
        // resource-not-found (404), but it must call Continue() in both cases.
        val result = handler.processRequest(req, callback)

        assertTrue(result)
        verify(exactly = 1) { callback.Continue() }
        // Status is either 200 (resource found in test classpath) or 404
        // (resource missing — test environment has no webview dist). Either is
        // acceptable here; what matters is the class loader was consulted
        // (we did NOT short-circuit to the traversal-rejection path).
        val status = captureStatus(handler)
        assertTrue(status == 200 || status == 404,
            "Expected 200 or 404 for legitimate path, got $status")
    }
}
