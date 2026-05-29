package com.workflow.orchestrator.document.pipeline

import org.apache.tika.parser.html.DefaultHtmlMapper
import org.apache.tika.parser.html.HtmlMapper

/**
 * NAV-3 — a Tika [HtmlMapper] that strips non-content "chrome" regions from HTML while
 * delegating every other decision to Tika's [DefaultHtmlMapper].
 *
 * ## Why a mapper, not a SAX-level subtree skip
 *
 * Tika's [DefaultHtmlMapper] is a *safe-listing* mapper: it keeps a known set of structural
 * elements and silently **drops the tag (but keeps the character content)** for everything
 * else. `<nav>`, `<header>`, `<footer>`, and `<aside>` are NOT in its safe-element set, so
 * Tika never emits a `startElement("nav")` — it just lets the chrome *text* flow through as
 * orphaned characters. That is exactly why the Wikipedia sidebar / footer text ("Contribute",
 * "Appearance", "English", "Print/export") leaked into the body and got promoted to section
 * anchors by the standalone-heading detector in
 * [com.workflow.orchestrator.document.sax.DocumentBlockHandler].
 *
 * Because the tag never reaches our SAX handler, we cannot skip the subtree there. The correct
 * seam is [HtmlMapper.isDiscardElement]: when it returns `true`, Tika discards the element AND
 * all of its descendant content (text included) before SAX events are produced — the same
 * mechanism Tika already uses for `<script>` / `<style>`.
 *
 * ## Conservatism (guard against over-stripping)
 *
 * Only the four HTML5 sectioning-chrome elements are discarded:
 * - `nav`    — navigation menus, language lists, breadcrumb bars (incl. `role="navigation"`,
 *              which authors place on `<nav>`/`<div>`; the element-name match covers `<nav>`).
 * - `header` — masthead / site banner (the page chrome header, NOT a table `<thead>`).
 * - `footer` — site footer, copyright/legal boilerplate.
 * - `aside`  — sidebars, pull-quotes, related-links rails.
 *
 * Real article content lives in `<main>`, `<article>`, `<section>`, `<div>`, `<table>`,
 * `<p>`, `<h1>`–`<h6>`, `<ul>`/`<ol>` — none of which are touched. This is a tag-name strip,
 * NOT a content-density / readability algorithm (that broader Boilerpipe-style extraction is
 * explicitly out of scope). The trade-off is deliberately to **under-strip**: a chrome region
 * authored without these semantic tags (e.g. a bare `<div class="sidebar">`) is left alone
 * rather than risk discarding real content via fragile class-name heuristics.
 *
 * ## Scope
 *
 * Applied by [TikaXhtmlPipeline] only when the source MIME is HTML. The mapper is consulted
 * exclusively by Tika's HtmlParser, so it is inert for non-HTML formats, but the pipeline
 * gates it anyway to keep the behaviour explicit.
 */
internal class ChromeStrippingHtmlMapper(
    private val delegate: HtmlMapper = DefaultHtmlMapper(),
) : HtmlMapper {

    override fun mapSafeElement(name: String?): String? = delegate.mapSafeElement(name)

    override fun isDiscardElement(name: String?): Boolean {
        if (name != null && name.lowercase() in CHROME_ELEMENTS) return true
        return delegate.isDiscardElement(name)
    }

    override fun mapSafeAttribute(elementName: String?, attributeName: String?): String? =
        delegate.mapSafeAttribute(elementName, attributeName)

    private companion object {
        /**
         * HTML5 sectioning-chrome elements whose entire subtree (text included) is discarded.
         * Deliberately minimal — see the class KDoc "Conservatism" note.
         */
        val CHROME_ELEMENTS = setOf("nav", "header", "footer", "aside")
    }
}
