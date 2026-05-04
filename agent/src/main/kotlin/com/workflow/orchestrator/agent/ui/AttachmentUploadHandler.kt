package com.workflow.orchestrator.agent.ui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.runBlockingCancellable
import com.workflow.orchestrator.agent.session.AttachmentStore
import com.workflow.orchestrator.core.settings.PluginSettings
import org.cef.callback.CefCallback
import org.cef.handler.CefResourceHandler
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse

/**
 * Serves `http://workflow-agent/upload/<sha256>` POST requests for image
 * uploads from the JCEF webview.
 *
 * **Why this path exists.** [com.intellij.ui.jcef.JBCefJSQuery] is string-only
 * (Java string ↔ JS string) and EDT-pinned. Multi-MB image bytes would block
 * the EDT and run a serious risk of bridge truncation or thread starvation.
 * The webview computes a sha256 client-side, sends the bytes over a normal
 * `fetch()`, and this handler — wired into the existing CEF scheme handler
 * factory in [AgentCefPanel] — receives the bytes off-EDT, hands them to the
 * per-session [AttachmentStore], and responds with a small JSON payload.
 *
 * **Per-session isolation.** [attachmentStoreProvider] is invoked on every
 * request so the store always reflects the *currently active* session, never
 * a stale instance captured at construction time. Phase 4 cemented the
 * `AttachmentStore(sessionDir)` contract: each invocation must resolve to the
 * active session's directory or images would land in another session's
 * `attachments/` folder.
 *
 * **Threading.** Runs on the CEF network thread (NOT EDT).
 * [runBlockingCancellable] around [AttachmentStore.store] keeps coroutine
 * cancellation propagating through any IDE-level progress indicator.
 *
 * **Validation.** Size and MIME validation run twice — once client-side in
 * `AttachmentManager.attachFile` (so the user gets an immediate toast and we
 * skip the bridge call) and once here (defense-in-depth, in case the JS code
 * is bypassed). Validation failures return a JSON error body but still
 * produce HTTP 200 — the client checks the body.
 *
 * **Phase 5 of multimodal-agent plan.**
 */
