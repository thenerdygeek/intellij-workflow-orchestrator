package com.workflow.orchestrator.agent.ide

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.SearchScope

/**
 * Language-specific code intelligence operations. Each implementation handles
 * one language family (Java/Kotlin, Python, etc.). Tools delegate to the
 * appropriate provider via [LanguageProviderRegistry].
 */
interface LanguageIntelligenceProvider {

    /** Languages this provider handles */
    val supportedLanguageIds: Set<String>

    // --- Symbol Resolution ---

    /** Find a named symbol (class, function, variable) by name in project scope */
    fun findSymbol(project: Project, name: String): PsiElement?

    /** Find a symbol in a specific file at the given offset */
    fun findSymbolAt(file: PsiFile, offset: Int): PsiElement?

    /** Get the definition location of a resolved element */
    fun getDefinitionInfo(element: PsiElement): DefinitionInfo?

    // --- File Structure ---

    /** Get the structure of a file (classes, functions, imports) */
    fun getFileStructure(file: PsiFile, detail: DetailLevel): FileStructureResult

    // --- Type System ---

    /** Get the type hierarchy for a class/type element */
    fun getTypeHierarchy(element: PsiElement): TypeHierarchyResult?

    /** Find implementations/subclasses of a class or overrides of a method */
    fun findImplementations(element: PsiElement, scope: SearchScope): List<ImplementationInfo>

    /** Infer the type of an expression, variable, or parameter */
    fun inferType(element: PsiElement): TypeInferenceResult?

    /** Analyze dataflow for an expression (nullability, range, constant value) */
    fun analyzeDataflow(element: PsiElement): DataflowResult?

    // --- Call Graph ---

    /** Find callers of a function/method */
    fun findCallers(element: PsiElement, depth: Int, scope: SearchScope): List<CallerInfo>

    /** Find callees within a function/method body */
    fun findCallees(element: PsiElement): List<CalleeInfo>

    // --- Metadata ---

    /** Get annotations (Java) or decorators (Python) on an element */
    fun getMetadata(element: PsiElement, includeInherited: Boolean): List<MetadataInfo>

    /** Get the source text of a function/method body */
    fun getBody(element: PsiElement, contextLines: Int): BodyResult?

    // --- Access Analysis ---

    /** Classify usages of a variable as read, write, or read-write */
    fun classifyAccesses(element: PsiElement, scope: SearchScope): AccessClassification

    // --- Test Discovery ---

    /** Find tests associated with a source element, or source for a test */
    fun findRelatedTests(element: PsiElement): TestRelationResult

    // --- Diagnostics ---

    /** Find syntax errors and unresolved references in a file */
    fun getDiagnostics(file: PsiFile, lineRange: IntRange?): List<DiagnosticInfo>

    // --- Structural Search ---

    /** Search for structural patterns (e.g., $Type$.$method$($args$)) */
    fun structuralSearch(project: Project, pattern: String, scope: SearchScope): List<StructuralMatchInfo>?
}

// --- Result Types ---

enum class DetailLevel { MINIMAL, SIGNATURES, FULL }

data class DefinitionInfo(
    val filePath: String,
    val line: Int,
    val signature: String,
    val documentation: String? = null,
    val skeleton: String? = null,
)

data class FileStructureResult(
    val packageOrModule: String?,
    val imports: List<String>,
    val declarations: List<DeclarationInfo>,
    val formatted: String,
)

data class DeclarationInfo(
    val name: String,
    val kind: String,
    val signature: String,
    val line: Int,
    val children: List<DeclarationInfo> = emptyList(),
)

data class TypeHierarchyResult(
    val element: String,
    val supertypes: List<HierarchyEntry>,
    val subtypes: List<HierarchyEntry>,
)

data class HierarchyEntry(
    val name: String,
    val qualifiedName: String,
    val filePath: String?,
    val line: Int?,
)

data class ImplementationInfo(
    val name: String,
    val signature: String,
    val filePath: String,
    val line: Int,
)

data class TypeInferenceResult(
    val typeName: String,
    val qualifiedName: String?,
    val nullability: Nullability,
)

enum class Nullability { NULLABLE, NOT_NULL, PLATFORM, UNKNOWN }

data class DataflowResult(
    val nullability: Nullability,
    val valueRange: String?,
    val constantValue: String?,
)

data class CallerInfo(
    val name: String,
    val filePath: String,
    val line: Int,
    val depth: Int,
)

data class CalleeInfo(
    val name: String,
    val filePath: String?,
    val line: Int?,
)

data class MetadataInfo(
    val name: String,
    val qualifiedName: String,
    val parameters: Map<String, String>,
    val isInherited: Boolean = false,
)

data class BodyResult(
    val source: String,
    val startLine: Int,
    val endLine: Int,
)

data class AccessClassification(
    val reads: List<AccessInfo>,
    val writes: List<AccessInfo>,
    val readWrites: List<AccessInfo>,
)

data class AccessInfo(
    val filePath: String,
    val line: Int,
    val context: String,
)

data class TestRelationResult(
    val isTestElement: Boolean,
    val relatedElements: List<TestRelatedInfo>,
)

data class TestRelatedInfo(
    val name: String,
    val filePath: String,
    val line: Int,
    val kind: String,
)

data class DiagnosticInfo(
    val message: String,
    val severity: String,
    val line: Int,
    val filePath: String,
    val isPreExisting: Boolean = false,
)

data class StructuralMatchInfo(
    val matchedText: String,
    val filePath: String,
    val line: Int,
)
