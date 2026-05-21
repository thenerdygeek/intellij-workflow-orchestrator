package com.workflow.orchestrator.core.services

import com.workflow.orchestrator.core.model.ChatLink

object LinkParser {

    // Path is everything up to the last ':' if the suffix after it is a valid line/range; else
    // the whole payload after "file:" is the path. Permits ':' inside directory names.
    private val LINE_SUFFIX_REGEX = Regex("""^(\d+)(?:-(\d+))?$""")
    private val CLASS_REGEX = Regex("""^class:([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*)(?:#([A-Za-z_][A-Za-z0-9_]*))?$""")
    private val JIRA_REGEX = Regex("""^jira:([A-Z][A-Z0-9]*)-(\d+)$""")
    private val WEB_REGEX = Regex("""^(https?)://(.+)$""")

    fun parse(href: String): ChatLink? {
        if (href.isEmpty()) return null
        return when {
            href.startsWith("file:") -> parseFile(href)
            href.startsWith("class:") -> parseClass(href)
            href.startsWith("jira:") -> parseJira(href)
            href.startsWith("http://") || href.startsWith("https://") -> parseWeb(href)
            else -> null
        }
    }

    private fun parseFile(href: String): ChatLink.FileLink? {
        val payload = href.removePrefix("file:")
        if (payload.isEmpty()) return null

        val lastColon = payload.lastIndexOf(':')
        if (lastColon < 0) return ChatLink.FileLink(raw = href, path = payload)

        val path = payload.substring(0, lastColon)
        val suffix = payload.substring(lastColon + 1)
        if (path.isEmpty()) return null

        val rangeMatch = LINE_SUFFIX_REGEX.matchEntire(suffix) ?: return null
        val line = rangeMatch.groupValues[1].toInt()
        if (line < 1) return null
        val endGroup = rangeMatch.groupValues[2]
        val endLine = if (endGroup.isEmpty()) null else endGroup.toInt()
        if (endLine != null && (endLine < 1 || endLine < line)) return null
        return ChatLink.FileLink(raw = href, path = path, line = line, endLine = endLine)
    }

    private fun parseClass(href: String): ChatLink.ClassLink? {
        val m = CLASS_REGEX.matchEntire(href) ?: return null
        val fqn = m.groupValues[1]
        val method = m.groupValues[2].ifEmpty { null }
        return ChatLink.ClassLink(raw = href, fqn = fqn, method = method)
    }

    private fun parseJira(href: String): ChatLink.JiraLink? {
        val m = JIRA_REGEX.matchEntire(href) ?: return null
        return ChatLink.JiraLink(raw = href, ticketId = "${m.groupValues[1]}-${m.groupValues[2]}")
    }

    private fun parseWeb(href: String): ChatLink.WebLink? {
        val m = WEB_REGEX.matchEntire(href) ?: return null
        if (m.groupValues[2].isBlank()) return null
        return ChatLink.WebLink(raw = href, url = href)
    }
}
