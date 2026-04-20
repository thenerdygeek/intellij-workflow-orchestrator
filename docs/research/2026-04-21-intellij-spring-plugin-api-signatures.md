# IntelliJ Ultimate Spring Plugin — Reflection-Accessible API Surface

**Date:** 2026-04-21
**Source:** Runtime introspection against `com.intellij.spring` bundled with IntelliJ IDEA Ultimate 2025.1.7 (test-classpath load)
**Purpose:** Pin the reflection contract for `SpringModelResolver` and similar Ultimate-native Spring tooling.

> **Why reflection?** The `:agent` module is a single JAR that ships against Community and Ultimate alike. `compileOnly` dependencies on `com.intellij.spring` would break Community-only classloaders at startup. Reflection keeps one JAR, at the cost of signature drift. This document pins the signatures so drift surfaces as a test failure, not a production "tool broken" message.

## Package corrections (non-obvious FQNs)

| Class | Expected FQN (incorrect) | **Actual FQN** |
|---|---|---|
| `CommonSpringModel` | `com.intellij.spring.model.CommonSpringModel` | **`com.intellij.spring.CommonSpringModel`** |
| `SpringModel` | `com.intellij.spring.model.SpringModel` | **`com.intellij.spring.contexts.model.SpringModel`** |
| `BeanClass` (search param) | (no guess) | **`com.intellij.spring.model.SpringModelSearchParameters$BeanClass`** (nested class) |

`SpringBeanPointer`, `CommonSpringBean`, `SpringStereotypeElement`, `SpringJavaBean`, `SpringModelSearchers`, `SpringModelUtils`, `SpringManager` are all under the FQNs you'd guess.

## Signatures

### `com.intellij.spring.SpringManager` (abstract class, implements `Disposable`)

```
static SpringManager getInstance(Project)
Set<SpringModel> getAllModels(Module)                           // ← takes Module, NOT Project
Set<SpringModel> getAllModelsWithoutDependencies(Module)
SpringModel getCombinedModel(Module)
SpringModel getSpringModelByFile(PsiFile)
Set<SpringModel> getSpringModelsByFile(PsiFile)
Object[] getModelsDependencies(Module, Object[])
```

### `com.intellij.spring.model.utils.SpringModelSearchers` (static util class)

```
static SpringBeanPointer findBean(CommonSpringModel, String)                    // by name / alias / class name
static Collection<SpringBeanPointer> findBeans(CommonSpringModel, String)       // by name (collection variant)
static List<SpringBeanPointer> findBeans(CommonSpringModel, SpringModelSearchParameters.BeanClass)  // by type
static boolean doesBeanExist(CommonSpringModel, PsiClass)
static boolean doesBeanExist(CommonSpringModel, SpringModelSearchParameters.BeanClass)
```

### `com.intellij.spring.CommonSpringModel` (interface)

```
Set<SpringProfile> getActiveProfiles()
Collection<CommonSpringBean> getAllCommonBeans()
Collection<CommonSpringBean> getAllCommonBeans(boolean includeInactive)
Module getModule()
Set<CommonSpringModel> getRelatedModels()
boolean processAllBeans(Processor<CommonSpringBean>, boolean includeInactive)
boolean processByClass(SpringModelSearchParameters.BeanClass, Processor)
boolean processByName(SpringModelSearchParameters.BeanName, Processor)
void processLocalBeans(Processor<CommonSpringBean>)
Direction traverseByClass(SpringModelSearchParameters.BeanClass, Processor)
Direction traverseByName(SpringModelSearchParameters.BeanName, Processor)
```

### `com.intellij.spring.contexts.model.SpringModel` (abstract class, IS-A `CommonSpringModel` via ancestry)

```
Set<SpringProfile> getActiveProfiles()
Set<SpringModel> getDependencies()
SpringFileSet getFileSet()
Module getModule()
Set<SpringModel> getRelatedModels()
Set<SpringModel> getRelatedModels(boolean)
void setDependencies(Set<SpringModel>)
```

### `com.intellij.spring.model.SpringBeanPointer<T>` (interface)

```
String getName()                                    // canonical bean name
String[] getAliases()                               // @Bean alias set / XML alias
PsiClass getBeanClass()                             // resolved bean type (may differ from declared)
Collection<PsiType> getEffectiveBeanTypes()         // all assignable types — interfaces + supertypes
CommonSpringBean getSpringBean()                    // the actual bean metadata
PsiFile getContainingFile()                         // defining file
SpringBeanPointer getBasePointer()
SpringBeanPointer getParentPointer()
SpringBeanPointer derive(String)
boolean isAbstract()
boolean isReferenceTo(CommonSpringBean)
boolean isValid()
```

