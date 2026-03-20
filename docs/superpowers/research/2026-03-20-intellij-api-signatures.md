# IntelliJ Platform API Signatures for Agent Tools

**Date:** 2026-03-20
**Purpose:** Exact method signatures and threading requirements for 14 AI agent tools

---

## Tool Pattern Reference

All tools implement `AgentTool` interface:
```kotlin
suspend fun execute(params: JsonObject, project: Project): ToolResult
```

PSI access pattern:
```kotlin
ReadAction.compute<T, Exception> { /* PSI reads */ }
ReadAction.nonBlocking<T> { /* PSI reads */ }.inSmartMode(project).executeSynchronously()
WriteCommandAction.runWriteCommandAction(project) { /* PSI writes */ }
```

---

## MUST HAVE (5)

### 1. WolfTheProblemSolver

**Package:** `com.intellij.problems`
**Source:** `platform/analysis-api/src/com/intellij/problems/WolfTheProblemSolver.java`

```kotlin
import com.intellij.problems.WolfTheProblemSolver
```

**Get instance:**
```kotlin
val wolf = WolfTheProblemSolver.getInstance(project)
```

**Key method signatures:**
```java
// Check if a file has problems (compilation errors reported by the highlighter)
public abstract boolean isProblemFile(@NotNull VirtualFile virtualFile);

// Check if any problem files exist beneath a directory
public abstract boolean hasProblemFilesBeneath(@NotNull Condition<? super VirtualFile> condition);

// Report problems for a file (called by the highlighting infrastructure)
public abstract void reportProblems(@NotNull VirtualFile file, @NotNull Collection<? extends Problem> problems);

// Notify that problems were found
public abstract void weHaveGotProblems(@NotNull VirtualFile virtualFile, @NotNull List<? extends Problem> problems);

// Notify that problems were resolved
public abstract void weHaveGotNonIgnorableProblems(@NotNull VirtualFile virtualFile, @NotNull List<? extends Problem> problems);

// Clear problems for a file
public abstract void clearProblems(@NotNull VirtualFile virtualFile);

// Convert to Problem object
public abstract Problem convertToProblem(@Nullable VirtualFile virtualFile, int line, int column, @NotNull String[] message);
```

**Threading:** Read from any thread. `isProblemFile()` is thread-safe.

**Requirements:**
- File does NOT need to be open in editor
- Works for Java AND Kotlin files (any file that goes through the highlighting pass)
- Problems are populated by the IDE's highlighting infrastructure (DaemonCodeAnalyzer)
- If a file has never been opened/highlighted, `isProblemFile` may return false even if the file has errors

**Gotchas:**
- WolfTheProblemSolver tracks problems reported by the *highlighting* pass, not by compilation. A file must have been highlighted (opened in editor at least once) for problems to be tracked.
- For agent use, prefer the PSI-based `PsiErrorElement` approach (as in `DiagnosticsTool`) for immediate syntax error detection, and WolfTheProblemSolver for "has this file been flagged as problematic by the IDE."

**Recommended tool approach:** Combine both:
```kotlin
ReadAction.compute<ToolResult, Exception> {
    // 1. Quick check via WolfTheProblemSolver
    val wolf = WolfTheProblemSolver.getInstance(project)
    val hasProblem = wolf.isProblemFile(virtualFile)

    // 2. PSI-based syntax error check (immediate, no highlighting needed)
    val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
    val syntaxErrors = PsiTreeUtil.collectElementsOfType(psiFile, PsiErrorElement::class.java)
    // ...
}
```

---

### 2. CodeStyleManager

**Package:** `com.intellij.psi.codeStyle`
**Source:** `platform/core-api/src/com/intellij/psi/codeStyle/CodeStyleManager.java`

```kotlin
import com.intellij.psi.codeStyle.CodeStyleManager
```

**Get instance:**
```kotlin
val codeStyleManager = CodeStyleManager.getInstance(project)
```

**Key method signatures:**
```java
// Reformat entire PsiElement (file, class, method, etc.)
@NotNull
public abstract PsiElement reformat(@NotNull PsiElement element) throws IncorrectOperationException;

// Reformat with option to adjust whitespace only (no structural changes)
@NotNull
public abstract PsiElement reformat(@NotNull PsiElement element, boolean canChangeWhiteSpacesOnly) throws IncorrectOperationException;

// Reformat a text range within a file
public abstract void reformatText(@NotNull PsiFile file, int startOffset, int endOffset) throws IncorrectOperationException;

// Reformat multiple ranges
public abstract void reformatText(@NotNull PsiFile file, @NotNull Collection<? extends TextRange> ranges) throws IncorrectOperationException;

// Reformat with option for changed-text-only
public void reformatText(@NotNull PsiFile file, @NotNull Collection<? extends TextRange> ranges, boolean processChangedTextOnly) throws IncorrectOperationException;

// Reformat and optimize imports in one step
public abstract PsiElement reformatRange(@NotNull PsiElement element, int startOffset, int endOffset) throws IncorrectOperationException;

// Adjust line indent at offset
public abstract int adjustLineIndent(@NotNull PsiFile file, int offset) throws IncorrectOperationException;

// Adjust line indent in document
public abstract int adjustLineIndent(@NotNull Document document, int offset);
```

**Threading:** MUST run inside `WriteCommandAction`:
```kotlin
WriteCommandAction.runWriteCommandAction(project, "Reformat Code", null, {
    val codeStyleManager = CodeStyleManager.getInstance(project)
    codeStyleManager.reformat(psiFile)
}, psiFile)
```

**Requirements:**
- File does NOT need to be open in editor (works on any PsiFile)
- Respects project's code style settings (.editorconfig, IDE settings)
- Works for Java, Kotlin, XML, and any language with a formatter registered

**Gotchas:**
- `reformat(PsiElement)` modifies the PSI tree, so it MUST be in a write action
- `reformatText()` with ranges is preferred for partial formatting
- The returned PsiElement from `reformat()` may be a different instance than the input (PSI tree mutation)

---

### 3. ImportOptimizer

**Package:** `com.intellij.lang`
**Source:** `platform/code-style-api/src/com/intellij/lang/ImportOptimizer.java`

```kotlin
import com.intellij.lang.ImportOptimizer
import com.intellij.lang.LanguageImportStatements
```

**Get optimizers for a file:**
```kotlin
val optimizers: Set<ImportOptimizer> = LanguageImportStatements.INSTANCE.forFile(psiFile)
```

