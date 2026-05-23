package com.workflow.orchestrator.core.model.web

import java.time.Instant

data class DomainAllowlistEntry(
    val domain: String,                 // "docs.python.org" or "*.example.com"
    val httpOk: Boolean = false,        // allow http:// for this domain
    val addedAt: Instant,
    val lastUsedAt: Instant? = null,
)
