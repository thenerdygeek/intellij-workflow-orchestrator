package com.workflow.orchestrator.web.service.sanitizer

import com.squareup.moshi.Moshi
import com.workflow.orchestrator.core.web.ContentSanitizer
import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.safety.Safelist
import org.jsoup.select.NodeVisitor

/**
 * Structural HTML sanitizer (no LLM). Strips scripts/styles/iframes/comments,
 * preferentially extracts article-like main content, then post-strips control chars
 * and collapses whitespace.
 *
 * For non-HTML content types: JSON is parsed+reserialized (drops anything invalid);
 * plain text/markdown is passed through with control chars stripped.
 */
class JsoupReadability : ContentSanitizer {

    override fun sanitize(
        rawBytes: ByteArray,
        contentType: String,
        sourceUrl: String,
        maxExtractedChars: Int,
    ): ContentSanitizer.SanitizeResult {
        val raw = rawBytes.toString(Charsets.UTF_8)
        val extracted = when {
            contentType.contains("html", ignoreCase = true) -> extractHtml(raw, sourceUrl)
            contentType.contains("json", ignoreCase = true) -> extractJson(raw)
            else -> stripControlChars(raw)
        }
        val original = extracted.length
        val (truncatedText, truncated) =
            if (original > maxExtractedChars) {
                extracted.take(maxExtractedChars) + "\n[truncated, original was $original chars]" to true
            } else {
                extracted to false
            }
        return ContentSanitizer.SanitizeResult(
            extractedText = truncatedText,
            truncated = truncated,
            originalChars = original,
        )
    }

    private fun extractHtml(raw: String, sourceUrl: String): String {
        val doc: Document = Jsoup.parse(raw, sourceUrl)
        // Drop dangerous elements
        doc.select("script, style, iframe, object, embed, link, meta, noscript").remove()
        // Drop HTML comments recursively (they're not Elements; walk and remove)
        removeComments(doc)
        // Preferentially extract main content
        val main =
            doc.selectFirst("main, article, [role=main]")
                ?: densestTextBlock(doc)
                ?: doc.body()
                ?: doc
        // Cleaner whitelist pass for safety (no attributes survive, no remaining tags execute)
        val cleanedHtml = Jsoup.clean(
            main.outerHtml(),
            Safelist.basic().removeTags("a", "img"),
        )
        // Text-only output
        val text = Jsoup.parse(cleanedHtml).text()
        return collapseWhitespace(stripControlChars(text))
    }

    private fun extractJson(raw: String): String =
        try {
            val moshi = Moshi.Builder().build()
            val any = moshi.adapter(Any::class.java).fromJson(raw)
            moshi.adapter(Any::class.java).indent("  ").toJson(any)
        } catch (_: Exception) {
            "" // malformed JSON returns empty extracted text
        }

    private fun densestTextBlock(doc: Document): Element? =
        doc.select("body *")
            .filter { it.text().length > 200 }
            .maxByOrNull { it.text().length }

    private fun removeComments(doc: Document) {
        val toRemove = mutableListOf<org.jsoup.nodes.Node>()
        doc.traverse(object : NodeVisitor {
            override fun head(node: org.jsoup.nodes.Node, depth: Int) {
                if (node is Comment) toRemove += node
            }
            override fun tail(node: org.jsoup.nodes.Node, depth: Int) {}
        })
        toRemove.forEach { it.remove() }
    }

    private fun stripControlChars(text: String): String =
        text.filter { it == '\n' || it == '\t' || it.code !in 0..31 }
            .filter { it.code != 0 }

    private fun collapseWhitespace(text: String): String =
        text.replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
}
