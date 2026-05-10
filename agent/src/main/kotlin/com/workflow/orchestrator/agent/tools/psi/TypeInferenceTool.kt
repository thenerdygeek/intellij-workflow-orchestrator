package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiParameter
import com.intellij.psi.util.PsiTreeUtil
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

class TypeInferenceTool(
    private val registry: LanguageProviderRegistry
) : AgentTool {
    override val name = "type_inference"
    override val description = "Get the resolved type of an expression or variable at a given position in a file. " +
        "Returns the fully-qualified type, presentable type name, and nullability info. Supports Java and Kotlin."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "file" to ParameterProperty(type = "string", description = "File path relative to project or absolute"),
            "offset" to ParameterProperty(type = "integer", description = "0-based character offset in the file"),
            "line" to ParameterProperty(type = "integer", description = "1-based line number (alternative to offset)"),
            "column" to ParameterProperty(type = "integer", description = "1-based column number (used with line)")
        ),
        required = listOf("file")
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.CODER, WorkerType.REVIEWER)

    override fun documentation(): ToolDocumentation = toolDoc("type_inference") {
        summary {
            technical(
                "Position-based PSI type-inference: given a file path and a character position (offset " +
                "or line/column), locates the leaf PsiElement at that position, classifies it as a " +
                "local variable, parameter, field, Kotlin property, or expression via `classifyElementKind`, " +
                "then delegates to `LanguageIntelligenceProvider.inferType` for the fully-qualified type name, " +
                "presentable type name, and nullability enum. Supports every language whose provider is " +
                "registered (Java, Kotlin, Python) — no hardcoded language fallback."
            )
            plain(
                "Like asking the IDE 'what type is this?' at a specific cursor position. When you hover " +
                "over a variable in IntelliJ and the tooltip says `val result: List<String>` or " +
                "`String? — can be null`, that is exactly what this tool computes — but driven by the " +
                "LLM passing a file path and a line/column instead of you hovering with a mouse. " +
                "Especially useful for inferred types (`var`, `val`, Kotlin's `it`) where the declared " +
                "type doesn't appear in the source text at all."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.READ_ONLY)
        counterfactual(
            "Without `type_inference`, the LLM reads the source file and tries to infer the type by " +
            "manually tracing the expression. This works acceptably for explicitly annotated Java fields " +
            "(`String name;`) but fails silently for: (1) Kotlin `val`/`var` with inferred types — the " +
            "source shows no type annotation and only the compiler knows the resolved type; (2) chain " +
            "expressions like `list.filter { it.isActive }.map { it.name }` where `it` in each lambda is " +
            "a different inferred type; (3) generic bounds resolved by the type checker (a `T` bounded by " +
            "multiple constraints is hard to fully resolve from source text alone); (4) nullability — " +
            "Java's `@Nullable`/`@NotNull`, Kotlin's `?` suffix, and platform types all require the " +
            "compiler's annotation-aware analysis to determine the correct nullability verdict. The LLM " +
            "reading source text will often guess wrong on platform types and may claim a Java API return " +
            "value is non-null when the actual signature is `String!` (platform). Net cost: incorrect type " +
            "assumptions silently propagate through subsequent reasoning, causing the LLM to suggest wrong " +
            "null-checks, incorrect casts, or incompatible API calls."
        )
        llmMistake(
            "Passes `offset=0` or omits position entirely — the guard at line 63 requires at least one " +
            "of `offset` or `line`, and offset 0 points to the very first character of the file (usually " +
            "the `package` keyword), almost never the expression of interest. Always compute the position " +
            "from a known line/column using the line/column params."
        )
        llmMistake(
            "Confuses 1-based and 0-based indexing: `offset` is 0-based (character position from start of " +
            "file), while `line` is 1-based (matching editor line numbers) and `column` is 1-based. " +
            "Passing a 1-based offset gives the wrong element, typically in the first line of the file."
        )
        llmMistake(
            "Points at a keyword or punctuation instead of the identifier — `findElementAt(offset)` " +
            "returns the leaf PSI token at that exact position. If the offset lands on `{`, `:`, or a " +
            "whitespace character, `classifyElementKind` falls through to the 'expression' branch with " +
            "the token text as the name. The provider's `inferType` may then return null and the tool " +
            "reports 'No typed element found at this position'. Move the offset one character into the " +
            "identifier name."
        )
        llmMistake(
            "Treats `Nullability.PLATFORM` as equivalent to `NOT_NULL` — Java's platform types (`String!`) " +
            "mean the IDE cannot determine nullability from annotations. The LLM sometimes reads " +
            "PLATFORM as 'safe to use without null-check' and skips defensive coding. PLATFORM = unknown, " +
            "not guaranteed non-null."
        )
        llmMistake(
            "Calls `type_inference` during indexing — the `PsiToolUtils.isDumb(project)` guard at line 70 " +
            "returns a hard dumb-mode error. The `inSmartMode(project)` deferral applies only after the " +
            "dumb-mode entry check, so the LLM gets an immediate failure rather than waiting for index " +
            "completion. Retry after indexing finishes."
        )
        llmMistake(
            "Uses `type_inference` on a method name expecting the method's return type — `classifyElementKind` " +
            "classifies the leaf element under a `PsiMethod`/`KtNamedFunction` as 'method return type', " +
            "and `inferType` resolves the method's declared return type, not a call expression's resolved " +
            "return. For a call expression like `foo.bar()`, point at `bar` within the call expression, " +
            "not at its declaration site, to get the return type at the call site."
        )
        params {
            required("file", "string") {
                llmSeesIt("File path relative to project or absolute")
                humanReadable(
                    "Path to the source file containing the expression or variable whose type you want. " +
                    "Relative paths are resolved against the project base directory via `PathValidator`."
                )
                whenPresent(
                    "File is located via `LocalFileSystem`, parsed into a `PsiFile`, and the language is " +
                    "resolved to a `LanguageIntelligenceProvider` via `registry.forFile(psiFile)`. If no " +
                    "provider is registered for the file's language, the tool returns 'Code intelligence " +
                    "not available for {language}' with no fallback."
                )
                example("src/main/kotlin/com/example/MyService.kt")
                example("src/main/java/com/example/UserRepository.java")
                example("src/api/views.py")
            }
            optional("offset", "integer") {
                llmSeesIt("0-based character offset in the file")
                humanReadable(
                    "The character position counting from the very start of the file, zero-indexed. " +
                    "Think of it like the cursor position in a plain-text editor where position 0 is " +
                    "before the first character. Prefer line/column for readability; use offset when " +
                    "you have the exact byte position from a prior tool result."
                )
                whenPresent(
                    "Used directly as the offset for `psiFile.findElementAt(offset)`. Validated to be " +
                    "within `[0, psiFile.textLength)` before use; out-of-bounds returns an error."
                )
                whenAbsent(
                    "Ignored when `line` is provided. If both `offset` and `line` are absent, the tool " +
                    "returns a validation error: 'at least one of offset or line must be provided'."
                )
                constraint("must be ≥ 0 and < file length in characters")
                constraint("0-based — offset 0 is the first character of the file")
                example("1042")
                example("0")
            }
            optional("line", "integer") {
                llmSeesIt("1-based line number (alternative to offset)")
                humanReadable(
                    "The line number as shown in the IntelliJ editor gutter — line 1 is the first line " +
                    "of the file. Used together with `column` (also 1-based); if `column` is omitted, " +
                    "column 1 (start of line) is assumed. Prefer this over `offset` when reading source " +
                    "output from `read_file`, which prefixes each line with its 1-based number."
                )
                whenPresent(
                    "Converted to a 0-based offset via `resolveLineColumnToOffset`, which uses " +
                    "`Document.getLineStartOffset(line - 1) + (column - 1)`. If the computed offset " +
                    "exceeds the line's end offset, the end of the line is used (clamp, not error). " +
                    "Out-of-bounds line numbers return an error."
                )
                whenAbsent(
                    "If `offset` is also absent, returns a validation error. If `offset` is present, " +
                    "`line` is ignored."
                )
                constraint("must be ≥ 1 and ≤ document line count")
                constraint("1-based — line 1 is the first line of the file")
                example("42")
                example("1")
            }
            optional("column", "integer") {
                llmSeesIt("1-based column number (used with line)")
                humanReadable(
                    "The column number within the line, also 1-based. Column 1 is the first character " +
                    "on the line (before any indentation). Point this at a character inside the " +
                    "identifier name you want the type of — not at the colon, bracket, or surrounding " +
                    "punctuation."
                )
                whenPresent(
                    "Used together with `line` to compute the character offset. Ignored when `offset` " +
                    "is provided directly."
                )
                whenAbsent(
                    "Defaults to column 1 (start of the line) when `line` is provided but `column` is " +
                    "omitted."
                )
                constraint("must be ≥ 1")
                constraint("1-based — column 1 is the first character of the line")
                example("15")
                example("1")
            }
        }
        verdict {
            keep(
                "Strong keep for any Kotlin-heavy or Java-heavy codebase. The core value proposition is " +
                "inferring types the LLM CANNOT see in the source text: Kotlin `val`/`var` with no type " +
                "annotation, lambda `it` parameters, complex generic resolutions, and nullability verdicts " +
                "on Java platform types. These cases are exactly where the LLM is most likely to guess " +
                "wrong when reading source alone. The tool is also cheap to call (single file + single " +
                "position, no network, no index-wide search), making it low-cost even when called " +
                "speculatively. Python support exists but `PythonProvider.inferType` has narrower coverage " +
                "than the Java/Kotlin path, so the value is somewhat lower on Python-only projects. " +
                "Overall: this is one of the highest-signal PSI tools for correctness of type-driven " +
                "code generation.",
                VerdictSeverity.STRONG
            )
        }
        related("find_definition", Relationship.COMPLEMENT,
            "Canonical pair for understanding a symbol. `find_definition` locates where a name is declared " +
            "and returns its signature; `type_inference` resolves the type at a specific USE site. Use " +
            "`find_definition` first to anchor the declaration, then `type_inference` on an expression that " +
            "uses it to confirm the inferred type matches your expectations (especially useful for generic " +
            "type parameters that change per call site)."
        )
        related("file_structure", Relationship.COMPLEMENT,
            "Use `file_structure` first to get a map of declared types and signatures across the file, then " +
            "`type_inference` at a specific position for the compiler-resolved type of an expression that " +
            "`file_structure` won't show (e.g. the inferred type of a `val` initializer, the concrete type " +
            "of a generic returned from a factory method). The two tools are at different granularities: " +
            "structure is file-wide and declaration-level; type_inference is single-point and expression-level."
        )
        related("dataflow_analysis", Relationship.COMPLEMENT,
            "After `type_inference` tells you the static type of an expression, `dataflow_analysis` on the " +
            "same element tells you the dynamic range, nullability, or constant value the dataflow engine " +
            "has proven at that point. Together they answer 'what type' (type_inference) and 'what value " +
            "constraints' (dataflow_analysis)."
        )
        related("get_annotations", Relationship.COMPLEMENT,
            "Use `get_annotations` alongside `type_inference` when nullability matters: `type_inference` " +
            "reports the Kotlin/Java type name and a single `Nullability` enum, but `get_annotations` " +
            "shows the raw `@Nullable` / `@NonNull` / JSR-305 annotations that drove that verdict."
        )
        downside(
            "Position precision required — the LLM must provide an exact character offset or line/column " +
            "that lands INSIDE an identifier. Hitting whitespace, a keyword, or a delimiter returns " +
            "'No typed element found at this position' with no helpful suggestion about where to move the " +
            "cursor. Requires more careful position tracking than symbol-name-based tools like `find_definition`."
        )
        downside(
            "Element classification walks the PSI tree twice: once with `PsiTreeUtil.getParentOfType` " +
            "for Java types, and once with a manual `while (current != null)` loop by class simple name " +
            "for Kotlin types. This dual-pass means Kotlin elements only surface if the Java chain " +
            "doesn't match first (correct), but it also means adding support for a third language requires " +
            "editing `classifyElementKind` as well as the provider — the classification and the inference " +
            "are not yet unified."
        )
        downside(
            "The tool classifies by element kind at the position and then delegates to the provider's " +
            "`inferType`. If the provider returns null, the tool reports 'No typed element found'. " +
            "There is no distinction between 'provider exists but returned null for this element kind' " +
            "and 'the element has no type at all' — both produce the same message, which can confuse " +
            "the LLM into believing the position is wrong when the actual cause is a provider limitation."
        )
        downside(
            "Python coverage is limited to what `PythonProvider.inferType` supports via reflection-based " +
            "PSI access. Dynamically typed variables without annotations, or variables whose type changes " +
            "across assignments, return limited or null results. For Python, `type_inference` is most " +
            "useful on annotated parameters and return types, not on dynamically bound names."
        )
        observation(
            "VERIFIED: `type_inference` does NOT have the hardcoded JAVA/kotlin provider-fallback bug. " +
            "Line 83 of TypeInferenceTool.kt calls `registry.forFile(psiFile)` — which does a direct " +
            "language-ID lookup on the registered provider map — and on a miss returns a clear " +
            "'Code intelligence not available for {language}' error with no fallback. This is the correct " +
            "pattern (same as `find_implementations`, `call_hierarchy`, `type_hierarchy`). Contrast with " +
            "the buggy `find_definition` / `find_references` pattern which falls back to " +
            "`registry.forLanguageId(\"JAVA\") ?: registry.forLanguageId(\"kotlin\")`, silently returning " +
            "wrong results for non-Java/Kotlin files. No fix needed here."
        )
        observation(
            "`classifyElementKind` uses two separate traversal strategies for Java vs Kotlin, which " +
            "creates a subtle ordering dependency: if a Kotlin element is also a `PsiLocalVariable` " +
            "(which some Kotlin compiled forms can match), it will be classified as a Java local variable " +
            "rather than a KtProperty. In practice this is rare for source-level PSI, but it is a " +
            "latent correctness issue worth noting for future language additions."
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
                ?: return@nonBlocking "No typed element found at this position"

            // Classify the element kind for descriptive output
            val (elementKind, elementName) = classifyElementKind(leafElement)

            // Delegate type inference to the language provider
            val result = provider.inferType(leafElement)
                ?: return@nonBlocking "No typed element found at this position"

            val sb = StringBuilder()
            sb.appendLine("Type of $elementKind:")
            if (elementName != null) {
                sb.appendLine("  Expression: $elementName")
            }
            sb.appendLine("  Presentable: ${result.typeName}")
            if (result.qualifiedName != null) {
                sb.appendLine("  Qualified: ${result.qualifiedName}")
            }
            sb.appendLine("  Nullability: ${result.nullability}")
            sb.toString().trimEnd()
        }.inSmartMode(project).executeSynchronously()

        val isError = content.startsWith("Error:") || content.startsWith("Code intelligence not available")
        return ToolResult(
            content = content,
            summary = if (isError) content else "Type resolved at $filePath",
            tokenEstimate = TokenEstimator.estimate(content),
            isError = isError
        )
    }

    /**
     * Classify a leaf PsiElement into its element kind and name for descriptive output.
     * Walks up from the leaf to find the nearest typed parent (variable, parameter, field, method, expression).
     * Supports both Java PSI types and Kotlin types via class name.
     */
    private fun classifyElementKind(leaf: PsiElement): Pair<String, String?> {
        // Java types
        PsiTreeUtil.getParentOfType(leaf, PsiLocalVariable::class.java, false)?.let { variable ->
            return "local variable" to (variable.name ?: variable.text.take(50))
        }
        PsiTreeUtil.getParentOfType(leaf, PsiParameter::class.java, false)?.let { param ->
            return "parameter" to (param.name ?: param.text.take(50))
        }
        PsiTreeUtil.getParentOfType(leaf, PsiField::class.java, false)?.let { field ->
            return "field" to (field.name ?: field.text.take(50))
        }
        PsiTreeUtil.getParentOfType(leaf, PsiMethod::class.java, false)?.let { method ->
            return "method return type" to method.name
        }

        // Kotlin types via class name (avoids hard compile-time dependency)
        var current: PsiElement? = leaf
        while (current != null) {
            when (current.javaClass.simpleName) {
                "KtProperty" -> {
                    val name = (current as? PsiNamedElement)?.name ?: current.text.take(50)
                    return "property" to name
                }
                "KtParameter" -> {
                    val name = (current as? PsiNamedElement)?.name ?: current.text.take(50)
                    return "parameter" to name
                }
                "KtNamedFunction" -> {
                    val name = (current as? PsiNamedElement)?.name ?: current.text.take(50)
                    return "function return type" to name
                }
            }
            current = current.parent
        }

        // Fallback: expression
        val exprText = leaf.text.take(100)
        return "expression" to exprText
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
