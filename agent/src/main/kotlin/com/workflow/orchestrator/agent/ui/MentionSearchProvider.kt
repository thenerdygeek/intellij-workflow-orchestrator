package com.workflow.orchestrator.agent.ui

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.search.PsiShortNamesCache
import com.workflow.orchestrator.core.model.jira.JiraCommentData
import com.workflow.orchestrator.core.model.jira.JiraTicketData
import com.workflow.orchestrator.core.services.JiraService
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Provides search results for @ mention autocomplete.
 * Searches project files (VFS), symbols (PSI), tools, and skills.
 * Returns JSON arrays for the JCEF dropdown.
 */
class MentionSearchProvider(private val project: Project) {

    /** Pre-fetched ticket data from validation — consumed by MentionContextBuilder on send. */
    data class CachedTicketContext(
        val ticket: JiraTicketData,
        val comments: List<JiraCommentData>,
        val timestamp: Long = System.currentTimeMillis()
    )

    companion object {
        private val LOG = Logger.getInstance(MentionSearchProvider::class.java)
        private const val MAX_RESULTS = 15
        private const val MAX_RESULTS_PER_TYPE = 10
        private const val MAX_COLLECT = 100 // collect more, then rank and trim
        private val FILE_EXTENSIONS = setOf("kt", "java", "xml", "yaml", "yml", "json", "properties", "gradle", "md", "html", "css", "js", "ts", "py", "go", "rs")
        /** Directories skipped by file/folder traversal. */
        private val EXCLUDED_DIRS = setOf("node_modules", "build", "out")
        /** Cache entries older than 5 minutes are considered stale. */
        private const val CACHE_TTL_MS = 5 * 60 * 1000L

        /** True if a directory should be skipped during traversal (hidden or excluded). */
        private fun isSkippedDir(name: String): Boolean =
            name.startsWith(".") || name in EXCLUDED_DIRS
    }

    /**
     * Cache of pre-fetched ticket data. Populated during validation (chip turns green),
     * consumed during context building (on send). Entries auto-expire after [CACHE_TTL_MS].
     */
    private val ticketContextCache = ConcurrentHashMap<String, CachedTicketContext>()

    /**
     * Consume (get and remove) cached ticket context for a key.
     * Returns null if not cached or stale.
     */
    fun consumeCachedTicket(ticketKey: String): CachedTicketContext? {
        val cached = ticketContextCache.remove(ticketKey) ?: return null
        return if (System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) cached else null
    }

    /**
     * Search for mentions by type and query.
     * @param type One of: "all", "file", "symbol", "tool", "skill", "categories"
     * @param query Search query (empty = show all/top results)
     * @return JSON string of results array
     */
    fun search(type: String, query: String): String {
        return try {
            when (type) {
                "categories" -> buildCategoriesJson()
                "all" -> searchAll(query)
                "file" -> searchFiles(query)
                "folder" -> searchFolders(query)
                "symbol" -> searchSymbols(query)
                "tool" -> searchTools(query)
                "skill" -> searchSkills(query)
                else -> "[]"
            }
        } catch (e: Exception) {
            LOG.debug("MentionSearchProvider: search failed for type=$type query=$query: ${e.message}")
            "[]"
        }
    }

