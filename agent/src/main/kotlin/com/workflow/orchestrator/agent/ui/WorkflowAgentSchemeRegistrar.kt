package com.workflow.orchestrator.agent.ui

import org.cef.CefApp
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefSchemeHandlerFactory
import org.cef.handler.CefResourceHandler
import org.cef.network.CefRequest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Single source of truth for the `http://workflow-agent` scheme registration.
 *
 * Background: [CefApp.registerSchemeHandlerFactory] is JVM-global — registering
 * a second factory for the same `(scheme, domain)` SILENTLY REPLACES the first.
 * Pre-fix, three classes ([AgentCefPanel], [com.workflow.orchestrator.agent.ui.plan.AgentPlanEditor],
 * [AgentVisualizationEditor]) each called `registerSchemeHandlerFactory` with
 * their own factory. Whichever ran last won, and the static-asset-only
 * factories from the plan/viz editors stomped on the upload-aware factory in
 * [AgentCefPanel]. Result: image upload `/upload/<sha256>` POSTs 404'd because
 * they hit [CefResourceSchemeHandler] instead of [AttachmentUploadHandler].
 *
 * Post-fix: every caller invokes [ensureRegistered]. We register exactly once.
 * The factory dispatches upload URLs (matching [AttachmentUploadHandler.matches])
 * to whatever upload handler factory the active chat panel installed via
 * [setUploadHandlerFactory]; everything else (and uploads when no chat panel
 * is mounted) goes to a fresh [CefResourceSchemeHandler], which serves bundled
 * webview assets and 404s URLs it doesn't recognize.
 *
 * **Why a factory, not a single handler?** [CefResourceHandler] instances
 * carry per-request state (response body bytes, write-position counters).
 * CEF invokes the scheme handler factory once per request expecting a fresh
 * handler — reusing one instance across concurrent uploads would corrupt
 * response bodies. [setUploadHandlerFactory] therefore takes a `() -> handler`
 * factory closure that the chat panel constructs once with its session-bound
 * `attachmentStoreProvider`; the dispatching factory invokes it per request.
 */
object WorkflowAgentSchemeRegistrar {

    private val registered = AtomicBoolean(false)
    private val uploadHandlerFactoryRef = AtomicReference<(() -> CefResourceHandler)?>()
    private val readHandlerFactoryRef = AtomicReference<(() -> CefResourceHandler)?>()

    /**
     * Idempotent. First call registers the dispatching factory with [CefApp];
     * subsequent calls are no-ops.
     */
    fun ensureRegistered() {
        if (registered.compareAndSet(false, true)) {
            CefApp.getInstance().registerSchemeHandlerFactory(
                CefResourceSchemeHandler.SCHEME,
                CefResourceSchemeHandler.AUTHORITY,
                DispatchingFactory,
            )
        }
    }

    /**
     * Install the upload handler factory that produces a fresh handler per
     * `/upload/<sha256>` request. The chat panel ([AgentCefPanel]) calls this
     * with a closure that captures its session-bound `attachmentStoreProvider`.
     *
     * Pass `null` to detach (e.g. when the chat panel is disposed). When
     * detached, upload requests are served by the static handler, which will
     * 404 — acceptable because uploads only happen from the chat input.
     */
    fun setUploadHandlerFactory(factory: (() -> CefResourceHandler)?) {
        uploadHandlerFactoryRef.set(factory)
    }

    /**
     * Install the read-handler factory that produces a fresh handler per
     * `GET /attachments/<sha256>` request. The chat panel calls this with a
     * closure that captures its session-bound `attachmentStoreProvider`.
     * Pass `null` to detach.
     */
    fun setReadHandlerFactory(factory: (() -> CefResourceHandler)?) {
        readHandlerFactoryRef.set(factory)
    }

    @org.jetbrains.annotations.VisibleForTesting
    internal object DispatchingFactory : CefSchemeHandlerFactory {
        override fun create(
            browser: CefBrowser?,
            frame: CefFrame?,
            schemeName: String?,
            request: CefRequest?,
        ): CefResourceHandler = dispatch(request?.url)

        /**
         * URL-only dispatch helper, exposed so tests can drive the routing
         * contract without instantiating a [CefRequest] (the JCEF JPMS module
         * doesn't open `org.cef.network` to unnamed modules, so MockK can't
         * subclass it).
         */
        @org.jetbrains.annotations.VisibleForTesting
        internal fun dispatch(url: String?): CefResourceHandler {
            if (url == null) return CefResourceSchemeHandler()
            return when {
                AttachmentUploadHandler.matches(url) ->
                    uploadHandlerFactoryRef.get()?.invoke() ?: CefResourceSchemeHandler()
                AttachmentReadHandler.matches(url) ->
                    readHandlerFactoryRef.get()?.invoke() ?: CefResourceSchemeHandler()
                else -> CefResourceSchemeHandler()
            }
        }
    }
}
