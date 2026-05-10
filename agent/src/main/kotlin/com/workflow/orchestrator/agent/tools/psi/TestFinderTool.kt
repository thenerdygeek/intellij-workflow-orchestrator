package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
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
import kotlinx.serialization.json.jsonPrimitive

class TestFinderTool(
    private val registry: LanguageProviderRegistry
) : AgentTool {
    override val name = "test_finder"
    override val description = "Find the test class for a source class, or the source class for a test class. " +
        "Uses IntelliJ's test framework integration (JUnit4, JUnit5, TestNG). " +
        "Convention-based: looks for FooTest, FooTests, TestFoo patterns."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "file" to ParameterProperty(type = "string", description = "Source or test file path relative to project or absolute"),
            "class_name" to ParameterProperty(type = "string", description = "Specific class name within the file (optional, uses first class if omitted)")
        ),
        required = listOf("file")
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.CODER, WorkerType.REVIEWER)

    override fun documentation(): ToolDocumentation = toolDoc("test_finder") {
        summary {
            technical(
                "PSI-backed bidirectional test ↔ source mapper: resolves a file to its first (or named) PsiClass, " +
                "delegates to `TestFinder.EP_NAME` extension-point finders for Java/Kotlin (JUnit 4/5, TestNG), " +
                "or to pytest naming-convention heuristics for Python. " +
                "Returns 'is this a test?', plus a list of counterpart classes (source→tests or test→sources) " +
                "with file-path and line number."
            )
            plain(
                "Like asking IntelliJ's 'Go To Test / Go To Tested Class' pair of shortcuts (Ctrl+Shift+T / Cmd+Shift+T) " +
                "without touching the keyboard — point it at a source class and it tells you which test files cover it; " +
                "point it at a test file and it tells you which source class it's testing."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.READ_ONLY)
        counterfactual(
            "Without test_finder the LLM falls back to naming-convention grep: search for `FooTest.kt`, `TestFoo.kt`, " +
            "`test_foo.py`, then scan for `@Test` on the method name inside every match. " +
            "This approach misses: (1) tests registered under a non-standard name (e.g. a JUnit 5 `@DisplayName` class); " +
            "(2) TestNG test classes that don't carry the word 'Test' in their name; " +
            "(3) Kotlin object / companion-object tests; (4) multi-class test files where one class covers several sources. " +
            "IntelliJ's `TestFinder.EP_NAME` extension point is the authoritative index for this mapping — " +
            "the LLM gets a clean, index-backed answer in one call instead of 3-5 heuristic searches with silent gaps."
        )
        llmMistake(
            "Calls test_finder on a FILE path and expects per-METHOD test links. " +
            "The tool resolves to a PsiClass (the first class in the file, or the named one) and returns class-level counterparts only. " +
            "There is no method-granularity result — `relatedElements` entries always point to the test CLASS, not a specific `@Test` method."
        )
        llmMistake(
            "Calls test_finder during IDE indexing (dumb mode) and assumes the empty result means no tests exist. " +
            "`PsiToolUtils.isDumb(project)` returns a hard dumb-mode error — there is no auto-retry or wait. " +
            "The LLM should surface the error to the user and wait for indexing before retrying."
        )
        llmMistake(
            "For a test file with multiple test classes, passes no `class_name` and trusts the result for the whole file. " +
            "The tool picks the FIRST class in the file — if the file contains `class FooTest` followed by `class BarTest`, " +
            "only FooTest's source counterpart is returned. Provide `class_name` to target a specific class."
        )
        llmMistake(
            "Interprets an empty `relatedElements` on a source class as 'this class is untested'. " +
            "An empty list has two distinct causes: (1) genuinely no test class exists in the project, or " +
            "(2) `TestFinder.EP_NAME.extensionList` is empty because no test-framework plugin is loaded — the fallback bug described in downsides. " +
            "The tool cannot distinguish these two cases; the LLM must check whether a test framework is available before treating the result as authoritative."
        )
        llmMistake(
            "Passes a directory path or a non-class file (e.g. a `.properties` or `.yaml` file). " +
            "`PathValidator.resolveAndValidate` succeeds, but `findFirstClass` returns null, producing " +
            "'Error: no class found in <path>' — not a dumb-mode error, but a class-not-found error. " +
            "The LLM should use test_finder only on `.java`, `.kt`, or `.py` source files."
        )
        params {
            required("file", "string") {
                llmSeesIt("Source or test file path relative to project or absolute")
                humanReadable(
                    "The `.java`, `.kt`, or `.py` source or test file to look up. " +
                    "Can be relative to the project root (`src/main/kotlin/Foo.kt`) or absolute. " +
                    "The tool resolves the file to its PsiClass before delegating to the test-finder extension points."
                )
                whenPresent(
                    "Path is canonicalised via `PathValidator.resolveAndValidate`, the VirtualFile is located via " +
                    "LocalFileSystem, the PsiFile is parsed, and the target PsiClass is resolved (first class or named class). " +
                    "If any step fails, a descriptive Error string is returned with `isError=true`."
                )
                constraint("must point inside the project boundary — path-traversal attempts are rejected by PathValidator")
                constraint("must be a source file containing at least one class; directories and binary files are rejected")
                example("src/main/kotlin/com/example/PaymentService.kt")
                example("src/test/java/com/example/PaymentServiceTest.java")
                example("app/services/payment.py")
            }
            optional("class_name", "string") {
                llmSeesIt("Specific class name within the file (optional, uses first class if omitted)")
                humanReadable(
                    "When the file contains multiple classes, name the one you want. " +
                    "Accepts simple name (`PaymentService`) or fully qualified name (`com.example.PaymentService`). " +
                    "If omitted, the first class in the file (depth-first PSI traversal order) is used."
                )
                whenPresent(
                    "`findClassByName` walks the PSI tree and matches by simple name OR qualified name; " +
                    "returns 'Error: class X not found in Y' if neither matches. " +
                    "Match is case-sensitive and exact — no partial matching."
                )
                whenAbsent(
                    "`findFirstClass` picks the first `PsiClass` encountered in depth-first traversal. " +
                    "For files with a single top-level class this is always correct; " +
                    "for files with nested or multiple top-level classes the selection may be surprising."
                )
                constraint("case-sensitive exact match against simple name or fully qualified name")
                example("PaymentService")
                example("com.example.PaymentService")
                example("TestFoo")
            }
        }
        verdict {
            keep(
                "Strong keep for Java/Kotlin projects where IntelliJ's test-framework integration (JUnit 4/5, TestNG) " +
                "is loaded. The `TestFinder.EP_NAME` extension-point finders are the authoritative index-backed source " +
                "for test ↔ source mapping — they see non-obvious pairs (tests registered without the word 'Test' in the name, " +
                "multi-module Maven/Gradle test modules, TestNG XML-configured suites) that naming-convention grep systematically misses. " +
                "For Python projects the fallback is pure naming convention (`Test*` class name, `test_*` file prefix), " +
                "which gives the same result as grep but with less code — marginal value for Python-only setups.",
                VerdictSeverity.STRONG,
            )
        }
        related("find_definition", Relationship.COMPOSE_WITH,
            "Use find_definition first to confirm the class exists and see its full signature, " +
            "then test_finder to locate its test counterpart. Standard workflow before writing a new test.")
        related("java_runtime_exec", Relationship.COMPOSE_WITH,
            "test_finder locates the test class; java_runtime_exec(action=run_tests) then runs it. " +
            "The two tools compose directly: test_finder gives you the class name to pass to run_tests.")
        related("python_runtime_exec", Relationship.COMPOSE_WITH,
            "test_finder locates the pytest test file or class; python_runtime_exec(action=run_tests) then runs it. " +
            "The two tools compose directly for the Python test-execute workflow.")
        related("find_references", Relationship.SEE_ALSO,
            "find_references on a source class returns ALL its usages — call sites, test classes, and imports mixed together. " +
            "test_finder returns ONLY the test-counterpart class(es) with no noise. " +
            "Use test_finder when you specifically want 'what tests cover this?'; use find_references when you want all usages.")
        downside(
            "FALLBACK BUG: When `TestFinder.EP_NAME.extensionList` is empty (no test-framework plugin loaded — " +
            "can happen in minimal IntelliJ Community installs without JUnit/TestNG plugins), " +
            "`JavaKotlinProvider.findRelatedTests` returns `TestRelationResult(isTestElement=false, relatedElements=emptyList())` — " +
            "the same result as 'no tests found'. The LLM cannot distinguish 'no providers loaded' from 'no test class exists'. " +
            "The tool does not surface an error or warning when the extension-point list is empty."
        )
        downside(
            "Python test discovery is naming-convention only: `PythonProvider.findRelatedTests` does NOT use any pytest plugin API. " +
            "It checks `elementName.startsWith('Test')` for classes, `elementName.startsWith('test_')` for functions, " +
            "and `filename.startsWith('test_') || filename.endsWith('_test.py')` for files. " +
            "Tests registered via `pytest.ini` `python_classes`/`python_files` config options that override defaults will be missed. " +
            "Parameterised test factories, dynamically generated test IDs, and conftest.py fixtures have no representation."
        )
        downside(
            "Class-granularity only — no method-level mapping. `relatedElements` always points to a test CLASS, " +
            "not to a specific `@Test` method inside it. For large test classes with hundreds of methods, " +
            "the LLM still has to navigate into the test file to find the specific test for a specific method."
        )
        downside(
            "No project-scope limitation parameter — `TestFinder.EP_NAME` finders run against the project index by default. " +
            "For monorepos with tens of thousands of test classes, the first-match semantics in the extension-point iteration " +
            "are non-deterministic; the provider whose finder returns results first wins."
        )
        observation(
            "The `class_name` parameter name (intended for disambiguating multi-class files) overlaps with `find_implementations` " +
            "and `find_definition`'s `class_name` parameter but has different semantics: here it scopes the PSI walk within " +
            "the given FILE, not project-wide. LLMs familiar with find_implementations may assume `class_name` is a project-scope " +
            "hint and omit it when the file has a single class — this is harmless but worth noting."
        )
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val filePath = params["file"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Error: 'file' parameter is required",
                "Error: missing file", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )

        val className = try {
            params["class_name"]?.jsonPrimitive?.content
        } catch (_: Exception) { null }

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

            // Find the target class in the file
            val targetClass = if (className != null) {
                findClassByName(psiFile, className)
                    ?: return@nonBlocking "Error: class '$className' not found in $filePath"
            } else {
                findFirstClass(psiFile)
                    ?: return@nonBlocking "Error: no class found in $filePath"
            }

            // Delegate test finding to the provider
            val result = provider.findRelatedTests(targetClass)

            val qualifiedName = (targetClass as? PsiClass)?.qualifiedName
                ?: (targetClass as? PsiClass)?.name ?: "Unknown"
            val relativePath = PsiToolUtils.relativePath(project, targetClass.containingFile?.virtualFile?.path ?: filePath)

            val sb = StringBuilder()
            sb.appendLine("Source class: $qualifiedName ($relativePath)")
            sb.appendLine("Is test: ${result.isTestElement}")
            sb.appendLine()

            if (result.relatedElements.isEmpty()) {
                if (result.isTestElement) {
                    sb.appendLine("No source classes found for this test.")
                } else {
                    sb.appendLine("No test classes found for this source class.")
                }
            } else {
                val label = if (result.isTestElement) "source" else "test"
                sb.appendLine("Found ${result.relatedElements.size} $label class${if (result.relatedElements.size > 1) "es" else ""}:")
                result.relatedElements.forEachIndexed { index, info ->
                    sb.appendLine("  ${index + 1}. ${info.name} (${info.filePath})")
                }
            }

            sb.toString().trimEnd()
        }.inSmartMode(project).executeSynchronously()

        val isError = content.startsWith("Error:") || content.startsWith("Code intelligence not available")
        return ToolResult(
            content = content,
            summary = if (isError) content else "Test finder results for $filePath",
            tokenEstimate = TokenEstimator.estimate(content),
            isError = isError
        )
    }

    private fun findClassByName(psiFile: com.intellij.psi.PsiFile, className: String): com.intellij.psi.PsiElement? {
        val classes = mutableListOf<PsiClass>()
        psiFile.accept(object : com.intellij.psi.PsiRecursiveElementVisitor() {
            override fun visitElement(element: com.intellij.psi.PsiElement) {
                if (element is PsiClass) {
                    classes.add(element)
                }
                super.visitElement(element)
            }
        })
        // Match by simple name or qualified name
        return classes.firstOrNull { it.name == className || it.qualifiedName == className }
    }

    private fun findFirstClass(psiFile: com.intellij.psi.PsiFile): com.intellij.psi.PsiElement? {
        var found: PsiClass? = null
        psiFile.accept(object : com.intellij.psi.PsiRecursiveElementVisitor() {
            override fun visitElement(element: com.intellij.psi.PsiElement) {
                if (found == null && element is PsiClass) {
                    found = element
                    return
                }
                if (found == null) {
                    super.visitElement(element)
                }
            }
        })
        return found
    }
}
