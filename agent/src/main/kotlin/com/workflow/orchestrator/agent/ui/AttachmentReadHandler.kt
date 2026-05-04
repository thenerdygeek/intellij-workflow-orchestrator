package com.workflow.orchestrator.agent.ui

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.agent.session.AttachmentStore
import org.cef.callback.CefCallback
import org.cef.handler.CefResourceHandler
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse

/**
 * Serves `GET http://workflow-agent/attachments/<sha256>` so the webview can
 * render `<img src="http://workflow-agent/attachments/{sha}">` thumbnails
 * inside USER_MESSAGE bubbles without piping multi-MB byte payloads through
 * the EDT-pinned [com.intellij.ui.jcef.JBCefJSQuery] string bridge.
 *
 * **Why a separate handler from [AttachmentUploadHandler]?** Upload is POST
 * and per-session (writes into the active session's `attachments/` dir);
 * read is GET and content-addressed by sha256 only. Splitting keeps each
 * handler's contract focused.
 *
 * **MIME inference.** The bytes on disk live at `<sha256>.<ext>` where ext
 * was chosen by [AttachmentStore.mimeToExtension] at write-time. We invert
 * the mapping at read-time so the response carries the right
 * `Content-Type`. JCEF's renderer needs that header to decode and display
 * the image; without it the `<img>` shows a broken-image icon.
 *
 * **Threading.** Runs on the CEF network thread (NOT EDT). Calls
 * [AttachmentStore.readBlocking] (synchronous JDK file I/O) — same pattern
 * as the upload handler.
 */
class AttachmentReadHandler(
    private val attachmentStoreProvider: () -> AttachmentStore?,
) : CefResourceHandler {

    companion object {
        private val LOG = Logger.getInstance(AttachmentReadHandler::class.java)

        /** URL path prefix this handler serves. */
        const val URL_PREFIX = "http://workflow-agent/attachments/"

        /** Returns true iff [url] should be routed to this handler. */
        fun matches(url: String): Boolean = url.startsWith(URL_PREFIX)

        /** Reverse the on-disk extension → wire `Content-Type` mapping. */
        internal fun mimeFromExtension(ext: String): String = when (ext.lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            "gif" -> "image/gif"
            else -> "application/octet-stream"
        }
    }

    private var responseBytes: ByteArray = ByteArray(0)
    private var responseStatus: Int = 200
    private var responseMime: String = "application/octet-stream"
    private var bytesWritten = 0

    override fun processRequest(request: CefRequest, callback: CefCallback): Boolean {
        try {
            val url = request.url ?: run {
                LOG.warn("AttachmentReadHandler: rejected null URL")
                respond404()
                callback.Continue()
                return true
            }
            // Strip query/fragment defensively.
            val sha = url
                .substringAfterLast("/attachments/")
                .substringBefore("?")
                .substringBefore("#")
            // sha256 hex is 64 lowercase chars — defend against traversal/corruption.
            if (!sha.matches(Regex("^[a-f0-9]{64}$"))) {
                LOG.warn("AttachmentReadHandler: rejected malformed sha256='${sha.take(80)}'")
                respond404()
                callback.Continue()
                return true
            }
            val store = attachmentStoreProvider() ?: run {
                LOG.warn("AttachmentReadHandler: no active session for sha256=${sha.take(12)}…")
                respond404()
                callback.Continue()
                return true
            }
            val bytes = store.readBlocking(sha) ?: run {
                LOG.info("AttachmentReadHandler: not found sha256=${sha.take(12)}…")
                respond404()
                callback.Continue()
                return true
            }
            // Look up the on-disk extension so we can set Content-Type.
            // AttachmentStore.read fetches by sha-prefix match; we re-derive
            // ext by listing the same directory. Cheap (single dir scan).
            val ext = store.findExtensionForBlocking(sha) ?: "bin"
            responseBytes = bytes
            responseMime = mimeFromExtension(ext)
            responseStatus = 200
            LOG.info("AttachmentReadHandler: served sha256=${sha.take(12)}… ${bytes.size}B mime=$responseMime")
        } catch (e: Exception) {
            LOG.warn("AttachmentReadHandler: processRequest failed", e)
            respond404()
        }
        callback.Continue()
        return true
    }

    private fun respond404() {
        responseBytes = ByteArray(0)
        responseStatus = 404
        responseMime = "text/plain"
    }

    override fun getResponseHeaders(
        response: CefResponse,
        responseLength: IntRef,
        redirectUrl: StringRef?,
    ) {
        response.mimeType = responseMime
        response.status = responseStatus
        response.setHeaderByName("Cache-Control", "no-store", true)
        // Same-origin only; CSP already restricts; this is for completeness.
        response.setHeaderByName("Access-Control-Allow-Origin", "*", true)
        responseLength.set(responseBytes.size)
    }

    override fun readResponse(
        dataOut: ByteArray,
        bytesToRead: Int,
        bytesRead: IntRef,
        callback: CefCallback,
    ): Boolean {
        if (bytesWritten >= responseBytes.size) {
            bytesRead.set(0)
            return false
        }
        val copy = minOf(bytesToRead, responseBytes.size - bytesWritten)
        System.arraycopy(responseBytes, bytesWritten, dataOut, 0, copy)
        bytesWritten += copy
        bytesRead.set(copy)
        return true
    }

    override fun cancel() {
        // No-op — bytes are already on disk; no transient state to clean up.
    }
}
