package com.workflow.orchestrator.agent.context

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier

/**
 * Generates a compact repo map using IntelliJ's PSI (Program Structure Interface).
 *
 * Inspired by Aider's tree-sitter + PageRank approach, but leveraging PSI which
 * provides richer semantic information: type hierarchies, Spring annotations,
 * REST endpoint mappings, etc.
 *
 * The repo map gives the agent a "bird's eye view" of the project structure,
 * reducing exploration overhead by ~2-3 tool calls per task.
 *
 * Currently supports Java files via PSI. Kotlin files require the Kotlin plugin
 * and are handled via the Kotlin PSI API when available.
 *
 * Output format (~1500 tokens for a medium project):
 * ```
 * com.example.service/
 *   UserService @Service
 *     + createUser(CreateUserRequest): User
 *     + findById(Long): User?
 * com.example.controller/
 *   UserController @RestController
 *     + GET /api/users → getUsers(): List<User>
 * ```
 */
object RepoMapGenerator {

    private val LOG = Logger.getInstance(RepoMapGenerator::class.java)

    private val SPRING_ANNOTATIONS = setOf(
        "RestController", "Controller", "Service", "Repository", "Component",
        "Configuration", "SpringBootApplication"
    )

    private val REST_MAPPING_ANNOTATIONS = setOf(
        "GetMapping", "PostMapping", "PutMapping", "DeleteMapping",
        "PatchMapping", "RequestMapping"
    )

    data class ClassInfo(
        val packageName: String,
        val className: String,
        val superClass: String?,
        val interfaces: List<String>,
        val springAnnotations: List<String>,
        val methods: List<MethodInfo>
    )

    data class MethodInfo(
        val name: String,
        val params: String,
        val returnType: String?,
        val httpMethod: String? = null,
        val httpPath: String? = null
    )

    /**
     * Generate a repo map for the project.
     *
     * @param project The IntelliJ project
     * @param maxTokens Maximum tokens for the output (prunes if exceeded)
     * @return Compact text representation of the project structure
     */
    fun generate(project: Project, maxTokens: Int = 1500): String {
        return try {
            ReadAction.compute<String, Exception> {
                generateInReadAction(project, maxTokens)
            }
        } catch (e: Exception) {
            LOG.warn("RepoMapGenerator: failed to generate repo map", e)
            ""
        }
    }

    private fun generateInReadAction(project: Project, maxTokens: Int): String {
        val psiManager = PsiManager.getInstance(project)
        val classes = mutableListOf<ClassInfo>()

        val contentRoots = ProjectRootManager.getInstance(project).contentSourceRoots
        for (root in contentRoots) {
            collectClasses(root, psiManager, classes)
        }

        if (classes.isEmpty()) return ""

        // Group by package
        val byPackage = classes.groupBy { it.packageName }
            .toSortedMap()

        // Format and prune to fit token budget
        return formatAndPrune(byPackage, maxTokens)
    }

    private fun collectClasses(
        dir: com.intellij.openapi.vfs.VirtualFile,
        psiManager: PsiManager,
        result: MutableList<ClassInfo>
    ) {
        for (child in dir.children) {
            if (child.isDirectory) {
                collectClasses(child, psiManager, result)
                continue
            }

            val psiFile = psiManager.findFile(child) ?: continue

            // Handle Java files via PSI
            if (psiFile is PsiJavaFile) {
                for (psiClass in psiFile.classes) {
                    extractJavaClass(psiClass)?.let { result.add(it) }
                }
            }
            // Kotlin files: try via Kotlin PSI if plugin is loaded
            else if (child.extension == "kt") {
                try {
                    extractKotlinClasses(psiFile, result)
                } catch (_: Exception) {
                    // Kotlin plugin not available — skip
                }
            }
        }
    }

