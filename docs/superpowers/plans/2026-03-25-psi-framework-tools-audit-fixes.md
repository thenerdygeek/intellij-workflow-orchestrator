# PSI & Framework Tools — Audit Fixes + New Tools

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix systemic issues in 14 existing PSI/framework tools (no Kotlin support, no detail-level params, dead code, duplicated Maven utilities) and create 11 new high-value tools (Gradle, Spring Boot, Maven enhancements, PSI tools).

**Architecture:** Targeted fixes to existing code — extract shared utilities, add Kotlin PSI handling alongside Java, add optional `detail` parameters to key tools. New tools follow the established `AgentTool` pattern with `ReadAction.nonBlocking` + `inSmartMode`. Gradle tools parse `build.gradle.kts`/`gradle.properties` files directly (no Gradle plugin API dependency — the Gradle plugin is NOT bundled in agent's build.gradle.kts). Spring Boot tools use reflection on `com.intellij.spring.boot` APIs (bundled in gradle.properties but not agent's build.gradle.kts). Maven enhanced tools use existing `MavenProjectsManager` reflection.

**Tech Stack:** Kotlin 2.1.10, IntelliJ Platform SDK 2025.1+, PSI (Java + Kotlin), Maven plugin API (via reflection), Spring/Spring Boot plugin APIs (via reflection), Gradle (file parsing)

**Tool Registration:** `AgentService.kt:117-293` — lazy `ToolRegistry().apply { register(...) }` block. New tools go after line 292.

---

## File Map

| File | Changes |
|------|---------|
| `agent/src/main/kotlin/.../tools/psi/PsiToolUtils.kt` | Add Kotlin PSI support: `findClassAnywhere()`, `formatKotlinSkeleton()`, `formatClassSkeletonUnified()`, relative path helper |
| `agent/src/main/kotlin/.../tools/psi/FileStructureTool.kt` | Handle `KtFile` natively, add `detail` param |
| `agent/src/main/kotlin/.../tools/psi/FindDefinitionTool.kt` | Fix bare method/field search, add Kotlin support |
| `agent/src/main/kotlin/.../tools/psi/FindReferencesTool.kt` | Fix no-file-no-class fallback, add `context_lines` param, relative paths |
| `agent/src/main/kotlin/.../tools/psi/FindImplementationsTool.kt` | Remove dead `findClasses("*")` code, fix fallback path |
| `agent/src/main/kotlin/.../tools/psi/CallHierarchyTool.kt` | Add Kotlin call expression support, `depth` param, callee locations |
| `agent/src/main/kotlin/.../tools/psi/TypeHierarchyTool.kt` | Relative paths, Kotlin class support |
| `agent/src/main/kotlin/.../tools/psi/SpringEndpointsTool.kt` | Add `include_params` option for `@PathVariable`/`@RequestParam`/`@RequestBody` |
| `agent/src/main/kotlin/.../tools/psi/SpringSecurityTool.kt` | Add `@RolesAllowed` detection |
| `agent/src/main/kotlin/.../tools/psi/SpringRepositoriesTool.kt` | Show `@Modifying`, `@Transactional` on query methods |
| `agent/src/main/kotlin/.../tools/framework/MavenUtils.kt` | **NEW:** Extract duplicated Maven reflection helpers from 4 tools |
| `agent/src/main/kotlin/.../tools/framework/MavenDependenciesTool.kt` | Use `MavenUtils`, add `include_transitive` param |
| `agent/src/main/kotlin/.../tools/framework/MavenPluginsTool.kt` | Use `MavenUtils`, add plugin `configuration` extraction |
| `agent/src/main/kotlin/.../tools/framework/MavenProfilesTool.kt` | Use `MavenUtils` |
| `agent/src/main/kotlin/.../tools/framework/MavenPropertiesTool.kt` | Use `MavenUtils` |
| `agent/src/main/kotlin/.../tools/framework/SpringVersionTool.kt` | Use `MavenUtils` |
| `agent/src/main/kotlin/.../tools/framework/GradleDependenciesTool.kt` | **NEW:** Parse build.gradle[.kts] for dependencies by configuration |
| `agent/src/main/kotlin/.../tools/framework/GradleTasksTool.kt` | **NEW:** Parse build.gradle[.kts] for task definitions |
| `agent/src/main/kotlin/.../tools/framework/GradlePropertiesTool.kt` | **NEW:** Read gradle.properties files across modules |
| `agent/src/main/kotlin/.../tools/psi/GetAnnotationsTool.kt` | **NEW:** List annotations on a class/method with parameter values |
| `agent/src/main/kotlin/.../tools/psi/GetMethodBodyTool.kt` | **NEW:** Return full method body with surrounding context |
| `agent/src/main/kotlin/.../tools/psi/SpringBootEndpointsTool.kt` | **NEW:** Rich endpoint discovery using IntelliJ's endpoint framework — resolves context paths, property placeholders, shows full param types |
| `agent/src/main/kotlin/.../tools/psi/SpringBootAutoConfigTool.kt` | **NEW:** List auto-configurations with @Conditional* status |
| `agent/src/main/kotlin/.../tools/psi/SpringBootConfigPropertiesTool.kt` | **NEW:** Type-safe @ConfigurationProperties classes with field types, defaults, validation |
| `agent/src/main/kotlin/.../tools/psi/SpringBootActuatorTool.kt` | **NEW:** List configured actuator endpoints and their exposure |
| `agent/src/main/kotlin/.../tools/framework/MavenDependencyTreeTool.kt` | **NEW:** Transitive dependency tree with conflict detection |
| `agent/src/main/kotlin/.../tools/framework/MavenEffectivePomTool.kt` | **NEW:** Plugin configurations from effective POM |
| `agent/src/main/kotlin/.../tools/ToolCategoryRegistry.kt` | Register 11 new tools in appropriate categories |
| `agent/src/main/kotlin/.../agent/AgentService.kt` | Add register() calls for 11 new tools after line 292 |

---

## Part 1: Fix Existing Tools

### Task 1: Extract MavenUtils shared utility

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/MavenUtils.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/MavenDependenciesTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/MavenPluginsTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/MavenProfilesTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/MavenPropertiesTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/SpringVersionTool.kt`

The 5 Maven/Spring tools each duplicate `getMavenManager()`, `getMavenProjects()`, `findMavenProject()`, `getDisplayName()`, `getProjectNames()` — ~60 lines copy-pasted per tool.

- [ ] **Step 1:** Create `MavenUtils.kt` as an `object` with the shared reflection helpers:

```kotlin
package com.workflow.orchestrator.agent.tools.framework

import com.intellij.openapi.project.Project

/**
 * Shared Maven reflection utilities for agent tools.
 * Uses reflection because org.jetbrains.idea.maven is an optional plugin dependency.
 */
object MavenUtils {

    fun getMavenManager(project: Project): Any? {
        return try {
            val clazz = Class.forName("org.jetbrains.idea.maven.project.MavenProjectsManager")
            val getInstance = clazz.getMethod("getInstance", Project::class.java)
            val manager = getInstance.invoke(null, project)
            val isMaven = clazz.getMethod("isMavenizedProject").invoke(manager) as Boolean
            if (isMaven) manager else null
        } catch (_: Exception) { null }
    }

    fun getMavenProjects(manager: Any): List<Any> {
        return try {
            @Suppress("UNCHECKED_CAST")
            manager.javaClass.getMethod("getProjects").invoke(manager) as List<Any>
        } catch (_: Exception) { emptyList() }
    }

    fun findMavenProject(projects: List<Any>, manager: Any, moduleFilter: String?): Any? {
        if (moduleFilter == null) return projects.firstOrNull()
        for (mavenProject in projects) {
            val moduleName = try {
                val findModuleMethod = manager.javaClass.getMethod("findModule", mavenProject.javaClass)
                val module = findModuleMethod.invoke(manager, mavenProject)
                if (module != null) module.javaClass.getMethod("getName").invoke(module) as? String else null
            } catch (_: Exception) { null }
            val displayName = getDisplayName(mavenProject)
            val artifactId = getMavenId(mavenProject, "getArtifactId")
            if (moduleName == moduleFilter || displayName == moduleFilter || artifactId == moduleFilter) {
                return mavenProject
            }
        }
        return null
    }

    fun getDisplayName(mavenProject: Any): String {
        return try {
            mavenProject.javaClass.getMethod("getDisplayName").invoke(mavenProject) as? String ?: "unknown"
        } catch (_: Exception) { "unknown" }
    }

    fun getProjectNames(projects: List<Any>): String {
        return projects.mapNotNull { getDisplayName(it).takeIf { n -> n != "unknown" } }.joinToString(", ")
    }

    fun getMavenId(mavenProject: Any, field: String): String? {
        return try {
            val mavenId = mavenProject.javaClass.getMethod("getMavenId").invoke(mavenProject)
            mavenId.javaClass.getMethod(field).invoke(mavenId) as? String
        } catch (_: Exception) { null }
    }

    fun getProperties(mavenProject: Any): Map<String, String> {
        return try {
            val props = mavenProject.javaClass.getMethod("getProperties").invoke(mavenProject) as java.util.Properties
            props.entries.associate { (k, v) -> k.toString() to v.toString() }
        } catch (_: Exception) { emptyMap() }
    }

    fun getDependencies(mavenProject: Any): List<MavenDependencyInfo> {
        return try {
            @Suppress("UNCHECKED_CAST")
            val deps = mavenProject.javaClass.getMethod("getDependencies").invoke(mavenProject) as List<Any>
            deps.mapNotNull { dep ->
                try {
                    MavenDependencyInfo(
                        groupId = dep.javaClass.getMethod("getGroupId").invoke(dep) as? String ?: return@mapNotNull null,
                        artifactId = dep.javaClass.getMethod("getArtifactId").invoke(dep) as? String ?: return@mapNotNull null,
                        version = dep.javaClass.getMethod("getVersion").invoke(dep) as? String ?: "",
                        scope = dep.javaClass.getMethod("getScope").invoke(dep) as? String ?: "compile"
                    )
                } catch (_: Exception) { null }
            }
        } catch (_: Exception) { emptyList() }
    }

