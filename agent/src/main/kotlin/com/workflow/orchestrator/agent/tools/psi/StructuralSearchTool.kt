package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.structuralsearch.MalformedPatternException
import com.intellij.structuralsearch.MatchOptions
import com.intellij.structuralsearch.Matcher
import com.intellij.structuralsearch.UnsupportedPatternException
import com.intellij.structuralsearch.plugin.util.CollectingMatchResultSink
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

class StructuralSearchTool : AgentTool {
    override val name = "structural_search"
    override val description = "Search for code patterns using structural search syntax. " +
        "More powerful than regex — matches code structure semantically. " +
        "Use \$var\$ for template variables. " +
        "Example: 'System.out.println(\$arg\$)' finds all println calls. Java and Kotlin supported."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "pattern" to ParameterProperty(
                type = "string",
                description = "SSR pattern with \$var\$ template variables"
            ),
            "file_type" to ParameterProperty(
                type = "string",
                description = "Language: \"java\" or \"kotlin\" (default: \"java\")"
            ),
            "scope" to ParameterProperty(
                type = "string",
                description = "Search scope: \"project\" (default) or a module name"
            ),
            "max_results" to ParameterProperty(
                type = "integer",
                description = "Maximum number of results to return (default: 20)"
            )
        ),
        required = listOf("pattern")
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.REVIEWER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val pattern = params["pattern"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Error: 'pattern' parameter is required",
                "Error: missing pattern", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )

        if (pattern.isBlank()) {
            return ToolResult(
                "Error: 'pattern' must not be blank",
                "Error: blank pattern", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        val fileTypeName = params["file_type"]?.jsonPrimitive?.content ?: "java"
        val scopeName = params["scope"]?.jsonPrimitive?.content ?: "project"
        val maxResults = try {
            params["max_results"]?.jsonPrimitive?.int ?: 20
        } catch (_: Exception) { 20 }

        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        val fileType = resolveFileType(fileTypeName)
            ?: return ToolResult(
                "Error: unsupported file_type '$fileTypeName'. Use 'java' or 'kotlin'.",
                "Error: bad file_type", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )

        val content = try {
            performSearch(project, pattern, fileType, scopeName, maxResults)
        } catch (e: MalformedPatternException) {
            return ToolResult(
                "Error: malformed pattern — ${e.message}",
                "Error: malformed pattern", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        } catch (e: UnsupportedPatternException) {
            return ToolResult(
                "Error: unsupported pattern — ${e.message}",
                "Error: unsupported pattern", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        } catch (e: Exception) {
            return ToolResult(
                "Error: structural search failed — ${e.message}",
                "Error: search failed", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        val isError = content.startsWith("Error:")
        return ToolResult(
            content = content,
            summary = if (isError) content else "Structural search completed for: $pattern",
            tokenEstimate = TokenEstimator.estimate(content),
            isError = isError
        )
    }

    private fun resolveFileType(name: String): LanguageFileType? {
        return when (name.lowercase()) {
            "java" -> com.intellij.ide.highlighter.JavaFileType.INSTANCE
            "kotlin", "kt" -> {
                try {
                    Class.forName("org.jetbrains.kotlin.idea.KotlinFileType")
                        .getField("INSTANCE")
                        .get(null) as LanguageFileType
                } catch (_: Exception) {
                    null
                }
            }
            else -> null
        }
    }

    private suspend fun performSearch(
        project: Project,
        pattern: String,
        fileType: LanguageFileType,
        scopeName: String,
        maxResults: Int
    ): String {
        val results = ReadAction.nonBlocking<List<MatchInfo>> {
            val matchOptions = MatchOptions().apply {
                setSearchPattern(pattern)
                setFileType(fileType)
                setRecursiveSearch(true)
                scope = resolveScope(project, scopeName)
            }

            val sink = CollectingMatchResultSink()
            val matcher = Matcher(project, matchOptions)
            matcher.findMatches(sink)

            sink.matches.take(maxResults).mapNotNull { result ->
                val match = result.match ?: return@mapNotNull null
                val psiFile = match.containingFile ?: return@mapNotNull null
                val virtualFile = psiFile.virtualFile ?: return@mapNotNull null
                val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
                val lineNumber = document?.getLineNumber(match.textOffset)?.plus(1)
                val relativePath = PsiToolUtils.relativePath(project, virtualFile.path)
                val matchedText = result.matchImage ?: match.text

                MatchInfo(
                    filePath = relativePath,
                    line = lineNumber ?: 0,
                    matchedText = matchedText.take(100)
                )
            }
        }.inSmartMode(project).executeSynchronously()

        if (results.isEmpty()) {
            return "No matches found for pattern: $pattern"
        }

        val sb = StringBuilder()
        sb.appendLine("Found ${results.size} match${if (results.size != 1) "es" else ""} for pattern: $pattern")
        sb.appendLine()
        results.forEachIndexed { index, match ->
            sb.appendLine("${index + 1}. ${match.filePath}:${match.line}")
            sb.appendLine("   ${match.matchedText}")
            if (index < results.size - 1) sb.appendLine()
        }
        return sb.toString().trimEnd()
    }

    private fun resolveScope(project: Project, scopeName: String): GlobalSearchScope {
        if (scopeName == "project" || scopeName.isBlank()) {
            return GlobalSearchScope.projectScope(project)
        }
        // Try to find a module with this name
        val moduleManager = com.intellij.openapi.module.ModuleManager.getInstance(project)
        val module = moduleManager.findModuleByName(scopeName)
        return if (module != null) {
            GlobalSearchScope.moduleScope(module)
        } else {
            // Fall back to project scope
            GlobalSearchScope.projectScope(project)
        }
    }

    private data class MatchInfo(
        val filePath: String,
        val line: Int,
        val matchedText: String
    )
}
