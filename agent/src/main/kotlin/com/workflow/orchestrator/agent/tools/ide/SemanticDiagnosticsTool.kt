package com.workflow.orchestrator.agent.tools.ide

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.problems.WolfTheProblemSolver
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.builtin.PathValidator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class SemanticDiagnosticsTool : AgentTool {
    companion object {
        /** Lines of buffer around the edited range to include in diagnostics. */
        private const val EDIT_LINE_BUFFER = 5

        /**
         * Matches plain Java/Kotlin identifier references and qualified names
         * (e.g. "MyClass", "com.example.Service", "myMethod").
         * Filters out file paths, URLs, property placeholders like "${key}", etc.
         */
        private val IDENTIFIER_RE = Regex("""[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*""")
    }

    override val name = "diagnostics"
    override val description = "Check a file for compilation errors using the IDE's semantic analysis engine — syntax errors, unresolved references, type mismatches, missing imports. Faster and more precise than running mvn compile or gradle build. Use this instead of shell build commands to verify code correctness. When run after edit_file, automatically scopes to only NEW issues near the edited lines."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "path" to ParameterProperty(type = "string", description = "File path to check (e.g., 'src/main/kotlin/UserService.kt')")
        ),
        required = listOf("path")
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val rawPath = params["path"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'path' required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val (path, pathError) = PathValidator.resolveAndValidate(rawPath, project.basePath)
        if (pathError != null) return pathError

        if (DumbService.isDumb(project)) {
            return ToolResult("IDE is still indexing. Try again shortly.", "Indexing", 5, isError = true)
        }

        val extension = path!!.substringAfterLast('.', "").lowercase()
        if (extension !in setOf("kt", "java")) {
            return ToolResult("Semantic diagnostics only available for .kt and .java files.", "Unsupported type", 5)
        }

        // Check if there's a recent edit range for this file (set by EditFileTool)
        val canonicalPath = try { java.io.File(path).canonicalPath } catch (_: Exception) { path }
        val editRange = com.workflow.orchestrator.agent.tools.builtin.EditFileTool.lastEditLineRanges.remove(canonicalPath)

        return try {
            val result = ReadAction.nonBlocking<ToolResult?> {
                val vf = LocalFileSystem.getInstance().findFileByIoFile(java.io.File(path))
                    ?: return@nonBlocking ToolResult("File not found: $path", "Not found", 5, isError = true)
                val psiFile = PsiManager.getInstance(project).findFile(vf)
                    ?: return@nonBlocking ToolResult("Cannot parse: $path", "Parse error", 5, isError = true)
                if (!psiFile.isValid) return@nonBlocking null

                // Compute the line filter: if we have an edit range, only report issues near it
                val filterRange = editRange?.let {
                    val start = maxOf(1, it.first - EDIT_LINE_BUFFER)
                    val end = it.last + EDIT_LINE_BUFFER
                    start..end
                }

                val allProblems = mutableListOf<Pair<Int, String>>() // (line, message)

                // 1. WolfTheProblemSolver check (file-level, always included)
                val wolf = WolfTheProblemSolver.getInstance(project)
                val hasProblemFlag = wolf.isProblemFile(vf)

                // 2. Syntax errors (PsiErrorElement)
                PsiTreeUtil.collectElementsOfType(psiFile, PsiErrorElement::class.java).forEach { error ->
                    val line = psiFile.viewProvider.document?.getLineNumber(error.textOffset)?.plus(1) ?: 0
                    allProblems.add(line to "Syntax error — ${error.errorDescription}")
                }

                // 3. Unresolved references (semantic errors — missing imports, unknown types)
                val unresolvedSeen = mutableSetOf<String>()
                psiFile.accept(object : PsiRecursiveElementWalkingVisitor() {
                    override fun visitElement(element: PsiElement) {
                        // Don't descend into comments — @link/@see/@param refs are advisory, not errors.
                        if (element is PsiComment) return

                        // Don't descend into string literal values — plugins register soft references on
                        // string content (Spring bean names, property keys, @Value placeholders, etc.) that
                        // return resolve()=null but are never compilation errors.
                        if (element is PsiLiteralExpression && element.value is String) return

                        // Kotlin string templates: avoid by simple name check since we can't import KT PSI.
                        val simpleName = element.javaClass.simpleName
                        if (simpleName == "KtStringTemplateExpression" ||
                            simpleName == "KtLiteralStringTemplateEntry") return

                        super.visitElement(element)  // recurse into children

                        for (ref in element.references) {
                            // isSoft = advisory reference (Spring, Hibernate, file paths, etc.) —
                            // these are not compilation errors even when unresolved.
                            if (ref.isSoft) continue

                            // PsiPolyVariantReference covers overloaded methods and other multi-target
                            // references. resolve() always returns null for them; use multiResolve().
                            val resolved = if (ref is PsiPolyVariantReference) {
                                ref.multiResolve(false).isNotEmpty()
                            } else {
                                ref.resolve() != null
                            }
                            if (!resolved) {
                                val text = ref.canonicalText.take(60)
                                // Only report plain identifier/qualified-name references, not paths or URLs.
                                if (text.isNotBlank()
                                    && IDENTIFIER_RE.matches(text)
                                    && text !in unresolvedSeen
                                    && !text.startsWith("kotlin.")
                                    && !text.startsWith("java.lang.")
                                ) {
                                    unresolvedSeen.add(text)
                                    val line = psiFile.viewProvider.document
                                        ?.getLineNumber(element.textOffset)?.plus(1) ?: 0
                                    allProblems.add(line to "Unresolved reference '$text'")
                                }
                            }
                        }
                    }
                })

                // Filter to only issues near the edited lines (if edit range is known)
                val relevantProblems = if (filterRange != null) {
                    allProblems.filter { (line, _) -> line in filterRange }
                } else {
                    allProblems
                }

                val skippedCount = allProblems.size - relevantProblems.size

                if (relevantProblems.isEmpty() && !hasProblemFlag) {
                    val suffix = if (filterRange != null && skippedCount > 0) {
                        " ($skippedCount pre-existing issue(s) outside your edit range were excluded)"
                    } else ""
                    ToolResult("No errors in ${vf.name} near your changes.$suffix", "No errors", 5)
                } else {
                    val shown = relevantProblems.take(20)
                    val lines = shown.map { (line, msg) -> "  Line $line: $msg" }
                    val more = if (relevantProblems.size > 20) "\n... and ${relevantProblems.size - 20} more" else ""
                    val scopeNote = if (filterRange != null) " (lines ${filterRange.first}-${filterRange.last})" else ""
                    val skippedNote = if (skippedCount > 0) "\n  ($skippedCount pre-existing issue(s) outside edit range excluded)" else ""
                    val flagNote = if (hasProblemFlag && filterRange != null) "\n  Note: IDE flags this file as problematic (may have issues outside your edit)" else ""
                    val content = "${relevantProblems.size} issue(s) in ${vf.name}$scopeNote:\n${lines.joinToString("\n")}$more$skippedNote$flagNote"
                    ToolResult(content, "${relevantProblems.size} issues", TokenEstimator.estimate(content), isError = true)
                }
            }.inSmartMode(project).executeSynchronously()
            result ?: ToolResult("PSI file became invalid during analysis.", "Invalid", 5, isError = true)
        } catch (e: Exception) {
            ToolResult("Error: ${e.message}", "Error", 5, isError = true)
        }
    }
}
