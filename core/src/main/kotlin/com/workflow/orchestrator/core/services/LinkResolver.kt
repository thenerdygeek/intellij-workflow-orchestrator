package com.workflow.orchestrator.core.services

import com.workflow.orchestrator.core.model.ChatLink
import com.workflow.orchestrator.core.model.LinkResolution

interface LinkResolver {
    fun resolve(link: ChatLink): LinkResolution
    fun open(link: ChatLink)
}
