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
        val response = client.get("/test")
        assertNotEquals("OK", response.bodyAsText())
    }

    @Test
    fun `admin endpoints are never affected by chaos`() = testApplication {
        setupChaos(enabled = true, rate = 1.0)
        val response = client.get("/__admin/test")
        assertEquals("admin OK", response.bodyAsText())
    }

    @Test
    fun `admin can toggle chaos at runtime via shared config`() = testApplication {
        val config = ChaosConfig().apply { enabled = false }
        application {
            attributes.put(ChaosConfigKey, config)
            install(ChaosPlugin)
            routing { get("/test") { call.respondText("OK") } }
        }
        assertEquals("OK", client.get("/test").bodyAsText())
        config.enabled = true
        config.rate = 1.0
        assertNotEquals("OK", client.get("/test").bodyAsText())
    }
}