### `com.intellij.spring.model.CommonSpringBean` (interface)

```
String getBeanName()
String[] getAliases()
PsiType getBeanType()
PsiType getBeanType(boolean)
SpringBeanScope getSpringScope()                    // singleton/prototype/request/session/...
SpringProfile getProfile()                          // @Profile value
Collection<SpringQualifier> getSpringQualifiers()   // @Qualifier values
boolean isPrimary()                                 // @Primary
PsiFile getContainingFile()
```

### `com.intellij.spring.model.jam.stereotype.SpringStereotypeElement` (for `@Component`/`@Service`/`@Repository`/`@Controller`/`@Configuration`)

```
extends JamPsiClassSpringBean
String getBeanName()
List<SpringBean> getBeans()
PsiElement getIdentifyingPsiElement()               // the PsiClass
PsiAnnotation getPsiAnnotation()                    // the stereotype annotation
PsiTarget getPsiTarget()
```

### `com.intellij.spring.model.jam.javaConfig.SpringJavaBean` (for `@Bean`-annotated methods)

```
extends JamPsiMethodSpringBean
PsiElement getIdentifyingPsiElement()               // the PsiMethod
PsiNamedElement getIdentifyingPsiElement()
PsiAnnotation getPsiAnnotation()                    // the @Bean annotation
boolean isPublic()
```

### `com.intellij.spring.model.utils.SpringModelUtils` (abstract, `getInstance()` singleton)

```
static SpringModelUtils getInstance()
CommonSpringModel getModuleCombinedSpringModel(PsiElement)      // most convenient for PSI entry
CommonSpringModel getPsiClassSpringModel(PsiClass)
CommonSpringModel getSpringModel(PsiElement)
CommonSpringModel getSpringModel(SpringModelElement)
CommonSpringModel getSpringModelByBean(CommonSpringBean)
SpringModel getCombinedSpringModel(Set, Module)
boolean hasAutoConfiguredModels(Module)
boolean isTestContext(Module, PsiFile)
boolean isUsedConfigurationFile(PsiFile, boolean)
```

### `com.intellij.spring.model.SpringModelSearchParameters.BeanClass` (nested class)

Constructed via static factories on parent (`SpringModelSearchParameters.byClass(PsiClass)` etc. — not yet dumped).

```
boolean canSearch()
BeanClass effectiveBeanTypes()          // fluent: also include inherited/assignable types
BeanClass withInheritors()              // fluent: include subtypes
BeanClass searchInLibraries(boolean)    // fluent: include library beans
PsiType getSearchType()
boolean isEffectiveBeanTypes()
boolean isSearchInLibraries()
boolean isWithInheritors()
```

## Notes for implementers

- **`SpringManager.getAllModels` returns `Set`, not `List`.** Our old code cast to `Collection<Any>`, which worked by luck — `Set` implements `Collection`. Prefer `Set<*>` in new code.
- **`SpringModel` extends `AbstractProcessableModel`**, which (apparently) implements `CommonSpringModel`. This means `SpringModelSearchers.findBean(SpringModel, "name")` works at the type level because `SpringModel` IS-A `CommonSpringModel` via ancestry.
- **`SpringModelUtils.getModuleCombinedSpringModel(PsiElement)`** is the most ergonomic entry point when you already have a PSI element — skips the Module iteration entirely for per-element queries.
- **`CommonSpringBean.getBeanType()` returns `PsiType`** (not `PsiClass`) — can be primitive, array, or generic. Resolve to PsiClass via `PsiTypesUtil.getPsiClass(psiType)`.
- **`SpringBeanPointer.getEffectiveBeanTypes()`** is the right answer for "given bean X, what types could be injected by?" — covers interface injection, generic erasure, hierarchy.

## Verified against

- IntelliJ IDEA Ultimate 2025.1.7
- `com.intellij.spring` bundled plugin (whatever version ships with IU-251)
- Test classloader: `com.intellij.util.lang.PathClassLoader` (standard IntelliJ Platform Plugin v2 test runtime)

Runtime classes loaded successfully. `Class.forName("com.intellij.spring.model.CommonSpringModel")` fails — that FQN is wrong; the class lives at `com.intellij.spring.CommonSpringModel`. Lesson: never trust package guesses for plugin internals — introspect.
