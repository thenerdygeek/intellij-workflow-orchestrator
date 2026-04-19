package com.workflow.orchestrator.core.http

import com.intellij.openapi.diagnostic.Logger
import okhttp3.Interceptor
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger

/**
 * OkHttp [Interceptor] that writes verbatim HTTP traffic to disk when tracing is enabled.
 *
 * For each LLM call, up to three files are written to `traceDir()`:
 *
 * - `{reqId}.pre-sanitize.json` — serialized pre-sanitize messages (written by [PreSanitizeDumper]
 *   before this interceptor fires, correlated via the `X-Workflow-Trace-Id` header)
 * - `{reqId}.request.http`  — request line + headers + body going out
 * - `{reqId}.response.http` — status line + response headers + body bytes (SSE verbatim)
 *
 * The [ForwardingSource] wrapper makes response-body capture **transparent** — SSE streaming
 * continues to work exactly as before because every byte is mirrored, not buffered-then-replayed.
 *
 * `Authorization` and `Cookie` headers are always redacted before writing (hardcoded, not
 * configurable). If [RawApiTraceConfig.redactPromptBody] is true, the request body is passed
 * through [bodyRedactor] before writing (inject `CredentialRedactor::redact` from `:agent` to
 * enable; defaults to identity to avoid a circular compile dependency on `:core`).
 *
 * Retention: at the start of each `intercept()`, dated directories under the `raw-api/` parent
 * that are older than [RawApiTraceConfig.retentionDays] days are deleted.
 *
 * @param config        Trace configuration singleton.
 * @param traceDir      Factory that resolves the dated output directory per request.
 * @param bodyRedactor  Optional function to sanitize request bodies before writing.
 *                      Defaults to identity.
 */