**Key method signatures:**
```java
// Interface: ImportOptimizer
public interface ImportOptimizer {
    // Check if this optimizer supports the given file
    boolean supports(@NotNull PsiFile file);

    // Process the file and return a Runnable that performs the actual optimization
    // processFile() runs in ReadAction context
    // The returned Runnable runs in WriteAction context
    @NotNull
    Runnable processFile(@NotNull PsiFile file);
}
```

**Threading:** Two-phase execution:
```kotlin
// Phase 1: Compute (ReadAction) - analyzes imports, determines what to remove/add
// Phase 2: Execute (WriteAction) - applies the changes

WriteCommandAction.runWriteCommandAction(project, "Optimize Imports", null, {
    val optimizers = LanguageImportStatements.INSTANCE.forFile(psiFile)
    for (optimizer in optimizers) {
        if (optimizer.supports(psiFile)) {
            val runnable = optimizer.processFile(psiFile)  // computes changes
            runnable.run()  // applies changes (already inside WriteAction)
        }
    }
}, psiFile)
```

**Requirements:**
- File does NOT need to be open in editor
- Works for Java (`JavaImportOptimizer`), Kotlin (`KotlinImportOptimizer`), and other languages
- The `processFile()` method is guaranteed to run with ReadAction privileges
- The returned `Runnable` is guaranteed to run with WriteAction privileges

**Gotchas:**
- Must call `supports(file)` before `processFile()` -- not all optimizers handle all files
- The Runnable returned by `processFile()` should be run inside WriteCommandAction
- For Java files specifically, you can also use `com.intellij.codeInsight.actions.OptimizeImportsProcessor`:
```kotlin
import com.intellij.codeInsight.actions.OptimizeImportsProcessor
OptimizeImportsProcessor(project, psiFile).run()  // Higher-level API, handles threading
```

---

### 4. InspectionManager + Running Inspections Programmatically

**Package:** `com.intellij.codeInspection`
**Source:** `platform/analysis-api/src/com/intellij/codeInspection/InspectionManager.java`

```kotlin
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ex.InspectionToolWrapper
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
```

**Get instance:**
```kotlin
val inspectionManager = InspectionManager.getInstance(project)
```

**Key method signatures:**

```java
// InspectionManager - factory for ProblemDescriptors
public abstract class InspectionManager {
    public static InspectionManager getInstance(@NotNull Project project);

    @NotNull
    public abstract ProblemDescriptor createProblemDescriptor(
        @NotNull PsiElement psiElement,
        @NotNull String descriptionTemplate,
        @Nullable LocalQuickFix fix,
        @NotNull ProblemHighlightType highlightType,
        boolean onTheFly
    );

    // Get the global inspection context
    @NotNull
    public abstract GlobalInspectionContext createNewGlobalContext();
}

// LocalInspectionTool - the primary inspection interface
public abstract class LocalInspectionTool {
    // Override this to visit PSI elements
    @NotNull
    public PsiElementVisitor buildVisitor(
        @NotNull ProblemsHolder holder,
        boolean isOnTheFly
    );

    // Alternative: check entire file at once
    public ProblemDescriptor @Nullable [] checkFile(
        @NotNull PsiFile file,
        @NotNull InspectionManager manager,
        boolean isOnTheFly
    );
}

// ProblemsHolder - collects problems during inspection
public class ProblemsHolder {
    public ProblemsHolder(
        @NotNull InspectionManager manager,
        @NotNull PsiFile file,
        boolean onTheFly
    );

    @NotNull
    public List<ProblemDescriptor> getResults();
}
```

**Running inspections on a single file programmatically:**
```kotlin
// Approach 1: Run a specific LocalInspectionTool on a file
fun runInspection(tool: LocalInspectionTool, psiFile: PsiFile, project: Project): List<ProblemDescriptor> {
    return ReadAction.compute<List<ProblemDescriptor>, Exception> {
        val manager = InspectionManager.getInstance(project)
        val holder = ProblemsHolder(manager, psiFile, /* isOnTheFly = */ false)

        // Let the tool visit the file
        val visitor = tool.buildVisitor(holder, false)
        psiFile.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                element.accept(visitor)
                super.visitElement(element)
            }
        })

        holder.results
    }
}

// Approach 2: Run all enabled inspections using the profile
fun runAllInspections(psiFile: PsiFile, project: Project): List<ProblemDescriptor> {
    return ReadAction.compute<List<ProblemDescriptor>, Exception> {
        val profile = InspectionProjectProfileManager.getInstance(project).currentProfile
        val manager = InspectionManager.getInstance(project)
        val allProblems = mutableListOf<ProblemDescriptor>()

        for (toolWrapper in profile.getInspectionTools(psiFile)) {
            val tool = toolWrapper.tool
            if (tool is LocalInspectionTool && toolWrapper.isEnabled) {
                val holder = ProblemsHolder(manager, psiFile, false)
                val visitor = tool.buildVisitor(holder, false)
                psiFile.accept(object : PsiRecursiveElementWalkingVisitor() {
                    override fun visitElement(element: PsiElement) {
                        element.accept(visitor)
                        super.visitElement(element)
                    }
                })
                allProblems.addAll(holder.results)
            }
        }
        allProblems
    }
}
```

**Getting inspection profile and enabled tools:**
```kotlin
import com.intellij.profile.codeInspection.InspectionProjectProfileManager

val profileManager = InspectionProjectProfileManager.getInstance(project)
val profile = profileManager.currentProfile  // InspectionProfile

// Get all tool wrappers for a file
val tools: List<InspectionToolWrapper<*, *>> = profile.getInspectionTools(psiFile)

// Check if a specific inspection is enabled
val isEnabled = profile.isToolEnabled(HighlightDisplayKey.find("UnusedImport"), psiFile)
```

**Threading:** `ReadAction` for running inspections. No write action needed (inspections are read-only).

**Requirements:**
- File does NOT need to be open in editor
- Requires smart mode (indices must be ready)
- `isOnTheFly = false` for batch/agent mode (disables incremental highlighting optimizations)

**Gotchas:**
- `InspectionEngine` is internal API (`com.intellij.codeInspection.ex.InspectionEngine`) -- prefer using `LocalInspectionTool.buildVisitor()` + `ProblemsHolder` directly
- The `PsiRecursiveElementWalkingVisitor` approach is the standard pattern for running inspections outside the highlighting pass
- `ProblemDescriptor.getLineNumber()` returns 0-based line number
- Some inspections may require specific file types or language support

---

### 5. RenameProcessor (Programmatic Rename Refactoring)

**Package:** `com.intellij.refactoring.rename`
**Source:** `platform/lang-impl/src/com/intellij/refactoring/rename/RenameProcessor.java`

