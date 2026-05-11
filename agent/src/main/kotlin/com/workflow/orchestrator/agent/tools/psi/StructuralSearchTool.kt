package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.ide.LanguageProviderRegistry
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

class StructuralSearchTool(
    private val registry: LanguageProviderRegistry
) : AgentTool {
    override val name = "structural_search"
    override val description = "Search for code patterns using structural search syntax. " +
        "More powerful than regex — matches code structure semantically. " +
        "Use \$var\$ for template variables. " +
        "Example: 'System.out.println(\$arg\$)' finds all println calls."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "pattern" to ParameterProperty(
                type = "string",
                description = "SSR pattern with \$var\$ template variables"
            ),
            "file_type" to ParameterProperty(
                type = "string",
                description = "Language: \"java\" or \"kotlin\" (default: tries all SSR-capable providers). Python is not supported."
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

    override fun documentation(): ToolDocumentation = toolDoc("structural_search") {
        summary {
            technical(
                "IntelliJ Structural Search and Replace (SSR) over Java/Kotlin source — drives " +
                    "`MatchOptions + Matcher + CollectingMatchResultSink` with user-supplied SSR template " +
                    "patterns (e.g. `\$Instance\$.equals(\$Arg\$)`) against `GlobalSearchScope`. " +
                    "Iterates `allProviders()` via `firstNotNullOfOrNull`; only `JavaKotlinProvider` " +
                    "implements the operation — `PythonProvider.structuralSearch()` is a stub that " +
                    "always returns null. Hard cap: `JavaKotlinProvider` takes at most 50 raw matches " +
                    "before the tool applies its own `max_results` (default 20) truncation."
            )
            plain(
                "Like grep, but it understands code structure. A regex for `equals` hits comments, " +
                    "string literals, method names in docs, and every variant spelling. SSR lets the " +
                    "agent write a template like `\$x\$.equals(\$y\$)` that matches the CALL as an " +
                    "AST node — skipping comments, string contents, and unrelated identifiers. Think " +
                    "of it as 'find all the places where this specific code shape appears', not just " +
                    "'find this text'."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.READ_ONLY)
        counterfactual(
            "Without structural_search, the LLM falls back to `search_code` with a regex. For " +
                "structural patterns the gap is severe: a regex for `\\.equals\\(` matches inside " +
                "string literals, Javadoc comments, method declarations named `equals`, and " +
                "multi-line calls split across lines where the regex anchor fails. SSR's AST-level " +
                "matching eliminates all of those false positives in a single tool call. The cost " +
                "of regex fallback is manual filtering across potentially dozens of false-positive " +
                "matches — and on patterns with template variables (e.g., capturing all arguments " +
                "to a varargs call), regex simply cannot capture the structural binding; the LLM " +
                "would need to hand-parse AST structure from read_file output."
        )
        llmMistake(
            "Uses regex syntax instead of SSR template syntax — writes `\\.equals\\(.*\\)` instead " +
                "of `\$x\$.equals(\$y\$)`. The Matcher parses the pattern as an SSR template, not a " +
                "regex; the literal `\\.` and `.*` are treated as SSR text tokens and the search " +
                "produces no matches or an exception. SSR variables use the `\$Name\$` dollar-sign " +
                "convention, not regex anchors."
        )
        llmMistake(
            "Passes `file_type = \"python\"` expecting Python structural search results. " +
                "Python SSR is unsupported — the dispatch site checks `supportsStructuralSearch()` " +
                "and returns a clear 'SSR not supported for language Python' error immediately. " +
                "The LLM must use `search_code` for Python pattern matching instead."
        )
        llmMistake(
            "Writes a Kotlin-specific SSR pattern (e.g., `val \$x\$ = \$y\$`) and omits `file_type`. " +
                "While `JavaKotlinProvider` now correctly selects the Kotlin file type when " +
                "`file_type = \"kotlin\"` is provided, omitting `file_type` causes the Matcher to " +
                "run with the Java file type by default. Always specify `file_type = \"kotlin\"` " +
                "when the pattern targets Kotlin-only syntax."
        )
        llmMistake(
            "Calls structural_search during IDE indexing. `PsiToolUtils.isDumb(project)` at entry " +
                "returns a hard dumb-mode error immediately. The `inSmartMode(project)` deferral " +
                "applies only AFTER entry — it does not wait for indexing to finish at the call site. " +
                "The LLM must retry after indexing completes, not immediately loop."
        )
        llmMistake(
            "Writes a complex multi-statement SSR pattern expecting full method-body matching. " +
                "The SSR engine excels at expression- and statement-level patterns; deep block-level " +
                "patterns (matching a sequence of statements in an arbitrary method body) can silently " +
                "time out or return no results without a clear error. Simpler expression patterns are " +
                "more reliable."
        )
        params {
            required("pattern", "string") {
                llmSeesIt("SSR pattern with \$var\$ template variables")
                humanReadable(
                    "An IntelliJ SSR template string. `\$Var\$` placeholders are named template " +
                        "variables that match any expression of the appropriate kind. Example: " +
                        "`\$Instance\$.equals(\$Arg\$)` matches every call to `.equals()` on any receiver " +
                        "with any argument. Typed constraints (e.g. `\$x\$:[exprtype( java.lang.String )]`) " +
                        "narrow the match to a specific type. The pattern is parsed by IntelliJ's SSR " +
                        "engine — not a regex, not a glob."
                )
                whenPresent(
                    "Fed directly to `MatchOptions.setSearchPattern()`. The Matcher parses it as an " +
                        "SSR template and runs the search. Invalid SSR syntax typically causes an exception " +
                        "caught by the outer `try/catch`, returning `\"Error: structural search failed — <message>\"`."
                )
                constraint("SSR template syntax, not regex — `\$Name\$` for variables, not `.*`")
                constraint("Must not be blank — blank pattern returns an immediate validation error")
                constraint("Complex block patterns may time out or produce no results silently")
                example("""${'$'}Instance${'$'}.equals(${'$'}Arg${'$'})""")
                example("""System.out.println(${'$'}arg${'$'})""")
                example("""if (${'$'}condition${'$'}) { ${'$'}body${'$'} }""")
                example("""new ${'$'}Type${'$'}()""")
            }
            optional("file_type", "string") {
                llmSeesIt("""Language: "java", "kotlin", or "python" (default: tries all available)""")
                humanReadable(
                    "Restricts the provider used. Maps `\"java\"` → `\"JAVA\"`, `\"kotlin\"`/`\"kt\"` → " +
                        "`\"kotlin\"`, `\"python\"`/`\"py\"` → `\"Python\"`. " +
                        "WARNING: Python SSR is not supported — specifying `\"python\"` returns a clear " +
                        "'SSR not supported for language Python' error. " +
                        "For Kotlin patterns, always specify `file_type = \"kotlin\"` so the Matcher " +
                        "runs against Kotlin sources (not Java)."
                )
                whenPresent(
                    "Calls `registry.forLanguageId(langId)`. The dispatch site then checks " +
                        "`provider.supportsStructuralSearch()` — if false (e.g. PythonProvider), " +
                        "the tool returns an immediate 'SSR not supported' error. If true " +
                        "(JavaKotlinProvider), the langId is forwarded so the correct file type " +
                        "(Kotlin or Java) is selected inside the Matcher."
                )
                whenAbsent(
                    "Filters `allProviders()` to those where `supportsStructuralSearch()` is true " +
                        "(currently only JavaKotlinProvider) and tries them in order. " +
                        "PythonProvider is excluded even when registered."
                )
                enumValue("java", "kotlin", "kt", "python", "py")
                example("java")
                example("kotlin")
            }
            optional("scope", "string") {
                llmSeesIt("""Search scope: "project" (default) or a module name""")
                humanReadable(
                    "Controls which files the SSR engine scans. `\"project\"` maps to " +
                        "`GlobalSearchScope.projectScope(project)`, excluding library JARs and JDK. " +
                        "Any other string is treated as a module name — `ModuleManager.findModuleByName()` " +
                        "is called and on a hit `GlobalSearchScope.moduleScope(module)` is used. " +
                        "If the module name is not found, silently falls back to project scope."
                )
                whenPresent(
                    "Passed to `resolveScope()`. A module name that matches an existing module " +
                        "narrows the search to that module's source roots only."
                )
                whenAbsent("Defaults to `\"project\"` — whole-project scan.")
                constraint(
                    "Unrecognised module names silently fall back to project scope with no warning; " +
                        "the LLM gets full-project results without knowing the narrowing failed"
                )
                example("project")
                example("automation")
                example("bamboo")
            }
            optional("max_results", "integer") {
                llmSeesIt("Maximum number of results to return (default: 20)")
                humanReadable(
                    "Secondary cap applied AFTER `JavaKotlinProvider`'s own internal hard cap of 50 " +
                        "raw matches. If the provider returns 50 and `max_results` is 20, the tool " +
                        "shows 20 with a `... (30 more)` footer. If `max_results` > 50, the provider's " +
                        "internal cap still limits the actual hit set to 50 regardless."
                )
                whenPresent("Applied via `results.take(maxResults)` after the provider returns.")
                whenAbsent("Defaults to 20.")
                constraint("Effective maximum is min(max_results, 50) due to provider's internal cap")
                constraint("Values that fail to parse as integer silently default to 20")
                example("10")
                example("50")
            }
        }
        verdict {
            keep(
                "Earns its slot for Java/Kotlin codebases where the LLM needs to find structural " +
                    "patterns rather than text patterns. The canonical use cases — find all `.equals()` " +
                    "calls that should be `==` in Kotlin, find all `new` expressions of a deprecated " +
                    "type, find all `null` checks on a variable — are genuinely impossible to do " +
                    "accurately with `search_code` regex without prohibitive false-positive filtering. " +
                    "The schema cost is low (single-action, four params) and the tool is deferred, " +
                    "so it only inflates context when the LLM explicitly loads it.",
                VerdictSeverity.NORMAL,
            )
            drop(
                "Niche within niche: the LLM rarely reaches for SSR unprompted because most " +
                    "'find all X' tasks are adequately served by `search_code` + manual filtering. " +
                    "Python support is absent (clear error returned). " +
                    "The SSR template syntax has a steep learning curve — the LLM frequently writes " +
                    "regex instead and gets empty results. For the typical agentic workflow " +
                    "(read, edit, test), structural search is rarely the bottleneck.",
                VerdictSeverity.NORMAL,
            )
        }
        related("search_code", Relationship.FALLBACK, "Use for Python files (SSR not supported), " +
            "for JavaScript/TypeScript/Go/Rust (no provider registered), and when the SSR pattern " +
            "is too complex or the LLM cannot express the structure in SSR template syntax. " +
            "search_code regex is always available; structural_search is a precision upgrade for " +
            "Java/Kotlin where regex false positives are a real problem.")
        related("find_references", Relationship.COMPLEMENT, "find_references answers 'where is this " +
            "symbol used?' (PSI index, zero false positives). structural_search answers 'where does " +
            "this code SHAPE appear?' (pattern match, catches anonymous calls and literal expressions " +
            "that find_references cannot index). Use together: find_references for a named symbol, " +
            "structural_search for anonymous patterns around that symbol.")
        related("find_implementations", Relationship.SEE_ALSO, "find_implementations is the right " +
            "tool for 'who implements this interface'; structural_search is the right tool for 'find " +
            "all call sites that match this call shape'. Different questions, different engines.")
        downside(
            "Python SSR unsupported — returns a clear 'SSR not supported for language Python' error " +
                "immediately. The LLM must use `search_code` for Python pattern matching."
        )
        downside(
            "Double cap with unequal enforcement — the provider hard-caps at 50 raw matches before " +
                "mapping to `StructuralMatchInfo`; `max_results` is a second cap applied by the tool. " +
                "If the project has 200 matches, the LLM can only ever see 50 regardless of " +
                "`max_results`. The `... (N more)` footer reflects the PROVIDER'S reported total, " +
                "which is also capped at 50, so the count can be misleading."
        )
        downside(
            "Complex block patterns are unreliable — SSR is optimised for expression- and " +
                "statement-level templates. Multi-statement block patterns (matching a sequence of " +
                "statements across an arbitrary method body) can silently time out inside the " +
                "Matcher and surface as a caught exception returning null. " +
                "Simpler expression patterns are more reliable."
        )
        downside(
            "Module scope fallback is silent — if `scope` names a non-existent module, " +
                "`resolveScope()` falls back to project scope with no warning. " +
                "The LLM receives full-project results and has no way to know the narrowing was ignored."
        )
        downside(
            "Requires full project indexing — `PsiToolUtils.isDumb(project)` at entry and " +
                "`inSmartMode(project)` wrapping the read action both require indexing to be complete. " +
                "On first project open, the tool is unavailable until indexing finishes."
        )
        observation(
            "Bug 12 + Bug 13 fixed (Batch 22 swarm). " +
                "Bug 12: dispatch now checks `provider.supportsStructuralSearch()` before adding " +
                "a provider to `providersToTry`. When `file_type = \"python\"`, the tool returns " +
                "'SSR not supported for language Python' instead of the misleading " +
                "'provider returned null'. " +
                "Bug 13: `JavaKotlinProvider.structuralSearch()` now picks the Kotlin file type " +
                "(via `FileTypeManager.findFileTypeByName(\"Kotlin\")`) when the caller passes " +
                "`langId = \"kotlin\"`, instead of always hardwiring `JavaFileType.INSTANCE`. " +
                "Regression tests: StructuralSearchToolTest and JavaKotlinProviderStructuralSearchTest."
        )
    }

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

        val scopeName = params["scope"]?.jsonPrimitive?.content ?: "project"
        val maxResults = try {
            params["max_results"]?.jsonPrimitive?.int ?: 20
        } catch (_: Exception) { 20 }

        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        val fileType = params["file_type"]?.jsonPrimitive?.content

        // Resolve provider: use file_type if specified, otherwise try all providers
        val allProviders = registry.allProviders()
        if (allProviders.isEmpty()) {
            return ToolResult(
                "Code intelligence not available — no language provider registered",
                "Error: no provider",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        val resolvedLangId: String?
        val providersToTry = if (fileType != null) {
            // Map common file type names to language IDs
            val langId = when (fileType.lowercase()) {
                "java" -> "JAVA"
                "kotlin", "kt" -> "kotlin"
                "python", "py" -> "Python"
                else -> fileType
            }
            resolvedLangId = langId
            val specific = registry.forLanguageId(langId)
            if (specific != null && !specific.supportsStructuralSearch()) {
                return ToolResult.error(
                    "Structural search is not supported for language '$langId'. " +
                    "SSR is currently Java/Kotlin only."
                )
            }
            val candidates = if (specific != null) listOf(specific) else allProviders
            candidates.filter { it.supportsStructuralSearch() }
        } else {
            resolvedLangId = null
            allProviders.filter { it.supportsStructuralSearch() }
        }

        if (providersToTry.isEmpty()) {
            return ToolResult.error(
                "No registered language provider supports structural search. " +
                "Install the Java/Kotlin plugin."
            )
        }

        val content = try {
            val results = ReadAction.nonBlocking<List<com.workflow.orchestrator.agent.ide.StructuralMatchInfo>?> {
                val scope = resolveScope(project, scopeName)
                // Try each SSR-capable provider until one returns non-null results.
                // For JavaKotlinProvider, pass the resolved langId so Kotlin patterns
                // use the Kotlin file type instead of hardwired JavaFileType.
                providersToTry.firstNotNullOfOrNull { provider ->
                    if (provider is com.workflow.orchestrator.agent.ide.JavaKotlinProvider) {
                        provider.structuralSearch(project, pattern, scope, resolvedLangId)
                    } else {
                        provider.structuralSearch(project, pattern, scope)
                    }
                }
            }.inSmartMode(project).executeSynchronously()

            if (results == null) {
                "Error: structural search failed — provider returned null"
            } else if (results.isEmpty()) {
                "No matches found for pattern: $pattern"
            } else {
                val shown = results.take(maxResults)
                val sb = StringBuilder()
                sb.appendLine("Found ${results.size} match${if (results.size != 1) "es" else ""} for pattern: $pattern")
                sb.appendLine()
                shown.forEachIndexed { index, match ->
                    sb.appendLine("${index + 1}. ${match.filePath}:${match.line}")
                    sb.appendLine("   ${match.matchedText.take(100)}")
                    if (index < shown.size - 1) sb.appendLine()
                }
                if (results.size > maxResults) {
                    sb.appendLine("\n... (${results.size - maxResults} more)")
                }
                sb.toString().trimEnd()
            }
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
}
