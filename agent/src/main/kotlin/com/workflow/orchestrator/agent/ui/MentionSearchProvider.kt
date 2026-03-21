package com.workflow.orchestrator.agent.ui

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.search.PsiShortNamesCache
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
}
