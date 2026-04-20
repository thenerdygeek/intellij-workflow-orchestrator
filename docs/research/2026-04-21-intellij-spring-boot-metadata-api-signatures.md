# IntelliJ Ultimate Spring Boot Plugin — Reflection-Accessible API Surface

**Date:** 2026-04-21
**Source:** Runtime introspection against `com.intellij.spring.boot` bundled with IntelliJ IDEA Ultimate 2025.1.7 (test-classpath load)
**Purpose:** Pin the reflection contract for `SpringBootMetadataResolver` (to be written in the next dispatch).

> **Why reflection?** The `:agent` module ships as a single JAR against both Community and Ultimate.
> `compileOnly` against `com.intellij.spring.boot` would break Community-only classloaders at startup.
> Reflection keeps one JAR, at the cost of signature drift. This document pins the signatures so
> drift surfaces as a test failure, not a production "tool broken" message.

## Package corrections (non-obvious FQNs)

| Guessed FQN | **Actual FQN** | Notes |
|---|---|---|
| `com.intellij.spring.boot.SpringBootLibraryUtil` | **`com.intellij.spring.boot.library.SpringBootLibraryUtil`** | Nested under `library/` sub-package |
| `com.intellij.spring.boot.metadata.ApplicationMetadataIndex` | **`com.intellij.spring.boot.model.autoconfigure.AutoConfigureMetadataIndex`** | Index lives under `model.autoconfigure`, not `metadata` |
| `com.intellij.spring.boot.metadata.model.ConfigurationMetadata` | **`com.intellij.spring.boot.application.metadata.SpringBootConfigKeysData`** | Data class: configFiles + keyDefinitions + keyHints |
| `com.intellij.spring.boot.metadata.model.MetaConfigKey` | **`com.intellij.spring.boot.application.metadata.SpringBootApplicationMetaConfigKeyImpl`** | Extends `AbstractMetaConfigKey`; the manager returns `MetaConfigKey` interface |
| `com.intellij.spring.boot.application.SpringBootApplicationUtil` | **`com.intellij.spring.boot.application.SpringBootApplicationService`** | Interface, obtained via `getInstance()` |
| `com.intellij.spring.boot.autoconfigure.SpringBootAutoConfigurationUtil` | **`com.intellij.spring.boot.model.autoconfigure.SpringBootAutoConfigClassFilterService`** | Abstract, singleton via `getInstance()` |

**The `application.metadata` package is the main metadata namespace.** All `[MISSING]` results in early rounds pointed to FQNs that simply don't exist; the real classes are in `application.metadata.*` and `model.autoconfigure.*`.

## Signatures

### `com.intellij.spring.boot.library.SpringBootLibraryUtil`

Static utility — all methods are `static`. No instance needed.

```
static PatternCondition createVersionPatternCondition(SpringBootVersion)
static SpringBootVersion getSpringBootVersion(Module)           // returns the enum constant or null
static String getVersionFromJar(Module)                        // raw string e.g. "3.2.0"
static boolean hasActuators(Module)
static boolean hasDevtools(Module)
static boolean hasRequestMappings(Module)
static boolean hasSpringBootLibrary(Module)
static boolean hasSpringBootLibrary(Project)
static boolean isAtLeastVersion(Module, SpringBootVersion)
static boolean isBelowVersion(Module, SpringBootVersion)
```

Public constant fields:
```
static PatternCondition SB_1_3_OR_HIGHER
static PatternCondition SB_1_5_OR_HIGHER
static String SPRING_BOOT_MAVEN                               // Maven artifact ID string
```

### `com.intellij.spring.boot.library.SpringBootLibraryUtil$SpringBootVersion` (enum)

Access via `SpringBootLibraryUtil.getSpringBootVersion(module)`.

```
boolean isAtLeast(SpringBootVersion)
static SpringBootVersion valueOf(String)
static SpringBootVersion[] values()
// enum constants: ANY, VERSION_1_2_0 ... VERSION_3_4_0
```

Enum constants (all `static SpringBootVersion`):
`ANY`, `VERSION_1_2_0`, `VERSION_1_3_0`, `VERSION_1_4_0`, `VERSION_1_5_0`, `VERSION_2_0_0`,
`VERSION_2_1_0`, `VERSION_2_2_0`, `VERSION_2_4_0`, `VERSION_2_5_0`, `VERSION_2_7_0`,
`VERSION_3_0_0`, `VERSION_3_4_0`.

