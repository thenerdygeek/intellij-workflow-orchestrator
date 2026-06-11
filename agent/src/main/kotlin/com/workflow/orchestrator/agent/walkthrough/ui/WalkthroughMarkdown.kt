package com.workflow.orchestrator.agent.walkthrough.ui

import com.intellij.lang.Language
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.richcopy.HtmlSyntaxInfoUtil
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser

/**
 * Markdown -> HTML for the callout popup body, via the JetBrains `intellij-markdown`
 * library that ships with the platform (no webview round-trip; the popup is Swing).
 *
 * The popup body is a [javax.swing.JEditorPane] with [com.intellij.util.ui.HTMLEditorKitBuilder.simple],
 * which honors inline `style=` attributes and an inline `<style>` block, but IGNORES `class=`.
 * So all styling here is emitted inline:
 *   - a small `<style>` block tightens `<pre>`/`<code>` (IDE editor font, code background, padding);
 *   - fenced code blocks get per-token color `<span style="color:…">` via the IDE lexer
 *     ([HtmlSyntaxInfoUtil]) when the fence language maps to a known FileType.
 */
object WalkthroughMarkdown {
    private val LOG = Logger.getInstance(WalkthroughMarkdown::class.java)
    private val flavour = GFMFlavourDescriptor()

    /** Matches an intellij-markdown fenced block: `<pre><code class="language-x">…</code></pre>` (or no class). */
    private val FENCED_BLOCK = Regex(
        """<pre>\s*<code(?:\s+class="language-([^"]*)")?\s*>(.*?)</code>\s*</pre>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    /** 0-arg path: plain markdown -> HTML (no syntax-highlighting). Kept for the headless unit test. */
    fun toHtml(markdown: String): String = document(render(markdown))

    /** Project-aware path: same markdown, with IDE-themed colored code blocks. */
    fun toHtml(markdown: String, project: Project): String =
        document(highlightCodeBlocks(render(markdown), project))

    /**
     * Wrap body content in a full document with the `<style>` in `<head>`. Swing's
     * HTML 3.2 [javax.swing.text.html.HTMLEditorKit] only applies a `<style>` block that
     * lives in `<head>` — a `<style>` inside `<body>` is rendered as literal text (the
     * `body { margin: 0 }…` leak). So the document shell is built here, not by the caller.
     */
    private fun document(body: String): String =
        "<html><head>${baseStyle()}</head><body>$body</body></html>"

    private fun render(markdown: String): String {
        val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(markdown)
        return HtmlGenerator(markdown, tree, flavour).generateHtml()
    }

    /** Inline `<style>` so even non-highlighted code reads as code and the box is less bland. */
    private fun baseStyle(): String {
        val codeBg = hex(JBColor(CODE_BG_LIGHT, CODE_BG_DARK))
        return "<style>" +
            "body { margin: 0; }" +
            "pre { background: $codeBg; padding: 6px 8px; margin: 4px 0; }" +
            "code { font-family: '${editorFontName()}', monospace; background: $codeBg; }" +
            "pre code { background: transparent; }" +
            "</style>"
    }

    /** IDE editor font when an Application is live; a plain "monospace" fallback otherwise (unit tests). */
    private fun editorFontName(): String = try {
        EditorColorsManager.getInstance().globalScheme.editorFontName
    } catch (e: Exception) {
        LOG.debug("editor font unavailable (no Application) — using monospace fallback", e)
        "monospace"
    }

    /**
     * Replace each fenced block's plain code with IDE-lexer-colored HTML when the language
     * resolves to a known FileType. Falls back to the original (styled-but-uncolored) block
     * on any failure — highlighting is purely cosmetic and must never break the callout.
     */
    private fun highlightCodeBlocks(html: String, project: Project): String =
        FENCED_BLOCK.replace(html) { match ->
            val lang = match.groupValues[1]
            val encodedCode = match.groupValues[2]
            val colored = colorize(lang, decodeHtml(encodedCode), project)
            if (colored != null) "<pre>$colored</pre>" else match.value
        }

    private fun colorize(lang: String, code: String, project: Project): String? {
        if (lang.isBlank()) return null
        val language = resolveLanguage(lang) ?: return null
        return try {
            val sb = StringBuilder()
            HtmlSyntaxInfoUtil.appendHighlightedByLexerAndEncodedAsHtmlCodeSnippet(
                sb,
                project,
                language,
                code,
                DEFAULT_FONT_SCALE,
            )
            sb.toString().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            LOG.debug("walkthrough code highlight failed for lang=$lang", e)
            null
        }
    }

    /** Map a markdown fence language token to an IDE [Language] (by id, then by file extension). */
    private fun resolveLanguage(lang: String): Language? {
        Language.findLanguageByID(lang)?.let { return it }
        Language.findLanguageByID(lang.uppercase())?.let { return it }
        val fileType = FileTypeManager.getInstance().getFileTypeByExtension(lang)
            .takeUnless { it == PlainTextFileType.INSTANCE }
            ?: FileTypeManager.getInstance().findFileTypeByName(lang)
        return (fileType as? LanguageFileType)?.language
    }

    /** intellij-markdown HTML-encodes code content; undo it before re-lexing the raw source. */
    private fun decodeHtml(s: String): String =
        s.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&amp;", "&")

    private fun hex(color: java.awt.Color): String =
        "#%02x%02x%02x".format(color.red, color.green, color.blue)

    private const val DEFAULT_FONT_SCALE = 1.0f
    private const val CODE_BG_LIGHT = 0xF2F2F2
    private const val CODE_BG_DARK = 0x2B2D30
}
