package com.workflow.orchestrator.agent.ui

import com.intellij.openapi.diagnostic.Logger
import org.cef.callback.CefCallback
import org.cef.handler.CefResourceHandler
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Serves resources from the plugin JAR's webview/ directory.
 * Registered for http://workflow-agent/ so <script src="lib/marked.min.js">
 * resolves to resources/webview/lib/marked.min.js inside the JAR.
 * Eliminates all CDN dependencies — plugin works 100% offline.
 */
class CefResourceSchemeHandler : CefResourceHandler {

    companion object {
        private val LOG = Logger.getInstance(CefResourceSchemeHandler::class.java)
        const val SCHEME = "http"
        const val AUTHORITY = "workflow-agent"
        const val BASE_URL = "$SCHEME://$AUTHORITY/"

        private val MIME_TYPES = mapOf(
            "html" to "text/html",
            "js" to "application/javascript",
            "css" to "text/css",
            "json" to "application/json",
            "svg" to "image/svg+xml",
            "png" to "image/png",
            "woff2" to "font/woff2",
            "woff" to "font/woff",
            "ttf" to "font/ttf"
        )
    }

    private var inputStream: InputStream? = null
    private var mimeType: String = "application/octet-stream"
    private var responseLength: Int = 0
    private var statusCode: Int = 200

    override fun processRequest(request: CefRequest, callback: CefCallback): Boolean {
        val url = request.url ?: return false
        val path = url.removePrefix(BASE_URL).takeIf { it.isNotBlank() } ?: "index.html"

        val resourcePath = "webview/dist/$path"
        val bytes = try {
            javaClass.classLoader.getResourceAsStream(resourcePath)?.readBytes()
        } catch (e: Exception) {
            LOG.debug("CefResourceSchemeHandler: failed to load $resourcePath: ${e.message}")
            null
        }

        if (bytes != null) {
            inputStream = ByteArrayInputStream(bytes)
            responseLength = bytes.size
            mimeType = MIME_TYPES[path.substringAfterLast('.').lowercase()] ?: "application/octet-stream"
            statusCode = 200
        } else {
            LOG.warn("CefResourceSchemeHandler: resource not found: $resourcePath")
            inputStream = ByteArrayInputStream(ByteArray(0))
            responseLength = 0
            statusCode = 404
        }

        callback.Continue()
        return true
    }

    override fun getResponseHeaders(response: CefResponse, responseLength: IntRef, redirectUrl: StringRef?) {
        response.mimeType = mimeType
        response.status = statusCode
        responseLength.set(this.responseLength)

        // CORS: Allow sandboxed iframes (origin "null") to load scripts/styles.
        // Safe because we only serve bundled static assets from the plugin JAR,
        // and CSP connect-src:'none' prevents any outbound network requests.
        response.setHeaderByName("Access-Control-Allow-Origin", "*", true)

        if (mimeType == "text/html") {
            response.setHeaderByName(
                "Content-Security-Policy",
                "default-src 'self' $SCHEME://$AUTHORITY; " +
                    "script-src 'self' 'unsafe-inline' 'unsafe-eval' $SCHEME://$AUTHORITY; " +
                    "style-src 'self' 'unsafe-inline' $SCHEME://$AUTHORITY; " +
                    "img-src 'self' data: blob: $SCHEME://$AUTHORITY; " +
                    "font-src 'self' $SCHEME://$AUTHORITY; " +
                    "connect-src 'none'; " +
                    "frame-src 'self' $SCHEME://$AUTHORITY;",
                true
            )
        }
    }

    override fun readResponse(dataOut: ByteArray, bytesToRead: Int, bytesRead: IntRef, callback: CefCallback): Boolean {
        val stream = inputStream ?: return false
        val available = stream.available()
        if (available <= 0) {
            bytesRead.set(0)
            return false
        }
        val read = stream.read(dataOut, 0, minOf(bytesToRead, available))
        if (read <= 0) {
            bytesRead.set(0)
            return false
        }
        bytesRead.set(read)
        return true
    }

    override fun cancel() {
        inputStream?.close()
        inputStream = null
    }
}
