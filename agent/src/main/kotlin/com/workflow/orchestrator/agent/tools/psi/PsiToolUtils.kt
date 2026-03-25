package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.workflow.orchestrator.agent.tools.ToolResult

@Suppress("UNCHECKED_CAST")
object PsiToolUtils {

    fun isDumb(project: Project): Boolean = DumbService.isDumb(project)

    fun dumbModeError(): ToolResult = ToolResult(
        content = "Error: IDE is still indexing. Try again in a few seconds.",
        summary = "Error: indexing in progress",
        tokenEstimate = 10,
        isError = true
    )

    /** Convert an absolute path to a project-relative path. */
    fun relativePath(project: Project, absolutePath: String): String {
        val basePath = project.basePath ?: return absolutePath
        return if (absolutePath.startsWith(basePath)) absolutePath.removePrefix("$basePath/") else absolutePath
    }

    /** Find a PsiClass by fully qualified name or simple name. */
    fun findClass(project: Project, className: String): PsiClass? {
        val scope = GlobalSearchScope.projectScope(project)
        val facade = JavaPsiFacade.getInstance(project)

        // Try fully qualified first
        facade.findClass(className, scope)?.let { return it }

        // Fall back to short name search
        val shortName = className.substringAfterLast('.')
        val classes = facade.findClasses(shortName, scope)
        // Prefer exact simple name match, then first result
        return classes.firstOrNull { it.name == shortName }
    }

    /**
     * Find a PsiClass by name, searching Java classes first via [findClass],
     * then falling back to [PsiShortNamesCache] for Kotlin classes that may
     * not be found by JavaPsiFacade.
     */
    fun findClassAnywhere(project: Project, className: String): PsiClass? {
        findClass(project, className)?.let { return it }
        return try {
            val scope = GlobalSearchScope.projectScope(project)
            val shortName = className.substringAfterLast('.')
            val cache = com.intellij.psi.search.PsiShortNamesCache.getInstance(project)
            cache.getClassesByName(shortName, scope).firstOrNull { it.name == shortName }
        } catch (_: Exception) {
            null
        }
    }

    /** Format a PsiMethod signature as a concise string. */
    fun formatMethodSignature(method: PsiMethod): String {
        val modifiers = method.modifierList.text.trim()
        val returnType = method.returnType?.presentableText ?: "void"
        val params = method.parameterList.parameters.joinToString(", ") { p ->
            "${p.type.presentableText} ${p.name}"
        }
        val annotations = method.annotations
            .filter { it.qualifiedName?.startsWith("org.springframework") == true || it.qualifiedName?.startsWith("jakarta") == true }
            .joinToString(" ") { "@${it.qualifiedName?.substringAfterLast('.') ?: ""}" }
        val prefix = if (annotations.isNotBlank()) "$annotations " else ""
        return "$prefix$modifiers $returnType ${method.name}($params)"
    }

    /** Format a PsiClass as a skeleton (signatures only, no bodies). */
    fun formatClassSkeleton(psiClass: PsiClass): String {
        val sb = StringBuilder()
        // Package
        (psiClass.containingFile as? PsiJavaFile)?.packageName?.let {
            if (it.isNotBlank()) sb.appendLine("package $it;")
        }
        // Detect class kind
        val classKind = when {
            psiClass.isInterface -> "interface"
            psiClass.isEnum -> "enum"
            psiClass.isAnnotationType -> "@interface"
            psiClass.hasModifierProperty(PsiModifier.ABSTRACT) -> "abstract class"
            else -> "class"
        }
        // Class declaration
        val superTypes = psiClass.superTypes.map { it.presentableText }.filter { it != "Object" }
        val extendsClause = if (superTypes.isNotEmpty()) " extends/implements ${superTypes.joinToString(", ")}" else ""
        sb.appendLine("${psiClass.modifierList?.text ?: ""} $classKind ${psiClass.name}$extendsClause {")

        // Fields
        psiClass.fields.forEach { field ->
            sb.appendLine("    ${field.modifierList?.text ?: ""} ${field.type.presentableText} ${field.name};")
        }

        // Methods (signature only)
        psiClass.methods.forEach { method ->
            sb.appendLine("    ${formatMethodSignature(method)};")
        }

        sb.appendLine("}")
        return sb.toString()
    }

