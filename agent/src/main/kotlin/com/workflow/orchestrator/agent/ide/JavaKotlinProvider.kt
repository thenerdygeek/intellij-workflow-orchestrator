package com.workflow.orchestrator.agent.ide

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtil
import com.intellij.structuralsearch.MatchOptions
import com.intellij.structuralsearch.Matcher
import com.intellij.structuralsearch.plugin.util.CollectingMatchResultSink
import com.intellij.testIntegration.TestFinder
import com.workflow.orchestrator.agent.tools.psi.PsiToolUtils

/**
 * Java/Kotlin implementation of [LanguageIntelligenceProvider].
 *
 * All PSI logic is extracted from the existing 14 PSI tools and [PsiToolUtils].
 * Methods assume they are called inside a `ReadAction` (or equivalent read-lock).
 * The tools remain responsible for parameter parsing, path validation,
 * `ReadAction.nonBlocking`, dumb-mode checks, error handling, and formatting.
 */
class JavaKotlinProvider(private val project: Project) : LanguageIntelligenceProvider {

    override val supportedLanguageIds = setOf("JAVA", "kotlin")

    // ---------------------------------------------------------------------------
    // Symbol Resolution
    // ---------------------------------------------------------------------------

    /**
     * Find a named symbol by name in project scope.
     * Extracted from [FindDefinitionTool]: PsiToolUtils.findClassAnywhere, then
     * PsiShortNamesCache for methods/fields.
     */
    override fun findSymbol(project: Project, name: String): PsiElement? {
        // Try as class (FQN or simple name)
        PsiToolUtils.findClassAnywhere(project, name)?.let { return it }

        // Try "Class#method" or "Class.method" syntax
        val parts = name.split('#', '.')
        if (parts.size == 2) {
            val clazz = PsiToolUtils.findClassAnywhere(project, parts[0])
            clazz?.methods?.firstOrNull { it.name == parts[1] }?.let { return it }
            clazz?.fields?.firstOrNull { it.name == parts[1] }?.let { return it }
        }

        // Bare name fallback via PsiShortNamesCache
        val scope = GlobalSearchScope.projectScope(project)
        val cache = PsiShortNamesCache.getInstance(project)
        cache.getMethodsByName(name, scope).firstOrNull()?.let { return it }
        cache.getFieldsByName(name, scope).firstOrNull()?.let { return it }

        return null
    }

    /**
     * Find the declaration element at [offset] in [file].
     * Walks from the leaf upward to find the nearest declaration (variable,
     * parameter, field, method, class).
     */
    override fun findSymbolAt(file: PsiFile, offset: Int): PsiElement? {
        val leaf = file.findElementAt(offset) ?: return null
        // Walk up to find the enclosing declaration
        var current: PsiElement? = leaf.parent
        while (current != null && current !is PsiFile) {
            when (current) {
                is PsiLocalVariable, is PsiParameter, is PsiField, is PsiMethod, is PsiClass ->
                    return current
            }
            // Check Kotlin declarations via reflection
            if (isKotlinDeclaration(current)) return current
            current = current.parent
        }
        return null
    }