```kotlin
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.RefactoringFactory
import com.intellij.refactoring.RenameRefactoring
```

**Approach A: RenameProcessor (lower-level, more control)**

```java
// Constructor signatures:
public RenameProcessor(
    @NotNull Project project,
    @NotNull PsiElement element,
    @NotNull @NonNls String newName,
    boolean isSearchInComments,
    boolean isSearchTextOccurrences
);

public RenameProcessor(
    @NotNull Project project,
    @NotNull PsiElement element,
    @NotNull @NonNls String newName,
    @NotNull SearchScope refactoringScope,
    boolean isSearchInComments,
    boolean isSearchTextOccurrences
);
```

**Usage (headless, no dialog):**
```kotlin
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.openapi.command.WriteCommandAction

// RenameProcessor.run() executes the rename headlessly
WriteCommandAction.runWriteCommandAction(project) {
    val processor = RenameProcessor(
        project,
        psiElement,       // PsiClass, PsiMethod, PsiField, PsiVariable, etc.
        "newName",        // The new name
        false,            // searchInComments
        false             // searchTextOccurrences
    )
    processor.run()  // Executes the rename (finds usages + replaces)
}
```

**Approach B: RefactoringFactory (higher-level)**

```kotlin
import com.intellij.refactoring.RefactoringFactory

val factory = RefactoringFactory.getInstance(project)
```

```java
// RefactoringFactory method signatures:
public abstract class RefactoringFactory {
    public static RefactoringFactory getInstance(@NotNull Project project);

    public abstract RenameRefactoring createRename(
        @NotNull PsiElement element,
        @Nullable String newName
    );

    public abstract RenameRefactoring createRename(
        @NotNull PsiElement element,
        @Nullable String newName,
        boolean searchInComments,
        boolean searchInNonJavaFiles
    );

    public abstract RenameRefactoring createRename(
        @NotNull PsiElement element,
        @Nullable String newName,
        @NotNull SearchScope scope,
        boolean searchInComments,
        boolean searchInNonJavaFiles
    );
}

// RenameRefactoring interface:
public interface RenameRefactoring extends Refactoring {
    void addElement(@NotNull PsiElement element, @NotNull String newName);
    Set<PsiElement> getElements();
    // Inherited from Refactoring:
    void run();                    // Execute the refactoring
    UsageInfo[] findUsages();      // Find usages without executing
    boolean preprocessUsages(Ref<UsageInfo[]> usages);
    void doRefactoring(UsageInfo[] usages);
}
```

**Usage with RefactoringFactory:**
```kotlin
WriteCommandAction.runWriteCommandAction(project) {
    val factory = RefactoringFactory.getInstance(project)
    val rename = factory.createRename(psiElement, "newName", false, false)
    rename.run()
}
```

**Threading:** MUST run in WriteCommandAction on EDT. The `run()` method performs both usage search and replacement.

**Requirements:**
- Element must be a `PsiNamedElement` (class, method, field, variable, parameter)
- Works headlessly -- no dialog shown when calling `run()` directly
- Handles renaming of usages, overriding methods, getter/setter pairs automatically

**Gotchas:**
- `RenameProcessor.run()` may show a conflicts dialog if there are naming conflicts. To suppress: `processor.setPreviewUsages(false)` before `run()`.
- For completely headless operation (no dialogs at all), you may need to handle `ConflictsDialog` suppression or use `processor.doRun()` in tests.
- Kotlin rename requires the Kotlin plugin's `RenamePsiElementProcessor` to be registered.
- The rename propagates through the entire project (all usages updated).

---

## SHOULD HAVE (9)

### 6. OverridingMethodsSearch + ClassInheritorsSearch

**Package:** `com.intellij.psi.search.searches`
**Source:** `java/java-indexing-api/src/com/intellij/psi/search/searches/`

```kotlin
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.Query
```

**Key method signatures:**

```java
// OverridingMethodsSearch - find methods that override a given method
public class OverridingMethodsSearch extends ExtensibleQueryFactory<PsiMethod, OverridingMethodsSearch.SearchParameters> {
    public static Query<PsiMethod> search(@NotNull PsiMethod method);
    public static Query<PsiMethod> search(@NotNull PsiMethod method, boolean checkDeep);
    public static Query<PsiMethod> search(
        @NotNull PsiMethod method,
        @NotNull SearchScope scope,
        boolean checkDeep
    );
}

// ClassInheritorsSearch - find classes that extend/implement a given class/interface
public class ClassInheritorsSearch extends ExtensibleQueryFactory<PsiClass, ClassInheritorsSearch.SearchParameters> {
    public static Query<PsiClass> search(@NotNull PsiClass aClass);
    public static Query<PsiClass> search(@NotNull PsiClass aClass, boolean checkDeep);
    public static Query<PsiClass> search(
        @NotNull PsiClass aClass,
        @NotNull SearchScope scope,
        boolean checkDeep
    );
    public static Query<PsiClass> search(
        @NotNull PsiClass aClass,
        @NotNull SearchScope scope,
        boolean checkDeep,
        boolean checkInheritance
    );
}

// DefinitionsScopedSearch - find implementations of abstract/interface methods
public class DefinitionsScopedSearch extends ExtensibleQueryFactory<PsiElement, DefinitionsScopedSearch.SearchParameters> {
    public static Query<PsiElement> search(@NotNull PsiElement element);
    public static Query<PsiElement> search(
        @NotNull PsiElement element,
        @NotNull SearchScope scope
    );
}

// Query<T> - result interface
public interface Query<T> {
    @NotNull Collection<T> findAll();
    @Nullable T findFirst();
    boolean forEach(@NotNull Processor<? super T> consumer);
    T @NotNull [] toArray(T @NotNull [] a);
}
```

**Usage in agent tool:**
```kotlin
ReadAction.compute<List<String>, Exception> {
    val scope = GlobalSearchScope.projectScope(project)

    // Find all overriding methods
    val overrides: Collection<PsiMethod> = OverridingMethodsSearch.search(psiMethod, scope, true).findAll()

    // Find all implementing classes
    val inheritors: Collection<PsiClass> = ClassInheritorsSearch.search(psiClass, scope, true).findAll()

    // Find implementations of an interface method
    val implementations: Collection<PsiElement> = DefinitionsScopedSearch.search(psiMethod).findAll()

    // Format results...
}
```

**Threading:** ReadAction required. Can be slow on large codebases -- use `Query.forEach` for lazy evaluation.

**Requirements:**
- Requires smart mode (indices must be built)
- Works for Java and Kotlin (Kotlin overrides are found via PSI wrappers)
- `checkDeep = true` finds transitive overrides/inheritors

