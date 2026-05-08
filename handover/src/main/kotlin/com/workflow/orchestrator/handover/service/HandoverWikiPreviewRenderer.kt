package com.workflow.orchestrator.handover.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.notifications.WorkflowNotificationService
import com.workflow.orchestrator.core.services.JiraService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.jetbrains.annotations.TestOnly
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Project-scoped service that wraps [HandoverWikiPreviewRenderer] with a live Jira render path.
 *
 * - [renderImmediate] returns instantly: cache hit → LIVE_CACHED, miss → LOCAL.
 * - [requestLive] fires a background call to Jira; on success emits via [liveResults] and
 *   populates the cache so subsequent [renderImmediate] calls return LIVE_CACHED.
 * - After any non-recoverable failure (401/403/network) [isLiveAvailable] becomes sticky-false
 *   and a single warning notification is shown for the session.
 *
 * T10: added alongside the existing [HandoverWikiPreviewRenderer] object.
 */
@Service(Service.Level.PROJECT)
class HandoverWikiPreviewRendererService(
    private val jira: JiraService?,
    private val notifications: WorkflowNotificationService?,
    private val cs: CoroutineScope,
) {
    enum class Source { LIVE_CACHED, LIVE_FRESH, LOCAL }
    data class Result(val html: String, val source: Source)

    /** Production IntelliJ DI constructor. */
    constructor(project: Project, cs: CoroutineScope) : this(
        jira = project.getService(JiraService::class.java),
        notifications = project.getService(WorkflowNotificationService::class.java),
        cs = cs,
    )

    // ── Cache ─────────────────────────────────────────────────────────────────

    /** SHA-256(resolvedMarkup) → rendered HTML. Capped at CACHE_SIZE entries. */
    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, String>) = size > CACHE_SIZE
    }

    // ── Live availability ─────────────────────────────────────────────────────

    private val liveAvailable = AtomicBoolean(true)
    private val notifiedOnce = AtomicBoolean(false)

    /** SHA-256(resolvedMarkup) → in-flight Job. Prevents duplicate POSTs for the same content. */
    private val inflight = ConcurrentHashMap<String, Job>()

    // ── Live results flow ─────────────────────────────────────────────────────

    private val _liveResults = MutableSharedFlow<Pair<String, Result>>(extraBufferCapacity = 32)

    /** UI subscribers collect this to swap preview from LOCAL → LIVE_FRESH. */
    val liveResults: SharedFlow<Pair<String, Result>> = _liveResults.asSharedFlow()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Synchronous: returns LIVE_CACHED on cache hit, LOCAL on miss.
     * Does NOT trigger a live fetch — call [requestLive] separately.
     */
    fun renderImmediate(resolvedMarkup: String): Result {
        val key = sha256(resolvedMarkup)
        val cached = synchronized(cache) { cache[key] }
        return if (cached != null) {
            Result(html = cached, source = Source.LIVE_CACHED)
        } else {
            Result(html = HandoverWikiPreviewRenderer.local(resolvedMarkup), source = Source.LOCAL)
        }
    }

    /**
     * Fires a live Jira render in the background.
     * No-op if live is unavailable (sticky after first non-recoverable failure).
     * On success: populates cache + emits via [liveResults].
     * On failure: flips [isLiveAvailable] to false + notifies once.
     */
    fun requestLive(resolvedMarkup: String, issueKey: String) {
        if (!liveAvailable.get()) return
        val jiraService = jira ?: return
        val sha = sha256(resolvedMarkup)

        // Coalesce: if a request for this exact content is already in-flight, skip.
        // We avoid calling inflight.remove() inside computeIfAbsent (which would cause
        // a recursive-update exception on ConcurrentHashMap when the coroutine runs
        // undispatched). Instead we use putIfAbsent and attach the cleanup via
        // invokeOnCompletion — which is called after computeIfAbsent returns.
        if (inflight.containsKey(sha)) return
        val job = cs.launch {
            try {
                val toolResult = jiraService.renderWikiMarkup(resolvedMarkup, issueKey)
                val html = toolResult.data
                if (!toolResult.isError && html != null) {
                    synchronized(cache) { cache[sha] = html }
                    _liveResults.emit(resolvedMarkup to Result(html = html, source = Source.LIVE_FRESH))
                } else {
                    val summary = toolResult.summary
                    val isAuthFailure = summary.contains("401") ||
                        summary.contains("403", ignoreCase = true) ||
                        summary.contains("AUTH", ignoreCase = true)
                    if (isAuthFailure) {
                        liveAvailable.set(false)
                        if (notifiedOnce.compareAndSet(false, true)) {
                            notifications?.notifyWarning(
                                "workflow.handover.wiki",
                                "Wiki Preview Unavailable",
                                "Live Jira wiki rendering is unavailable: $summary"
                            )
                        }
                    } else {
                        // Transient failure (5xx, 429, network) — log but keep live available.
                        notifications?.notifyWarning(
                            "workflow.handover.wiki.transient",
                            "Wiki Preview Transient Error",
                            "Live Jira wiki rendering failed (will retry): $summary"
                        )
                    }
                }
            } finally {
                inflight.remove(sha)
            }
        }
        // putIfAbsent returns non-null if another Job raced us to the same key;
        // in that case cancel the redundant job we just launched.
        if (inflight.putIfAbsent(sha, job) != null) {
            job.cancel()
        }
    }

    /** True while live Jira rendering is available; sticky-false after first non-recoverable failure. */
    fun isLiveAvailable(): Boolean = liveAvailable.get()

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val CACHE_SIZE = 64

        /** Test-only factory — inject collaborators directly without a Project. */
        @TestOnly
        fun forTest(
            jira: JiraService,
            notifications: WorkflowNotificationService,
            cs: CoroutineScope,
        ): HandoverWikiPreviewRendererService = HandoverWikiPreviewRendererService(
            jira = jira,
            notifications = notifications,
            cs = cs,
        )
    }
}

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
                output.append(codeLines.joinToString("\n") { escape(it) })
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
        // Escape raw text first so user content never injects HTML tags.
        var result = escape(text)
        result = COLOR_RE.replace(result) { m ->
            // The color span wrapper is safe; escape the inner text (already escaped by this point
            // because we replaced on the escaped string — so groupValues[2] is already escaped).
            """<span style="color:${m.groupValues[1]}">${m.groupValues[2]}</span>"""
        }
        result = LINK_RE.replace(result) { m ->
            """<a href="${m.groupValues[2]}">${m.groupValues[1]}</a>"""
        }
        result = BOLD_RE.replace(result) { m -> "<b>${m.groupValues[1]}</b>" }
        result = ITALIC_RE.replace(result) { m -> "<i>${m.groupValues[1]}</i>" }
        return result
    }

    /** Escapes HTML special characters so user content cannot inject tags. */
    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