    /** Standard error result when Maven is not configured */
    fun noMavenError(): com.workflow.orchestrator.agent.tools.ToolResult =
        com.workflow.orchestrator.agent.tools.ToolResult("Maven not configured. This tool requires a Maven project.", "No Maven", com.workflow.orchestrator.agent.tools.ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

    data class MavenDependencyInfo(
        val groupId: String,
        val artifactId: String,
        val version: String,
        val scope: String
    )
}
```

- [ ] **Step 2:** Refactor `MavenDependenciesTool.kt` — delete the 5 private duplicated methods, replace calls with `MavenUtils.xxx()`. Change `DependencyInfo` references to `MavenUtils.MavenDependencyInfo`.

- [ ] **Step 3:** Refactor `MavenPluginsTool.kt` — same: delete duplicated methods, use `MavenUtils`.

- [ ] **Step 4:** Refactor `MavenProfilesTool.kt` — delete `getMavenManager`, `getMavenProjects`, use `MavenUtils`.

- [ ] **Step 5:** Refactor `MavenPropertiesTool.kt` — delete all 6 duplicated private methods, use `MavenUtils`.

- [ ] **Step 6:** Refactor `SpringVersionTool.kt` — delete duplicated methods, use `MavenUtils.getDependencies()` and `MavenUtils.getProperties()`.

- [ ] **Step 7:** Compile: `./gradlew :agent:compileKotlin`

- [ ] **Step 8:** Commit: `refactor: extract MavenUtils to DRY 5 Maven tool classes (~300 lines removed)`

---

### Task 2: Add Kotlin PSI support to PsiToolUtils

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/PsiToolUtils.kt`

The current `PsiToolUtils` only handles Java via `JavaPsiFacade`, `PsiJavaFile`, and hardcoded `class` keyword. `RepoMapGenerator` already uses Kotlin PSI with FQN references, proving the pattern works.

- [ ] **Step 1:** Add a `relativePath` helper and a unified `findClassAnywhere` that searches both Java and Kotlin:

```kotlin
// Add to PsiToolUtils object:

/** Convert absolute path to project-relative. */
fun relativePath(project: Project, absolutePath: String): String {
    val basePath = project.basePath ?: return absolutePath
    return if (absolutePath.startsWith(basePath)) absolutePath.removePrefix("$basePath/") else absolutePath
}

/**
 * Find a PsiClass by name — searches Java classes via JavaPsiFacade,
 * then Kotlin classes via KotlinFullClassNameIndex if not found.
 */
fun findClassAnywhere(project: Project, className: String): PsiClass? {
    // Try Java first (handles both Java and Kotlin classes that have a PsiClass representation)
    findClass(project, className)?.let { return it }

    // Kotlin classes compiled to PsiClass are usually found by findClass above.
    // For Kotlin-only constructs (object declarations, etc.), try Kotlin stub index.
    return try {
        val scope = GlobalSearchScope.projectScope(project)
        val shortName = className.substringAfterLast('.')
        // Kotlin plugin provides light classes that wrap KtClass as PsiClass
        // so findClass should already find them. This is a fallback.
        val cache = com.intellij.psi.search.PsiShortNamesCache.getInstance(project)
        cache.getClassesByName(shortName, scope).firstOrNull { it.name == shortName }
    } catch (_: Exception) { null }
}
```

- [ ] **Step 2:** Add `formatKotlinFileStructure` for handling `KtFile`:

```kotlin
/** Format a Kotlin file's structure (classes, functions, properties — no bodies). */
fun formatKotlinFileStructure(ktFile: Any, detail: String = "signatures"): String {
    // Use reflection to avoid compile-time dependency on Kotlin plugin
    val sb = StringBuilder()
    try {
        val ktFileClass = Class.forName("org.jetbrains.kotlin.psi.KtFile")
        val packageFqName = ktFileClass.getMethod("getPackageFqName").invoke(ktFile)
        val pkgName = packageFqName.toString()
        if (pkgName.isNotBlank()) sb.appendLine("package $pkgName")

        // Top-level declarations
        @Suppress("UNCHECKED_CAST")
        val declarations = ktFileClass.getMethod("getDeclarations").invoke(ktFile) as List<Any>

        for (decl in declarations) {
            formatKotlinDeclaration(decl, sb, indent = "", detail = detail)
        }
    } catch (e: Exception) {
        sb.appendLine("(Kotlin PSI unavailable: ${e.message})")
    }
    return sb.toString()
}

private fun formatKotlinDeclaration(decl: Any, sb: StringBuilder, indent: String, detail: String) {
    try {
        val ktClassClass = Class.forName("org.jetbrains.kotlin.psi.KtClass")
        val ktFunctionClass = Class.forName("org.jetbrains.kotlin.psi.KtNamedFunction")
        val ktPropertyClass = Class.forName("org.jetbrains.kotlin.psi.KtProperty")
        val ktObjectClass = Class.forName("org.jetbrains.kotlin.psi.KtObjectDeclaration")

        when {
            ktClassClass.isInstance(decl) -> {
                val name = ktClassClass.getMethod("getName").invoke(decl) as? String ?: return
                val keyword = try {
                    val isData = ktClassClass.getMethod("isData").invoke(decl) as Boolean
                    val isSealed = ktClassClass.getMethod("isSealed").invoke(decl) as Boolean
                    val isEnum = ktClassClass.getMethod("isEnum").invoke(decl) as Boolean
                    val isInterface = ktClassClass.getMethod("isInterface").invoke(decl) as Boolean
                    when {
                        isData -> "data class"
                        isSealed -> "sealed class"
                        isEnum -> "enum class"
                        isInterface -> "interface"
                        else -> "class"
                    }
                } catch (_: Exception) { "class" }

                // Supertype list
                val supertypeText = try {
                    val supertypeList = ktClassClass.getMethod("getSuperTypeList").invoke(decl)
                    if (supertypeList != null) " : ${supertypeList.javaClass.getMethod("getText").invoke(supertypeList)}" else ""
                } catch (_: Exception) { "" }

                sb.appendLine("$indent$keyword $name$supertypeText {")

                // Members
                if (detail != "minimal") {
                    try {
                        @Suppress("UNCHECKED_CAST")
                        val body = ktClassClass.getMethod("getBody").invoke(decl)
                        if (body != null) {
                            val bodyDeclarations = body.javaClass.getMethod("getDeclarations").invoke(body) as List<Any>
                            for (member in bodyDeclarations) {
                                formatKotlinDeclaration(member, sb, "$indent    ", detail)
                            }
                        }
                    } catch (_: Exception) {}
                }

                sb.appendLine("$indent}")
            }
            ktObjectClass.isInstance(decl) -> {
                val name = try { ktObjectClass.getMethod("getName").invoke(decl) as? String } catch (_: Exception) { null }
                val isCompanion = try { ktObjectClass.getMethod("isCompanion").invoke(decl) as Boolean } catch (_: Exception) { false }
                val label = if (isCompanion) "companion object" else "object ${name ?: ""}"
                sb.appendLine("$indent$label { ... }")
            }
            ktFunctionClass.isInstance(decl) -> {
                val name = ktFunctionClass.getMethod("getName").invoke(decl) as? String ?: return
                val typeRef = try {
                    val ref = ktFunctionClass.getMethod("getTypeReference").invoke(decl)
                    if (ref != null) ": ${ref.javaClass.getMethod("getText").invoke(ref)}" else ""
                } catch (_: Exception) { "" }
                val params = try {
                    val paramList = ktFunctionClass.getMethod("getValueParameterList").invoke(decl)
                    if (paramList != null) paramList.javaClass.getMethod("getText").invoke(paramList) as String else "()"
                } catch (_: Exception) { "()" }
                sb.appendLine("${indent}fun $name$params$typeRef")
            }
            ktPropertyClass.isInstance(decl) -> {
                val name = ktPropertyClass.getMethod("getName").invoke(decl) as? String ?: return
                val isVar = ktPropertyClass.getMethod("isVar").invoke(decl) as Boolean
                val keyword = if (isVar) "var" else "val"
                val typeRef = try {
                    val ref = ktPropertyClass.getMethod("getTypeReference").invoke(decl)
                    if (ref != null) ": ${ref.javaClass.getMethod("getText").invoke(ref)}" else ""
                } catch (_: Exception) { "" }
                sb.appendLine("$indent$keyword $name$typeRef")
            }
        }
    } catch (_: Exception) {
        // Unknown declaration type — skip
    }
}
```

- [ ] **Step 3:** Update `formatClassSkeleton` to detect class kind (interface, enum, abstract, annotation):

```kotlin
fun formatClassSkeleton(psiClass: PsiClass): String {
    val sb = StringBuilder()
    (psiClass.containingFile as? PsiJavaFile)?.packageName?.let {
        if (it.isNotBlank()) sb.appendLine("package $it;")
    }
    val classKind = when {
        psiClass.isInterface -> "interface"
        psiClass.isEnum -> "enum"
        psiClass.isAnnotationType -> "@interface"
        psiClass.hasModifierProperty(com.intellij.psi.PsiModifier.ABSTRACT) -> "abstract class"
        else -> "class"
    }
    val superTypes = psiClass.superTypes.map { it.presentableText }
        .filter { it != "Object" }
    val extendsClause = if (superTypes.isNotEmpty()) " extends/implements ${superTypes.joinToString(", ")}" else ""
    sb.appendLine("${psiClass.modifierList?.text ?: ""} $classKind ${psiClass.name}$extendsClause {")

    psiClass.fields.forEach { field ->
        sb.appendLine("    ${field.modifierList?.text ?: ""} ${field.type.presentableText} ${field.name};")
    }
    psiClass.methods.forEach { method ->
        sb.appendLine("    ${formatMethodSignature(method)};")
    }
    sb.appendLine("}")
    return sb.toString()
}
```

- [ ] **Step 4:** Compile: `./gradlew :agent:compileKotlin`

- [ ] **Step 5:** Commit: `feat(psi): add Kotlin PSI support and relative paths to PsiToolUtils`

---

### Task 3: Fix FileStructureTool — Kotlin support + detail parameter

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/FileStructureTool.kt`

Currently: Kotlin files hit "first 50 lines" fallback. No option for detail level.

- [ ] **Step 1:** Add `detail` parameter to `FunctionParameters`:

```kotlin
override val parameters = FunctionParameters(
    properties = mapOf(
        "path" to ParameterProperty(type = "string", description = "Absolute or project-relative file path"),
        "detail" to ParameterProperty(
            type = "string",
            description = "Detail level: 'signatures' (default — class/method/field names only), 'full' (includes method bodies, annotations, field initializers), 'minimal' (class names + field/method counts only)"
        )
    ),
    required = listOf("path")
)
```

- [ ] **Step 2:** Update `execute()` to handle Kotlin files and the `detail` param:

```kotlin
val detail = params["detail"]?.jsonPrimitive?.content ?: "signatures"

val content = ReadAction.nonBlocking<String> {
    val psiFile = PsiManager.getInstance(project).findFile(vFile)
    when {
        psiFile is PsiJavaFile -> {
            psiFile.classes.joinToString("\n\n") { PsiToolUtils.formatClassSkeleton(it) }
        }
        psiFile?.javaClass?.name?.contains("KtFile") == true -> {
            PsiToolUtils.formatKotlinFileStructure(psiFile, detail)
        }
        else -> {
            // Non-Java/Kotlin: return first 100 lines as fallback
            val text = psiFile?.text ?: return@nonBlocking "Error: Cannot read file"
            val lines = text.lines()
            val shown = lines.take(100).joinToString("\n")
            if (lines.size > 100) "$shown\n... (${lines.size - 100} more lines)" else shown
        }
    }
}.inSmartMode(project).executeSynchronously()
```

- [ ] **Step 3:** Also handle `detail = "full"` for Java: when `detail == "full"`, use `psiFile.text` (full file content) instead of skeleton. When `detail == "minimal"`, show class names with field/method counts.

- [ ] **Step 4:** Compile: `./gradlew :agent:compileKotlin`

- [ ] **Step 5:** Commit: `fix(psi): FileStructureTool handles Kotlin files natively + detail parameter`

---

### Task 4: Fix FindDefinitionTool — bare method/field search

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/FindDefinitionTool.kt`

Currently: searching for just `"myMethod"` (no class prefix) returns "No definition found" because it only tries class lookup and `ClassName#method` split.

- [ ] **Step 1:** After the `ClassName#method` check fails, add a `PsiShortNamesCache` fallback for methods and fields:

```kotlin
// After the parts.size == 2 block, add:

// Bare method name search — search all project classes
val scope = GlobalSearchScope.projectScope(project)
val shortNameCache = com.intellij.psi.search.PsiShortNamesCache.getInstance(project)

// Try as method
val methods = shortNameCache.getMethodsByName(symbol, scope)
if (methods.isNotEmpty()) {
    val method = methods.first()
    return@nonBlocking formatMethodDefinition(project, method) +
        if (methods.size > 1) "\n\n(${methods.size - 1} other method(s) with same name — provide class_name to disambiguate)" else ""
}

// Try as field
val fields = shortNameCache.getFieldsByName(symbol, scope)
if (fields.isNotEmpty()) {
    val field = fields.first()
    val file = field.containingFile?.virtualFile?.path?.let { PsiToolUtils.relativePath(project, it) } ?: "unknown"
    val document = field.containingFile?.let { PsiDocumentManager.getInstance(project).getDocument(it) }
    val line = document?.getLineNumber(field.textOffset)?.plus(1) ?: 0
    return@nonBlocking "Definition of '${field.containingClass?.qualifiedName ?: ""}#${field.name}':\n" +
        "  File: $file\n  Line: $line\n  Type: ${field.type.presentableText}"
}
```

- [ ] **Step 2:** Add optional `class_name` parameter:

```kotlin
override val parameters = FunctionParameters(
    properties = mapOf(
        "symbol" to ParameterProperty(type = "string", description = "Symbol name: class FQN, method name, field name, or ClassName#methodName"),
        "class_name" to ParameterProperty(type = "string", description = "Optional: class name for disambiguation when multiple symbols share the same name")
    ),
    required = listOf("symbol")
)
```

When `class_name` is provided, search within that class first.

- [ ] **Step 3:** Use `PsiToolUtils.relativePath()` in `formatMethodDefinition`.

- [ ] **Step 4:** Compile: `./gradlew :agent:compileKotlin`

- [ ] **Step 5:** Commit: `fix(psi): FindDefinitionTool supports bare method/field names + class_name param`

---

### Task 5: Fix FindReferencesTool — global fallback + context lines

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/FindReferencesTool.kt`

Currently: if symbol is not a class and no `file` param is given, returns "No symbol found". Should fall back to `PsiShortNamesCache`.

- [ ] **Step 1:** Add `context_lines` parameter and fix the no-file fallback:

```kotlin
override val parameters = FunctionParameters(
    properties = mapOf(
        "symbol" to ParameterProperty(type = "string", description = "Symbol name to search for (class name, method name, or field name)"),
        "file" to ParameterProperty(type = "string", description = "Optional file path for disambiguation when multiple symbols share the same name"),
        "context_lines" to ParameterProperty(type = "integer", description = "Number of context lines around each reference (default: 0, max: 3)")
    ),
    required = listOf("symbol")
)
```

- [ ] **Step 2:** After the existing `searchTarget ?: run { ... }` block, add a global fallback before the "No symbol found" return:

```kotlin
val searchTarget = targetElement ?: run {
    // existing file-path-based search...
} ?: run {
    // NEW: global fallback via PsiShortNamesCache
    val shortNameCache = com.intellij.psi.search.PsiShortNamesCache.getInstance(project)
    // Try method
    shortNameCache.getMethodsByName(symbol, scope).firstOrNull()
        // Try field
        ?: shortNameCache.getFieldsByName(symbol, scope).firstOrNull()
}
```

- [ ] **Step 3:** Add context lines to reference output and use relative paths:

```kotlin
val contextLines = (params["context_lines"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0).coerceIn(0, 3)

// In the reference formatting loop (all line numbers are 0-indexed internally):
val relativePath = PsiToolUtils.relativePath(project, file)
val zeroIndexedLine = line - 1 // 'line' is 1-indexed from earlier getLineNumber() + 1
val contextStr = if (contextLines > 0) {
    val startLine = (zeroIndexedLine - contextLines).coerceAtLeast(0)
    val endLine = (zeroIndexedLine + contextLines).coerceAtMost(document.lineCount - 1)
    (startLine..endLine).joinToString("\n") { lineIdx ->
        val prefix = if (lineIdx == zeroIndexedLine) ">>>" else "   "
        val lineContent = document.getText(
            com.intellij.openapi.util.TextRange(
                document.getLineStartOffset(lineIdx),
                document.getLineEndOffset(lineIdx)
            )
        )
        "$prefix ${lineIdx + 1}: $lineContent"
    }
} else {
    lineText
}
"$relativePath:$line  $contextStr"
```

- [ ] **Step 4:** Compile: `./gradlew :agent:compileKotlin`

- [ ] **Step 5:** Commit: `fix(psi): FindReferencesTool global fallback + context_lines + relative paths`

---

### Task 6: Fix FindImplementationsTool — remove dead code

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/FindImplementationsTool.kt`

Line 49: `facade.findClasses("*", scope)` is a wildcard search that doesn't work — the entire else branch when `className == null` computes `targetClass = null` then falls through to `findMethodAcrossProject` anyway. The dead code is confusing.

- [ ] **Step 1:** Simplify the class-finding logic:

```kotlin
val content = ReadAction.nonBlocking<String> {
    val scope = GlobalSearchScope.projectScope(project)

    if (className != null) {
        val targetClass = PsiToolUtils.findClassAnywhere(project, className)
            ?: return@nonBlocking "Class '$className' not found in project."

        val methods = targetClass.findMethodsByName(methodName, false)
        if (methods.isEmpty()) {
            return@nonBlocking "No method '$methodName' found in '${targetClass.qualifiedName ?: targetClass.name}'."
        }
        // ... existing overriders search (keep as-is)
    } else {
        // No class name — search all project classes
        findMethodAcrossProject(project, methodName, scope)
    }
}.inSmartMode(project).executeSynchronously()
```

- [ ] **Step 2:** In `findMethodAcrossProject`, use relative paths via `PsiToolUtils.relativePath()`.

- [ ] **Step 3:** Compile: `./gradlew :agent:compileKotlin`

- [ ] **Step 4:** Commit: `fix(psi): remove dead wildcard class search in FindImplementationsTool`

---

### Task 7: Fix CallHierarchyTool — Kotlin calls + depth + callee locations

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/CallHierarchyTool.kt`

Currently: only finds Java `PsiMethodCallExpression` callees (misses Kotlin), no callee file locations, no depth control.

- [ ] **Step 1:** Add `depth` parameter:

```kotlin
override val parameters = FunctionParameters(
    properties = mapOf(
        "method" to ParameterProperty(type = "string", description = "Method name to analyze"),
        "class_name" to ParameterProperty(type = "string", description = "Optional class name containing the method, for disambiguation"),
        "depth" to ParameterProperty(type = "integer", description = "How many levels deep to trace callers (default: 1, max: 3). depth=2 shows callers of callers.")
    ),
    required = listOf("method")
)
```

- [ ] **Step 2:** Add file locations for callees in the output:

```kotlin
// Replace the callees section:
val callExpressions = PsiTreeUtil.findChildrenOfType(psiMethod, PsiMethodCallExpression::class.java)
val callees = callExpressions.mapNotNull { call ->
    val resolved = call.resolveMethod() ?: return@mapNotNull null
    val calleeName = "${resolved.containingClass?.name ?: ""}#${resolved.name}"
    val calleeFile = resolved.containingFile?.virtualFile?.path?.let {
        PsiToolUtils.relativePath(project, it)
    } ?: ""
    val calleeLine = resolved.containingFile?.let { pf ->
        PsiDocumentManager.getInstance(project).getDocument(pf)
    }?.getLineNumber(resolved.textOffset)?.plus(1) ?: 0
    Triple(calleeName, calleeFile, calleeLine)
}.distinctBy { it.first }

callees.take(30).forEach { (name, file, line) ->
    sb.appendLine("  $name  ($file:$line)")
}
```

- [ ] **Step 3:** Also search for Kotlin call expressions by checking for `KtCallExpression` in the children:

```kotlin
// After Java callees, try Kotlin
try {
    val ktCallClass = Class.forName("org.jetbrains.kotlin.psi.KtCallExpression")
    val ktCalls = PsiTreeUtil.findChildrenOfType(psiMethod, ktCallClass)
    // These may resolve via PsiReference
    for (call in ktCalls) {
        val ref = (call as com.intellij.psi.PsiElement).references.firstOrNull()
        val resolved = ref?.resolve()
        if (resolved is PsiMethod) {
            val calleeName = "${resolved.containingClass?.name ?: ""}#${resolved.name}"
            // ... add to callees list
        }
    }
} catch (_: ClassNotFoundException) {
    // Kotlin plugin not available
}
```

- [ ] **Step 4:** Implement depth parameter for callers (recurse `depth` levels up):

```kotlin
val maxDepth = (params["depth"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1).coerceIn(1, 3)

fun collectCallers(method: PsiMethod, currentDepth: Int, indent: String) {
    if (currentDepth > maxDepth) return
    val refs = ReferencesSearch.search(method, scope).findAll()
    refs.take(if (currentDepth == 1) 30 else 10).forEach { ref ->
        val element = ref.element
        val containingMethod = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
        // ... format caller with indent
        if (containingMethod != null && currentDepth < maxDepth) {
            collectCallers(containingMethod, currentDepth + 1, "$indent  ")
        }
    }
}
```

- [ ] **Step 5:** Use `PsiToolUtils.relativePath()` for all file paths.

- [ ] **Step 6:** Compile: `./gradlew :agent:compileKotlin`

- [ ] **Step 7:** Commit: `fix(psi): CallHierarchyTool Kotlin support + depth param + callee locations`

---

### Task 8: Fix TypeHierarchyTool — relative paths

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/TypeHierarchyTool.kt`

- [ ] **Step 1:** Use `PsiToolUtils.relativePath()` for all file paths in supertype and subtype output. Also use `findClassAnywhere()` instead of `findClass()`.

- [ ] **Step 2:** Compile: `./gradlew :agent:compileKotlin`

- [ ] **Step 3:** Commit: `fix(psi): TypeHierarchyTool uses relative paths + findClassAnywhere`

---

### Task 9: Enhance Spring PSI tools — endpoints params, security, repositories

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/SpringEndpointsTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/SpringSecurityTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/SpringRepositoriesTool.kt`

- [ ] **Step 1: SpringEndpointsTool** — Add `include_params` parameter (default `false`). When `true`, show `@PathVariable`, `@RequestParam`, `@RequestBody` annotations on handler method parameters:

```kotlin
"include_params" to ParameterProperty(type = "boolean", description = "If true, show handler method parameters with @PathVariable/@RequestParam/@RequestBody annotations (default: false)")

// In formatMethodParams, when include_params=true:
method.parameterList.parameters.joinToString(", ") { p ->
    val annotations = listOf("PathVariable", "RequestParam", "RequestBody")
        .mapNotNull { ann ->
            p.getAnnotation("org.springframework.web.bind.annotation.$ann")?.let { "@$ann" }
        }
    val prefix = if (annotations.isNotEmpty()) "${annotations.joinToString(" ")} " else ""
    "$prefix${p.type.presentableText} ${p.name}"
}
```

- [ ] **Step 2: SpringSecurityTool** — Add `@RolesAllowed` detection (Jakarta Security):

```kotlin
// After @Secured search, add:
val rolesAllowedClass = facade.findClass("jakarta.annotation.security.RolesAllowed", allScope)
    ?: facade.findClass("javax.annotation.security.RolesAllowed", allScope)
if (rolesAllowedClass != null) {
    val methods = AnnotatedElementsSearch.searchPsiMethods(rolesAllowedClass, scope).findAll()
    for (method in methods) {
        val annotation = method.getAnnotation("jakarta.annotation.security.RolesAllowed")
            ?: method.getAnnotation("javax.annotation.security.RolesAllowed") ?: continue
        val value = annotation.findAttributeValue("value")?.text
            ?.removeSurrounding("\"")?.removeSurrounding("{", "}") ?: ""
        preAuthorizeMethods.add(MethodSecurityInfo(
            className = method.containingClass?.name ?: "(anonymous)",
            methodName = method.name,
            annotationType = "@RolesAllowed",
            expression = value
        ))
    }
}
```

- [ ] **Step 3: SpringRepositoriesTool** — Show `@Modifying` and `@Transactional` on query methods:

```kotlin
// In the custom method extraction loop, add:
val isModifying = method.getAnnotation("org.springframework.data.jpa.repository.Modifying") != null
val isTransactional = method.getAnnotation("org.springframework.transaction.annotation.Transactional") != null
val annotations = buildList {
    if (isModifying) add("@Modifying")
    if (isTransactional) add("@Transactional")
    if (queryValue != null) add("@Query")
}
```

- [ ] **Step 4:** Compile: `./gradlew :agent:compileKotlin`

- [ ] **Step 5:** Commit: `fix(psi): enhance Spring endpoints, security, repositories tools with more detail`

---

### Task 10: Compile + run tests

- [ ] **Step 1:** Full compile: `./gradlew :agent:compileKotlin :core:compileKotlin`

- [ ] **Step 2:** Run existing agent tests: `./gradlew :agent:test`

- [ ] **Step 3:** Fix any compilation errors or test failures.

- [ ] **Step 4:** Commit any fixes: `fix: resolve compilation/test issues from PSI tool updates`

---

## Part 2: New Tools

### Task 11: Create GetAnnotationsTool

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/GetAnnotationsTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ToolCategoryRegistry.kt`

High-value tool — lets the agent understand Spring config, JPA mappings, validation rules on any class/method.

- [ ] **Step 1:** Create the tool:

```kotlin
package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class GetAnnotationsTool : AgentTool {
    override val name = "get_annotations"
    override val description = "List all annotations on a class, method, or field — with their parameter values. " +
        "Useful for understanding Spring config, JPA mappings, validation rules, security annotations."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "class_name" to ParameterProperty(type = "string", description = "Class name (simple or fully qualified)"),
            "member" to ParameterProperty(type = "string", description = "Optional: method or field name within the class. If omitted, shows class-level annotations."),
            "include_inherited" to ParameterProperty(type = "boolean", description = "Include annotations from superclasses (default: false)")
        ),
        required = listOf("class_name")
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.CODER, WorkerType.REVIEWER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        val className = params["class_name"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'class_name' required", "Error: missing class_name", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val memberName = params["member"]?.jsonPrimitive?.contentOrNull
        val includeInherited = params["include_inherited"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false

        val content = ReadAction.nonBlocking<String> {
            val psiClass = PsiToolUtils.findClassAnywhere(project, className)
                ?: return@nonBlocking "No class '$className' found in project"

            if (memberName != null) {
                // Find member (method or field)
                val method = psiClass.findMethodsByName(memberName, includeInherited).firstOrNull()
                if (method != null) {
                    return@nonBlocking formatAnnotations("${psiClass.name}#$memberName", method.annotations, method.modifierList)
                }
                val field = psiClass.findFieldByName(memberName, includeInherited)
                if (field != null) {
                    return@nonBlocking formatAnnotations("${psiClass.name}.$memberName", field.annotations, field.modifierList)
                }
                return@nonBlocking "No method or field '$memberName' found in '${psiClass.name}'"
            }

            // Class-level annotations
            val classAnnotations = if (includeInherited) {
                collectInheritedAnnotations(psiClass)
            } else {
                psiClass.annotations.toList()
            }
            formatAnnotations(psiClass.qualifiedName ?: psiClass.name ?: className, classAnnotations.toTypedArray(), psiClass.modifierList)
        }.inSmartMode(project).executeSynchronously()

        return ToolResult(
            content = content,
            summary = "Annotations on '${memberName?.let { "$className#$it" } ?: className}'",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }

    private fun formatAnnotations(targetName: String, annotations: Array<PsiAnnotation>, modifierList: PsiModifierList?): String {
        if (annotations.isEmpty()) {
            return "No annotations on '$targetName'."
        }
        val sb = StringBuilder("Annotations on $targetName:\n")
        for (annotation in annotations) {
            val fqn = annotation.qualifiedName ?: continue
            val shortName = fqn.substringAfterLast('.')
            val attributes = annotation.parameterList.attributes
            if (attributes.isEmpty()) {
                sb.appendLine("  @$shortName")
            } else {
                val params = attributes.joinToString(", ") { attr ->
                    val name = attr.name ?: "value"
                    val value = attr.value?.text ?: "?"
                    if (name == "value" && attributes.size == 1) value else "$name=$value"
                }
                sb.appendLine("  @$shortName($params)")
            }
            sb.appendLine("    FQN: $fqn")
        }
        return sb.toString().trimEnd()
    }

    private fun collectInheritedAnnotations(psiClass: PsiClass): List<PsiAnnotation> {
        val result = mutableListOf<PsiAnnotation>()
        result.addAll(psiClass.annotations)
        var current: PsiClass? = psiClass.superClass
        val visited = mutableSetOf(psiClass.qualifiedName)
        while (current != null && current.qualifiedName != "java.lang.Object") {
            if (!visited.add(current.qualifiedName)) break
            result.addAll(current.annotations)
            current = current.superClass
        }
        return result
    }
}
```

- [ ] **Step 2:** Register in `ToolCategoryRegistry` — add `"get_annotations"` to the `core` category tools list.

- [ ] **Step 3:** Register the tool instance in whichever file initializes the ToolRegistry (likely `AgentController` or similar initialization code).

- [ ] **Step 4:** Compile: `./gradlew :agent:compileKotlin`

- [ ] **Step 5:** Commit: `feat(psi): add get_annotations tool for class/method/field annotations`

---

### Task 12: Create GetMethodBodyTool

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/GetMethodBodyTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ToolCategoryRegistry.kt`

Bridges the gap between `file_structure` (no bodies) and `read_file` (need exact line numbers).

- [ ] **Step 1:** Create the tool:

```kotlin
package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class GetMethodBodyTool : AgentTool {
    override val name = "get_method_body"
    override val description = "Get the full source code of a specific method including annotations, " +
        "signature, and body. More targeted than read_file — no need to know line numbers."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "method" to ParameterProperty(type = "string", description = "Method name to retrieve"),
            "class_name" to ParameterProperty(type = "string", description = "Class containing the method (simple or fully qualified name)"),
            "context_lines" to ParameterProperty(type = "integer", description = "Lines of context before/after the method (default: 0, max: 5)")
        ),
        required = listOf("method", "class_name")
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.CODER, WorkerType.REVIEWER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        val methodName = params["method"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'method' required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val className = params["class_name"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'class_name' required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val contextLines = (params["context_lines"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0).coerceIn(0, 5)

        val content = ReadAction.nonBlocking<String> {
            val psiClass = PsiToolUtils.findClassAnywhere(project, className)
                ?: return@nonBlocking "No class '$className' found in project"

            val methods = psiClass.findMethodsByName(methodName, false)
            if (methods.isEmpty()) {
                // Try inherited
                val inherited = psiClass.findMethodsByName(methodName, true)
                if (inherited.isNotEmpty()) {
                    return@nonBlocking "No method '$methodName' defined directly in '$className'. " +
                        "Found in superclass: ${inherited.first().containingClass?.name}. " +
                        "Use class_name='${inherited.first().containingClass?.qualifiedName}' to see it."
                }
                return@nonBlocking "No method '$methodName' found in '$className'. " +
                    "Available methods: ${psiClass.methods.take(20).joinToString(", ") { it.name }}"
            }

            val results = methods.mapIndexed { idx, method ->
                formatMethodWithContext(project, method, contextLines, if (methods.size > 1) idx + 1 else null)
            }
            results.joinToString("\n---\n")
        }.inSmartMode(project).executeSynchronously()

        return ToolResult(
            content = content,
            summary = "Method body: $className#$methodName",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }

    private fun formatMethodWithContext(project: Project, method: PsiMethod, contextLines: Int, overloadIndex: Int?): String {
        val file = method.containingFile ?: return "(no containing file)"
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return "(no document)"
        val filePath = PsiToolUtils.relativePath(project, file.virtualFile?.path ?: "unknown")

        val methodStartLine = document.getLineNumber(method.textRange.startOffset)
        val methodEndLine = document.getLineNumber(method.textRange.endOffset)

        // Include annotations above the method
        val annotationStartLine = method.modifierList.annotations.minOfOrNull {
            document.getLineNumber(it.textRange.startOffset)
        } ?: methodStartLine
        val effectiveStartLine = minOf(annotationStartLine, methodStartLine)

        val startLine = maxOf(0, effectiveStartLine - contextLines)
        val endLine = minOf(document.lineCount - 1, methodEndLine + contextLines)

        val sb = StringBuilder()
        val overloadLabel = if (overloadIndex != null) " (overload #$overloadIndex)" else ""
        sb.appendLine("// $filePath:${effectiveStartLine + 1}$overloadLabel")

        for (lineNum in startLine..endLine) {
            val lineStart = document.getLineStartOffset(lineNum)
            val lineEnd = document.getLineEndOffset(lineNum)
            val lineText = document.getText(com.intellij.openapi.util.TextRange(lineStart, lineEnd))
            sb.appendLine("${lineNum + 1}: $lineText")
        }

        return sb.toString().trimEnd()
    }
}
```

- [ ] **Step 2:** Register `"get_method_body"` in `ToolCategoryRegistry` `core` category.

- [ ] **Step 3:** Register in ToolRegistry initialization.

- [ ] **Step 4:** Compile: `./gradlew :agent:compileKotlin`

- [ ] **Step 5:** Commit: `feat(psi): add get_method_body tool for targeted method source retrieval`

---

### Task 13: Create Gradle tools (3 tools)

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/GradleDependenciesTool.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/GradleTasksTool.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/GradlePropertiesTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ToolCategoryRegistry.kt`

**Approach:** The Gradle plugin (`org.jetbrains.plugins.gradle`) is NOT bundled in agent's `build.gradle.kts`, and the ExternalSystem API is not available at compile time. Instead, parse `build.gradle.kts` / `build.gradle` / `gradle.properties` files directly using `Dispatchers.IO` file reading (same pattern as `SpringConfigTool`). This is simpler, has zero plugin dependencies, and works even for projects that haven't been synced yet.

- [ ] **Step 1:** Create `GradleDependenciesTool.kt` — parses `dependencies { }` blocks from build files:

```kotlin
package com.workflow.orchestrator.agent.tools.framework

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class GradleDependenciesTool : AgentTool {
    override val name = "gradle_dependencies"
    override val description = "List Gradle dependencies from build.gradle[.kts] files: group:artifact:version with configuration (implementation, testImplementation, api, compileOnly, runtimeOnly)."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "module" to ParameterProperty(type = "string", description = "Optional: submodule directory name (e.g., 'core', 'agent'). If omitted, reads root build file."),
            "configuration" to ParameterProperty(type = "string", description = "Optional: filter by configuration (implementation, testImplementation, api, compileOnly, runtimeOnly)."),
            "search" to ParameterProperty(type = "string", description = "Optional: filter by groupId or artifactId substring.")
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.ORCHESTRATOR, WorkerType.TOOLER)

    // Dependency line patterns for both Groovy and Kotlin DSL
    private val depPatterns = listOf(
        // Kotlin DSL: implementation("group:artifact:version")
        Regex("""(\w+)\(\s*"([^"]+)"\s*\)"""),
        // Kotlin DSL: implementation(libs.xxx) — version catalog
        Regex("""(\w+)\(\s*libs\.([a-zA-Z0-9.]+)\s*\)"""),
        // Kotlin DSL: implementation(project(":module"))
        Regex("""(\w+)\(\s*project\(\s*"([^"]+)"\s*\)\s*\)"""),
        // Groovy DSL: implementation 'group:artifact:version'
        Regex("""(\w+)\s+'([^']+)'"""),
        // Groovy DSL: implementation "group:artifact:version"
        Regex("""(\w+)\s+"([^"]+)"(?!\()"""),
    )

    private val validConfigurations = setOf(
        "implementation", "api", "compileOnly", "compileOnlyApi",
        "runtimeOnly", "testImplementation", "testCompileOnly", "testRuntimeOnly",
        "annotationProcessor", "kapt", "ksp",
        "intellijPlatform" // IntelliJ plugin projects
    )

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val basePath = project.basePath
            ?: return ToolResult("Error: project base path not available", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val moduleFilter = params["module"]?.jsonPrimitive?.content
        val configFilter = params["configuration"]?.jsonPrimitive?.content?.lowercase()
        val searchFilter = params["search"]?.jsonPrimitive?.content?.lowercase()

        return withContext(Dispatchers.IO) {
            val buildFile = findBuildFile(File(basePath), moduleFilter)
                ?: return@withContext ToolResult(
                    if (moduleFilter != null) "No build.gradle[.kts] found in module '$moduleFilter'. Available modules: ${listModules(File(basePath)).joinToString(", ")}"
                    else "No build.gradle[.kts] found in project root.",
                    "No Gradle", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                )

            val dependencies = parseDependencies(buildFile)
            if (dependencies.isEmpty()) {
                return@withContext ToolResult("No dependencies found in ${buildFile.name}.", "No dependencies", 5)
            }

            val filtered = dependencies.filter { dep ->
                val matchesConfig = configFilter == null || dep.configuration.lowercase() == configFilter
                val matchesSearch = searchFilter == null ||
                    dep.coordinate.lowercase().contains(searchFilter)
                matchesConfig && matchesSearch
            }

            if (filtered.isEmpty()) {
                return@withContext ToolResult("No dependencies matching filters.", "No matches", 5)
            }

            val grouped = filtered.groupBy { it.configuration }
                .toSortedMap()

            val content = buildString {
                val label = moduleFilter ?: "root"
                appendLine("Gradle dependencies for '$label' (${filtered.size} total):")
                appendLine()
                for ((config, deps) in grouped) {
                    appendLine("$config (${deps.size}):")
                    for (dep in deps.sortedBy { it.coordinate }) {
                        appendLine("  ${dep.coordinate}")
                    }
                    appendLine()
                }
            }

            ToolResult(content.trimEnd(), "${filtered.size} dependencies", TokenEstimator.estimate(content))
        }
    }

    private fun parseDependencies(buildFile: File): List<GradleDep> {
        val text = buildFile.readText()
        val deps = mutableListOf<GradleDep>()

        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("//") || trimmed.startsWith("/*")) continue

            for (pattern in depPatterns) {
                val match = pattern.find(trimmed) ?: continue
                val config = match.groupValues[1]
                if (config !in validConfigurations) continue
                val coordinate = match.groupValues[2]
                deps.add(GradleDep(config, coordinate))
                break // only first pattern match per line
            }
        }
        return deps
    }

    private fun findBuildFile(baseDir: File, module: String?): File? {
        val dir = if (module != null) File(baseDir, module) else baseDir
        return File(dir, "build.gradle.kts").takeIf { it.isFile }
            ?: File(dir, "build.gradle").takeIf { it.isFile }
    }

    private fun listModules(baseDir: File): List<String> {
        val settingsFile = File(baseDir, "settings.gradle.kts").takeIf { it.isFile }
            ?: File(baseDir, "settings.gradle").takeIf { it.isFile }
            ?: return emptyList()
        val includePattern = Regex("""include\(\s*"([^"]+)"\s*\)""")
        return settingsFile.readText().lines().mapNotNull { line ->
            includePattern.find(line.trim())?.groupValues?.get(1)?.removePrefix(":")
        }
    }

    private data class GradleDep(val configuration: String, val coordinate: String)
}
```

- [ ] **Step 2:** Create `GradleTasksTool.kt` — parses task definitions from build files:

```kotlin
package com.workflow.orchestrator.agent.tools.framework

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class GradleTasksTool : AgentTool {
    override val name = "gradle_tasks"
    override val description = "List Gradle tasks defined in build.gradle[.kts] files: custom tasks, task registrations, and standard lifecycle tasks."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "module" to ParameterProperty(type = "string", description = "Optional: submodule directory name. If omitted, reads root build file."),
            "search" to ParameterProperty(type = "string", description = "Optional: filter tasks by name substring.")
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.ORCHESTRATOR, WorkerType.TOOLER)

    // Task definition patterns
    private val taskPatterns = listOf(
        // tasks.register<Type>("name")
        Regex("""tasks\.register<(\w+)>\(\s*"(\w+)"\s*\)"""),
        // tasks.register("name")
        Regex("""tasks\.register\(\s*"(\w+)"\s*\)"""),
        // task("name") or task<Type>("name")
        Regex("""task(?:<(\w+)>)?\(\s*"(\w+)"\s*\)"""),
        // tasks.named("name")
        Regex("""tasks\.named\(\s*"(\w+)"\s*\)"""),
        // Groovy: task name(type: Type)
        Regex("""task\s+(\w+)\s*\("""),
    )

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val basePath = project.basePath
            ?: return ToolResult("Error: project base path not available", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val moduleFilter = params["module"]?.jsonPrimitive?.content
        val searchFilter = params["search"]?.jsonPrimitive?.content?.lowercase()

        return withContext(Dispatchers.IO) {
            val buildFile = findBuildFile(File(basePath), moduleFilter)
                ?: return@withContext ToolResult("No build.gradle[.kts] found.", "No Gradle", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

            val tasks = parseTasks(buildFile)
            val filtered = if (searchFilter != null) {
                tasks.filter { it.name.lowercase().contains(searchFilter) }
            } else { tasks }

            if (filtered.isEmpty()) {
                return@withContext ToolResult("No custom tasks found in ${buildFile.name}.", "No tasks", 5)
            }

            val content = buildString {
                val label = moduleFilter ?: "root"
                appendLine("Gradle tasks for '$label' (${filtered.size}):")
                for (task in filtered.sortedBy { it.name }) {
                    val typeStr = if (task.type != null) " (${task.type})" else ""
                    appendLine("  ${task.name}$typeStr")
                }
            }

            ToolResult(content.trimEnd(), "${filtered.size} tasks", TokenEstimator.estimate(content))
        }
    }

    private fun parseTasks(buildFile: File): List<GradleTask> {
        val text = buildFile.readText()
        val tasks = mutableListOf<GradleTask>()
        val seenNames = mutableSetOf<String>()

        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("//") || trimmed.startsWith("/*")) continue

            for (pattern in taskPatterns) {
                val match = pattern.find(trimmed) ?: continue
                val groups = match.groupValues.drop(1).filter { it.isNotBlank() }
                val name: String
                val type: String?

                when (groups.size) {
                    1 -> { name = groups[0]; type = null }
                    2 -> {
                        // If first looks like a type (starts with uppercase), it's Type+Name
                        if (groups[0].first().isUpperCase()) {
                            type = groups[0]; name = groups[1]
                        } else {
                            name = groups[0]; type = groups.getOrNull(1)
                        }
                    }
                    else -> continue
                }

                if (seenNames.add(name)) {
                    tasks.add(GradleTask(name, type))
                }
                break
            }
        }
        return tasks
    }

    private fun findBuildFile(baseDir: File, module: String?): File? {
        val dir = if (module != null) File(baseDir, module) else baseDir
        return File(dir, "build.gradle.kts").takeIf { it.isFile }
            ?: File(dir, "build.gradle").takeIf { it.isFile }
    }

    private data class GradleTask(val name: String, val type: String?)
}
```

- [ ] **Step 3:** Create `GradlePropertiesTool.kt` — reads `gradle.properties` from project root and submodules:

```kotlin
package com.workflow.orchestrator.agent.tools.framework

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Properties

class GradlePropertiesTool : AgentTool {
    override val name = "gradle_properties"
    override val description = "Read Gradle project properties from gradle.properties files (root + submodules). Shows property name → value."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "search" to ParameterProperty(type = "string", description = "Optional: filter properties by name substring.")
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.ORCHESTRATOR, WorkerType.TOOLER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val basePath = project.basePath
            ?: return ToolResult("Error: project base path not available", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val searchFilter = params["search"]?.jsonPrimitive?.content?.lowercase()

        return withContext(Dispatchers.IO) {
            val allProperties = mutableMapOf<String, MutableList<PropertySource>>()

            // Read root gradle.properties
            val rootProps = File(basePath, "gradle.properties")
            if (rootProps.isFile) {
                readPropertiesFile(rootProps, "gradle.properties", allProperties)
            }

            // Read submodule gradle.properties
            val baseDir = File(basePath)
            baseDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.forEach { subDir ->
                val subProps = File(subDir, "gradle.properties")
                if (subProps.isFile) {
                    readPropertiesFile(subProps, "${subDir.name}/gradle.properties", allProperties)
                }
            }

            // Also read gradle/libs.versions.toml if it exists (version catalog)
            val versionCatalog = File(basePath, "gradle/libs.versions.toml")
            if (versionCatalog.isFile) {
                readVersionCatalog(versionCatalog, allProperties)
            }

            if (allProperties.isEmpty()) {
                return@withContext ToolResult("No gradle.properties files found.", "No properties", 5)
            }

            val filtered = if (searchFilter != null) {
                allProperties.filter { (key, _) -> key.lowercase().contains(searchFilter) }
            } else { allProperties }

            if (filtered.isEmpty()) {
                return@withContext ToolResult("No properties matching '$searchFilter'.", "No matches", 5)
            }

            val content = buildString {
                appendLine("Gradle properties (${filtered.size}):")
                appendLine()

                // Group by source file
                val bySource = mutableMapOf<String, MutableList<Pair<String, String>>>()
                for ((key, sources) in filtered) {
                    for (source in sources) {
                        bySource.getOrPut(source.file) { mutableListOf() }.add(key to source.value)
                    }
                }

                for ((file, props) in bySource) {
                    appendLine("[$file]")
                    for ((key, value) in props.sortedBy { it.first }) {
                        val displayValue = if (value.length > 80) value.take(77) + "..." else value
                        appendLine("  $key = $displayValue")
                    }
                    appendLine()
                }
            }

            ToolResult(content.trimEnd(), "${filtered.size} properties", TokenEstimator.estimate(content))
        }
    }

    private fun readPropertiesFile(file: File, relativePath: String, target: MutableMap<String, MutableList<PropertySource>>) {
        val props = Properties()
        file.inputStream().use { props.load(it) }
        for ((key, value) in props) {
            target.getOrPut(key.toString()) { mutableListOf() }
                .add(PropertySource(value.toString(), relativePath))
        }
    }

    private fun readVersionCatalog(file: File, target: MutableMap<String, MutableList<PropertySource>>) {
        // Parse [versions] section of libs.versions.toml
        var inVersions = false
        for (line in file.readLines()) {
            val trimmed = line.trim()
            if (trimmed == "[versions]") { inVersions = true; continue }
            if (trimmed.startsWith("[") && inVersions) break
            if (!inVersions || trimmed.isBlank() || trimmed.startsWith("#")) continue

            val eqIdx = trimmed.indexOf('=')
            if (eqIdx < 0) continue
            val key = "catalog.${trimmed.substring(0, eqIdx).trim()}"
            val value = trimmed.substring(eqIdx + 1).trim().removeSurrounding("\"")
            target.getOrPut(key) { mutableListOf() }
                .add(PropertySource(value, "gradle/libs.versions.toml"))
        }
    }

    private data class PropertySource(val value: String, val file: String)
}
```

- [ ] **Step 4:** Register all 3 tools in `ToolCategoryRegistry` `framework` category: add `"gradle_dependencies"`, `"gradle_tasks"`, `"gradle_properties"` to the tools list.

- [ ] **Step 5:** Compile: `./gradlew :agent:compileKotlin`

- [ ] **Step 6:** Commit: `feat(framework): add Gradle tools — dependencies, tasks, properties (file-based parsing)`

---

### Task 14: Create Spring Boot Endpoints Tool (rich endpoint discovery)

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/SpringBootEndpointsTool.kt`

The existing `SpringEndpointsTool` does basic `@RequestMapping` annotation scanning. IntelliJ's Endpoints tool window shows much richer data: **resolved context paths**, **property placeholder resolution**, **full parameter annotations**, **request/response content types**. This tool enhances endpoint discovery by resolving the full URL path from `server.servlet.context-path` + class-level `@RequestMapping` + method-level mapping, and showing complete parameter details.

- [ ] **Step 1:** Create the tool that resolves full endpoint URLs:

```kotlin
package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class SpringBootEndpointsTool : AgentTool {
    override val name = "spring_boot_endpoints"
    override val description = "Rich HTTP endpoint discovery for Spring Boot projects. Shows: full resolved URL (with context-path), " +
        "HTTP method, handler class.method, parameter annotations (@PathVariable, @RequestParam, @RequestBody, @Valid), " +
        "return type, consumes/produces media types. More detailed than spring_endpoints."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "filter" to ParameterProperty(type = "string", description = "Optional: filter by URL path, HTTP method, or class name."),
            "class_name" to ParameterProperty(type = "string", description = "Optional: show only endpoints from this controller class.")
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.ORCHESTRATOR, WorkerType.CODER)

    private val httpMappings = mapOf(
        "org.springframework.web.bind.annotation.GetMapping" to "GET",
        "org.springframework.web.bind.annotation.PostMapping" to "POST",
        "org.springframework.web.bind.annotation.PutMapping" to "PUT",
        "org.springframework.web.bind.annotation.DeleteMapping" to "DELETE",
        "org.springframework.web.bind.annotation.PatchMapping" to "PATCH",
        "org.springframework.web.bind.annotation.RequestMapping" to null  // method resolved from annotation
    )

    private val paramAnnotations = listOf(
        "PathVariable", "RequestParam", "RequestBody", "RequestHeader",
        "CookieValue", "ModelAttribute", "Valid", "Validated"
    )

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        val filter = params["filter"]?.jsonPrimitive?.contentOrNull
        val classFilter = params["class_name"]?.jsonPrimitive?.contentOrNull

        val content = ReadAction.nonBlocking<String> {
            collectDetailedEndpoints(project, filter, classFilter)
        }.inSmartMode(project).executeSynchronously()

        return ToolResult(content, "Spring Boot endpoints", TokenEstimator.estimate(content))
    }

    private fun collectDetailedEndpoints(project: Project, filter: String?, classFilter: String?): String {
        val scope = GlobalSearchScope.projectScope(project)
        val allScope = GlobalSearchScope.allScope(project)
        val facade = JavaPsiFacade.getInstance(project)

        // Resolve context path from Spring config
        val contextPath = resolveContextPath(project)

        // Find controller classes
        val controllerClasses = if (classFilter != null) {
            val cls = PsiToolUtils.findClassAnywhere(project, classFilter)
            if (cls != null) listOf(cls) else return "No class '$classFilter' found."
        } else {
            val classes = mutableSetOf<PsiClass>()
            for (fqn in listOf(
                "org.springframework.web.bind.annotation.RestController",
                "org.springframework.stereotype.Controller"
            )) {
                val ann = facade.findClass(fqn, allScope)
                if (ann != null) classes.addAll(AnnotatedElementsSearch.searchPsiClasses(ann, scope).findAll())
            }
            classes.toList()
        }

        if (controllerClasses.isEmpty()) return "No @RestController or @Controller classes found."

        val endpoints = mutableListOf<EndpointDetail>()
        for (cls in controllerClasses) {
            val classPath = extractPath(cls.getAnnotation("org.springframework.web.bind.annotation.RequestMapping"))

            for (method in cls.methods) {
                for ((annFqn, httpMethod) in httpMappings) {
                    val annotation = method.getAnnotation(annFqn) ?: continue
                    val methodPath = extractPath(annotation)
                    val fullPath = "${contextPath}${classPath}${methodPath}".ifBlank { "/" }
                    val resolvedMethod = httpMethod ?: extractHttpMethod(annotation) ?: "GET"

                    val paramDetails = method.parameterList.parameters.map { param ->
                        val annotations = paramAnnotations.mapNotNull { annName ->
                            val pa = param.getAnnotation("org.springframework.web.bind.annotation.$annName")
                                ?: param.getAnnotation("jakarta.validation.constraints.$annName")
                                ?: param.getAnnotation("jakarta.validation.$annName")
                            if (pa != null) {
                                val value = pa.findAttributeValue("value")?.text?.removeSurrounding("\"")
                                if (value != null && value.isNotBlank()) "@$annName(\"$value\")" else "@$annName"
                            } else null
                        }
                        val prefix = if (annotations.isNotEmpty()) "${annotations.joinToString(" ")} " else ""
                        "$prefix${param.type.presentableText} ${param.name}"
                    }

                    val returnType = method.returnType?.presentableText ?: "void"
                    val consumes = extractMediaType(annotation, "consumes")
                    val produces = extractMediaType(annotation, "produces")

                    endpoints.add(EndpointDetail(
                        httpMethod = resolvedMethod,
                        path = fullPath,
                        className = cls.name ?: "?",
                        methodName = method.name,
                        params = paramDetails,
                        returnType = returnType,
                        consumes = consumes,
                        produces = produces,
                        file = PsiToolUtils.relativePath(project, cls.containingFile?.virtualFile?.path ?: "")
                    ))
                }
            }
        }

        val filtered = if (filter != null) {
            endpoints.filter { ep ->
                ep.path.contains(filter, ignoreCase = true) ||
                    ep.httpMethod.contains(filter, ignoreCase = true) ||
                    ep.className.contains(filter, ignoreCase = true)
            }
        } else endpoints

        if (filtered.isEmpty()) return "No endpoints found${if (filter != null) " matching '$filter'" else ""}."

        val sorted = filtered.sortedWith(compareBy({ it.path }, { it.httpMethod }))
        return buildString {
            if (contextPath.isNotBlank()) appendLine("Context path: $contextPath")
            appendLine("Endpoints (${sorted.size}):")
            appendLine()
            for (ep in sorted.take(100)) {
                appendLine("${ep.httpMethod.padEnd(7)} ${ep.path}")
                appendLine("  Handler: ${ep.className}.${ep.methodName}()")
                appendLine("  Params:  ${ep.params.joinToString(", ").ifBlank { "(none)" }}")
                appendLine("  Returns: ${ep.returnType}")
                if (ep.consumes != null) appendLine("  Consumes: ${ep.consumes}")
                if (ep.produces != null) appendLine("  Produces: ${ep.produces}")
                appendLine("  File:    ${ep.file}")
                appendLine()
            }
            if (sorted.size > 100) appendLine("... (${sorted.size - 100} more)")
        }.trimEnd()
    }

    private fun resolveContextPath(project: Project): String {
        // Read from application.properties/yml
        val basePath = project.basePath ?: return ""
        val propsFile = java.io.File(basePath, "src/main/resources/application.properties")
        if (propsFile.isFile) {
            for (line in propsFile.readLines()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("server.servlet.context-path=")) {
                    return trimmed.substringAfter("=").trim()
                }
                // Spring Boot 3.x
                if (trimmed.startsWith("server.servlet.context-path=")) {
                    return trimmed.substringAfter("=").trim()
                }
            }
        }
        // Check YAML
        val ymlFile = java.io.File(basePath, "src/main/resources/application.yml")
        if (ymlFile.isFile) {
            for (line in ymlFile.readLines()) {
                if (line.trim().startsWith("context-path:")) {
                    return line.trim().substringAfter(":").trim().removeSurrounding("\"")
                }
            }
        }
        return ""
    }

    private fun extractPath(annotation: PsiAnnotation?): String {
        if (annotation == null) return ""
        val value = annotation.findAttributeValue("value")
            ?: annotation.findAttributeValue("path")
        return value?.text?.removeSurrounding("\"")?.removeSurrounding("{", "}") ?: ""
    }

    private fun extractHttpMethod(annotation: PsiAnnotation): String? {
        val method = annotation.findAttributeValue("method") ?: return null
        return method.text?.replace("RequestMethod.", "")?.removeSurrounding("{", "}")
    }

    private fun extractMediaType(annotation: PsiAnnotation, attribute: String): String? {
        val value = annotation.findAttributeValue(attribute) ?: return null
        val text = value.text?.removeSurrounding("{", "}")?.removeSurrounding("\"")
        return if (text.isNullOrBlank() || text == "\"\"") null else text
    }

    private data class EndpointDetail(
        val httpMethod: String, val path: String, val className: String,
        val methodName: String, val params: List<String>, val returnType: String,
        val consumes: String?, val produces: String?, val file: String
    )
}
```

- [ ] **Step 2:** Compile: `./gradlew :agent:compileKotlin`

- [ ] **Step 3:** Commit: `feat(spring): add spring_boot_endpoints — rich endpoint discovery with context path, params, media types`

---

### Task 15: Create Spring Boot Auto-Configuration Tool

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/SpringBootAutoConfigTool.kt`

Scans `@AutoConfiguration`, `@Configuration`, and `@Conditional*` annotations to show what's auto-configured and why. Answers "why isn't my bean being created?" questions.

- [ ] **Step 1:** Create the tool — scans PSI for `@Configuration` + `@Conditional*` annotations:

```kotlin
package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class SpringBootAutoConfigTool : AgentTool {
    override val name = "spring_boot_autoconfig"
    override val description = "List Spring Boot auto-configuration classes with their @Conditional* conditions. " +
        "Shows: @ConditionalOnClass, @ConditionalOnProperty, @ConditionalOnBean, @ConditionalOnMissingBean, etc. " +
        "Helps debug 'why isn't my bean being created?' questions."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "filter" to ParameterProperty(type = "string", description = "Optional: filter by class name or condition (e.g., 'DataSource', 'OnClass')."),
            "project_only" to ParameterProperty(type = "boolean", description = "If true, only show @Configuration classes in project scope (not library auto-configs). Default: true.")
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.ORCHESTRATOR)

    private val conditionalAnnotations = listOf(
        "ConditionalOnClass", "ConditionalOnMissingClass",
        "ConditionalOnBean", "ConditionalOnMissingBean",
        "ConditionalOnProperty", "ConditionalOnResource",
        "ConditionalOnWebApplication", "ConditionalOnNotWebApplication",
        "ConditionalOnExpression", "ConditionalOnJava",
        "ConditionalOnSingleCandidate", "ConditionalOnCloudPlatform"
    )

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        val filter = params["filter"]?.jsonPrimitive?.contentOrNull
        val projectOnly = params["project_only"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: true

        val content = ReadAction.nonBlocking<String> {
            collectAutoConfigs(project, filter, projectOnly)
        }.inSmartMode(project).executeSynchronously()

        return ToolResult(content, "Auto-configuration analysis", TokenEstimator.estimate(content))
    }

    private fun collectAutoConfigs(project: Project, filter: String?, projectOnly: Boolean): String {
        val searchScope = if (projectOnly) GlobalSearchScope.projectScope(project) else GlobalSearchScope.allScope(project)
        val allScope = GlobalSearchScope.allScope(project)
        val facade = JavaPsiFacade.getInstance(project)

        val configClasses = mutableSetOf<PsiClass>()

        // Find @Configuration classes
        val configAnnotation = facade.findClass("org.springframework.context.annotation.Configuration", allScope)
        if (configAnnotation != null) {
            configClasses.addAll(AnnotatedElementsSearch.searchPsiClasses(configAnnotation, searchScope).findAll())
        }

        // Find @AutoConfiguration classes (Spring Boot 3.x)
        val autoConfigAnnotation = facade.findClass("org.springframework.boot.autoconfigure.AutoConfiguration", allScope)
        if (autoConfigAnnotation != null) {
            configClasses.addAll(AnnotatedElementsSearch.searchPsiClasses(autoConfigAnnotation, searchScope).findAll())
        }

        if (configClasses.isEmpty()) return "No @Configuration or @AutoConfiguration classes found."

        val results = configClasses.mapNotNull { cls ->
            val conditions = extractConditions(cls)
            val enableAnnotations = extractEnableAnnotations(cls)
            if (conditions.isEmpty() && enableAnnotations.isEmpty() && filter != null) return@mapNotNull null

            val name = cls.qualifiedName ?: cls.name ?: return@mapNotNull null
            if (filter != null && !name.contains(filter, ignoreCase = true) &&
                !conditions.any { it.contains(filter, ignoreCase = true) }) return@mapNotNull null

            AutoConfigInfo(name, conditions, enableAnnotations,
                PsiToolUtils.relativePath(project, cls.containingFile?.virtualFile?.path ?: ""))
        }.sortedBy { it.name }

        if (results.isEmpty()) return "No auto-configurations found${if (filter != null) " matching '$filter'" else ""}."

        return buildString {
            appendLine("Auto-configurations (${results.size}):")
            appendLine()
            for (config in results.take(50)) {
                appendLine("${config.name}")
                if (config.enableAnnotations.isNotEmpty()) {
                    config.enableAnnotations.forEach { appendLine("  $it") }
                }
                if (config.conditions.isNotEmpty()) {
                    config.conditions.forEach { appendLine("  $it") }
                } else {
                    appendLine("  (no conditions — always active)")
                }
                appendLine("  File: ${config.file}")
                appendLine()
            }
            if (results.size > 50) appendLine("... (${results.size - 50} more)")
        }.trimEnd()
    }

    private fun extractConditions(cls: PsiClass): List<String> {
        val conditions = mutableListOf<String>()
        for (annName in conditionalAnnotations) {
            for (prefix in listOf("org.springframework.boot.autoconfigure.condition.", "org.springframework.context.annotation.")) {
                val annotation = cls.getAnnotation("$prefix$annName") ?: continue
                val value = annotation.findAttributeValue("value")?.text
                    ?.removeSurrounding("\"")?.removeSurrounding("{", "}")
                val name = annotation.findAttributeValue("name")?.text
                    ?.removeSurrounding("\"")?.removeSurrounding("{", "}")
                val havingValue = annotation.findAttributeValue("havingValue")?.text?.removeSurrounding("\"")

                val detail = buildString {
                    append("@$annName")
                    val params = mutableListOf<String>()
                    if (!value.isNullOrBlank()) params.add(value)
                    if (!name.isNullOrBlank()) params.add("name=$name")
                    if (!havingValue.isNullOrBlank()) params.add("havingValue=$havingValue")
                    if (params.isNotEmpty()) append("(${params.joinToString(", ")})")
                }
                conditions.add(detail)
            }
        }
        return conditions
    }

    private fun extractEnableAnnotations(cls: PsiClass): List<String> {
        return cls.annotations.filter { ann ->
            ann.qualifiedName?.contains("Enable") == true
        }.mapNotNull { ann ->
            val name = ann.qualifiedName?.substringAfterLast('.') ?: return@mapNotNull null
            "@$name"
        }
    }

    private data class AutoConfigInfo(
        val name: String, val conditions: List<String>,
        val enableAnnotations: List<String>, val file: String
    )
}
```

- [ ] **Step 2:** Compile: `./gradlew :agent:compileKotlin`

- [ ] **Step 3:** Commit: `feat(spring): add spring_boot_autoconfig — conditional auto-configuration analysis`

---

### Task 16: Create Spring Boot @ConfigurationProperties Tool

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/SpringBootConfigPropertiesTool.kt`

Shows type-safe `@ConfigurationProperties` classes with their field types, defaults, and `@Valid`/`@NotNull` constraints. Answers "what properties can I configure for X?".

- [ ] **Step 1:** Create the tool:

```kotlin
package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class SpringBootConfigPropertiesTool : AgentTool {
    override val name = "spring_boot_config_properties"
    override val description = "List @ConfigurationProperties classes — the type-safe way Spring Boot binds properties. " +
        "Shows: prefix, field names with types, default values, @Valid/@NotNull constraints. " +
        "Answers 'what properties can I configure for feature X?'"
    override val parameters = FunctionParameters(
        properties = mapOf(
            "prefix" to ParameterProperty(type = "string", description = "Optional: filter by property prefix (e.g., 'spring.datasource', 'app.security')."),
            "class_name" to ParameterProperty(type = "string", description = "Optional: specific @ConfigurationProperties class name.")
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()
        val prefixFilter = params["prefix"]?.jsonPrimitive?.contentOrNull
        val classFilter = params["class_name"]?.jsonPrimitive?.contentOrNull

        val content = ReadAction.nonBlocking<String> {
            collectConfigProperties(project, prefixFilter, classFilter)
        }.inSmartMode(project).executeSynchronously()

        return ToolResult(content, "Configuration properties", TokenEstimator.estimate(content))
    }

    private fun collectConfigProperties(project: Project, prefixFilter: String?, classFilter: String?): String {
        val scope = GlobalSearchScope.projectScope(project)
        val allScope = GlobalSearchScope.allScope(project)
        val facade = JavaPsiFacade.getInstance(project)

        val configPropsClasses = mutableSetOf<PsiClass>()
        for (fqn in listOf(
            "org.springframework.boot.context.properties.ConfigurationProperties",
            "org.springframework.boot.context.properties.ConstructorBinding"
        )) {
            val ann = facade.findClass(fqn, allScope) ?: continue
            configPropsClasses.addAll(AnnotatedElementsSearch.searchPsiClasses(ann, scope).findAll())
        }

        if (configPropsClasses.isEmpty()) return "No @ConfigurationProperties classes found in project."

        val results = configPropsClasses.mapNotNull { cls ->
            val annotation = cls.getAnnotation("org.springframework.boot.context.properties.ConfigurationProperties")
            val prefix = annotation?.findAttributeValue("prefix")?.text?.removeSurrounding("\"")
                ?: annotation?.findAttributeValue("value")?.text?.removeSurrounding("\"")
                ?: ""

            if (prefixFilter != null && !prefix.contains(prefixFilter, ignoreCase = true)) return@mapNotNull null
            if (classFilter != null && cls.name?.contains(classFilter, ignoreCase = true) != true &&
                cls.qualifiedName?.contains(classFilter, ignoreCase = true) != true) return@mapNotNull null

            val fields = cls.allFields.filter { !it.hasModifierProperty(PsiModifier.STATIC) }.map { field ->
                val validationAnnotations = listOf("NotNull", "NotBlank", "NotEmpty", "Min", "Max", "Size", "Pattern", "Email", "Valid")
                    .mapNotNull { annName ->
                        field.getAnnotation("jakarta.validation.constraints.$annName")?.let { "@$annName" }
                            ?: field.getAnnotation("javax.validation.constraints.$annName")?.let { "@$annName" }
                    }
                val defaultValue = field.initializer?.text
                ConfigField(
                    name = field.name,
                    type = field.type.presentableText,
                    defaultValue = defaultValue,
                    constraints = validationAnnotations
                )
            }

            ConfigPropsInfo(
                className = cls.name ?: "?",
                qualifiedName = cls.qualifiedName ?: "",
                prefix = prefix,
                fields = fields,
                file = PsiToolUtils.relativePath(project, cls.containingFile?.virtualFile?.path ?: "")
            )
        }.sortedBy { it.prefix }

        if (results.isEmpty()) return "No @ConfigurationProperties found${if (prefixFilter != null) " with prefix '$prefixFilter'" else ""}."

        return buildString {
            appendLine("Configuration Properties (${results.size}):")
            appendLine()
            for (cp in results) {
                appendLine("@ConfigurationProperties(prefix = \"${cp.prefix}\")")
                appendLine("class ${cp.className}  (${cp.file})")
                for (field in cp.fields) {
                    val constraints = if (field.constraints.isNotEmpty()) " ${field.constraints.joinToString(" ")}" else ""
                    val default = if (field.defaultValue != null) " = ${field.defaultValue}" else ""
                    appendLine("  ${cp.prefix}.${field.name}: ${field.type}$default$constraints")
                }
                appendLine()
            }
        }.trimEnd()
    }

    private data class ConfigField(val name: String, val type: String, val defaultValue: String?, val constraints: List<String>)
    private data class ConfigPropsInfo(val className: String, val qualifiedName: String, val prefix: String, val fields: List<ConfigField>, val file: String)
}
```

- [ ] **Step 2:** Compile: `./gradlew :agent:compileKotlin`

- [ ] **Step 3:** Commit: `feat(spring): add spring_boot_config_properties — type-safe property binding analysis`

---

### Task 17: Create Spring Boot Actuator Endpoints Tool

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/SpringBootActuatorTool.kt`

Shows configured actuator endpoints and their exposure settings. Useful for understanding observability setup.

- [ ] **Step 1:** Create the tool — scans classpath for actuator dependencies and configuration:

```kotlin
package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.framework.MavenUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.Properties

class SpringBootActuatorTool : AgentTool {
    override val name = "spring_boot_actuator"
    override val description = "Analyze Spring Boot Actuator setup: detect actuator dependency, list configured endpoints, " +
        "exposure settings (web/jmx include/exclude), management port, base-path. " +
        "Reads from Maven dependencies + application.properties/yml."
    override val parameters = FunctionParameters(
        properties = emptyMap(),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.ORCHESTRATOR)

    private val defaultEndpoints = listOf(
        "health" to "Shows application health", "info" to "Application info",
        "metrics" to "Application metrics", "env" to "Environment properties",
        "beans" to "All Spring beans", "configprops" to "Configuration properties",
        "mappings" to "URL mappings", "loggers" to "Logger levels",
        "threaddump" to "Thread dump", "heapdump" to "Heap dump",
        "conditions" to "Auto-config conditions report", "shutdown" to "Graceful shutdown",
        "scheduledtasks" to "Scheduled tasks", "httptrace" to "HTTP trace",
        "caches" to "Cache managers", "flyway" to "Flyway migrations",
        "liquibase" to "Liquibase changelogs", "sessions" to "Session info",
        "prometheus" to "Prometheus metrics export"
    )

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val basePath = project.basePath
            ?: return ToolResult("Error: no project path", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        return withContext(Dispatchers.IO) {
            // Check if actuator is in dependencies
            val hasActuator = checkActuatorDependency(project, basePath)

            // Read actuator config from properties
            val config = readActuatorConfig(basePath)

            val content = buildString {
                appendLine("Spring Boot Actuator:")
                appendLine()

                if (!hasActuator) {
                    appendLine("⚠ spring-boot-starter-actuator NOT found in dependencies.")
                    appendLine("  Add: implementation(\"org.springframework.boot:spring-boot-starter-actuator\")")
                    return@buildString
                }

                appendLine("✓ spring-boot-starter-actuator detected")
                appendLine()

                // Management config
                val mgmtPort = config["management.server.port"]
                val baseMgmtPath = config["management.endpoints.web.base-path"] ?: "/actuator"
                appendLine("Management:")
                appendLine("  Base path: $baseMgmtPath")
                if (mgmtPort != null) appendLine("  Port: $mgmtPort (separate from app)")
                appendLine()

                // Exposure settings
                val webInclude = config["management.endpoints.web.exposure.include"] ?: "health"
                val webExclude = config["management.endpoints.web.exposure.exclude"] ?: ""
                appendLine("Web exposure:")
                appendLine("  Include: $webInclude")
                if (webExclude.isNotBlank()) appendLine("  Exclude: $webExclude")
                appendLine()

                // List endpoints with their status
                val includedEndpoints = if (webInclude == "*") {
                    defaultEndpoints.map { it.first }.toSet()
                } else {
                    webInclude.split(",").map { it.trim() }.toSet()
                }
                val excludedEndpoints = webExclude.split(",").map { it.trim() }.toSet()

                appendLine("Endpoints:")
                for ((name, desc) in defaultEndpoints) {
                    val exposed = name in includedEndpoints && name !in excludedEndpoints
                    val status = if (exposed) "✓" else "·"
                    val enabledProp = config["management.endpoint.$name.enabled"]
                    val enabledTag = when (enabledProp) {
                        "false" -> " (disabled)"
                        "true" -> " (explicitly enabled)"
                        else -> ""
                    }
                    appendLine("  $status ${name.padEnd(16)} — $desc$enabledTag")
                }

                // Custom health indicators
                val healthShowDetails = config["management.endpoint.health.show-details"] ?: "never"
                val healthShowComponents = config["management.endpoint.health.show-components"] ?: "never"
                appendLine()
                appendLine("Health endpoint:")
                appendLine("  Show details: $healthShowDetails")
                appendLine("  Show components: $healthShowComponents")
            }

            ToolResult(content.trimEnd(), "Actuator analysis", TokenEstimator.estimate(content))
        }
    }

    private fun checkActuatorDependency(project: Project, basePath: String): Boolean {
        // Check Maven
        val manager = MavenUtils.getMavenManager(project)
        if (manager != null) {
            val projects = MavenUtils.getMavenProjects(manager)
            return projects.any { mp ->
                MavenUtils.getDependencies(mp).any { dep ->
                    dep.artifactId == "spring-boot-starter-actuator"
                }
            }
        }
        // Check Gradle build files
        val buildFile = File(basePath, "build.gradle.kts").takeIf { it.isFile }
            ?: File(basePath, "build.gradle").takeIf { it.isFile }
        if (buildFile != null) {
            return buildFile.readText().contains("spring-boot-starter-actuator")
        }
        return false
    }

    private fun readActuatorConfig(basePath: String): Map<String, String> {
        val config = mutableMapOf<String, String>()
        // Search common locations
        for (dir in listOf("src/main/resources", "src/main/resources/config")) {
            val propsFile = File(basePath, "$dir/application.properties")
            if (propsFile.isFile) {
                val props = Properties()
                propsFile.inputStream().use { props.load(it) }
                props.forEach { k, v -> if (k.toString().startsWith("management.")) config[k.toString()] = v.toString() }
            }
            // YAML (simple line-based)
            for (ymlName in listOf("application.yml", "application.yaml")) {
                val ymlFile = File(basePath, "$dir/$ymlName")
                if (ymlFile.isFile) {
                    parseYamlManagement(ymlFile, config)
                }
            }
        }
        return config
    }

    private fun parseYamlManagement(file: File, target: MutableMap<String, String>) {
        // Simple YAML key extraction for management.* properties
        val keyStack = mutableListOf<Pair<Int, String>>()
        for (line in file.readLines()) {
            val trimmed = line.trimEnd()
            if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("---")) continue
            val indent = line.length - line.trimStart().length
            while (keyStack.isNotEmpty() && keyStack.last().first >= indent) keyStack.removeAt(keyStack.size - 1)
            val colonIdx = trimmed.indexOf(':')
            if (colonIdx < 0) continue
            val key = trimmed.substring(0, colonIdx).trim()
            val value = trimmed.substring(colonIdx + 1).trim()
            keyStack.add(indent to key)
            if (value.isNotEmpty() && !value.startsWith("#")) {
                val fullKey = keyStack.joinToString(".") { it.second }
                if (fullKey.startsWith("management.")) {
                    target[fullKey] = value.removeSurrounding("\"").removeSurrounding("'")
                }
            }
        }
    }
}
```

- [ ] **Step 2:** Compile: `./gradlew :agent:compileKotlin`

- [ ] **Step 3:** Commit: `feat(spring): add spring_boot_actuator — actuator endpoint analysis`

---

### Task 18: Create Maven Dependency Tree Tool

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/MavenDependencyTreeTool.kt`

The existing `maven_dependencies` only shows direct dependencies. This tool shows the full transitive tree — critical for understanding version conflicts and dependency hell.

- [ ] **Step 1:** Create the tool using `MavenProject.getDependencyTree()` via reflection:

```kotlin
package com.workflow.orchestrator.agent.tools.framework

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class MavenDependencyTreeTool : AgentTool {
    override val name = "maven_dependency_tree"
    override val description = "Show the full transitive dependency tree from Maven. Shows version conflicts, " +
        "dependency path, and which version was selected. Critical for diagnosing 'NoSuchMethodError' / classpath issues."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "module" to ParameterProperty(type = "string", description = "Optional: module name. If omitted, uses root project."),
            "artifact" to ParameterProperty(type = "string", description = "Optional: trace dependency path for a specific artifact (e.g., 'jackson-databind').")
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.ORCHESTRATOR, WorkerType.TOOLER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val moduleFilter = params["module"]?.jsonPrimitive?.contentOrNull
        val artifactFilter = params["artifact"]?.jsonPrimitive?.contentOrNull?.lowercase()

        return try {
            val manager = MavenUtils.getMavenManager(project)
                ?: return MavenUtils.noMavenError()
            val mavenProjects = MavenUtils.getMavenProjects(manager)
            if (mavenProjects.isEmpty()) return MavenUtils.noMavenError()

            val targetProject = MavenUtils.findMavenProject(mavenProjects, manager, moduleFilter)
                ?: return ToolResult(
                    "Module '$moduleFilter' not found. Available: ${MavenUtils.getProjectNames(mavenProjects)}",
                    "Not found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                )

            // Try to get dependency tree via MavenProject.getDependencyTree()
            val content = getDependencyTree(targetProject, artifactFilter)

            ToolResult(content, "Dependency tree", TokenEstimator.estimate(content))
        } catch (e: Exception) {
            ToolResult("Error building dependency tree: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun getDependencyTree(mavenProject: Any, artifactFilter: String?): String {
        // MavenProject.getDependencyTree() returns Collection<MavenArtifactNode>
        // Each node has: getArtifact() -> MavenArtifact, getDependencies() -> List<MavenArtifactNode>
        val tree = try {
            mavenProject.javaClass.getMethod("getDependencyTree").invoke(mavenProject) as? Collection<Any>
        } catch (_: NoSuchMethodException) {
            null
        } catch (_: Exception) {
            null
        }

        if (tree == null || tree.isEmpty()) {
            // Fallback: show direct dependencies with note
            val directDeps = MavenUtils.getDependencies(mavenProject)
            return buildString {
                appendLine("Direct dependencies (${directDeps.size}):")
                appendLine("(Transitive tree not available — Maven project may need re-import)")
                appendLine()
                for (dep in directDeps.sortedBy { "${it.groupId}:${it.artifactId}" }) {
                    appendLine("  ${dep.groupId}:${dep.artifactId}:${dep.version} [${dep.scope}]")
                }
            }.trimEnd()
        }

        val sb = StringBuilder()
        val projectName = MavenUtils.getDisplayName(mavenProject)
        sb.appendLine("Dependency tree for $projectName:")
        sb.appendLine()

        var totalNodes = 0
        for (node in tree) {
            totalNodes += formatTreeNode(node, sb, "", artifactFilter, depth = 0, maxDepth = 5)
        }

        if (artifactFilter != null && totalNodes == 0) {
            sb.appendLine("No dependencies matching '$artifactFilter' found in tree.")
        }

        return sb.toString().trimEnd()
    }

    private fun formatTreeNode(node: Any, sb: StringBuilder, indent: String, filter: String?, depth: Int, maxDepth: Int): Int {
        if (depth > maxDepth) {
            sb.appendLine("$indent  ... (truncated at depth $maxDepth)")
            return 0
        }

        val artifact = try {
            node.javaClass.getMethod("getArtifact").invoke(node)
        } catch (_: Exception) { return 0 }

        val groupId = try { artifact.javaClass.getMethod("getGroupId").invoke(artifact) as? String } catch (_: Exception) { "?" }
        val artifactId = try { artifact.javaClass.getMethod("getArtifactId").invoke(artifact) as? String } catch (_: Exception) { "?" }
        val version = try { artifact.javaClass.getMethod("getVersion").invoke(artifact) as? String } catch (_: Exception) { "?" }
        val scope = try { artifact.javaClass.getMethod("getScope").invoke(artifact) as? String } catch (_: Exception) { "compile" }

        val coordinate = "$groupId:$artifactId:$version"
        val matchesFilter = filter == null || coordinate.lowercase().contains(filter)

        var count = 0
        if (matchesFilter) {
            val scopeTag = if (scope != "compile") " [$scope]" else ""
            sb.appendLine("$indent$coordinate$scopeTag")
            count = 1
        }

        // Recurse into children
        @Suppress("UNCHECKED_CAST")
        val children = try {
            node.javaClass.getMethod("getDependencies").invoke(node) as? Collection<Any>
        } catch (_: Exception) { null }

        if (children != null) {
            val childIndent = if (matchesFilter) "$indent  " else indent
            for (child in children) {
                count += formatTreeNode(child, sb, childIndent, filter, depth + 1, maxDepth)
            }
        }

        return count
    }
}
```

- [ ] **Step 2:** Compile: `./gradlew :agent:compileKotlin`

- [ ] **Step 3:** Commit: `feat(maven): add maven_dependency_tree — transitive tree with conflict detection`

---

### Task 19: Create Maven Effective POM / Plugin Config Tool

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/MavenEffectivePomTool.kt`

The existing `maven_plugins` only lists plugin GAVs. This tool shows plugin **configurations** — compiler source/target, surefire includes/excludes, spring-boot-maven-plugin repackage settings, etc.

- [ ] **Step 1:** Create the tool:

```kotlin
package com.workflow.orchestrator.agent.tools.framework

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class MavenEffectivePomTool : AgentTool {
    override val name = "maven_effective_pom"
    override val description = "Show Maven plugin configurations from the effective POM. " +
        "Reveals: compiler source/target, surefire/failsafe includes/excludes, " +
        "spring-boot-maven-plugin settings, shade/assembly configs, resource filtering."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "module" to ParameterProperty(type = "string", description = "Optional: module name. If omitted, uses root project."),
            "plugin" to ParameterProperty(type = "string", description = "Optional: filter by plugin artifactId (e.g., 'maven-compiler-plugin', 'spring-boot-maven-plugin').")
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.ORCHESTRATOR, WorkerType.TOOLER)

    @Suppress("UNCHECKED_CAST")
    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val moduleFilter = params["module"]?.jsonPrimitive?.contentOrNull
        val pluginFilter = params["plugin"]?.jsonPrimitive?.contentOrNull?.lowercase()

        return try {
            val manager = MavenUtils.getMavenManager(project)
                ?: return MavenUtils.noMavenError()
            val mavenProjects = MavenUtils.getMavenProjects(manager)
            if (mavenProjects.isEmpty()) return MavenUtils.noMavenError()

            val targetProject = MavenUtils.findMavenProject(mavenProjects, manager, moduleFilter)
                ?: return ToolResult("Module not found.", "Not found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

            // Get plugins with configuration from MavenProject
            val plugins = try {
                targetProject.javaClass.getMethod("getDeclaredPlugins").invoke(targetProject) as List<Any>
            } catch (_: Exception) { emptyList() }

            val filtered = plugins.filter { plugin ->
                if (pluginFilter == null) return@filter true
                val artifactId = try { plugin.javaClass.getMethod("getArtifactId").invoke(plugin) as? String } catch (_: Exception) { null }
                artifactId?.lowercase()?.contains(pluginFilter) == true
            }

            if (filtered.isEmpty()) {
                return ToolResult(
                    "No plugins found${if (pluginFilter != null) " matching '$pluginFilter'" else ""}.",
                    "No plugins", 5
                )
            }

            val content = buildString {
                val projectName = MavenUtils.getDisplayName(targetProject)
                appendLine("Plugin configurations for $projectName:")
                appendLine()

                for (plugin in filtered) {
                    val groupId = try { plugin.javaClass.getMethod("getGroupId").invoke(plugin) as? String } catch (_: Exception) { "?" }
                    val artifactId = try { plugin.javaClass.getMethod("getArtifactId").invoke(plugin) as? String } catch (_: Exception) { "?" }
                    val version = try { plugin.javaClass.getMethod("getVersion").invoke(plugin) as? String } catch (_: Exception) { "" }

                    appendLine("$groupId:$artifactId${if (version.isNotBlank()) ":$version" else ""}")

                    // Get configuration XML element
                    val configElement = try {
                        plugin.javaClass.getMethod("getConfigurationElement").invoke(plugin)
                    } catch (_: Exception) {
                        try { plugin.javaClass.getMethod("getGoalConfiguration").invoke(plugin) } catch (_: Exception) { null }
                    }

                    if (configElement != null) {
                        val configXml = try {
                            configElement.javaClass.getMethod("getText").invoke(configElement) as? String
                                ?: configElement.toString()
                        } catch (_: Exception) { configElement.toString() }

                        // Pretty-print the config (indent each line)
                        val trimmedConfig = configXml.trim()
                        if (trimmedConfig.isNotBlank() && trimmedConfig != "<configuration/>") {
                            appendLine("  Configuration:")
                            for (line in trimmedConfig.lines().take(30)) {
                                appendLine("    $line")
                            }
                            if (trimmedConfig.lines().size > 30) appendLine("    ... (truncated)")
                        } else {
                            appendLine("  (no configuration)")
                        }
                    } else {
                        appendLine("  (no configuration)")
                    }

                    // Executions
                    val executions = try {
                        plugin.javaClass.getMethod("getExecutions").invoke(plugin) as? List<Any>
                    } catch (_: Exception) { null }
                    if (executions != null && executions.isNotEmpty()) {
                        appendLine("  Executions:")
                        for (exec in executions.take(10)) {
                            val id = try { exec.javaClass.getMethod("getExecutionId").invoke(exec) as? String } catch (_: Exception) { "?" }
                            val phase = try { exec.javaClass.getMethod("getPhase").invoke(exec) as? String } catch (_: Exception) { null }
                            val goals = try {
                                (exec.javaClass.getMethod("getGoals").invoke(exec) as? List<String>)?.joinToString(", ")
                            } catch (_: Exception) { null }
                            val phaseStr = if (phase != null) " (phase: $phase)" else ""
                            val goalsStr = if (goals != null) " goals: $goals" else ""
                            appendLine("    $id$phaseStr$goalsStr")
                        }
                    }

                    appendLine()
                }
            }

            ToolResult(content.trimEnd(), "Plugin configs", TokenEstimator.estimate(content))
        } catch (e: Exception) {
            ToolResult("Error reading plugin configs: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }
}
```

- [ ] **Step 2:** Compile: `./gradlew :agent:compileKotlin`

- [ ] **Step 3:** Commit: `feat(maven): add maven_effective_pom — plugin configurations + executions`

---

### Task 20: Update DynamicToolSelector + PromptAssembler for new tools

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/DynamicToolSelector.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt`

Without these changes, the LLM won't know the new tools exist (DynamicToolSelector won't select them) and won't understand when to use them (PromptAssembler won't explain them).

- [ ] **Step 1:** In `DynamicToolSelector.kt`, add `get_annotations` and `get_method_body` to `ALWAYS_INCLUDE` (line 32-39). These are core tools useful for any task:

```kotlin
private val ALWAYS_INCLUDE = setOf(
    "read_file", "edit_file", "search_code", "run_command", "glob_files",
    "file_structure", "find_definition", "find_references", "type_hierarchy", "call_hierarchy",
    "get_annotations", "get_method_body",
    "diagnostics", "format_code", "optimize_imports",
    "agent", "delegate_task", "think"
)
```

- [ ] **Step 2:** Expand `SPRING_TOOL_NAMES` (line 92-96) with Spring Boot tools:

```kotlin
private val SPRING_TOOL_NAMES = setOf(
    "spring_context", "spring_endpoints", "spring_bean_graph", "spring_config",
    "spring_version_info", "spring_profiles", "spring_repositories",
    "spring_security_config", "spring_scheduled_tasks", "spring_event_listeners",
    // Spring Boot
    "spring_boot_endpoints", "spring_boot_autoconfig",
    "spring_boot_config_properties", "spring_boot_actuator"
)
```

- [ ] **Step 3:** Expand `MAVEN_TOOL_NAMES` (line 98-101) with new Maven tools:

```kotlin
private val MAVEN_TOOL_NAMES = setOf(
    "maven_dependencies", "maven_properties", "maven_plugins", "maven_profiles",
    "maven_dependency_tree", "maven_effective_pom",
    "project_modules"
)
```

- [ ] **Step 4:** Add new `GRADLE_TOOL_NAMES` set after `MAVEN_TOOL_NAMES`:

```kotlin
private val GRADLE_TOOL_NAMES = setOf(
    "gradle_dependencies", "gradle_tasks", "gradle_properties"
)
```

- [ ] **Step 5:** Update the `spring` ToolGroup (line 164-171) keywords to include Spring Boot terms:

```kotlin
ToolGroup(
    "spring",
    setOf("spring", "bean", "endpoint", "controller", "service", "repository",
        "autowired", "injection", "config", "application.properties",
        "application.yml", "security", "auth", "authentication", "authorization",
        "scheduled", "cron", "event", "listener",
        // Spring Boot specific
        "spring boot", "auto-config", "autoconfig", "conditional", "actuator",
        "configuration properties", "configprops", "health check", "metrics",
        "starter", "@ConditionalOn"),
    SPRING_TOOL_NAMES
),
```

- [ ] **Step 6:** Update the `maven` ToolGroup (line 172-177) to include Gradle keywords and split into separate groups:

```kotlin
ToolGroup(
    "maven",
    setOf("maven", "pom", "dependency", "dependencies", "plugin", "profile",
        "version conflict", "transitive", "effective pom", "dependency tree"),
    MAVEN_TOOL_NAMES + setOf("spring_version_info")
),
ToolGroup(
    "gradle",
    setOf("gradle", "gradlew", "build.gradle", "gradle.properties",
        "version catalog", "libs.versions.toml", "gradle task"),
    GRADLE_TOOL_NAMES
),
```

- [ ] **Step 7:** Expand `MAVEN_PROJECT_TOOLS` (line 213-216):

```kotlin
private val MAVEN_PROJECT_TOOLS = setOf(
    "maven_dependencies", "maven_properties", "maven_plugins", "maven_profiles",
    "maven_dependency_tree", "maven_effective_pom",
    "spring_version_info", "project_modules"
)
```

- [ ] **Step 8:** Expand `SPRING_PROJECT_TOOLS` (line 219-223) with Spring Boot tools:

```kotlin
private val SPRING_PROJECT_TOOLS = setOf(
    "spring_context", "spring_endpoints", "spring_bean_graph", "spring_config",
    "spring_profiles", "spring_repositories", "spring_security_config",
    "spring_scheduled_tasks", "spring_event_listeners",
    // Spring Boot (auto-detected alongside Spring)
    "spring_boot_endpoints", "spring_boot_autoconfig",
    "spring_boot_config_properties", "spring_boot_actuator"
)
```

- [ ] **Step 9:** Add Gradle project auto-detection in `detectProjectTools()` (after line 264):

```kotlin
// Detect Gradle project (check for build.gradle or build.gradle.kts in project root)
try {
    val basePath = project.basePath
    if (basePath != null) {
        val hasGradle = java.io.File(basePath, "build.gradle.kts").isFile ||
            java.io.File(basePath, "build.gradle").isFile
        if (hasGradle) {
            tools.addAll(GRADLE_PROJECT_TOOLS)
        }
    }
} catch (_: Exception) { /* Filesystem not available */ }
```

And add the constant:

```kotlin
private val GRADLE_PROJECT_TOOLS = setOf(
    "gradle_dependencies", "gradle_tasks", "gradle_properties", "project_modules"
)
```

- [ ] **Step 10:** Update the class doc comment to say "97 tools" instead of "86 tools".

- [ ] **Step 11:** In `PromptAssembler.kt`, update `CORE_IDENTITY` (line 221-236) to mention new capabilities:

```kotlin
val CORE_IDENTITY = """
    You are an AI coding assistant integrated into IntelliJ IDEA via the Workflow Orchestrator plugin.
    You can analyze code structure, edit files, run commands, check diagnostics, interact with
    enterprise tools (Jira, Bamboo, SonarQube, Bitbucket), analyze Spring Boot configuration
    (endpoints, auto-configuration, actuator, @ConfigurationProperties), inspect Maven/Gradle
    build systems (dependency trees, plugin configs, properties), activate workflow skills,
    and delegate tasks to specialized subagents.

    <capabilities>
    - Analyze: Read files, search code, find references, explore type hierarchies, view file structure, get annotations, get method bodies
    - Code: Edit files precisely, run shell commands, check for compilation errors, format and optimize imports
    - Review: Read diffs, check diagnostics, run inspections, find issues
    - Spring Boot: Discover endpoints with full URLs and params, analyze auto-configuration conditions, inspect @ConfigurationProperties, check actuator setup
    - Build Systems: Maven dependency trees, effective POM plugin configs, Gradle dependencies/tasks/properties
    - Enterprise: Read Jira tickets, transition statuses, add comments, check builds, query quality issues, create PRs
    - Skills: Activate workflow skills for specialized tasks (debugging, code review, deployment)
    - Delegation: Spawn subagents for complex sub-tasks with isolated context
    - Reasoning: Use the think tool for complex reasoning — pause and think through your approach before acting.
    </capabilities>
""".trimIndent()
```

- [ ] **Step 12:** In `PromptAssembler.kt`, add Spring Boot context rules in `buildIntegrationRules()` (after line 208):

```kotlin
val hasSpringBoot = includeAll || activeToolNames!!.any { it.startsWith("spring_boot_") }

if (hasSpringBoot) parts.add(SPRING_BOOT_CONTEXT_RULES)
```

And add the constant:

```kotlin
val SPRING_BOOT_CONTEXT_RULES = """
    <spring_boot_rules>
    Spring Boot tools available — use them proactively:
    - When user mentions endpoints/APIs/routes: use spring_boot_endpoints (not spring_endpoints) for full URL resolution with context-path and parameter details
    - When debugging "bean not created" / "auto-configuration not applied": use spring_boot_autoconfig to check @Conditional* conditions
    - When user asks about configurable properties: use spring_boot_config_properties to show @ConfigurationProperties classes with types and defaults
    - When user asks about monitoring/health/metrics: use spring_boot_actuator to check actuator setup and endpoint exposure
    - For dependency conflicts (NoSuchMethodError, ClassNotFoundException): use maven_dependency_tree to trace transitive dependencies
    - For build configuration questions: use maven_effective_pom to show plugin configurations
    </spring_boot_rules>
""".trimIndent()
```

- [ ] **Step 13:** Compile: `./gradlew :agent:compileKotlin`

- [ ] **Step 14:** Commit: `feat: update DynamicToolSelector + PromptAssembler for 11 new tools (Spring Boot, Gradle, Maven, PSI)`

---

### Task 21: Register all 11 new tools in AgentService + ToolCategoryRegistry

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt:292` (tool registration block)
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ToolCategoryRegistry.kt:4,28-35,59-65`

- [ ] **Step 1:** In `AgentService.kt`, add imports at the top for all 11 new tool classes:

```kotlin
import com.workflow.orchestrator.agent.tools.psi.GetAnnotationsTool
import com.workflow.orchestrator.agent.tools.psi.GetMethodBodyTool
import com.workflow.orchestrator.agent.tools.psi.SpringBootEndpointsTool
import com.workflow.orchestrator.agent.tools.psi.SpringBootAutoConfigTool
import com.workflow.orchestrator.agent.tools.psi.SpringBootConfigPropertiesTool
import com.workflow.orchestrator.agent.tools.psi.SpringBootActuatorTool
import com.workflow.orchestrator.agent.tools.framework.GradleDependenciesTool
import com.workflow.orchestrator.agent.tools.framework.GradleTasksTool
import com.workflow.orchestrator.agent.tools.framework.GradlePropertiesTool
import com.workflow.orchestrator.agent.tools.framework.MavenDependencyTreeTool
import com.workflow.orchestrator.agent.tools.framework.MavenEffectivePomTool
```

- [ ] **Step 2:** In `AgentService.kt`, add registration calls after line 292 (after `register(SpringEventListenersTool())`):

```kotlin
            // New PSI tools
            register(GetAnnotationsTool())
            register(GetMethodBodyTool())

            // Spring Boot Intelligence
            register(SpringBootEndpointsTool())
            register(SpringBootAutoConfigTool())
            register(SpringBootConfigPropertiesTool())
            register(SpringBootActuatorTool())

            // Gradle Intelligence
            register(GradleDependenciesTool())
            register(GradleTasksTool())
            register(GradlePropertiesTool())

            // Maven Intelligence (enhanced)
            register(MavenDependencyTreeTool())
            register(MavenEffectivePomTool())
```

- [ ] **Step 3:** In `ToolCategoryRegistry.kt`, update the `core` category tools list — add `"get_annotations"` and `"get_method_body"`.

- [ ] **Step 4:** In `ToolCategoryRegistry.kt`, update the `framework` category tools list — add all 9 new framework tools:

```kotlin
"spring_boot_endpoints", "spring_boot_autoconfig", "spring_boot_config_properties", "spring_boot_actuator",
"gradle_dependencies", "gradle_tasks", "gradle_properties",
"maven_dependency_tree", "maven_effective_pom"
```

- [ ] **Step 5:** Update the doc comment from "86 agent tools" to "97 agent tools".

- [ ] **Step 6:** Compile: `./gradlew :agent:compileKotlin`

- [ ] **Step 7:** Commit: `feat: register 11 new tools in AgentService + ToolCategoryRegistry (97 total)`

---

### Task 22: Final verification

- [ ] **Step 1:** Full Kotlin compile: `./gradlew :agent:compileKotlin :core:compileKotlin`
- [ ] **Step 2:** Run agent tests: `./gradlew :agent:test`
- [ ] **Step 3:** Build plugin ZIP: `./gradlew clean buildPlugin`
- [ ] **Step 4:** Update `agent/CLAUDE.md`:
  - Update "86 tools" to "97 tools" in header and all references
  - Add `get_annotations`, `get_method_body` to Core tools table row
  - Add all new Spring Boot tools to the Spring & Framework table row
  - Add Gradle tools to the Spring & Framework table row
  - Add Maven enhanced tools to the Spring & Framework table row
- [ ] **Step 5:** Update root `CLAUDE.md` if it references tool count.
- [ ] **Step 6:** Commit: `docs: update CLAUDE.md files with 11 new tools (97 total)`