---

### 7. CompilerManager

**Package:** `com.intellij.openapi.compiler`
**Source:** `java/compiler/openapi/src/com/intellij/openapi/compiler/CompilerManager.java`

```kotlin
import com.intellij.openapi.compiler.CompilerManager
import com.intellij.openapi.compiler.CompileScope
import com.intellij.openapi.compiler.CompileStatusNotification
import com.intellij.openapi.compiler.CompilerMessageCategory
```

**Get instance:**
```kotlin
val compilerManager = CompilerManager.getInstance(project)
```

**Key method signatures:**
```java
public abstract class CompilerManager {
    public static CompilerManager getInstance(@NotNull Project project);

    // Make (incremental build) the whole project
    public abstract void make(@Nullable CompileStatusNotification callback);

    // Make specific scope
    public abstract void make(
        @NotNull CompileScope scope,
        @Nullable CompileStatusNotification callback
    );

    // Make specific modules
    public abstract void make(
        @NotNull Module @NotNull [] modules,
        @Nullable CompileStatusNotification callback
    );

    // Compile (force full build, not incremental)
    public abstract void compile(
        @NotNull CompileScope scope,
        @Nullable CompileStatusNotification callback
    );

    // Compile specific modules
    public abstract void compile(
        @NotNull Module @NotNull [] module,
        @Nullable CompileStatusNotification callback
    );

    // Make with modal progress (blocks UI with progress dialog)
    public void makeWithModalProgress(
        @NotNull CompileScope scope,
        @Nullable CompileStatusNotification callback
    );

    // Create scopes
    @NotNull
    public abstract CompileScope createModuleCompileScope(
        @NotNull Module module,
        boolean includeDependentModules
    );

    @NotNull
    public abstract CompileScope createModulesCompileScope(
        @NotNull Module @NotNull [] modules,
        boolean includeDependentModules
    );

    @NotNull
    public abstract CompileScope createModulesCompileScope(
        @NotNull Module @NotNull [] modules,
        boolean includeDependentModules,
        boolean includeRuntimeDependencies
    );

    @NotNull
    public abstract CompileScope createProjectCompileScope(@NotNull Project project);

    // Check if compilation is needed
    public abstract boolean isUpToDate(@NotNull CompileScope scope);
}

// Callback interface
public interface CompileStatusNotification {
    void finished(
        boolean aborted,
        int errors,
        int warnings,
        @NotNull CompileContext compileContext
    );
}
```

**Usage in agent tool (async with callback):**
```kotlin
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

suspend fun compileModule(module: Module, project: Project): CompileResult {
    return suspendCancellableCoroutine { continuation ->
        val scope = CompilerManager.getInstance(project)
            .createModuleCompileScope(module, /* includeDependentModules = */ true)

        CompilerManager.getInstance(project).make(scope) { aborted, errors, warnings, context ->
            continuation.resume(CompileResult(aborted, errors, warnings))
        }
    }
}
```

**Threading:** `make()` and `compile()` methods must be called from EDT (they schedule background compilation). The callback is invoked on EDT after compilation finishes.

**Getting errors from CompileContext:**
```kotlin
// Inside the callback:
val errorMessages = compileContext.getMessages(CompilerMessageCategory.ERROR)
for (msg in errorMessages) {
    println("${msg.virtualFile?.path}:${msg.line}: ${msg.message}")
}
```

**Gotchas:**
- The `make()` call is ASYNCHRONOUS -- it returns immediately and calls the callback when done
- Must call from EDT, but compilation runs in background
- For agent tools, wrap in `suspendCancellableCoroutine` to bridge async callback to coroutine

---

### 8. RunManager + ExecutionManager

**Package:** `com.intellij.execution`
**Source:** `platform/execution/src/com/intellij/execution/RunManager.kt`

```kotlin
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.ExecutionManager
import com.intellij.execution.junit.JUnitConfiguration
import com.intellij.execution.junit.JUnitConfigurationType
```

**Get instance:**
```kotlin
val runManager = RunManager.getInstance(project)
```

**Key method signatures:**
```kotlin
// RunManager (Kotlin class in modern IntelliJ)
abstract class RunManager {
    companion object {
        @JvmStatic
        fun getInstance(project: Project): RunManager
    }

    // Create a new run configuration
    abstract fun createConfiguration(
        name: String,
        factory: ConfigurationFactory
    ): RunnerAndConfigurationSettings

    // Add configuration to the list
    abstract fun addConfiguration(settings: RunnerAndConfigurationSettings)

    // Get existing configurations
    abstract fun getConfigurationSettingsList(type: ConfigurationType): List<RunnerAndConfigurationSettings>

    // Get all configurations
    abstract fun getAllSettings(): List<RunnerAndConfigurationSettings>

    // Set selected configuration
    abstract var selectedConfiguration: RunnerAndConfigurationSettings?
}
```

**Creating and running a JUnit configuration programmatically:**
```kotlin
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.junit.JUnitConfiguration
import com.intellij.execution.junit.JUnitConfigurationType

// Create JUnit config
val junitType = JUnitConfigurationType.getInstance()
val factory = junitType.configurationFactories[0]
val settings = runManager.createConfiguration("Test MyClass", factory)
val config = settings.configuration as JUnitConfiguration

// Configure it
val data = config.persistentData
data.TEST_OBJECT = JUnitConfiguration.TEST_CLASS  // or TEST_METHOD, TEST_PACKAGE
data.MAIN_CLASS_NAME = "com.example.MyClassTest"
data.METHOD_NAME = "testMethod"  // only for TEST_METHOD
config.setModule(module)

// Execute
ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
```

**Executing and getting test results:**
```java
// ProgramRunnerUtil
public class ProgramRunnerUtil {
    public static void executeConfiguration(
        @NotNull RunnerAndConfigurationSettings configuration,
        @NotNull Executor executor
    );
}

// To capture test results, listen to test events:
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener

// Register listener on project message bus:
project.messageBus.connect().subscribe(
    SMTRunnerEventsListener.TEST_STATUS,
    object : SMTRunnerEventsAdapter() {
        override fun onTestFinished(test: SMTestProxy) {
            // test.isPassed, test.isDefect, test.getErrorMessage()
        }
        override fun onSuiteFinished(suite: SMTestProxy) {
            val allTests = suite.allTests
            val passed = allTests.count { it.isPassed }
            val failed = allTests.count { it.isDefect }
        }
    }
)
```

**Threading:** Configuration creation can happen on any thread. `ProgramRunnerUtil.executeConfiguration()` MUST be called on EDT.

