package com.workflow.orchestrator.mockserver.sonar

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

fun Route.sonarRoutes(stateProvider: () -> SonarState) {

    get("/api/authentication/validate") {
        val state = stateProvider()
        call.respondText(
            buildJsonObject { put("valid", state.authValid) }.toString(),
            ContentType.Application.Json
        )
    }

    get("/api/projects/search") {
        val state = stateProvider()
        val query = call.request.queryParameters["q"] ?: ""
        val matched = state.projects.filter {
            it.name.contains(query, ignoreCase = true) || it.key.contains(query, ignoreCase = true)
        }
        call.respondText(
            buildJsonObject {
                putJsonObject("paging") {
                    put("pageIndex", 1)
                    put("pageSize", 100)
                    put("total", matched.size)
                }
                putJsonArray("components") {
                    matched.forEach { p ->
                        addJsonObject {
                            put("key", p.key)
                            put("name", p.name)
                            put("qualifier", "TRK")
                        }
                    }
                }
            }.toString(),
            ContentType.Application.Json
        )
    }

    get("/api/measures/component_tree") {
        val state = stateProvider()
        val componentKey = call.request.queryParameters["component"] ?: ""
        val metricKeys = call.request.queryParameters["metricKeys"]?.split(",") ?: emptyList()
        val matched = state.measures.filter { m ->
            (componentKey.isEmpty() || m.component.startsWith(componentKey)) &&
                (metricKeys.isEmpty() || m.metricKey in metricKeys)
        }
        call.respondText(
            buildJsonObject {
                putJsonObject("baseComponent") {
                    put("key", componentKey)
                    putJsonArray("measures") {
                        matched.forEach { m ->
                            addJsonObject {
                                put("metric", m.metricKey)
                                put("value", m.value)
                            }
                        }
                    }
                }
                putJsonArray("components") {}
            }.toString(),
            ContentType.Application.Json
        )
    }

    get("/api/sources/lines") {
        val state = stateProvider()
        val componentKey = call.request.queryParameters["key"] ?: ""
        val lines = state.sourceLines[componentKey] ?: emptyList()
        call.respondText(
            buildJsonObject {
                putJsonArray("sources") {
                    lines.forEach { line ->
                        addJsonObject {
                            put("line", line.line)
                            put("code", line.code)
                            line.covered?.let { put("lineHits", if (it) 1 else 0) }
                        }
                    }
                }
            }.toString(),
            ContentType.Application.Json
        )
    }

    get("/api/issues/search") {
        val state = stateProvider()
        val componentKeys = call.request.queryParameters["componentKeys"]
        val resolved = call.request.queryParameters["resolved"]
        val issues = state.issues.filter { issue ->
            (componentKeys == null || issue.component.startsWith(componentKeys)) &&
                (resolved == null || (resolved == "false" && issue.status == "OPEN"))
        }
        call.respondText(
            buildJsonObject {
                put("total", issues.size)
                put("p", 1)
                put("ps", 100)
                putJsonArray("issues") {
                    issues.forEach { issue ->
                        addJsonObject {
                            put("key", issue.key)
                            put("rule", issue.rule)
                            put("severity", issue.severity)
                            put("type", issue.type)
                            put("message", issue.message)
                            put("component", issue.component)
                            issue.line?.let { put("line", it) }
                            put("status", issue.status)
                        }
                    }
                }
            }.toString(),
            ContentType.Application.Json
        )
    }

    get("/api/qualitygates/project_status") {
        val state = stateProvider()
        val gate = state.qualityGate
        if (gate == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        call.respondText(
            buildJsonObject {
                putJsonObject("projectStatus") {
                    put("status", gate.status)
                    putJsonArray("conditions") {
                        gate.conditions.forEach { cond ->
                            addJsonObject {
                                put("status", cond.status)
                                put("metricKey", cond.metricKey)
                                put("comparator", cond.comparator)
                                cond.errorThreshold?.let { put("errorThreshold", it) }
                                cond.warningThreshold?.let { put("warningThreshold", it) }
                                put("actualValue", cond.actualValue)
                            }
                        }
                    }
                }
            }.toString(),
            ContentType.Application.Json
        )
    }
}
