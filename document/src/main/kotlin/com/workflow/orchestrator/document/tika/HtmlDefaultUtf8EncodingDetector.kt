package com.workflow.orchestrator.document.tika

import org.apache.tika.detect.EncodingDetector
import org.apache.tika.metadata.Metadata
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Tika [EncodingDetector] that defaults **HTML** content to UTF-8.
 *
 * ## Why this exists (SF-6)
 *
 * The hardened `tika-config.xml` chains encoding detectors in order; the first non-null
 * charset wins. The chain is:
 *
 * 1. `StandardHtmlEncodingDetector` — the WHATWG HTML "encoding sniffing" pre-scan: it
 *    honors a UTF-8/UTF-16 BOM, then an in-document `<meta charset>` / `Content-Type`
 *    charset. For HTML that declares **nothing**, it returns `null`. It also returns
 *    `null` for non-HTML content.
 * 2. **this detector** — returns UTF-8 only for HTML (so undeclared HTML defaults to UTF-8,
 *    per the HTML spec's modern default, instead of falling through to a statistical guess
 *    that mis-read sparse-multibyte UTF-8 pages as windows-1252 → mojibake). Returns `null`
 *    for everything else.
 * 3. `UniversalEncodingDetector` — statistical fallback, now reached only by non-HTML
 *    formats (CSV / plain text / RTF / …), preserving their prior behaviour.
 *
 * It deliberately keys off the resource MIME (`Metadata.CONTENT_TYPE`) rather than sniffing
 * bytes: HTML is identified upstream by [com.workflow.orchestrator.document.service.MimeDetector]
 * and the MIME is set on the [Metadata] by
 * [com.workflow.orchestrator.document.pipeline.TikaXhtmlPipeline] before parsing. When the
 * MIME is absent or non-HTML this detector is a no-op, so the statistical detector still
 * governs those formats.
 *
 * ## Stream contract
 *
 * Per the [EncodingDetector] contract, this method must NOT consume the stream
 * destructively — it returns its decision purely from [Metadata] and never reads bytes, so
 * the mark/reset position is untouched for downstream detectors and the parser.
 *
 * Tika instantiates this via the no-arg constructor declared in `tika-config.xml`.
 */
class HtmlDefaultUtf8EncodingDetector : EncodingDetector {

    override fun detect(input: InputStream?, metadata: Metadata?): Charset? {
        val contentType = metadata?.get(Metadata.CONTENT_TYPE)?.lowercase() ?: return null
        // Match the bare MIME prefix; tolerate a trailing "; charset=…" or other params.
        val isHtml = HTML_MIME_PREFIXES.any { contentType.startsWith(it) }
        return if (isHtml) StandardCharsets.UTF_8 else null
    }

    private companion object {
        val HTML_MIME_PREFIXES = listOf("text/html", "application/xhtml+xml")
    }
}