    /**
     * Search files, folders, and symbols together with server-side ranking.
     * Returns up to [MAX_RESULTS_PER_TYPE] per type, ranked by name relevance.
     */
    private fun searchAll(query: String): String {
        val results = mutableListOf<JsonObject>()
        val basePath = project.basePath ?: return "[]"
        val lowerQuery = query.lowercase()

        // Empty query: show open editor tabs with the active file first
        if (lowerQuery.isBlank()) {
            try {
                val fem = FileEditorManager.getInstance(project)
                val selectedFile = fem.selectedFiles.firstOrNull()
                val openFiles = fem.openFiles.toList()

                val seen = mutableSetOf<String>()
                val orderedFiles = buildList {
                    if (selectedFile != null) {
                        add(selectedFile)
                        seen.add(selectedFile.path)
                    }
                    for (f in openFiles) {
                        if (f.path !in seen && size < 8) {
                            add(f)
                            seen.add(f.path)
                        }
                    }
                }

                for (file in orderedFiles) {
                    if (file.isDirectory) continue
                    val relativePath = file.path.removePrefix("$basePath/")
                    results.add(buildJsonObject {
                        put("type", JsonPrimitive("file"))
                        put("label", JsonPrimitive(file.name))
                        put("path", JsonPrimitive(relativePath))
                        put("description", JsonPrimitive(relativePath.substringBeforeLast('/')))
                    })
                }
            } catch (_: Exception) {}
            return JsonArray(results).toString()
        }

        // Collect files — gather broadly, then rank
        val fileResults = mutableListOf<ScoredResult>()
        val folderResults = mutableListOf<ScoredResult>()
        try {
            val roots = ProjectRootManager.getInstance(project).contentSourceRoots
            for (root in roots) {
                if (fileResults.size >= MAX_COLLECT && folderResults.size >= MAX_COLLECT) break
                collectAllRanked(root, lowerQuery, basePath, fileResults, folderResults)
            }
        } catch (_: Exception) {}

        // Sort by relevance and take top N per type
        fileResults.sortByDescending { it.score }
        folderResults.sortByDescending { it.score }

        for (r in fileResults.take(MAX_RESULTS_PER_TYPE)) results.add(r.json)
        for (r in folderResults.take(MAX_RESULTS_PER_TYPE)) results.add(r.json)

        // Symbols (already ranked by PSI cache lookup)
        if (query.length >= 2) {
            try {
                val symbolJson = searchSymbols(query)
                val symbols = Json.parseToJsonElement(symbolJson).jsonArray
                for (s in symbols.take(MAX_RESULTS_PER_TYPE)) results.add(s.jsonObject)
            } catch (_: Exception) {}
        }

        return JsonArray(results).toString()
    }

    /** Score a match: higher = more relevant. Name matches always beat path-only matches. */
    private fun scoreMatch(name: String, relativePath: String, query: String): Int {
        val lowerName = name.lowercase()
        val nameNoExt = lowerName.substringBeforeLast('.')

        // Name starts with query → highest
        if (nameNoExt.startsWith(query)) return 100

        // Word boundary in name (e.g., "MyServiceTest" matches "test" at word boundary)
        if (nameNoExt.contains(query) && (nameNoExt.indexOf(query).let { idx ->
                idx == 0 || nameNoExt[idx - 1].isUpperCase() != nameNoExt[idx].isUpperCase() ||
                nameNoExt[idx - 1] == '_' || nameNoExt[idx - 1] == '-'
            })) return 85

        // Substring anywhere in name
        if (lowerName.contains(query)) return 75

        // Path-only match (file lives in a matching directory but name doesn't match)
        if (relativePath.lowercase().contains(query)) return 30

        return 0
    }

