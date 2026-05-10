package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
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

class DataFlowAnalysisTool(
    private val registry: LanguageProviderRegistry
) : AgentTool {
    override val name = "dataflow_analysis"
    override val description = "Analyze nullability, value ranges, and constant values of an expression in Java code. " +
        "Returns whether a variable can be null, its possible value range, and constant value if determinable. " +
        "Java only — does not work on Kotlin files."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "file" to ParameterProperty(type = "string", description = "Java file path relative to project or absolute"),
            "offset" to ParameterProperty(type = "integer", description = "0-based character offset in the file"),
            "line" to ParameterProperty(type = "integer", description = "1-based line number (alternative to offset)"),
            "column" to ParameterProperty(type = "integer", description = "1-based column number (used with line)")
        ),
        required = listOf("file")
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.CODER, WorkerType.REVIEWER)

    override fun documentation(): ToolDocumentation = toolDoc("dataflow_analysis") {
        summary {
            technical(
                "File-position PSI tool that drives IntelliJ's `CommonDataflow.getDfType()` / " +
                "`getExpressionRange()` / `getKnownConstantValue()` APIs on the Java expression " +
                "found at the given file offset or line/column, and returns three dataflow facts: " +
                "nullability (NULLABLE / NOT_NULL / PLATFORM / UNKNOWN), value range (e.g. '[0,100]' " +
                "for a bounded int), and constant value (e.g. '42' for a compile-time constant). " +
                "Java only — `PythonProvider.analyzeDataflow` returns null (no equivalent exists), " +
                "and Kotlin files are rejected before provider dispatch via an explicit `.kt`/`.kts` guard. " +
                "Provider resolved via `LanguageProviderRegistry.forFile(psiFile)` (file-language keyed, " +
                "the correct pattern for position-based tools)."
            )
            plain(
                "Like asking 'where does this variable's value come from, and what can it be?' " +
                "Point it at any expression in a Java file — a variable, a method call result, a field " +
                "read — and it answers three questions the IDE's data flow engine already knows: " +
                "(1) can this be null? (2) if it's a number, what values can it hold? " +
                "(3) is it a compile-time constant? " +
                "Think of it as the IDE's 'Null check is always true' or 'Value is always 0' warning " +
                "engine, surfaced as a tool result instead of a gutter icon. " +
                "It only works on Java — not Kotlin, not Python."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.READ_ONLY)
        counterfactual(
            "Without dataflow_analysis, the LLM must manually trace data flow by chaining `find_references` " +
            "(to locate every assignment to a variable) + `read_file` (to read each assignment site) + " +
            "inline reasoning about which branch conditions gate each write. For a parameter with " +
            "`@NotNull` in one caller and `null` in another, this manual chain requires reading every " +
            "call site individually and synthesizing nullability by hand — typically 5-15 tool calls " +
            "for a simple field with 3 writers. The IDE's data flow engine has already solved the " +
            "fixed-point analysis across all branches; `dataflow_analysis` surfaces that result in a " +
            "single call. Constant folding (detecting that `int x = 7 * 6; return x;` is always 42) " +
            "and numeric range narrowing (detecting that a value after `if (x > 0 && x < 100)` is " +
            "in [1, 99]) are simply not achievable by manual code reading at LLM scale."
        )
        params {
            required("file", "string") {
                llmSeesIt("Java file path relative to project or absolute")
                humanReadable(
                    "Path to the Java source file containing the expression to analyse. " +
                    "Relative paths are resolved against the project root; absolute paths are used as-is. " +
                    "Only Java files are accepted — passing a `.kt` or `.kts` file returns an error immediately."
                )
                whenPresent("File is located via `LocalFileSystem`, parsed into a `PsiFile`, and dispatched to `JavaKotlinProvider.analyzeDataflow` once the offset resolves to a leaf PSI element.")
                constraint("must be a `.java` file — `.kt` and `.kts` are rejected with an explicit error message before provider dispatch")
                constraint("file must be within the project scope or reachable via `PathValidator.resolveAndValidate`")
                example("src/main/java/com/example/OrderService.java")
                example("/Users/dev/project/src/main/java/Foo.java")
            }
            optional("offset", "integer") {
                llmSeesIt("0-based character offset in the file")
                humanReadable(
                    "Character offset counting from the very beginning of the file (0 = first character). " +
                    "The most precise positioning mode — use when you have the exact byte position from a " +
                    "previous tool result (e.g., `find_references` or `diagnostics` sometimes surfaces offsets). " +
                    "Mutually exclusive with `line`/`column` in practice — if both are given, `offset` wins."
                )
                whenPresent("Used directly as the PSI leaf-element lookup offset. Bounds-checked against `psiFile.textLength`.")
                whenAbsent("If `line` is also absent, the tool returns an error: 'at least one of offset or line must be provided'.")
                constraint("must be ≥ 0 and < file length in characters")
                constraint("0-based (file first character = 0, not 1)")
                example("1523")
                example("0")
            }
            optional("line", "integer") {
                llmSeesIt("1-based line number (alternative to offset)")
                humanReadable(
                    "Human-friendly line number, counting from 1. Use when you can see the source in a " +
                    "`read_file` result and know the line but not the exact character offset. " +
                    "Combined with `column` to produce an offset; if `column` is omitted, column 1 (line start) is assumed."
                )
                whenPresent("Converted to a 0-based offset via `document.getLineStartOffset(line - 1) + (column - 1)`. Clamped to the line's end offset if the column overshoots.")
                whenAbsent("If `offset` is also absent, the tool returns an error. If `offset` IS present, `line` is ignored entirely.")
                constraint("must be ≥ 1 and ≤ document.lineCount")
                constraint("1-based (first line = 1, not 0)")
                example("42")
                example("100")
            }
            optional("column", "integer") {
                llmSeesIt("1-based column number (used with line)")
                humanReadable(
                    "Character column within the line, counting from 1. Only meaningful when `line` is provided. " +
                    "Point this at the first character of the expression you want analysed — e.g., if the " +
                    "expression is `foo.getBar()` starting at column 12, pass column=12."
                )
                whenPresent("Added to `document.getLineStartOffset(line - 1)` to derive the character offset. Clamped to the line's end offset if it overshoots.")
                whenAbsent("Defaults to column 1 — the start of the line — which may land on whitespace or the wrong token if the expression starts mid-line. Always provide column when you know it.")
                constraint("must be ≥ 1")
                constraint("1-based (first column = 1, not 0)")
                example("12")
                example("1")
            }
        }
        verdict {
            keep(
                "Provides three facts (nullability, value range, constant value) that are the direct output " +
                "of IntelliJ's fixed-point data flow engine — facts that are either impossible or " +
                "prohibitively expensive to derive by manual `find_references` + `read_file` chains. " +
                "The nullability signal alone prevents the LLM from inserting unnecessary null-guards " +
                "around `@NotNull` returns, and from missing genuine null-dereference risks on nullable " +
                "fields. The constant-value signal short-circuits dead-code analysis. The value-range signal " +
                "is uniquely valuable for security reviews (detecting off-by-one in bounds checks) and for " +
                "reasoning about enum/switch exhaustiveness. The narrow scope (Java only) is a real limit, " +
                "but for Java-heavy codebases this earns its deferred slot without question.",
                VerdictSeverity.STRONG,
            )
            drop(
                "Zero value for Python projects (returns null from `PythonProvider.analyzeDataflow` — the " +
                "tool reaches the provider dispatch but the result is null, producing 'No expression found " +
                "at this position'), and explicitly rejected for Kotlin (early guard). " +
                "For mixed Java+Kotlin codebases where the Kotlin side is dominant, this tool's applicability " +
                "may be narrow enough to justify dropping it from the deferred catalog in favour of a " +
                "broader future tool that wraps Kotlin's analysis APIs.",
                VerdictSeverity.WEAK,
            )
        }
        llmMistake(
            "Passes a Kotlin file path (`.kt` or `.kts`) — the tool returns an error immediately " +
            "('DataFlow analysis is only available for Java files') because the `.kt`/`.kts` guard " +
            "fires BEFORE provider dispatch. The LLM sometimes concludes 'the tool is broken' rather " +
            "than 'use a different approach for Kotlin'. Correct response to this error: switch to " +
            "`diagnostics` (for nullability warnings already computed by the Kotlin compiler) or " +
            "`read_file` + manual reasoning."
        )
        llmMistake(
            "Points the offset/line at a non-expression token (a keyword, a method name in a declaration, " +
            "a brace) — `psiFile.findElementAt(offset)` returns the leaf, but " +
            "`PsiTreeUtil.getParentOfType(leaf, PsiExpression::class.java)` walks up and may return null, " +
            "producing 'No expression found at this position. DataFlow analysis requires an expression " +
            "inside a method body.' The LLM should target the VALUE being analysed (the right-hand side " +
            "of an assignment, the argument in a call, the receiver of a dereference), not the declaration " +
            "itself."
        )
        llmMistake(
            "Uses 0-based line numbers — `line` is 1-based. Passing `line=0` is out of bounds and returns " +
            "an error ('line 0 is out of bounds'); passing line N when intending line N+1 silently " +
            "analyses the wrong expression. Always read `line` as 'the line number shown in an editor or " +
            "in `read_file` output', which starts at 1."
        )
        llmMistake(
            "Calls the tool during project indexing (dumb mode) — `PsiToolUtils.isDumb(project)` check " +
            "at entry returns immediately. The `inSmartMode(project)` deferral applies only inside the " +
            "ReadAction body AFTER entry; the entry guard fires first. The LLM receives a hard dumb-mode " +
            "error and must wait for indexing to complete, not retry immediately."
        )
        llmMistake(
            "Expects the tool to trace data flow ACROSS method boundaries — `CommonDataflow` analyses the " +
            "expression in its local scope (the enclosing method body's CFG). It does NOT interprocedurally " +
            "track where a parameter's value came from in the caller, or where a return value goes in the " +
            "callee. For cross-boundary flow, compose with `find_references` to locate call sites, then " +
            "run `dataflow_analysis` at each call site independently."
        )
        related("find_references", Relationship.COMPLEMENT, "Run find_references first to locate every assignment site for a field or parameter, then run dataflow_analysis at each site to determine what value is being assigned. Together they answer 'who writes this value and what range of values does it take'.")
        related("find_definition", Relationship.COMPLEMENT, "find_definition anchors the declaration of the variable or method whose return type you want to analyse; dataflow_analysis then runs at usage sites to answer 'what does it actually hold at runtime' (which may differ from the declared type, e.g., NOT_NULL despite a nullable return type).")
        related("diagnostics", Relationship.FALLBACK, "When dataflow_analysis is unavailable (Kotlin file, Python file, dumb mode), `diagnostics` surfaces the IDE's already-computed nullability warnings and constant-condition warnings as diagnostic messages. Less precise (no range or constant value), but covers Kotlin and Python.")
        related("type_inference", Relationship.SEE_ALSO, "type_inference tells you the DECLARED type of an expression (e.g., 'String?'); dataflow_analysis tells you the NARROWED type in context (e.g., NOT_NULL because the branch already checked for null). Use both when the declared type is nullable but you suspect the IDE knows it's always non-null here.")
        downside(
            "Java only — `PythonProvider.analyzeDataflow` is a stub that returns null, and the " +
            "tool's `.kt`/`.kts` early guard rejects Kotlin files before provider dispatch. " +
            "Effectively a Java-only tool despite being registered for all language-capable environments."
        )
        downside(
            "Intraprocedural scope only — `CommonDataflow` analyses the enclosing method's control-flow " +
            "graph. It does not trace values across method boundaries, through reflection, through " +
            "Spring bean injection, or through external serialisation/deserialisation. A parameter " +
            "annotated `@NotNull` by the IDE's inference may still be null if it arrives via a " +
            "reflection-based framework that bypasses compile-time checks."
        )
        downside(
            "JDK and library class bodies are NOT analysed — `CommonDataflow` runs against bytecode-backed " +
            "PSI stubs for JDK/library classes, which may lack the CFG detail needed for range or " +
            "constant resolution. For expressions whose value originates entirely in JDK code, " +
            "nullability returns UNKNOWN and range/constant return null."
        )
        downside(
            "No expression-at-offset disambiguation — if the offset falls on a composite expression " +
            "(`a + b * c`), `PsiTreeUtil.getParentOfType` climbs to the nearest enclosing `PsiExpression`, " +
            "which may be the whole compound expression rather than the sub-expression the LLM intended. " +
            "There is no way to say 'analyse just the `b * c` part' without pointing at a character " +
            "strictly inside that sub-expression."
        )
        downside(
            "VERIFIED: `DataFlowAnalysisTool` uses `registry.forFile(psiFile)` (in the execute body) — the " +
            "file-language-keyed lookup — which is the CORRECT pattern for a file-position-based tool. " +
            "It does NOT have the hardcoded `forLanguageId(\"JAVA\") ?: forLanguageId(\"kotlin\")` " +
            "fallback bug present in `find_definition` and `find_references`. " +
            "However, `forFile` returns null for any language without a registered provider, producing " +
            "the generic 'Code intelligence not available for {language}' message. For the concrete " +
            "Python case, the `.py` file would reach `forFile` and get `PythonProvider`, whose " +
            "`analyzeDataflow` returns null — so the result is 'No expression found at this position' " +
            "(the null-result message in the execute body), not the 'Code intelligence not available' message. " +
            "Net: no hardcoded-fallback bug, but Python is still silently unsupported."
        )
        observation(
            "VERIFIED: DataFlowAnalysisTool does NOT have the hardcoded Java/kotlin provider-fallback bug. " +
            "The execute body uses `registry.forFile(psiFile)` — file-language keyed, correct for a " +
            "position-based tool. The find_definition / find_references `forLanguageId(\"JAVA\") ?: " +
            "forLanguageId(\"kotlin\")` bug is absent here. The Kotlin early-return guard at the top " +
            "of execute() and the Python null-return from PythonProvider.analyzeDataflow() are both " +
            "documented limitations, not bugs."
        )
        observation(
            "The tool's description says 'Java only — does not work on Kotlin files' which is accurate " +
            "but slightly misleading: the JavaKotlinProvider handles both 'JAVA' and 'kotlin' language IDs, " +
            "and analyzeDataflow in JavaKotlinProvider uses Java-specific PsiExpression APIs. The early " +
            "Kotlin guard in execute() is a necessary safeguard — without it, a `.kt` file would reach " +
            "`JavaKotlinProvider.analyzeDataflow` and fail at the `PsiExpression` cast since Kotlin PSI " +
            "uses `KtExpression`, not `PsiExpression`."
        )
        observation(
            "The `line`/`column` → offset resolution in resolveLineColumnToOffset() clamps to " +
            "`lineEndOffset` on column overshoot but the execute body's offset bounds check returns an " +
            "error string (not an exception) for out-of-bounds offset params. The " +
            "resolveLineColumnToOffset function handles line out-of-bounds by returning null (producing " +
            "the 'line N is out of bounds' message), but column 0 is treated as a valid request " +
            "(column - 1 = -1, underflowing to lineStartOffset - 1, potentially landing in the previous " +
            "line). LLMs providing 0-based columns will get silently wrong results."
        )
        flowchart("""
            graph TD
            A[LLM calls dataflow_analysis] --> B{file ends .kt or .kts?}
            B -->|yes| C[Error: Java only]
            B -->|no| D{offset or line provided?}
            D -->|neither| E[Error: missing position]
            D -->|yes| F{dumb mode?}
            F -->|yes| G[Error: indexing in progress]
            F -->|no| H[PathValidator.resolveAndValidate]
            H -->|invalid| I[Error: path error]
            H -->|ok| J[LocalFileSystem.findFileByPath]
            J -->|not found| K[Error: file not found]
            J -->|found| L[PsiManager.findFile]
            L -->|null| M[Error: could not parse]
            L -->|ok| N[registry.forFile psiFile]
            N -->|null| O[Error: Code intelligence not available]
            N -->|JavaKotlinProvider| P[Resolve offset from line/col if needed]
            P -->|out of bounds| Q[Error: line or offset out of bounds]
            P -->|ok| R[psiFile.findElementAt offset]
            R -->|null| S[Error: No element found]
            R -->|leaf| T[provider.analyzeDataflow leaf]
            T -->|null - no PsiExpression parent| U[Error: No expression found]
            T -->|DataflowResult| V[Format nullability + range + constant]
            V --> W[ToolResult with analysis]
        """)
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val filePath = params["file"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Error: 'file' parameter is required",
                "Error: missing file", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )

        // Check for Kotlin files
        if (filePath.endsWith(".kt") || filePath.endsWith(".kts")) {
            val msg = "DataFlow analysis is only available for Java files. Kotlin has its own analysis not exposed via this API."
            return ToolResult(msg, msg, ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

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

        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        val (resolvedPath, pathError) = PathValidator.resolveAndValidate(filePath, project.basePath)
        if (pathError != null) return pathError

        val content = ReadAction.nonBlocking<String> {
            val virtualFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .findFileByPath(resolvedPath!!)
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

            val fileName = virtualFile.name
            val lineNumber = document?.getLineNumber(offset)?.plus(1) ?: 0
            val expressionText = leafElement.text.take(100)

            // Delegate dataflow analysis to the language provider
            val result = provider.analyzeDataflow(leafElement)
                ?: return@nonBlocking "No expression found at this position. DataFlow analysis requires an expression inside a method body."

            val sb = StringBuilder()
            sb.appendLine("DataFlow analysis at $fileName:$lineNumber")
            sb.appendLine("  Expression: $expressionText")
            sb.appendLine("  Nullability: ${result.nullability}")
            sb.appendLine("  Range: ${result.valueRange ?: "(not applicable)"}")
            sb.appendLine("  Constant value: ${result.constantValue ?: "(none)"}")
            sb.toString().trimEnd()
        }.inSmartMode(project).executeSynchronously()

        val isError = content.startsWith("Error:") || content.startsWith("Code intelligence not available")
        return ToolResult(
            content = content,
            summary = if (isError) content else "DataFlow analyzed at $filePath",
            tokenEstimate = TokenEstimator.estimate(content),
            isError = isError
        )
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
