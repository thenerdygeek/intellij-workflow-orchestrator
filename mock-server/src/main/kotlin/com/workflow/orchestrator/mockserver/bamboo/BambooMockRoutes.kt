package com.workflow.orchestrator.mockserver.bamboo

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/**
 * CRITICAL: Same provider lambda pattern as jiraRoutes — see JiraMockRoutes.kt for explanation.
 * Every route handler must start with: val state = stateProvider()
 */
fun Route.bambooRoutes(stateProvider: () -> BambooState) {

    // GET /rest/api/latest/currentUser
    get("/rest/api/latest/currentUser") {
        val state = stateProvider()
        call.respondText(
            buildJsonObject {
                put("name", state.currentUser)
                put("fullName", "Mock User")
                put("email", "mock.user@example.com")
            }.toString(),
            ContentType.Application.Json
        )
    }

    // GET /rest/api/latest/plan
    get("/rest/api/latest/plan") {
        val state = stateProvider()
        call.respondText(
            buildJsonObject {
                putJsonObject("plans") {
                    put("size", state.plans.size)
                    putJsonArray("plan") {
                        state.plans.forEach { plan ->
                            addJsonObject {
                                put("key", plan.key)
                                put("shortName", plan.shortName)
                                put("name", plan.name)
                            }
                        }
                    }
                }
            }.toString(),
            ContentType.Application.Json
        )
    }

    // GET /rest/api/latest/search/plans
    get("/rest/api/latest/search/plans") {
        val state = stateProvider()
        val searchTerm = call.request.queryParameters["searchTerm"] ?: ""
        val matched = state.plans.filter {
            it.name.contains(searchTerm, ignoreCase = true) ||
                it.key.contains(searchTerm, ignoreCase = true)
        }
        call.respondText(
            buildJsonObject {
                put("size", matched.size)
                putJsonArray("searchResults") {
                    matched.forEach { plan ->
                        addJsonObject {
                            putJsonObject("searchEntity") {
                                put("key", plan.key)
                                put("planName", plan.name)
                            }
                        }
                    }
                }
            }.toString(),
            ContentType.Application.Json
        )
    }

    // GET /rest/api/latest/plan/{key}/branch
    get("/rest/api/latest/plan/{key}/branch") {
        val state = stateProvider()
        val key = call.parameters["key"] ?: ""
        val plan = state.plans.find { it.key == key }
        val branches = plan?.branches ?: emptyList()
        call.respondText(
            buildJsonObject {
                putJsonObject("branches") {
                    put("size", branches.size)
                    putJsonArray("branch") {
                        branches.forEach { b ->
                            addJsonObject {
                                put("key", b.key)
                                put("shortName", b.shortName)
                            }
                        }
                    }
                }
            }.toString(),
            ContentType.Application.Json
        )
    }

    // GET /rest/api/latest/plan/{key}/variable
    get("/rest/api/latest/plan/{key}/variable") {
        val state = stateProvider()
        val key = call.parameters["key"] ?: ""
        val plan = state.plans.find { it.key == key }
        val vars = plan?.variables ?: emptyMap()
        call.respondText(
            buildJsonObject {
                putJsonArray("variables") {
                    vars.forEach { (name, value) ->
                        addJsonObject {
                            put("name", name)
                            put("value", value)
                        }
                    }
                }
            }.toString(),
            ContentType.Application.Json
        )
    }

    // GET /rest/api/latest/result/{planKey}/latest
    get("/rest/api/latest/result/{planKey}/latest") {
        val state = stateProvider()
        val planKey = call.parameters["planKey"] ?: ""
        val latest = state.builds.values
            .filter { it.planKey == planKey }
            .maxByOrNull { it.buildNumber }
        if (latest == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        call.respondText(buildResultToJson(latest).toString(), ContentType.Application.Json)
    }

    // GET /rest/api/latest/result/{buildKey} — specific build, supports ?expand=logEntries,variables
    get("/rest/api/latest/result/{buildKey}") {
        val state = stateProvider()
        val buildKey = call.parameters["buildKey"] ?: ""
        // Also handle /{planKey} for listing builds
        val build = state.builds[buildKey]
        if (build != null) {
            val expand = call.request.queryParameters["expand"] ?: ""
            call.respondText(
                buildResultToJson(build, expand.contains("logEntries"), expand.contains("variables")).toString(),
                ContentType.Application.Json
            )
            return@get
        }
        // Treat as plan key — list builds for that plan
        val planBuilds = state.builds.values
            .filter { it.planKey == buildKey }
            .sortedByDescending { it.buildNumber }
        call.respondText(
            buildJsonObject {
                putJsonObject("results") {
                    put("size", planBuilds.size)
                    putJsonArray("result") {
                        planBuilds.forEach { b ->
                            add(buildResultToJson(b))
                        }
                    }
                }
            }.toString(),
            ContentType.Application.Json
        )
    }

    // POST /rest/api/latest/queue/{planKey}
    post("/rest/api/latest/queue/{planKey}") {
        val state = stateProvider()
        val planKey = call.parameters["planKey"] ?: ""
        val plan = state.plans.find { it.key == planKey }
        if (plan == null) {
            call.respondText("""{"message":"Plan not found"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
            return@post
        }
        val bodyText = call.receiveText()
        val variables = if (bodyText.isNotBlank()) {
            try {
                val body = Json.parseToJsonElement(bodyText).jsonObject
                body.entries.associate { it.key to it.value.jsonPrimitive.content }
            } catch (_: Exception) {
                emptyMap()
            }
        } else {
            emptyMap()
        }

        val build = state.triggerBuild(planKey, variables)
        call.respondText(
            buildResultToJson(build).toString(),
            ContentType.Application.Json,
            HttpStatusCode.OK
        )
    }

    // DELETE /rest/api/latest/queue/{resultKey}
    delete("/rest/api/latest/queue/{resultKey}") {
        val state = stateProvider()
        val resultKey = call.parameters["resultKey"] ?: ""
        if (state.cancelBuild(resultKey)) {
            call.respond(HttpStatusCode.NoContent)
        } else {
            call.respond(HttpStatusCode.NotFound)
        }
    }
}

private fun buildResultToJson(
    build: BambooBuildResult,
    includeLog: Boolean = false,
    includeVars: Boolean = false,
): JsonObject = buildJsonObject {
    put("buildResultKey", build.buildResultKey)
    put("key", build.buildResultKey)
    putJsonObject("plan") {
        put("key", build.planKey)
    }
    put("buildNumber", build.buildNumber)
    put("lifeCycleState", build.lifeCycleState)
    build.state?.let { put("state", it) }
    if (build.stages.isNotEmpty()) {
        putJsonObject("stages") {
            put("size", build.stages.size)
            putJsonArray("stage") {
                build.stages.forEach { stage ->
                    addJsonObject {
                        put("name", stage.name)
                        put("state", stage.state)
                        put("lifeCycleState", stage.lifeCycleState)
                    }
                }
            }
        }
    }
    if (includeLog && build.logEntries.isNotEmpty()) {
        putJsonArray("logEntries") {
            build.logEntries.forEach { add(JsonPrimitive(it)) }
        }
    }
    if (includeVars && build.variables.isNotEmpty()) {
        putJsonObject("variables") {
            build.variables.forEach { (k, v) -> put(k, v) }
        }
    }
}