    /**
     * Extract Kotlin classes using the Kotlin PSI API.
     * Uses reflection-like approach to avoid hard compile-time dependency
     * on Kotlin plugin classes, but we import them since the plugin is now bundled.
     */
    private fun extractKotlinClasses(psiFile: com.intellij.psi.PsiFile, result: MutableList<ClassInfo>) {
        val ktFile = psiFile as? org.jetbrains.kotlin.psi.KtFile ?: return
        val packageName = ktFile.packageFqName.asString()

        for (declaration in ktFile.declarations) {
            val ktClass = declaration as? org.jetbrains.kotlin.psi.KtClass ?: continue
            val name = ktClass.name ?: continue
            if (ktClass.isEnum() || ktClass.isAnnotation()) continue

            val springAnnotations = ktClass.annotationEntries
                .mapNotNull { it.shortName?.asString() }
                .filter { it in SPRING_ANNOTATIONS }

            val publicFunctions = ktClass.declarations
                .filterIsInstance<org.jetbrains.kotlin.psi.KtNamedFunction>()
                .filter { !it.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PRIVATE_KEYWORD) }
                .map { func ->
                    MethodInfo(
                        name = func.name ?: "anonymous",
                        params = func.valueParameters.joinToString(", ") {
                            "${it.name}: ${it.typeReference?.text ?: "Any"}"
                        },
                        returnType = func.typeReference?.text
                    )
                }

            if (publicFunctions.isEmpty() && springAnnotations.isEmpty()) continue

            val superTypes = ktClass.superTypeListEntries.map { it.text }

            result.add(ClassInfo(
                packageName = packageName,
                className = name,
                superClass = superTypes.firstOrNull(),
                interfaces = superTypes.drop(1),
                springAnnotations = springAnnotations,
                methods = publicFunctions
            ))
        }
    }

    private fun extractJavaClass(psiClass: PsiClass): ClassInfo? {
        val name = psiClass.name ?: return null
        if (psiClass.isEnum || psiClass.isAnnotationType) return null

        val springAnnotations = psiClass.annotations
            .mapNotNull { it.qualifiedName?.substringAfterLast('.') }
            .filter { it in SPRING_ANNOTATIONS }

        val publicMethods = psiClass.methods
            .filter { it.hasModifierProperty(PsiModifier.PUBLIC) && !it.isConstructor }
            .map { method ->
                val httpInfo = extractHttpMapping(method)
                MethodInfo(
                    name = method.name,
                    params = method.parameterList.parameters.joinToString(", ") {
                        "${it.type.presentableText} ${it.name}"
                    },
                    returnType = method.returnType?.presentableText,
                    httpMethod = httpInfo?.first,
                    httpPath = httpInfo?.second
                )
            }

        if (publicMethods.isEmpty() && springAnnotations.isEmpty()) return null

        return ClassInfo(
            packageName = (psiClass.containingFile as? PsiJavaFile)?.packageName ?: "",
            className = name,
            superClass = psiClass.superClass?.name?.takeIf { it != "Object" },
            interfaces = psiClass.interfaces.mapNotNull { it.name },
            springAnnotations = springAnnotations,
            methods = publicMethods
        )
    }

    private fun extractHttpMapping(method: PsiMethod): Pair<String, String>? {
        for (annotation in method.annotations) {
            val shortName = annotation.qualifiedName?.substringAfterLast('.') ?: continue
            if (shortName !in REST_MAPPING_ANNOTATIONS) continue

            val httpMethod = when (shortName) {
                "GetMapping" -> "GET"
                "PostMapping" -> "POST"
                "PutMapping" -> "PUT"
                "DeleteMapping" -> "DELETE"
                "PatchMapping" -> "PATCH"
                "RequestMapping" -> {
                    val methodAttr = annotation.findAttributeValue("method")?.text ?: "GET"
                    methodAttr.substringAfterLast('.').removeSuffix("\"")
                }
                else -> continue
            }

            val pathAttr = annotation.findAttributeValue("value")?.text
                ?: annotation.findAttributeValue(null)?.text
                ?: "/"
            val path = pathAttr.removeSurrounding("\"").removeSurrounding("{", "}")

            return httpMethod to path
        }
        return null
    }

    private fun formatAndPrune(byPackage: Map<String, List<ClassInfo>>, maxTokens: Int): String {
        val sb = StringBuilder()

        for ((pkg, classes) in byPackage) {
            val pkgLine = if (pkg.isNotEmpty()) "$pkg/\n" else "(default package)/\n"
            sb.append(pkgLine)

            for (cls in classes.sortedBy { it.className }) {
                sb.append(formatSingleClass(cls))
            }
        }

        val result = sb.toString()
        val estimatedTokens = TokenEstimator.estimate(result)

        if (estimatedTokens <= maxTokens) return result

        // Prune: keep classes with most public methods first
        val allClasses = byPackage.values.flatten().sortedByDescending { it.methods.size }
        val pruned = mutableListOf<ClassInfo>()
        var tokenBudget = maxTokens

        for (cls in allClasses) {
            val clsText = formatSingleClass(cls)
            val clsTokens = TokenEstimator.estimate(clsText)
            if (tokenBudget - clsTokens >= 0) {
                pruned.add(cls)
                tokenBudget -= clsTokens
            }
        }

        // Re-format pruned set
        val prunedByPackage = pruned.groupBy { it.packageName }.toSortedMap()
        val prunedSb = StringBuilder()
        for ((pkg, classes) in prunedByPackage) {
            prunedSb.append(if (pkg.isNotEmpty()) "$pkg/\n" else "(default package)/\n")
            for (cls in classes.sortedBy { it.className }) {
                prunedSb.append(formatSingleClass(cls))
            }
        }

        return prunedSb.toString()
    }

    private fun formatSingleClass(cls: ClassInfo): String {
        val sb = StringBuilder()
        val annotations = cls.springAnnotations.joinToString(" ") { "@$it" }
        val extends = cls.superClass?.let { " extends $it" } ?: ""
        val implements = if (cls.interfaces.isNotEmpty()) " implements ${cls.interfaces.joinToString(", ")}" else ""

        sb.append("  ${cls.className}$extends$implements")
        if (annotations.isNotEmpty()) sb.append(" $annotations")
        sb.append("\n")

        for (method in cls.methods) {
            if (method.httpMethod != null) {
                sb.append("    + ${method.httpMethod} ${method.httpPath} → ${method.name}(${method.params})")
            } else {
                sb.append("    + ${method.name}(${method.params})")
            }
            method.returnType?.let { sb.append(": $it") }
            sb.append("\n")
        }
        return sb.toString()
    }
}
