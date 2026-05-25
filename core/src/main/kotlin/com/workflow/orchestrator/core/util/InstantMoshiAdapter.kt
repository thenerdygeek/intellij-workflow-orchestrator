package com.workflow.orchestrator.core.util

import com.squareup.moshi.FromJson
import com.squareup.moshi.ToJson
import java.time.Instant

/**
 * Moshi adapter that serializes [Instant] as an ISO-8601 string and deserializes it back.
 * Used for persisting [com.workflow.orchestrator.core.model.web.DomainAllowlistEntry]
 * timestamps in [com.workflow.orchestrator.core.settings.PluginSettings].
 */
class InstantMoshiAdapter {
    @ToJson fun toJson(value: Instant): String = value.toString()
    @FromJson fun fromJson(value: String): Instant = Instant.parse(value)
}