### `com.intellij.spring.boot.application.metadata.SpringBootApplicationMetaConfigKeyManager` (abstract, singleton)

This is the **primary entry point** for Spring Boot configuration property metadata.
Obtain via `SpringBootApplicationMetaConfigKeyManager.getInstance()`.

```
static SpringBootApplicationMetaConfigKeyManager getInstance()
List getAllMetaConfigGroups(Module)                             // returns List<SpringBootConfigKeyGroup>
MetaConfigKey createFakeConfigKey(Module, String, AccessType)  // builds a synthetic key
MetaConfigKey createFakeConfigKey(Module, String)
MetaConfigKey createFakeConfigKey(Project, String, AccessType)
SpringBootConfigKeyGroup getGroupForKey(Module, MetaConfigKey)
extends: MetaConfigKeyManager
```

**The concrete impl** (`SpringBootApplicationMetaConfigKeyManagerImpl`) adds:
```
List getAllMetaConfigKeys(Module)                              // returns List<MetaConfigKey>
ConfigKeyNameBinder getConfigKeyNameBinder(Module)
```

To get all config keys from a module: call `getAllMetaConfigKeys(module)` on the impl,
or cast `getInstance()` to `SpringBootApplicationMetaConfigKeyManagerImpl` first.

### `com.intellij.spring.boot.application.metadata.SpringBootApplicationMetaConfigKeyImpl`

Returned by `getAllMetaConfigKeys`. Extends `AbstractMetaConfigKey` which implements `MetaConfigKey`.

```
PsiElement getDeclaration()
DeclarationResolveResult getDeclarationResolveResult()
String getDefaultValue()
Deprecation getDeprecation()
Deprecation getDeprecationFast()              // avoids PSI resolve — use for filtering
DescriptionText getDescriptionText()
ItemHint getItemHint()
ItemHint getKeyItemHint()
MetaConfigKeyManager getManager()
ConfigKeyPathBeanPropertyResolver getPropertyResolver()
String getSourceType()                        // FQN of the @ConfigurationProperties class
```

### `com.intellij.spring.boot.application.metadata.SpringBootKeyDefinition` (Kotlin data class)

Property definition backed by a PSI element (field, getter, or `@ConfigurationProperties`-annotated class).

```
String getName()                              // dot-notation key name e.g. "server.port"
PsiType getType()
Lazy getDefaultValue()                        // Lazy<String?> — avoid forcing if not needed
DescriptionText getDescriptionText()          // Lazy<String?> wrapper
Lazy getDeprecation()                         // Lazy<Deprecation?> wrapper
PsiElement getDefiningElement()               // the PsiField/PsiMethod that declares this
PsiClass getContainingClass()
```

### `com.intellij.spring.boot.application.metadata.SpringBootConfigKeyGroup` (interface)

```
String getName()
String getType()                              // FQN of the group type
PsiModifierListOwner getDeclaration()
```

### `com.intellij.spring.boot.application.metadata.SpringBootConfigKeyLocations`

Data class returned by `SpringBootConfigKeysCollector.collectConfigKeysFromSources()`.

```
Collection getFiles()                         // Collection<VirtualFile> — contributing metadata JSONs
List getKeys()                                // List<SpringBootKeyDefinition>
```

### `com.intellij.spring.boot.application.metadata.SpringBootConfigKeysData`

A snapshot of all keys in a module. Three components:
```
Collection getConfigFiles()                   // VirtualFiles of spring-configuration-metadata.json
Map getAdditionalKeyDefinitions()             // Map<String,SpringBootKeyDefinition> — user-defined
Map getKeyHints()                             // Map<String,ItemHint>
```

### `com.intellij.spring.boot.application.metadata.SpringBootMetadataConstants`

String constants for JSON field names in `spring-configuration-metadata.json`:

```
PROPERTIES, GROUPS, HINTS, NAME, SOURCE_TYPE, TYPE, DESCRIPTION, DEFAULT_VALUE,
DEPRECATED, DEPRECATION, REPLACEMENT, REASON, LEVEL, SOURCE_METHOD, VALUES,
PROVIDERS, VALUE, PARAMETERS, TARGET, CONCRETE, GROUP,
SPRING_PROFILES_KEY, SPRING_CONFIG_ACTIVE_ON_PROFILE_KEY
```

