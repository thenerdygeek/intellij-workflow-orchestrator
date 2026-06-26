package com.workflow.orchestrator.mockserver.bitbucket

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/**
 * Bitbucket Server / Data Center REST mock.
 *
 * All routes are under /rest/api/1.0/ and require Bearer auth (enforced by the
 * shared AuthPlugin already installed at the server level). Routes are registered
 * as a Route extension so they compose cleanly with the other mock triads.
 *
 * CRITICAL: Routes take a `() -> BitbucketState` lambda — NOT a direct reference —
 * so that each request sees the CURRENT state from the holder after scenario
 * switches. Every handler must start with: val state = stateProvider()
 *
 * Called as: bitbucketRoutes { bitbucketHolder.state }
 */
fun Route.bitbucketRoutes(stateProvider: () -> BitbucketState) {

    // --- User lookup -------------------------------------------------------

    // GET /rest/api/1.0/users — reviewer-picker and current-user lookup
    get("/rest/api/1.0/users") {
        val state = stateProvider()
        val filter = call.request.queryParameters["filter"]?.lowercase() ?: ""
        val allUsers = listOf(
            state.currentUser,
            MockBitbucketUser("jane.smith", "Jane Smith", "jane.smith@example.com"),
            MockBitbucketUser("bob.jones", "Bob Jones", "bob.jones@example.com"),
        )
        val matched = if (filter.isBlank()) allUsers else allUsers.filter { u ->
            u.name.contains(filter, ignoreCase = true) ||
                u.displayName.contains(filter, ignoreCase = true) ||
                u.emailAddress.contains(filter, ignoreCase = true)
        }
        call.respondText(
            buildJsonObject {
                putJsonArray("values") { matched.forEach { add(userToJson(it)) } }
                put("size", matched.size)
                put("isLastPage", true)
            }.toString(),
            ContentType.Application.Json
        )
    }

    // --- Dashboard PR listing -----------------------------------------------

    // GET /rest/api/1.0/dashboard/pull-requests — cross-repo PR dashboard
    get("/rest/api/1.0/dashboard/pull-requests") {
        val state = stateProvider()
        val role = call.request.queryParameters["role"]?.uppercase() ?: "AUTHOR"
        val stateFilter = call.request.queryParameters["state"]?.uppercase() ?: "OPEN"
        val currentUsername = state.currentUser.name

        val matched = state.prs.values.filter { pr ->
            val stateOk = pr.state == stateFilter
            val roleOk = when (role) {
                "AUTHOR" -> pr.author.name == currentUsername
                "REVIEWER" -> pr.reviewers.any { it.user.name == currentUsername }
                "PARTICIPANT" -> pr.author.name == currentUsername ||
                    pr.reviewers.any { it.user.name == currentUsername }
                else -> true
            }
            stateOk && roleOk
        }.sortedByDescending { it.updatedDate }

        call.respondText(
            buildJsonObject {
                putJsonArray("values") { matched.forEach { add(prDetailToJson(it)) } }
                put("size", matched.size)
                put("isLastPage", true)
                put("start", 0)
            }.toString(),
            ContentType.Application.Json
        )
    }

    // --- Repository ---------------------------------------------------------

    // GET /rest/api/1.0/projects/{projectKey}/repos/{repoSlug}
    get("/rest/api/1.0/projects/{projectKey}/repos/{repoSlug}") {
        val projectKey = call.parameters["projectKey"] ?: ""
        val repoSlug = call.parameters["repoSlug"] ?: ""
        call.respondText(
            buildJsonObject {
                put("slug", repoSlug)
                put("name", repoSlug)
                putJsonObject("project") {
                    put("key", projectKey)
                    put("name", projectKey)
                }
                putJsonObject("links") {
                    putJsonArray("clone") {
                        addJsonObject {
                            put("name", "http")
                            put("href", "http://localhost:8480/scm/$projectKey/$repoSlug.git")
                        }
                        addJsonObject {
                            put("name", "ssh")
                            put("href", "ssh://git@localhost:7999/$projectKey/$repoSlug.git")
                        }
                    }
                    putJsonArray("self") {
                        addJsonObject {
                            put("href", "http://localhost:8480/projects/$projectKey/repos/$repoSlug/browse")
                        }
                    }
                }
            }.toString(),
            ContentType.Application.Json
        )
    }

    // GET /rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/branches
    get("/rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/branches") {
        val filter = call.request.queryParameters["filterText"]?.lowercase() ?: ""
        val branches = listOf(
            Triple("refs/heads/main", "main", "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"),
            Triple("refs/heads/develop", "develop", "b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3"),
            Triple("refs/heads/feature/PROJ-101-auth", "feature/PROJ-101-auth", "b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3"),
            Triple("refs/heads/hotfix/PROJ-102-payment-timeout", "hotfix/PROJ-102-payment-timeout", "d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5"),
        )
        val matched = if (filter.isBlank()) branches
        else branches.filter { it.second.contains(filter, ignoreCase = true) }
        call.respondText(
            buildJsonObject {
                putJsonArray("values") {
                    matched.forEachIndexed { idx, (refId, displayId, commit) ->
                        addJsonObject {
                            put("id", refId)
                            put("displayId", displayId)
                            put("latestCommit", commit)
                            put("isDefault", idx == 0)
                        }
                    }
                }
                put("size", matched.size)
                put("isLastPage", true)
            }.toString(),
            ContentType.Application.Json
        )
    }

    // GET /rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/default-branch
    get("/rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/default-branch") {
        call.respondText(
            buildJsonObject {
                put("id", "refs/heads/main")
                put("displayId", "main")
                put("latestCommit", "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2")
                put("isDefault", true)
            }.toString(),
            ContentType.Application.Json
        )
    }

    // --- PR listing ---------------------------------------------------------

    // GET /rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests
    get("/rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests") {
        val state = stateProvider()
        val stateFilter = call.request.queryParameters["state"]?.uppercase() ?: "OPEN"
        val roleFilter = call.request.queryParameters["role.1"]?.uppercase()
        val usernameFilter = call.request.queryParameters["username.1"]
        val start = call.request.queryParameters["start"]?.toIntOrNull() ?: 0
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 25
        val currentUsername = usernameFilter ?: state.currentUser.name

        val matched = state.prs.values
            .filter { pr ->
                val stateOk = stateFilter == "ALL" || pr.state == stateFilter
                val roleOk = when (roleFilter) {
                    "AUTHOR" -> pr.author.name == currentUsername
                    "REVIEWER" -> pr.reviewers.any { it.user.name == currentUsername }
                    else -> true
                }
                stateOk && roleOk
            }
            .sortedByDescending { it.updatedDate }
            .drop(start)
            .take(limit)

        call.respondText(
            buildJsonObject {
                putJsonArray("values") { matched.forEach { add(prDetailToJson(it)) } }
                put("size", matched.size)
                put("isLastPage", true)
                put("start", start)
            }.toString(),
            ContentType.Application.Json
        )
    }

    // --- PR detail ----------------------------------------------------------

    // GET /rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests/{prId}
    get("/rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests/{prId}") {
        val state = stateProvider()
        val prId = call.parameters["prId"]?.toIntOrNull()
        val pr = prId?.let { state.prs[it] }
        if (pr == null) {
            call.respondText(
                """{"errors":[{"message":"PR not found"}]}""",
                ContentType.Application.Json,
                HttpStatusCode.NotFound
            )
            return@get
        }
        call.respondText(prDetailToJson(pr).toString(), ContentType.Application.Json)
    }

    // --- PR sub-resources ---------------------------------------------------

    // GET /rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests/{prId}/activities
    get("/rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests/{prId}/activities") {
        val state = stateProvider()
        val prId = call.parameters["prId"]?.toIntOrNull()
        val pr = prId?.let { state.prs[it] }
        if (pr == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        val activities = mutableListOf<JsonObject>()
        // OPENED activity (always first)
        activities.add(buildJsonObject {
            put("id", 1000L + pr.id)
            put("action", "OPENED")
            put("user", userToJson(pr.author))
            put("createdDate", pr.createdDate)
        })
        // COMMENTED activities — each comment becomes one COMMENTED activity
        pr.comments.forEachIndexed { idx, comment ->
            val commentUser = MockBitbucketUser(
                name = comment.authorName,
                displayName = comment.authorDisplayName,
                emailAddress = "${comment.authorName}@example.com",
            )
            activities.add(buildJsonObject {
                put("id", 2000L + pr.id * 100 + idx)
                put("action", "COMMENTED")
                put("user", userToJson(commentUser))
                put("createdDate", comment.createdDate)
                putJsonObject("comment") {
                    put("id", comment.id)
                    put("text", comment.text)
                    put("author", userToJson(commentUser))
                    put("createdDate", comment.createdDate)
                    put("updatedDate", comment.createdDate)
                }
            })
        }
        // APPROVED activities — one per approved reviewer
        pr.reviewers.filter { it.approved }.forEach { reviewer ->
            activities.add(buildJsonObject {
                put("id", 3000L + pr.id * 100 + reviewer.user.name.hashCode().and(0xFFFF))
                put("action", "APPROVED")
                put("user", userToJson(reviewer.user))
                put("createdDate", pr.updatedDate)
            })
        }
        // MERGED activity for merged PRs
        if (pr.state == "MERGED") {
            activities.add(buildJsonObject {
                put("id", 9000L + pr.id)
                put("action", "MERGED")
                put("user", userToJson(pr.author))
                put("createdDate", pr.updatedDate)
            })
        }
        call.respondText(
            buildJsonObject {
                putJsonArray("values") { activities.forEach { add(it) } }
                put("isLastPage", true)
            }.toString(),
            ContentType.Application.Json
        )
    }

    // GET /rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests/{prId}/comments
    // Note: Bitbucket DC 9.4 rejects this without ?path or ?count=true; the plugin
    // falls back to /activities. The mock serves it for robustness.
    get("/rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests/{prId}/comments") {
        val state = stateProvider()
        val prId = call.parameters["prId"]?.toIntOrNull()
        val pr = prId?.let { state.prs[it] }
        if (pr == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        call.respondText(
            buildJsonObject {
                putJsonArray("values") {
                    pr.comments.forEach { add(commentResponseToJson(it)) }
                }
                put("isLastPage", true)
            }.toString(),
            ContentType.Application.Json
        )
    }

    // GET /rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests/{prId}/commits
    get("/rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests/{prId}/commits") {
        val state = stateProvider()
        val prId = call.parameters["prId"]?.toIntOrNull()
        val pr = prId?.let { state.prs[it] }
        if (pr == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        call.respondText(
            buildJsonObject {
                putJsonArray("values") {
                    pr.commits.forEach { commit ->
                        addJsonObject {
                            put("id", commit.id)
                            put("displayId", commit.displayId)
                            put("message", commit.message)
                            putJsonObject("author") {
                                put("name", commit.authorName)
                                put("displayName", commit.authorName)
                            }
                            put("authorTimestamp", commit.authorTimestamp)
                            putJsonArray("parents") {}
                        }
                    }
                }
                put("size", pr.commits.size)
                put("isLastPage", true)
                put("start", 0)
            }.toString(),
            ContentType.Application.Json
        )
    }

    // GET /rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests/{prId}/changes
    get("/rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests/{prId}/changes") {
        val state = stateProvider()
        val prId = call.parameters["prId"]?.toIntOrNull()
        val pr = prId?.let { state.prs[it] }
        if (pr == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        call.respondText(
            buildJsonObject {
                putJsonArray("values") {
                    pr.changes.forEach { change ->
                        addJsonObject {
                            putJsonObject("path") {
                                put("toString", change.path)
                                put("name", change.path.substringAfterLast('/'))
                            }
                            put("type", change.type)
                            put("nodeType", "FILE")
                        }
                    }
                }
                put("isLastPage", true)
            }.toString(),
            ContentType.Application.Json
        )
    }

    // GET /rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests/{prId}/diff
    // Client sends Accept: text/plain; returns unified diff as plain text.
    get("/rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests/{prId}/diff") {
        val state = stateProvider()
        val prId = call.parameters["prId"]?.toIntOrNull()
        val pr = prId?.let { state.prs[it] }
        if (pr == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        call.respondText(pr.diff, ContentType.Text.Plain)
    }

    // GET /rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests/{prId}/merge
    // Returns merge precondition check (canMerge / vetoes).
    get("/rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests/{prId}/merge") {
        val state = stateProvider()
        val prId = call.parameters["prId"]?.toIntOrNull()
        val pr = prId?.let { state.prs[it] }
        if (pr == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        val unapproved = pr.reviewers.filter { !it.approved }
        val canMerge = pr.state == "OPEN" && unapproved.isEmpty()
        call.respondText(
            buildJsonObject {
                put("canMerge", canMerge)
                put("conflicted", false)
                put("outcome", if (canMerge) "CLEAN" else "UNRESOLVED")
                putJsonArray("vetoes") {
                    if (unapproved.isNotEmpty()) {
                        addJsonObject {
                            put("summaryMessage", "Not all required reviewers have approved.")
                            put("detailedMessage", "Unapproved: " +
                                unapproved.joinToString(", ") { it.user.displayName })
                        }
                    }
                }
            }.toString(),
            ContentType.Application.Json
        )
    }

    // --- Browse (file content) ----------------------------------------------

    // GET /rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/browse/{path...}
    get("/rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/browse/{path...}") {
        val filePath = call.parameters.getAll("path")?.joinToString("/") ?: "unknown"
        call.respondText(
            "// Mock file content for: $filePath\n// Replace with real content.\n",
            ContentType.Text.Plain
        )
    }

    // --- Merge strategy settings -------------------------------------------

    // GET /rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/settings/pull-requests/git
    get("/rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/settings/pull-requests/git") {
        call.respondText(mergeConfigJson(), ContentType.Application.Json)
    }

    // GET /rest/api/1.0/projects/{projectKey}/settings/pull-requests/git
    // Fallback: client tries repo URL first, then falls back to project URL on 404.
    get("/rest/api/1.0/projects/{projectKey}/settings/pull-requests/git") {
        call.respondText(mergeConfigJson(), ContentType.Application.Json)
    }

    // === Write endpoints ====================================================
    // These mutate state and return realistic success responses so the plugin's
    // "verify dialog then cancel" UI flows render correctly.

    // POST /rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests/{prId}/merge
    post("/rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests/{prId}/merge") {
        val state = stateProvider()
        val prId = call.parameters["prId"]?.toIntOrNull()
        val pr = prId?.let { state.prs[it] }
        if (pr == null) {
            call.respond(HttpStatusCode.NotFound)
            return@post
        }
        pr.state = "MERGED"
        pr.version++
        pr.updatedDate = System.currentTimeMillis()
        call.respondText(prDetailToJson(pr).toString(), ContentType.Application.Json)
    }

    // POST /rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests/{prId}/approve
    post("/rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests/{prId}/approve") {
        val state = stateProvider()
        val prId = call.parameters["prId"]?.toIntOrNull()
        val pr = prId?.let { state.prs[it] }
        if (pr == null) {
            call.respond(HttpStatusCode.NotFound)
            return@post
        }
        val currentUser = state.currentUser
        val idx = pr.reviewers.indexOfFirst { it.user.name == currentUser.name }
        if (idx >= 0) {
            pr.reviewers[idx] = pr.reviewers[idx].copy(approved = true, status = "APPROVED")
        } else {
            pr.reviewers.add(MockBitbucketReviewer(currentUser, approved = true, status = "APPROVED"))
        }
        pr.version++
        pr.updatedDate = System.currentTimeMillis()
        // Response shape: BitbucketPrReviewer
        call.respondText(
            buildJsonObject {
                put("user", userToJson(currentUser))
                put("role", "REVIEWER")
                put("approved", true)
                put("status", "APPROVED")
            }.toString(),
            ContentType.Application.Json
        )
    }

    // DELETE /rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests/{prId}/approve
    delete("/rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests/{prId}/approve") {
        val state = stateProvider()
        val prId = call.parameters["prId"]?.toIntOrNull()
        val pr = prId?.let { state.prs[it] }
        if (pr != null) {
            val currentUser = state.currentUser
            val idx = pr.reviewers.indexOfFirst { it.user.name == currentUser.name }
            if (idx >= 0) {
                pr.reviewers[idx] = pr.reviewers[idx].copy(approved = false, status = "UNAPPROVED")
                pr.version++
                pr.updatedDate = System.currentTimeMillis()
            }
        }
        call.respond(HttpStatusCode.NoContent)
    }

    // POST /rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests/{prId}/decline
    post("/rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests/{prId}/decline") {
        val state = stateProvider()
        val prId = call.parameters["prId"]?.toIntOrNull()
        val pr = prId?.let { state.prs[it] }
        if (pr == null) {
            call.respond(HttpStatusCode.NotFound)
            return@post
        }
        pr.state = "DECLINED"
        pr.version++
        pr.updatedDate = System.currentTimeMillis()
        call.respondText(prDetailToJson(pr).toString(), ContentType.Application.Json)
    }

    // PUT /rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests/{prId}/participants/{username}
    put("/rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests/{prId}/participants/{username}") {
        val state = stateProvider()
        val prId = call.parameters["prId"]?.toIntOrNull()
        val username = call.parameters["username"] ?: ""
        val pr = prId?.let { state.prs[it] }
        if (pr == null) {
            call.respond(HttpStatusCode.NotFound)
            return@put
        }
        val body = runCatching { Json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
        val newStatus = body?.get("status")?.jsonPrimitive?.content ?: "APPROVED"
        val approved = newStatus == "APPROVED"
        val existing = pr.reviewers.find { it.user.name == username }
        if (existing != null) {
            val idx = pr.reviewers.indexOf(existing)
            pr.reviewers[idx] = existing.copy(approved = approved, status = newStatus)
        }
        pr.version++
        pr.updatedDate = System.currentTimeMillis()
        call.respondText(
            buildJsonObject {
                put("user", userToJson(existing?.user ?: state.currentUser))
                put("role", "REVIEWER")
                put("approved", approved)
                put("status", newStatus)
            }.toString(),
            ContentType.Application.Json
        )
    }

    // DELETE /rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests/{prId}/participants/{username}
    delete("/rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests/{prId}/participants/{username}") {
        val state = stateProvider()
        val prId = call.parameters["prId"]?.toIntOrNull()
        val username = call.parameters["username"] ?: ""
        val pr = prId?.let { state.prs[it] }
        if (pr != null) {
            pr.reviewers.removeIf { it.user.name == username }
            pr.version++
            pr.updatedDate = System.currentTimeMillis()
        }
        call.respond(HttpStatusCode.NoContent)
    }

    // POST /rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests/{prId}/comments
    post("/rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests/{prId}/comments") {
        val state = stateProvider()
        val prId = call.parameters["prId"]?.toIntOrNull()
        val pr = prId?.let { state.prs[it] }
        if (pr == null) {
            call.respond(HttpStatusCode.NotFound)
            return@post
        }
        val body = runCatching { Json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
        val text = body?.get("text")?.jsonPrimitive?.content ?: ""
        val commentId = state.nextCommentId++
        val now = System.currentTimeMillis()
        val comment = MockBitbucketComment(
            id = commentId,
            text = text,
            authorName = state.currentUser.name,
            authorDisplayName = state.currentUser.displayName,
            createdDate = now,
        )
        pr.comments.add(comment)
        // Response shape: BitbucketPrComment
        call.respondText(
            buildJsonObject {
                put("id", comment.id)
                put("text", comment.text)
                put("author", userToJson(state.currentUser))
                put("createdDate", now)
                put("updatedDate", now)
            }.toString(),
            ContentType.Application.Json,
            HttpStatusCode.Created
        )
    }

    // PUT /rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests/{prId}/comments/{commentId}
    put("/rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests/{prId}/comments/{commentId}") {
        val state = stateProvider()
        val prId = call.parameters["prId"]?.toIntOrNull()
        val commentId = call.parameters["commentId"]?.toLongOrNull()
        val pr = prId?.let { state.prs[it] }
        val comment = pr?.comments?.find { it.id == commentId }
        if (pr == null || comment == null) {
            call.respond(HttpStatusCode.NotFound)
            return@put
        }
        val body = runCatching { Json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
        val newText = body?.get("text")?.jsonPrimitive?.content ?: comment.text
        comment.text = newText
        comment.version++
        call.respondText(commentResponseToJson(comment).toString(), ContentType.Application.Json)
    }

    // DELETE /rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests/{prId}/comments/{commentId}
    delete("/rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests/{prId}/comments/{commentId}") {
        val state = stateProvider()
        val prId = call.parameters["prId"]?.toIntOrNull()
        val commentId = call.parameters["commentId"]?.toLongOrNull()
        val pr = prId?.let { state.prs[it] }
        if (pr != null && commentId != null) {
            pr.comments.removeIf { it.id == commentId }
        }
        call.respond(HttpStatusCode.NoContent)
    }

    // POST /rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests
    // Returns a realistic PR-created response using the request body.
    post("/rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests") {
        val state = stateProvider()
        val projectKey = call.parameters["projectKey"] ?: ""
        val repoSlug = call.parameters["repoSlug"] ?: ""
        val body = runCatching { Json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
        val title = body?.get("title")?.jsonPrimitive?.content ?: "New PR"
        val description = body?.get("description")?.jsonPrimitive?.content ?: ""
        val fromId = body?.get("fromRef")?.jsonObject?.get("id")?.jsonPrimitive?.content
            ?: "refs/heads/feature/new"
        val toId = body?.get("toRef")?.jsonObject?.get("id")?.jsonPrimitive?.content
            ?: "refs/heads/main"
        val newId = (state.prs.keys.maxOrNull() ?: 0) + 1
        val now = System.currentTimeMillis()
        val newPr = MockBitbucketPr(
            id = newId,
            title = title,
            description = description,
            state = "OPEN",
            version = 0,
            fromRef = MockBitbucketRef(
                id = fromId,
                displayId = fromId.removePrefix("refs/heads/"),
                latestCommit = "0000000000000000000000000000000000000000",
                repoSlug = repoSlug,
                projectKey = projectKey,
            ),
            toRef = MockBitbucketRef(
                id = toId,
                displayId = toId.removePrefix("refs/heads/"),
                latestCommit = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2",
                repoSlug = repoSlug,
                projectKey = projectKey,
            ),
            author = state.currentUser,
            reviewers = mutableListOf(),
            createdDate = now,
            updatedDate = now,
            comments = mutableListOf(),
            commits = emptyList(),
            changes = emptyList(),
            diff = "",
        )
        state.prs[newId] = newPr
        // Response shape: BitbucketPrResponse (id, title, state, links, fromRef, toRef)
        call.respondText(
            buildJsonObject {
                put("id", newId)
                put("title", title)
                put("state", "OPEN")
                putJsonObject("links") {
                    putJsonArray("self") {
                        addJsonObject {
                            put("href", "http://localhost:8480/projects/$projectKey/repos/$repoSlug/pull-requests/$newId")
                        }
                    }
                }
                putJsonObject("fromRef") {
                    put("id", fromId)
                    put("displayId", fromId.removePrefix("refs/heads/"))
                    put("latestCommit", "0000000000000000000000000000000000000000")
                }
                putJsonObject("toRef") {
                    put("id", toId)
                    put("displayId", toId.removePrefix("refs/heads/"))
                    put("latestCommit", "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2")
                }
            }.toString(),
            ContentType.Application.Json,
            HttpStatusCode.Created
        )
    }
}

// ---------------------------------------------------------------------------
// Private helpers — render mock domain objects to JSON matching plugin DTOs
// ---------------------------------------------------------------------------

private fun userToJson(user: MockBitbucketUser): JsonObject = buildJsonObject {
    put("name", user.name)
    put("displayName", user.displayName)
    put("emailAddress", user.emailAddress)
}

private fun refToJson(ref: MockBitbucketRef): JsonObject = buildJsonObject {
    put("id", ref.id)
    put("displayId", ref.displayId)
    put("latestCommit", ref.latestCommit)
    putJsonObject("repository") {
        put("slug", ref.repoSlug)
        put("name", ref.repoSlug)
        putJsonObject("project") {
            put("key", ref.projectKey)
        }
    }
}

/** Full PR detail shape — matches BitbucketPrDetail (and the simpler BitbucketPrResponse). */
private fun prDetailToJson(pr: MockBitbucketPr): JsonObject = buildJsonObject {
    put("id", pr.id)
    put("title", pr.title)
    put("description", pr.description)
    put("state", pr.state)
    put("version", pr.version)
    put("createdDate", pr.createdDate)
    put("updatedDate", pr.updatedDate)
    putJsonObject("author") {
        put("user", userToJson(pr.author))
    }
    putJsonArray("reviewers") {
        pr.reviewers.forEach { reviewer ->
            addJsonObject {
                put("user", userToJson(reviewer.user))
                put("role", reviewer.role)
                put("approved", reviewer.approved)
                put("status", reviewer.status)
            }
        }
    }
    put("fromRef", refToJson(pr.fromRef))
    put("toRef", refToJson(pr.toRef))
    putJsonObject("links") {
        putJsonArray("self") {
            addJsonObject {
                put("href", "http://localhost:8480/projects/${pr.fromRef.projectKey}" +
                    "/repos/${pr.fromRef.repoSlug}/pull-requests/${pr.id}")
            }
        }
    }
}

/** Comment response shape — matches BitbucketPrCommentResponse. */
private fun commentResponseToJson(comment: MockBitbucketComment): JsonObject = buildJsonObject {
    put("id", comment.id)
    put("version", comment.version)
    put("text", comment.text)
    putJsonObject("author") {
        put("name", comment.authorName)
        put("displayName", comment.authorDisplayName)
    }
    put("createdDate", comment.createdDate)
    put("updatedDate", comment.createdDate)
    put("state", "OPEN")
    put("severity", "NORMAL")
    putJsonArray("comments") {}
}

/** Merge config response — matches BitbucketRepoSettingsResponse. */
private fun mergeConfigJson(): String = buildJsonObject {
    putJsonObject("mergeConfig") {
        putJsonObject("defaultStrategy") {
            put("id", "no-ff")
            put("name", "No fast forward")
            put("description", "Always create a merge commit.")
            put("enabled", true)
        }
        putJsonArray("strategies") {
            listOf(
                Triple("no-ff", "No fast forward", "Always create a merge commit."),
                Triple("ff", "Fast forward", "Fast-forward if possible, otherwise merge commit."),
                Triple("ff-only", "Fast forward only", "Only merge if fast-forward is possible."),
                Triple("squash", "Squash", "Squash all commits into one."),
                Triple("squash-ff-only", "Squash, fast-forward only", "Squash; only if fast-forward is possible."),
            ).forEachIndexed { idx, (id, name, desc) ->
                addJsonObject {
                    put("id", id)
                    put("name", name)
                    put("description", desc)
                    put("enabled", idx < 4)  // squash-ff-only disabled by default
                }
            }
        }
    }
}.toString()