    /** Collect files and folders with relevance scores. */
    private fun collectAllRanked(
        dir: com.intellij.openapi.vfs.VirtualFile,
        query: String,
        basePath: String,
        fileResults: MutableList<ScoredResult>,
        folderResults: MutableList<ScoredResult>
    ) {
        if (isSkippedDir(dir.name)) return

        // Score this directory as a folder result
        if (query.isNotBlank()) {
            val relativePath = dir.path.removePrefix("$basePath/")
            val score = scoreMatch(dir.name, relativePath, query)
            if (score > 0 && folderResults.size < MAX_COLLECT) {
                folderResults.add(ScoredResult(score, buildJsonObject {
                    put("type", JsonPrimitive("folder"))
                    put("label", JsonPrimitive(dir.name + "/"))
                    put("path", JsonPrimitive(relativePath))
                    put("description", JsonPrimitive(relativePath))
                }))
            }
        }

        for (child in dir.children) {
            if (child.isDirectory) {
                collectAllRanked(child, query, basePath, fileResults, folderResults)
            } else {
                if (child.extension?.lowercase() !in FILE_EXTENSIONS) continue
                if (query.isBlank()) continue // all:'' with no query returns nothing (categories handles empty)
                val relativePath = child.path.removePrefix("$basePath/")
                val score = scoreMatch(child.name, relativePath, query)
                if (score > 0 && fileResults.size < MAX_COLLECT) {
                    fileResults.add(ScoredResult(score, buildJsonObject {
                        put("type", JsonPrimitive("file"))
                        put("label", JsonPrimitive(child.name))
                        put("path", JsonPrimitive(relativePath))
                        put("description", JsonPrimitive(relativePath.substringBeforeLast('/')))
                    }))
                }
            }
        }
    }

    private data class ScoredResult(val score: Int, val json: JsonObject)

    private fun buildCategoriesJson(): String = buildJsonArray {
        add(buildJsonObject {
            put("type", JsonPrimitive("file"))
            put("icon", JsonPrimitive("[F]"))
            put("label", JsonPrimitive("File"))
            put("description", JsonPrimitive("Search project files"))
        })
        add(buildJsonObject {
            put("type", JsonPrimitive("folder"))
            put("icon", JsonPrimitive("[D]"))
            put("label", JsonPrimitive("Folder"))
            put("description", JsonPrimitive("Search project directories"))
        })
        add(buildJsonObject {
            put("type", JsonPrimitive("symbol"))
            put("icon", JsonPrimitive("&#x2726;"))
            put("label", JsonPrimitive("Symbol"))
            put("description", JsonPrimitive("Search classes, methods"))
        })
        add(buildJsonObject {
            put("type", JsonPrimitive("tool"))
            put("icon", JsonPrimitive("&#x2699;"))
            put("label", JsonPrimitive("Tool"))
            put("description", JsonPrimitive("Agent tools"))
        })
        add(buildJsonObject {
            put("type", JsonPrimitive("skill"))
            put("icon", JsonPrimitive("&#x26A1;"))
            put("label", JsonPrimitive("Skill"))
            put("description", JsonPrimitive("Workflow skills"))
        })
    }.toString()

    private fun searchFiles(query: String): String =
        collectFromRoots(query) { root, q, basePath, results -> collectFiles(root, q, basePath, results) }

    private fun collectFiles(
        dir: com.intellij.openapi.vfs.VirtualFile,
        query: String,
        basePath: String,
        results: MutableList<JsonObject>
    ) {
        if (results.size >= MAX_RESULTS) return
        for (child in dir.children) {
            if (results.size >= MAX_RESULTS) return
            if (child.isDirectory) {
                if (isSkippedDir(child.name)) continue
                collectFiles(child, query, basePath, results)
            } else {
                if (child.extension?.lowercase() !in FILE_EXTENSIONS) continue
                if (query.isBlank() || child.name.lowercase().contains(query) || child.path.lowercase().contains(query)) {
                    val relativePath = child.path.removePrefix("$basePath/")
                    results.add(buildJsonObject {
                        put("type", JsonPrimitive("file"))
                        put("label", JsonPrimitive(child.name))
                        put("path", JsonPrimitive(relativePath))
                        put("description", JsonPrimitive(relativePath.substringBeforeLast('/')))
                    })
                }
            }
        }
    }

    private fun searchFolders(query: String): String =
        collectFromRoots(query) { root, q, basePath, results -> collectFolders(root, q, basePath, results) }

