package com.workflow.orchestrator.agent.tools.ide

import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.codeStyle.CodeStyleManager
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.builtin.PathValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class FormatCodeTool : AgentTool {
    override val name = "format_code"
    override val description = "Reformat a file according to the project's code style (.editorconfig, IDE settings). Use after editing files to ensure consistent formatting."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "path" to ParameterProperty(type = "string", description = "File path to format")
        ),
        required = listOf("path")
    )
    override val allowedWorkers = setOf(WorkerType.CODER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val rawPath = params["path"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'path' required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val (path, pathError) = PathValidator.resolveAndValidate(rawPath, project.basePath)
        if (pathError != null) return pathError

        if (DumbService.isDumb(project)) {
            return ToolResult(
                "IDE is still indexing. format_code cannot run safely during indexing " +
                "because it may operate on incomplete PSI trees. Try again in a moment.",
                "Indexing", 5, isError = true
            )
        }

        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path!!)
            ?: return ToolResult("File not found: $path", "Not found", 5, isError = true)

        return try {
            var result: ToolResult? = null
            withContext(Dispatchers.EDT) {
                WriteCommandAction.runWriteCommandAction(project, "Agent: Format Code", null, {
                    val psiFile = PsiManager.getInstance(project).findFile(vf)
                    if (psiFile != null) {
                        val document = FileDocumentManager.getInstance().getDocument(vf)
                        val textBefore = document?.text ?: psiFile.text
                        CodeStyleManager.getInstance(project).reformat(psiFile)
                        val textAfter = document?.text ?: psiFile.text

                        if (textBefore == textAfter) {
                            result = ToolResult(
                                "File already formatted — no changes needed.",
                                "Already formatted",
                                5,
                                artifacts = listOf(path)
                            )
                        } else {
                            val changedLines = countChangedLines(textBefore, textAfter)
                            result = ToolResult(
                                "Formatted ${vf.name} ($changedLines line${if (changedLines != 1) "s" else ""} changed).",
                                "Formatted ${vf.name}",
                                5,
                                artifacts = listOf(path)
                            )
                        }
                    } else {
                        result = ToolResult("Cannot parse file: $path", "Parse error", 5, isError = true)
                    }
                })
            }
            result ?: ToolResult("Format failed", "Error", 5, isError = true)
        } catch (e: Exception) {
            ToolResult("Error formatting: ${e.message}", "Format error", 5, isError = true)
        }
    }

    private fun countChangedLines(before: String, after: String): Int {
        val linesBefore = before.lines()
        val linesAfter = after.lines()
        val maxLines = maxOf(linesBefore.size, linesAfter.size)
        var changed = 0
        for (i in 0 until maxLines) {
            val lineBefore = linesBefore.getOrNull(i)
            val lineAfter = linesAfter.getOrNull(i)
            if (lineBefore != lineAfter) changed++
        }
        return changed
    }
}