    /**
     * Get definition info for a resolved element.
     * Extracted from [FindDefinitionTool]: location, signature, docs.
     */
    override fun getDefinitionInfo(element: PsiElement): DefinitionInfo? {
        val containingFile = element.containingFile ?: return null
        val virtualFile = containingFile.virtualFile ?: return null
        val filePath = PsiToolUtils.relativePath(project, virtualFile.path)
        val document = PsiDocumentManager.getInstance(project).getDocument(containingFile)
        val line = document?.getLineNumber(element.textOffset)?.plus(1) ?: 0

        return when (element) {
            is PsiClass -> {
                val skeleton = PsiToolUtils.formatClassSkeleton(element)
                DefinitionInfo(
                    filePath = filePath,
                    line = line,
                    signature = element.qualifiedName ?: element.name ?: "anonymous",
                    documentation = extractDocComment(element),
                    skeleton = skeleton
                )
            }
            is PsiMethod -> {
                val signature = PsiToolUtils.formatMethodSignature(element)
                DefinitionInfo(
                    filePath = filePath,
                    line = line,
                    signature = signature,
                    documentation = extractDocComment(element)
                )
            }
            is PsiField -> {
                DefinitionInfo(
                    filePath = filePath,
                    line = line,
                    signature = "${element.type.presentableText} ${element.name}",
                    documentation = extractDocComment(element)
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
    }

    // ---------------------------------------------------------------------------
    // File Structure
    // ---------------------------------------------------------------------------

    /**
     * Get the structure of a file.
     * Extracted from [FileStructureTool]: PsiToolUtils.formatClassSkeleton /
     * formatKotlinFileStructure.
     */
    override fun getFileStructure(file: PsiFile, detail: DetailLevel): FileStructureResult {
        val detailStr = when (detail) {
            DetailLevel.MINIMAL -> "minimal"
            DetailLevel.SIGNATURES -> "signatures"
            DetailLevel.FULL -> "full"
        }

        val packageName: String?
        val imports: List<String>
        val declarations: List<DeclarationInfo>
        val formatted: String

        when {
            file is PsiJavaFile -> {
                packageName = file.packageName.ifBlank { null }
                imports = file.importList?.allImportStatements?.mapNotNull { it.text } ?: emptyList()
                declarations = file.classes.map { buildJavaClassDeclaration(it, detail) }
                formatted = when (detailStr) {
                    "full" -> file.text
                    "minimal" -> file.classes.joinToString("\n") { cls ->
                        "${cls.name} // ${cls.methods.size} methods, ${cls.fields.size} fields"
                    }
                    else -> file.classes.joinToString("\n\n") { PsiToolUtils.formatClassSkeleton(it) }
                }
            }
            file.javaClass.name.contains("KtFile") -> {
                packageName = extractKotlinPackageName(file)
                imports = extractKotlinImports(file)
                declarations = extractKotlinDeclarations(file, detail)
                formatted = PsiToolUtils.formatKotlinFileStructure(file, detailStr)
                    ?: "Error: Kotlin PSI unavailable"
            }
            else -> {
                packageName = null
                imports = emptyList()
                declarations = emptyList()
                val text = file.text ?: ""
                val lines = text.lines()
                formatted = if (lines.size > 100) {
                    lines.take(100).joinToString("\n") + "\n... (${lines.size - 100} more lines)"
                } else {
                    text
                }
            }
        }

        return FileStructureResult(
            packageOrModule = packageName,
            imports = imports,
            declarations = declarations,
            formatted = formatted
        )
    }

    // ---------------------------------------------------------------------------
    // Type System
    // ---------------------------------------------------------------------------

    /**
     * Get the type hierarchy (supertypes + subtypes) for a class element.
     * Extracted from [TypeHierarchyTool]: PsiClass.supers, ClassInheritorsSearch.
     */
    override fun getTypeHierarchy(element: PsiElement): TypeHierarchyResult? {
        val psiClass = element as? PsiClass ?: return null

        // Supertypes (recursive)
        val supertypes = mutableListOf<HierarchyEntry>()
        val visited = mutableSetOf<String>()
        collectSupertypes(psiClass, supertypes, visited)

        // Subtypes via ClassInheritorsSearch
        val scope = GlobalSearchScope.projectScope(project)
        val inheritors = ClassInheritorsSearch.search(psiClass, scope, true).findAll()
        val subtypes = inheritors.take(30).map { inheritor ->
            val filePath = inheritor.containingFile?.virtualFile?.path
                ?.let { PsiToolUtils.relativePath(project, it) }
            val doc = inheritor.containingFile?.let {
                PsiDocumentManager.getInstance(project).getDocument(it)
            }
            val line = doc?.getLineNumber(inheritor.textOffset)?.plus(1)
            HierarchyEntry(
                name = inheritor.name ?: "?",
                qualifiedName = inheritor.qualifiedName ?: inheritor.name ?: "?",
                filePath = filePath,
                line = line
            )
        }

        return TypeHierarchyResult(
            element = psiClass.qualifiedName ?: psiClass.name ?: "?",
            supertypes = supertypes,
            subtypes = subtypes
        )
    }

    /**
     * Find implementations of a class (inheritors) or method (overriders).
     * Extracted from [FindImplementationsTool]: OverridingMethodsSearch, ClassInheritorsSearch.
     */
    override fun findImplementations(element: PsiElement, scope: SearchScope): List<ImplementationInfo> {
        val globalScope = if (scope is GlobalSearchScope) scope
        else GlobalSearchScope.projectScope(project)

        when (element) {
            is PsiMethod -> {
                val overriders = OverridingMethodsSearch.search(element, globalScope, true).findAll()
                return overriders.take(40).map { overrider ->
                    val relativePath = PsiToolUtils.relativePath(
                        project, overrider.containingFile?.virtualFile?.path ?: ""
                    )
                    val doc = overrider.containingFile?.let {
                        PsiDocumentManager.getInstance(project).getDocument(it)
                    }
                    val line = doc?.getLineNumber(overrider.textOffset)?.plus(1) ?: 0
                    ImplementationInfo(
                        name = "${overrider.containingClass?.name ?: "?"}.${overrider.name}",
                        signature = PsiToolUtils.formatMethodSignature(overrider),
                        filePath = relativePath,
                        line = line
                    )
                }
            }
            is PsiClass -> {
                val inheritors = ClassInheritorsSearch.search(element, globalScope, true).findAll()
                return inheritors.take(40).map { inheritor ->
                    val relativePath = PsiToolUtils.relativePath(
                        project, inheritor.containingFile?.virtualFile?.path ?: ""
                    )
                    val doc = inheritor.containingFile?.let {
                        PsiDocumentManager.getInstance(project).getDocument(it)
                    }
                    val line = doc?.getLineNumber(inheritor.textOffset)?.plus(1) ?: 0
                    ImplementationInfo(
                        name = inheritor.name ?: "?",
                        signature = inheritor.qualifiedName ?: inheritor.name ?: "?",
                        filePath = relativePath,
                        line = line
                    )
                }
            }
            else -> return emptyList()
        }
    }

    /**
     * Infer the type of an expression, variable, or parameter at the given element.
     * Extracted from [TypeInferenceTool]: PsiLocalVariable.type, PsiParameter.type,
     * PsiField.type, PsiMethod.returnType, PsiExpression.type, plus Kotlin via reflection.
     */
    override fun inferType(element: PsiElement): TypeInferenceResult? {
        // Java types
        resolveJavaType(element)?.let { return it }

        // Kotlin types via reflection
        resolveKotlinTypeResult(element)?.let { return it }

        return null
    }

    /**
     * Analyze dataflow for an expression (nullability, range, constant value).
     * Extracted from [DataFlowAnalysisTool]: reflection-based CommonDataflow.getDfType().
     * Java only.
     */
    override fun analyzeDataflow(element: PsiElement): DataflowResult? {
        val expression = when (element) {
            is PsiExpression -> element
            else -> PsiTreeUtil.getParentOfType(element, PsiExpression::class.java, false)
        } ?: return null

        val exprType = expression.type

        try {
            val commonDataflowClass = Class.forName("com.intellij.codeInspection.dataFlow.CommonDataflow")

            // Nullability via DfType
            val nullability = try {
                val getDfTypeMethod = commonDataflowClass.getMethod("getDfType", PsiExpression::class.java)
                val dfType = getDfTypeMethod.invoke(null, expression)
                if (dfType != null) {
                    val dfTypeClass = Class.forName("com.intellij.codeInspection.dataFlow.types.DfType")
                    val topValue = dfTypeClass.getField("TOP").get(null)
                    resolveNullability(dfType, topValue, exprType)
                } else {
                    Nullability.UNKNOWN
                }
            } catch (_: Exception) {
                if (exprType is PsiPrimitiveType) Nullability.NOT_NULL else Nullability.UNKNOWN
            }

            // Range
            val valueRange = try {
                if (exprType != null && isNumericType(exprType)) {
                    val getRangeMethod = commonDataflowClass.getMethod(
                        "getExpressionRange", PsiExpression::class.java
                    )
                    getRangeMethod.invoke(null, expression)?.toString()
                } else null
            } catch (_: Exception) { null }

            // Constant value
            val constantValue = try {
                val computeValueMethod = commonDataflowClass.getMethod(
                    "computeValue", PsiExpression::class.java
                )
                computeValueMethod.invoke(null, expression)?.toString()
            } catch (_: Exception) { null }

            return DataflowResult(
                nullability = nullability,
                valueRange = valueRange,
                constantValue = constantValue
            )
        } catch (_: ClassNotFoundException) {
            return null
        }
    }

    // ---------------------------------------------------------------------------
    // Call Graph
    // ---------------------------------------------------------------------------

    /**
     * Find callers of a method with recursive depth support.
     * Extracted from [CallHierarchyTool]: ReferencesSearch with recursive depth.
     */
    override fun findCallers(element: PsiElement, depth: Int, scope: SearchScope): List<CallerInfo> {
        val method = element as? PsiMethod ?: return emptyList()
        val callers = mutableListOf<CallerInfo>()
        val maxDepth = depth.coerceIn(1, 3)

        collectCallers(method, 1, maxDepth, scope, callers)
        return callers
    }

    /**
     * Find callees within a method body.
     * Extracted from [CallHierarchyTool]: PsiTreeUtil.findChildrenOfType(PsiMethodCallExpression)
     * plus Kotlin call expressions via reflection.
     */
    override fun findCallees(element: PsiElement): List<CalleeInfo> {
        val method = element as? PsiMethod ?: return emptyList()
        val callees = mutableListOf<CalleeInfo>()

        // Java call expressions
        val callExpressions = PsiTreeUtil.findChildrenOfType(method, PsiMethodCallExpression::class.java)
        for (call in callExpressions) {
            val resolved = call.resolveMethod() ?: continue
            val calleeName = "${resolved.containingClass?.name ?: ""}#${resolved.name}"
            val calleeFile = resolved.containingFile?.virtualFile?.path
                ?.let { PsiToolUtils.relativePath(project, it) }
            val calleeLine = resolved.containingFile?.let {
                PsiDocumentManager.getInstance(project).getDocument(it)
            }?.getLineNumber(resolved.textOffset)?.plus(1)
            callees.add(CalleeInfo(name = calleeName, filePath = calleeFile, line = calleeLine))
        }

        // Kotlin call expressions via reflection
        try {
            @Suppress("UNCHECKED_CAST")
            val ktCallClass = Class.forName("org.jetbrains.kotlin.psi.KtCallExpression") as Class<PsiElement>
            val ktCalls = PsiTreeUtil.findChildrenOfType(method, ktCallClass)
            for (call in ktCalls) {
                val ref = (call as PsiElement).references.firstOrNull()
                val resolved = ref?.resolve()
                if (resolved is PsiMethod) {
                    val calleeName = "${resolved.containingClass?.name ?: ""}#${resolved.name}"
                    val calleeFile = resolved.containingFile?.virtualFile?.path
                        ?.let { PsiToolUtils.relativePath(project, it) }
                    val calleeLine = resolved.containingFile?.let {
                        PsiDocumentManager.getInstance(project).getDocument(it)
                    }?.getLineNumber(resolved.textOffset)?.plus(1)
                    callees.add(CalleeInfo(name = calleeName, filePath = calleeFile, line = calleeLine))
                }
            }
        } catch (_: ClassNotFoundException) { /* Kotlin plugin not available */ }

        // Deduplicate by name
        return callees.distinctBy { it.name }
    }

    // ---------------------------------------------------------------------------
    // Metadata
    // ---------------------------------------------------------------------------

    /**
     * Get annotations on an element, optionally including inherited ones.
     * Extracted from [GetAnnotationsTool]: PsiModifierListOwner.annotations +
     * superclass walk.
     */
    override fun getMetadata(element: PsiElement, includeInherited: Boolean): List<MetadataInfo> {
        val owner = element as? PsiModifierListOwner ?: return emptyList()
        val result = mutableListOf<MetadataInfo>()

        // Direct annotations
        for (annotation in owner.annotations) {
            result.add(annotationToMetadataInfo(annotation, isInherited = false))
        }

        // Walk superclass chain if requested and element is a class
        if (includeInherited && element is PsiClass) {
            val seen = mutableSetOf<String>()
            owner.annotations.forEach { a -> a.qualifiedName?.let { seen.add(it) } }

            var cursor: PsiClass? = element.superClass
            while (cursor != null && cursor.qualifiedName != "java.lang.Object") {
                for (annotation in cursor.annotations) {
                    val qName = annotation.qualifiedName ?: continue
                    if (seen.add(qName)) {
                        result.add(annotationToMetadataInfo(annotation, isInherited = true))
                    }
                }
                cursor = cursor.superClass
            }
        }

        return result
    }

    /**
     * Get the source text of a method body with optional context lines.
     * Extracted from [GetMethodBodyTool]: PsiMethod.textRange with document context.
     */
    override fun getBody(element: PsiElement, contextLines: Int): BodyResult? {
        val method = element as? PsiMethod ?: return null
        val containingFile = method.containingFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(containingFile)
            ?: return null

        val methodStartOffset = method.textRange.startOffset
        val methodEndOffset = method.textRange.endOffset

        val methodStartLine = document.getLineNumber(methodStartOffset)
        val methodEndLine = document.getLineNumber(methodEndOffset)

        val clampedContext = contextLines.coerceIn(0, 5)
        val rangeStart = maxOf(0, methodStartLine - clampedContext)
        val rangeEnd = minOf(document.lineCount - 1, methodEndLine + clampedContext)

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
    }

    // ---------------------------------------------------------------------------
    // Access Analysis
    // ---------------------------------------------------------------------------

    /**
     * Classify usages of a variable/field/parameter as read, write, or read-write.
     * Extracted from [ReadWriteAccessTool]: PsiUtil.isAccessedForWriting/Reading
     * plus Kotlin assignment detection via reflection.
     */
    override fun classifyAccesses(element: PsiElement, scope: SearchScope): AccessClassification {
        val target = resolveAccessTarget(element) ?: return AccessClassification(
            reads = emptyList(), writes = emptyList(), readWrites = emptyList()
        )

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

            when (classifyAccessType(refElement)) {
                AccessType.WRITE -> writes.add(info)
                AccessType.READ -> reads.add(info)
                AccessType.READ_WRITE -> {
                    readWrites.add(info)
                    writes.add(info)
                    reads.add(info)
                }
            }
        }

        return AccessClassification(reads = reads, writes = writes, readWrites = readWrites)
    }

    // ---------------------------------------------------------------------------
    // Test Discovery
    // ---------------------------------------------------------------------------

    /**
     * Find tests associated with a source element, or source for a test.
     * Extracted from [TestFinderTool]: TestFinder.EP_NAME extensions.
     */
    override fun findRelatedTests(element: PsiElement): TestRelationResult {
        val targetClass = when (element) {
            is PsiClass -> element
            else -> PsiTreeUtil.getParentOfType(element, PsiClass::class.java)
        } ?: return TestRelationResult(isTestElement = false, relatedElements = emptyList())

        val finders = TestFinder.EP_NAME.extensionList
        if (finders.isEmpty()) {
            return TestRelationResult(isTestElement = false, relatedElements = emptyList())
        }

        val isTest = finders.any { finder ->
            try { finder.isTest(targetClass) } catch (_: Exception) { false }
        }

        val related = mutableListOf<TestRelatedInfo>()

        if (isTest) {
            // Find source classes for this test
            for (finder in finders) {
                try {
                    val classes = finder.findClassesForTest(targetClass)
                    for (e in classes) {
                        if (e is PsiClass && related.none { it.name == (e.qualifiedName ?: e.name) }) {
                            val path = e.containingFile?.virtualFile?.path
                                ?.let { PsiToolUtils.relativePath(project, it) } ?: "unknown"
                            val doc = e.containingFile?.let {
                                PsiDocumentManager.getInstance(project).getDocument(it)
                            }
                            val line = doc?.getLineNumber(e.textOffset)?.plus(1) ?: 0
                            related.add(TestRelatedInfo(
                                name = e.qualifiedName ?: e.name ?: "?",
                                filePath = path,
                                line = line,
                                kind = "source"
                            ))
                        }
                    }
                } catch (_: Exception) { }
            }
        } else {
            // Find test classes for this source
            for (finder in finders) {
                try {
                    val tests = finder.findTestsForClass(targetClass)
                    for (e in tests) {
                        if (e is PsiClass && related.none { it.name == (e.qualifiedName ?: e.name) }) {
                            val path = e.containingFile?.virtualFile?.path
                                ?.let { PsiToolUtils.relativePath(project, it) } ?: "unknown"
                            val doc = e.containingFile?.let {
                                PsiDocumentManager.getInstance(project).getDocument(it)
                            }
                            val line = doc?.getLineNumber(e.textOffset)?.plus(1) ?: 0
                            related.add(TestRelatedInfo(
                                name = e.qualifiedName ?: e.name ?: "?",
                                filePath = path,
                                line = line,
                                kind = "test"
                            ))
                        }
                    }
                } catch (_: Exception) { }
            }
        }

        return TestRelationResult(isTestElement = isTest, relatedElements = related)
    }

    // ---------------------------------------------------------------------------
    // Diagnostics
    // ---------------------------------------------------------------------------

    /**
     * Find syntax errors and unresolved references in a file.
     * Extracted from [SemanticDiagnosticsTool]: PsiErrorElement walking +
     * unresolved reference detection.
     */
    override fun getDiagnostics(file: PsiFile, lineRange: IntRange?): List<DiagnosticInfo> {
        val virtualFile = file.virtualFile ?: return emptyList()
        val filePath = PsiToolUtils.relativePath(project, virtualFile.path)
        val document = file.viewProvider.document
        val allProblems = mutableListOf<DiagnosticInfo>()

        // Syntax errors (PsiErrorElement)
        PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java).forEach { error ->
            val line = document?.getLineNumber(error.textOffset)?.plus(1) ?: 0
            allProblems.add(DiagnosticInfo(
                message = "Syntax error — ${error.errorDescription}",
                severity = "ERROR",
                line = line,
                filePath = filePath
            ))
        }

        // Unresolved references
        val unresolvedSeen = mutableSetOf<String>()
        file.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element is PsiComment) return
                if (element is PsiLiteralExpression && element.value is String) return

                val simpleName = element.javaClass.simpleName
                if (simpleName == "KtStringTemplateExpression" ||
                    simpleName == "KtLiteralStringTemplateEntry") return

                super.visitElement(element)

                for (ref in element.references) {
                    if (ref.isSoft) continue
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
                            && !text.startsWith("kotlin.")
                            && !text.startsWith("java.lang.")
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
        })

