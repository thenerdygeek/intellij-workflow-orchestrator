package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
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

class TypeHierarchyTool(
    private val registry: LanguageProviderRegistry
) : AgentTool {
    override val name = "type_hierarchy"
    override val description = "Get the class hierarchy: supertypes (extends/implements) and subtypes (implementations/subclasses)."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "class_name" to ParameterProperty(type = "string", description = "Fully qualified or simple class name")
        ),
        required = listOf("class_name")
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.REVIEWER)

    override fun documentation(): ToolDocumentation = toolDoc("type_hierarchy") {
        summary {
            technical("PSI class-hierarchy walk for a class name (FQN or simple): collects supertypes by recursing `PsiClass.supers` (skipping `java.lang.Object` / `builtins.object`), then enumerates project-scoped subtypes via `ClassInheritorsSearch` (Java/Kotlin) or `helper.findInheritors` (Python). Subtype list is capped at 30 entries with an overflow note.")
            plain("A class's family tree. Give it a class name and it shows you the parents (what it extends/implements, walked all the way up) plus the descendants (every class that inherits from it inside this project). Same machinery as IntelliJ's Ctrl-H Type Hierarchy view, just driven by the LLM.")
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.READ_ONLY)
        counterfactual(
            "Without type_hierarchy, the LLM enumerates parents by reading the class file and grep-walking each `extends` / `implements` clause one supertype at a time, then opens each parent file to walk ITS clauses — error-prone for deep hierarchies, useless for transitive supertypes hidden behind 3+ levels. Subtypes are even worse: the only counterfactual is `search_code` for `extends ClassName` / `: ClassName` / `(ClassName)` (Python) which misses generic parameterizations, type aliases, fully-qualified-name uses, and (in Kotlin) delegation receivers. Net cost on a 4-level hierarchy: ~10–20x more tool calls and substantial false negatives on subtype enumeration."
        )
        llmMistake("Calls type_hierarchy on a method or field name — the underlying provider checks `element as? PsiClass` and returns null, surfacing as 'No class \\'<name>\\' found in project'. Type hierarchy is class-only; for method overrides use `find_implementations` with the method name, not type_hierarchy.")
        llmMistake("Expects Python multiple-inheritance to be linearized via MRO (C3) — the Python provider walks `helper.getSuperClasses()` in declaration order without applying MRO, so for `class C(A, B)` you get `[A, B]` plus their transitive parents in declaration-walk order, not the actual method-resolution order Python would use at runtime. Reasoning about which `__init__` will run requires the LLM to compute MRO itself.")
        llmMistake("Assumes 'no subtypes found' means the class is genuinely a leaf — subtype enumeration is bounded to the project's `GlobalSearchScope.projectScope` and to the first 30 inheritors, so library-only subclasses, framework-generated proxies (Spring CGLIB, Hibernate enhanced entities), and unindexed modules are silently invisible.")
        llmMistake("Calls during indexing — the entry-point `PsiToolUtils.isDumb(project)` guard returns a hard dumb-mode error rather than waiting; the inner `inSmartMode(project)` defers but only fires after the entry guard. The LLM should retry one tool-call cycle later, not immediately.")
        llmMistake("Confuses simple-name collisions silently — `findSymbol` returns the first match for `Result` even when six unrelated classes named `Result` exist. The output's first line `Type hierarchy for <fqn>:` reveals which one was picked, but the LLM occasionally proceeds without checking.")
        params {
            required("class_name", "string") {
                llmSeesIt("Fully qualified or simple class name")
                humanReadable("The class to root the hierarchy at. Either a fully-qualified name (`com.foo.Bar`) for unambiguous lookup, or a simple name (`Bar`) where the first matching class wins. Method names, field names, and free functions are not valid.")
                whenPresent("Each registered provider's `findSymbol` is tried in turn until one resolves the name to a `PsiClass` (Java/Kotlin) or Python class. Then `getTypeHierarchy(element)` is called: supertypes recurse through `PsiClass.supers` with cycle protection (visited-set on qualified name) and `java.lang.Object` / `builtins.object` filtered out; subtypes come from `ClassInheritorsSearch` (project scope, deep=true) or `helper.findInheritors`, capped at 30 with an overflow note when there are more.")
                constraint("must resolve to a class — methods, fields, and free functions return 'No class found in project'")
                constraint("simple-name lookups are first-match-wins; pass an FQN when multiple classes share a name")
                constraint("subtype enumeration is project-scoped — library and SDK subclasses are not listed")
                example("com.workflow.orchestrator.agent.tools.AgentTool")
                example("AgentTool")
                example("UserRepository")
            }
        }
        verdict {
            keep(
                "In Java/Kotlin codebases this is a STRONG keep: hierarchy queries are a daily navigation primitive (\"who implements this interface\", \"what does this base class extend\") and the counterfactual scales terribly — line-by-line `extends` / `implements` parsing across many files. Subtype enumeration in particular has no good textual fallback because parameterized types, FQN references, and Kotlin delegation hide subclass relationships from regex.",
                VerdictSeverity.STRONG,
            )
            drop(
                "In pure-Python projects the value drops to NORMAL, not STRONG: Python's dynamic class system (metaclasses, runtime `type()` construction, dataclass decorators that synthesize bases, `__init_subclass__` hooks) means the static PSI hierarchy is a lower bound, not the truth. The MRO-vs-walk-order asymmetry is also a real gotcha — the LLM may reason about method-resolution incorrectly. Still useful as a starting point, but the LLM should cross-check with `search_code` for runtime-injected subclasses.",
                VerdictSeverity.WEAK,
            )
        }
        related("find_implementations", Relationship.ALTERNATIVE, "Use instead when you only want subtypes/overriders and don't care about the supertype chain. find_implementations also handles methods (override search via `OverridingMethodsSearch`), which type_hierarchy rejects. type_hierarchy is the right call when you need both directions in one round-trip.")
        related("find_definition", Relationship.COMPLEMENT, "Start at the class definition with find_definition (jump to source), then walk the family tree with type_hierarchy. Useful when the LLM has a symbol name from a stack trace or comment and needs to understand both 'where is this' and 'how is it shaped'.")
        related("call_hierarchy", Relationship.SEE_ALSO, "Different graph: type_hierarchy walks class-inheritance edges (extends/implements), call_hierarchy walks method-call edges (caller→callee). Both are 'who is connected to whom' but the connectedness relation is different — pick by question being asked.")
        related("file_structure", Relationship.COMPOSE_WITH, "After type_hierarchy reveals a subtype list, file_structure on each subtype's file lists the methods/fields actually overridden — closes the loop on 'what does this concrete class actually look like'.")
        downside("Subtype list is capped at 30 entries (hardcoded `take(30)` in both providers) with an overflow note like '... (47 more)'. Useful for shallow trees, lossy for hub interfaces (`Runnable`, `Serializable`, custom event listeners) where the project may have hundreds of subtypes — the LLM cannot page through the rest.")
        downside("Subtype enumeration is project-scoped only — `GlobalSearchScope.projectScope(project)` excludes libraries, generated source roots not registered as such, and other modules in a multi-module workspace whose source isn't open. A class with zero in-project subtypes may still have many across the broader codebase.")
        downside("Java provider skips `java.lang.Object` and Python provider skips `builtins.object` from the supertypes list — useful 99% of the time, but if the LLM is reasoning about default `equals`/`hashCode` provenance or `__init__` MRO termination, it must remember the implicit root.")
        downside("Python's hierarchy is walked, not MRO-linearized: declaration-order traversal of `helper.getSuperClasses()` does not match Python's actual C3 method resolution order for diamond inheritance. Faithful to the source-code structure, misleading for runtime-behaviour reasoning.")
        downside("Densely-extended types (e.g. a popular event listener interface) trigger expensive `ClassInheritorsSearch` walks on every call. There is no caching across calls — repeated hierarchy queries on the same root re-do the index search each time.")
        downside("Output format mixes supertypes (recursive, full ancestor chain) with subtypes (one level deep, project-scoped) under the same heading style, with no depth indicator on supertypes. The LLM occasionally treats the supertype list as immediate-parents-only and miscounts the inheritance depth.")
        observation(
            "Provider resolution uses the correct `registry.allProviders().firstNotNullOfOrNull { ... }` pattern (lines 35–48 of TypeHierarchyTool.kt) — does NOT have the JAVA/kotlin hardcoded fallback bug found in `find_definition` (FindDefinitionTool.kt:113). Pure-Python projects work as intended for this tool. If `find_definition` is fixed by adopting this pattern, audit other PSI tools to ensure consistency."
        )
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        val className = params["class_name"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'class_name' parameter required", "Error: missing class_name", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

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
            // Try each provider until one finds the symbol
            val (provider, psiClass) = allProviders.firstNotNullOfOrNull { p ->
                p.findSymbol(project, className)?.let { p to it }
            } ?: return@nonBlocking "No class '$className' found in project"

            val result = provider.getTypeHierarchy(psiClass)
                ?: return@nonBlocking "No class '$className' found in project"

            val sb = StringBuilder()
            sb.appendLine("Type hierarchy for ${result.element}:")

            // Supertypes
            sb.appendLine("\nSupertypes:")
            if (result.supertypes.isEmpty()) {
                sb.appendLine("  (none)")
            } else {
                for (entry in result.supertypes) {
                    val file = entry.filePath ?: ""
                    sb.appendLine("  ${entry.qualifiedName}  ($file)")
                }
            }

            // Subtypes
            sb.appendLine("\nSubtypes/Implementations:")
            if (result.subtypes.isEmpty()) {
                sb.appendLine("  (none)")
            } else {
                result.subtypes.take(30).forEach { entry ->
                    val file = entry.filePath ?: ""
                    sb.appendLine("  ${entry.qualifiedName}  ($file)")
                }
                if (result.subtypes.size > 30) {
                    sb.appendLine("  ... (${result.subtypes.size - 30} more)")
                }
            }

            sb.toString()
        }.inSmartMode(project).executeSynchronously()

        val spilled = spillOrFormat(content, project)
        return ToolResult(
            content = spilled.preview,
            summary = "Type hierarchy for '$className'",
            tokenEstimate = TokenEstimator.estimate(spilled.preview),
            spillPath = spilled.spilledToFile,
        )
    }
}