**Gotchas:**
- JUnit plugin must be present (`com.intellij.junit`)
- Test result events are asynchronous -- subscribe before executing
- `JUnitConfiguration.TEST_OBJECT` constants: `TEST_CLASS`, `TEST_METHOD`, `TEST_PACKAGE`, `TEST_DIRECTORY`, `TEST_PATTERN`

---

### 9. MavenProjectsManager

**Package:** `org.jetbrains.idea.maven.project`
**Source:** `plugins/maven/src/main/java/org/jetbrains/idea/maven/project/MavenProjectsManager.java`

```kotlin
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.model.MavenId
```

**Get instance:**
```kotlin
val mavenManager = MavenProjectsManager.getInstance(project)
```

**Key method signatures:**
```java
public class MavenProjectsManager {
    public static MavenProjectsManager getInstance(@NotNull Project project);

    // Get all Maven projects in the workspace
    @NotNull
    public List<MavenProject> getProjects();

    // Get root-level Maven projects only
    @NotNull
    public List<MavenProject> getRootProjects();

    // Find Maven project by module
    @Nullable
    public MavenProject findProject(@NotNull Module module);

    // Find Maven project by MavenId (groupId:artifactId:version)
    @Nullable
    public MavenProject findProject(@NotNull MavenId id);

    // Find Maven project by MavenArtifact
    @Nullable
    public MavenProject findProject(@NotNull MavenArtifact artifact);

    // Find IntelliJ Module for a MavenProject
    @Nullable
    public Module findModule(@NotNull MavenProject project);

    // Check if Maven integration is active
    public boolean isMavenizedProject();

    // Check if a specific module is managed by Maven
    public boolean isMavenizedModule(@NotNull Module module);

    // Force reimport
    public void forceUpdateAllProjectsOrFindAllAvailablePomFiles();
}

// MavenProject key properties
public class MavenProject {
    @NotNull public MavenId getMavenId();          // groupId:artifactId:version
    @NotNull public String getDirectory();          // absolute path to module directory
    @NotNull public VirtualFile getFile();           // pom.xml VirtualFile
    @NotNull public String getDisplayName();         // human-readable name
    @Nullable public String getParentId();

    // Dependencies
    @NotNull public List<MavenArtifact> getDependencies();

    // Source roots
    @NotNull public List<String> getSources();
    @NotNull public List<String> getTestSources();
    @NotNull public List<String> getResources();

    // Properties
    @NotNull public Properties getProperties();

    // Plugins
    @NotNull public List<MavenPlugin> getDeclaredPlugins();
}

// MavenId
public class MavenId {
    @Nullable public String getGroupId();
    @Nullable public String getArtifactId();
    @Nullable public String getVersion();
    @NotNull  public String getKey();               // "groupId:artifactId:version"
}
```

**Threading:** Read from any thread. `getProjects()` is thread-safe.

**Requirements:**
- Requires Maven plugin: `<depends optional="true" config-file="plugin-withMaven.xml">org.jetbrains.idea.maven</depends>`
- `isMavenizedProject()` returns false if Maven plugin is disabled or no pom.xml found

---

### 10. GitRepositoryManager + ChangeListManager

**Package:** `git4idea.repo` / `com.intellij.openapi.vcs.changes`

```kotlin
import git4idea.repo.GitRepositoryManager
import git4idea.repo.GitRepository
import git4idea.GitLocalBranch
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ContentRevision
```

**Get instances:**
```kotlin
val gitManager = GitRepositoryManager.getInstance(project)
val changeListManager = ChangeListManager.getInstance(project)
```

**Key method signatures:**
```java
// GitRepositoryManager
public class GitRepositoryManager extends AbstractRepositoryManager<GitRepository> {
    @NotNull
    public static GitRepositoryManager getInstance(@NotNull Project project);

    // Get all git repositories in the project
    @NotNull
    public List<GitRepository> getRepositories();

    // Get repository for a specific file
    @Nullable
    public GitRepository getRepositoryForFile(@NotNull VirtualFile file);

    @Nullable
    public GitRepository getRepositoryForFileQuick(@NotNull FilePath filePath);

    // Get repository for project root
    @Nullable
    public GitRepository getRepositoryForRoot(@NotNull VirtualFile root);
}

// GitRepository
public interface GitRepository extends Repository {
    @Nullable GitLocalBranch getCurrentBranch();
    @NotNull  State getState();                     // NORMAL, REBASING, MERGING, DETACHED, GRAFTED
    @NotNull  GitBranchesCollection getBranches();
    @NotNull  VirtualFile getRoot();
    @NotNull  String getCurrentRevision();          // full SHA
    @Nullable GitRemote getRemote(@NotNull String name);
    @NotNull  Collection<GitRemote> getRemotes();
}

// GitLocalBranch
public class GitLocalBranch extends GitBranch {
    @NotNull public String getName();
    @Nullable public GitRemoteBranch findTrackedBranch(@NotNull GitRepository repository);
}

// ChangeListManager
public abstract class ChangeListManager {
    @NotNull
    public static ChangeListManager getInstance(@NotNull Project project);

    // Get all changes (staged + unstaged)
    @NotNull
    public abstract Collection<Change> getAllChanges();

    // Get changes in default changelist
    @NotNull
    public abstract List<LocalChangeList> getChangeLists();

    // Get affected files
    @NotNull
    public abstract List<VirtualFile> getAffectedFiles();

    // Get modified files without changes (e.g., new untracked files)
    @NotNull
    public abstract List<VirtualFile> getModifiedWithoutEditing();

    // Check if a file is modified
    public abstract boolean isModifiedDocumentTrackingRequired(@Nullable VirtualFile file);
}

// Change
public class Change {
    @Nullable public ContentRevision getBeforeRevision();
    @Nullable public ContentRevision getAfterRevision();
    @NotNull  public ChangeType getType();          // NEW, DELETED, MOVED, MODIFICATION
    @Nullable public VirtualFile getVirtualFile();
}
```

**Usage in agent tool:**
```kotlin
ReadAction.compute<String, Exception> {
    val repos = GitRepositoryManager.getInstance(project).repositories
    val repo = repos.firstOrNull() ?: return@compute "No git repository found"

    val branch = repo.currentBranch?.name ?: "DETACHED HEAD"
    val changes = ChangeListManager.getInstance(project).allChanges

    buildString {
        appendLine("Branch: $branch")
        appendLine("Changed files (${changes.size}):")
        changes.forEach { change ->
            val path = change.afterRevision?.file?.path ?: change.beforeRevision?.file?.path
            appendLine("  ${change.type}: $path")
        }
    }
}
```