    /**
     * Walk all source roots collecting matching results until [MAX_RESULTS] is reached.
     * Returns a JSON array string suitable for the JCEF dropdown.
     */
    private inline fun collectFromRoots(
        query: String,
        collect: (com.intellij.openapi.vfs.VirtualFile, String, String, MutableList<JsonObject>) -> Unit
    ): String {
        val lowerQuery = query.lowercase()
        val results = mutableListOf<JsonObject>()
        val basePath = project.basePath ?: return "[]"

        try {
            val roots = ProjectRootManager.getInstance(project).contentSourceRoots
            for (root in roots) {
                if (results.size >= MAX_RESULTS) break
                collect(root, lowerQuery, basePath, results)
            }
        } catch (_: Exception) {}

        return JsonArray(results).toString()
    }

    private fun collectFolders(
        dir: com.intellij.openapi.vfs.VirtualFile,
        query: String,
        basePath: String,
        results: MutableList<JsonObject>
    ) {
        if (results.size >= MAX_RESULTS) return
        if (isSkippedDir(dir.name)) return
        val relativePath = dir.path.removePrefix("$basePath/")
        if (query.isBlank() || dir.name.lowercase().contains(query) || relativePath.lowercase().contains(query)) {
            results.add(buildJsonObject {
                put("type", JsonPrimitive("folder"))
                put("label", JsonPrimitive(dir.name + "/"))
                put("path", JsonPrimitive(relativePath))
                put("description", JsonPrimitive(relativePath))
            })
        }
        for (child in dir.children) {
            if (child.isDirectory && results.size < MAX_RESULTS) {
                collectFolders(child, query, basePath, results)
            }
        }
    }

    private fun searchSymbols(query: String): String {
        if (query.length < 2) return "[]" // Need at least 2 chars for symbol search
        val results = mutableListOf<JsonObject>()

        try {
            ReadAction.run<Exception> {
                val cache = PsiShortNamesCache.getInstance(project)
                // Search classes
                val classNames = cache.allClassNames.filter { it.lowercase().contains(query.lowercase()) }.take(MAX_RESULTS)
                for (name in classNames) {
                    if (results.size >= MAX_RESULTS) break
                    val classes = cache.getClassesByName(name, com.intellij.psi.search.GlobalSearchScope.projectScope(project))
                    for (cls in classes) {
                        if (results.size >= MAX_RESULTS) break
                        val qualifiedName = cls.qualifiedName ?: name
                        val filePath = cls.containingFile?.virtualFile?.path?.let {
                            project.basePath?.let { bp -> it.removePrefix("$bp/") } ?: it
                        } ?: ""
                        results.add(buildJsonObject {
                            put("type", JsonPrimitive("symbol"))
                            put("label", JsonPrimitive(name))
                            put("path", JsonPrimitive(qualifiedName))
                            put("description", JsonPrimitive(filePath))
                        })
                    }
                }
            }
        } catch (_: Exception) {}

        return JsonArray(results).toString()
    }

    private fun searchTools(@Suppress("UNUSED_PARAMETER") query: String): String {
        // Not wired — @tool autocomplete deferred (see project_remaining_wiring_gaps.md)
        return "[]"
    }

    private fun searchSkills(query: String): String {
        // Discover all skills (bundled + user) for search — matches Cline's lazy discovery
        val projectPath = project.basePath ?: ""
        val allDiscovered = com.workflow.orchestrator.agent.prompt.InstructionLoader.discoverSkills(projectPath)
        val skills = com.workflow.orchestrator.agent.prompt.InstructionLoader.getAvailableSkills(allDiscovered)
        val lowerQuery = query.lowercase()
        val filtered = if (lowerQuery.isBlank()) {
            skills
        } else {
            skills.filter { skill ->
                skill.name.lowercase().contains(lowerQuery) ||
                skill.description.lowercase().contains(lowerQuery)
            }
        }.take(MAX_RESULTS)

        return buildJsonArray {
            for (skill in filtered) {
                add(buildJsonObject {
                    put("type", JsonPrimitive("skill"))
                    put("label", JsonPrimitive(skill.name))
                    put("path", JsonPrimitive(skill.name))
                    put("description", JsonPrimitive(skill.description.take(80)))
                })
            }
        }.toString()
    }

