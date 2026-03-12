package com.workflow.orchestrator.mockserver.chaos

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ChaosMiddlewareTest {

    private fun ApplicationTestBuilder.setupChaos(enabled: Boolean, rate: Double = 0.2) {
        application {
            val config = ChaosConfig().apply {
                this.enabled = enabled
                this.rate = rate
                // Zero delays in tests to avoid timeouts
                this.slowDelayMs = 0L..0L
                this.timeoutDelayMs = 0L
            }
            attributes.put(ChaosConfigKey, config)
            install(ChaosPlugin)
            routing {
                get("/test") { call.respondText("OK") }
                get("/__admin/test") { call.respondText("admin OK") }
            }
        }
    }

    @Test
    fun `chaos disabled does not affect requests`() = testApplication {
        setupChaos(enabled = false)
        repeat(20) {
            val response = client.get("/test")
            assertEquals("OK", response.bodyAsText())
        }
    }

    @Test
    fun `chaos with rate 1 affects all requests`() = testApplication {
        setupChaos(enabled = true, rate = 1.0)
        // SLOW_RESPONSE type delays but still returns normal "OK" from the route handler,
        // so we run multiple requests and assert at least one is visibly affected
        // (non-200 status, different body, or different content-type).
        val results = (1..10).map {
            val r = client.get("/test")
            Triple(r.status, r.bodyAsText(), r.headers["Content-Type"])
        }
        val anyAffected = results.any { (status, body, _) ->
            status != HttpStatusCode.OK || body != "OK"
        }
        assertTrue(anyAffected, "At least one request should be visibly affected by chaos")
    }

    @Test
    fun `admin endpoints are never affected by chaos`() = testApplication {
        setupChaos(enabled = true, rate = 1.0)
        val response = client.get("/__admin/test")
        assertEquals("admin OK", response.bodyAsText())
    }

    @Test
    fun `admin can toggle chaos at runtime via shared config`() = testApplication {
        val config = ChaosConfig().apply {
            enabled = false
            slowDelayMs = 0L..0L
            timeoutDelayMs = 0L
        }
        application {
            attributes.put(ChaosConfigKey, config)
            install(ChaosPlugin)
            routing { get("/test") { call.respondText("OK") } }
        }
        assertEquals("OK", client.get("/test").bodyAsText())
        config.enabled = true
        config.rate = 1.0
        // SLOW_RESPONSE chaos type delays but still lets route return "OK",
        // so check multiple requests for at least one visible effect.
        val results = (1..10).map {
            val r = client.get("/test")
            r.status to r.bodyAsText()
        }
        val anyAffected = results.any { (status, body) ->
            status != HttpStatusCode.OK || body != "OK"
        }
        assertTrue(anyAffected, "At least one request should be affected after enabling chaos")
    }
}
