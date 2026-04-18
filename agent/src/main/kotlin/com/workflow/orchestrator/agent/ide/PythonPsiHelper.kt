package com.workflow.orchestrator.agent.ide

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.SearchScope

/**
 * Reflection-based access to Python PSI classes from the PythonCore plugin.
 * All class loading is lazy and cached — if the Python plugin is not installed,
 * [isAvailable] returns false and all accessors return null.
 *
 * This avoids a compile-time dependency on the Python plugin, matching the
 * pattern used by [JavaKotlinProvider] for Kotlin PSI access.
 */
class PythonPsiHelper {

    companion object {
        const val PY_FILE_CLASS = "com.jetbrains.python.psi.PyFile"
        const val PY_CLASS_CLASS = "com.jetbrains.python.psi.PyClass"
        const val PY_FUNCTION_CLASS = "com.jetbrains.python.psi.PyFunction"
        const val PY_TARGET_EXPRESSION_CLASS = "com.jetbrains.python.psi.PyTargetExpression"
        const val PY_DECORATOR_CLASS = "com.jetbrains.python.psi.PyDecorator"
        const val PY_DECORATOR_LIST_CLASS = "com.jetbrains.python.psi.PyDecoratorList"
        const val PY_IMPORT_STATEMENT_CLASS = "com.jetbrains.python.psi.PyImportStatement"
        const val PY_FROM_IMPORT_STATEMENT_CLASS = "com.jetbrains.python.psi.PyFromImportStatement"
        const val PY_PARAMETER_CLASS = "com.jetbrains.python.psi.PyParameter"
        const val PY_PARAMETER_LIST_CLASS = "com.jetbrains.python.psi.PyParameterList"
        const val PY_ASSIGNMENT_STATEMENT_CLASS = "com.jetbrains.python.psi.PyAssignmentStatement"
        const val PY_AUG_ASSIGNMENT_STATEMENT_CLASS = "com.jetbrains.python.psi.PyAugAssignmentStatement"
        const val PY_CALL_EXPRESSION_CLASS = "com.jetbrains.python.psi.PyCallExpression"
        const val PY_REFERENCE_EXPRESSION_CLASS = "com.jetbrains.python.psi.PyReferenceExpression"
        const val PY_STRING_LITERAL_CLASS = "com.jetbrains.python.psi.PyStringLiteralExpression"
        const val PY_TYPED_ELEMENT_CLASS = "com.jetbrains.python.psi.PyTypedElement"
        const val PY_RESOLVE_CONTEXT_CLASS = "com.jetbrains.python.psi.resolve.PyResolveContext"
        const val TYPE_EVAL_CONTEXT_CLASS = "com.jetbrains.python.psi.types.TypeEvalContext"
        const val PY_CLASS_INHERITORS_SEARCH = "com.jetbrains.python.psi.search.PyClassInheritorsSearch"

        fun loadClass(fqn: String): Class<*>? =
            try { Class.forName(fqn) } catch (_: ClassNotFoundException) { null }
    }

    // Lazy-loaded Python PSI classes
    val pyFileClass by lazy { loadClass(PY_FILE_CLASS) }
    val pyClassClass by lazy { loadClass(PY_CLASS_CLASS) }
    val pyFunctionClass by lazy { loadClass(PY_FUNCTION_CLASS) }
    val pyTargetExpressionClass by lazy { loadClass(PY_TARGET_EXPRESSION_CLASS) }
    val pyDecoratorClass by lazy { loadClass(PY_DECORATOR_CLASS) }
    val pyDecoratorListClass by lazy { loadClass(PY_DECORATOR_LIST_CLASS) }
    val pyImportStatementClass by lazy { loadClass(PY_IMPORT_STATEMENT_CLASS) }
    val pyFromImportStatementClass by lazy { loadClass(PY_FROM_IMPORT_STATEMENT_CLASS) }
    val pyParameterClass by lazy { loadClass(PY_PARAMETER_CLASS) }
    val pyCallExpressionClass by lazy { loadClass(PY_CALL_EXPRESSION_CLASS) }
    val pyReferenceExpressionClass by lazy { loadClass(PY_REFERENCE_EXPRESSION_CLASS) }
    val pyTypedElementClass by lazy { loadClass(PY_TYPED_ELEMENT_CLASS) }
    val typeEvalContextClass by lazy { loadClass(TYPE_EVAL_CONTEXT_CLASS) }
    val pyClassInheritorsSearchClass by lazy { loadClass(PY_CLASS_INHERITORS_SEARCH) }
    val pyParameterListClass by lazy { loadClass(PY_PARAMETER_LIST_CLASS) }
    val pyAssignmentStatementClass by lazy { loadClass(PY_ASSIGNMENT_STATEMENT_CLASS) }
    val pyAugAssignmentStatementClass by lazy { loadClass(PY_AUG_ASSIGNMENT_STATEMENT_CLASS) }
    val pyStringLiteralClass by lazy { loadClass(PY_STRING_LITERAL_CLASS) }
    val pyResolveContextClass by lazy { loadClass(PY_RESOLVE_CONTEXT_CLASS) }

