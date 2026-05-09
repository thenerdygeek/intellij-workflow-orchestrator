package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.ide.DefinitionInfo
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
import kotlinx.serialization.json.jsonPrimitive

class FindDefinitionTool(
    private val registry: LanguageProviderRegistry
) : AgentTool {
    override val name = "find_definition"
    override val description = "Find the declaration/definition location of a class, method, or field."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "symbol" to ParameterProperty(type = "string", description = "Symbol name to find (class FQN, method name, or field name)"),
            "class_name" to ParameterProperty(type = "string", description = "Optional: class name for disambiguation when multiple symbols share the same name")
        ),
        required = listOf("symbol")
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.REVIEWER)

    override fun documentation(): ToolDocumentation = toolDoc("find_definition") {
        summary {
            technical("PSI go-to-definition for a class (FQN or simple name), method (`Class#method` / bare name), or field — delegates to the per-language `LanguageIntelligenceProvider` resolved from the file containing the matched symbol, returning file path, line, signature, and (for classes) a formatted skeleton.")
            plain("The agent's Cmd-click. Give it a symbol name and it jumps to where the class, function, or field is declared — same machinery the IDE uses when you Cmd/Ctrl-click an identifier, just driven by the LLM instead of a mouse.")
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.READ_ONLY)
        counterfactual(
            "Without find_definition, the LLM falls back to `search_code` with the symbol name as a regex — which matches every reference, import, comment, and string literal mentioning the name (often 50+ hits for common methods like `execute` or `build`), forcing the LLM to read each candidate file to identify the declaration. Net cost: ~3-5x more tool calls per symbol lookup, and the LLM frequently picks the wrong file because it can't distinguish definition from usage textually."
        )
        llmMistake("Passes a snake_case identifier when the codebase uses camelCase (or vice versa) — the underlying `findSymbol` lookup is case- and style-sensitive, so `find_user_by_id` returns 'No definition found' even when `findUserById` exists. The LLM then often retries `search_code` blindly instead of fixing the casing.")
        llmMistake("Skips the `class_name` hint on overloaded methods — gets the first match plus a '(N other method(s) with same name)' note, then proceeds as if the first hit is canonical. The disambiguation hint fires only for `PsiMethod`, not for fields or top-level Python functions, so the LLM can silently land on the wrong overload.")
        llmMistake("Calls find_definition during indexing — gets a dumb-mode error and immediately retries without backoff, burning iterations until the index finishes. No internal wait/retry; the LLM should switch to `search_code` or wait one tool-call cycle.")
        llmMistake("Calls find_definition in a pure-Python project (no Java plugin loaded) — the tool's hardcoded fallback resolves only `JAVA`/`kotlin` providers when no element context is available, so the very first call returns 'no language provider registered' even though `PythonProvider` IS in the registry. See downsides; this is a real registration bug, not LLM misuse.")
        llmMistake("Searches for a Kotlin top-level function or extension function by bare name — `findSymbol` only walks `PsiShortNamesCache.getMethodsByName` (members of classes) and `getFieldsByName`, missing top-level `fun` declarations. The LLM gets 'No definition found' and falls back to `search_code`.")
        params {
            required("symbol", "string") {
                llmSeesIt("Symbol name to find (class FQN, method name, or field name)")
                humanReadable("What you're looking for. Can be a fully-qualified class name (`com.foo.Bar`), a simple class name (`Bar`), a method/field name, or `Class#method` / `Class.method` syntax to scope a member to its class.")
                whenPresent("The provider's `findSymbol` runs through (1) class FQN/simple-name lookup, (2) `Class#method` or `Class.method` parsing for member resolution, (3) bare-name fallback via `PsiShortNamesCache` (Java/Kotlin) or top-level walk (Python). First match wins; for methods, a disambiguation note is appended if the short-names cache reports multiple methods with the same name.")
                constraint("must be a single identifier or `Class#member` / `Class.member` / fully-qualified path — arbitrary text fails silently with 'No definition found'")
                constraint("case-sensitive — `MyClass` and `myclass` are distinct lookups")
                example("com.workflow.orchestrator.agent.AgentService")
                example("AgentService")
                example("AgentService#executeTask")
                example("UserRepository.findById")
            }
            optional("class_name", "string") {
                llmSeesIt("Optional: class name for disambiguation when multiple symbols share the same name")
                humanReadable("If multiple methods/fields share a name across the codebase, name the class to scope the lookup. Internally builds `class_name#symbol` and tries that first before falling back to the unscoped lookup.")
                whenPresent("`findSymbol` is called with the composed `<class_name>#<symbol>` string first; on a hit, that scoped result is returned. On a miss, the tool falls through to the bare-symbol path.")
                whenAbsent("Tool runs the bare-symbol path directly. For methods, the result includes a disambiguation note like `(3 other method(s) with same name — provide class_name to disambiguate)` when `PsiShortNamesCache` reports siblings.")
                example("UserRepository")
                example("PaymentService")
            }
        }
        verdict {
            keep(
                "Foundational read-only IDE intelligence. Forms the navigation backbone for any 'understand this codebase' workflow, and the counterfactual (regex-match every textual occurrence of the name) is dramatically worse — both in tool-call count and in correctness.",
                VerdictSeverity.STRONG,
            )
        }
        related("find_references", Relationship.COMPLEMENT, "Definition vs. usages — find_definition jumps to where a symbol is declared, find_references lists every place it's used. Typical workflow: find_definition to locate the source, then find_references to see who depends on it.")
        related("search_code", Relationship.FALLBACK, "Use when the language has no LanguageIntelligenceProvider registered (e.g. JS/TS, Go, Rust today), when the symbol is dynamic (Python attribute lookups, runtime injection), or when find_definition returns 'No definition found'. Loses precision but always works.")
        related("call_hierarchy", Relationship.COMPOSE_WITH, "After find_definition locates a method, call_hierarchy traces who calls it (callers) and what it calls (callees). find_definition → call_hierarchy is the standard 'how is this method wired into the codebase' pipeline.")
        related("find_implementations", Relationship.COMPOSE_WITH, "After find_definition locates an interface or abstract method, find_implementations enumerates concrete subtypes/overrides. Together they answer 'what's the contract and who satisfies it'.")
        related("file_structure", Relationship.ALTERNATIVE, "Use instead when you have a file path and want every declaration in it — no symbol name needed.")
        downside("Hardcoded `JAVA`/`kotlin` fallback in the no-element-context path means find_definition is effectively unusable in pure-Python projects: the very first call returns 'no language provider registered' even though `PythonProvider` is registered in the registry. Workaround until fixed: drive Python lookups through `search_code`. Fix: iterate `registry.allProviders()` for the fallback or pick by language ID present in the registry.")
        downside("Returns the FIRST match for ambiguous bare-name lookups — `findSymbol` walks class → `Class#member` → short-names cache and stops on the first hit. For overloaded methods (Java method overloading, Kotlin extension functions on different receivers), the disambiguation note fires only for `PsiMethod` and only via `PsiShortNamesCache.getMethodsByName`. Fields and Python functions get no warning.")
        downside("Requires indexing to be complete — `inSmartMode(project)` defers the read action until smart mode, but the `PsiToolUtils.isDumb(project)` guard at entry returns immediately with a dumb-mode error if indexing is in progress, so the LLM gets a hard fail (not a wait).")
        downside("Kotlin top-level functions and extension functions are not resolved by bare name — `findSymbol` for Java/Kotlin uses `PsiShortNamesCache.getMethodsByName`, which indexes class members only. Workaround: pass the FQN of the containing file class (`FooKt`) or use `search_code`.")
        downside("Python `findSymbol` parses a single `.` as `Class.member` (2-part split) — multi-segment paths like `pkg.module.Class.method` won't resolve through the member path; the LLM has to use the bare-class lookup and rely on the FQN fallback through `PsiShortNamesCache`.")
        downside("Output format differs by element kind — classes get a skeleton block, methods get a signature line, fields get a type line. The LLM occasionally treats the field 'Type:' line as a method signature and fabricates a return type when summarising.")
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        val symbol = params["symbol"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'symbol' parameter required", "Error: missing symbol", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val classNameHint = params["class_name"]?.jsonPrimitive?.content

        val content = ReadAction.nonBlocking<String> {
            // Resolve provider from the file context of found symbols, falling back to hardcoded IDs
            fun resolveProvider(element: com.intellij.psi.PsiElement? = null): com.workflow.orchestrator.agent.ide.LanguageIntelligenceProvider? {
                // Prefer file-based resolution when we have an element
                if (element != null) {
                    val psiFile = element.containingFile
                    if (psiFile != null) {
                        registry.forFile(psiFile)?.let { return it }
                    }
                }
                // Fall back to hardcoded language IDs when no file context available
                return registry.forLanguageId("JAVA") ?: registry.forLanguageId("kotlin")
            }

            val fallbackProvider = resolveProvider()
                ?: return@nonBlocking "Code intelligence not available — no language provider registered"

            // If class_name hint provided, search within that class first using "class#symbol" syntax
            if (classNameHint != null) {
                val element = fallbackProvider.findSymbol(project, "$classNameHint#$symbol")
                if (element != null) {
                    val provider = resolveProvider(element) ?: fallbackProvider
                    val info = provider.getDefinitionInfo(element)
                    if (info != null) {
                        return@nonBlocking formatDefinitionOutput(element, info, symbol)
                    }
                }
            }

            // General symbol lookup (handles FQN, Class#method, bare names)
            val element = fallbackProvider.findSymbol(project, symbol)
            if (element != null) {
                val provider = resolveProvider(element) ?: fallbackProvider
                val info = provider.getDefinitionInfo(element)
                if (info != null) {
                    // Check for disambiguation hint
                    val disambiguationNote = if (element is PsiMethod) {
                        val scope = GlobalSearchScope.projectScope(project)
                        val cache = com.intellij.psi.search.PsiShortNamesCache.getInstance(project)
                        val allMethods = cache.getMethodsByName(element.name, scope)
                        if (allMethods.size > 1)
                            "\n\n(${allMethods.size - 1} other method(s) with same name — provide class_name to disambiguate)"
                        else ""
                    } else ""
                    return@nonBlocking formatDefinitionOutput(element, info, symbol) + disambiguationNote
                }
            }

            "No definition found for '$symbol'"
        }.inSmartMode(project).executeSynchronously()

        return ToolResult(
            content = content,
            summary = "Definition of '$symbol'",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }

    /**
     * Format the definition output to match the original tool's output format exactly.
     */
    private fun formatDefinitionOutput(
        element: com.intellij.psi.PsiElement,
        info: DefinitionInfo,
        originalSymbol: String
    ): String {
        return when (element) {
            is PsiClass -> {
                val qualifiedName = element.qualifiedName ?: element.name ?: originalSymbol
                val skeleton = info.skeleton
                "Definition of '$qualifiedName':\n" +
                    "  File: ${info.filePath}\n" +
                    "  Line: ${info.line}" +
                    if (skeleton != null) "\n\n$skeleton" else ""
            }
            is PsiMethod -> {
                val qualifiedRef = "${element.containingClass?.qualifiedName ?: ""}#${element.name}"
                "Definition of '$qualifiedRef':\n" +
                    "  File: ${info.filePath}\n" +
                    "  Line: ${info.line}\n" +
                    "  Signature: ${info.signature}"
            }
            is PsiField -> {
                val qualifiedRef = "${element.containingClass?.qualifiedName ?: ""}#${element.name}"
                "Definition of '$qualifiedRef':\n" +
                    "  File: ${info.filePath}\n" +
                    "  Line: ${info.line}\n" +
                    "  Type: ${element.type.presentableText}"
            }
            else -> {
                "Definition of '$originalSymbol':\n" +
                    "  File: ${info.filePath}\n" +
                    "  Line: ${info.line}\n" +
                    "  Signature: ${info.signature}"
            }
        }
    }
}
