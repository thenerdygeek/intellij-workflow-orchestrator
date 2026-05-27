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
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
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

    override fun documentation(): ToolDocumentation = toolDoc("format_code") {
        summary {
            technical("Reformat a file via IntelliJ's CodeStyleManager.reformat on the resolved PsiFile, executed inside a WriteCommandAction on the EDT. Refuses to run while DumbService.isDumb is true (PSI may be incomplete during indexing). Compares document text before/after and emits a 'no changes needed' result when nothing changed; otherwise reports the count of changed lines.")
            plain("Like clicking IntelliJ's 'Reformat Code' menu item, but driven by the agent. Tidies indentation, spacing, and line breaks in one file using whatever code style the IDE has loaded for that language — the same style your hand-written edits would conform to if you used the IDE shortcut.")
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.FILE_WRITE)
        counterfactual(
            "Without format_code, the LLM either shells out to a project formatter via run_command (e.g., `./gradlew spotlessApply`, `mvn spotless:apply`, `prettier --write`, `black .`) — which is approval-gated per invocation, depends on the project actually configuring that plugin, and reformats far more files than intended — OR the LLM hand-formats inside edit_file SEARCH/REPLACE blocks, which is error-prone (whitespace mismatches, missing trailing newlines) and burns iterations. Net cost: ~2-4 extra approval prompts per session and a meaningful regression in 'fix indentation' success rate."
        )
        llmMistake("Calls format_code on non-source files (`format_code('README.md')`, `format_code('package.json')`) — IntelliJ has formatters for Markdown/JSON/YAML so the call may succeed and silently rewrite the file in IDE-style (e.g., reflowing prose, alphabetising JSON keys). Surprising and rarely what the LLM wanted.")
        llmMistake("Calls format_code immediately after edit_file without checking whether the edit changed anything that matters for formatting — produces a no-op result and a wasted iteration. Worth flagging because the LLM tends to chain edit_file → format_code → optimize_imports unconditionally.")
        llmMistake("Assumes `.editorconfig` is honoured. The description mentions it but EditorConfig is only respected when IntelliJ's EditorConfig plugin is loaded AND the file is under a directory containing `.editorconfig`. In stripped-down IDE installs the file's settings are silently ignored and the IDE's global code style wins.")
        llmMistake("Tries to format an entire directory by passing a folder path — gets a parse error (`Cannot parse file:`) because PathValidator + PsiManager.findFile both reject directories. Should iterate file-by-file via glob_files first.")
        llmMistake("Calls format_code during indexing and gets the DUMB_MODE error, then immediately retries instead of waiting — typically loops 3-5 times before indexing finishes.")
        params {
            required("path", "string") {
                llmSeesIt("File path to format")
                humanReadable("Where the file lives — relative to the project (`src/main/kotlin/Foo.kt`) or absolute (`/Users/me/proj/src/Main.kt`).")
                whenPresent("Path is canonicalised, validated against project boundaries via PathValidator, the corresponding VirtualFile is refreshed and resolved, and a write-command-action runs CodeStyleManager.reformat on the PsiFile.")
                constraint("must point inside the project root (or another allow-listed root) — traversal is rejected before any I/O")
                constraint("must point to a parseable file — directories and unknown file types fail with a 'Cannot parse file' error")
                example("src/main/kotlin/com/example/Foo.kt")
                example("agent/build.gradle.kts")
            }
        }
        verdict {
            keep(
                "Cheap to keep (one tiny file, single param, FILE_WRITE blast radius confined to the named path) and the LLM reaches for it often after edit_file / refactor_rename. Dropping it would push the LLM toward run_command-based formatters with worse blast radius (whole-project rewrites) and more approval prompts. The IDE-loaded code style is also more consistent with the user's manual edits than any external formatter the LLM could pick.",
                VerdictSeverity.NORMAL,
            )
        }
        related("optimize_imports", Relationship.COMPLEMENT, "Same shape (single `path` param, dumb-mode check, FILE_WRITE, write-command-action) but a different concern — imports vs whitespace/indentation. Typical pairing after edit_file: optimize_imports first, then format_code.")
        related("edit_file", Relationship.ALTERNATIVE, "Use instead when you only need to reflow a small region or fix a specific indent. format_code is whole-file reformat — too blunt for surgical whitespace fixes.")
        related("run_inspections", Relationship.COMPOSE_WITH, "Inspections (e.g., 'Reformat code') flag style issues; format_code is one of the canonical fixes. Run inspections first to confirm the file actually has formatting problems before reformatting it.")
        mergeOpportunity(
            "format_code and optimize_imports share ~80% scaffolding (PathValidator → DumbService check → refreshAndFindFileByPath → withContext(EDT) → WriteCommandAction → PsiManager.findFile → before/after diff → ToolResult). They could merge into a single `code_style` meta-tool with action ∈ {format, optimize_imports, both}. The 'both' action would let the LLM do the typical post-edit cleanup in one tool call instead of two, eliminating one round-trip per edit."
        )
        downside("DumbService blocks during indexing — first-time project open, after a Gradle/Maven sync, or after switching branches with dependency changes can mean format_code is unavailable for 30s-2min. The error message tells the LLM to retry but doesn't say when.")
        downside("Code style comes from the IDE's loaded settings, not the project. `.editorconfig` is honoured only if the EditorConfig plugin is loaded; project-level scheme files (`.idea/codeStyles/`) are honoured only if IntelliJ has imported them. A formatter that 'just works' on the user's machine may produce different output in CI or for a teammate.")
        downside("CodeStyleManager.reformat operates on the PsiFile, not the on-disk bytes — runs through the Document API. So if the file has unsaved editor changes, those get reformatted too (which is usually what the user wants, but worth knowing).")
        downside("Even when before == after (no changes needed), the call still grabs the EDT, opens a write-command-action, and runs the reformatter — costs ~50-200ms of EDT time. Cheap, but not free, and the LLM tends to call format_code reflexively.")
        downside("No range/scope parameter — always whole-file. Can't reformat 'just this method' or 'just lines 100-150'. If the user wants surgical reformatting they have to use edit_file by hand.")
        flowchart("""
            flowchart TD
                A[LLM calls format_code] --> B{Path validates?}
                B -- no --> X1[Return path-traversal error]
                B -- yes --> C{DumbService.isDumb?}
                C -- yes --> X2[Return Indexing error — retry later]
                C -- no --> D{File exists in VFS?}
                D -- no --> X3[Return Not found error]
                D -- yes --> E[withContext EDT + WriteCommandAction]
                E --> F{PsiManager.findFile?}
                F -- null --> X4[Return Parse error]
                F -- ok --> G[Snapshot textBefore]
                G --> H[CodeStyleManager.reformat psiFile]
                H --> I[Read textAfter]
                I --> J{Before == after?}
                J -- yes --> K[Return Already formatted no-op]
                J -- no --> L[countChangedLines]
                L --> M[Return Formatted N line s changed]
        """)
    }

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
                // Flush the in-memory reformat to disk so an external `git diff` / build sees it
                // immediately instead of after the next Ctrl+S / frame-deactivation save trigger.
                FileDocumentManager.getInstance().getDocument(vf)?.let {
                    FileDocumentManager.getInstance().saveDocument(it)
                }
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
