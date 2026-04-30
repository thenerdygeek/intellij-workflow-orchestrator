package com.workflow.orchestrator.core.api

/**
 * Describes the document-handling capabilities of the active LLM transport.
 *
 * Different LLM providers expose different content-block types. For example, Anthropic's
 * Claude API supports a `document` content block that lets the model read a raw PDF
 * without prior text extraction. Sourcegraph Cody Enterprise — the plugin's primary
 * transport — has no equivalent documented capability, so v1 always extracts to Markdown
 * client-side before sending to the model.
 *
 * This interface is a forward-compatibility seam: v2 will probe per-transport at startup
 * (mirroring the image-transport probe in [com.workflow.orchestrator.core.services]) and
 * return an appropriate implementation. For now, [DefaultTransportCapabilities] provides
 * the conservative no-op default.
 *
 * @see DefaultTransportCapabilities
 */
interface TransportCapabilities {
    /**
     * `true` if the active LLM transport supports passing PDF bytes as a `document` content block.
     *
     * **ALWAYS `false` in v1:** Sourcegraph Cody Enterprise (the plugin's primary transport) has no
     * documented PDF document-block support. v2 will probe per-transport and may return `true` for
     * transports that confirm the capability at runtime.
     */
    val supportsNativePdfDocumentBlock: Boolean
}

/**
 * Conservative default implementation of [TransportCapabilities] for v1.
 * All document content is extracted to Markdown before being sent to the LLM.
 */
object DefaultTransportCapabilities : TransportCapabilities {
    override val supportsNativePdfDocumentBlock: Boolean = false
}