        // Filter to line range if provided
        return if (lineRange != null) {
            allProblems.filter { it.line in lineRange }
        } else {
            allProblems
        }
    }

    // ---------------------------------------------------------------------------
    // Structural Search
    // ---------------------------------------------------------------------------

    /**
     * Search for structural patterns.
     * Extracted from [StructuralSearchTool]: MatchOptions + Matcher.
     */
    override fun structuralSearch(
        project: Project,
        pattern: String,
        scope: SearchScope
    ): List<StructuralMatchInfo>? {
        val fileType = com.intellij.ide.highlighter.JavaFileType.INSTANCE
        val globalScope = if (scope is GlobalSearchScope) scope
        else GlobalSearchScope.projectScope(project)

        return try {
            val matchOptions = MatchOptions().apply {
                setSearchPattern(pattern)
                setFileType(fileType)
                setRecursiveSearch(true)
                this.scope = globalScope
            }

            val sink = CollectingMatchResultSink()
            val matcher = Matcher(project, matchOptions)
            matcher.findMatches(sink)

            sink.matches.take(50).mapNotNull { result ->
                val match = result.match ?: return@mapNotNull null
                val psiFile = match.containingFile ?: return@mapNotNull null
                val vf = psiFile.virtualFile ?: return@mapNotNull null
                val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
                val lineNumber = document?.getLineNumber(match.textOffset)?.plus(1) ?: 0
                val relativePath = PsiToolUtils.relativePath(project, vf.path)
                val matchedText = result.matchImage ?: match.text

                StructuralMatchInfo(
                    matchedText = matchedText.take(100),
                    filePath = relativePath,
                    line = lineNumber
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private fun extractDocComment(element: PsiElement): String? {
        val docComment = when (element) {
            is PsiDocCommentOwner -> element.docComment?.text
            else -> null
        }
        return docComment?.takeIf { it.isNotBlank() }
    }

    /**
     * Recursively collect supertypes, skipping java.lang.Object.
     * Ported from [TypeHierarchyTool.collectSupertypes].
     */
    private fun collectSupertypes(
        psiClass: PsiClass,
        result: MutableList<HierarchyEntry>,
        visited: MutableSet<String>
    ) {
        for (superType in psiClass.supers) {
            val qName = superType.qualifiedName ?: superType.name ?: continue
            if (qName == "java.lang.Object") continue
            if (qName in visited) continue
            visited.add(qName)
            val filePath = superType.containingFile?.virtualFile?.path
                ?.let { PsiToolUtils.relativePath(project, it) }
            val doc = superType.containingFile?.let {
                PsiDocumentManager.getInstance(project).getDocument(it)
            }
            val line = doc?.getLineNumber(superType.textOffset)?.plus(1)
            result.add(HierarchyEntry(
                name = superType.name ?: qName,
                qualifiedName = qName,
                filePath = filePath,
                line = line
            ))
            collectSupertypes(superType, result, visited)
        }
    }

    /**
     * Recursive caller collection. Ported from [CallHierarchyTool].
     */
    private fun collectCallers(
        method: PsiMethod,
        currentDepth: Int,
        maxDepth: Int,
        scope: SearchScope,
        result: MutableList<CallerInfo>
    ) {
        if (currentDepth > maxDepth) return
        val refs = ReferencesSearch.search(method, scope).findAll()
        val limit = if (currentDepth == 1) 30 else 10
        for (ref in refs.take(limit)) {
            val element = ref.element
            val containingMethod = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
            val callerName = if (containingMethod != null) {
                "${containingMethod.containingClass?.name ?: ""}#${containingMethod.name}"
            } else {
                "(top-level)"
            }
            val file = element.containingFile?.virtualFile?.path
                ?.let { PsiToolUtils.relativePath(project, it) } ?: ""
            val doc = element.containingFile?.let {
                PsiDocumentManager.getInstance(project).getDocument(it)
            }
            val line = doc?.getLineNumber(element.textOffset)?.plus(1) ?: 0
            result.add(CallerInfo(name = callerName, filePath = file, line = line, depth = currentDepth))

            if (containingMethod != null && currentDepth < maxDepth) {
                collectCallers(containingMethod, currentDepth + 1, maxDepth, scope, result)
            }
        }
    }

    // --- Java type inference helpers (from TypeInferenceTool) ---

    private fun resolveJavaType(element: PsiElement): TypeInferenceResult? {
        PsiTreeUtil.getParentOfType(element, PsiLocalVariable::class.java, false)?.let { variable ->
            return psiTypeToResult(variable.type)
        }
        PsiTreeUtil.getParentOfType(element, PsiParameter::class.java, false)?.let { param ->
            return psiTypeToResult(param.type)
        }
        PsiTreeUtil.getParentOfType(element, PsiField::class.java, false)?.let { field ->
            return psiTypeToResult(field.type)
        }
        PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false)?.let { method ->
            val returnType = method.returnType ?: return null
            return psiTypeToResult(returnType)
        }
        PsiTreeUtil.getParentOfType(element, PsiExpression::class.java, false)?.let { expr ->
            val type = expr.type ?: return null
            return psiTypeToResult(type)
        }
        return null
    }

    private fun psiTypeToResult(type: PsiType): TypeInferenceResult {
        return TypeInferenceResult(
            typeName = type.presentableText,
            qualifiedName = type.canonicalText,
            nullability = inferAnnotationNullability(type)
        )
    }

    private fun inferAnnotationNullability(type: PsiType): Nullability {
        for (annotation in type.annotations) {
            val qName = annotation.qualifiedName ?: continue
            val simpleName = qName.substringAfterLast('.')
            when {
                simpleName == "Nullable" || qName.endsWith(".Nullable") -> return Nullability.NULLABLE
                simpleName == "NotNull" || simpleName == "NonNull"
                        || qName.endsWith(".NotNull") || qName.endsWith(".NonNull") -> return Nullability.NOT_NULL
            }
        }
        return when (type) {
            is PsiPrimitiveType -> Nullability.NOT_NULL
            // Java reference types without nullability annotation are "platform" types
            else -> Nullability.PLATFORM
        }
    }

    // --- Kotlin type inference helpers (from TypeInferenceTool) ---

    private fun resolveKotlinTypeResult(element: PsiElement): TypeInferenceResult? {
        return try {
            val ktPropertyClass = Class.forName("org.jetbrains.kotlin.psi.KtProperty")
            val ktParameterClass = Class.forName("org.jetbrains.kotlin.psi.KtParameter")
            val ktNamedFunctionClass = Class.forName("org.jetbrains.kotlin.psi.KtNamedFunction")

            val ktProperty = findParentOfKotlinType(element, ktPropertyClass)
            if (ktProperty != null) {
                val typeText = getKotlinTypeRefText(ktProperty, ktPropertyClass) ?: return null
                return kotlinTypeToResult(typeText)
            }

            val ktParam = findParentOfKotlinType(element, ktParameterClass)
            if (ktParam != null) {
                val typeText = getKotlinTypeRefText(ktParam, ktParameterClass) ?: return null
                return kotlinTypeToResult(typeText)
            }

            val ktFunction = findParentOfKotlinType(element, ktNamedFunctionClass)
            if (ktFunction != null) {
                val typeText = try {
                    val getTypeReference = ktNamedFunctionClass.getMethod("getTypeReference")
                    val ref = getTypeReference.invoke(ktFunction)
                    ref?.let { it.javaClass.getMethod("getText").invoke(it) as? String }
                } catch (_: Exception) { null } ?: return null
                return kotlinTypeToResult(typeText)
            }

            null
        } catch (_: ClassNotFoundException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun kotlinTypeToResult(typeText: String): TypeInferenceResult {
        val nullability = if (typeText.endsWith("?")) Nullability.NULLABLE else Nullability.NOT_NULL
        return TypeInferenceResult(
            typeName = typeText,
            qualifiedName = null, // Kotlin type ref text is usually not fully qualified
            nullability = nullability
        )
    }

    private fun findParentOfKotlinType(element: PsiElement, ktClass: Class<*>): Any? {
        var current: PsiElement? = element
        while (current != null) {
            if (ktClass.isInstance(current)) return current
            current = current.parent
        }
        return null
    }

    private fun getKotlinTypeRefText(element: Any, elementClass: Class<*>): String? {
        return try {
            val getTypeReference = elementClass.getMethod("getTypeReference")
            val ref = getTypeReference.invoke(element)
            ref?.let { it.javaClass.getMethod("getText").invoke(it) as? String }
        } catch (_: Exception) {
            null
        }
    }

    // --- Dataflow helpers (from DataFlowAnalysisTool) ---

    private fun resolveNullability(dfType: Any, topValue: Any?, exprType: PsiType?): Nullability {
        if (dfType == topValue) return Nullability.UNKNOWN

        return try {
            val dfaNullabilityClass = Class.forName("com.intellij.codeInspection.dataFlow.DfaNullability")
            val fromDfTypeMethod = dfaNullabilityClass.getMethod(
                "fromDfType",
                Class.forName("com.intellij.codeInspection.dataFlow.types.DfType")
            )
            val nullability = fromDfTypeMethod.invoke(null, dfType)
            val nullabilityName = nullability.toString()

            when {
                nullabilityName.contains("NULLABLE", ignoreCase = true) -> Nullability.NULLABLE
                nullabilityName.contains("NOT_NULL", ignoreCase = true) -> Nullability.NOT_NULL
                else -> Nullability.UNKNOWN
            }
        } catch (_: Exception) {
            if (exprType is PsiPrimitiveType) Nullability.NOT_NULL else Nullability.UNKNOWN
        }
    }

    private fun isNumericType(type: PsiType): Boolean {
        return type.canonicalText in setOf(
            "int", "long", "short", "byte", "float", "double", "char",
            "java.lang.Integer", "java.lang.Long", "java.lang.Short",
            "java.lang.Byte", "java.lang.Float", "java.lang.Double",
            "java.lang.Character"
        )
    }

    // --- Access classification helpers (from ReadWriteAccessTool) ---

    private enum class AccessType { READ, WRITE, READ_WRITE }

    private fun resolveAccessTarget(element: PsiElement): PsiElement? {
        PsiTreeUtil.getParentOfType(element, PsiLocalVariable::class.java, false)?.let { return it }
        PsiTreeUtil.getParentOfType(element, PsiParameter::class.java, false)?.let { return it }
        PsiTreeUtil.getParentOfType(element, PsiField::class.java, false)?.let { return it }

        // Kotlin targets via reflection
        return try {
            val ktPropertyClass = Class.forName("org.jetbrains.kotlin.psi.KtProperty")
            val ktParameterClass = Class.forName("org.jetbrains.kotlin.psi.KtParameter")
            var current: PsiElement? = element
            while (current != null) {
                if (ktPropertyClass.isInstance(current) || ktParameterClass.isInstance(current)) {
                    return current
                }
                current = current.parent
            }
            null
        } catch (_: ClassNotFoundException) {
            null
        }
    }

    private fun classifyAccessType(element: PsiElement): AccessType {
        val expression = PsiTreeUtil.getParentOfType(element, PsiExpression::class.java, false)
        if (expression != null) {
            val isWrite = PsiUtil.isAccessedForWriting(expression)
            val isRead = PsiUtil.isAccessedForReading(expression)
            return when {
                isWrite && isRead -> AccessType.READ_WRITE
                isWrite -> AccessType.WRITE
                else -> AccessType.READ
            }
        }
        return classifyKotlinAccess(element)
    }

    private fun classifyKotlinAccess(element: PsiElement): AccessType {
        return try {
            val ktBinaryExprClass = Class.forName("org.jetbrains.kotlin.psi.KtBinaryExpression")
            val ktUnaryExprClass = Class.forName("org.jetbrains.kotlin.psi.KtUnaryExpression")

            var current: PsiElement? = element.parent
            while (current != null) {
                if (ktBinaryExprClass.isInstance(current)) {
                    val getOperationRef = ktBinaryExprClass.getMethod("getOperationReference")
                    val opRef = getOperationRef.invoke(current)
                    val opText = opRef?.javaClass?.getMethod("getText")?.invoke(opRef) as? String

                    val getLeft = ktBinaryExprClass.getMethod("getLeft")
                    val left = getLeft.invoke(current)

                    val isOnLeft = if (left is PsiElement) {
                        PsiTreeUtil.isAncestor(left, element, false)
                    } else false

                    if (isOnLeft) {
                        return when (opText) {
                            "=" -> AccessType.WRITE
                            "+=", "-=", "*=", "/=", "%=" -> AccessType.READ_WRITE
                            else -> AccessType.READ
                        }
                    }
                    break
                }

                if (ktUnaryExprClass.isInstance(current)) {
                    val getOpText = try {
                        val getOperationRef = ktUnaryExprClass.getMethod("getOperationReference")
                        val opRef = getOperationRef.invoke(current)
                        opRef?.javaClass?.getMethod("getText")?.invoke(opRef) as? String
                    } catch (_: Exception) { null }

                    if (getOpText == "++" || getOpText == "--") {
                        return AccessType.READ_WRITE
                    }
                    break
                }

                current = current.parent
            }
            AccessType.READ
        } catch (_: ClassNotFoundException) {
            AccessType.READ
        } catch (_: Exception) {
            AccessType.READ
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

    // --- Kotlin declaration detection ---

    private fun isKotlinDeclaration(element: PsiElement): Boolean {
        return try {
            val ktClassClass = Class.forName("org.jetbrains.kotlin.psi.KtClass")
            val ktFunctionClass = Class.forName("org.jetbrains.kotlin.psi.KtNamedFunction")
            val ktPropertyClass = Class.forName("org.jetbrains.kotlin.psi.KtProperty")
            val ktParameterClass = Class.forName("org.jetbrains.kotlin.psi.KtParameter")
            ktClassClass.isInstance(element) ||
                ktFunctionClass.isInstance(element) ||
                ktPropertyClass.isInstance(element) ||
                ktParameterClass.isInstance(element)
        } catch (_: ClassNotFoundException) {
            false
        }
    }

    // --- Kotlin file structure helpers ---

    private fun extractKotlinPackageName(file: PsiFile): String? {
        return try {
            val ktFileClass = Class.forName("org.jetbrains.kotlin.psi.KtFile")
            if (!ktFileClass.isInstance(file)) return null
            val getPackageFqName = ktFileClass.getMethod("getPackageFqName")
            val fqName = getPackageFqName.invoke(file)
            val asString = fqName.javaClass.getMethod("asString")
            (asString.invoke(fqName) as? String)?.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractKotlinImports(file: PsiFile): List<String> {
        return try {
            val ktFileClass = Class.forName("org.jetbrains.kotlin.psi.KtFile")
            if (!ktFileClass.isInstance(file)) return emptyList()
            val getImportDirectives = ktFileClass.getMethod("getImportDirectives")
            @Suppress("UNCHECKED_CAST")
            val imports = getImportDirectives.invoke(file) as? List<*> ?: return emptyList()
            imports.mapNotNull { imp ->
                try {
                    imp?.javaClass?.getMethod("getText")?.invoke(imp) as? String
                } catch (_: Exception) { null }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun extractKotlinDeclarations(file: PsiFile, detail: DetailLevel): List<DeclarationInfo> {
        return try {
            val ktFileClass = Class.forName("org.jetbrains.kotlin.psi.KtFile")
            if (!ktFileClass.isInstance(file)) return emptyList()
            val getDeclarations = ktFileClass.getMethod("getDeclarations")
            val declarations = getDeclarations.invoke(file) as? List<*> ?: return emptyList()
            val document = (file as? PsiFile)?.viewProvider?.document
            declarations.mapNotNull { decl ->
                if (decl == null) return@mapNotNull null
                val name = try {
                    decl.javaClass.getMethod("getName").invoke(decl) as? String
                } catch (_: Exception) { null } ?: return@mapNotNull null
                val kind = resolveKotlinDeclarationKind(decl)
                val line = if (decl is PsiElement && document != null) {
                    document.getLineNumber((decl as PsiElement).textOffset) + 1
                } else 0
                DeclarationInfo(
                    name = name,
                    kind = kind,
                    signature = name, // Simplified for structured data
                    line = line
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun resolveKotlinDeclarationKind(decl: Any): String {
        return try {
            val ktClassClass = Class.forName("org.jetbrains.kotlin.psi.KtClass")
            val ktObjectClass = Class.forName("org.jetbrains.kotlin.psi.KtObjectDeclaration")
            val ktFunctionClass = Class.forName("org.jetbrains.kotlin.psi.KtNamedFunction")
            val ktPropertyClass = Class.forName("org.jetbrains.kotlin.psi.KtProperty")
            when {
                ktClassClass.isInstance(decl) -> "class"
                ktObjectClass.isInstance(decl) -> "object"
                ktFunctionClass.isInstance(decl) -> "function"
                ktPropertyClass.isInstance(decl) -> "property"
                else -> "unknown"
            }
        } catch (_: ClassNotFoundException) {
            "unknown"
        }
    }

    // --- Java file structure helpers ---

    private fun buildJavaClassDeclaration(psiClass: PsiClass, detail: DetailLevel): DeclarationInfo {
        val document = psiClass.containingFile?.let {
            PsiDocumentManager.getInstance(project).getDocument(it)
        }
        val line = document?.getLineNumber(psiClass.textOffset)?.plus(1) ?: 0

        val children = when (detail) {
            DetailLevel.MINIMAL -> emptyList()
            else -> {
                val methodDecls = psiClass.methods.map { method ->
                    val methodLine = document?.getLineNumber(method.textOffset)?.plus(1) ?: 0
                    DeclarationInfo(
                        name = method.name,
                        kind = "method",
                        signature = PsiToolUtils.formatMethodSignature(method),
                        line = methodLine
                    )
                }
                val fieldDecls = psiClass.fields.map { field ->
                    val fieldLine = document?.getLineNumber(field.textOffset)?.plus(1) ?: 0
                    DeclarationInfo(
                        name = field.name,
                        kind = "field",
                        signature = "${field.type.presentableText} ${field.name}",
                        line = fieldLine
                    )
                }
                fieldDecls + methodDecls
            }
        }

        val classKind = when {
            psiClass.isInterface -> "interface"
            psiClass.isEnum -> "enum"
            psiClass.isAnnotationType -> "annotation"
            else -> "class"
        }

        return DeclarationInfo(
            name = psiClass.name ?: "?",
            kind = classKind,
            signature = psiClass.qualifiedName ?: psiClass.name ?: "?",
            line = line,
            children = children
        )
    }

    private fun annotationToMetadataInfo(annotation: PsiAnnotation, isInherited: Boolean): MetadataInfo {
        val paramList = annotation.parameterList.attributes
        val params = paramList.associate { attr ->
            val name = attr.name ?: "value"
            val value = attr.literalValue ?: attr.value?.text ?: "?"
            name to value
        }
        return MetadataInfo(
            name = annotation.qualifiedName?.substringAfterLast('.') ?: annotation.text,
            qualifiedName = annotation.qualifiedName ?: "",
            parameters = params,
            isInherited = isInherited
        )
    }

    companion object {
        /**
         * Matches plain Java/Kotlin identifier references and qualified names.
         * Ported from [SemanticDiagnosticsTool].
         */
        private val IDENTIFIER_RE = Regex("""[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*""")
    }
}