class RawApiTraceInterceptor(
    private val config: RawApiTraceConfig = RawApiTraceConfig,
    private val traceDir: () -> File,
    private val bodyRedactor: (String) -> String = { it }
) : Interceptor {

    private val log = Logger.getInstance(RawApiTraceInterceptor::class.java)

    /** Per-interceptor-instance monotonic counter — `HHmmss-001`, `HHmmss-002`, … */
    private val callCounter = AtomicInteger(0)

    private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmmss")

    // ── Retention cleanup ────────────────────────────────────────────────────

    private fun pruneOldTraceDirs(traceDirToday: File) {
        val rawApiParent = traceDirToday.parentFile ?: return
        if (!rawApiParent.isDirectory) return
        val cutoff = LocalDate.now().minusDays(config.retentionDays.toLong())
        rawApiParent.listFiles()?.forEach { dir ->
            if (dir.isDirectory) {
                try {
                    val dirDate = LocalDate.parse(dir.name)
                    if (dirDate.isBefore(cutoff)) {
                        dir.deleteRecursively()
                        log.debug("[RawTrace] Pruned old trace dir: ${dir.name}")
                    }
                } catch (_: Exception) {
                    // Directory name is not a valid date — skip.
                }
            }
        }
    }

    // ── Interceptor entry point ──────────────────────────────────────────────

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // Fast path — tracing disabled
        if (!config.shouldTrace()) {
            return chain.proceed(request)
        }

        val dir = traceDir()
        pruneOldTraceDirs(dir)

        val timeStr = LocalTime.now().format(TIME_FMT)
        val counter = callCounter.incrementAndGet()
        val reqId = "$timeStr-${counter.toString().padStart(3, '0')}"

        // Honour a caller-supplied trace ID (set by SourcegraphChatClient to correlate
        // the pre-sanitize dump with the request/response files).
        val effectiveReqId = request.header(TRACE_HEADER) ?: reqId

        dir.mkdirs()

        // ── Capture and rebuild request ──────────────────────────────────────
        val outgoingRequest = writeRequestFile(request, effectiveReqId, dir)

        // ── Execute the call ─────────────────────────────────────────────────
        val response: Response = chain.proceed(outgoingRequest)

        // ── Wrap response body ───────────────────────────────────────────────
        val originalBody = response.body ?: run {
            config.decrementBurst()
            return response
        }

        val responseFile = File(dir, "$effectiveReqId.response.http")
        val writer = try {
            OutputStreamWriter(FileOutputStream(responseFile), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            log.warn("[RawTrace] Cannot open response file: ${e.message}")
            config.decrementBurst()
            return response
        }

        // Write HTTP status line + response headers first
        try {
            writer.write("HTTP/${response.protocol} ${response.code} ${response.message}\r\n")
            response.headers.forEach { (name, value) ->
                writer.write("$name: $value\r\n")
            }
            writer.write("\r\n")
            writer.flush()
        } catch (e: Exception) {
            log.warn("[RawTrace] Failed to write response headers: ${e.message}")
        }

        val mirroringSource = MirroringSource(
            delegate = originalBody.source(),
            writer = writer,
            maxBytes = config.maxBodyBytes,
            onClose = {
                config.decrementBurst()
                log.debug("[RawTrace] Response stream closed for $effectiveReqId")
            }
        )

        // Wrap in a BufferedSource (Okio 3: buffer() top-level extension on Source).
        // ResponseBody.create(contentType, length, BufferedSource) is the OkHttp 4 API.
        @Suppress("DEPRECATION")
        val mirroredBody = okhttp3.ResponseBody.create(
            originalBody.contentType(),
            originalBody.contentLength(),
            mirroringSource.buffer()
        )

        return response.newBuilder().body(mirroredBody).build()
    }

    // ── Request file writer ──────────────────────────────────────────────────

    private fun writeRequestFile(
        request: okhttp3.Request,
        reqId: String,
        dir: File
    ): okhttp3.Request {
        val requestFile = File(dir, "$reqId.request.http")
        val newBuilder = request.newBuilder()

        var rawBody = ""
        val originalBody = request.body
        if (originalBody != null) {
            val buffer = Buffer()
            try {
                originalBody.writeTo(buffer)
                rawBody = buffer.readUtf8()
            } catch (e: IOException) {
                rawBody = "[Failed to buffer request body: ${e.message}]"
            }
            // Rebuild the request body from the snapshot so OkHttp can still send it.
            val rebuiltBody = rawBody.toByteArray(StandardCharsets.UTF_8)
                .toRequestBody(originalBody.contentType())
            newBuilder.method(request.method, rebuiltBody)
        }

        val bodyToWrite = if (config.redactPromptBody) bodyRedactor(rawBody) else rawBody
        val (finalBody, truncated) = truncate(bodyToWrite, config.maxBodyBytes)

        try {
            OutputStreamWriter(FileOutputStream(requestFile), StandardCharsets.UTF_8).use { w ->
                w.write("${request.method} ${request.url.encodedPath} HTTP/1.1\r\n")
                w.write("Host: ${request.url.host}\r\n")
                request.headers.forEach { (name, value) ->
                    val line = when (name.lowercase()) {
                        "authorization", "cookie" -> "$name: ***REDACTED***"
                        else -> "$name: $value"
                    }
                    w.write("$line\r\n")
                }
                if (originalBody != null) {
                    w.write("Content-Length: ${rawBody.toByteArray(StandardCharsets.UTF_8).size}\r\n")
                }
                w.write("\r\n")
                w.write(finalBody)
                if (truncated) {
                    w.write("\n---TRUNCATED AT ${config.maxBodyBytes} BYTES---\n")
                }
            }
        } catch (e: Exception) {
            log.warn("[RawTrace] Failed to write request file: ${e.message}")
        }

        return newBuilder.build()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun truncate(text: String, maxBytes: Long): Pair<String, Boolean> {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        return if (bytes.size <= maxBytes) {
            text to false
        } else {
            String(bytes, 0, maxBytes.toInt(), StandardCharsets.UTF_8) to true
        }
    }

    // ── MirroringSource ──────────────────────────────────────────────────────

    /**
     * Okio [ForwardingSource] that mirrors every [read] call into [writer].
     *
     * Bytes flow through unchanged to the real consumer (SSE parser etc.).
     * Once [maxBytes] are mirrored, a truncation marker is written and further
     * bytes are still forwarded but no longer mirrored.
     *
     * [onClose] is called exactly once when the source is closed.
     */
    private inner class MirroringSource(
        delegate: okio.Source,
        private val writer: OutputStreamWriter,
        private val maxBytes: Long,
        private val onClose: () -> Unit
    ) : ForwardingSource(delegate) {

        private var bytesWritten = 0L
        private var truncationMarkerWritten = false
        private var closed = false

        override fun read(sink: Buffer, byteCount: Long): Long {
            val bytesRead = super.read(sink, byteCount)
            if (bytesRead > 0) {
                val remaining = maxBytes - bytesWritten
                if (remaining > 0) {
                    // Peek at the bytes just appended to sink.
                    // sink.copy() gives a snapshot — we skip bytes before this read's window.
                    val copyBuffer = sink.copy()
                    val skipBytes = sink.size - bytesRead
                    if (skipBytes > 0) copyBuffer.skip(skipBytes)
                    val toWrite = minOf(bytesRead, remaining)
                    val chunk = copyBuffer.readByteArray(toWrite)
                    try {
                        writer.write(String(chunk, StandardCharsets.UTF_8))
                        writer.flush()
                    } catch (_: Exception) { /* Best-effort mirror */ }
                    bytesWritten += toWrite
                } else if (!truncationMarkerWritten) {
                    truncationMarkerWritten = true
                    try {
                        writer.write("\n---TRUNCATED AT $maxBytes BYTES---\n")
                        writer.flush()
                    } catch (_: Exception) { /* Best-effort */ }
                }
            }
            return bytesRead
        }

        override fun close() {
            if (!closed) {
                closed = true
                try { writer.close() } catch (_: Exception) { /* Best-effort */ }
                onClose()
            }
            super.close()
        }
    }

    companion object {
        /** Request header used to correlate the pre-sanitize dump with request/response files. */
        const val TRACE_HEADER = "X-Workflow-Trace-Id"
    }
}