**Threading:** `getRepositories()` and `getCurrentBranch()` are thread-safe, can read from any thread. `ChangeListManager.getAllChanges()` should be called from ReadAction or EDT.

**Requirements:**
- Requires Git4Idea plugin: `<depends optional="true" config-file="plugin-withGit.xml">Git4Idea</depends>`
- Already used extensively in our codebase (see `BranchingService`, `GenerateCommitMessageAction`, `SonarDataService`)

---

### 11. VcsAnnotationProvider (Git Blame)

**Package:** `com.intellij.openapi.vcs.annotate` / `git4idea.annotate`

```kotlin
import com.intellij.openapi.vcs.annotate.AnnotationProvider
import com.intellij.openapi.vcs.annotate.FileAnnotation
import com.intellij.openapi.vcs.AbstractVcs
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vcs.history.VcsFileRevision
import com.intellij.vcsUtil.VcsUtil
```

**Get the annotation provider:**
```kotlin
val vcsManager = ProjectLevelVcsManager.getInstance(project)
val vcs = vcsManager.getVcsFor(virtualFile)  // AbstractVcs (Git, SVN, etc.)
val annotationProvider = vcs?.annotationProvider  // AnnotationProvider?
```

**Key method signatures:**
```java
// AnnotationProvider interface
public interface AnnotationProvider {
    // Annotate the current version of a file
    @NotNull
    FileAnnotation annotate(@NotNull VirtualFile file) throws VcsException;

    // Annotate a specific revision
    @NotNull
    FileAnnotation annotate(
        @NotNull VirtualFile file,
        @NotNull VcsFileRevision revision
    ) throws VcsException;

    // Check if the provider can annotate this file
    boolean isAnnotationValid(@NotNull VirtualFile file);
}

// FileAnnotation - per-line blame data
public abstract class FileAnnotation {
    // Get the total number of lines
    public abstract int getLineCount();

    // Get revision for a specific line (0-based)
    @Nullable
    public abstract VcsRevisionNumber getLineRevisionNumber(int lineNumber);

    // Get author for a specific line
    @Nullable
    public abstract String getAnnotationAuthor(int lineNumber);  // e.g., "John Doe"

    // Get date for a specific line
    @Nullable
    public abstract Date getLineDate(int lineNumber);

    // Get tooltip text for a specific line
    @Nullable
    public abstract String getToolTip(int lineNumber);

    // Get all revisions
    @Nullable
    public abstract List<VcsFileRevision> getRevisions();

    // Dispose when done
    public abstract void dispose();
}
```

**Usage in agent tool:**
```kotlin
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.vcsUtil.VcsUtil

suspend fun getBlameInfo(virtualFile: VirtualFile, project: Project): String {
    // This is an I/O operation (runs git blame), do NOT run on EDT
    return withContext(Dispatchers.IO) {
        val vcsManager = ProjectLevelVcsManager.getInstance(project)
        val vcs = vcsManager.getVcsFor(virtualFile)
            ?: return@withContext "No VCS for file"
        val annotationProvider = vcs.annotationProvider
            ?: return@withContext "No annotation provider"

        val annotation = annotationProvider.annotate(virtualFile)
        try {
            buildString {
                for (line in 0 until annotation.lineCount) {
                    val author = annotation.getAnnotationAuthor(line) ?: "unknown"
                    val date = annotation.getLineDate(line)
                    val rev = annotation.getLineRevisionNumber(line)?.asString()?.take(8) ?: "?"
                    appendLine("$rev ${author.padEnd(20)} ${date ?: ""}")
                }
            }
        } finally {
            annotation.dispose()
        }
    }
}
```

**Threading:** `annotationProvider.annotate()` performs I/O (runs `git blame`) -- MUST be on background thread. NEVER call on EDT. The method is blocking (not suspend).

**Requirements:**
- File must be under version control
- Git4Idea plugin must be present
- File does NOT need to be open in editor

**Gotchas:**
- `annotate()` can be SLOW for large files (runs `git blame`)
- ALWAYS call `annotation.dispose()` when done (releases resources)
- Line numbers are 0-based
- `getLineRevisionNumber()` returns null for uncommitted lines

---

### 12. IntentionManager

**Package:** `com.intellij.codeInsight.intention`
**Source:** `platform/analysis-api/src/com/intellij/codeInsight/intention/IntentionManager.java`

```kotlin
import com.intellij.codeInsight.intention.IntentionManager
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.impl.ShowIntentionActionsHandler
```

**Get instance:**
```kotlin
val intentionManager = IntentionManager.getInstance()  // application-level, NOT project-level
```

**Key method signatures:**
```java
public abstract class IntentionManager {
    @NotNull
    public static IntentionManager getInstance();  // Note: NO project parameter

    // Get all registered and enabled intentions
    @NotNull
    public abstract List<IntentionAction> getAvailableIntentions();

    // Get intentions filtered by language
    @NotNull
    public List<IntentionAction> getAvailableIntentions(@NotNull Collection<String> languages);

    // Get ALL registered intentions (including disabled)
    @NotNull
    public abstract IntentionAction @NotNull [] getIntentionActions();

    // Register a new intention at runtime
    public abstract void addAction(@NotNull IntentionAction action);

    // Unregister
    public abstract void unregisterIntention(@NotNull IntentionAction action);
}

// IntentionAction interface - check availability at a specific location
public interface IntentionAction {
    @NotNull String getText();            // Display text
    @NotNull String getFamilyName();      // Category name

    // Check if this intention is available at the given caret position
    boolean isAvailable(
        @NotNull Project project,
        @Nullable Editor editor,
        @NotNull PsiFile file
    );

    // Execute the intention
    void invoke(
        @NotNull Project project,
        @Nullable Editor editor,
        @NotNull PsiFile file
    ) throws IncorrectOperationException;

    boolean startInWriteAction();
}
```

**Checking available intentions at a specific offset (without an editor):**
```kotlin
ReadAction.compute<List<String>, Exception> {
    val manager = IntentionManager.getInstance()
    val allIntentions = manager.availableIntentions

    // To check availability at a specific offset, you need a mock/lightweight editor
    // or use the cachedIntentions approach:
    val available = allIntentions.filter { intention ->
        try {
            intention.isAvailable(project, null, psiFile)  // null editor = no caret context
        } catch (e: Exception) {
            false
        }
    }
    available.map { it.text }
}
```

**Threading:** `getAvailableIntentions()` is thread-safe. `isAvailable()` requires ReadAction. `invoke()` may require WriteAction (depends on `startInWriteAction()`).