    /** True if PythonCore plugin is loaded and classes are available */
    val isAvailable: Boolean
        get() = pyFileClass != null

    // --- Type-safe reflection helpers ---

    fun isPyFile(file: PsiFile): Boolean =
        pyFileClass?.isInstance(file) == true

    fun isPyClass(element: PsiElement): Boolean =
        pyClassClass?.isInstance(element) == true

    fun isPyFunction(element: PsiElement): Boolean =
        pyFunctionClass?.isInstance(element) == true

    fun isPyTargetExpression(element: PsiElement): Boolean =
        pyTargetExpressionClass?.isInstance(element) == true

    fun isPyDeclaration(element: PsiElement): Boolean =
        isPyClass(element) || isPyFunction(element) || isPyTargetExpression(element)

    fun isPyCallExpression(element: PsiElement): Boolean =
        pyCallExpressionClass?.isInstance(element) == true

    /** Get the name of a PyClass, PyFunction, or PyTargetExpression */
    fun getName(element: PsiElement): String? =
        invokeMethod(element, "getName") as? String

    /** Get the qualified name of a PyClass or PyFunction */
    fun getQualifiedName(element: PsiElement): String? =
        invokeMethod(element, "getQualifiedName") as? String

    /** Get the docstring of a PyClass or PyFunction */
    fun getDocStringExpression(element: PsiElement): PsiElement? =
        invokeMethod(element, "getDocStringExpression") as? PsiElement

    /** Get top-level classes from a PyFile */
    @Suppress("UNCHECKED_CAST")
    fun getTopLevelClasses(file: PsiFile): List<PsiElement> =
        (invokeMethod(file, "getTopLevelClasses") as? Array<*>)?.toList()?.filterIsInstance<PsiElement>()
            ?: emptyList()

    /** Get top-level functions from a PyFile */
    @Suppress("UNCHECKED_CAST")
    fun getTopLevelFunctions(file: PsiFile): List<PsiElement> =
        (invokeMethod(file, "getTopLevelFunctions") as? Array<*>)?.toList()?.filterIsInstance<PsiElement>()
            ?: emptyList()

    /** Get top-level attributes from a PyFile */
    @Suppress("UNCHECKED_CAST")
    fun getTopLevelAttributes(file: PsiFile): List<PsiElement> =
        (invokeMethod(file, "getTopLevelAttributes") as? List<*>)?.filterIsInstance<PsiElement>()
            ?: emptyList()

    /** Get import block from a PyFile */
    @Suppress("UNCHECKED_CAST")
    fun getImportBlock(file: PsiFile): List<PsiElement> =
        (invokeMethod(file, "getImportBlock") as? Array<*>)?.filterIsInstance<PsiElement>()
            ?: emptyList()

    /** Get super classes of a PyClass */
    @Suppress("UNCHECKED_CAST")
    fun getSuperClasses(element: PsiElement, context: Any?): Array<PsiElement> =
        try {
            if (context != null) {
                val method = element.javaClass.getMethod("getSuperClasses", typeEvalContextClass)
                (method.invoke(element, context) as? Array<*>)?.filterIsInstance<PsiElement>()?.toTypedArray()
                    ?: emptyArray()
            } else {
                emptyArray()
            }
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.cause
            if (cause is com.intellij.openapi.progress.ProcessCanceledException) throw cause
            emptyArray()
        } catch (_: ReflectiveOperationException) { emptyArray() }

    /** Get methods/functions of a PyClass */
    @Suppress("UNCHECKED_CAST")
    fun getMethods(element: PsiElement): Array<PsiElement> =
        (invokeMethod(element, "getMethods") as? Array<*>)?.filterIsInstance<PsiElement>()?.toTypedArray()
            ?: emptyArray()

    /** Get properties/attributes of a PyClass */
    @Suppress("UNCHECKED_CAST")
    fun getClassAttributes(element: PsiElement): Array<PsiElement> =
        (invokeMethod(element, "getClassAttributes") as? Array<*>)?.filterIsInstance<PsiElement>()?.toTypedArray()
            ?: emptyArray()

    /** Get the parameter list of a PyFunction */
    fun getParameterList(element: PsiElement): PsiElement? =
        invokeMethod(element, "getParameterList") as? PsiElement

    /** Get parameters from a PyParameterList */
    @Suppress("UNCHECKED_CAST")
    fun getParameters(paramList: PsiElement): Array<PsiElement> =
        (invokeMethod(paramList, "getParameters") as? Array<*>)?.filterIsInstance<PsiElement>()?.toTypedArray()
            ?: emptyArray()

