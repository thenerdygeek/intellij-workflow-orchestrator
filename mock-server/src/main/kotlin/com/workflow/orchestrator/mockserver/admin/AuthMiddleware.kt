package com.workflow.orchestrator.mockserver.admin

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*

val AuthPlugin = createApplicationPlugin(name = "AuthPlugin") {
    onCall { call ->
        val path = call.request.local.uri
        if (path.startsWith("/__admin") || path == "/health") return@onCall

        val authHeader = call.request.headers["Authorization"]
        if (authHeader.isNullOrBlank()) {
            call.respondText(
                """{"message":"Authentication required"}""",
                ContentType.Application.Json,
                HttpStatusCode.Unauthorized
            )
        }
    }
}