**Gotchas:**
- `IntentionManager.getInstance()` is application-level (NOT project-level)
- `isAvailable()` with `editor = null` will fail for many intentions that need caret position
- For proper availability checking, you need an Editor instance or to position at a specific offset
- Most intentions are designed for interactive use -- running them headlessly requires care

---

### 13. Spring Configuration Model

**Package:** `com.intellij.spring`
**Source:** IntelliJ Ultimate only (bundled Spring plugin)

```kotlin
import com.intellij.spring.SpringManager
import com.intellij.spring.model.SpringModel
import com.intellij.spring.model.CommonSpringBean
import com.intellij.spring.model.utils.SpringModelSearchers
import com.intellij.spring.model.utils.SpringModelUtils
```

**Get Spring models:**
```kotlin
val springManager = SpringManager.getInstance(project)
```

**Key method signatures:**
```java
// SpringManager - entry point
public abstract class SpringManager {
    public static SpringManager getInstance(@NotNull Project project);

    // Get the combined Spring model for a module (merges all filesets)
    @Nullable
    public abstract SpringModel getCombinedModel(@NotNull Module module);

    // Get all Spring models
    @NotNull
    public abstract List<SpringModel> getAllModels(@NotNull Module module);
}

// SpringModel - represents a Spring application context
public interface SpringModel extends CommonSpringModel {
    // Get all beans in this model
    @NotNull
    Collection<? extends CommonSpringBean> getAllCommonBeans();

    // Find beans by PSI class
    @NotNull
    List<SpringBaseBeanPointer> findBeansByPsiClass(@NotNull PsiClass psiClass);

    // Find bean by name
    @Nullable
    SpringBaseBeanPointer findBean(@NotNull String beanName);
}

// SpringModelSearchers - utility for searching across models
public class SpringModelSearchers {
    @Nullable
    public static SpringBaseBeanPointer findBean(
        @NotNull CommonSpringModel model,
        @NotNull String beanName
    );

    @NotNull
    public static List<SpringBaseBeanPointer> findBeans(
        @NotNull CommonSpringModel model,
        @NotNull String beanName
    );
}

// CommonSpringBean - represents a Spring bean definition
public interface CommonSpringBean {
    @Nullable String getBeanName();
    @Nullable PsiClass getBeanClass();         // null for XML-only beans
    @NotNull  PsiElement getIdentifyingElement();
    boolean isValid();
}
```

**Usage in agent tool:**
```kotlin
ReadAction.compute<String, Exception> {
    val springManager = SpringManager.getInstance(project)
    val modules = ModuleManager.getInstance(project).modules

    val beans = mutableListOf<String>()
    for (module in modules) {
        val model = springManager.getCombinedModel(module) ?: continue
        for (bean in model.allCommonBeans) {
            val name = bean.beanName ?: "(anonymous)"
            val className = bean.beanClass?.qualifiedName ?: "(unknown)"
            beans.add("$name -> $className")
        }
    }
    beans.joinToString("\n")
}
```

**Threading:** ReadAction required for all Spring model access.

**Requirements:**
- IntelliJ IDEA Ultimate only (Spring plugin is bundled in Ultimate, not Community)
- Plugin dependency: `<depends optional="true" config-file="plugin-withSpring.xml">com.intellij.spring</depends>`
- Works for annotation-based (`@Component`, `@Service`, `@Bean`) and XML-based bean definitions

**Gotchas:**
- `getCombinedModel()` returns null if no Spring facet is configured for the module
- Spring plugin API is NOT open-source -- documentation is at https://plugins.jetbrains.com/docs/intellij/spring-api.html
- Bean resolution requires smart mode (indices)
- The API changes between IntelliJ versions -- check compatibility

---

### 14. JPA Entity Model (via PSI, no dedicated JPA API)

**Note:** IntelliJ does NOT expose a public JPA-specific API like it does for Spring. The JPA support is part of the Jakarta EE plugin (Ultimate only) and its API is largely internal.

**Recommended approach:** Use PSI to find JPA entities by annotation scanning.

```kotlin
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
```

**Key method signatures for annotation-based JPA entity discovery:**
```java
// AnnotatedElementsSearch - find classes/methods/fields with specific annotations
public class AnnotatedElementsSearch {
    public static Query<PsiClass> searchPsiClasses(
        @NotNull PsiClass annotationClass,      // e.g., @Entity PsiClass
        @NotNull SearchScope scope
    );

    public static Query<PsiMethod> searchPsiMethods(
        @NotNull PsiClass annotationClass,
        @NotNull SearchScope scope
    );

    public static Query<PsiMember> searchPsiMembers(
        @NotNull PsiClass annotationClass,
        @NotNull SearchScope scope
    );
}
```

**Usage for finding JPA entities:**
```kotlin
ReadAction.compute<List<EntityInfo>, Exception> {
    val scope = GlobalSearchScope.projectScope(project)
    val facade = JavaPsiFacade.getInstance(project)

    // Find @Entity annotation class
    val entityAnnotation = facade.findClass("jakarta.persistence.Entity", GlobalSearchScope.allScope(project))
        ?: facade.findClass("javax.persistence.Entity", GlobalSearchScope.allScope(project))
        ?: return@compute emptyList()

    // Find all classes annotated with @Entity
    val entities = AnnotatedElementsSearch.searchPsiClasses(entityAnnotation, scope).findAll()

    entities.map { psiClass ->
        val tableName = psiClass.getAnnotation("jakarta.persistence.Table")
            ?.findAttributeValue("name")?.text?.removeSurrounding("\"")
            ?: psiClass.name

        val fields = psiClass.fields.mapNotNull { field ->
            val columnAnnotation = field.getAnnotation("jakarta.persistence.Column")
            val columnName = columnAnnotation?.findAttributeValue("name")?.text?.removeSurrounding("\"")
                ?: field.name
            val isId = field.hasAnnotation("jakarta.persistence.Id")
            FieldInfo(field.name ?: "", field.type.presentableText, columnName ?: "", isId)
        }

        EntityInfo(
            className = psiClass.qualifiedName ?: "",
            tableName = tableName ?: "",
            fields = fields
        )
    }
}
```

**Alternative: JPA Facet (Ultimate only, semi-internal API):**
```kotlin
// This works in IntelliJ Ultimate with Jakarta EE plugin
import com.intellij.jpa.facet.JpaFacet
import com.intellij.facet.FacetManager

val facetManager = FacetManager.getInstance(module)
val jpaFacet = facetManager.getFacetByType(JpaFacet.ID)  // may be null
// JpaFacet API is limited and mostly internal
```

**Threading:** ReadAction required. Smart mode required for annotation search.

