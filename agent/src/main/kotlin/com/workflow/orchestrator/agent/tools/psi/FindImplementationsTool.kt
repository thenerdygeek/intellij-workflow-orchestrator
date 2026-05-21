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
import kotlinx.serialization.json.jsonPrimitive

class FindImplementationsTool(
    private val registry: LanguageProviderRegistry
) : AgentTool {
    override val name = "find_implementations"
    override val description = "Find concrete implementations of an interface method or abstract method. Shows which classes implement a specific method with file locations."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "method" to ParameterProperty(type = "string", description = "Method name to find implementations of"),
            "class_name" to ParameterProperty(type = "string", description = "Optional: fully qualified or simple class/interface name containing the method. Required if method name is ambiguous.")
        ),
        required = listOf("method")
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.REVIEWER)

    override fun documentation(): ToolDocumentation = toolDoc("find_implementations") {
        summary {
            technical("PSI 'go-to-implementation' for an interface/abstract method or class — resolves the symbol via `LanguageIntelligenceProvider.findSymbol` (composing `class_name#method` when both are passed), then dispatches by element kind: `PsiMethod` runs `OverridingMethodsSearch.search(scope, deep=true)` for every overriding method (Java/Kotlin) or walks Python subclass MRO for matching method names; `PsiClass` runs `ClassInheritorsSearch.search(scope, deep=true)` for every concrete subclass. Returns `name: signature` plus `filePath:line` rows, capped at 40 hits with a `... and N more` footer.")
            plain("The agent's 'Go to Implementation' (Cmd-Alt-B in IntelliJ). Given an interface or an abstract method, it answers 'who actually does this thing?' — listing every concrete class that implements the interface or every method that overrides the abstract declaration. Same machinery the IDE uses when you Cmd-click a method name on an interface; just driven by the LLM.")
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.READ_ONLY)
        counterfactual(
            "Without find_implementations, the LLM falls back to `search_code` for the interface or method name and tries to filter manually — which mixes the interface declaration, every implementor's `override fun` / `@Override`, every import statement, and every reference into one undifferentiated regex hit list. The LLM has to read every match to classify it as an implementation vs. a usage vs. an unrelated same-named symbol. Worse, deep inheritance chains (Spring's `JpaRepository` → `CrudRepository` → `Repository`, or any subtype that overrides at the grandchild level) are invisible to a name-only regex unless the grandchild explicitly mentions the parent name in its source. Net cost: ~5-10x more tool calls per 'who satisfies this contract' question, with systematically incomplete results on transitive overrides."
        )
        llmMistake("Confuses 'implementations' with 'subclasses' and runs `find_implementations` on a CONCRETE class that nothing extends — gets 'No implementations found' and concludes the class has no users, when the correct question was `find_references` (call sites) or `type_hierarchy` (subtypes that may not exist yet). Implementations is for INTERFACES / ABSTRACT classes / abstract methods specifically; for concrete-class-extension questions, use type_hierarchy.")
        llmMistake("Calls `find_implementations` on a non-abstract method of a concrete class — `OverridingMethodsSearch` returns subclass overrides if any exist, otherwise an empty list. The LLM treats the empty result as 'this method has no implementations' rather than 'this method has no overrides because nothing extends the class'. Correct interpretation: empty list = no overrides exist in project scope, NOT 'this method is unimplemented'.")
        llmMistake("Expects Python's `typing.Protocol` (duck-typed structural subtyping) to surface — `PythonProvider.findImplementations` walks `helper.findInheritors`, which traces nominal class inheritance only. A class that satisfies a Protocol structurally without `class Foo(MyProtocol):` declaration WILL NOT appear, so the LLM gets a falsely empty list for protocol-style interfaces.")
        llmMistake("Calls find_implementations during indexing — `PsiToolUtils.isDumb(project)` guard at entry returns immediately with a dumb-mode error. The `inSmartMode(project)` deferral on the read action only applies AFTER entry; the LLM gets a hard fail, not a wait, and often retries without backoff.")
        llmMistake("Searches for a Kotlin top-level function or extension function — these aren't `PsiMethod` members, so `findSymbol` either misses them or returns a non-method element, and `findImplementations` falls through to its `else -> emptyList()` branch silently. The LLM sees 'No implementations found' with no signal that the symbol kind was wrong.")
        llmMistake("Forgets the `class_name` hint on a common method name like `execute`, `build`, `run` — `findSymbol` walks `PsiShortNamesCache.getMethodsByName` and picks the first match, which may be a different class entirely. Implementations come back for the WRONG interface, and the LLM presents them as canonical with no warning.")
        params {
            required("method", "string") {
                llmSeesIt("Method name to find implementations of")
                humanReadable("Either a method name (interface or abstract method) OR a class name (interface, abstract class). The parameter is named `method` for historical reasons but the tool resolves the symbol generically — passing a class name returns its concrete subclasses; passing a method name returns its overrides.")
                whenPresent("Composed into `class_name#method` if both params are set, else passed bare. Routed to `registry.allProviders().firstNotNullOfOrNull { findSymbol }`, which iterates ALL registered language providers (Java/Kotlin and Python) until one resolves. The matching provider then drives `findImplementations`: `OverridingMethodsSearch` for `PsiMethod`, `ClassInheritorsSearch` for `PsiClass`, empty list for any other element kind.")
                constraint("must resolve to a `PsiMethod` or `PsiClass` — fields, top-level Kotlin functions, and Python module-level functions return an empty list with no warning")
                constraint("case-sensitive — `Repository` and `repository` are distinct lookups")
                example("execute")
                example("Repository")
                example("AgentTool")
                example("findSymbol")
            }
            optional("class_name", "string") {
                llmSeesIt("Optional: fully qualified or simple class/interface name containing the method. Required if method name is ambiguous.")
                humanReadable("Scope hint for the method lookup when multiple interfaces/classes share a method name. Built into the `class_name#method` symbol string and tried first; on a miss the bare-method path runs.")
                whenPresent("Composes `<class_name>#<method>` and feeds it to `findSymbol` across all providers. First provider that resolves it wins, and its `findImplementations` runs against the resolved element.")
                whenAbsent("Bare method name is fed directly to `findSymbol`. For ambiguous names like `execute` or `build`, `PsiShortNamesCache.getMethodsByName` returns the first match in iteration order; the tool does NOT emit a disambiguation note (unlike `find_definition`), so a wrong-overload pick is silent.")
                example("AgentTool")
                example("com.workflow.orchestrator.agent.tools.AgentTool")
                example("Repository")
            }
        }
        verdict {
            keep(
                "Strong keep for any OO/inheritance-heavy codebase. The counterfactual (regex on the interface or method name) is dramatically worse: it loses transitive overrides at grandchild depth, mixes implementations with imports/references, and silently misses Kotlin overrides that don't textually mention the parent. PSI's `OverridingMethodsSearch` and `ClassInheritorsSearch` are the index-backed answers to 'who satisfies this contract', and there is no name-based substitute that gets close on either accuracy or token cost. Less universal than `find_definition`/`find_references` because Python projects with mostly Protocol-style typing get little value, but for any Java/Kotlin/Spring codebase this earns its schema slot decisively.",
                VerdictSeverity.STRONG,
            )
        }
        related("type_hierarchy", Relationship.COMPLEMENT, "Class-relation pair. type_hierarchy walks BOTH directions (supertypes + subtypes) and returns the full inheritance tree. find_implementations returns ONLY the concrete leaves — the actual implementors. Use type_hierarchy when you want the structural picture of who-extends-what; use find_implementations when you specifically want 'who has to satisfy this contract'.")
        related("find_references", Relationship.ALTERNATIVE, "find_references on an interface method tends to return both call sites AND override declarations mixed together; find_implementations returns ONLY the override declarations. Use find_implementations when you need a clean 'who satisfies the contract' answer; use find_references when you also want the call sites.")
        related("find_definition", Relationship.COMPOSE_WITH, "Canonical workflow: find_definition first to anchor the interface/abstract method (confirm the symbol exists and see its signature), then find_implementations to enumerate the concrete satisfiers. find_definition → find_implementations is the standard 'what's the contract and who fulfills it' pipeline.")
        related("call_hierarchy", Relationship.COMPOSE_WITH, "After find_implementations enumerates concrete overrides of an abstract method, call_hierarchy on each implementation traces who calls THAT specific override. Useful when an interface has many implementors and you need to understand which subtype is wired into which call path.")
        related("search_code", Relationship.FALLBACK, "Use when no `LanguageIntelligenceProvider` exists for the language (JS/TS, Go, Rust today), when the symbol is duck-typed (Python `typing.Protocol`, JS structural typing), or when find_implementations returns 'No implementations found' on a symbol that you know is an interface — the result may be missing transitive overrides through reflection, dynamic dispatch, or generated code that the index can't see.")
        downside("Hard 40-result cap with no pagination, sort key, or filter — `implementations.take(40)` truncates without sorting by file path or relevance. For ubiquitous interfaces (Spring's `Repository`, JDK's `Comparable`, `Runnable`), the displayed implementations are an arbitrary first-40 subset of the searcher's iteration order. The trailing `... and N more` line distinguishes 'all of them' from 'a tiny biased sample', but the LLM occasionally drops the footer when summarising.")
        downside("Requires full project indexing to be complete — `PsiToolUtils.isDumb(project)` guard at entry returns a hard dumb-mode error if the index is still building. The `inSmartMode(project)` deferral applies only AFTER entry, so it doesn't help here. LLM has to retry, not wait.")
        downside("Library/JDK implementations are NOT enumerated by default — `findImplementations` runs against `GlobalSearchScope.projectScope(project)` (line 49 of FindImplementationsTool.kt), which excludes external libraries and the JDK. For 'who implements `java.lang.Comparable`' across the JDK + libraries, the project scope returns only project-owned implementations. Workaround: there is no parameter to widen scope; the LLM must use `search_code` with a regex against library JARs, or fall back to `find_references` which has the same scope limitation.")
        downside("Python Protocol / structural typing is invisible — `PythonProvider.findImplementations` traces nominal class inheritance via `helper.findInheritors`. A class that satisfies a `typing.Protocol` structurally without an explicit `class Foo(MyProtocol):` parent declaration is not returned. For Python projects that lean on Protocol, this tool dramatically under-reports.")
        downside("Element-kind filter is silent — non-method, non-class elements (fields, top-level Kotlin functions, Python module-level functions, lambdas, type aliases) hit the `else -> emptyList()` branch in both providers and return 'No implementations found' indistinguishable from 'this is an interface with zero implementors'. The LLM has no signal that the symbol kind was wrong.")
        downside("No disambiguation note on overloaded method names — unlike `find_definition`, this tool does NOT append a 'N other method(s) with same name' warning when `PsiShortNamesCache` reports multiple matches. A bare method name like `execute` silently picks one overload and returns implementations of THAT one only, with no hint that `class_name` would help.")
        observation("VERIFIED: `find_implementations` does NOT have the hardcoded `JAVA`/`kotlin` provider-fallback bug that affects `find_definition` (line 113) and `find_references` (line 227). Lines 38-59 of FindImplementationsTool.kt use the correct pattern: enumerate `registry.allProviders()`, then `firstNotNullOfOrNull { p -> p.findSymbol(project, symbolName)?.let { p to it } }`. This means find_implementations works correctly in pure-Python projects where `PythonProvider` is registered but no Java plugin is loaded — the same correct pattern used by `call_hierarchy` and `type_hierarchy`. When fixing the find_definition / find_references bugs, this file is the reference template.")
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val methodName = params["method"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'method' parameter required", "Error: missing method", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val className = params["class_name"]?.jsonPrimitive?.content

        // Resolve provider: try all registered providers until one finds the symbol
        val allProviders = registry.allProviders()
        if (allProviders.isEmpty()) {
            return ToolResult(
                "Code intelligence not available — no language provider registered",
                "Error: no provider",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        val content = ReadAction.nonBlocking<String> {
            val scope = GlobalSearchScope.projectScope(project)

            // Find the method element via provider — try each provider until one finds the symbol
            val symbolName = if (className != null) "$className#$methodName" else methodName
            val (provider, element) = allProviders.firstNotNullOfOrNull { p ->
                p.findSymbol(project, symbolName)?.let { p to it }
            } ?: return@nonBlocking if (className != null) {
                    "No method '$methodName' found in class '$className'."
                } else {
                    "No method '$methodName' found in project. Try providing 'class_name' to narrow the search."
                }

            val implementations = provider.findImplementations(element, scope)
            if (implementations.isEmpty()) {
                return@nonBlocking "No implementations found for '$methodName'. The method may not be abstract/interface, or has no overrides in project scope."
            }

            val sb = StringBuilder()
            sb.appendLine("Implementations of $methodName (${implementations.size} found):")
            sb.appendLine()

            implementations.take(40).forEach { impl ->
                sb.appendLine("  ${impl.name}: ${impl.signature}")
                sb.appendLine("    at ${impl.filePath}:${impl.line}")
            }
            if (implementations.size > 40) {
                sb.appendLine("  ... and ${implementations.size - 40} more")
            }

            sb.toString().trim()
        }.inSmartMode(project).executeSynchronously()

        return ToolResult(
            content = content,
            summary = "Implementations of '$methodName'",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }
}