    /**
     * Search Jira tickets by key or summary.
     * Returns JSON array of results for the ticket dropdown.
     *
     * @param query Ticket key (e.g. "PROJ-123"), key prefix (e.g. "PROJ"), or summary text
     */
    /** Cached sprint tickets — pre-populated by Sprint tab via EventBus, or loaded on first use. */
    @Volatile private var cachedSprintTickets: List<JiraTicketData>? = null
    private val sprintTicketsMutex = Mutex()

    /** Called when Sprint tab loads/refreshes data — pre-populates cache so # autocomplete is instant. */
    fun onSprintDataLoaded(tickets: List<JiraTicketData>) {
        cachedSprintTickets = tickets
        LOG.info("MentionSearchProvider: cache pre-populated with ${tickets.size} sprint tickets from Sprint tab")
    }

    suspend fun searchTickets(query: String): String {
        return try {
            val jiraService = try {
                project.getService(JiraService::class.java)
            } catch (e: Exception) {
                LOG.warn("MentionSearchProvider: JiraService not available: ${e.message}")
                return "[]"
            }

            // Load sprint tickets — mirrors the Sprint tab's logic:
            // 1. Use configured board ID from settings
            // 2. If not configured, auto-discover (prefer scrum boards)
            // 3. For scrum boards: load active sprint issues
            // 4. For kanban/other boards: load board issues directly
            // Mutex prevents concurrent callers from double-fetching.
            val sprintTickets = cachedSprintTickets ?: sprintTicketsMutex.withLock {
                // Double-check inside lock — another coroutine may have loaded while we waited
                cachedSprintTickets ?: run {
                val settings = com.workflow.orchestrator.core.settings.PluginSettings.getInstance(project)
                var boardId = settings.state.jiraBoardId
                var boardType = settings.state.jiraBoardType ?: ""
                LOG.info("MentionSearchProvider: boardId=$boardId, boardType='$boardType'")

                // Auto-discover board if not configured (same as Sprint tab)
                if (boardId <= 0) {
                    val boards = jiraService.getBoards()
                    if (!boards.isError && boards.data.isNotEmpty()) {
                        val board = boards.data.firstOrNull { it.type == "scrum" } ?: boards.data.first()
                        boardId = board.id
                        boardType = board.type
                        LOG.info("MentionSearchProvider: auto-discovered board ${board.name} (id=$boardId, type=$boardType)")
                    } else {
                        LOG.warn("MentionSearchProvider: board discovery failed: ${if (boards.isError) boards.summary else "no boards"}")
                    }
                }

                if (boardId <= 0) {
                    LOG.warn("MentionSearchProvider: no board ID available, returning empty")
                    return@run emptyList()
                }

                if (boardType == "scrum" || boardType.isBlank()) {
                    // Scrum board: get active sprint issues
                    val sprints = jiraService.getAvailableSprints(boardId)
                    if (sprints.isError) {
                        LOG.warn("MentionSearchProvider: getAvailableSprints failed: ${sprints.summary}")
                    }
                    val activeSprint = if (!sprints.isError) {
                        sprints.data.firstOrNull { it.state == "active" }
                    } else null

                    if (activeSprint != null) {
                        LOG.info("MentionSearchProvider: loading issues from sprint '${activeSprint.name}' (id=${activeSprint.id})")
                        val issues = jiraService.getSprintIssues(activeSprint.id)
                        if (issues.isError) {
                            LOG.warn("MentionSearchProvider: getSprintIssues failed: ${issues.summary}")
                            emptyList()
                        } else {
                            LOG.info("MentionSearchProvider: loaded ${issues.data.size} sprint tickets")
                            issues.data
                        }
                    } else {
                        // No active sprint — fall back to board issues
                        LOG.info("MentionSearchProvider: no active sprint, falling back to board issues")
                        val issues = jiraService.getBoardIssues(boardId)
                        if (issues.isError) {
                            LOG.warn("MentionSearchProvider: getBoardIssues failed: ${issues.summary}")
                            emptyList()
                        } else {
                            issues.data
                        }
                    }
                } else {
                    // Kanban/other board: load board issues directly
                    LOG.info("MentionSearchProvider: kanban board, loading board issues")
                    val issues = jiraService.getBoardIssues(boardId)
                    if (issues.isError) {
                        LOG.warn("MentionSearchProvider: getBoardIssues failed: ${issues.summary}")
                        emptyList()
                    } else {
                        issues.data
                    }
                }
                }.also { cachedSprintTickets = it }
            }

            // Status priority: open/active statuses float to the top so they appear before
            // Done/Closed/Resolved. The API returns issues in arbitrary order (often alphabetical
            // by key), so without sorting a sprint that's mostly done would show only closed
            // tickets in the first 8 results.
            val statusOrder = mapOf(
                "In Progress" to 0,
                "In Review"   to 1,
                "To Do"       to 2,
                "Open"        to 2,
                "Reopened"    to 2,
                "Done"        to 3,
                "Closed"      to 3,
                "Resolved"    to 3,
            )
            val filtered = if (query.isBlank()) {
                sprintTickets
            } else {
                val q = query.uppercase()
                sprintTickets.filter { ticket ->
                    ticket.key.uppercase().contains(q) ||
                    ticket.summary.uppercase().contains(q)
                }
            }.sortedBy { statusOrder[it.status] ?: 2 }.take(8)

            buildJsonArray {
                for (ticket in filtered) {
                    add(buildJsonObject {
                        put("type", JsonPrimitive("ticket"))
                        put("label", JsonPrimitive(ticket.key))
                        put("path", JsonPrimitive(ticket.key))
                        put("description", JsonPrimitive(ticket.summary.take(60)))
                        put("icon", JsonPrimitive(ticket.status))
                    })
                }
            }.toString()
        } catch (e: Exception) {
            LOG.debug("MentionSearchProvider: ticket search failed for query=$query: ${e.message}")
            "[]"
        }
    }