    /** Get the decorator list of a PyFunction or PyClass */
    fun getDecoratorList(element: PsiElement): PsiElement? =
        invokeMethod(element, "getDecoratorList") as? PsiElement

    /** Get decorators from a PyDecoratorList */
    @Suppress("UNCHECKED_CAST")
    fun getDecorators(decoratorList: PsiElement): Array<PsiElement> =
        (invokeMethod(decoratorList, "getDecorators") as? Array<*>)?.filterIsInstance<PsiElement>()?.toTypedArray()
            ?: emptyArray()

    /** Get the callee expression of a PyDecorator/PyCallExpression */
    fun getCallee(element: PsiElement): PsiElement? =
        invokeMethod(element, "getCallee") as? PsiElement

    /** Create a TypeEvalContext for code analysis */
    fun createCodeAnalysisContext(file: PsiFile): Any? =
        try {
            val method = typeEvalContextClass?.getMethod(
                "codeAnalysis",
                com.intellij.openapi.project.Project::class.java,
                PsiFile::class.java
            )
            method?.invoke(null, file.project, file)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.cause
            if (cause is com.intellij.openapi.progress.ProcessCanceledException) throw cause
            null
        } catch (_: ReflectiveOperationException) { null }

    /** Get the type of a typed element using TypeEvalContext */
    fun getType(element: PsiElement, context: Any?): Any? =
        try {
            if (context != null && pyTypedElementClass?.isInstance(element) == true) {
                val method = pyTypedElementClass!!.getMethod("getType", typeEvalContextClass)
                method.invoke(element, context)
            } else null
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.cause
            if (cause is com.intellij.openapi.progress.ProcessCanceledException) throw cause
            null
        } catch (_: ReflectiveOperationException) { null }

    /** Get the presentable text of a PyType */
    fun getTypeName(pyType: Any?): String? =
        try {
            pyType?.javaClass?.getMethod("getName")?.invoke(pyType) as? String
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.cause
            if (cause is com.intellij.openapi.progress.ProcessCanceledException) throw cause
            null
        } catch (_: ReflectiveOperationException) { null }

    /** Search for inheritors of a PyClass */
    @Suppress("UNCHECKED_CAST")
    fun findInheritors(pyClass: PsiElement, searchScope: SearchScope): List<PsiElement> =
        try {
            val searchClass = pyClassInheritorsSearchClass ?: return emptyList()
            val searchMethod = searchClass.getMethod(
                "search",
                pyClassClass,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                com.intellij.psi.search.SearchScope::class.java
            )
            val query = searchMethod.invoke(null, pyClass, true, true, searchScope)
            val findAll = query?.javaClass?.getMethod("findAll")
            (findAll?.invoke(query) as? Collection<*>)?.filterIsInstance<PsiElement>() ?: emptyList()
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.cause
            if (cause is com.intellij.openapi.progress.ProcessCanceledException) throw cause
            // Fallback: try simpler overload
            try {
                val searchClass = pyClassInheritorsSearchClass!!
                val searchMethod = searchClass.methods.firstOrNull { it.name == "search" && it.parameterCount == 1 }
                val query = searchMethod?.invoke(null, pyClass)
                val findAll = query?.javaClass?.getMethod("findAll")
                (findAll?.invoke(query) as? Collection<*>)?.filterIsInstance<PsiElement>() ?: emptyList()
            } catch (e2: java.lang.reflect.InvocationTargetException) {
                val cause2 = e2.cause
                if (cause2 is com.intellij.openapi.progress.ProcessCanceledException) throw cause2
                emptyList()
            } catch (_: ReflectiveOperationException) { emptyList() }
        } catch (_: ReflectiveOperationException) {
            // Fallback: try simpler overload
            try {
                val searchClass = pyClassInheritorsSearchClass!!
                val searchMethod = searchClass.methods.firstOrNull { it.name == "search" && it.parameterCount == 1 }
                val query = searchMethod?.invoke(null, pyClass)
                val findAll = query?.javaClass?.getMethod("findAll")
                (findAll?.invoke(query) as? Collection<*>)?.filterIsInstance<PsiElement>() ?: emptyList()
            } catch (e2: java.lang.reflect.InvocationTargetException) {
                val cause2 = e2.cause
                if (cause2 is com.intellij.openapi.progress.ProcessCanceledException) throw cause2
                emptyList()
            } catch (_: ReflectiveOperationException) { emptyList() }
        }

    // --- Internal reflection helper ---

    private fun invokeMethod(obj: Any, methodName: String, vararg args: Any?): Any? =
        try {
            val method = obj.javaClass.getMethod(methodName)
            method.invoke(obj, *args)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.cause
            if (cause is com.intellij.openapi.progress.ProcessCanceledException) throw cause
            null
        } catch (_: ReflectiveOperationException) { null }
}
