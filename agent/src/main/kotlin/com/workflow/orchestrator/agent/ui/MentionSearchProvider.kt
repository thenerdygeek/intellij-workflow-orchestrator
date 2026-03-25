package com.workflow.orchestrator.agent.ui

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.search.PsiShortNamesCache
import com.workflow.orchestrator.core.services.JiraService
import com.workflow.orchestrator.core.services.ToolResult
import kotlinx.serialization.json.*

/**
 * Provides search results for @ mention autocomplete.
 * Searches project files (VFS), symbols (PSI), tools, and skills.
 * Returns JSON arrays for the JCEF dropdown.
 */
class MentionSearchProvider(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(MentionSearchProvider::class.java)
        private const val MAX_RESULTS = 15
        private val FILE_EXTENSIONS = setOf("kt", "java", "xml", "yaml", "yml", "json", "properties", "gradle", "md", "html", "css", "js", "ts", "py", "go", "rs")
    }

    /**
     * Search for mentions by type and query.
     * @param type One of: "file", "symbol", "tool", "skill", "categories"
     * @param query Search query (empty = show all/top results)
     * @return JSON string of results array
     */
    fun search(type: String, query: String): String {
        return try {
            when (type) {
                "categories" -> buildCategoriesJson()
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

    private fun buildCategoriesJson(): String = buildJsonArray {
        add(buildJsonObject {
            put("type", JsonPrimitive("file"))
            put("icon", JsonPrimitive("[F]"))
            put("label", JsonPrimitive("File"))
            put("hint", JsonPrimitive("Search project files"))
        })
        add(buildJsonObject {
            put("type", JsonPrimitive("folder"))
            put("icon", JsonPrimitive("[D]"))
            put("label", JsonPrimitive("Folder"))
            put("hint", JsonPrimitive("Search project directories"))
        })
        add(buildJsonObject {
            put("type", JsonPrimitive("symbol"))
            put("icon", JsonPrimitive("&#x2726;"))
            put("label", JsonPrimitive("Symbol"))
            put("hint", JsonPrimitive("Search classes, methods"))
        })
        add(buildJsonObject {
            put("type", JsonPrimitive("tool"))
            put("icon", JsonPrimitive("&#x2699;"))
            put("label", JsonPrimitive("Tool"))
            put("hint", JsonPrimitive("Agent tools"))
        })
        add(buildJsonObject {
            put("type", JsonPrimitive("skill"))
            put("icon", JsonPrimitive("&#x26A1;"))
            put("label", JsonPrimitive("Skill"))
            put("hint", JsonPrimitive("Workflow skills"))
        })
    }.toString()

    private fun searchFiles(query: String): String {
        val lowerQuery = query.lowercase()
        val results = mutableListOf<JsonObject>()
        val basePath = project.basePath ?: return "[]"

        try {
            val roots = ProjectRootManager.getInstance(project).contentSourceRoots
            for (root in roots) {
                if (results.size >= MAX_RESULTS) break
                collectFiles(root, lowerQuery, basePath, results)
            }
        } catch (_: Exception) {}

        return JsonArray(results).toString()
    }

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
                if (child.name.startsWith(".") || child.name == "node_modules" || child.name == "build" || child.name == "out") continue
                collectFiles(child, query, basePath, results)
            } else {
                if (child.extension?.lowercase() !in FILE_EXTENSIONS) continue
                if (query.isBlank() || child.name.lowercase().contains(query) || child.path.lowercase().contains(query)) {
                    val relativePath = child.path.removePrefix("$basePath/")
                    results.add(buildJsonObject {
                        put("type", JsonPrimitive("file"))
                        put("name", JsonPrimitive(child.name))
                        put("value", JsonPrimitive(relativePath))
                        put("desc", JsonPrimitive(relativePath.substringBeforeLast('/')))
                    })
                }
            }
        }
    }

    private fun searchFolders(query: String): String {
        val lowerQuery = query.lowercase()
        val results = mutableListOf<JsonObject>()
        val basePath = project.basePath ?: return "[]"

        try {
            val roots = ProjectRootManager.getInstance(project).contentSourceRoots
            for (root in roots) {
                if (results.size >= MAX_RESULTS) break
                collectFolders(root, lowerQuery, basePath, results)
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
        if (dir.name.startsWith(".") || dir.name == "node_modules" || dir.name == "build" || dir.name == "out") return
        val relativePath = dir.path.removePrefix("$basePath/")
        if (query.isBlank() || dir.name.lowercase().contains(query) || relativePath.lowercase().contains(query)) {
            results.add(buildJsonObject {
                put("type", JsonPrimitive("folder"))
                put("name", JsonPrimitive(dir.name + "/"))
                put("value", JsonPrimitive(relativePath))
                put("desc", JsonPrimitive(relativePath))
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
                            put("name", JsonPrimitive(name))
                            put("value", JsonPrimitive(qualifiedName))
                            put("desc", JsonPrimitive(filePath))
                        })
                    }
                }
            }
        } catch (_: Exception) {}

        return JsonArray(results).toString()
    }

    private fun searchTools(query: String): String {
        val agentService = try {
            com.workflow.orchestrator.agent.AgentService.getInstance(project)
        } catch (_: Exception) { return "[]" }

        val lowerQuery = query.lowercase()
        val tools = agentService.toolRegistry.allTools()
            .filter { lowerQuery.isBlank() || it.name.lowercase().contains(lowerQuery) || it.description.lowercase().contains(lowerQuery) }
            .take(MAX_RESULTS)

        return buildJsonArray {
            for (tool in tools) {
                add(buildJsonObject {
                    put("type", JsonPrimitive("tool"))
                    put("name", JsonPrimitive(tool.name))
                    put("value", JsonPrimitive(tool.name))
                    put("desc", JsonPrimitive(tool.description.take(60)))
                })
            }
        }.toString()
    }

    private fun searchSkills(query: String): String {
        val agentService = try {
            com.workflow.orchestrator.agent.AgentService.getInstance(project)
        } catch (_: Exception) { return "[]" }

        val lowerQuery = query.lowercase()
        val skills = agentService.currentSkillManager?.registry?.getUserInvocableSkills()
            ?.filter { lowerQuery.isBlank() || it.name.lowercase().contains(lowerQuery) || it.description.lowercase().contains(lowerQuery) }
            ?.take(MAX_RESULTS)
            ?: return "[]"

        return buildJsonArray {
            for (skill in skills) {
                add(buildJsonObject {
                    put("type", JsonPrimitive("skill"))
                    put("name", JsonPrimitive(skill.name))
                    put("value", JsonPrimitive(skill.name))
                    put("desc", JsonPrimitive(skill.description.take(60)))
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
    /** Cached sprint tickets — loaded once, reused for fast # autocomplete. */
    private var cachedSprintTickets: List<com.workflow.orchestrator.core.model.jira.JiraTicketData>? = null

    suspend fun searchTickets(query: String): String {
        return try {
            val jiraService = try {
                project.getService(JiraService::class.java)
            } catch (_: Exception) { return "[]" }

            // Load sprint tickets from the same source as the Sprint tab
            val sprintTickets = cachedSprintTickets ?: run {
                val settings = com.workflow.orchestrator.core.settings.PluginSettings.getInstance(project)
                val boardId = settings.state.jiraBoardId
                if (boardId > 0) {
                    // Get active sprint for the configured board
                    val sprints = jiraService.getAvailableSprints(boardId)
                    val activeSprint = if (!sprints.isError) {
                        sprints.data.firstOrNull { it.state == "active" }
                    } else null

                    if (activeSprint != null) {
                        val issues = jiraService.getSprintIssues(activeSprint.id)
                        if (!issues.isError) issues.data else emptyList()
                    } else emptyList()
                } else emptyList()
            }.also { cachedSprintTickets = it }

            // Filter by query
            val filtered = if (query.isBlank()) {
                sprintTickets
            } else {
                val q = query.uppercase()
                sprintTickets.filter { ticket ->
                    ticket.key.uppercase().contains(q) ||
                    ticket.summary.uppercase().contains(q)
                }
            }.take(8)

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
     * Returns JSON: {"valid":true,"summary":"..."} or {"valid":false}
     */
    suspend fun validateTicket(ticketKey: String): String {
        return try {
            val jiraService = try {
                project.getService(JiraService::class.java)
            } catch (_: Exception) { return """{"valid":false}""" }

            val result = jiraService.getTicket(ticketKey)
            if (!result.isError) {
                """{"valid":true,"summary":"${result.data.summary.replace("\"", "\\\"")}"}"""
            } else {
                """{"valid":false}"""
            }
        } catch (e: Exception) {
            LOG.debug("MentionSearchProvider: ticket validation failed for $ticketKey: ${e.message}")
            """{"valid":false}"""
        }
    }
}
