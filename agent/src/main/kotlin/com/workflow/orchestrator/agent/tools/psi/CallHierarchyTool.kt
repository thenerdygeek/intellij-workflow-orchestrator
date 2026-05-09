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

class CallHierarchyTool(
    private val registry: LanguageProviderRegistry
) : AgentTool {
    override val name = "call_hierarchy"
    override val description = "Get callers (who calls this method) and callees (what this method calls)."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "method" to ParameterProperty(type = "string", description = "Method name to analyze"),
            "class_name" to ParameterProperty(type = "string", description = "Optional class name containing the method, for disambiguation"),
            "depth" to ParameterProperty(type = "integer", description = "How many levels deep to trace callers (default: 1, max: 3). depth=2 shows callers of callers.")
        ),
        required = listOf("method")
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.REVIEWER)

    override fun documentation(): ToolDocumentation = toolDoc("call_hierarchy") {
        summary {
            technical("Bidirectional call-graph walk for a method: callers (recursive `ReferencesSearch` up to `depth ∈ [1,3]` levels, IdentityHashMap-based visited set for cycle safety) and callees (one-level outbound calls via `PsiTreeUtil.findChildrenOfType(PsiMethodCallExpression)` for Java + Kotlin call expressions via reflection in `JavaKotlinProvider`). Both directions delegate to the per-language `LanguageIntelligenceProvider`; output is capped at 30 callers + 30 callees.")
            plain("Like the IDE's 'Show Call Hierarchy' tree, but for the agent — it picks one method and tells the LLM both who calls it (callers, traced upward through the codebase) and what it calls (callees, listed one level out). Same machinery as Cmd-Alt-H in IntelliJ, just driven by an LLM.")
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.READ_ONLY)
        counterfactual(
            "Without call_hierarchy, the LLM walks the call graph manually with repeated `find_references` calls — N tool calls collapse to 1 for a single method. Accuracy is roughly equivalent for callers (both wrap `ReferencesSearch`), but the LLM has no cycle-detection across hand-rolled recursion: feeding `find_references` results back into more `find_references` calls easily loops on mutual recursion (A→B→A), and the LLM doesn't track visited methods reliably. For callees there is no clean substitute — `find_references` is reference-IN, not call-OUT — so the LLM falls back to reading the method body and grepping for `(`, which is fragile."
        )
        llmMistake("Calls call_hierarchy on a hot, ubiquitously-called method (`logger.info`, `equals`, `toString`) at `depth=3` — explodes into thousands of edges, all silently truncated to the first 30 callers per level. The LLM treats the truncated output as canonical and reasons about a tiny biased sample. Use `class_name` to scope or pin `depth=1` for high-fanin methods.")
        llmMistake("Confuses 'callers' with 'called by' / 'callees' with 'calls' when summarising the output back to the user. The labels in the output (`Callers (who calls this method):` / `Callees (what this method calls):`) are unambiguous, but the LLM occasionally swaps them in prose. Bias check: callers point UP (who depends on me), callees point DOWN (what I depend on).")
        llmMistake("Calls call_hierarchy on a non-method element — a class, field, or constructor that isn't a `PsiMethod`. The provider's `findCallers`/`findCallees` cast `element as? PsiMethod` and silently return an empty list, producing `(no callers found)` + `(no callees found)`. The LLM then concludes the method is unused, when really it's a misuse.")
        llmMistake("Skips `class_name` for an overloaded method like `execute` or `build` — `findSymbol` returns the first match from `PsiShortNamesCache`, which may be a different overload than the one the user is asking about. Unlike `find_definition`, this tool does NOT append a 'N other methods with same name' disambiguation note; the LLM has no signal that disambiguation is needed.")
        llmMistake("Calls call_hierarchy during indexing — gets a dumb-mode error and retries immediately without backoff. The `inSmartMode(project)` deferral applies only after entry; the `PsiToolUtils.isDumb(project)` guard at the top short-circuits with a hard fail.")
        params {
            required("method", "string") {
                llmSeesIt("Method name to analyze")
                humanReadable("Which method's call graph to walk. Bare name (`executeTask`) or `Class#method` syntax (`AgentService#executeTask`); when ambiguous, use `class_name` instead of inlining the qualifier.")
                whenPresent("If `class_name` is set, the lookup composes `class_name#method` first; otherwise the bare name is fed to each provider's `findSymbol`. The first provider in `registry.allProviders()` that returns a `PsiMethod` wins, and that same provider drives both `findCallers` and `findCallees`.")
                constraint("must resolve to a `PsiMethod` — classes, fields, and Python top-level functions that aren't methods will silently return empty caller/callee lists (the cast `element as? PsiMethod` fails)")
                constraint("case-sensitive — `executeTask` and `executetask` are distinct lookups")
                example("executeTask")
                example("AgentService#executeTask")
                example("save")
            }
            optional("class_name", "string") {
                llmSeesIt("Optional class name containing the method, for disambiguation")
                humanReadable("If multiple classes have a method with this name (e.g. several `save()` methods), name the class to scope the lookup. Internally builds `class_name#method` and feeds that to `findSymbol`.")
                whenPresent("`findSymbol` is called with the composed `<class_name>#<method>` string. The first provider that resolves it wins.")
                whenAbsent("Tool runs the bare-name path. The first provider that finds ANY method with this name wins — there's no disambiguation note, so an overloaded name silently picks one overload.")
                example("UserRepository")
                example("PaymentService")
            }
            optional("depth", "integer") {
                llmSeesIt("How many levels deep to trace callers (default: 1, max: 3). depth=2 shows callers of callers.")
                humanReadable("How far up the caller chain to walk. `1` = direct callers only. `2` = callers and their callers. `3` = three levels up. Higher depths can explode for ubiquitously-called methods. Note: only callers are recursed; callees are always one level out.")
                whenPresent("Coerced into `[1, 3]`. The provider's `findCallers` does a DFS up the caller chain, marking each `PsiElement` in an `IdentityHashMap`-based visited set so mutual recursion (A→B→A) terminates instead of looping.")
                whenAbsent("Defaults to `1` — direct callers only.")
                constraint("coerced to `[1, 3]` — values outside this range are clamped, not rejected; `0` becomes `1`, `99` becomes `3`")
                example("1")
                example("2")
                example("3")
            }
        }
        verdict {
            keep(
                "Two genuine wins over `find_references` repetition: (1) cycle detection via IdentityHashMap-keyed visited set prevents infinite recursion on mutual call chains, which the LLM cannot reliably reproduce by hand, and (2) callees have no `find_references` substitute — `find_references` is reference-IN, not call-OUT, so without this tool the LLM falls back to reading bodies and grepping. Heavier than `find_references` (recursive search + truncation surprise) but earns its slot when the LLM needs the full picture for one method.",
                VerdictSeverity.NORMAL,
            )
        }
        related("find_references", Relationship.ALTERNATIVE, "Use instead when you only need direct callers of a single method (no recursion, no callees) — find_references is cheaper and returns full reference-site context lines, while call_hierarchy is summarized and capped at 30 entries per direction.")
        related("find_definition", Relationship.COMPLEMENT, "Run first to locate the method, then call_hierarchy to walk its call graph. find_definition → call_hierarchy is the canonical 'how is this method wired into the codebase' pipeline — same `findSymbol` resolution, but find_definition jumps to the source while call_hierarchy fans out from it.")
        related("type_hierarchy", Relationship.SEE_ALSO, "Class-relation analogue — type_hierarchy walks supertypes/subtypes (inheritance), call_hierarchy walks callers/callees (invocation). Use type_hierarchy for 'who extends this' / 'what does this extend'; use call_hierarchy for 'who calls this' / 'what does this call'.")
        related("find_implementations", Relationship.COMPOSE_WITH, "For interface or abstract methods, call_hierarchy on the abstract method finds CALLERS but no CALLEES (abstract methods have no body). Pair with find_implementations to enumerate concrete overrides, then call_hierarchy on each override for full coverage.")
        related("get_method_body", Relationship.COMPLEMENT, "Use after call_hierarchy when a caller looks suspicious — get_method_body fetches the source of a single caller without re-reading the whole file.")
        downside("Caller list is hard-capped at 30 entries per recursion level (`callers.take(30)`); callee list is hard-capped at 30 entries (`callees.take(30)`). For high-fanin methods (loggers, utility helpers, equals/hashCode), the displayed callers are an arbitrary first-30 subset of `ReferencesSearch` results — order is determined by the searcher, not by the user's intent. The LLM has no signal whether 30 is 'all of them' or 'a tiny biased sample'; only the trailing `... (N more)` line distinguishes.")
        downside("`depth` only recurses on callers, not callees. Callees are always exactly one level out — the listing is direct calls inside the method body via `PsiTreeUtil.findChildrenOfType(PsiMethodCallExpression)`, with no recursion into the called methods. Asking `depth=3` does NOT give a 3-deep callee tree.")
        downside("Cycle detection uses `PsiElement` identity (IdentityHashMap), not `.equals()`, because `PsiElement.equals()` is not a stable contract. This is correct, but it means the visited set is keyed on the LIVE PSI object — if PSI is invalidated mid-walk (rare, but possible during a write action) the visited set becomes meaningless. Read-only execution under `ReadAction.nonBlocking` makes this very unlikely in practice.")
        downside("Callees only resolve through method-call expressions the provider knows about — `JavaKotlinProvider` walks `PsiMethodCallExpression` (Java) plus Kotlin `KtCallExpression` via reflection. Lambda invocations, method references (`::foo`), reflective calls, and dynamic dispatch through interfaces resolve only when `resolveMethod()` succeeds; unresolved calls are silently dropped. Python callees are best-effort against the `PythonProvider` reflection helper.")
        downside("No disambiguation note for overloaded methods (unlike `find_definition`). When `executeTask` is overloaded, `findSymbol` returns the first match and the tool reports the call hierarchy of THAT overload only, with no `(N other method(s) with same name)` warning. The LLM has no signal that `class_name` would help.")
        downside("Output format mixes the two directions in a single string with section headers (`Callers (who calls this method):` / `Callees (what this method calls):`). LLMs occasionally lose the section boundary mid-summary and merge entries from both lists when reporting back to the user.")
        flowchart("""
            flowchart TD
                A[LLM calls call_hierarchy] --> B{Project in dumb mode?}
                B -- yes --> X1[Return dumb-mode error]
                B -- no --> C{method param present?}
                C -- no --> X2[Return missing-param error]
                C -- yes --> D{registry has any provider?}
                D -- no --> X3[Return no-provider error]
                D -- yes --> E[Build symbolName: class_name#method or method]
                E --> F[Iterate registry.allProviders firstNotNullOfOrNull findSymbol]
                F -- no match --> X4[Return No method found]
                F -- match --> G[Resolve provider that found it]
                G --> H[provider.findCallers method, depth, scope]
                H --> I[DFS with IdentityHashMap visited set; recurse to depth]
                I --> J[Take first 30 callers; truncate note if more]
                J --> K[provider.findCallees method]
                K --> L[Take first 30 callees; truncate note if more]
                L --> M[Format both sections; spillOrFormat if large]
                M --> N[Return content + spillPath]
        """)
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        val methodName = params["method"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'method' parameter required", "Error: missing method", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val className = params["class_name"]?.jsonPrimitive?.content
        val maxDepth = (params["depth"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1).coerceIn(1, 3)

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
            // Find the method via provider — try each provider until one finds the symbol
            val symbolName = if (className != null) "$className#$methodName" else methodName
            val (provider, psiMethod) = allProviders.firstNotNullOfOrNull { p ->
                p.findSymbol(project, symbolName)?.let { p to it }
            } ?: return@nonBlocking "No method '$methodName' found" +
                        (if (className != null) " in class '$className'" else " in project")

            val qualifiedMethodName = (psiMethod as? com.intellij.psi.PsiMethod)?.let {
                "${it.containingClass?.name ?: ""}#${it.name}"
            } ?: methodName

            val sb = StringBuilder()
            sb.appendLine("Call hierarchy for $qualifiedMethodName:")

            // Callers: delegate to provider
            sb.appendLine("\nCallers (who calls this method):")
            val scope = GlobalSearchScope.projectScope(project)
            val callers = provider.findCallers(psiMethod, maxDepth, scope)
            if (callers.isEmpty()) {
                sb.appendLine("  (no callers found)")
            } else {
                callers.take(30).forEach { caller ->
                    val indent = "  " + "  ".repeat(caller.depth - 1)
                    sb.appendLine("$indent${caller.name}  (${caller.filePath}:${caller.line})")
                }
                if (callers.size > 30) {
                    sb.appendLine("  ... (${callers.size - 30} more)")
                }
            }

            // Callees: delegate to provider
            sb.appendLine("\nCallees (what this method calls):")
            val callees = provider.findCallees(psiMethod)
            if (callees.isEmpty()) {
                sb.appendLine("  (no callees found)")
            } else {
                callees.take(30).forEach { callee ->
                    sb.appendLine("  ${callee.name}  (${callee.filePath ?: ""}:${callee.line ?: 0})")
                }
                if (callees.size > 30) {
                    sb.appendLine("  ... (${callees.size - 30} more)")
                }
            }

            sb.toString()
        }.inSmartMode(project).executeSynchronously()

        val spilled = spillOrFormat(content, project)
        return ToolResult(
            content = spilled.preview,
            summary = "Call hierarchy for '$methodName'",
            tokenEstimate = TokenEstimator.estimate(spilled.preview),
            spillPath = spilled.spilledToFile,
        )
    }
}