### `com.intellij.spring.boot.application.metadata.SpringBootConfigurationMetadataParser`

Parser for `spring-configuration-metadata.json` files.

```
static PsiElement findPropertyNavigationTarget(PsiClass, String, Module)
List getGroups()                              // parsed group entries
static Map getItemHints(JsonObject)           // parse hint block
```

### `com.intellij.spring.boot.application.metadata.SpringBootValueProvider` (enum)

Hint value provider types:

```
static SpringBootValueProvider findById(String)
String getDescription()
String getId()
Parameter[] getParameters()
boolean hasRequiredParameters()
// enum constants: ANY, CLASS_REFERENCE, HANDLE_AS, LOGGER_NAME, SPRING_BEAN_REFERENCE, SPRING_PROFILE_NAME
```

### `com.intellij.spring.boot.model.autoconfigure.AutoConfigureMetadataIndex`

A `FileBasedIndexExtension` for `spring-autoconfigure-metadata.properties`.

```
DataIndexer getIndexer()
InputFilter getInputFilter()
KeyDescriptor getKeyDescriptor()
IndexId getName()
DataExternalizer getValueExternalizer()
int getVersion()
boolean dependsOnFileContent()
extends: FileBasedIndexExtension
```

### `com.intellij.spring.boot.model.autoconfigure.SpringBootAutoConfigClassFilterService` (abstract, singleton)

```
static SpringBootAutoConfigClassFilterService getInstance()
List filterByConditionalOnClass(Module, List)   // List<String> FQNs -> filtered List<String>
```

### `com.intellij.spring.boot.model.SpringBootConfigValueSearcher`

Searches config files for the value of a given property key.

```
static SpringBootConfigValueSearcher productionForAllProfiles(Module, String)
static SpringBootConfigValueSearcher productionForAllProfiles(Module, String, boolean)
static SpringBootConfigValueSearcher productionForProfiles(Module, String, Set)
static SpringBootConfigValueSearcher productionForProfiles(Module, String, Set, boolean)
static SpringBootConfigValueSearcher productionForProfiles(Module, String, Set, String, String)
String findValueText()                          // returns the first matching value or null
boolean process(Processor)                      // Processor<ConfigurationValueResult>
static boolean processConfigFilesWithPropertySources(ConfigurationValueSearchParams, boolean, Processor)
static Set clearDefaultTestProfile(Set)
```

### `com.intellij.spring.boot.model.ConfigurationValueResult`

A single match from `SpringBootConfigValueSearcher.process()`.

```
MetaConfigKey getConfigKey()
int getDocumentId()
PsiElement getKeyElement()
String getKeyIndexText()
MetaConfigKeyReference getMetaConfigKeyReference()
String getProfileText()
PsiElement getValueElement()
String getValueText()
```

### `com.intellij.spring.boot.model.ConfigurationValueSearchParams` (Kotlin data class)

Builder for `ConfigValueSearcher` queries.

```
Module getModule()
MetaConfigKey getConfigKey()
String getKeyProperty()
String getKeyIndex()
Set getActiveProfiles()
boolean getCheckRelaxedNames()
boolean getProcessImports()
boolean isProcessAllProfiles()
Set getProcessedFiles()
ConfigurationValueSearchParams withProcessImports(boolean)   // fluent copy
static Set PROCESS_ALL_VALUES                                // sentinel for "all profiles"
```

### `com.intellij.spring.boot.model.SpringBootConfigurationFileService` (interface, singleton)

```
static SpringBootConfigurationFileService getInstance()
List findConfigFiles(Module, boolean)
List findConfigFiles(Module, boolean, Condition)
List findConfigFiles(Module, Set, boolean, boolean)           // Set<String> profiles
List findConfigFilesWithImports(Module, boolean)
List findPropertySources(Module)
List collectImports(Module, List)
List getImports(Module, VirtualFile)
boolean isApplicationConfigurationFile(PsiFile)
Icon getApplicationConfigurationFileIcon(PsiFile)
// static fields:
static String SPRING_CONFIG_IMPORT_KEY
static Key CONFIGURATION_FILE_MARKER_KEY
```

### `com.intellij.spring.boot.application.SpringBootApplicationService` (interface, singleton)

