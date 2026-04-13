package com.workflow.orchestrator.agent.ide

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.workflow.orchestrator.agent.tools.psi.PsiToolUtils
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Python implementation of [LanguageIntelligenceProvider].
 *
 * All Python PSI access goes through [PythonPsiHelper] (reflection). There are
 * NO compile-time imports from the Python plugin. Only IntelliJ platform imports
 * are used.
 *
 * Methods assume they are called inside a `ReadAction` (or equivalent read-lock).
 * The tools remain responsible for parameter parsing, path validation,
 * `ReadAction.nonBlocking`, dumb-mode checks, error handling, and formatting.
 */
class PythonProvider(
    private val helper: PythonPsiHelper
) : LanguageIntelligenceProvider {

    override val supportedLanguageIds = setOf("Python")

    // ---------------------------------------------------------------------------
    // Symbol Resolution
    // ---------------------------------------------------------------------------

    /**
     * Find a named symbol (class, function, variable) by name in project scope.
     * Iterates PyFile top-level elements via helper. Falls back to [PsiShortNamesCache].
     */
    override fun findSymbol(project: Project, name: String): PsiElement? {
        try {
            // Try "module.name" syntax
            val parts = name.split('.')
            if (parts.size == 2) {
                val className = parts[0]
                val memberName = parts[1]
                // Search for the class, then look for the member
                findPyClassByName(project, className)?.let { pyClass ->
                    helper.getMethods(pyClass).firstOrNull { helper.getName(it) == memberName }?.let { return it }
                    helper.getClassAttributes(pyClass).firstOrNull { helper.getName(it) == memberName }?.let { return it }
                }
            }

            // Try as a class name
            findPyClassByName(project, name)?.let { return it }

            // Try as a function name
            findPyFunctionByName(project, name)?.let { return it }

            // Fallback: PsiShortNamesCache (platform API, works for Python too)
            val scope = GlobalSearchScope.projectScope(project)
            val cache = PsiShortNamesCache.getInstance(project)
            cache.getClassesByName(name, scope).firstOrNull()?.let { return it }

            return null
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Find a symbol in a specific file at the given offset.
     * Walks from the leaf upward to find the nearest Python declaration.
     */
    override fun findSymbolAt(file: PsiFile, offset: Int): PsiElement? {
        try {
            val leaf = file.findElementAt(offset) ?: return null
            var current: PsiElement? = leaf.parent
            while (current != null && current !is PsiFile) {
                if (helper.isPyDeclaration(current)) return current
                current = current.parent
            }
            return null
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Get definition info for a resolved Python element.
     * Builds [DefinitionInfo] from helper.getName(), getQualifiedName(), document line number.
     */
    override fun getDefinitionInfo(element: PsiElement): DefinitionInfo? {
        try {
            val containingFile = element.containingFile ?: return null
            val virtualFile = containingFile.virtualFile ?: return null
            val project = element.project
            val filePath = PsiToolUtils.relativePath(project, virtualFile.path)
            val document = PsiDocumentManager.getInstance(project).getDocument(containingFile)
            val line = document?.getLineNumber(element.textOffset)?.plus(1) ?: 0

            val name = helper.getName(element)
            val qualifiedName = helper.getQualifiedName(element)

            return when {
                helper.isPyClass(element) -> {
                    val skeleton = buildClassSkeleton(element)
                    DefinitionInfo(
                        filePath = filePath,
                        line = line,
                        signature = qualifiedName ?: name ?: "anonymous",
                        documentation = extractDocString(element),
                        skeleton = skeleton
                    )
                }
                helper.isPyFunction(element) -> {
                    val signature = buildFunctionSignature(element)
                    DefinitionInfo(
                        filePath = filePath,
                        line = line,
                        signature = signature,
                        documentation = extractDocString(element)
                    )
                }
                helper.isPyTargetExpression(element) -> {
                    DefinitionInfo(
                        filePath = filePath,
                        line = line,
                        signature = name ?: element.text.take(120),
                        documentation = null
                    )
                }
                else -> {
                    DefinitionInfo(
                        filePath = filePath,
                        line = line,
                        signature = element.text.take(120)
                    )
                }
            }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (_: Exception) {
            return null
        }
    }

    // ---------------------------------------------------------------------------
    // File Structure
    // ---------------------------------------------------------------------------

    /**
     * Get the structure of a Python file (classes, functions, imports, attributes).
     */
    override fun getFileStructure(file: PsiFile, detail: DetailLevel): FileStructureResult {
        try {
            if (!helper.isPyFile(file)) {
                return FileStructureResult(
                    packageOrModule = null,
                    imports = emptyList(),
                    declarations = emptyList(),
                    formatted = file.text?.take(5000) ?: ""
                )
            }

            val document = file.viewProvider.document

            // Module name from file name
            val moduleName = file.virtualFile?.nameWithoutExtension

            // Imports
            val importBlock = helper.getImportBlock(file)
            val imports = importBlock.map { it.text.trim() }

            // Declarations
            val declarations = mutableListOf<DeclarationInfo>()

            // Top-level classes
            for (pyClass in helper.getTopLevelClasses(file)) {
                declarations.add(buildClassDeclaration(pyClass, detail, document))
            }

            // Top-level functions
            for (pyFunc in helper.getTopLevelFunctions(file)) {
                val funcName = helper.getName(pyFunc) ?: continue
                val line = document?.getLineNumber(pyFunc.textOffset)?.plus(1) ?: 0
                val signature = buildFunctionSignature(pyFunc)
                declarations.add(DeclarationInfo(
                    name = funcName,
                    kind = "function",
                    signature = signature,
                    line = line
                ))
            }

            // Top-level attributes
            for (attr in helper.getTopLevelAttributes(file)) {
                val attrName = helper.getName(attr) ?: continue
                val line = document?.getLineNumber(attr.textOffset)?.plus(1) ?: 0
                declarations.add(DeclarationInfo(
                    name = attrName,
                    kind = "variable",
                    signature = attrName,
                    line = line
                ))
            }

            // Formatted output
            val formatted = buildFormattedStructure(file, detail, moduleName, imports, declarations)

            return FileStructureResult(
                packageOrModule = moduleName,
                imports = imports,
                declarations = declarations,
                formatted = formatted
            )
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (_: Exception) {
            return FileStructureResult(
                packageOrModule = null,
                imports = emptyList(),
                declarations = emptyList(),
                formatted = "Error: Could not parse Python file structure"
            )
        }
    }

    // ---------------------------------------------------------------------------
    // Type System
    // ---------------------------------------------------------------------------

    /**
     * Get the type hierarchy for a Python class (superclasses + subclasses).
     */
    override fun getTypeHierarchy(element: PsiElement): TypeHierarchyResult? {
        try {
            if (!helper.isPyClass(element)) return null

            val project = element.project
            val name = helper.getName(element) ?: "?"
            val qualifiedName = helper.getQualifiedName(element) ?: name
            val containingFile = element.containingFile

            // Supertypes via helper.getSuperClasses()
            val context = if (containingFile != null) {
                helper.createCodeAnalysisContext(containingFile)
            } else null

            val supers = helper.getSuperClasses(element, context)
            val supertypes = supers.mapNotNull { superClass ->
                val superName = helper.getName(superClass) ?: return@mapNotNull null
                val superQName = helper.getQualifiedName(superClass) ?: superName
                // Skip 'object' base class
                if (superQName == "builtins.object" || superName == "object") return@mapNotNull null
                val filePath = superClass.containingFile?.virtualFile?.path
                    ?.let { PsiToolUtils.relativePath(project, it) }
                val doc = superClass.containingFile?.let {
                    PsiDocumentManager.getInstance(project).getDocument(it)
                }
                val line = doc?.getLineNumber(superClass.textOffset)?.plus(1)
                HierarchyEntry(
                    name = superName,
                    qualifiedName = superQName,
                    filePath = filePath,
                    line = line
                )
            }

            // Subtypes via helper.findInheritors()
            val scope = GlobalSearchScope.projectScope(project)
            val inheritors = helper.findInheritors(element, scope)
            val subtypes = inheritors.take(30).mapNotNull { inheritor ->
                val inhName = helper.getName(inheritor) ?: return@mapNotNull null
                val inhQName = helper.getQualifiedName(inheritor) ?: inhName
                val filePath = inheritor.containingFile?.virtualFile?.path
                    ?.let { PsiToolUtils.relativePath(project, it) }
                val doc = inheritor.containingFile?.let {
                    PsiDocumentManager.getInstance(project).getDocument(it)
                }
                val line = doc?.getLineNumber(inheritor.textOffset)?.plus(1)
                HierarchyEntry(
                    name = inhName,
                    qualifiedName = inhQName,
                    filePath = filePath,
                    line = line
                )
            }

            return TypeHierarchyResult(
                element = qualifiedName,
                supertypes = supertypes,
                subtypes = subtypes
            )
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Find implementations/subclasses of a class or overrides of a method.
     * For classes: helper.findInheritors(). For functions: search inheritor classes
     * for methods with the same name.
     */
    override fun findImplementations(element: PsiElement, scope: SearchScope): List<ImplementationInfo> {
        try {
            val project = element.project
            val globalScope = if (scope is GlobalSearchScope) scope
            else GlobalSearchScope.projectScope(project)

            when {
                helper.isPyClass(element) -> {
                    val inheritors = helper.findInheritors(element, globalScope)
                    return inheritors.take(40).mapNotNull { inheritor ->
                        val name = helper.getName(inheritor) ?: return@mapNotNull null
                        val qName = helper.getQualifiedName(inheritor) ?: name
                        val filePath = PsiToolUtils.relativePath(
                            project, inheritor.containingFile?.virtualFile?.path ?: ""
                        )
                        val doc = inheritor.containingFile?.let {
                            PsiDocumentManager.getInstance(project).getDocument(it)
                        }
                        val line = doc?.getLineNumber(inheritor.textOffset)?.plus(1) ?: 0
                        ImplementationInfo(
                            name = name,
                            signature = qName,
                            filePath = filePath,
                            line = line
                        )
                    }
                }
                helper.isPyFunction(element) -> {
                    // For a method, find the containing class, then search inheritors
                    // for a method with the same name
                    val methodName = helper.getName(element) ?: return emptyList()
                    val containingClass = findContainingPyClass(element) ?: return emptyList()

                    val inheritors = helper.findInheritors(containingClass, globalScope)
                    return inheritors.take(40).mapNotNull { inheritor ->
                        val methods = helper.getMethods(inheritor)
                        val overridingMethod = methods.firstOrNull { helper.getName(it) == methodName }
                            ?: return@mapNotNull null
                        val className = helper.getName(inheritor) ?: "?"
                        val filePath = PsiToolUtils.relativePath(
                            project, overridingMethod.containingFile?.virtualFile?.path ?: ""
                        )
                        val doc = overridingMethod.containingFile?.let {
                            PsiDocumentManager.getInstance(project).getDocument(it)
                        }
                        val line = doc?.getLineNumber(overridingMethod.textOffset)?.plus(1) ?: 0
                        ImplementationInfo(
                            name = "$className.$methodName",
                            signature = buildFunctionSignature(overridingMethod),
                            filePath = filePath,
                            line = line
                        )
                    }
                }
                else -> return emptyList()
            }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (_: Exception) {
            return emptyList()
        }
    }

    /**
     * Infer the type of a Python expression, variable, or parameter.
     * Uses helper.getType() + helper.getTypeName(). Detects Optional[X] / X | None.
     */
    override fun inferType(element: PsiElement): TypeInferenceResult? {
        try {
            val containingFile = element.containingFile ?: return null
            val context = helper.createCodeAnalysisContext(containingFile)
            val pyType = helper.getType(element, context)
            val typeName = helper.getTypeName(pyType) ?: return null

            val nullability = when {
                typeName.startsWith("Optional[") -> Nullability.NULLABLE
                typeName.contains(" | None") || typeName.contains("None | ") -> Nullability.NULLABLE
                typeName == "None" -> Nullability.NULLABLE
                else -> Nullability.UNKNOWN
            }

            return TypeInferenceResult(
                typeName = typeName,
                qualifiedName = null, // Python type names are not typically fully qualified
                nullability = nullability
            )
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Analyze dataflow for a Python element.
     * Python has no equivalent of Java's CommonDataflow — always returns null.
     */
    override fun analyzeDataflow(element: PsiElement): DataflowResult? {
        // No Python equivalent for Java's CommonDataflow analysis
        return null
    }

    // ---------------------------------------------------------------------------
    // Call Graph
    // ---------------------------------------------------------------------------

    /**
     * Find callers of a Python function/method.
     * Uses [ReferencesSearch] (platform API) and walks up to containing function.
     */
    override fun findCallers(element: PsiElement, depth: Int, scope: SearchScope): List<CallerInfo> {
        try {
            if (!helper.isPyFunction(element)) return emptyList()

            val project = element.project
            val callers = mutableListOf<CallerInfo>()
            val maxDepth = depth.coerceIn(1, 3)
            // IdentityHashMap: PsiElement.equals() is not a stable contract; identity comparison
            // prevents re-visiting the same live PSI object across a recursive DFS.
            val visited = Collections.newSetFromMap(IdentityHashMap<PsiElement, Boolean>())

            collectPyCallers(element, 1, maxDepth, scope, callers, project, visited)
            return callers
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (_: Exception) {
            return emptyList()
        }
    }

    /**
     * Find callees within a Python function/method body.
     * Walks PSI children, finds PyCallExpression elements, resolves references.
     */
    override fun findCallees(element: PsiElement): List<CalleeInfo> {
        try {
            if (!helper.isPyFunction(element)) return emptyList()

            val project = element.project
            val callees = mutableListOf<CalleeInfo>()
            val seen = mutableSetOf<String>()

            element.accept(object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(elem: PsiElement) {
                    super.visitElement(elem)
                    if (helper.isPyCallExpression(elem)) {
                        val callee = helper.getCallee(elem)
                        if (callee != null) {
                            val ref = callee.reference
                            val resolved = ref?.resolve()
                            val calleeName = if (resolved != null) {
                                helper.getName(resolved) ?: callee.text?.take(60) ?: "?"
                            } else {
                                callee.text?.take(60) ?: "?"
                            }
                            if (calleeName !in seen) {
                                seen.add(calleeName)
                                val calleeFile = resolved?.containingFile?.virtualFile?.path
                                    ?.let { PsiToolUtils.relativePath(project, it) }
                                val calleeLine = resolved?.containingFile?.let {
                                    PsiDocumentManager.getInstance(project).getDocument(it)
                                }?.getLineNumber(resolved.textOffset)?.plus(1)
                                callees.add(CalleeInfo(
                                    name = calleeName,
                                    filePath = calleeFile,
                                    line = calleeLine
                                ))
                            }
                        }
                    }
                }
            })

            return callees
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (_: Exception) {
            return emptyList()
        }
    }

    // ---------------------------------------------------------------------------
    // Metadata
    // ---------------------------------------------------------------------------

    /**
     * Get decorators on a Python class or function.
     * Uses helper.getDecoratorList() and helper.getDecorators().
     */
    override fun getMetadata(element: PsiElement, includeInherited: Boolean): List<MetadataInfo> {
        try {
            if (!helper.isPyClass(element) && !helper.isPyFunction(element)) return emptyList()

            val result = mutableListOf<MetadataInfo>()

            // Direct decorators
            val decoratorList = helper.getDecoratorList(element)
            if (decoratorList != null) {
                val decorators = helper.getDecorators(decoratorList)
                for (decorator in decorators) {
                    result.add(decoratorToMetadataInfo(decorator, isInherited = false))
                }
            }

            // Walk superclass chain if requested and element is a class
            if (includeInherited && helper.isPyClass(element)) {
                val seen = result.map { it.name }.toMutableSet()
                val containingFile = element.containingFile
                val context = if (containingFile != null) {
                    helper.createCodeAnalysisContext(containingFile)
                } else null

                val supers = helper.getSuperClasses(element, context)
                for (superClass in supers) {
                    val superQName = helper.getQualifiedName(superClass) ?: helper.getName(superClass)
                    if (superQName == "builtins.object" || superQName == "object") continue

                    val superDecList = helper.getDecoratorList(superClass)
                    if (superDecList != null) {
                        for (dec in helper.getDecorators(superDecList)) {
                            val decName = helper.getName(dec) ?: dec.text?.take(60) ?: continue
                            if (seen.add(decName)) {
                                result.add(decoratorToMetadataInfo(dec, isInherited = true))
                            }
                        }
                    }
                }
            }

            return result
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (_: Exception) {
            return emptyList()
        }
    }

    /**
     * Get the source text of a function/class body with optional context lines.
     * Same pattern as JavaKotlinProvider — uses document text range.
     */
    override fun getBody(element: PsiElement, contextLines: Int): BodyResult? {
        try {
            if (!helper.isPyFunction(element) && !helper.isPyClass(element)) return null

            val containingFile = element.containingFile ?: return null
            val project = element.project
            val document = PsiDocumentManager.getInstance(project).getDocument(containingFile)
                ?: return null

            val startOffset = element.textRange.startOffset
            val endOffset = element.textRange.endOffset

            val startLine = document.getLineNumber(startOffset)
            val endLine = document.getLineNumber(endOffset)

            val clampedContext = contextLines.coerceIn(0, 5)
            val rangeStart = maxOf(0, startLine - clampedContext)
            val rangeEnd = minOf(document.lineCount - 1, endLine + clampedContext)

            val sb = StringBuilder()
            for (lineIdx in rangeStart..rangeEnd) {
                val lineStartOffset = document.getLineStartOffset(lineIdx)
                val lineEndOffset = document.getLineEndOffset(lineIdx)
                val lineText = document.getText(TextRange(lineStartOffset, lineEndOffset))
                sb.appendLine("${lineIdx + 1}: $lineText")
            }

            return BodyResult(
                source = sb.toString().trimEnd(),
                startLine = rangeStart + 1,
                endLine = rangeEnd + 1
            )
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (_: Exception) {
            return null
        }
    }

    // ---------------------------------------------------------------------------
    // Access Analysis
    // ---------------------------------------------------------------------------

    /**
     * Classify usages of a Python variable as read, write, or read-write.
     * Uses [ReferencesSearch] (platform API). Checks if parent is a PyAssignmentStatement
     * target to detect writes.
     */
    override fun classifyAccesses(element: PsiElement, scope: SearchScope): AccessClassification {
        try {
            // Resolve to a nameable target (function, class, target expression)
            val target = if (helper.isPyDeclaration(element)) element
            else return AccessClassification(emptyList(), emptyList(), emptyList())

            val project = element.project
            val references = ReferencesSearch.search(target, scope).findAll()
            val reads = mutableListOf<AccessInfo>()
            val writes = mutableListOf<AccessInfo>()
            val readWrites = mutableListOf<AccessInfo>()

            for (ref in references) {
                val refElement = ref.element
                val refFile = refElement.containingFile?.virtualFile?.path ?: continue
                val refRelPath = PsiToolUtils.relativePath(project, refFile)
                val refDoc = PsiDocumentManager.getInstance(project)
                    .getDocument(refElement.containingFile) ?: continue
                val refLine = refDoc.getLineNumber(refElement.textOffset) + 1
                val lineText = getLineText(refDoc, refLine - 1).trim()
                val info = AccessInfo(filePath = refRelPath, line = refLine, context = lineText)

                when (classifyPyAccessType(refElement)) {
                    PyAccessType.WRITE -> writes.add(info)
                    PyAccessType.READ -> reads.add(info)
                    PyAccessType.READ_WRITE -> {
                        readWrites.add(info)
                        writes.add(info)
                        reads.add(info)
                    }
                }
            }

            return AccessClassification(reads = reads, writes = writes, readWrites = readWrites)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (_: Exception) {
            return AccessClassification(emptyList(), emptyList(), emptyList())
        }
    }

    // ---------------------------------------------------------------------------
    // Test Discovery
    // ---------------------------------------------------------------------------

    /**
     * Find tests associated with a source element using pytest conventions.
     * Python test discovery: test_*.py files, Test{ClassName} classes, test_{method} functions.
     */
    override fun findRelatedTests(element: PsiElement): TestRelationResult {
        try {
            val project = element.project

            // Determine if this element is itself a test
            val elementName = helper.getName(element)
            val isTest = when {
                helper.isPyFunction(element) -> elementName?.startsWith("test_") == true
                helper.isPyClass(element) -> elementName?.startsWith("Test") == true
                else -> {
                    val containingFileName = element.containingFile?.name ?: ""
                    containingFileName.startsWith("test_") || containingFileName.endsWith("_test.py")
                }
            }

            val related = mutableListOf<TestRelatedInfo>()

            if (isTest) {
                // This is a test — find source elements it tests
                // For a test class "TestFoo", look for class "Foo"
                if (helper.isPyClass(element) && elementName != null && elementName.startsWith("Test")) {
                    val sourceClassName = elementName.removePrefix("Test")
                    findPyClassByName(project, sourceClassName)?.let { sourceClass ->
                        val filePath = sourceClass.containingFile?.virtualFile?.path
                            ?.let { PsiToolUtils.relativePath(project, it) } ?: "unknown"
                        val doc = sourceClass.containingFile?.let {
                            PsiDocumentManager.getInstance(project).getDocument(it)
                        }
                        val line = doc?.getLineNumber(sourceClass.textOffset)?.plus(1) ?: 0
                        related.add(TestRelatedInfo(
                            name = helper.getQualifiedName(sourceClass) ?: sourceClassName,
                            filePath = filePath,
                            line = line,
                            kind = "source"
                        ))
                    }
                }
            } else {
                // This is a source element — find test files/classes
                val scope = GlobalSearchScope.projectScope(project)

                if (helper.isPyClass(element) && elementName != null) {
                    // Look for "Test{ClassName}" classes
                    val testClassName = "Test$elementName"
                    findPyClassByName(project, testClassName)?.let { testClass ->
                        val filePath = testClass.containingFile?.virtualFile?.path
                            ?.let { PsiToolUtils.relativePath(project, it) } ?: "unknown"
                        val doc = testClass.containingFile?.let {
                            PsiDocumentManager.getInstance(project).getDocument(it)
                        }
                        val line = doc?.getLineNumber(testClass.textOffset)?.plus(1) ?: 0
                        related.add(TestRelatedInfo(
                            name = helper.getQualifiedName(testClass) ?: testClassName,
                            filePath = filePath,
                            line = line,
                            kind = "test"
                        ))
                    }

                    // Scan for test_*.py files matching the source file
                    val sourceFileName = element.containingFile?.virtualFile?.nameWithoutExtension
                    if (sourceFileName != null) {
                        scanForTestFiles(project, scope, sourceFileName, related)
                    }
                }

                if (helper.isPyFunction(element) && elementName != null) {
                    // For a function "foo", look for "test_foo" in test files
                    val containingClass = findContainingPyClass(element)
                    if (containingClass != null) {
                        val className = helper.getName(containingClass)
                        if (className != null) {
                            val testClassName = "Test$className"
                            findPyClassByName(project, testClassName)?.let { testClass ->
                                val testMethodName = "test_$elementName"
                                helper.getMethods(testClass).firstOrNull {
                                    helper.getName(it) == testMethodName
                                }?.let { testMethod ->
                                    val filePath = testMethod.containingFile?.virtualFile?.path
                                        ?.let { PsiToolUtils.relativePath(project, it) } ?: "unknown"
                                    val doc = testMethod.containingFile?.let {
                                        PsiDocumentManager.getInstance(project).getDocument(it)
                                    }
                                    val line = doc?.getLineNumber(testMethod.textOffset)?.plus(1) ?: 0
                                    related.add(TestRelatedInfo(
                                        name = "${helper.getQualifiedName(testClass) ?: testClassName}.$testMethodName",
                                        filePath = filePath,
                                        line = line,
                                        kind = "test"
                                    ))
                                }
                            }
                        }
                    }
                }
            }

            return TestRelationResult(isTestElement = isTest, relatedElements = related)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (_: Exception) {
            return TestRelationResult(isTestElement = false, relatedElements = emptyList())
        }
    }

    // ---------------------------------------------------------------------------
    // Diagnostics
    // ---------------------------------------------------------------------------

    /**
     * Find syntax errors and unresolved references in a Python file.
     * Uses platform PsiErrorElement walking + Python reference resolution check.
     */
    override fun getDiagnostics(file: PsiFile, lineRange: IntRange?): List<DiagnosticInfo> {
        try {
            val virtualFile = file.virtualFile ?: return emptyList()
            val project = file.project
            val filePath = PsiToolUtils.relativePath(project, virtualFile.path)
            val document = file.viewProvider.document
            val allProblems = mutableListOf<DiagnosticInfo>()

            // Syntax errors (PsiErrorElement — platform API)
            PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java).forEach { error ->
                val line = document?.getLineNumber(error.textOffset)?.plus(1) ?: 0
                allProblems.add(DiagnosticInfo(
                    message = "Syntax error — ${error.errorDescription}",
                    severity = "ERROR",
                    line = line,
                    filePath = filePath
                ))
            }

            // Unresolved references (Python-specific)
            val unresolvedSeen = mutableSetOf<String>()
            val pyRefClass = helper.pyReferenceExpressionClass
            if (pyRefClass != null) {
                file.accept(object : PsiRecursiveElementWalkingVisitor() {
                    override fun visitElement(element: PsiElement) {
                        // Skip string literals
                        if (helper.pyStringLiteralClass?.isInstance(element) == true) return
                        super.visitElement(element)

                        if (pyRefClass.isInstance(element)) {
                            val ref = element.reference
                            if (ref != null) {
                                val resolved = if (ref is PsiPolyVariantReference) {
                                    ref.multiResolve(false).isNotEmpty()
                                } else {
                                    ref.resolve() != null
                                }
                                if (!resolved) {
                                    val text = ref.canonicalText.take(60)
                                    if (text.isNotBlank()
                                        && IDENTIFIER_RE.matches(text)
                                        && text !in unresolvedSeen
                                        && !text.startsWith("builtins.")
                                    ) {
                                        unresolvedSeen.add(text)
                                        val line = document?.getLineNumber(element.textOffset)?.plus(1) ?: 0
                                        allProblems.add(DiagnosticInfo(
                                            message = "Unresolved reference '$text'",
                                            severity = "ERROR",
                                            line = line,
                                            filePath = filePath
                                        ))
                                    }
                                }
                            }
                        }
                    }
                })
            }

            // Filter to line range if provided
            return if (lineRange != null) {
                allProblems.filter { it.line in lineRange }
            } else {
                allProblems
            }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (_: Exception) {
            return emptyList()
        }
    }

    // ---------------------------------------------------------------------------
    // Structural Search
    // ---------------------------------------------------------------------------

    /**
     * Structural search is not supported for Python in IntelliJ Platform.
     * Returns null — the tool handles null by reporting "not supported".
     */
    override fun structuralSearch(
        project: Project,
        pattern: String,
        scope: SearchScope
    ): List<StructuralMatchInfo>? {
        return null
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private enum class PyAccessType { READ, WRITE, READ_WRITE }

    /**
     * Classify a Python reference as read/write by checking if the parent
     * is a PyAssignmentStatement and this element is on the target (left) side.
     */
    private fun classifyPyAccessType(element: PsiElement): PyAccessType {
        val assignmentClass = helper.pyAssignmentStatementClass ?: return PyAccessType.READ

        var current: PsiElement? = element.parent
        while (current != null && current !is PsiFile) {
            if (assignmentClass.isInstance(current)) {
                // Check if element is on the target side (left of '=')
                try {
                    val getTargets = current.javaClass.getMethod("getTargets")
                    @Suppress("UNCHECKED_CAST")
                    val targets = getTargets.invoke(current) as? Array<*>
                    if (targets != null) {
                        for (target in targets) {
                            if (target is PsiElement && PsiTreeUtil.isAncestor(target, element, false)) {
                                return PyAccessType.WRITE
                            }
                        }
                    }
                } catch (_: ReflectiveOperationException) {
                    // Fallback: check text-based position relative to '='
                    val assignText = current.text
                    val eqIndex = assignText.indexOf('=')
                    if (eqIndex > 0) {
                        val elemOffset = element.textOffset - current.textOffset
                        if (elemOffset < eqIndex) return PyAccessType.WRITE
                    }
                }
                return PyAccessType.READ
            }

            // Augmented assignment (+=, -=, etc.)
            if (helper.pyAugAssignmentStatementClass?.isInstance(current) == true) {
                return PyAccessType.READ_WRITE
            }

            current = current.parent
        }

        return PyAccessType.READ
    }

    /**
     * Find the containing PyClass of an element by walking up the PSI tree.
     */
    private fun findContainingPyClass(element: PsiElement): PsiElement? {
        var current: PsiElement? = element.parent
        while (current != null && current !is PsiFile) {
            if (helper.isPyClass(current)) return current
            current = current.parent
        }
        return null
    }

    /**
     * Find a Python class by name across all project files.
     * Uses PsiShortNamesCache (platform API) and filters for PyClass.
     */
    private fun findPyClassByName(project: Project, name: String): PsiElement? {
        val scope = GlobalSearchScope.projectScope(project)
        val cache = PsiShortNamesCache.getInstance(project)
        // PsiShortNamesCache.getClassesByName returns PsiClass[], which may include Python classes
        // if the Python plugin registers them
        val classes = cache.getClassesByName(name, scope)
        for (cls in classes) {
            if (helper.isPyClass(cls)) return cls
        }
        return null
    }

    /**
     * Find a top-level Python function by name.
     * Falls back to PsiShortNamesCache method search.
     */
    private fun findPyFunctionByName(project: Project, name: String): PsiElement? {
        val scope = GlobalSearchScope.projectScope(project)
        val cache = PsiShortNamesCache.getInstance(project)
        val methods = cache.getMethodsByName(name, scope)
        for (method in methods) {
            if (helper.isPyFunction(method)) return method
        }
        return null
    }

    /**
     * Recursive caller collection for Python functions.
     */
    private fun collectPyCallers(
        element: PsiElement,
        currentDepth: Int,
        maxDepth: Int,
        scope: SearchScope,
        result: MutableList<CallerInfo>,
        project: Project,
        visited: MutableSet<PsiElement>
    ) {
        if (currentDepth > maxDepth || !visited.add(element)) return
        val refs = ReferencesSearch.search(element, scope).findAll()
        val limit = if (currentDepth == 1) 30 else 10
        for (ref in refs.take(limit)) {
            val refElement = ref.element
            // Walk up to find the containing function
            var container: PsiElement? = refElement.parent
            while (container != null && container !is PsiFile) {
                if (helper.isPyFunction(container)) break
                container = container.parent
            }
            val callerName = if (container != null && helper.isPyFunction(container)) {
                val containingClass = findContainingPyClass(container)
                val className = if (containingClass != null) helper.getName(containingClass) else null
                val funcName = helper.getName(container) ?: "?"
                if (className != null) "$className.$funcName" else funcName
            } else {
                "(module-level)"
            }
            val file = refElement.containingFile?.virtualFile?.path
                ?.let { PsiToolUtils.relativePath(project, it) } ?: ""
            val doc = refElement.containingFile?.let {
                PsiDocumentManager.getInstance(project).getDocument(it)
            }
            val line = doc?.getLineNumber(refElement.textOffset)?.plus(1) ?: 0
            result.add(CallerInfo(name = callerName, filePath = file, line = line, depth = currentDepth))

            if (container != null && helper.isPyFunction(container) && currentDepth < maxDepth) {
                collectPyCallers(container, currentDepth + 1, maxDepth, scope, result, project, visited)
            }
        }
    }

    /**
     * Build a function signature string from a PyFunction element.
     */
    private fun buildFunctionSignature(pyFunc: PsiElement): String {
        val name = helper.getName(pyFunc) ?: "anonymous"
        val paramList = helper.getParameterList(pyFunc)
        val params = if (paramList != null) {
            helper.getParameters(paramList).joinToString(", ") { param ->
                helper.getName(param) ?: param.text.take(40)
            }
        } else ""
        return "def $name($params)"
    }

    /**
     * Build a class skeleton showing methods and attributes.
     */
    private fun buildClassSkeleton(pyClass: PsiElement): String {
        val sb = StringBuilder()
        val name = helper.getName(pyClass) ?: "?"
        sb.appendLine("class $name:")

        val methods = helper.getMethods(pyClass)
        if (methods.isEmpty() && helper.getClassAttributes(pyClass).isEmpty()) {
            sb.appendLine("    pass")
            return sb.toString().trimEnd()
        }

        for (method in methods) {
            sb.appendLine("    ${buildFunctionSignature(method)}")
        }

        for (attr in helper.getClassAttributes(pyClass)) {
            val attrName = helper.getName(attr) ?: continue
            sb.appendLine("    $attrName")
        }

        return sb.toString().trimEnd()
    }

    /**
     * Extract the docstring text from a Python class or function.
     */
    private fun extractDocString(element: PsiElement): String? {
        val docExpr = helper.getDocStringExpression(element)
        return docExpr?.text?.takeIf { it.isNotBlank() }
    }

    /**
     * Convert a Python decorator to MetadataInfo.
     */
    private fun decoratorToMetadataInfo(decorator: PsiElement, isInherited: Boolean): MetadataInfo {
        val name = helper.getName(decorator) ?: decorator.text?.take(60) ?: "?"
        val callee = helper.getCallee(decorator)
        val qualifiedName = callee?.text ?: name

        // Try to extract arguments from decorator text
        val parameters = mutableMapOf<String, String>()
        val decoratorText = decorator.text ?: ""
        val parenStart = decoratorText.indexOf('(')
        val parenEnd = decoratorText.lastIndexOf(')')
        if (parenStart >= 0 && parenEnd > parenStart) {
            val argText = decoratorText.substring(parenStart + 1, parenEnd).trim()
            if (argText.isNotBlank()) {
                // Simple key=value parsing
                argText.split(',').forEachIndexed { index, arg ->
                    val trimmed = arg.trim()
                    val eqIndex = trimmed.indexOf('=')
                    if (eqIndex > 0) {
                        val key = trimmed.substring(0, eqIndex).trim()
                        val value = trimmed.substring(eqIndex + 1).trim()
                        parameters[key] = value
                    } else if (trimmed.isNotBlank()) {
                        parameters["arg$index"] = trimmed
                    }
                }
            }
        }

        return MetadataInfo(
            name = name,
            qualifiedName = qualifiedName,
            parameters = parameters,
            isInherited = isInherited
        )
    }

    /**
     * Build a [DeclarationInfo] for a Python class, including children.
     */
    private fun buildClassDeclaration(
        pyClass: PsiElement,
        detail: DetailLevel,
        document: com.intellij.openapi.editor.Document?
    ): DeclarationInfo {
        val name = helper.getName(pyClass) ?: "?"
        val line = document?.getLineNumber(pyClass.textOffset)?.plus(1) ?: 0

        val children = mutableListOf<DeclarationInfo>()
        if (detail != DetailLevel.MINIMAL) {
            for (method in helper.getMethods(pyClass)) {
                val methodName = helper.getName(method) ?: continue
                val methodLine = document?.getLineNumber(method.textOffset)?.plus(1) ?: 0
                children.add(DeclarationInfo(
                    name = methodName,
                    kind = "method",
                    signature = buildFunctionSignature(method),
                    line = methodLine
                ))
            }
            for (attr in helper.getClassAttributes(pyClass)) {
                val attrName = helper.getName(attr) ?: continue
                val attrLine = document?.getLineNumber(attr.textOffset)?.plus(1) ?: 0
                children.add(DeclarationInfo(
                    name = attrName,
                    kind = "attribute",
                    signature = attrName,
                    line = attrLine
                ))
            }
        }

        return DeclarationInfo(
            name = name,
            kind = "class",
            signature = "class $name",
            line = line,
            children = children
        )
    }

    /**
     * Build a formatted text representation of the file structure.
     */
    private fun buildFormattedStructure(
        file: PsiFile,
        detail: DetailLevel,
        moduleName: String?,
        imports: List<String>,
        declarations: List<DeclarationInfo>
    ): String {
        return when (detail) {
            DetailLevel.FULL -> file.text?.take(50_000) ?: ""
            DetailLevel.MINIMAL -> {
                val sb = StringBuilder()
                if (moduleName != null) sb.appendLine("# module: $moduleName")
                declarations.forEach { decl ->
                    when (decl.kind) {
                        "class" -> sb.appendLine("${decl.name} // ${decl.children.size} members")
                        "function" -> sb.appendLine(decl.signature)
                        else -> sb.appendLine(decl.name)
                    }
                }
                sb.toString().trimEnd()
            }
            DetailLevel.SIGNATURES -> {
                val sb = StringBuilder()
                if (moduleName != null) sb.appendLine("# module: $moduleName")
                if (imports.isNotEmpty()) {
                    imports.forEach { sb.appendLine(it) }
                    sb.appendLine()
                }
                declarations.forEach { decl ->
                    when (decl.kind) {
                        "class" -> {
                            sb.appendLine("class ${decl.name}:")
                            if (decl.children.isEmpty()) {
                                sb.appendLine("    pass")
                            } else {
                                decl.children.forEach { child ->
                                    sb.appendLine("    ${child.signature}")
                                }
                            }
                            sb.appendLine()
                        }
                        "function" -> sb.appendLine(decl.signature)
                        else -> sb.appendLine(decl.name)
                    }
                }
                sb.toString().trimEnd()
            }
        }
    }

    /**
     * Scan for test files matching a source file name.
     * Looks for test_{name}.py and {name}_test.py patterns.
     */
    private fun scanForTestFiles(
        project: Project,
        scope: SearchScope,
        sourceFileName: String,
        related: MutableList<TestRelatedInfo>
    ) {
        val cache = PsiShortNamesCache.getInstance(project)
        val globalScope = scope as? GlobalSearchScope ?: GlobalSearchScope.projectScope(project)

        // Look for Test{SourceFileName} classes in test files
        val classes = cache.getClassesByName("Test${sourceFileName.replaceFirstChar { it.titlecase() }}", globalScope)
        for (cls in classes) {
            if (helper.isPyClass(cls) && related.none { it.name == (helper.getQualifiedName(cls) ?: helper.getName(cls)) }) {
                val filePath = cls.containingFile?.virtualFile?.path
                    ?.let { PsiToolUtils.relativePath(project, it) } ?: continue
                val doc = cls.containingFile?.let {
                    PsiDocumentManager.getInstance(project).getDocument(it)
                }
                val line = doc?.getLineNumber(cls.textOffset)?.plus(1) ?: 0
                related.add(TestRelatedInfo(
                    name = helper.getQualifiedName(cls) ?: helper.getName(cls) ?: "?",
                    filePath = filePath,
                    line = line,
                    kind = "test"
                ))
            }
        }
    }

    private fun getLineText(document: com.intellij.openapi.editor.Document, zeroBasedLine: Int): String {
        if (zeroBasedLine < 0 || zeroBasedLine >= document.lineCount) return ""
        return document.getText(
            TextRange(
                document.getLineStartOffset(zeroBasedLine),
                document.getLineEndOffset(zeroBasedLine)
            )
        )
    }

    companion object {
        private val IDENTIFIER_RE = Regex("^[a-zA-Z_][a-zA-Z0-9_.]*$")
    }
}
