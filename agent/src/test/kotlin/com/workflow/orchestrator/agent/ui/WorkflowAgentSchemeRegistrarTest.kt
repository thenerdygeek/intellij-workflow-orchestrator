package com.workflow.orchestrator.agent.ui

import org.cef.callback.CefCallback
import org.cef.handler.CefResourceHandler
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [WorkflowAgentSchemeRegistrar].
 *
 * The registrar's [WorkflowAgentSchemeRegistrar.ensureRegistered] call goes
 * through [org.cef.CefApp.getInstance], which requires a live CEF runtime —
 * we therefore exercise only the dispatching factory in isolation here.
 * The full registration-with-CEF path is exercised by the runIde manual smoke
 * test (image attachment via plus-button / paste / drag-drop must work after
 * either the plan editor or visualization tab has been opened).
 *
 * The dispatch logic this test pins is the regression-fix contract:
 *   - URLs matching [AttachmentUploadHandler.matches] go to the installed
 *     upload handler factory's output (a fresh handler per request).
 *   - Everything else goes to a fresh [CefResourceSchemeHandler].
 *   - When no upload handler factory is installed, even upload URLs fall back
 *     to the static handler (which will 404 — acceptable, uploads only happen
 *     from the chat input).
 *
 * Tests drive the URL-only [WorkflowAgentSchemeRegistrar.DispatchingFactory.dispatch]
 * helper instead of building a [org.cef.network.CefRequest] mock — JCEF's
 * `org.cef.network` and `org.cef.handler` modules are not opened to unnamed
 * modules, so MockK can't subclass `CefRequest` / `CefResourceHandler`. The
 * full `create(browser, frame, schemeName, request)` path delegates to
 * `dispatch(request?.url)` in one line, so the URL-only coverage is sufficient.
 *
 * Identifies the upload handler via a hand-written [SentinelHandler] stub so
 * `assertSame` can confirm routing without instantiating a JCEF-module type.
 */
class WorkflowAgentSchemeRegistrarTest {

    private class SentinelHandler : CefResourceHandler {
        override fun processRequest(request: CefRequest?, callback: CefCallback?) = false
        override fun getResponseHeaders(response: CefResponse?, responseLength: IntRef?, redirectUrl: StringRef?) = Unit
        override fun readResponse(dataOut: ByteArray?, bytesToRead: Int, bytesRead: IntRef?, callback: CefCallback?) = false
        override fun cancel() = Unit
    }

    @AfterEach
    fun cleanup() {
        WorkflowAgentSchemeRegistrar.setUploadHandlerFactory(null)
    }

    @Test
    fun `dispatching factory routes upload URLs to the installed upload handler factory`() {
        val uploadHandler = SentinelHandler()
        WorkflowAgentSchemeRegistrar.setUploadHandlerFactory { uploadHandler }

        val handler = WorkflowAgentSchemeRegistrar.DispatchingFactory.dispatch(
            "http://workflow-agent/upload/abc123"
        )

        assertSame(uploadHandler, handler)
    }

    @Test
    fun `dispatching factory invokes the upload handler factory once per request`() {
        var calls = 0
        WorkflowAgentSchemeRegistrar.setUploadHandlerFactory {
            calls += 1
            SentinelHandler()
        }

        val first = WorkflowAgentSchemeRegistrar.DispatchingFactory.dispatch(
            "http://workflow-agent/upload/abc123"
        )
        val second = WorkflowAgentSchemeRegistrar.DispatchingFactory.dispatch(
            "http://workflow-agent/upload/abc123"
        )

        // Per-request handler construction is the contract — CefResourceHandler
        // instances carry per-request state (response body, write-position).
        // Reusing one across concurrent requests would corrupt response bodies.
        assertNotNull(first)
        assertNotNull(second)
        assert(calls == 2) { "factory should be invoked once per request, was invoked $calls times" }
    }

    @Test
    fun `dispatching factory routes non-upload URLs to a fresh CefResourceSchemeHandler`() {
        WorkflowAgentSchemeRegistrar.setUploadHandlerFactory(null)

        val handler = WorkflowAgentSchemeRegistrar.DispatchingFactory.dispatch(
            "http://workflow-agent/index.html"
        )

        assertTrue(handler is CefResourceSchemeHandler)
    }

    @Test
    fun `dispatching factory falls back to static handler when no upload factory is installed`() {
        WorkflowAgentSchemeRegistrar.setUploadHandlerFactory(null)

        val handler = WorkflowAgentSchemeRegistrar.DispatchingFactory.dispatch(
            "http://workflow-agent/upload/abc123"
        )

        assertTrue(
            handler is CefResourceSchemeHandler,
            "Should fall back to static handler when no upload factory is set",
        )
    }

    @Test
    fun `dispatching factory falls back to static handler when request url is null`() {
        WorkflowAgentSchemeRegistrar.setUploadHandlerFactory { SentinelHandler() }

        val handler = WorkflowAgentSchemeRegistrar.DispatchingFactory.dispatch(null)

        assertTrue(handler is CefResourceSchemeHandler)
    }
}
