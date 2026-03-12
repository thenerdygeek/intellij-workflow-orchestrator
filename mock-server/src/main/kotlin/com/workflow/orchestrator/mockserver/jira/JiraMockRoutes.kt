package com.workflow.orchestrator.mockserver.jira

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/**
 * CRITICAL: All route functions take a `() -> State` provider lambda, NOT a direct state reference.
 * This is because Ktor calls `jiraRoutes(...)` once at server startup. If we passed `JiraState`
 * directly, route closures would capture that snapshot. When scenario switching replaces the
 * state in the holder, routes would still serve the old state.
 *
 * With a lambda, each request calls stateProvider() to get the CURRENT state from the holder.
 * Called as: jiraRoutes { jiraHolder.state }
 *
 * Every route handler must start with: val state = stateProvider()
 */
fun Route.jiraRoutes(stateProvider: () -> JiraState) {

    // GET /rest/api/2/myself — Test connection
    get("/rest/api/2/myself") {
        val state = stateProvider()
        call.respondText(
            buildJsonObject {
                put("key", state.currentUser)
                put("name", state.currentUser)
                put("displayName", "Mock User")
                put("emailAddress", "mock.user@example.com")
                put("active", true)
            }.toString(),
            ContentType.Application.Json
        )
    }

    // GET /rest/agile/1.0/board?type=scrum — Board discovery
    get("/rest/agile/1.0/board") {
        val state = stateProvider()
        val type = call.request.queryParameters["type"]
        val boards = if (type != null) state.boards.filter { it.type == type } else state.boards
        call.respondText(
            buildJsonObject {
                put("maxResults", 50)
                put("startAt", 0)
                put("isLast", true)
                putJsonArray("values") {
                    boards.forEach { board ->
                        addJsonObject {
                            put("id", board.id)
                            put("name", board.name)
                            put("type", board.type)
                        }
                    }
                }
            }.toString(),
            ContentType.Application.Json
        )
    }

    // GET /rest/agile/1.0/board/{boardId}/sprint?state=active — Sprint discovery
    get("/rest/agile/1.0/board/{boardId}/sprint") {
        val state = stateProvider()
        val boardId = call.parameters["boardId"]?.toIntOrNull()
        val stateFilter = call.request.queryParameters["state"]
        val sprints = state.sprints
            .filter { boardId == null || it.boardId == boardId }
            .filter { stateFilter == null || it.state == stateFilter }
        call.respondText(
            buildJsonObject {
                put("maxResults", 50)
                put("startAt", 0)
                put("isLast", true)
                putJsonArray("values") {
                    sprints.forEach { sprint ->
                        addJsonObject {
                            put("id", sprint.id)
                            put("name", sprint.name)
                            put("state", sprint.state)
                        }
                    }
                }
            }.toString(),
            ContentType.Application.Json
        )
    }

    // GET /rest/agile/1.0/sprint/{sprintId}/issue — Sprint issues
    get("/rest/agile/1.0/sprint/{sprintId}/issue") {
        val state = stateProvider()
        val assigneeIssues = state.issues.values
            .filter { it.assignee == state.currentUser }
            .sortedBy { it.key }
        call.respondText(
            buildJsonObject {
                put("maxResults", 200)
                put("startAt", 0)
                put("total", assigneeIssues.size)
                putJsonArray("issues") {
                    assigneeIssues.forEach { issue ->
                        add(issueToJson(issue))
                    }
                }
            }.toString(),
            ContentType.Application.Json
        )
    }

    // GET /rest/api/2/issue/{key} — Issue detail with links
    get("/rest/api/2/issue/{key}") {
        val state = stateProvider()
        val key = call.parameters["key"]
        val issue = state.issues[key]
        if (issue == null) {
            call.respondText(
                """{"errorMessages":["Issue does not exist or you do not have permission."]}""",
                ContentType.Application.Json,
                HttpStatusCode.NotFound
            )
            return@get
        }
        call.respondText(issueToJson(issue).toString(), ContentType.Application.Json)
    }

    // GET /rest/api/2/issue/{key}/transitions — Available transitions
    get("/rest/api/2/issue/{key}/transitions") {
        val state = stateProvider()
        val key = call.parameters["key"]
        val transitions = state.getTransitionsForIssue(key ?: "")
        call.respondText(
            buildJsonObject {
                putJsonArray("transitions") {
                    transitions.forEach { t ->
                        addJsonObject {
                            put("id", t.id)
                            put("name", t.name)
                            putJsonObject("to") {
                                put("id", t.to.id)
                                put("name", t.to.name)
                                putJsonObject("statusCategory") {
                                    put("id", t.to.statusCategory.id)
                                    put("key", t.to.statusCategory.key)
                                    put("name", t.to.statusCategory.name)
                                }
                            }
                            if (t.fields.isNotEmpty()) {
                                putJsonObject("fields") {
                                    t.fields.forEach { (fieldName, field) ->
                                        putJsonObject(fieldName) {
                                            put("required", field.required)
                                            put("name", field.name)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }.toString(),
            ContentType.Application.Json
        )
    }

    // POST /rest/api/2/issue/{key}/transitions — Execute transition
    post("/rest/api/2/issue/{key}/transitions") {
        val state = stateProvider()
        val key = call.parameters["key"] ?: ""
        val body = call.receiveText()
        val bodyJson = Json.parseToJsonElement(body).jsonObject
        val transitionId = bodyJson["transition"]?.jsonObject?.get("id")?.jsonPrimitive?.content

        if (transitionId == null) {
            call.respondText(
                """{"errorMessages":["Missing transition id"]}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest
            )
            return@post
        }

        // Check required fields
        val requiredFields = state.getRequiredFieldsForTransition(key, transitionId)
        if (requiredFields.isNotEmpty()) {
            val providedFields = bodyJson["fields"]?.jsonObject
            val missingFields = requiredFields.filter { (fieldName, _) ->
                providedFields?.get(fieldName) == null
            }
            if (missingFields.isNotEmpty()) {
                val errors = buildJsonObject {
                    missingFields.forEach { (fieldName, field) ->
                        put(fieldName, "${field.name} is required for this transition")
                    }
                }
                call.respondText(
                    buildJsonObject {
                        putJsonArray("errorMessages") {}
                        put("errors", errors)
                    }.toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest
                )
                return@post
            }
        }

        if (state.applyTransition(key, transitionId)) {
            call.respond(HttpStatusCode.NoContent)
        } else {
            call.respondText(
                """{"errorMessages":["Transition not available"]}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest
            )
        }
    }

    // POST /rest/api/2/issue/{key}/comment — Add comment
    post("/rest/api/2/issue/{key}/comment") {
        val state = stateProvider()
        val key = call.parameters["key"] ?: ""
        val issue = state.issues[key]
        if (issue == null) {
            call.respond(HttpStatusCode.NotFound)
            return@post
        }
        val body = Json.parseToJsonElement(call.receiveText()).jsonObject
        val commentBody = body["body"]?.jsonPrimitive?.content ?: ""
        val comment = JiraComment(
            id = (issue.comments.size + 1).toString(),
            body = commentBody,
            author = state.currentUser,
            created = "2026-03-12T10:00:00.000+0000",
        )
        issue.comments.add(comment)
        call.respondText(
            buildJsonObject {
                put("id", comment.id)
                put("body", comment.body)
                putJsonObject("author") { put("name", comment.author) }
                put("created", comment.created)
            }.toString(),
            ContentType.Application.Json,
            HttpStatusCode.Created
        )
    }

    // POST /rest/api/2/issue/{key}/worklog — Log time
    post("/rest/api/2/issue/{key}/worklog") {
        val state = stateProvider()
        val key = call.parameters["key"] ?: ""
        val issue = state.issues[key]
        if (issue == null) {
            call.respond(HttpStatusCode.NotFound)
            return@post
        }
        val body = Json.parseToJsonElement(call.receiveText()).jsonObject
        val worklog = JiraWorklog(
            id = (issue.worklogs.size + 1).toString(),
            timeSpentSeconds = body["timeSpentSeconds"]?.jsonPrimitive?.long ?: 0,
            comment = body["comment"]?.jsonPrimitive?.content ?: "",
            author = state.currentUser,
            started = body["started"]?.jsonPrimitive?.content ?: "2026-03-12T09:00:00.000+0000",
        )
        issue.worklogs.add(worklog)
        call.respond(HttpStatusCode.Created)
    }
}

private fun issueToJson(issue: JiraIssue): JsonObject = buildJsonObject {
    put("key", issue.key)
    put("id", issue.key.hashCode().toString())
    putJsonObject("fields") {
        put("summary", issue.summary)
        putJsonObject("status") {
            put("id", issue.status.id)
            put("name", issue.status.name)
            putJsonObject("statusCategory") {
                put("id", issue.status.statusCategory.id)
                put("key", issue.status.statusCategory.key)
                put("name", issue.status.statusCategory.name)
            }
        }
        putJsonObject("issuetype") {
            put("name", issue.issueType)
        }
        if (issue.assignee != null) {
            putJsonObject("assignee") {
                put("name", issue.assignee)
                put("displayName", "Mock User")
            }
        } else {
            put("assignee", JsonNull)
        }
        putJsonObject("priority") {
            put("name", issue.priority)
        }
        putJsonArray("issuelinks") {
            issue.issueLinks.forEach { link ->
                addJsonObject {
                    putJsonObject("type") {
                        put("name", link.type)
                    }
                    link.outwardIssue?.let {
                        putJsonObject("outwardIssue") { put("key", it) }
                    }
                    link.inwardIssue?.let {
                        putJsonObject("inwardIssue") { put("key", it) }
                    }
                }
            }
        }
    }
}
