package com.workflow.orchestrator.core.services.link

import com.intellij.ide.BrowserUtil
import com.workflow.orchestrator.core.model.ChatLink
import com.workflow.orchestrator.core.model.LinkResolution
import java.net.URI

class WebLinkResolver {

    fun resolve(link: ChatLink.WebLink): LinkResolution {
        val label = hostOrFull(link.url)
        return LinkResolution(
            kind = LinkResolution.Kind.WEB,
            raw = link.raw,
            displayLabel = label,
            targetDescription = "Opens in external browser",
            browserUrl = link.url,
        )
    }

    fun open(link: ChatLink.WebLink) {
        BrowserUtil.browse(link.url)
    }

    private fun hostOrFull(url: String): String {
        return try {
            URI(url).host?.takeIf { it.isNotBlank() } ?: url
        } catch (_: Exception) {
            url
        }
    }
}
