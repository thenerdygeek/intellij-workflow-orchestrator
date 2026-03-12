package com.workflow.orchestrator.mockserver.chaos

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.util.*
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Shared mutable config — passed by reference so admin routes can toggle at runtime.
 * The ChaosPlugin reads from this on every request (not a copy at install time).
 */
class ChaosConfig {
    var enabled: Boolean = false
    var rate: Double = 0.2
    /** Delay range in ms for SLOW_RESPONSE. Set to 0..0 in tests to avoid real waits. */
    var slowDelayMs: LongRange = 10_000L..15_000L
    /** Delay in ms for TIMEOUT. Set to 0 in tests. */
    var timeoutDelayMs: Long = 35_000L
}

/** Attribute key to store the shared ChaosConfig reference on the application. */
val ChaosConfigKey = AttributeKey<ChaosConfig>("ChaosConfig")

val ChaosPlugin = createApplicationPlugin(name = "ChaosPlugin") {
    onCall { call ->
        val config = call.application.attributes.getOrNull(ChaosConfigKey) ?: return@onCall

        // Never affect admin endpoints
        if (call.request.local.uri.startsWith("/__admin")) return@onCall
        if (!config.enabled) return@onCall
        if (Random.nextDouble() > config.rate) return@onCall

        val failureType = selectFailureType()
        when (failureType) {
            ChaosFailure.SLOW_RESPONSE -> {
                val range = config.slowDelayMs
                if (range.last > 0) delay(Random.nextLong(range.first, range.last + 1))
                return@onCall
            }
            ChaosFailure.TIMEOUT -> {
                if (config.timeoutDelayMs > 0) delay(config.timeoutDelayMs)
                call.respondText("Timed out", ContentType.Text.Plain, HttpStatusCode.GatewayTimeout)
            }
            ChaosFailure.MALFORMED_JSON -> {
                call.respondText(
                    """{"partial": "data", "broken":""",
                    ContentType.Application.Json,
                    HttpStatusCode.OK,
                )
            }
            ChaosFailure.HTTP_429 -> {
                call.response.header("Retry-After", "5")
                call.respondText("Too Many Requests", ContentType.Text.Plain, HttpStatusCode.TooManyRequests)
            }
            ChaosFailure.HTTP_500 -> {
                call.respondText(
                    """{"error": "Internal Server Error"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError,
                )
            }
            ChaosFailure.HTTP_503 -> {
                call.respondText("Service Unavailable", ContentType.Text.Plain, HttpStatusCode.ServiceUnavailable)
            }
            ChaosFailure.EMPTY_BODY -> {
                call.respondText("", ContentType.Application.Json, HttpStatusCode.OK)
            }
            ChaosFailure.WRONG_CONTENT_TYPE -> {
                call.respondText(
                    "<html>Session expired. Please log in again.</html>",
                    ContentType.Text.Html,
                    HttpStatusCode.OK,
                )
            }
        }
    }
}

enum class ChaosFailure(val weight: Int) {
    MALFORMED_JSON(20),
    SLOW_RESPONSE(15),
    TIMEOUT(5),
    HTTP_429(10),
    HTTP_500(15),
    HTTP_503(15),
    EMPTY_BODY(10),
    WRONG_CONTENT_TYPE(10),
}

private fun selectFailureType(): ChaosFailure {
    val totalWeight = ChaosFailure.entries.sumOf { it.weight }
    var random = Random.nextInt(totalWeight)
    for (failure in ChaosFailure.entries) {
        random -= failure.weight
        if (random < 0) return failure
    }
    return ChaosFailure.HTTP_500
}
