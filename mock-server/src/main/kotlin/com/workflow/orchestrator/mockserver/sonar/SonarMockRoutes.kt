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

    get("/api/components/search") {
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

    get("/api/hotspots/search") {
        val state = stateProvider()
        val projectKey = call.request.queryParameters["project"] ?: ""
        val hotspots = state.hotspots.filter { h ->
            projectKey.isEmpty() || h.component.startsWith(projectKey)
        }
        call.respondText(
            buildJsonObject {
                putJsonObject("paging") {
                    put("pageIndex", 1)
                    put("pageSize", 500)
                    put("total", hotspots.size)
                }
                putJsonArray("hotspots") {
                    hotspots.forEach { h ->
                        addJsonObject {
                            put("key", h.key)
                            put("message", h.message)
                            put("component", h.component)
                            put("securityCategory", h.securityCategory)
                            put("vulnerabilityProbability", h.vulnerabilityProbability)
                            put("status", h.status)
                            h.resolution?.let { put("resolution", it) }
                            h.line?.let { put("line", it) }
                            h.creationDate?.let { put("creationDate", it) }
                            h.updateDate?.let { put("updateDate", it) }
                            h.author?.let { put("author", it) }
                        }
                    }
                }
            }.toString(),
            ContentType.Application.Json
        )
    }

    get("/api/duplications/show") {
        val state = stateProvider()
        val componentKey = call.request.queryParameters["key"] ?: ""
        val dups = state.duplications[componentKey] ?: emptyList()
        val files = state.duplicationFiles
        call.respondText(
            buildJsonObject {
                putJsonArray("duplications") {
                    dups.forEach { dup ->
                        addJsonObject {
                            putJsonArray("blocks") {
                                dup.blocks.forEach { block ->
                                    addJsonObject {
                                        put("_ref", block.ref)
                                        put("from", block.from)
                                        put("size", block.size)
                                    }
                                }
                            }
                        }
                    }
                }
                putJsonObject("files") {
                    files.forEach { (ref, file) ->
                        putJsonObject(ref) {
                            put("key", file.key)
                            put("name", file.name)
                            put("projectName", file.projectName)
                        }
                    }
                }
            }.toString(),
            ContentType.Application.Json
        )
    }

    get("/api/new_code_periods/show") {
        val state = stateProvider()
        val period = state.newCodePeriod
        if (period == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        call.respondText(
            buildJsonObject {
                put("projectKey", period.projectKey)
                put("branchKey", period.branchKey)
                put("type", period.type)
                put("value", period.value)
                put("effectiveValue", period.effectiveValue)
                put("inherited", period.inherited)
            }.toString(),
            ContentType.Application.Json
        )
    }

    get("/api/project_branches/list") {
        val state = stateProvider()
        call.respondText(
            buildJsonObject {
                putJsonArray("branches") {
                    state.branches.forEach { b ->
                        addJsonObject {
                            put("name", b.name)
                            put("isMain", b.isMain)
                            put("type", b.type)
                            putJsonObject("status") {
                                put("qualityGateStatus", b.qualityGateStatus)
                                b.bugs?.let { put("bugs", it) }
                                b.vulnerabilities?.let { put("vulnerabilities", it) }
                                b.codeSmells?.let { put("codeSmells", it) }
                            }
                            b.analysisDate?.let { put("analysisDate", it) }
                        }
                    }
                }
            }.toString(),
            ContentType.Application.Json
        )
    }

    get("/api/ce/activity") {
        val state = stateProvider()
        val componentKey = call.request.queryParameters["component"] ?: ""
        val tasks = state.ceTasks.filter { t ->
            componentKey.isEmpty() || t.componentKey == componentKey
        }
        call.respondText(
            buildJsonObject {
                putJsonArray("tasks") {
                    tasks.forEach { t ->
                        addJsonObject {
                            put("id", t.id)
                            put("type", t.type)
                            put("componentKey", t.componentKey)
                            put("status", t.status)
                            t.branch?.let { put("branch", it) }
                            t.branchType?.let { put("branchType", it) }
                            t.submittedAt?.let { put("submittedAt", it) }
                            t.startedAt?.let { put("startedAt", it) }
                            t.executedAt?.let { put("executedAt", it) }
                            t.executionTimeMs?.let { put("executionTimeMs", it) }
                            t.errorMessage?.let { put("errorMessage", it) }
                            put("hasScannerContext", t.hasScannerContext)
                            put("hasErrorStacktrace", t.hasErrorStacktrace)
                        }
                    }
                }
            }.toString(),
            ContentType.Application.Json
        )
    }

    get("/api/measures/component") {
        val state = stateProvider()
        val componentKey = call.request.queryParameters["component"] ?: ""
        val metricKeys = call.request.queryParameters["metricKeys"]?.split(",") ?: emptyList()
        val matched = state.projectMeasures.filter { m ->
            metricKeys.isEmpty() || m.metric in metricKeys
        }
        call.respondText(
            buildJsonObject {
                putJsonObject("component") {
                    put("key", componentKey)
                    put("name", state.projects.firstOrNull()?.name ?: componentKey)
                    put("qualifier", "TRK")
                    putJsonArray("measures") {
                        matched.forEach { m ->
                            addJsonObject {
                                put("metric", m.metric)
                                put("value", m.value)
                            }
                        }
                    }
                }
            }.toString(),
            ContentType.Application.Json
        )
    }
}
