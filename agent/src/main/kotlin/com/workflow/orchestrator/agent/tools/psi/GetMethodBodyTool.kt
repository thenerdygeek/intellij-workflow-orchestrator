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

class GetMethodBodyTool(
    private val registry: LanguageProviderRegistry
) : AgentTool {
    override val name = "get_method_body"
    override val description =
        "Get the full source code of a specific method including annotations, signature, and body. " +
        "More targeted than read_file — no need to know line numbers."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "method" to ParameterProperty(type = "string", description = "Method name to retrieve"),
            "class_name" to ParameterProperty(type = "string", description = "Class containing the method"),
            "context_lines" to ParameterProperty(
                type = "integer",
                description = "Lines of context before/after the method (default: 0, max: 5)"
            )
        ),
        required = listOf("method", "class_name")
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.CODER, WorkerType.REVIEWER)

    override fun documentation(): ToolDocumentation = toolDoc("get_method_body") {
        summary {
            technical(
                "PSI-backed method-body extractor. Resolves the class via `LanguageIntelligenceProvider.findSymbol`, " +
                "casts to `PsiClass`, then runs `findMethodsByName(deep=false)` with an inherited fallback " +
                "(`deep=true`). Delegates body extraction to `provider.getBody(method, contextLines)`, which " +
                "returns the raw PSI text range plus `contextLines` (0-5) of surrounding source. Caps at 3 " +
                "overloads; emits `// Source unavailable` on a null `getBody` result with NO secondary fallback. " +
                "Returns `// filePath` header + method source per overload, separated by `---`."
            )
            plain(
                "The agent's 'show me the code for this exact method' button. Instead of opening an entire file " +
                "and hunting for a method by scrolling, the LLM names a class and a method and gets back just " +
                "that method's source — annotations, signature, and body included — with its file path. Like " +
                "right-clicking a method in IntelliJ and choosing 'Jump to Source', but the result is text " +
                "the agent can reason about directly."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.READ_ONLY)
        counterfactual(
            "Without get_method_body the LLM must first guess (or discover via file_structure or find_definition) " +
            "which file a method lives in, then call read_file on that whole file, then mentally extract the " +
            "relevant lines. For large classes (500-2000+ lines) this is a significant context-budget waste: " +
            "the entire file occupies tokens even though only 10-40 lines are relevant. The LLM also tends to " +
            "call read_file with a guessed line range that clips the annotation block or the closing brace, " +
            "forcing a second read. get_method_body eliminates the file-size tax entirely and gives the exact " +
            "source text in a single call, including overloads the LLM might not know exist."
        )
        llmMistake(
            "Passes a fully-qualified class name with package path (e.g. `com.foo.bar.MyService`) when the " +
            "provider's `findSymbol` lookup uses `PsiShortNamesCache` which expects the simple class name " +
            "('MyService') or a `#`-separated symbol. The FQN may resolve correctly via some providers but " +
            "will fail on others; prefer the simple class name unless disambiguation is needed."
        )
        llmMistake(
            "Expects get_method_body to work on top-level Kotlin functions or Kotlin extension functions — " +
            "these are not `PsiMethod` members, so `psiClass.findMethodsByName` won't find them. The tool " +
            "will return 'Method not found' with a list of methods that are actually available as `PsiMethod` " +
            "elements, causing the LLM to believe the function doesn't exist. Use read_file with a targeted " +
            "offset or search_code to locate top-level and extension functions."
        )
        llmMistake(
            "Assumes an empty 'Available methods' list in the error message means the class has no methods. " +
            "The list is capped at 20 via `psiClass.methods.take(20)` and shows only direct PsiMethod members; " +
            "inherited methods, property accessors, and synthetic methods generated by the compiler are not " +
            "listed. A missing method name here doesn't mean the class is empty."
        )
        llmMistake(
            "Provides `context_lines` larger than 5 — the value is silently clamped to 5 by " +
            "`coerceIn(0, 5)`. The LLM sometimes passes 10 or 20 expecting full surrounding context; " +
            "it gets at most 5 lines before and after the method boundary with no warning about the clamp."
        )
        llmMistake(
            "Treats a `// Source unavailable for <method> in <file>` line in the output as a tool failure " +
            "rather than a provider limitation. This stub is silently emitted when `provider.getBody()` " +
            "returns null (e.g. for compiled-only classes or decompiled sources where PSI text range is " +
            "unavailable). The tool still returns HTTP 200 / non-error ToolResult; the LLM must detect the " +
            "stub comment pattern and fall back to read_file on the containing file."
        )
        llmMistake(
            "Calls get_method_body during IDE indexing (dumb mode). The `PsiToolUtils.isDumb(project)` guard " +
            "at the entry point returns a hard error immediately — the `inSmartMode(project)` deferral on the " +
            "inner ReadAction.nonBlocking fires only after the guard. The LLM should wait for indexing to " +
            "finish (retry with backoff) rather than retrying immediately in a tight loop."
        )
        params {
            required("method", "string") {
                llmSeesIt("Method name to retrieve")
                humanReadable(
                    "The name of the method to extract. Matches against `psiClass.findMethodsByName()` — " +
                    "case-sensitive, simple name only (no parameter types or return type). Overloads are " +
                    "collected together and shown up to 3 with `---` separators."
                )
                whenPresent(
                    "Passed to `psiClass.findMethodsByName(methodName, false)` first (direct methods). " +
                    "If no match, falls back to `findMethodsByName(methodName, true)` (inherited). " +
                    "Overloads: up to 3 overloads displayed; if more exist, a `... (N more overload(s) not shown)` " +
                    "footer is appended."
                )
                constraint("case-sensitive — 'execute' and 'Execute' are distinct lookups")
                constraint("simple method name only — do not include parameter types, return type, or parentheses")
                example("execute")
                example("getBody")
                example("findMethodsByName")
                example("onSuccess")
            }
            required("class_name", "string") {
                llmSeesIt("Class containing the method")
                humanReadable(
                    "Simple or fully-qualified name of the class that declares or inherits the method. " +
                    "The tool resolves this via `LanguageIntelligenceProvider.findSymbol`, iterating all " +
                    "registered providers (Java/Kotlin, Python) until one resolves the class. Simple names " +
                    "('MyService') are preferred; FQNs work on providers that support them."
                )
                whenPresent(
                    "Passed as the sole symbol to `registry.allProviders().firstNotNullOfOrNull { p -> " +
                    "p.findSymbol(project, className) }`. The first provider that returns a non-null element " +
                    "wins and drives the rest of the lookup. The element is cast to `PsiClass`; if the cast " +
                    "fails, an error is returned: `'<class_name>' was found but is not a class.`"
                )
                constraint("must resolve to a PsiClass — resolving to a method, field, or package returns an error")
                example("GetMethodBodyTool")
                example("AgentTool")
                example("com.workflow.orchestrator.agent.tools.AgentTool")
                example("FindImplementationsTool")
            }
            optional("context_lines", "integer") {
                llmSeesIt("Lines of context before/after the method (default: 0, max: 5)")
                humanReadable(
                    "Extra lines of surrounding source to include above and below the method boundary. " +
                    "Useful when the method is a one-liner and the adjacent `@Bean` / `@Override` annotation " +
                    "or the enclosing block matters. Silently clamped to [0, 5] — requesting 10 yields 5."
                )
                whenPresent(
                    "Passed directly to `provider.getBody(method, contextLines)`. Each provider's implementation " +
                    "of `getBody` reads `contextLines` extra lines from the containing PsiFile's text buffer " +
                    "before the method's start offset and after its end offset."
                )
                whenAbsent("Defaults to 0 — only the method text range itself is returned, no surrounding lines.")
                constraint("clamped to [0, 5] — values outside this range are coerced silently")
                example("0")
                example("2")
                example("5")
            }
        }
        verdict {
            keep(
                "Strong keep for Java/Kotlin codebases. The alternative — read_file on the whole containing " +
                "file — wastes context budget proportionally to class size. For a 1500-line service class with " +
                "a 20-line method of interest, get_method_body uses ~40 tokens; read_file uses ~2000. Beyond " +
                "the token cost, read_file requires the LLM to already know the file path and either guess a " +
                "line range or ingest the full file. get_method_body's PSI resolution handles the 'where does " +
                "this method live' question automatically. Python value is lower because `provider.getBody` may " +
                "return null for some Python elements, emitting the silent stub comment — but for Java/Kotlin " +
                "this earns its deferred schema slot decisively.",
                VerdictSeverity.STRONG,
            )
        }
        related("find_definition", Relationship.COMPOSE_WITH,
            "Canonical upstream step: use find_definition first to confirm the method exists, see its " +
            "declared signature, and discover its file location. Then use get_method_body to retrieve the " +
            "full implementation. find_definition → get_method_body is the standard 'what's the contract, " +
            "what's the implementation' pipeline."
        )
        related("file_structure", Relationship.COMPLEMENT,
            "Use file_structure to enumerate all methods in a class when you don't know the method name, " +
            "then call get_method_body once you've identified the target. file_structure gives the outline; " +
            "get_method_body fills in one method's body at a time."
        )
        related("read_file", Relationship.ALTERNATIVE,
            "Use read_file when: (1) the method is a top-level Kotlin function or extension function " +
            "(get_method_body won't find it); (2) provider.getBody returns null and the stub comment appears; " +
            "(3) you want a large contiguous slice of a file including multiple adjacent methods; " +
            "(4) the class is in a library JAR without sources. read_file is the correct fallback whenever " +
            "get_method_body emits '// Source unavailable'."
        )
        downside(
            "Silent stub on null getBody: when `provider.getBody(method, contextLines)` returns null " +
            "(line 117 of GetMethodBodyTool.kt), the tool emits `// Source unavailable for <method> in <file>` " +
            "and continues to the next overload WITHOUT attempting any fallback — no raw VFS read, no " +
            "decompiled source attempt. The ToolResult is non-error, so the LLM must actively pattern-match " +
            "the stub comment to know it got no useful source. This is the primary reliability gap for " +
            "compiled-only classes and decompiled library methods."
        )
        downside(
            "3-overload cap with no selection criteria: `methods.take(3)` picks the first three overloads " +
            "in PSI iteration order, which is not deterministic and may not include the overload the LLM " +
            "actually wants. For a heavily-overloaded method (e.g. `execute(Runnable)`, `execute(Callable)`, " +
            "`execute(String, Object...)`, `execute(String, Class<T>)`, ...) only 3 appear with a " +
            "`... (N more overload(s) not shown)` footer but no way to select which 3."
        )
        downside(
            "class_name resolves to inherited lookup only as a fallback: if a method is not found in direct " +
            "methods (`deep=false`), the tool repeats with `deep=true` and emits a `Note: '<method>' is " +
            "inherited from '<declaringClass>'` hint. The LLM must read this hint to understand the source " +
            "of truth is a different class — the body shown is from the declaring class's file, not the one " +
            "the LLM originally named."
        )
        downside(
            "No scope control — library/JAR methods without attached sources will resolve the PsiClass " +
            "correctly but `provider.getBody` typically returns null because the PSI text range is " +
            "unavailable for class files. There is no parameter to widen scope to decompiled sources; " +
            "the LLM must fall back to read_file on the decompiled `.class` or look for sources JAR."
        )
        downside(
            "context_lines is capped at 5 with no warning — a LLM that passes 10 to get more context " +
            "silently receives only 5 lines and may not realise it's missing context it asked for."
        )
        observation(
            "VERIFIED: get_method_body does NOT have the hardcoded provider-fallback bug that affects " +
            "find_definition (line 113) and find_references (line 227). Lines 55-69 of GetMethodBodyTool.kt " +
            "use the correct pattern: `registry.allProviders().firstNotNullOfOrNull { p -> " +
            "p.findSymbol(project, className)?.let { p to it } }`. The resolved `provider` variable is then " +
            "consistently reused at line 116 for the `getBody` call — no silent fallback to a hardcoded " +
            "JavaKotlinProvider. This means get_method_body works correctly in pure-Python projects where " +
            "only PythonProvider is registered. The canonical correct pattern matches find_implementations."
        )
        observation(
            "CONFIRMED FALLBACK BUG: At line 117, when `provider.getBody(method, contextLines)` returns " +
            "null, the tool emits a stub comment and calls `return@forEachIndexed` — no secondary strategy " +
            "is attempted (no raw VFS bytes read, no decompiled source, no alternative provider tried). " +
            "This is a silent degradation: the ToolResult has `isError=false`, the content contains a " +
            "`// Source unavailable` comment, and there is nothing in the tool description to warn the LLM " +
            "to check for this pattern. A future improvement would be to (a) emit `isError=true` when ALL " +
            "overloads produce null bodies, or (b) fall back to reading the raw VFS document text at the " +
            "method's text range via `PsiDocumentManager`."
        )
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        val methodName = params["method"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Error: 'method' parameter required",
                "Error: missing method",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        val className = params["class_name"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Error: 'class_name' parameter required",
                "Error: missing class_name",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        val contextLines = (params["context_lines"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0).coerceIn(0, 5)

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
            // Find the class via provider — try each provider until one finds the symbol
            val (provider, psiElement) = allProviders.firstNotNullOfOrNull { p ->
                p.findSymbol(project, className)?.let { p to it }
            } ?: return@nonBlocking "Error: Class '$className' not found in project. " +
                        "Check the class name spelling or provide the fully qualified name."

            val psiClass = psiElement as? com.intellij.psi.PsiClass
                ?: return@nonBlocking "Error: '$className' was found but is not a class."

            // Try direct (non-inherited) methods first
            var methods = psiClass.findMethodsByName(methodName, false).toList()

            // If not found in own class, check inherited
            val foundInherited = if (methods.isEmpty()) {
                val inheritedMethods = psiClass.findMethodsByName(methodName, true).toList()
                if (inheritedMethods.isNotEmpty()) {
                    methods = inheritedMethods
                    true
                } else {
                    false
                }
            } else {
                false
            }

            if (methods.isEmpty()) {
                val available = psiClass.methods.take(20).joinToString(", ") { it.name }
                val availableMsg = if (available.isNotEmpty()) "\nAvailable methods: $available" else ""
                return@nonBlocking "Error: Method '$methodName' not found in class '$className'.$availableMsg"
            }

            val inheritedHint = if (foundInherited) {
                val declaringClass = methods.first().containingClass?.qualifiedName ?: methods.first().containingClass?.name
                "\nNote: '$methodName' is inherited from '$declaringClass', not declared directly in '$className'.\n"
            } else {
                ""
            }

            val overloadsToShow = methods.take(3)
            val hiddenCount = methods.size - overloadsToShow.size

            val sb = StringBuilder()
            if (inheritedHint.isNotEmpty()) sb.append(inheritedHint)

            overloadsToShow.forEachIndexed { index, method ->
                if (overloadsToShow.size > 1) {
                    sb.appendLine("(overload #${index + 1})")
                }

                // Delegate body extraction to the provider
                val bodyResult = provider.getBody(method, contextLines)
                if (bodyResult == null) {
                    val filePath = method.containingFile?.virtualFile?.path
                        ?.let { PsiToolUtils.relativePath(project, it) } ?: "unknown"
                    sb.appendLine("// Source unavailable for ${method.name} in $filePath")
                    if (index < overloadsToShow.size - 1) sb.appendLine("---")
                    return@forEachIndexed
                }

                val filePath = method.containingFile?.virtualFile?.path
                    ?.let { PsiToolUtils.relativePath(project, it) } ?: "unknown"
                sb.appendLine("// $filePath")
                sb.appendLine(bodyResult.source)

                if (index < overloadsToShow.size - 1) {
                    sb.appendLine("---")
                }
            }

            if (hiddenCount > 0) {
                sb.appendLine("... ($hiddenCount more overload(s) not shown)")
            }

            sb.toString().trimEnd()
        }.inSmartMode(project).executeSynchronously()

        return ToolResult(
            content = content,
            summary = "Method body of '$className#$methodName'",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }
}