    /** Clear cached sprint tickets so the next # search fetches fresh data. */
    fun invalidateTicketCache() {
        cachedSprintTickets = null
    }

    /**
     * Validate a ticket key by fetching it from Jira.
     * Also pre-fetches comments in parallel and caches both so that
     * [MentionContextBuilder] can use them on send without redundant API calls.
     * Returns JSON: {"valid":true,"summary":"..."} or {"valid":false}
     */
    suspend fun validateTicket(ticketKey: String): String {
        return try {
            val jiraService = try {
                project.getService(JiraService::class.java)
            } catch (_: Exception) { return """{"valid":false}""" }

            // Fetch ticket + comments in parallel
            val (ticketResult, commentsResult) = coroutineScope {
                val ticketDeferred = async { jiraService.getTicket(ticketKey) }
                val commentsDeferred = async { jiraService.getComments(ticketKey) }
                ticketDeferred.await() to commentsDeferred.await()
            }

            if (!ticketResult.isError) {
                val comments = if (!commentsResult.isError) commentsResult.data else emptyList()
                // Cache for MentionContextBuilder to consume on send
                ticketContextCache[ticketKey.uppercase()] = CachedTicketContext(
                    ticket = ticketResult.data,
                    comments = comments
                )
                buildJsonObject {
                    put("valid", JsonPrimitive(true))
                    put("summary", JsonPrimitive(ticketResult.data.summary))
                }.toString()
            } else {
                """{"valid":false}"""
            }
        } catch (e: Exception) {
            LOG.debug("MentionSearchProvider: ticket validation failed for $ticketKey: ${e.message}")
            """{"valid":false}"""
        }
    }
}
