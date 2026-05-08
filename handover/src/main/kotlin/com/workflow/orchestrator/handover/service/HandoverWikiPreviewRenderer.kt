package com.workflow.orchestrator.handover.service

/**
 * Local Jira wiki markup renderer — no network, no project scope.
 * Supports: h1/h2/h3 headings, *bold*, _italic_, {color:x}...{color},
 * [label|url] links, ||header|| / |cell| tables, and {code:lang}...{code} blocks.
 * All other lines are passed through wrapped in <p> (unless they already start
 * with a block-level HTML tag).
 *
 * T10 will wrap this with a project-scoped service that adds a live-render path.
 */
object HandoverWikiPreviewRenderer {

    private val HEADING_RE = Regex("""^(h[1-6])\.\s+(.+)$""")
    private val CODE_OPEN_RE = Regex("""^\{code(?::([^}]*))?\}$""")
    private val CODE_CLOSE_RE = Regex("""^\{code\}$""")
    private val HEADER_ROW_RE = Regex("""^\|\|(.+)\|\|$""")
    private val DATA_ROW_RE = Regex("""^\|(.+)\|$""")

    // Inline patterns — applied in this order
    private val BOLD_RE = Regex("""\*([^*]+)\*""")
    private val ITALIC_RE = Regex("""_([^_]+)_""")
    private val COLOR_RE = Regex("""\{color:([^}]+)\}([^{]+)\{color\}""")
    private val LINK_RE = Regex("""\[([^|\]]+)\|([^\]]+)\]""")

    // Block-level HTML tags we use — don't double-wrap lines starting with these
    private val BLOCK_TAG_RE = Regex("""^<(h[1-6]|table|pre|p|ul|ol|li|div|blockquote)[\s>]""")

    fun local(text: String): String {
        if (text.isEmpty()) return ""

        val lines = text.lines()
        val output = StringBuilder()
        var i = 0

        // Table accumulation state
        val tableRows = mutableListOf<String>() // pre-rendered <tr>...</tr> strings
        var inTable = false

        fun flushTable() {
            if (inTable && tableRows.isNotEmpty()) {
                output.append("<table>")
                tableRows.forEach { output.append(it) }
                output.append("</table>")
                tableRows.clear()
                inTable = false
            }
        }

        while (i < lines.size) {
            val raw = lines[i]
            val line = raw.trim()

            // ── Code block ────────────────────────────────────────────────────
            val codeOpen = CODE_OPEN_RE.matchEntire(line)
            if (codeOpen != null) {
                flushTable()
                val lang = codeOpen.groupValues[1].ifBlank { "text" }
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size) {
                    val codeLine = lines[i].trim()
                    if (CODE_CLOSE_RE.matches(codeLine)) {
                        i++
                        break
                    }
                    codeLines.add(lines[i])
                    i++
                }
                output.append("""<pre><code class="$lang">""")
                output.append(codeLines.joinToString("\n"))
                output.append("</code></pre>")
                continue
            }

            // ── Heading ───────────────────────────────────────────────────────
            val headingMatch = HEADING_RE.matchEntire(line)
            if (headingMatch != null) {
                flushTable()
                val tag = headingMatch.groupValues[1]
                val content = applyInline(headingMatch.groupValues[2])
                output.append("<$tag>$content</$tag>")
                i++
                continue
            }

            // ── Table header row  ─────────────────────────────────────────────
            val headerMatch = HEADER_ROW_RE.matchEntire(line)
            if (headerMatch != null) {
                inTable = true
                val cells = headerMatch.groupValues[1].split("||")
                val tr = buildString {
                    append("<tr>")
                    cells.forEach { append("<th>${applyInline(it.trim())}</th>") }
                    append("</tr>")
                }
                tableRows.add(tr)
                i++
                continue
            }

            // ── Table data row ────────────────────────────────────────────────
            val dataMatch = DATA_ROW_RE.matchEntire(line)
            if (dataMatch != null) {
                inTable = true
                val cells = dataMatch.groupValues[1].split("|")
                val tr = buildString {
                    append("<tr>")
                    cells.forEach { append("<td>${applyInline(it.trim())}</td>") }
                    append("</tr>")
                }
                tableRows.add(tr)
                i++
                continue
            }

            // ── Not a table line — flush any accumulated table ────────────────
            flushTable()

            // ── Blank line (paragraph separator) ─────────────────────────────
            if (line.isEmpty()) {
                i++
                continue
            }

            // ── Regular paragraph line ────────────────────────────────────────
            val inlined = applyInline(line)
            if (BLOCK_TAG_RE.containsMatchIn(inlined)) {
                output.append(inlined)
            } else {
                output.append("<p>$inlined</p>")
            }
            i++
        }

        // Flush any trailing table
        flushTable()

        return output.toString()
    }

    private fun applyInline(text: String): String {
        var result = text
        result = COLOR_RE.replace(result) { m ->
            """<span style="color:${m.groupValues[1]}">${m.groupValues[2]}</span>"""
        }
        result = LINK_RE.replace(result) { m ->
            """<a href="${m.groupValues[2]}">${m.groupValues[1]}</a>"""
        }
        result = BOLD_RE.replace(result) { m -> "<b>${m.groupValues[1]}</b>" }
        result = ITALIC_RE.replace(result) { m -> "<i>${m.groupValues[1]}</i>" }
        return result
    }
}