**Requirements:**
- For annotation-based search: works in both Community and Ultimate editions
- For `JpaFacet`: Ultimate only with Jakarta EE plugin
- `javax.persistence` for JPA 2.x, `jakarta.persistence` for JPA 3.x+ (check both)

**Gotchas:**
- No stable public API for JPA model -- annotation scanning is the most reliable approach
- `AnnotatedElementsSearch` requires the annotation class to be resolvable (JPA JAR must be in project classpath)
- Kotlin `@Entity` classes use the same Java annotations, so PSI search works for both
- `psiClass.getAnnotation()` searches the class only, not inherited annotations -- use `AnnotationUtil.findAnnotation()` for inherited

---

## Summary Table

| # | API | Package | Threading | File Open Required? | Smart Mode? |
|---|-----|---------|-----------|-------------------|-------------|
| 1 | WolfTheProblemSolver | `com.intellij.problems` | Any thread | No (but needs prior highlighting) | No |
| 2 | CodeStyleManager | `com.intellij.psi.codeStyle` | WriteCommandAction | No | No |
| 3 | ImportOptimizer | `com.intellij.lang` | WriteCommandAction | No | Yes |
| 4 | InspectionManager | `com.intellij.codeInspection` | ReadAction | No | Yes |
| 5 | RenameProcessor | `com.intellij.refactoring.rename` | WriteCommandAction (EDT) | No | Yes |
| 6 | OverridingMethodsSearch | `com.intellij.psi.search.searches` | ReadAction | No | Yes |
| 7 | CompilerManager | `com.intellij.openapi.compiler` | EDT (async callback) | No | No |
| 8 | RunManager | `com.intellij.execution` | EDT for execution | No | No |
| 9 | MavenProjectsManager | `org.jetbrains.idea.maven.project` | Any thread | No | No |
| 10 | GitRepositoryManager | `git4idea.repo` | Any thread | No | No |
| 11 | VcsAnnotationProvider | `com.intellij.openapi.vcs.annotate` | Background thread (I/O) | No | No |
| 12 | IntentionManager | `com.intellij.codeInsight.intention` | ReadAction for availability | Needs Editor for full check | Yes |
| 13 | SpringManager | `com.intellij.spring` | ReadAction | No | Yes |
| 14 | JPA (via AnnotatedElementsSearch) | `com.intellij.psi.search.searches` | ReadAction | No | Yes |

---

## Plugin Dependencies Required

```xml
<!-- Already present -->
<depends>com.intellij.modules.platform</depends>
<depends>com.intellij.modules.java</depends>
<depends optional="true" config-file="plugin-withGit.xml">Git4Idea</depends>
<depends optional="true" config-file="plugin-withMaven.xml">org.jetbrains.idea.maven</depends>
<depends optional="true" config-file="plugin-withSpring.xml">com.intellij.spring</depends>

<!-- May need to add -->
<depends optional="true" config-file="plugin-withJUnit.xml">JUnit</depends>
<!-- For CompilerManager: part of com.intellij.modules.java, no extra dep needed -->
<!-- For JPA Facet (optional): -->
<depends optional="true" config-file="plugin-withJavaEE.xml">com.intellij.javaee</depends>
```

---

## Sources

- [WolfTheProblemSolver.java](https://github.com/JetBrains/intellij-community/blob/master/platform/analysis-api/src/com/intellij/problems/WolfTheProblemSolver.java)
- [CodeStyleManager.java](https://github.com/JetBrains/intellij-community/blob/master/platform/core-api/src/com/intellij/psi/codeStyle/CodeStyleManager.java)
- [ImportOptimizer.java](https://github.com/JetBrains/intellij-community/blob/idea/241.18034.62/platform/code-style-api/src/com/intellij/lang/ImportOptimizer.java)
- [InspectionManager.java](https://github.com/JetBrains/intellij-community/blob/master/platform/analysis-api/src/com/intellij/codeInspection/InspectionManager.java)
- [LocalInspectionTool.java](https://github.com/JetBrains/intellij-community/blob/master/platform/analysis-api/src/com/intellij/codeInspection/LocalInspectionTool.java)
- [RenameProcessor.java](https://github.com/JetBrains/intellij-community/blob/master/platform/lang-impl/src/com/intellij/refactoring/rename/RenameProcessor.java)
- [RefactoringFactory.java](https://github.com/JetBrains/intellij-community/blob/master/platform/lang-api/src/com/intellij/refactoring/RefactoringFactory.java)
- [CompilerManager.java](https://github.com/JetBrains/intellij-community/blob/master/java/compiler/openapi/src/com/intellij/openapi/compiler/CompilerManager.java)
- [RunManager.kt](https://github.com/JetBrains/intellij-community/blob/idea/243.22562.145/platform/execution/src/com/intellij/execution/RunManager.kt)
- [MavenProjectsManager.java](https://github.com/JetBrains/intellij-community/blob/master/plugins/maven/src/main/java/org/jetbrains/idea/maven/project/MavenProjectsManager.java)
- [GitRepositoryManager.java](https://github.com/JetBrains/intellij-community/blob/master/plugins/git4idea/src/git4idea/repo/GitRepositoryManager.java)
- [ChangeListManager.java](https://github.com/JetBrains/intellij-community/blob/master/platform/vcs-api/src/com/intellij/openapi/vcs/changes/ChangeListManager.java)
- [GitAnnotationProvider.java](https://github.com/JetBrains/intellij-community/blob/master/plugins/git4idea/src/git4idea/annotate/GitAnnotationProvider.java)
- [FileAnnotation.java](https://github.com/JetBrains/intellij-community/blob/master/platform/vcs-api/src/com/intellij/openapi/vcs/annotate/FileAnnotation.java)
- [IntentionManager.java](https://github.com/JetBrains/intellij-community/blob/master/platform/analysis-api/src/com/intellij/codeInsight/intention/IntentionManager.java)
- [IntentionAction.java](https://github.com/JetBrains/intellij-community/blob/master/platform/analysis-api/src/com/intellij/codeInsight/intention/IntentionAction.java)
- [Spring API Documentation](https://plugins.jetbrains.com/docs/intellij/spring-api.html)
- [Run Configurations Documentation](https://plugins.jetbrains.com/docs/intellij/run-configurations.html)
- [PSI Cookbook](https://plugins.jetbrains.com/docs/intellij/psi-cookbook.html)
- [Code Inspections Documentation](https://plugins.jetbrains.com/docs/intellij/code-inspections.html)
- [Rename Refactoring Documentation](https://plugins.jetbrains.com/docs/intellij/rename-refactoring.html)