    /**
     * Format a Kotlin file's structure using reflection to access Kotlin PSI classes.
     * This avoids a hard compile-time dependency on the Kotlin plugin, which may not
     * be available at runtime.
     *
     * @param ktFile a PsiFile that is an instance of org.jetbrains.kotlin.psi.KtFile
     * @param detail "signatures" (default), "full" (includes bodies), or "minimal" (class names + counts)
     */
    fun formatKotlinFileStructure(ktFile: Any, detail: String = "signatures"): String? {
        return try {
            val ktFileClass = Class.forName("org.jetbrains.kotlin.psi.KtFile")
            if (!ktFileClass.isInstance(ktFile)) return null

            val sb = StringBuilder()

            // Package name
            val getPackageFqName = ktFileClass.getMethod("getPackageFqName")
            val fqName = getPackageFqName.invoke(ktFile)
            val asString = fqName.javaClass.getMethod("asString")
            val packageName = asString.invoke(fqName) as String
            if (packageName.isNotBlank()) sb.appendLine("package $packageName")

            // Declarations
            val getDeclarations = ktFileClass.getMethod("getDeclarations")
            val declarations = getDeclarations.invoke(ktFile) as List<*>

            val ktClassClass = Class.forName("org.jetbrains.kotlin.psi.KtClass")
            val ktObjectClass = Class.forName("org.jetbrains.kotlin.psi.KtObjectDeclaration")
            val ktFunctionClass = Class.forName("org.jetbrains.kotlin.psi.KtNamedFunction")
            val ktPropertyClass = Class.forName("org.jetbrains.kotlin.psi.KtProperty")

            for (decl in declarations) {
                if (decl == null) continue
                when {
                    ktClassClass.isInstance(decl) -> {
                        sb.append(formatKotlinClass(decl, ktClassClass, ktFunctionClass, ktPropertyClass, detail))
                    }
                    ktObjectClass.isInstance(decl) -> {
                        sb.append(formatKotlinObject(decl, ktObjectClass, ktFunctionClass, ktPropertyClass, detail))
                    }
                    ktFunctionClass.isInstance(decl) -> {
                        sb.appendLine(formatKotlinFunction(decl, ktFunctionClass, detail))
                    }
                    ktPropertyClass.isInstance(decl) -> {
                        sb.appendLine(formatKotlinProperty(decl, ktPropertyClass))
                    }
                }
            }

            sb.toString().ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    // ---- Kotlin PSI reflection helpers ----

    private fun formatKotlinClass(
        ktClass: Any,
        ktClassClass: Class<*>,
        ktFunctionClass: Class<*>,
        ktPropertyClass: Class<*>,
        detail: String
    ): String {
        val sb = StringBuilder()
        val getName = ktClassClass.getMethod("getName")
        val name = getName.invoke(ktClass) as? String ?: return ""

        // Determine class kind
        val classKind = kotlinClassKind(ktClass, ktClassClass)

        // Supertypes
        val superList = try {
            val getSuperTypeList = ktClassClass.getMethod("getSuperTypeListEntries")
            val entries = getSuperTypeList.invoke(ktClass) as List<*>
            if (entries.isNotEmpty()) " : ${entries.joinToString(", ") { it.toString().trim() }}" else ""
        } catch (_: Exception) { "" }

        sb.appendLine("$classKind $name$superList {")

        if (detail == "minimal") {
            // Count members only
            val members = getClassBodyDeclarations(ktClass, ktClassClass)
            val methodCount = members.count { ktFunctionClass.isInstance(it) }
            val propCount = members.count { ktPropertyClass.isInstance(it) }
            sb.appendLine("    // $methodCount functions, $propCount properties")
        } else {
            val members = getClassBodyDeclarations(ktClass, ktClassClass)
            for (member in members) {
                if (member == null) continue
                when {
                    ktFunctionClass.isInstance(member) -> {
                        sb.appendLine("    ${formatKotlinFunction(member, ktFunctionClass, detail)}")
                    }
                    ktPropertyClass.isInstance(member) -> {
                        sb.appendLine("    ${formatKotlinProperty(member, ktPropertyClass)}")
                    }
                    ktClassClass.isInstance(member) -> {
                        // Nested class — recurse with indentation
                        val nested = formatKotlinClass(member, ktClassClass, ktFunctionClass, ktPropertyClass, detail)
                        nested.lines().forEach { line ->
                            if (line.isNotBlank()) sb.appendLine("    $line") else sb.appendLine()
                        }
                    }
                }
            }
        }

        sb.appendLine("}")
        return sb.toString()
    }

    private fun formatKotlinObject(
        ktObject: Any,
        ktObjectClass: Class<*>,
        ktFunctionClass: Class<*>,
        ktPropertyClass: Class<*>,
        detail: String
    ): String {
        val sb = StringBuilder()
        val getName = ktObjectClass.getMethod("getName")
        val name = getName.invoke(ktObject) as? String

        val isCompanion = try {
            val isCompanionMethod = ktObjectClass.getMethod("isCompanion")
            isCompanionMethod.invoke(ktObject) as Boolean
        } catch (_: Exception) { false }

        val keyword = if (isCompanion) "companion object" else "object"
        val label = if (name != null && !isCompanion) "$keyword $name" else keyword

        sb.appendLine("$label {")

        if (detail == "minimal") {
            val members = getObjectBodyDeclarations(ktObject, ktObjectClass)
            val methodCount = members.count { ktFunctionClass.isInstance(it) }
            val propCount = members.count { ktPropertyClass.isInstance(it) }
            sb.appendLine("    // $methodCount functions, $propCount properties")
        } else {
            val members = getObjectBodyDeclarations(ktObject, ktObjectClass)
            for (member in members) {
                if (member == null) continue
                when {
                    ktFunctionClass.isInstance(member) -> {
                        sb.appendLine("    ${formatKotlinFunction(member, ktFunctionClass, detail)}")
                    }
                    ktPropertyClass.isInstance(member) -> {
                        sb.appendLine("    ${formatKotlinProperty(member, ktPropertyClass)}")
                    }
                }
            }
        }

        sb.appendLine("}")
        return sb.toString()
    }

    private fun formatKotlinFunction(ktFunction: Any, ktFunctionClass: Class<*>, detail: String): String {
        val getName = ktFunctionClass.getMethod("getName")
        val name = getName.invoke(ktFunction) as? String ?: "anonymous"

        val params = try {
            val getValueParameters = ktFunctionClass.getMethod("getValueParameters")
            val parameters = getValueParameters.invoke(ktFunction) as List<*>
            parameters.joinToString(", ") { param ->
                val paramClass = Class.forName("org.jetbrains.kotlin.psi.KtParameter")
                val pName = paramClass.getMethod("getName").invoke(param) as? String ?: "_"
                val typeRef = try {
                    val getTypeReference = paramClass.getMethod("getTypeReference")
                    val ref = getTypeReference.invoke(param)
                    ref?.let { it.javaClass.getMethod("getText").invoke(it) as? String } ?: "Any"
                } catch (_: Exception) { "Any" }
                "$pName: $typeRef"
            }
        } catch (_: Exception) { "" }

        val returnType = try {
            val getTypeReference = ktFunctionClass.getMethod("getTypeReference")
            val ref = getTypeReference.invoke(ktFunction)
            ref?.let { ": ${it.javaClass.getMethod("getText").invoke(it) as? String ?: ""}" } ?: ""
        } catch (_: Exception) { "" }

        if (detail == "full") {
            val bodyText = try {
                val getText = ktFunctionClass.getMethod("getText")
                getText.invoke(ktFunction) as? String ?: ""
            } catch (_: Exception) { "" }
            return "fun $name($params)$returnType ${extractBody(bodyText)}"
        }

        return "fun $name($params)$returnType"
    }

    private fun formatKotlinProperty(ktProperty: Any, ktPropertyClass: Class<*>): String {
        val getName = ktPropertyClass.getMethod("getName")
        val name = getName.invoke(ktProperty) as? String ?: "_"

        val isVar = try {
            val isVarMethod = ktPropertyClass.getMethod("isVar")
            isVarMethod.invoke(ktProperty) as Boolean
        } catch (_: Exception) { false }

        val typeRef = try {
            val getTypeReference = ktPropertyClass.getMethod("getTypeReference")
            val ref = getTypeReference.invoke(ktProperty)
            ref?.let { ": ${it.javaClass.getMethod("getText").invoke(it) as? String ?: ""}" } ?: ""
        } catch (_: Exception) { "" }

        val keyword = if (isVar) "var" else "val"
        return "$keyword $name$typeRef"
    }

    private fun kotlinClassKind(ktClass: Any, ktClassClass: Class<*>): String {
        return try {
            val isInterface = ktClassClass.getMethod("isInterface").invoke(ktClass) as Boolean
            if (isInterface) return "interface"

            val isEnum = ktClassClass.getMethod("isEnum").invoke(ktClass) as Boolean
            if (isEnum) return "enum class"

            val isAnnotation = ktClassClass.getMethod("isAnnotation").invoke(ktClass) as Boolean
            if (isAnnotation) return "annotation class"

            val isData = ktClassClass.getMethod("isData").invoke(ktClass) as Boolean
            val isSealed = ktClassClass.getMethod("isSealed").invoke(ktClass) as Boolean

            when {
                isData -> "data class"
                isSealed -> "sealed class"
                else -> "class"
            }
        } catch (_: Exception) {
            "class"
        }
    }

    private fun getClassBodyDeclarations(ktClass: Any, ktClassClass: Class<*>): List<*> {
        return try {
            val getBody = ktClassClass.getMethod("getBody")
            val body = getBody.invoke(ktClass) ?: return emptyList<Any>()
            val getDeclarations = body.javaClass.getMethod("getDeclarations")
            getDeclarations.invoke(body) as List<*>
        } catch (_: Exception) {
            emptyList<Any>()
        }
    }

    private fun getObjectBodyDeclarations(ktObject: Any, ktObjectClass: Class<*>): List<*> {
        return try {
            val getBody = ktObjectClass.getMethod("getBody")
            val body = getBody.invoke(ktObject) ?: return emptyList<Any>()
            val getDeclarations = body.javaClass.getMethod("getDeclarations")
            getDeclarations.invoke(body) as List<*>
        } catch (_: Exception) {
            emptyList<Any>()
        }
    }

    /** Extract the body portion from a full function text (everything from the first '{' onward). */
    private fun extractBody(fullText: String): String {
        val braceIndex = fullText.indexOf('{')
        return if (braceIndex >= 0) fullText.substring(braceIndex) else "{ ... }"
    }
}
