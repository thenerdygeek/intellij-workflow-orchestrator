package com.workflow.orchestrator.agent.tools.ide

import com.intellij.lang.LanguageImportStatements
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
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

class OptimizeImportsTool : AgentTool {
    override val name = "optimize_imports"
    override val isMutating: Boolean get() = true
    override val description = "Add missing imports and remove unused imports in a file. Use after editing to fix 'unresolved reference' errors caused by missing imports."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "path" to ParameterProperty(type = "string", description = "File path to optimize imports for")
        ),
        required = listOf("path")
    )
    override val allowedWorkers = setOf(WorkerType.CODER)

    override fun documentation(): ToolDocumentation = toolDoc("optimize_imports") {
        summary {
            technical("Runs IntelliJ's per-language ImportOptimizer on a single file inside a WriteCommandAction on the EDT — adds missing imports the IDE can resolve, removes unused ones, and reorders/groups them per project code style. Gated by DumbService.isDumb so it never executes during indexing. Compares text before/after and short-circuits to 'already optimal' when nothing changed.")
            plain("Like clicking IntelliJ's 'Code → Optimize Imports' menu item on a single file. The agent uses this after editing code to clean up the import block — pulling in references it just used, dropping ones the edit made stale, and sorting them the way the project prefers.")
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.FILE_WRITE)
        counterfactual(
            "Without optimize_imports, the LLM cleans up imports by hand inside edit_file SEARCH/REPLACE blocks — error-prone (it has to know the exact import-block whitespace and ordering) and easy to skip after refactors, leaving 'unresolved reference' errors or trailing dead imports. The IDE-driven optimizer also knows star-import policies, conflict resolution between two same-named classes, and the project's import-grouping style — none of which the LLM reliably reproduces by text manipulation."
        )
        llmMistake("Runs optimize_imports on a file with an intentional side-effect-only import (e.g. `import scala.language.implicitConversions`, `import 'reflect-metadata'` in TS, or a Kotlin file-level `@file:JvmName` companion that touches a marker class) — the optimizer drops it as 'unused', breaking the build at runtime. The LLM has no way to know the import was load-bearing.")
        llmMistake("Skips it after a refactor that removed code — the unused imports linger and confuse the next edit_file round (the LLM sees stale `import X` and assumes X is still in scope).")
        llmMistake("Calls it BEFORE writing the code that uses the new symbols — the optimizer can't add an import for a reference the file doesn't have yet, and may strip imports it thinks are unused. Optimize AFTER edit_file, not before.")
        llmMistake("Calls it during indexing, gets the 'still indexing' error, and immediately retries instead of waiting — burns iterations on the same DumbService rejection.")
        llmMistake("Assumes the call wrote bytes and tries to read the file to verify, but optimize_imports returns 'already optimal' without writing — the no-op short-circuit means no actual disk change. The summary string is the source of truth for whether anything happened.")
        params {
            required("path", "string") {
                llmSeesIt("File path to optimize imports for")
                humanReadable("Which file to clean up. Path can be relative to the project root or absolute, just like read_file / edit_file.")
                whenPresent("PathValidator canonicalises the path, refuses traversal outside the project, then the file is loaded into PSI and the language-appropriate ImportOptimizer runs on it.")
                constraint("must point inside the project root — `../etc/passwd`-style paths are rejected before any IDE work")
                constraint("must point to a file the IDE recognises (PSI parseable) — unknown extensions return 'Cannot parse'")
                constraint("must have an ImportOptimizer registered for its language — exotic file types return 'No import optimizer available'")
                example("src/main/kotlin/com/example/Foo.kt")
                example("src/main/java/com/example/Bar.java")
            }
        }
        verdict {
            keep(
                "After every non-trivial edit_file in Java/Kotlin/Python, imports drift. Asking the LLM to manage imports by hand is one of the highest-failure-rate things in agentic coding — the IDE optimizer is correct by construction (it knows the language's resolution rules, project code style, and conflict resolution). Pulling this out would force the LLM back into hand-rolled SEARCH/REPLACE on import blocks, which fails ~20% of the time on multi-line whitespace alone.",
                VerdictSeverity.NORMAL,
            )
        }
        related("format_code", Relationship.COMPLEMENT, "Sibling tool. Same shape (single file, EDT WriteCommandAction, DumbService-gated, no-op detection). Pair them after edit_file: optimize_imports first, then format_code — the optimizer can change line counts that formatting then re-aligns.")
        related("edit_file", Relationship.ALTERNATIVE, "Use edit_file when you need fine-grained control over a specific import (e.g. preserving a side-effect-only import the optimizer would drop, or adding a single import without touching anything else).")
        related("refactor_rename", Relationship.COMPOSE_WITH, "After refactor_rename moves or renames a class, run optimize_imports on every file the rename touched — the rename leaves stale imports referencing the old FQN.")
        related("diagnostics", Relationship.COMPLEMENT, "Run after optimize_imports to confirm no 'unresolved reference' errors remain — the optimizer can occasionally fail to add an import if the symbol is ambiguous between two libraries.")
        downside("CRITICAL: depends on `DumbService.isDumb(project)` being false. Without the guard, running during indexing could remove imports the IDE just hasn't finished resolving yet, silently corrupting the file. The guard is load-bearing — do not remove it.")
        downside("Drops 'unused' imports that have side effects at load time (Scala `language.implicitConversions`, JS/TS reflect-metadata, Kotlin `@file:` companion markers). The optimizer has no concept of side-effect imports — if you have any, you're better off using edit_file.")
        downside("Per-language behaviour varies — Java optimizer aggressively merges to star-imports past a threshold; Kotlin's never does; Python's puts each import on its own line. The LLM gets whichever the project's IDE settings prefer, and can't override per-call.")
        downside("Operates on PSI, so the file must be parseable. A syntax error elsewhere in the file blocks optimization — the tool returns a parse error rather than partial work.")
        downside("No-op detection compares full text, not just imports — if the optimizer reorders comments adjacent to the import block, that counts as a change. Usually fine, occasionally surprising.")
        observation("Both `optimize_imports` and `format_code` are thin wrappers over IntelliJ bulk-transform actions on a single file. Same DumbService gate, same WriteCommandAction wrapper, same no-op detection. See auditNotes for a possible merge.")
        flowchart("""
            flowchart TD
                A[LLM calls optimize_imports] --> B{Path validates?}
                B -- no --> X1[Return path-traversal error]
                B -- yes --> C{IDE indexing?}
                C -- yes --> X2[Return 'still indexing' error — load-bearing guard]
                C -- no --> D{File found in VFS?}
                D -- no --> X3[Return file-not-found error]
                D -- yes --> E[Run on EDT inside WriteCommandAction]
                E --> F{PSI parses?}
                F -- no --> X4[Return parse error]
                F -- yes --> G{ImportOptimizer registered for language?}
                G -- no --> X5[Return 'no optimizer' error]
                G -- yes --> H[Snapshot text before]
                H --> I[Run optimizer.processFile.run]
                I --> J[Snapshot text after]
                J --> K{textBefore == textAfter?}
                K -- yes --> L[Return 'already optimal' — no disk write]
                K -- no --> M[Diff import lines: removed vs added]
                M --> N[Return 'Optimized X.kt removed 2, added 1']
        """)
        // STRONG merge candidate. Both this tool and FormatCodeTool:
        //   - take exactly one param (`path`)
        //   - guard on DumbService.isDumb
        //   - run inside withContext(EDT) + WriteCommandAction
        //   - implement no-op detection by comparing text before/after
        //   - return identical ToolResult shapes (artifacts=[path], same token estimate)
        //   - declare isMutating (plan-mode blocked) and CODER worker only
        // The dispatch difference is one line: optimizer.processFile vs CodeStyleManager.reformat.
        // A single `transform(path, kind: imports|format|both)` action would shrink the schema
        // surface and let the LLM optimize+format in a single tool call (which is the natural
        // post-edit chain). Counter-argument: keeping them separate makes the LLM's intent
        // explicit in tool-call traces, and the schema cost is small (~50 tokens each).
        mergeOpportunity("Strong candidate to merge with `format_code` into a single `transform(kind=imports|format|both)` tool — both wrap an IDE bulk-transform action on a single file, share the DumbService gate + WriteCommandAction frame + no-op detection, and are typically chained after edit_file. Merging would halve the schema cost and let the LLM combine 'optimize then format' into one tool call (today it takes two). The trade-off: separate tools make the LLM's intent self-documenting in traces.")
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val rawPath = params["path"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'path' required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val (path, pathError) = PathValidator.resolveAndValidate(rawPath, project.basePath)
        if (pathError != null) return pathError

        if (DumbService.isDumb(project)) {
            return ToolResult(
                "IDE is still indexing. optimize_imports cannot run safely during indexing " +
                "because it may incorrectly identify used imports as unused. Try again in a moment.",
                "Indexing", 5, isError = true
            )
        }

        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path!!)
            ?: return ToolResult("File not found: $path", "Not found", 5, isError = true)

        return try {
            var result: ToolResult? = null
            withContext(Dispatchers.EDT) {
                WriteCommandAction.runWriteCommandAction(project, "Agent: Optimize Imports", null, {
                    val psiFile = PsiManager.getInstance(project).findFile(vf)
                    if (psiFile != null) {
                        val document = FileDocumentManager.getInstance().getDocument(vf)
                        val textBefore = document?.text ?: psiFile.text
                        val importsBefore = extractImportLines(textBefore)

                        val optimizers = LanguageImportStatements.INSTANCE.forFile(psiFile)
                        var optimized = false
                        for (optimizer in optimizers) {
                            if (optimizer.supports(psiFile)) {
                                optimizer.processFile(psiFile).run()
                                optimized = true
                            }
                        }

                        if (optimized) {
                            val textAfter = document?.text ?: psiFile.text
                            if (textBefore == textAfter) {
                                result = ToolResult(
                                    "Imports already optimal — no changes needed.",
                                    "Already optimal",
                                    5,
                                    artifacts = listOf(path)
                                )
                            } else {
                                val importsAfter = extractImportLines(textAfter)
                                val removed = importsBefore - importsAfter.toSet()
                                val added = importsAfter - importsBefore.toSet()
                                val parts = mutableListOf<String>()
                                if (removed.isNotEmpty()) {
                                    parts.add("removed ${removed.size} unused import${if (removed.size != 1) "s" else ""}")
                                }
                                if (added.isNotEmpty()) {
                                    parts.add("added ${added.size} import${if (added.size != 1) "s" else ""}")
                                }
                                val detail = if (parts.isNotEmpty()) parts.joinToString(", ") else "reformatted imports"
                                result = ToolResult(
                                    "Optimized imports in ${vf.name} ($detail).",
                                    "Imports optimized",
                                    5,
                                    artifacts = listOf(path)
                                )
                            }
                        } else {
                            result = ToolResult("No import optimizer available for ${vf.name}.", "No optimizer", 5)
                        }
                    } else {
                        result = ToolResult("Cannot parse: $path", "Parse error", 5, isError = true)
                    }
                })
                // Flush the in-memory import changes to disk so an external `git diff` / build
                // sees them now instead of after the next Ctrl+S / frame-deactivation save trigger.
                FileDocumentManager.getInstance().getDocument(vf)?.let {
                    FileDocumentManager.getInstance().saveDocument(it)
                }
            }
            result ?: ToolResult("Import optimization failed", "Error", 5, isError = true)
        } catch (e: Exception) {
            ToolResult("Error: ${e.message}", "Import error", 5, isError = true)
        }
    }

    private fun extractImportLines(text: String): List<String> {
        return text.lines().filter { line ->
            val trimmed = line.trim()
            trimmed.startsWith("import ") || trimmed.startsWith("from ")
        }
    }
}