```
static SpringBootApplicationService getInstance()
boolean isSpringApplication(PsiClass)
boolean isSpringBootApplicationRun(PsiClass)
boolean hasMainMethod(PsiClass)
boolean hasSpringBootApplicationRun(PsiMethod)
List getSpringApplications(Module)                           // List<PsiClass>
PsiClass findMainClassCandidate(PsiClass)
```

### `com.intellij.spring.boot.model.properties.jam.ConfigurationProperties`

JAM element for `@ConfigurationProperties`-annotated classes/methods.

```
static ConfigurationProperties getByPsiMember(PsiMember)    // null if not annotated
String getValueOrPrefix()                                    // the prefix string
JamStringAttributeElement getValueOrPrefixAttribute()
PsiAnnotation getAnnotation()
Collection getLocations()
boolean isMerge()
boolean isIgnoreUnknownFields()
boolean isIgnoreInvalidFields()
boolean isIgnoreNestedProperties()
boolean isExceptionIfInvalid()
extends: JamBaseElement
static JamClassMeta CLASS_META
```

### `com.intellij.spring.boot.model.properties.ConfigurationPropertiesDiscoverer`

Discovers `@ConfigurationProperties` beans from a `LocalModel`.

```
static CommonSpringBean createConfigurationPropertiesBean(ConfigurationProperties, PsiClass, boolean)
Collection getCustomComponents(LocalModel)
extends: CustomLocalComponentsDiscoverer
```

### Run configuration classes

`com.intellij.spring.boot.run.SpringBootApplicationRunConfiguration` (extends `ApplicationConfiguration`):

```
String getSpringBootMainClass()
void setSpringBootMainClass(String)
PsiClass getMainClass()
String getActiveProfiles()
void setActiveProfiles(String)
boolean isLifecycleManagementEnabled()
boolean isEnableJmxAgent()
// + standard ApplicationConfiguration getter/setters
```

`com.intellij.spring.boot.run.SpringBootApplicationConfigurationType`:
```
static String ID     // = "SpringBootApplicationConfigurationType"
static SpringBootApplicationConfigurationTypeBase getInstance()
```

### Live runtime models (Actuator)

`com.intellij.spring.boot.run.lifecycle.beans.model.LiveBeansModel` (interface):
```
List getBeans()       // List<LiveBean>
List getContexts()    // List<LiveContext>
List getResources()   // List<LiveResource>
```

`com.intellij.spring.boot.run.lifecycle.beans.model.LiveBean` (interface):
```
Set getDependencies()   // Set<String> bean names
Set getInjectedInto()
LiveResource getResource()
implements: SpringRuntimeBean
```

`com.intellij.spring.boot.run.lifecycle.env.model.RuntimeEnvironmentModel`:
```
List getActiveProfiles()
List getPropertySources()   // List<RuntimePropertySource>
```

`com.intellij.spring.boot.run.lifecycle.env.model.RuntimeProperty`:
```
String getName()
String getValue()
String getSource()
String getOffset()
boolean isHidden()
SpringRuntimeResource getResource()
```

`com.intellij.spring.boot.run.lifecycle.conditions.model.RuntimeConditionsModel`:
```
Map getOutcomes()   // Map<String, RuntimeConditionOutcome>
```

`com.intellij.spring.boot.run.lifecycle.conditions.model.RuntimeConditionOutcome`:
```
String getCondition()
boolean getMatched()
String getMessage()
```

## Notes for implementers

- **The metadata manager is the correct entry point for config-key listing.** Do NOT try to directly parse `spring-configuration-metadata.json` files yourself — use `SpringBootApplicationMetaConfigKeyManagerImpl.getAllMetaConfigKeys(module)`.

- **`MetaConfigKey` is an interface** from the `com.intellij.spring.model.metadata.MetaConfigKey` package (core Spring plugin, not Spring Boot). `SpringBootApplicationMetaConfigKeyImpl` is the Spring Boot implementation. When iterating keys, cast or use the interface methods only.

- **`getDeprecationFast()` vs `getDeprecation()`:** `getDeprecationFast()` avoids PSI resolution. Use it for quick checks in list rendering; use `getDeprecation()` only when you need the full deprecation details (replacement key, reason).

- **Kotlin data class `copy$default` methods:** Ignore these — they are Kotlin compiler artifacts. Use the regular `copy(...)` overload or just read fields.

