package com.workflow.orchestrator.core.model.web

import com.workflow.orchestrator.core.web.UrlScreener

data class SearchHit(
    val title: String,
    val url: String,
    val snippet: String,
    val provider: String,
    val rank: Int,
    val screenerFlags: Set<UrlScreener.Flag>,
)
