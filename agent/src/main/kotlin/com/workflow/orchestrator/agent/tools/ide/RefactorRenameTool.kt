package com.workflow.orchestrator.agent.tools.ide

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.refactoring.rename.RenameProcessor
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.ide.LanguageProviderRegistry
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.builtin.PathValidator
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
import com.workflow.orchestrator.agent.tools.psi.PsiToolUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

class RefactorRenameTool(
    private val registry: LanguageProviderRegistry
) : AgentTool {
    override val name = "refactor_rename"
    override val description = "Safely rename a class, method, field, or variable across the entire project. Updates ALL references, imports, and usages. Much safer than text replacement with edit_file."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "symbol" to ParameterProperty(type = "string", description = "Symbol to rename (class name, method name, or ClassName.methodName)"),
            "new_name" to ParameterProperty(type = "string", description = "New name for the symbol"),
            "file" to ParameterProperty(type = "string", description = "Optional: file path for disambiguation if multiple symbols share the name"),
            "description" to ParameterProperty(type = "string", description = "Brief description of what this action does and why (shown to user in approval dialog)"),
            "confirm_cross_module" to ParameterProperty(
                type = "boolean",
                description = "Required when the rename spans >1 project module. First call without this flag returns a preview of affected modules; set to true on a follow-up call to apply. Library renames are ALWAYS blocked and cannot be bypassed with this flag."
            )
        ),
        required = listOf("symbol", "new_name", "description")
    )
    override val allowedWorkers = setOf(WorkerType.CODER)

    override fun documentation(): ToolDocumentation = toolDoc("refactor_rename") {
        summary {
            technical(
                "Invokes IntelliJ's RenameProcessor to atomically rename a class, method, field, or variable " +
                "and update every reference, import, and usage across the entire project — all inside a " +
                "WriteCommandAction on the EDT. A 3-phase pipeline: (1) ReadAction.nonBlocking finds the " +
                "PsiElement; (2) ReadAction.nonBlocking discovers all usages and classifies each as library, " +
                "test, or production code per module; (3) WriteCommandAction performs the refactoring. " +
                "A hard library-block and an optional cross-module confirmation gate (SummaryResult) protect " +
                "against unsafe renames before any mutation occurs."
            )
            plain(
                "Like using IntelliJ's Refactor → Rename menu (Shift+F6) — type a new name once and the IDE " +
                "updates every place in the codebase that referred to the old name in one shot: class files, " +
                "import statements, XML configs, test files, everything. The agent can trigger exactly this " +
                "action so it never misses a reference the way a find-and-replace would."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.FILE_WRITE)
        counterfactual(
            "Without refactor_rename the LLM must search for every reference via search_code or find_references, " +
            "then edit each file individually with edit_file. This is fragile: it misses references in " +
            "generated code, XML descriptors, and string literals that PSI-rename catches; it silently " +
            "skips import alias forms; it gets case-sensitive variants wrong; and a partial rename leaves " +
            "the codebase in a broken state mid-session. In a medium project a single class rename might " +
            "require 10-30 sequential edit_file calls, each approval-gated, versus one refactor_rename call."
        )
        llmMistake(
            "Passes a bare local variable name (e.g. `symbol='index'`) instead of a qualified form " +
            "(`'MyClass.index'` or providing `file` for disambiguation). Multiple symbols share common " +
            "names like `result`, `item`, `entry` — without disambiguation the tool resolves the first " +
            "hit from the provider registry, which is rarely the intended one."
        )
        llmMistake(
            "Attempts to rename a symbol that lives in an external library (e.g. a standard library class " +
            "like `ArrayList` or a third-party annotation like `@JsonProperty`). The tool blocks this " +
            "unconditionally and returns an error; the LLM should recognise the LibraryBlocked error and " +
            "not retry with `confirm_cross_module=true` — that flag cannot bypass the library block."
        )
        llmMistake(
            "Provides `confirm_cross_module=true` on the first call without waiting for the preview. " +
            "The intended workflow is: first call returns a CrossModulePreview listing which modules will " +
            "be touched; the LLM (or user) reviews the scope; re-invoke with `confirm_cross_module=true` " +
            "to proceed. Skipping the preview makes cross-module renames opaque."
        )
        llmMistake(
            "Calls refactor_rename while DumbService.isDumb is true (during indexing). The tool returns " +
            "a dumb-mode error; the LLM should wait for indexing to finish rather than retrying " +
            "immediately — typically 30 seconds to 2 minutes after project open or Gradle/Maven sync."
        )
        llmMistake(
            "Uses the `file` parameter to rename a symbol that appears only once globally, then complains " +
            "when the tool resolves a different occurrence. The `file` parameter is for disambiguation " +
            "when multiple symbols share the same name across files — it is not a scope limiter. The " +
            "rename still propagates to ALL references project-wide regardless of the `file` value."
        )
        llmMistake(
            "Treats a successful single-module rename (no `confirm_cross_module` needed) as confirmation " +
            "that the rename was cross-module safe. If the project later has cross-module consumers added, " +
            "a future rename of the same symbol may need confirmation. The tool's safety analysis is " +
            "computed fresh on each invocation from the current index state."
        )
        params {
            required("symbol", "string") {
                llmSeesIt("Symbol to rename (class name, method name, or ClassName.methodName)")
                humanReadable(
                    "The name of the symbol to rename. Use a bare class name for top-level types, " +
                    "`ClassName.methodName` for members, or rely on the `file` param for disambiguation " +
                    "when the same name exists in multiple files."
                )
                whenPresent(
                    "The tool walks the LanguageProviderRegistry and PsiToolUtils to resolve the PsiElement. " +
                    "If a dot is present the tool splits on the first dot and tries methods then fields on " +
                    "the named class. If no provider finds the symbol the tool returns a 'Cannot find symbol' " +
                    "error with suggestions to use qualified form or the `file` param."
                )
                constraint("Must be a project-defined symbol — library symbols are blocked at execution time")
                constraint("Dot notation `ClassName.memberName` assumes exactly one dot — nested classes must be renamed one level at a time")
                example("UserService")
                example("UserService.findById")
                example("MAX_RETRIES")
            }
            required("new_name", "string") {
                llmSeesIt("New name for the symbol")
                humanReadable(
                    "The replacement identifier. Must be a valid identifier in the target language — " +
                    "the tool does not validate identifier syntax before calling RenameProcessor; " +
                    "IntelliJ's processor will surface a 'not a valid identifier' error at runtime."
                )
                whenPresent("RenameProcessor uses this as the target name for all reference updates.")
                constraint("Must be a valid identifier for the language — no spaces, no reserved words, no dots")
                example("findUserById")
                example("MAX_RETRY_COUNT")
                example("AccountService")
            }
            required("description", "string") {
                llmSeesIt("Brief description of what this action does and why (shown to user in approval dialog)")
                humanReadable(
                    "A short human-readable explanation shown in the IDE's approval dialog. Tells the user " +
                    "why the agent is renaming this symbol, not just what it is doing."
                )
                whenPresent("Shown verbatim in the user-facing approval gate before the mutation runs.")
                constraint("Should be 1-2 sentences; purely informational, no effect on execution")
                example("Renaming UserService to AccountService to match the new domain terminology.")
                example("Renaming MAX_RETRIES to MAX_RETRY_COUNT for clarity.")
            }
            optional("file", "string") {
                llmSeesIt("Optional: file path for disambiguation if multiple symbols share the name")
                humanReadable(
                    "Absolute or project-relative path to the file where the target symbol is defined. " +
                    "Provides disambiguation when the same name exists in multiple files. The rename still " +
                    "propagates project-wide — this only controls which occurrence is selected as the anchor."
                )
                whenPresent(
                    "Tool searches within this file first using a PsiRecursiveElementWalkingVisitor, " +
                    "returning the first PsiNamedElement whose name matches `symbol`. Falls through to " +
                    "the global registry search if the symbol is not found in the file."
                )
                whenAbsent(
                    "Global resolution is used: LanguageProviderRegistry → PsiToolUtils.findClass. " +
                    "If multiple symbols have the same name in different files, an arbitrary one is chosen — " +
                    "supply `file` to be precise."
                )
                example("src/main/kotlin/com/example/user/UserService.kt")
                example("agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt")
            }
            optional("confirm_cross_module", "boolean") {
                llmSeesIt(
                    "Required when the rename spans >1 project module. First call without this flag returns " +
                    "a preview of affected modules; set to true on a follow-up call to apply. Library renames " +
                    "are ALWAYS blocked and cannot be bypassed with this flag."
                )
                humanReadable(
                    "Two-step confirmation gate for renames that touch more than one Gradle/Maven module. " +
                    "Omit (or pass false) on the first call to get a preview of which modules and how many " +
                    "test vs production usages will be affected. Re-invoke with `true` after reviewing the " +
                    "preview to actually apply the rename. Single-module renames never need this flag."
                )
                whenPresent(
                    "If `true` AND the prior analysis showed a cross-module rename, the tool skips the " +
                    "preview and proceeds to Phase 3 (WriteCommandAction). If the rename turns out to be " +
                    "single-module, the flag is ignored."
                )
                whenAbsent(
                    "Defaults to `false`. Cross-module renames return a non-error preview result listing " +
                    "affected modules; single-module renames proceed immediately."
                )
                enumValue("true", "false")
            }
        }
        verdict {
            keep(
                "Atomic multi-file rename is irreplaceable: no composition of search_code + edit_file + " +
                "optimize_imports can reproduce PSI-accurate reference discovery — especially for import " +
                "alias forms, XML descriptors, generated code, and language-specific reference patterns " +
                "like Kotlin property accessors. The cost is minimal (one deferred tool, FILE_WRITE blast " +
                "radius confined to touched files). Dropping it would make class/method renames in " +
                "medium+ codebases unreliable and session-long. CODER workers reach for this regularly " +
                "during any non-trivial refactoring task.",
                VerdictSeverity.STRONG,
            )
        }
        related(
            "find_references",
            Relationship.COMPLEMENT,
            "Use find_references before refactor_rename to preview exactly which locations will be updated — " +
            "especially useful for widely-used symbols where the cross-module confirmation preview alone " +
            "may not show enough detail."
        )
        related(
            "edit_file",
            Relationship.ALTERNATIVE,
            "Use instead only for rename-in-one-file when PSI is unavailable (dumb mode, non-indexed file). " +
            "edit_file misses cross-file references and import statements — a last resort, not an equivalent."
        )
        related(
            "search_code",
            Relationship.ALTERNATIVE,
            "Use to audit references after a rename (check that no plain-string occurrences were missed by " +
            "PSI, e.g. in string literals or comments). Also useful to count likely impact before deciding " +
            "whether a rename is safe."
        )
        related(
            "format_code",
            Relationship.COMPLEMENT,
            "After a rename that touches many files the affected files may have minor style drift. " +
            "Run format_code on the key files if the user's code style requires it."
        )
        related(
            "optimize_imports",
            Relationship.COMPLEMENT,
            "RenameProcessor updates import statements but does not remove any newly-unused imports that " +
            "were brought in by the old name. Run optimize_imports on touched files after a rename."
        )
        observation(
            "The `description` parameter is required in the schema but is only used for the approval dialog " +
            "shown to the user — it has no effect on the rename operation itself. If the approval gate is " +
            "ever replaced or removed, this param becomes dead weight."
        )
        observation(
            "refactor_rename and find_references both run RenameProcessor.findUsages internally. " +
            "A future version could expose the usage list as part of the refactor_rename preview result, " +
            "eliminating the need for a separate find_references call before large renames."
        )
        mergeOpportunity(
            "refactor_rename, format_code, and optimize_imports all follow the same scaffold: " +
            "DumbService check → ReadAction.nonBlocking/PSI resolve → withContext(EDT) → WriteCommandAction → " +
            "ToolResult. A future `refactor` meta-tool with action ∈ {rename, format, optimize_imports, " +
            "extract_method} would eliminate this duplication and let the LLM chain rename + optimize_imports " +
            "in a single tool call."
        )
        downside(
            "Depends on PSI index accuracy. If the index is stale (e.g., immediately after a large Gradle " +
            "sync that hasn't re-indexed yet), `findUsages()` may return an incomplete list and some " +
            "references are silently missed. No warning is emitted — the tool reports 'N usages updated' " +
            "based on what PSI returned, which may be less than the true count."
        )
        downside(
            "Python renames via `LanguageProviderRegistry` + `PythonProvider` are best-effort. Python's " +
            "dynamic dispatch means PSI cannot statically resolve all call sites (e.g., duck-typed " +
            "callers, `getattr`-based access, monkey-patched references). Some Python references will " +
            "be missed even if the tool reports success."
        )
        downside(
            "Library symbols are unconditionally blocked — even if the user owns the library source and " +
            "has it checked out in the same project. The block key is `ProjectFileIndex.isInLibrary`, " +
            "which fires on any jar-resident class. Users who maintain internal libraries inside the " +
            "monorepo must ensure the library module is added as a content root, not as a library dependency."
        )
        downside(
            "The cross-module confirmation gate adds a mandatory round-trip for any rename that spans " +
            "more than one module. In projects with many modules even a single-class rename may trigger " +
            "the preview, requiring the LLM to re-invoke and burning an extra iteration and approval prompt."
        )
        downside(
            "RenameProcessor operates at PsiElement identity, not textual identity. If a symbol " +
            "appears both as a PsiNamedElement and as a string literal (e.g. `@RequestMapping(\"/userService\")`), " +
            "the string literal is NOT updated. search_code for the old name string should follow every " +
            "rename to catch these non-PSI references."
        )
        downside(
            "writeCommandAction runs on the EDT. For projects with many thousands of references the " +
            "EDT can be blocked for several seconds, making the IDE unresponsive during the rename. " +
            "The user sees a freeze; there is no progress indicator from the agent."
        )
        flowchart("""
            flowchart TD
                A[LLM calls refactor_rename] --> B{DumbService.isDumb?}
                B -- yes --> X1[Return dumb-mode error]
                B -- no --> C[ReadAction.nonBlocking: findElement]
                C --> D{Element found?}
                D -- no --> X2[Return Cannot find symbol error]
                D -- yes --> E[ReadAction.nonBlocking: RenameProcessor.findUsages + classifyUsage]
                E --> F{summarizeForApproval}
                F -- LibraryBlocked --> X3[Return library-block error UNCONDITIONAL]
                F -- CrossModulePreview AND confirm_cross_module=false --> G[Return non-error preview listing modules]
                G --> H[LLM re-invokes with confirm_cross_module=true]
                H --> E
                F -- CrossModulePreview AND confirm_cross_module=true --> I
                F -- NoUsages OR SingleModuleOK --> I[withContext EDT + WriteCommandAction]
                I --> J[RenameProcessor.performRefactoring usages]
                J --> K[Return Renamed oldName → newName N usages M modules]
        """)
    }

    /**
     * ## F4 refactor safety (Task 5.3)
     *
     * Two guards wrap the existing `RenameProcessor` flow:
     *
     * 1. **Hard library block — UNCONDITIONAL.** If any usage is inside a jar /
     *    external library (detected via [ProjectFileIndex.isInLibrary] +
     *    [ProjectFileIndex.isInLibraryClasses]), the tool returns an error
     *    before touching the refactoring. `confirm_cross_module=true` CANNOT
     *    bypass this — project code would reference a name that no longer
     *    exists in the library jar's bytecode.
     *
     * 2. **Cross-module confirmation gate.** If usages span >1 module, the tool
     *    returns a non-error PREVIEW (not `isError=true`). The LLM must
     *    re-invoke with `confirm_cross_module=true` to proceed.
     *
     * Single-module renames (the common case) still proceed without
     * confirmation — preserves the existing UX.
     *
     * Classification is delegated to the pure [summarizeForApproval] helper in
     * [RenameSafetyAnalyzer], which is unit-testable without an IntelliJ
     * fixture.
     */
    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val symbol = params["symbol"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'symbol' required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val newName = params["new_name"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'new_name' required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val rawFile = params["file"]?.jsonPrimitive?.content
        val confirmCrossModule = params["confirm_cross_module"]?.jsonPrimitive?.booleanOrNull ?: false

        if (DumbService.isDumb(project)) {
            return PsiToolUtils.dumbModeError()
        }

        // Resolve the PsiElement to rename (requires read lock for PSI access)
        val element = ReadAction.nonBlocking<com.intellij.psi.PsiElement?> {
            findElement(project, symbol, rawFile)
        }.inSmartMode(project).executeSynchronously()
            ?: return ToolResult(
                "Cannot find symbol '$symbol'. Provide a class name, ClassName.methodName, or specify 'file' for disambiguation.",
                "Not found",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val oldName = (element as? PsiNamedElement)?.name ?: symbol

        return try {
            // Phase 1: Find + classify usages (read-only, off-EDT).
            // We classify inside the same ReadAction so PSI/VFS state is
            // stable and we don't race against indexing.
            data class FindResult(
                val usages: Array<com.intellij.usageView.UsageInfo>,
                val classifications: List<UsageClassification>,
            )
            val findResult = ReadAction.nonBlocking<FindResult> {
                val processor = RenameProcessor(project, element, newName, false, false)
                processor.setPreviewUsages(false)
                val usages = processor.findUsages()
                val classifications = usages.mapNotNull { classifyUsage(it, project) }
                FindResult(usages, classifications)
            }.inSmartMode(project).executeSynchronously()

            val usages = findResult.usages
            val classifications = findResult.classifications

            // Phase 2: Safety analysis via the pure helper.
            when (val summary = summarizeForApproval(classifications)) {
                is SummaryResult.LibraryBlocked -> {
                    // Hard block — UNCONDITIONAL. The cross-module confirm
                    // flag is deliberately NOT consulted here: rename of
                    // library code is never allowed because we cannot modify
                    // jar bytecode. See RenameSafetyAnalyzer kdoc for rationale.
                    val fileList = summary.libraryFiles.joinToString(", ")
                    return ToolResult(
                        "Rename of '$oldName' blocked: ${summary.libraryFiles.size} usage(s) " +
                            "are in external library code (cannot modify jar contents). " +
                            "Library files: $fileList",
                        "Library rename blocked",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )
                }

                is SummaryResult.CrossModulePreview -> {
                    if (!confirmCrossModule) {
                        val moduleLines = summary.moduleBreakdown.entries.joinToString("\n") { (mod, counts) ->
                            "  - ${counts.total} usages in module :$mod (${counts.testCount} test, ${counts.prodCount} production)"
                        }
                        return ToolResult(
                            "PREVIEW (no changes made yet): rename of '$oldName' → '$newName' will affect:\n$moduleLines\n\n" +
                                "This spans multiple modules. To proceed, re-invoke refactor_rename with the SAME arguments plus confirm_cross_module=true.",
                            "Cross-module preview — confirmation required",
                            10
                            // NOT isError — this is a successful preview, not
                            // a failure. The LLM is expected to re-invoke with
                            // confirm_cross_module=true.
                        )
                    }
                    // Fall through to perform the rename.
                }

                is SummaryResult.NoUsages, is SummaryResult.SingleModuleOK -> {
                    // Proceed without confirmation — common case.
                }
            }

            // Phase 3: Perform refactoring (write action on EDT).
            withContext(Dispatchers.EDT) {
                WriteCommandAction.runWriteCommandAction(project, "Agent: Rename $oldName → $newName", null, {
                    val processor = RenameProcessor(project, element, newName, false, false)
                    processor.setPreviewUsages(false)
                    processor.performRefactoring(usages)
                })
            }

            // Module count — distinct non-null modules touched. Library
            // count is zero here (we'd have returned above) so modules are
            // all project modules. Include unknown-bucket in the count so
            // the LLM sees the full scope.
            val moduleCount = classifications.map { it.module }.distinct().size.coerceAtLeast(1)

            ToolResult(
                "Renamed '$oldName' → '$newName'. ${usages.size} usages updated across $moduleCount module(s).",
                "Renamed $oldName → $newName",
                10
            )
        } catch (e: Exception) {
            ToolResult("Error during rename: ${e.message}", "Rename error", 5, isError = true)
        }
    }

    private fun findElement(project: Project, symbol: String, rawFile: String?): com.intellij.psi.PsiElement? {
        // If a file is provided, search within that file first for disambiguation
        if (rawFile != null) {
            val (path, pathError) = PathValidator.resolveAndValidate(rawFile, project.basePath)
            if (pathError == null && path != null) {
                val vf = LocalFileSystem.getInstance().findFileByIoFile(java.io.File(path))
                if (vf != null) {
                    val psiFile = PsiManager.getInstance(project).findFile(vf)
                    if (psiFile != null) {
                        findSymbolInFile(psiFile, symbol)?.let { return it }
                    }
                }
            }
        }

        // If symbol contains a dot, try ClassName.memberName via Java/Kotlin PSI
        if ('.' in symbol) {
            val parts = symbol.split('.')
            if (parts.size == 2) {
                val className = parts[0]
                val memberName = parts[1]
                val psiClass = PsiToolUtils.findClass(project, className)
                if (psiClass != null) {
                    // Try methods first, then fields
                    psiClass.findMethodsByName(memberName, false).firstOrNull()?.let { return it }
                    psiClass.findFieldByName(memberName, false)?.let { return it }
                }
            }
        }

        // Try all registered providers (works for Java, Kotlin, Python, etc.)
        registry.allProviders().firstNotNullOfOrNull { provider ->
            provider.findSymbol(project, symbol)
        }?.let { return it }

        // Fallback: try Java PSI facade directly for class names
        PsiToolUtils.findClass(project, symbol)?.let { return it }

        return null
    }

    private fun findSymbolInFile(psiFile: com.intellij.psi.PsiFile, symbol: String): com.intellij.psi.PsiElement? {
        var found: com.intellij.psi.PsiElement? = null
        psiFile.accept(object : com.intellij.psi.PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: com.intellij.psi.PsiElement) {
                if (found != null) return
                if (element is PsiNamedElement && element.name == symbol) {
                    found = element
                    return
                }
                super.visitElement(element)
            }
        })
        return found
    }
}
