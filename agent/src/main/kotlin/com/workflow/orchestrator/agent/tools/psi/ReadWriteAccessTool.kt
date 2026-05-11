package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiParameter
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.ide.LanguageProviderRegistry
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.builtin.PathValidator
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

class ReadWriteAccessTool(
    private val registry: LanguageProviderRegistry
) : AgentTool {
    override val name = "read_write_access"
    override val description = "Find all read and write accesses to a variable, field, or parameter. " +
        "Shows which code reads the value vs which code modifies it. " +
        "Useful for understanding data flow and finding unintended mutations."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "file" to ParameterProperty(type = "string", description = "File path containing the variable/field declaration"),
            "offset" to ParameterProperty(type = "integer", description = "0-based character offset of the variable name"),
            "line" to ParameterProperty(type = "integer", description = "1-based line number (alternative to offset)"),
            "column" to ParameterProperty(type = "integer", description = "1-based column number (used with line)"),
            "scope" to ParameterProperty(type = "string", description = "Search scope: 'project' (default) or 'file'")
        ),
        required = listOf("file")
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.CODER, WorkerType.REVIEWER)

    override fun documentation(): ToolDocumentation = toolDoc("read_write_access") {
        summary {
            technical(
                "PSI read/write access classifier for a variable, field, or parameter. " +
                "Resolves the target element by file path + character offset (or line/column), then calls " +
                "`LanguageIntelligenceProvider.classifyAccesses(target, scope)` which walks every reference " +
                "returned by `ReferencesSearch` and buckets each one into READ, WRITE, or READ_WRITE " +
                "(compound assignments like `+=`, `-=`, `++`). Returns three labelled lists " +
                "(up to 50 entries each with `filePath:line — context` rows) plus section totals. " +
                "Scope: `project` (default) or `file`. " +
                "Works for Java fields/local variables/parameters and Kotlin properties/parameters; " +
                "Python support depends on PythonProvider's `classifyAccesses` implementation."
            )
            plain(
                "Like `find_references` but with a READ / WRITE / READ+WRITE tag on every result. " +
                "Instead of a flat list of 'every place this name appears', you get three buckets: " +
                "who reads the value, who overwrites it, and who both reads and modifies it in the " +
                "same statement (e.g., `count += 1`). Useful when you want to understand mutation " +
                "patterns — for example, checking whether a field is ever written from outside its " +
                "class, or finding the single write site of a variable that looks constant."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.READ_ONLY)
        counterfactual(
            "Without read_write_access, the LLM uses `find_references` and then reads each call site " +
            "individually to decide whether it is a read or write — a manual classification pass that " +
            "costs O(N) additional `read_file` calls for N usages. For fields with dozens of references " +
            "this is both token-expensive and error-prone: assignment-expression context is easy to " +
            "misread from a snippet, especially for augmented assignments (`field.value += delta`), " +
            "pre/post-increment (`counter++`), and Kotlin property delegation. " +
            "read_write_access does the classification in a single PSI pass using the same " +
            "`AccessToken` machinery the IDE uses for its own 'Highlight Usages' colour-coding."
        )
        llmMistake(
            "Calls read_write_access on a METHOD name rather than a variable/field/parameter — " +
            "`findSymbolAt` resolves a PsiMethod, but `classifyAccesses` is defined for variable-like " +
            "elements. Depending on provider implementation, the result may be empty or enumerate " +
            "call sites as 'reads' with no 'write' bucket (method invocations are never writes). " +
            "The correct tool for method call sites is `find_references`."
        )
        llmMistake(
            "Assumes an empty WRITE list means the field is effectively final/immutable — " +
            "writes via reflection, serialization frameworks, or Kotlin property delegation " +
            "bypasses PSI reference tracking and will NOT appear in the WRITE bucket. " +
            "An empty write list is 'no PSI-visible write', not 'provably immutable'."
        )
        llmMistake(
            "Provides only `file` without `offset` or `line` — the tool returns " +
            "'Error: at least one of offset or line must be provided'. The LLM must use " +
            "`file_structure` or `read_file` first to locate the symbol's line/offset, " +
            "then supply one of the position parameters."
        )
        llmMistake(
            "Passes a 1-based offset thinking the parameter is 1-based — `offset` is 0-based " +
            "(character index from start of file). Off-by-one causes the tool to land on the " +
            "wrong character, resolving a different symbol or returning 'No element found at this position'. " +
            "Use `line`/`column` (both 1-based) when reading line numbers from `read_file` output."
        )
        llmMistake(
            "Runs read_write_access during indexing (dumb mode) — the `PsiToolUtils.isDumb(project)` " +
            "guard at entry returns a hard fail immediately. The tool does not wait; the LLM " +
            "must poll or retry after indexing completes."
        )
        llmMistake(
            "Expects `scope=file` to show references from other files — `LocalSearchScope(psiFile)` " +
            "limits the search strictly to the single PSI file. A field declared in a service class " +
            "but written only from tests will show an empty WRITE list under `scope=file`. " +
            "Use the default `scope=project` when cross-file mutation is the question."
        )
        params {
            required("file", "string") {
                llmSeesIt("File path containing the variable/field declaration")
                humanReadable(
                    "Path to the source file that contains the declaration of the variable, field, or " +
                    "parameter you want to analyse. Can be relative to the project root or absolute. " +
                    "The tool resolves the PSI file from this path, then looks up the provider by " +
                    "language (Java, Kotlin, Python) via `registry.forFile(psiFile)`. " +
                    "If no provider is registered for the file's language, the tool returns " +
                    "'Code intelligence not available for <language>'."
                )
                whenPresent(
                    "File is located via `LocalFileSystem`, parsed into a `PsiFile`, " +
                    "and the provider is resolved by language ID. Subsequent position resolution " +
                    "and access classification run against this file's PSI tree."
                )
                constraint("must be a source file registered in the project's content roots")
                constraint("binary files, non-source files, and files outside the project return an error")
                example("src/main/kotlin/com/example/service/OrderService.kt")
                example("src/main/java/com/example/model/Order.java")
            }
            optional("offset", "integer") {
                llmSeesIt("0-based character offset of the variable name")
                humanReadable(
                    "Zero-based character index counting from the very first character of the file. " +
                    "Mutually exclusive with `line`+`column` — if both are provided, `offset` wins. " +
                    "Use when you have the exact character position from a prior tool result."
                )
                whenPresent(
                    "Used directly as the PSI lookup offset. Out-of-bounds values (< 0 or ≥ file length) " +
                    "return 'Error: offset N is out of bounds'."
                )
                whenAbsent(
                    "Falls through to `line`+`column` position resolution. " +
                    "If neither `offset` nor `line` is provided, the tool returns an error."
                )
                constraint("must be ≥ 0 and < file character length")
                constraint("0-based — character index, not byte index or line number")
                example("1452")
            }
            optional("line", "integer") {
                llmSeesIt("1-based line number (alternative to offset)")
                humanReadable(
                    "One-based line number as shown in editors and in `read_file` output. " +
                    "Combined with `column` to resolve a character offset via " +
                    "`document.getLineStartOffset(line-1) + (column-1)`. " +
                    "This is the easiest parameter to supply when working from `read_file` line numbers."
                )
                whenPresent(
                    "Converted to a 0-based character offset using the PSI document. " +
                    "If `line` is beyond the document's line count, returns an out-of-bounds error."
                )
                whenAbsent("Falls through to `offset` if provided; otherwise the tool errors.")
                constraint("must be ≥ 1 and ≤ document line count")
                constraint("1-based — matches line numbers shown in editors and `read_file` output")
                example("42")
            }
            optional("column", "integer") {
                llmSeesIt("1-based column number (used with line)")
                humanReadable(
                    "One-based column within the line. Column 1 is the first character. " +
                    "Used together with `line` to pin the exact character. " +
                    "If the computed offset would overshoot the line end, it is clamped to the line end."
                )
                whenPresent("Added to the line-start offset (after converting to 0-based) to yield the final character offset.")
                whenAbsent("Defaults to 1 (start of the line). Adequate for variables that are the first identifier on their line.")
                constraint("1-based; values beyond the line length are clamped to the line end (not an error)")
                example("15")
            }
            optional("scope", "string") {
                llmSeesIt("Search scope: 'project' (default) or 'file'")
                humanReadable(
                    "Controls how broadly the reference search runs. " +
                    "`project` (default) searches all source files in the project index, excluding libraries. " +
                    "`file` restricts to the single PSI file containing the declaration — useful for large " +
                    "projects where you only care about local-variable access patterns within one method/class."
                )
                whenPresent("Selects either `GlobalSearchScope.projectScope(project)` (project) or `LocalSearchScope(psiFile)` (file).")
                whenAbsent("Defaults to `project` — project-wide scope.")
                enumValue("project", "file")
                example("project")
                example("file")
            }
        }
        verdict {
            keep(
                "Strong keep for any mutation-audit or refactoring task. The counterfactual (reading each " +
                "reference site manually via `find_references` + `read_file`) is dramatically more expensive " +
                "for fields with many usages, and it systematically misclassifies compound assignments " +
                "(`+=`, `++`) which look like reads in a snippet but are writes at the bytecode level. " +
                "read_write_access does this in one PSI pass and is the only tool that surfaces the " +
                "READ_WRITE (compound) bucket explicitly. Uniquely valuable for questions like 'is this field " +
                "written from outside its class?', 'does anything mutate this parameter?', or 'is this " +
                "variable written more than once (making it unsuitable to inline)?'. " +
                "Earns its slot in any codebase with non-trivial field/variable mutation patterns.",
                VerdictSeverity.STRONG,
            )
        }
        related("find_references", Relationship.COMPOSE_WITH,
            "Canonical compose-with pair. `find_references` finds every usage site; " +
            "`read_write_access` classifies each usage as read, write, or read+write. " +
            "Use `find_references` when you want all usages (call sites, imports, type references); " +
            "use `read_write_access` when you specifically need to separate readers from mutators."
        )
        related("dataflow_analysis", Relationship.SEE_ALSO,
            "`dataflow_analysis` (Java only) goes further: it narrows the type of an expression at a " +
            "specific point (e.g., NOT_NULL despite nullable declaration) and computes constant-value " +
            "ranges. `read_write_access` tells you WHERE writes happen; `dataflow_analysis` tells you " +
            "WHAT VALUE they assign (or could assign). Compose both when you need 'who writes this " +
            "field AND what is the written value range'."
        )
        downside(
            "No pagination — each of the three buckets (reads, writes, read+writes) is capped at 50 " +
            "entries with a '... N more' footer. For widely-used fields (e.g., a logging variable " +
            "referenced hundreds of times), the 50-entry cap per bucket yields an arbitrary first-50 " +
            "subset. The LLM gets the totals from the section headers but cannot page through the remainder."
        )
        downside(
            "Provider resolved by file language only (`registry.forFile(psiFile)`) — if the target " +
            "variable is declared in a file whose language has no registered provider (e.g., a Groovy " +
            "DSL, a TOML config, or a language plugin not loaded in the current IDE), the tool returns " +
            "'Code intelligence not available for <language>' immediately. There is no secondary " +
            "fallback to a different provider. Workaround: use `find_references` + manual inspection."
        )
        downside(
            "Reflection, Kotlin property delegation, and framework-generated setters are invisible — " +
            "`classifyAccesses` uses PSI `ReferencesSearch` which tracks only source-level references. " +
            "A Spring `@Value`-injected field or a Kotlin `var` backed by a delegated property " +
            "may show zero writes even though the runtime writes it constantly. " +
            "The WRITE bucket is 'PSI-visible writes', not 'all possible writes'."
        )
        downside(
            "No library scope — `scope=project` uses `GlobalSearchScope.projectScope(project)`, " +
            "which excludes library JARs and the JDK. Write sites from test utilities in external " +
            "dependencies, or read sites in framework code, will not appear."
        )
        downside(
            "Requires dumb-mode guard to pass — if IntelliJ's index is still building when the tool " +
            "runs, `PsiToolUtils.isDumb(project)` returns a hard error immediately. " +
            "The `inSmartMode` deferral applies only inside the ReadAction body, so it does not help " +
            "at the entry point. The LLM must wait for indexing to complete and retry."
        )
        observation(
            "NO hardcoded-language fallback bug. Unlike `find_definition` (line 113 of FindDefinitionTool.kt), " +
            "`read_write_access` does NOT call `registry.forLanguageId(\"JAVA\") ?: registry.forLanguageId(\"kotlin\")` " +
            "as a fallback. Provider resolution is exclusively `registry.forFile(psiFile)` (line 88 of " +
            "ReadWriteAccessTool.kt), which is the correct pattern for a file+offset tool — the language " +
            "is already known from the PSI file context. If `forFile` returns null, the tool fails fast " +
            "with 'Code intelligence not available for <language>' rather than silently routing to the " +
            "wrong provider. This is the correct behavior; no fix needed."
        )
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val filePath = params["file"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Error: 'file' parameter is required",
                "Error: missing file", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )

        val offsetParam = try {
            params["offset"]?.jsonPrimitive?.int
        } catch (_: Exception) { null }

        val lineParam = try {
            params["line"]?.jsonPrimitive?.int
        } catch (_: Exception) { null }

        val columnParam = try {
            params["column"]?.jsonPrimitive?.int
        } catch (_: Exception) { null }

        if (offsetParam == null && lineParam == null) {
            return ToolResult(
                "Error: at least one of 'offset' or 'line' must be provided",
                "Error: missing position", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        val scopeParam = params["scope"]?.jsonPrimitive?.content ?: "project"

        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        val (resolvedPath, pathError) = PathValidator.resolveAndValidate(filePath, project.basePath)
        if (pathError != null) return pathError

        val content = ReadAction.nonBlocking<String> {
            val virtualFile = LocalFileSystem.getInstance().findFileByPath(resolvedPath!!)
                ?: return@nonBlocking "Error: file not found: $filePath"

            val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                ?: return@nonBlocking "Error: could not parse file: $filePath"

            val provider = registry.forFile(psiFile)
                ?: return@nonBlocking "Code intelligence not available for ${psiFile.language.displayName}"

            val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)

            val offset = if (offsetParam != null) {
                offsetParam
            } else {
                resolveLineColumnToOffset(document, lineParam!!, columnParam ?: 1)
                    ?: return@nonBlocking "Error: line $lineParam is out of bounds (file has ${document?.lineCount ?: 0} lines)"
            }

            if (offset < 0 || offset >= psiFile.textLength) {
                return@nonBlocking "Error: offset $offset is out of bounds (file length: ${psiFile.textLength})"
            }

            val leafElement = psiFile.findElementAt(offset)
                ?: return@nonBlocking "No element found at this position"

            // Use the provider's findSymbolAt to locate the target variable/field/parameter
            val target = provider.findSymbolAt(psiFile, offset)
                ?: return@nonBlocking "No variable, field, or parameter found at this position"

            val targetName = (target as? PsiNamedElement)?.name ?: "unknown"
            val targetKind = classifyTargetKind(target)
            val targetLine = document?.let { doc ->
                doc.getLineNumber(target.textOffset) + 1
            } ?: 0
            val targetFileName = PsiToolUtils.relativePath(project, psiFile.virtualFile.path)

            // Determine search scope
            val searchScope: SearchScope = when (scopeParam) {
                "file" -> LocalSearchScope(psiFile)
                else -> GlobalSearchScope.projectScope(project)
            }

            // Delegate access classification to the provider
            val classification = provider.classifyAccesses(target, searchScope)

            if (classification.reads.isEmpty() && classification.writes.isEmpty() && classification.readWrites.isEmpty()) {
                return@nonBlocking "Read/Write analysis for $targetKind '$targetName' ($targetFileName:$targetLine)\n\nNo references found."
            }

            val sb = StringBuilder()
            sb.appendLine("Read/Write analysis for $targetKind '$targetName' ($targetFileName:$targetLine)")
            sb.appendLine()

            sb.appendLine("Writes (${classification.writes.size}):")
            if (classification.writes.isEmpty()) {
                sb.appendLine("  (none)")
            } else {
                for (w in classification.writes.take(50)) {
                    sb.appendLine("  ${w.filePath}:${w.line} — ${w.context}")
                }
                if (classification.writes.size > 50) {
                    sb.appendLine("  ... (${classification.writes.size - 50} more)")
                }
            }

            sb.appendLine()
            sb.appendLine("Reads (${classification.reads.size}):")
            if (classification.reads.isEmpty()) {
                sb.appendLine("  (none)")
            } else {
                for (r in classification.reads.take(50)) {
                    sb.appendLine("  ${r.filePath}:${r.line} — ${r.context}")
                }
                if (classification.reads.size > 50) {
                    sb.appendLine("  ... (${classification.reads.size - 50} more)")
                }
            }

            if (classification.readWrites.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("Read+Write (compound assignments like +=, -=) (${classification.readWrites.size}):")
                for (rw in classification.readWrites.take(50)) {
                    sb.appendLine("  ${rw.filePath}:${rw.line} — ${rw.context}")
                }
                if (classification.readWrites.size > 50) {
                    sb.appendLine("  ... (${classification.readWrites.size - 50} more)")
                }
            }

            sb.toString().trimEnd()
        }.inSmartMode(project).executeSynchronously()

        val isError = content.startsWith("Error:") || content.startsWith("Code intelligence not available")
        return ToolResult(
            content = content,
            summary = if (isError) content else "Read/write analysis completed for $filePath",
            tokenEstimate = TokenEstimator.estimate(content),
            isError = isError
        )
    }

    /**
     * Classify the target element into a human-readable kind string.
     * Supports Java PSI types and Kotlin types via class name.
     */
    private fun classifyTargetKind(element: PsiElement): String {
        return when (element) {
            is PsiLocalVariable -> "local variable"
            is PsiParameter -> "parameter"
            is PsiField -> "field"
            else -> {
                // Kotlin types via class name
                when (element.javaClass.simpleName) {
                    "KtProperty" -> "property"
                    "KtParameter" -> "parameter"
                    else -> "variable"
                }
            }
        }
    }

    private fun resolveLineColumnToOffset(document: Document?, line: Int, column: Int): Int? {
        if (document == null) return null
        val zeroBasedLine = line - 1
        if (zeroBasedLine < 0 || zeroBasedLine >= document.lineCount) return null
        val lineStartOffset = document.getLineStartOffset(zeroBasedLine)
        val lineEndOffset = document.getLineEndOffset(zeroBasedLine)
        val zeroBasedColumn = column - 1
        val offset = lineStartOffset + zeroBasedColumn
        return if (offset > lineEndOffset) lineEndOffset else offset
    }
}