class AttachmentUploadHandler(
    private val attachmentStoreProvider: () -> AttachmentStore?,
    private val settings: PluginSettings,
) : CefResourceHandler {

    companion object {
        private val LOG = Logger.getInstance(AttachmentUploadHandler::class.java)

        /** URL path prefix this handler serves. */
        const val URL_PREFIX = "http://workflow-agent/upload/"

        /** Returns true iff [url] should be routed to this handler. */
        fun matches(url: String): Boolean = url.startsWith(URL_PREFIX)

        /**
         * Pure validation predicate exposed for unit testing — keeps the main
         * [processRequest] path's logic straightforward and lets tests assert
         * on the size/MIME rejection rules without needing live CEF infra.
         */
        fun validate(
            bytes: ByteArray,
            mime: String,
            settings: PluginSettings,
        ): ValidationResult {
            if (!settings.state.enableImageInput) return ValidationResult.Disabled
            if (bytes.size > settings.state.imageMaxBytes) return ValidationResult.SizeExceeded
            if (mime !in settings.state.imageMimeWhitelist) return ValidationResult.MimeNotAllowed
            return ValidationResult.Ok
        }
    }

    /**
     * Pure validation outcome surface used by [validate] — also surfaces in
     * the wire response body (`error` field).
     */
    sealed class ValidationResult(val errorCode: String?) {
        object Ok : ValidationResult(null)
        object Disabled : ValidationResult("disabled")
        object SizeExceeded : ValidationResult("size_exceeded")
        object MimeNotAllowed : ValidationResult("mime_not_allowed")
    }

    private var responseBody: ByteArray = ByteArray(0)
    private var responseStatus: Int = 200
    private var bytesWritten = 0

    override fun processRequest(request: CefRequest, callback: CefCallback): Boolean {
        try {
            val url = request.url ?: run {
                LOG.warn("AttachmentUploadHandler: rejected request with null URL")
                respondError("missing_url", 400)
                callback.Continue()
                return true
            }
            LOG.info("AttachmentUploadHandler: processRequest url=$url method=${request.method}")
            val sha256FromUrl = url
                .substringAfterLast("/upload/")
                .substringBefore("?")
                .substringBefore("#")
            if (sha256FromUrl.isBlank()) {
                respondError("missing_sha256", 400)
                callback.Continue()
                return true
            }

            val mime = request.getHeaderByName("X-Image-Mime") ?: "application/octet-stream"
            val originalFilename = request.getHeaderByName("X-Original-Filename")

            val postData = request.postData ?: run {
                respondError("missing_post_data", 400)
                callback.Continue()
                return true
            }
            // CefPostData.getElementCount() + getElements(Vector) — the JCEF
            // surface mirrors CEF's C++ API: elements are owned by the request
            // and copied into a caller-supplied vector. We only ever expect
            // one element (the raw binary body), so we read the first.
            val elementCount = postData.elementCount
            if (elementCount <= 0) {
                respondError("empty_post_data", 400)
                callback.Continue()
                return true
            }
            val elements = java.util.Vector<org.cef.network.CefPostDataElement>(elementCount)
            postData.getElements(elements)
            val element = elements.firstOrNull() ?: run {
                respondError("empty_post_data", 400)
                callback.Continue()
                return true
            }
            val byteCount = element.bytesCount
            if (byteCount <= 0) {
                respondError("empty_payload", 400)
                callback.Continue()
                return true
            }
            val bytes = ByteArray(byteCount)
            element.getBytes(bytes.size, bytes)

            LOG.info("AttachmentUploadHandler: received ${bytes.size} bytes, mime=$mime, sha256FromUrl=${sha256FromUrl.take(12)}…, filename=$originalFilename")
            // Defense-in-depth validation (UI does this client-side too).
            when (val v = validate(bytes, mime, settings)) {
                is ValidationResult.Ok -> {
                    val store = attachmentStoreProvider() ?: run {
                        LOG.warn("AttachmentUploadHandler: no active session — rejecting upload")
                        respondError("no_active_session", 400)
                        callback.Continue()
                        return true
                    }
                    val ref = runBlockingCancellable { store.store(bytes, mime, originalFilename) }
                    LOG.info("AttachmentUploadHandler: stored sha256=${ref.sha256.take(12)}… size=${ref.size}")
                    // Best-effort sanity-check: client-computed sha256 should match
                    // server-computed. A mismatch typically means the bytes were
                    // corrupted in transit or the JS hasher is buggy. We log but
                    // still accept (server-computed sha is authoritative).
                    if (sha256FromUrl != ref.sha256) {
                        LOG.warn(
                            "AttachmentUploadHandler: sha256 mismatch — " +
                                "client='${sha256FromUrl}' server='${ref.sha256}' " +
                                "(${bytes.size} bytes, mime=$mime)"
                        )
                    }
                    val body = """{"stored":true,"sha256":"${ref.sha256}","size":${ref.size}}"""
                    responseBody = body.toByteArray()
                    responseStatus = 200
                }
                else -> {
                    LOG.warn("AttachmentUploadHandler: validation REJECTED — code=${v.errorCode} bytes=${bytes.size} mime=$mime")
                    respondError(v.errorCode!!, 200)
                }
            }
        } catch (e: Exception) {
            LOG.warn("AttachmentUploadHandler: processRequest failed", e)
            respondError("internal_error", 500)
        }
        callback.Continue()
        return true
    }

    private fun respondError(code: String, status: Int) {
        responseBody = """{"stored":false,"error":"$code"}""".toByteArray()
        responseStatus = status
    }

    override fun getResponseHeaders(
        response: CefResponse,
        responseLength: IntRef,
        redirectUrl: StringRef?,
    ) {
        response.mimeType = "application/json"
        response.status = responseStatus
        response.setHeaderByName("Access-Control-Allow-Origin", "*", true)
        response.setHeaderByName("Cache-Control", "no-store", true)
        responseLength.set(responseBody.size)
    }

    override fun readResponse(
        dataOut: ByteArray,
        bytesToRead: Int,
        bytesRead: IntRef,
        callback: CefCallback,
    ): Boolean {
        if (bytesWritten >= responseBody.size) {
            bytesRead.set(0)
            return false
        }
        val copy = minOf(bytesToRead, responseBody.size - bytesWritten)
        System.arraycopy(responseBody, bytesWritten, dataOut, 0, copy)
        bytesWritten += copy
        bytesRead.set(copy)
        return true
    }

    override fun cancel() {
        // Nothing to clean up — bytes are already on disk before we respond.
    }
}
