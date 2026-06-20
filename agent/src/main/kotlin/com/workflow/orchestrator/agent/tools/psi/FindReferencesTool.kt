package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
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
import kotlinx.serialization.json.jsonPrimitive

class FindReferencesTool(
    private val registry: LanguageProviderRegistry
) : AgentTool {
    override val name = "find_references"
    override val description = "Find all usages/references of a symbol (class, method, or field) across the project."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "symbol" to ParameterProperty(type = "string", description = "Symbol name to search for (class name, method name, or field name)"),
            "file" to ParameterProperty(type = "string", description = "Optional file path for disambiguation when multiple symbols share the same name"),
            "context_lines" to ParameterProperty(type = "integer", description = "Number of context lines around each reference (default: 0, max: 3)")
        ),
        required = listOf("symbol")
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.REVIEWER)

    override fun documentation(): ToolDocumentation = toolDoc("find_references") {
        summary {
            technical(
                "PSI find-usages for a class, method, or field — resolves the symbol to a " +
                    "`PsiElement` (provider `findSymbol` first, `PsiShortNamesCache` fallback), " +
                    "then runs `ReferencesSearch.search(target, projectScope).findAll()` inside " +
                    "`smartReadAction(project)` so the search is coroutine-cancellation-aware, " +
                    "and emits `path:line  <line text>` rows (or `>>>`-marked context blocks " +
                    "when `context_lines > 0`); capped at the first 50 hits with a count footer.",
            )
            plain("The agent's 'Find Usages' (Cmd-Click → Find Usages in IntelliJ). Hand it a symbol name and it returns every place that symbol is read, called, or referenced — same index-backed search the IDE shows in the Usages tool window, just textual output instead of a tree view.")
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.READ_ONLY)
        counterfactual(
            "Without find_references, the LLM falls back to `search_code` with the symbol name as a regex — which mixes the definition, every call site, every import statement, every comment, and every string literal mentioning the name into a single undifferentiated list (commonly 50-200 hits for a normal method). The LLM then has to read each candidate file to classify hit vs. noise, often follows an import or comment and misattributes a 'usage', and burns 3-10x more tool calls per impact-analysis question. find_references uses PSI resolution, so it returns ONLY actual references to the resolved target — imports of the same name on a different symbol don't false-match."
        )
        llmMistake("Confuses the result with `find_definition` output — find_references returns USAGES (excluding the declaration itself, which lives elsewhere in the index), so the LLM sometimes claims 'this method has no callers' when the result is empty, when really the method IS the only thing named `foo` and just isn't called yet. Use find_definition first to confirm the target exists before drawing 'unused' conclusions.")
        llmMistake("Searches an overloaded method by bare name without the `file` hint — the resolver hits `PsiShortNamesCache.getMethodsByName(symbol).firstOrNull()`, which picks WHICHEVER overload the cache returns first. References for the wrong overload come back, and the LLM treats them as canonical. Pass `file` to scope the lookup to the declaring file.")
        llmMistake("Calls find_references in a pure-Python project without passing `file` — the no-file-context path now iterates all registered providers via `registry.allProviders()`, so `PythonProvider` is found correctly even when no Java plugin is loaded. Passing `file` still provides the most reliable file-scoped disambiguation, but is no longer required for basic symbol resolution in Python projects.")
        llmMistake(
            "Calls find_references during indexing — `smartReadAction(project)` suspends until smart mode, " +
                "but if the entire IDE is in dumb mode for an extended period the call may time out. " +
                "No internal wait/retry beyond the platform suspension; the LLM should pause one " +
                "tool-call cycle or fall back to `search_code` when indexing is actively in progress.",
        )
        llmMistake("Sets `context_lines` higher than 3 expecting more context — the value is silently coerced to `0..3` by `coerceIn(0, 3)`. Asking for `context_lines=10` returns the same output as `context_lines=3`, which can mislead the LLM into thinking it's seeing the full surrounding block.")
        llmMistake("Treats the 50-result cap as the true count — output truncation appends `... (showing first 50 of N)` only when `references.size > 50`, but the LLM sometimes drops the footer when summarising and reports '50 references' as the total. The header line `References to '<symbol>' (N total)` is the authoritative count.")
        params {
            required("symbol", "string") {
                llmSeesIt("Symbol name to search for (class name, method name, or field name)")
                humanReadable("What you're looking for — typically a class name, method name, or field name. Bare names work; `Class#method` / FQN is NOT parsed by this tool's resolver (unlike `find_definition`), so for member disambiguation use the `file` hint instead.")
                whenPresent("Routed to `resolveSearchTarget`: if `file` is set, file-scoped resolution runs first (provider for that file's language → file-scoped class+method walk for Java files); otherwise the resolver iterates `registry.allProviders().firstNotNullOfOrNull` (post-commit 3918e3d7b — no hardcoded JAVA/kotlin fallback), with `PsiShortNamesCache.getMethodsByName` / `getFieldsByName` as the last fallback. The first match is fed to `ReferencesSearch.search(target, projectScope)`.")
                constraint("must be a single identifier — `Class#method` / `Class.method` / FQN strings are NOT parsed and will fall through to `PsiShortNamesCache` short-name lookup, which won't match")
                constraint("case-sensitive — `MyClass` and `myclass` are distinct lookups")
                example("AgentService")
                example("executeTask")
                example("planModeActive")
            }
            optional("file", "string") {
                llmSeesIt("Optional file path for disambiguation when multiple symbols share the same name")
                humanReadable("Path to the file that DECLARES the symbol — used to pick the right one when multiple classes/methods share a name across the project. Validated against the project root by `PathValidator.resolveAndValidate` to prevent traversal.")
                whenPresent("File-scoped resolution runs first: the resolver looks up the provider for that file's language, calls `findSymbol`, and accepts the result only if its containing file matches. Falls through to `(PsiJavaFile).classes.flatMap { methods }.firstOrNull { name == symbol }` for method-by-name within the file. If file-scoped resolution misses, the global path runs anyway — so `file` is a hint, not a hard filter.")
                whenAbsent("Tool runs the global path: iterates `registry.allProviders()` until one resolves the symbol via `findSymbol`, then falls back to `PsiShortNamesCache` short-name lookup. First hit wins regardless of which file declares it. All registered providers (Java/Kotlin and Python) are tried, so pure-Python projects work correctly.")
                constraint("must resolve under the project root after `PathValidator.resolveAndValidate` — absolute paths outside the project return a path-error ToolResult before any PSI work runs")
                example("src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt")
                example("src/main/java/com/example/UserRepository.java")
            }
            optional("context_lines", "integer") {
                llmSeesIt("Number of context lines around each reference (default: 0, max: 3)")
                humanReadable("How many lines of code to print above and below each reference, with `>>>` marking the actual hit line. 0 is a single-line `path:line  <code>` row; 1-3 is a small surrounding block.")
                whenPresent("Each reference is rendered as `path:line\\n` followed by `(start..end).joinToString` over the surrounding lines, with `>>>` prefixing the hit line and `   ` (3 spaces) prefixing context lines. Computed via `document.getLineStartOffset` / `getLineEndOffset` on the containing file's PSI document.")
                whenAbsent("Single-line rendering: `path:line  <trimmed line text>`. Compact and cheap — preferred for high-fanout symbols where context lines would blow out the 50-result cap into a wall of code.")
                constraint("silently coerced into the range 0..3 via `coerceIn(0, 3)` — values above 3 are clamped without warning")
                constraint("non-integer / unparseable strings fall back to the default 0 (`toIntOrNull() ?: 0`)")
                example("0")
                example("2")
                example("3")
            }
        }
        verdict {
            keep(
                "Foundational read-only IDE intelligence and the inverse half of the find_definition / find_references pair. The counterfactual (`search_code` for the symbol name) loses the definition-vs-usage distinction, the import-vs-call distinction, and the symbol-identity distinction — three signal losses that the LLM cannot recover textually. Drop only if PSI infrastructure goes away entirely; on its own merits this tool earns its schema slot.",
                VerdictSeverity.STRONG,
            )
        }
        related("find_definition", Relationship.COMPLEMENT, "Inverse-direction pair: find_definition jumps to where a symbol is DECLARED, find_references lists where it is USED. Canonical workflow: find_definition to anchor the target, then find_references to map its blast radius before refactoring or removing.")
        related("search_code", Relationship.FALLBACK, "Use when no LanguageIntelligenceProvider exists for the language (JS/TS, Go, Rust today), when the symbol is dynamic (Python `getattr`, runtime-injected names), when find_references returns 'No symbol found', or when you specifically WANT to find string-literal / comment mentions that PSI references-search excludes by design.")
        related("call_hierarchy", Relationship.COMPOSE_WITH, "find_references gives you the flat list of usage sites; call_hierarchy walks the tree of who-calls-who. Use find_references to spot-check 'is this method used at all'; switch to call_hierarchy when you need to follow the chain through wrappers and indirections.")
        related("find_implementations", Relationship.SEE_ALSO, "For interfaces and abstract methods, `find_references` tends to return the implementations and call-through sites; `find_implementations` returns ONLY the concrete subtype/override declarations. Use the latter when you need a clean list of who satisfies a contract.")
        related("refactor_rename", Relationship.COMPOSE_WITH, "Run find_references before refactor_rename to preview the rename's blast radius, then rename. Skipping the preview risks renaming through a misidentified symbol when overloads collide.")
        observation("Bug fixed — no-file-context global path was `registry.forLanguageId(\"JAVA\") ?: registry.forLanguageId(\"kotlin\")`, which silently skipped `PythonProvider` in pure-Python projects. Switched to `registry.allProviders().firstNotNullOfOrNull { ... }`, matching the canonical pattern used by find_implementations, call_hierarchy, and type_hierarchy. Pure-Python projects now work correctly without needing to pass `file`. Surfaced by Phase 5 tool-docs swarm (Batch 4).")
        downside("Hard 50-result cap with no pagination, sort key, or filter — `references.take(50)` truncates without sorting by file path, line number, or relevance. For a method called 200+ times across the codebase, the LLM sees an arbitrary slice of the first 50 in iteration order and can't widen the search. Workaround: pass a more specific symbol or use `file` to scope, then re-query for the remainder.")
        downside("Does NOT separate import-statement references from real call/use references — `ReferencesSearch.search` returns import references alongside actual usages. For Java/Kotlin classes with many imports and few real call sites, the result is dominated by import lines. The LLM has to filter visually. Workaround: ask the LLM to grep for the symbol name in the result lines, ignoring lines starting with `import`.")
        downside("Slow on large codebases — `ReferencesSearch.findAll()` is index-backed but still walks every file in `GlobalSearchScope.projectScope`. For symbols in large monorepos, expect multi-second latencies; the 120s default tool timeout can be hit on pathological symbols (every PSI element named `equals` / `hashCode` / `toString`).")
        downside(
            "Requires indexing to be complete — `smartReadAction(project)` suspends until smart " +
                "mode, but the underlying `ReferencesSearch` APIs are index-dependent. If the IDE is " +
                "in a prolonged dumb-mode period the call may time out at the tool's timeout boundary. " +
                "LLM should retry after a pause when indexing finishes.",
        )
        downside("File-scoped resolution's class-walk fallback (in `resolveSearchTarget`) only runs for `PsiJavaFile` — Kotlin files, Python files, and any other language with `file` set fall through to the global path even when file-scoped resolution should narrow them. Disambiguation via `file` is therefore most reliable for Java sources.")
        downside("Top-level Kotlin functions / extension functions are not findable by bare name in the no-file-context path — `findSymbol` walks `PsiShortNamesCache.getMethodsByName`, which indexes class members only. Workaround: pass `file` pointing at the containing `.kt` file, which uses provider resolution.")
        observation("Hardcoded-fallback bug is now fixed in both find_references and find_definition — both now use `registry.allProviders().firstNotNullOfOrNull` for the no-file-context path. Fixed together in the same commit; consistent with find_implementations, call_hierarchy, and type_hierarchy.")
        observation("`file` parameter does double duty as a path-validation entry point AND a disambiguation hint, but the file-scoped fallback only handles `PsiJavaFile.classes.methods` — non-Java files get path validation but no actual file-scoping benefit. Consider either dropping the Java-only fallback, generalizing it via the provider, or renaming the parameter to signal its Java-leaning behaviour.")
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val symbol = params["symbol"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'symbol' parameter required", "Error: missing symbol", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val rawFilePath = params["file"]?.jsonPrimitive?.content
        val contextLines = (params["context_lines"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0).coerceIn(0, 3)

        // Validate file path upfront if provided (prevents path traversal)
        val resolvedFilePath = if (rawFilePath != null) {
            val (validated, pathError) = PathValidator.resolveAndValidate(rawFilePath, project.basePath)
            if (pathError != null) return pathError
            validated
        } else {
            null
        }

        val content = smartReadAction(project) {
            val scope = GlobalSearchScope.projectScope(project)

            // Resolve the search target via provider or fallback
            val searchTarget = resolveSearchTarget(project, symbol, resolvedFilePath, scope)

            if (searchTarget == null) {
                return@smartReadAction "No symbol '$symbol' found in project"
            }

            val references = ReferencesSearch.search(searchTarget, scope).findAll()
            if (references.isEmpty()) {
                return@smartReadAction "No references found for '$symbol'"
            }

            val results = references.take(50).mapNotNull { ref ->
                val element = ref.element
                val absoluteFilePath = element.containingFile?.virtualFile?.path ?: return@mapNotNull null
                val relativeFilePath = PsiToolUtils.relativePath(project, absoluteFilePath)
                val document = com.intellij.psi.PsiDocumentManager.getInstance(project)
                    .getDocument(element.containingFile) ?: return@mapNotNull null
                val line = document.getLineNumber(element.textOffset) + 1
                val zeroIndexedLine = line - 1

                if (contextLines > 0) {
                    val startLine = maxOf(0, zeroIndexedLine - contextLines)
                    val endLine = minOf(document.lineCount - 1, zeroIndexedLine + contextLines)
                    val contextBlock = (startLine..endLine).joinToString("\n") { lineIdx ->
                        val lineText = document.getText(
                            com.intellij.openapi.util.TextRange(
                                document.getLineStartOffset(lineIdx),
                                document.getLineEndOffset(lineIdx)
                            )
                        )
                        val lineNum = lineIdx + 1
                        val marker = if (lineIdx == zeroIndexedLine) ">>>" else "   "
                        "$marker $lineNum: $lineText"
                    }
                    "$relativeFilePath:$line\n$contextBlock"
                } else {
                    val lineText = document.getText(
                        com.intellij.openapi.util.TextRange(
                            document.getLineStartOffset(zeroIndexedLine),
                            document.getLineEndOffset(zeroIndexedLine)
                        )
                    ).trim()
                    "$relativeFilePath:$line  $lineText"
                }
            }

            val header = "References to '$symbol' (${references.size} total):\n"
            val truncated = if (references.size > 50) "\n... (showing first 50 of ${references.size})" else ""
            header + results.joinToString("\n") + truncated
        }

        val spilled = spillOrFormat(content, project)
        return ToolResult(
            content = spilled.preview,
            summary = "References for '$symbol'",
            tokenEstimate = TokenEstimator.estimate(spilled.preview),
            spillPath = spilled.spilledToFile,
        )
    }

    /**
     * Resolve the PsiElement to search references for.
     * Delegates symbol resolution to the language provider when available.
     * When a file path is provided, file-scoped resolution runs first for disambiguation.
     * Otherwise, iterates all registered providers via [LanguageProviderRegistry.allProviders]
     * until one resolves the symbol — ensuring Python, Java, and Kotlin projects all work.
     */
    private fun resolveSearchTarget(
        project: Project,
        symbol: String,
        resolvedFilePath: String?,
        scope: GlobalSearchScope
    ): com.intellij.psi.PsiElement? {
        // If a file path is provided, try file-scoped resolution first
        if (resolvedFilePath != null) {
            val vFile = LocalFileSystem.getInstance().findFileByPath(resolvedFilePath)
            val psiFile = vFile?.let { PsiManager.getInstance(project).findFile(it) }

            if (psiFile != null) {
                // Resolve provider from the actual file's language
                val fileProvider = registry.forFile(psiFile)
                if (fileProvider != null) {
                    // Try to find symbol as class first, verify it's in this file
                    val classElement = fileProvider.findSymbol(project, symbol)
                    if (classElement != null && classElement.containingFile?.virtualFile?.path == resolvedFilePath) {
                        return classElement
                    }
                }

                // File-scoped fallback: search classes in the file for a method with this name
                val classes = (psiFile as? com.intellij.psi.PsiJavaFile)?.classes ?: emptyArray()
                classes.flatMap { it.methods.toList() }
                    .firstOrNull { it.name == symbol }
                    ?.let { return it }
            }
        }

        // Global resolution via all registered providers — iterate until one resolves the symbol
        registry.allProviders().firstNotNullOfOrNull { p ->
            p.findSymbol(project, symbol)
        }?.let { return it }

        // Final fallback via PsiShortNamesCache (in case provider didn't find it)
        val shortNameCache = com.intellij.psi.search.PsiShortNamesCache.getInstance(project)
        shortNameCache.getMethodsByName(symbol, scope).firstOrNull()?.let { return it }
        shortNameCache.getFieldsByName(symbol, scope).firstOrNull()?.let { return it }

        return null
    }
}
