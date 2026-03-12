package com.workflow.orchestrator.mockserver.admin

import com.workflow.orchestrator.mockserver.chaos.ChaosConfig
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

data class RequestLog(
    val method: String,
    val path: String,
    val timestamp: Long,
    val responseCode: Int,
)

class AdminState {
    val requestLog = ArrayDeque<RequestLog>(50)

    fun logRequest(method: String, path: String, responseCode: Int) {
        if (path.startsWith("/__admin")) return
        synchronized(requestLog) {
            if (requestLog.size >= 50) requestLog.removeFirst()
            requestLog.addLast(RequestLog(method, path, System.currentTimeMillis(), responseCode))
        }
    }
}

fun Route.adminRoutes(
    serviceName: String,
    chaosConfig: ChaosConfig,
    adminState: AdminState,
    getStateJson: () -> JsonObject,
    resetState: () -> Unit,
    loadScenario: (String) -> Boolean,
    availableScenarios: List<String>,
) {
    route("/__admin") {
        get("/state") {
            call.respondText(getStateJson().toString(), ContentType.Application.Json)
        }

        post("/reset") {
            resetState()
            call.respondText("""{"message":"$serviceName state reset"}""", ContentType.Application.Json)
        }

        post("/chaos") {
            val enabled = call.request.queryParameters["enabled"]
            val rate = call.request.queryParameters["rate"]

            if (enabled != null) {
                chaosConfig.enabled = enabled.toBoolean()
            }
            if (rate != null) {
                chaosConfig.rate = rate.toDouble().coerceIn(0.0, 1.0)
            }

            call.respondText(
                buildJsonObject {
                    put("enabled", chaosConfig.enabled)
                    put("rate", chaosConfig.rate)
                }.toString(),
                ContentType.Application.Json
            )
        }

        post("/scenario/{name}") {
            val name = call.parameters["name"] ?: ""
            if (loadScenario(name)) {
                call.respondText("""{"message":"Scenario '$name' loaded for $serviceName"}""", ContentType.Application.Json)
            } else {
                call.respondText(
                    """{"error":"Unknown scenario: $name"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound
                )
            }
        }

        get("/scenarios") {
            call.respondText(
                buildJsonObject {
                    putJsonArray("scenarios") {
                        availableScenarios.forEach { add(JsonPrimitive(it)) }
                    }
                }.toString(),
                ContentType.Application.Json
            )
        }

        get("/requests") {
            call.respondText(
                buildJsonObject {
                    putJsonArray("requests") {
                        synchronized(adminState.requestLog) {
                            adminState.requestLog.forEach { req ->
                                addJsonObject {
                                    put("method", req.method)
                                    put("path", req.path)
                                    put("timestamp", req.timestamp)
                                    put("responseCode", req.responseCode)
                                }
                            }
                        }
                    }
                }.toString(),
                ContentType.Application.Json
            )
        }
    }
}