- **`SpringBootConfigValueSearcher` is `static`-factory-based:** Create via `productionForAllProfiles(module, key)`. Call `findValueText()` for the most common query, or `process(Processor)` to iterate all matches with profile info.

- **Generic parameter erasure:** `getAllMetaConfigKeys(Module)` returns `List` (raw). Cast to `List<MetaConfigKey>` with an unchecked cast. Same for `getAllMetaConfigGroups` returning `List<SpringBootConfigKeyGroup>`.

- **`SpringBootApplicationMetaConfigKeyManager.getInstance()`** returns the abstract type. For `getAllMetaConfigKeys`, you must call `getInstance()` and then either invoke via reflection (to avoid compile-time dependency) or cast to `SpringBootApplicationMetaConfigKeyManagerImpl`. Via reflection:
  ```kotlin
  val mgr = Class.forName("com.intellij.spring.boot.application.metadata.SpringBootApplicationMetaConfigKeyManager")
      .getMethod("getInstance").invoke(null)
  val keys = mgr.javaClass.getMethod("getAllMetaConfigKeys", Module::class.java).invoke(mgr, module) as List<*>
  ```

- **Thread safety:** All PSI/index operations must run on a read action thread (or inside `ReadAction.compute`). `hasSpringBootLibrary(Module)` is safe from any thread (reads module libraries, not PSI).

- **`AutoConfigureMetadataIndex`** indexes `spring-autoconfigure-metadata.properties` and `spring-boot/spring-autoconfigure.imports` files. It is a `FileBasedIndex` extension — use `FileBasedIndex.getInstance().getValues(...)` with its `IndexId`, not direct reflection on the class itself.

- **`SpringBootConfigurationFileService.findConfigFiles(module, true)`** — the boolean is `includeTests`. Pass `false` for production-only search.

- **`SpringBootApplicationRunConfiguration` extends `ApplicationConfiguration`** (IntelliJ Run Config API), so all standard `RunConfiguration` APIs apply. The Spring Boot-specific fields are the `activeProfiles`, `springBootMainClass`, and lifecycle flags.

## Verified against

- IntelliJ IDEA Ultimate 2025.1.7 (build IU-251.*)
- `com.intellij.spring.boot` bundled plugin (`spring-boot-core.jar` — 623 top-level classes)
- Test classloader: `com.intellij.util.lang.PathClassLoader` (standard IntelliJ Platform Plugin v2 test runtime)
- Kotlin 2.1.10, JVM 21

Classes that **loaded successfully** (reflection-accessible at test runtime):
- `SpringBootLibraryUtil` + `SpringBootVersion` enum
- `SpringBootApplicationConfigurationType`, `SpringBootApplicationConfigurationTypeBase`
- `SpringBootApplicationRunConfiguration`, `SpringBootApplicationRunConfigurationBase`
- `AutoConfigureMetadataIndex`, `SpringBootAutoConfigClassFilterService`
- `SpringBootApplicationMetaConfigKeyManager` + `Impl` + `Impl`
- `SpringBootConfigKeysData`, `SpringBootConfigKeysCollector`, `SpringBootKeyDefinition`
- `SpringBootConfigKeyGroup` + `Impl`, `SpringBootConfigKeyLocations`
- `SpringBootMetadataConstants`, `SpringBootValueProvider`, `SpringBootConfigurationMetadataParser`
- `ClassContent`
- `ConfigurationProperties`, `EnableConfigurationProperties`, `NestedConfigurationProperty`
- `ConfigurationPropertiesDiscoverer`
- `SpringBootApplicationService`, `SpringBootMainUtil`
- `SpringBootApplication`, `EnableAutoConfiguration`, `AutoConfiguration` (JAM elements)
- `SpringBootConfigurationFileService`
- `LiveBeansModel`, `LiveBean`, `RuntimeEnvironmentModel`, `RuntimeProperty`, `RuntimePropertySource`
- `RuntimeConditionsModel`, `RuntimeConditionOutcome`
- `SpringBootConfigValueSearcher`, `ConfigurationValueResult`, `ConfigurationValueSearchParams`

All `[MISSING]` results were caused by **incorrect package guesses** in rounds 1 and 2. Every intended class has a real counterpart that was located in rounds 3/4.
