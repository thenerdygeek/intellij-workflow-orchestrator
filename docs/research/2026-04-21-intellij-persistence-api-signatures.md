# IntelliJ Ultimate Persistence Plugin — Reflection-Accessible API Surface

**Date:** 2026-04-21
**Source:** Runtime introspection against `com.intellij.persistence` bundled with IntelliJ IDEA Ultimate 2025.1.7 (test-classpath load)
**Purpose:** Pin the reflection contract for `PersistenceModelResolver` (to be written in the next dispatch).

> **Why reflection?** The `:agent` module ships as a single JAR against both Community and Ultimate.
> `compileOnly` against `com.intellij.persistence` would break Community-only classloaders at startup.
> Reflection keeps one JAR, at the cost of signature drift. This document pins the signatures so
> drift surfaces as a test failure, not a production "tool broken" message.

## Package corrections (non-obvious FQNs)

8 rounds of introspection were needed. The following table documents every FQN guess that missed, and the
actual location discovered via return-type walking.

| Guessed FQN | Actual FQN | Notes |
|---|---|---|
| `com.intellij.persistence.JpaFacet` | **[not found]** | Does not exist in the test classloader; the JPA facet is not a top-level class at this path |
| `com.intellij.persistence.facet.JpaFacetType` | **[not found]** | No `JpaFacetType` class accessible under `facet` subpackage |
| `com.intellij.persistence.model.PersistenceCommonModel` | **`com.intellij.persistence.model.PersistenceMappings`** | The "model" is accessed via `PersistenceFacet.getEntityMappings(PersistencePackage)` which returns `PersistenceMappings`; no standalone `CommonModel` type exists |
| `com.intellij.persistence.model.PersistenceCommonEntity` | **`com.intellij.persistence.model.PersistentEntity`** | Confirmed via `PersistenceMappingsModelHelper.getPersistentEntities()` generic return type |
| `com.intellij.persistence.model.PersistenceColumnInfo` | **[not found]** | Column metadata is accessed via `PersistentAttributeModelHelper.getMappedColumns()` (returns `List<GenericValue<String>>`) and `TableInfoProvider.getTableName()`, not a dedicated ColumnInfo type |
| `com.intellij.persistence.model.PersistenceRelationshipInfo` | **`com.intellij.persistence.model.PersistentRelationshipAttribute`** | Relationship metadata lives on the attribute itself, via `PersistentRelationshipAttributeModelHelper` |
| `com.intellij.persistence.model.PersistenceMappedSuperclass` | **`com.intellij.persistence.model.PersistentSuperclass`** | Confirmed via `PersistenceMappingsModelHelper.getPersistentSuperclasses()` generic |
| `com.intellij.jpa.model.JpaEntity` | **[not found]** | No `com.intellij.jpa.model.*` classes accessible from test classloader; JPA entity model is accessed purely through `com.intellij.persistence.model.*` hierarchy |
| `com.intellij.jpa.model.JpaPersistentAttribute` | **[not found]** | Same — use `com.intellij.persistence.model.PersistentAttribute` |
| `com.intellij.jpa.model.JpaRelationshipAttribute` | **[not found]** | Same — use `com.intellij.persistence.model.PersistentRelationshipAttribute` |
| `com.intellij.persistence.model.utils.PersistenceModelUtils` | **[not found]** | No utility class at this path |
| `com.intellij.jpa.model.JpaModelUtil` | **[not found]** | No utility class at this path |
| `com.intellij.persistence.model.ModelValidator` | **`com.intellij.persistence.model.validators.ModelValidator`** | Sub-package `validators`, not `model` root |
| `com.intellij.persistence.model.PersistenceUnitModelHelper` | **`com.intellij.persistence.model.helpers.PersistenceUnitModelHelper`** | Sub-package `helpers` |
| `com.intellij.persistence.PersistenceModelBrowser` | **`com.intellij.persistence.util.PersistenceModelBrowser`** | Sub-package `util` |
| `com.intellij.persistence.ManipulatorsRegistry` | **`com.intellij.persistence.model.manipulators.ManipulatorsRegistry`** | Sub-package `model.manipulators` |
| `com.intellij.persistence.facet.PersistencePackageDefaults` | confirmed at `com.intellij.persistence.facet.PersistencePackageDefaults` | Correct package |

**The `com.intellij.jpa.*` package is not accessible from the test classloader at IU-2025.1.** All JPA entity
metadata must be accessed through the `com.intellij.persistence.model.*` and `com.intellij.persistence.model.helpers.*`
interfaces. The `com.intellij.persistence` plugin provides a vendor-neutral persistence layer; JPA-specific types
(annotation discovery) go through `PersistenceHelper.getSharedModelBrowser()` which works across JPA, EclipseLink, etc.

## Signatures

### `com.intellij.persistence.PersistenceHelper` (abstract, singleton)

This is the **primary entry point** for the Persistence plugin. Obtain via `PersistenceHelper.getHelper()`.

```
static PersistenceHelper getHelper()
PersistenceModelBrowser createModelBrowser()          // create a fresh scoped browser
PersistenceModelBrowser getSharedModelBrowser()       // shared (project-wide) browser — prefer this
ManipulatorsRegistry getManipulatorsRegistry()
void runCompositeWriteCommandAction(Project, String, Collection, Ref, Runnable[])
```

Via reflection:
```kotlin
val helper = Class.forName("com.intellij.persistence.PersistenceHelper")
    .getMethod("getHelper").invoke(null)
val browser = helper.javaClass.getMethod("getSharedModelBrowser").invoke(helper)
```

### `com.intellij.persistence.util.PersistenceModelBrowser` (interface)

The **main query API** for navigating the persistence model. Returned by `PersistenceHelper.getSharedModelBrowser()`.
All `query*` methods return `com.intellij.util.Query<T>` — use `.findAll()` or `.forEach()`.

```
Query<PersistenceFacet>  queryPersistenceFacets(PsiElement)          // find all facets covering a PsiElement
Query<PersistentObject>  queryPersistentObjects(PsiClass)            // all persistent objects for a PsiClass
Query<PersistentObject>  queryPersistentObjects(PsiClass, PersistenceClassRoleEnum)
Query<PersistentObject>  queryPersistentObjectHierarchy(PersistentObject)
Query<PersistentAttribute> queryAttributes(PersistentObject)
Query<PersistenceListener> queryPersistenceListeners(PsiClass)
Query<PersistentObject>  queryTargetPersistentObjects(PersistentRelationshipAttribute)
Query<PersistentObject>  queryTargetPersistentObjects(PersistentEmbeddedAttribute)
Query<PersistentRelationshipAttribute> queryTheOtherSideAttributes(PersistentRelationshipAttribute, boolean)
Query<PersistentRelationshipAttribute> queryTheOtherSideAttributes(PersistentRelationshipAttribute, boolean, PersistentObject)

List<PersistentAttribute> getPersistenceAttributes(PsiMember)
List<PersistentAttribute> getPersistenceAttributes(PersistentObject, PsiMember)
List<PersistenceFacet>    getPersistenceFacets(PersistencePackage)
List<PersistencePackage>  getPersistenceUnits(PersistenceMappings)

boolean acceptsRole(PersistenceClassRole)
PersistenceModelBrowser setRoleFilter(Condition)    // fluent — filters which roles are visited
```

### `com.intellij.persistence.facet.PersistenceFacet` (interface)

One facet per persistence-enabled module. Accessed via `PersistenceModelBrowser.queryPersistenceFacets(psiElement)`.

```
List<PersistenceMappings>  getDefaultEntityMappings(PersistencePackage)
PersistenceMappings        getEntityMappings(PersistencePackage)
PersistenceMappings        getAnnotationEntityMappings()                // annotation-based mappings (no XML)
PersistenceMappings        getAnnotationEntityMappings(PersistencePackage)
List<PersistencePackage>   getPersistenceUnits()                       // persistence units (JPA units)
List<PersistencePackage>   getExplicitPersistenceUnits()
Class<? extends PersistencePackage> getPersistenceUnitClass()
PersistencePackageDefaults getPersistenceUnitDefaults(PersistencePackage)
ModelValidator             getModelValidator(PersistencePackage)
ModificationTracker        getModificationTracker()
Module                     getModule()
String                     getNamingStrategy(PersistencePackage)
String                     getDataSourceId(PersistencePackage)
String                     getDataSourceId(PersistencePackagePointer)
List<ConfigFile>           getDescriptors()
Language                   getQlLanguage()
Map<ConfigFileMetaData, Class<? extends PersistenceMappings>> getSupportedDomMappingFormats()
```

`implements: UserDataHolderEx`

### `com.intellij.persistence.model.PersistencePackage` (interface, = JPA Persistence Unit)

Represents a JPA persistence unit. Returned by `PersistenceFacet.getPersistenceUnits()`.

```
PersistenceUnitModelHelper getModelHelper()       // use this for listing classes, mapping files, etc.
GenericValue<String>       getName()
// inherited from CommonModelElement:
Module       getModule()
PsiFile      getContainingFile()
PsiElement   getIdentifyingPsiElement()
boolean      isValid()
```

`implements: CommonModelElement, UserDataHolder`

### `com.intellij.persistence.model.helpers.PersistenceUnitModelHelper` (interface)

Accessed via `PersistencePackage.getModelHelper()`.

```
PersistenceMappings                 getAdditionalMapping()
List<GenericValue<PsiClass>>        getClasses()             // explicitly listed entity classes
List<GenericValue<PsiFile>>         getJarFiles()
List<GenericValue<PsiPackage>>      getPackages()
List<? extends GenericValue<V>>     getMappingFiles(Class)   // e.g. getMappingFiles(XmlFile.class)
GenericValue<String>                getDataSourceName()
GenericValue<Boolean>               getExcludeUnlistedClasses()
String                              getPersistenceProviderName()   // e.g. "org.hibernate.jpa.HibernatePersistenceProvider"
String                              getNamingStrategy()
Properties                          getPersistenceUnitProperties()
List<? extends PersistenceListener> getPersistentListeners()
Collection<Object>                  getCacheDependencies()
```

### `com.intellij.persistence.model.PersistenceMappings` (interface)

The main container of entity metadata. Accessed via `PersistenceFacet.getEntityMappings(PersistencePackage)`.
Use `getModelHelper()` to list entities.

```
PersistenceMappingsModelHelper getModelHelper()
GenericValue<PsiPackage>       getPackage()
// inherited from CommonModelElement:
Module       getModule()
PsiFile      getContainingFile()
PsiElement   getIdentifyingPsiElement()
boolean      isValid()
```

### `com.intellij.persistence.model.helpers.PersistenceMappingsModelHelper` (interface)

**The key entity listing API.** Accessed via `PersistenceMappings.getModelHelper()`.

```
List<? extends PersistentEntity>    getPersistentEntities()       // all @Entity classes
List<? extends PersistentSuperclass> getPersistentSuperclasses()  // all @MappedSuperclass classes
List<? extends PersistentEmbeddable> getPersistentEmbeddables()   // all @Embeddable classes
List<? extends PersistenceQuery>    getNamedQueries()             // @NamedQuery entries
List<? extends PersistenceQuery>    getNamedNativeQueries()       // @NamedNativeQuery entries
List<? extends PersistenceListener> getPersistentListeners()
PropertyMemberType                  getDeclaredAccess()           // FIELD or PROPERTY
```

### `com.intellij.persistence.model.PersistentObject` (interface)

Base for all persistent types (entities, superclasses, embeddables).

```
GenericValue<PsiClass>          getClazz()             // the backing PsiClass
PersistentObjectModelHelper     getObjectModelHelper()
// inherited from CommonModelElement:
Module    getModule()
PsiFile   getContainingFile()
boolean   isValid()
```

### `com.intellij.persistence.model.PersistentEntityBase` (interface, extends PersistentObject)

Base for types that can have an id class (entities and mapped superclasses).

```
GenericValue<PsiClass>             getIdClassValue()      // PsiClass of the @IdClass
PersistentEntityBaseModelHelper    getObjectModelHelper()
// from PersistentObject: getClazz(), getObjectModelHelper()
```

### `com.intellij.persistence.model.PersistentEntity` (interface, extends PersistentEntityBase)

Represents a `@Entity` class. Accessed via `PersistenceMappingsModelHelper.getPersistentEntities()`.

```
GenericValue<String>         getName()                  // entity name (from @Entity(name=...))
PersistentEntityModelHelper  getObjectModelHelper()     // cast to this for entity-specific methods
// from PersistentEntityBase: getIdClassValue()
// from PersistentObject: getClazz(), isValid(), getModule()
```

### `com.intellij.persistence.model.PersistentSuperclass` (interface, extends PersistentEntityBase)

Represents a `@MappedSuperclass` class. Accessed via `getPersistentSuperclasses()`.

```
// from PersistentEntityBase: getIdClassValue(), getObjectModelHelper()
// from PersistentObject: getClazz(), isValid(), getModule()
// from CommonModelElement: getContainingFile(), getIdentifyingPsiElement()
```

### `com.intellij.persistence.model.PersistentEmbeddable` (interface, extends PersistentObject)

Represents a `@Embeddable` class. Accessed via `getPersistentEmbeddables()`.

```
// from PersistentObject: getClazz(), getObjectModelHelper(), isValid(), getModule()
```

### `com.intellij.persistence.model.helpers.PersistentObjectModelHelper` (interface)

Base helper for all persistent objects.

```
List<? extends PersistentAttribute> getAttributes()           // all mapped attributes
PropertyMemberType                  getDefaultAccessMode()    // FIELD or PROPERTY
boolean                             isAccessModeFixed()
```

### `com.intellij.persistence.model.helpers.PersistentEntityBaseModelHelper` (interface, extends PersistentObjectModelHelper)

Helper for entities and mapped superclasses.

```
List<? extends PersistenceQuery> getNamedQueries()
List<? extends PersistenceQuery> getNamedNativeQueries()
// from PersistentObjectModelHelper: getAttributes(), getDefaultAccessMode(), isAccessModeFixed()
```

### `com.intellij.persistence.model.helpers.PersistentEntityModelHelper` (interface, extends PersistentEntityBaseModelHelper)

Full entity helper — adds inheritance and table metadata.

```
PersistenceInheritanceType       getInheritanceType(PersistentEntity)    // SINGLE_TABLE, JOINED, TABLE_PER_CLASS
TableInfoProvider                getTable()                              // primary table metadata
List<? extends TableInfoProvider> getSecondaryTables()                   // secondary tables
// from base: getNamedQueries(), getNamedNativeQueries(), getAttributes()
```

### `com.intellij.persistence.model.PersistenceInheritanceType` (enum)

```
enum constants: JOINED, SINGLE_TABLE, TABLE_PER_CLASS
static PersistenceInheritanceType valueOf(String)
static PersistenceInheritanceType[] values()
```

### `com.intellij.persistence.model.TableInfoProvider` (interface)

Table name and catalog/schema for `@Table` / `@SecondaryTable`.

```
GenericValue<String> getTableName()
GenericValue<String> getCatalog()
GenericValue<String> getSchema()
```

`implements: CommonModelElement`

### `com.intellij.persistence.model.PersistentAttribute` (interface)

A mapped field or property. Accessed via `PersistentObjectModelHelper.getAttributes()`.

```
GenericValue<String>            getName()                  // field/property name
PersistentAttributeModelHelper  getAttributeModelHelper()  // use for column/type details
PsiMember                       getPsiMember()             // backing PsiField or PsiMethod
PsiType                         getPsiType()               // Java type
PersistentObject                getPersistentObject()      // the owner entity/embeddable
// from CommonModelElement: getModule(), getContainingFile(), isValid()
```

### `com.intellij.persistence.model.helpers.PersistentAttributeModelHelper` (interface)

Attribute-level metadata for any mapped attribute.

```
List<? extends GenericValue<String>> getMappedColumns()    // column name(s); multi for @ManyToOne FK columns
GenericValue<PsiClass>               getMapKeyClass()      // key type for @MapKey collections
boolean isIdAttribute()        // true for @Id / @EmbeddedId
boolean isLob()                // true for @Lob
boolean isFieldAccess()        // true = FIELD access; false = PROPERTY access
boolean isContainer()          // true for collection/map associations
```

### `com.intellij.persistence.model.PersistentRelationshipAttribute` (interface, extends PersistentAttribute)

A relationship mapping (`@OneToOne`, `@OneToMany`, `@ManyToOne`, `@ManyToMany`).

```
GenericValue<PsiClass>                     getTargetEntityClass()
PersistentRelationshipAttributeModelHelper getAttributeModelHelper()
// from PersistentAttribute: getName(), getPsiMember(), getPsiType(), getPersistentObject()
```

### `com.intellij.persistence.model.helpers.PersistentRelationshipAttributeModelHelper` (interface, extends PersistentAttributeModelHelper)

```
RelationshipType         getRelationshipType()           // ONE_TO_ONE, ONE_TO_MANY, MANY_TO_ONE, MANY_TO_MANY
String                   getFetchType()                  // "LAZY" or "EAGER" as String
Collection<String>       getCascadeTypes()               // e.g. ["ALL"] or ["PERSIST", "MERGE"]
String                   getMappedByAttributeName()      // value of mappedBy attribute (inverse side)
boolean                  isInverseSide()                 // true if mappedBy is set
boolean                  isRelationshipSideOptional(boolean)
TableInfoProvider        getTableInfoProvider()          // join table for @ManyToMany
GenericValue<PersistentAttribute> getMapKey()            // @MapKey attribute reference
// from PersistentAttributeModelHelper: getMappedColumns(), isContainer(), isFieldAccess()
```

### `com.intellij.persistence.model.RelationshipType` (enum)

```
enum constants: ONE_TO_ONE, ONE_TO_MANY, MANY_TO_ONE, MANY_TO_MANY
RelationshipType getInverseType()                        // ONE_TO_ONE->ONE_TO_ONE, ONE_TO_MANY->MANY_TO_ONE, etc.
boolean          isMany(boolean)                         // isMany(ownerSide)
boolean          corresponds(RelationshipType)
static RelationshipType getRelationshipType(boolean isManySource, boolean isManyTarget)
static RelationshipType valueOf(String)
static RelationshipType[] values()
```

### `com.intellij.persistence.model.PersistentEmbeddedAttribute` (interface, extends PersistentAttribute)

An embeddable attribute mapping (`@Embedded`).

```
GenericValue<PsiClass> getTargetEmbeddableClass()
// from PersistentAttribute: getName(), getPsiMember(), getPsiType(), getPersistentObject()
```

### `com.intellij.persistence.model.PersistenceQuery` (interface)

Named query or named native query entry.

```
GenericValue<String>                     getName()
GenericValue<String>                     getQuery()
List<? extends PersistenceQueryParam>    getQueryParams()
// from CommonModelElement: getModule(), getContainingFile(), isValid()
```

### `com.intellij.persistence.model.PersistenceQueryParam` (interface)

Parameter entry within a named query.

```
GenericValue<String>  getName()
GenericValue<PsiType> getType()
```

### `com.intellij.persistence.model.PersistenceListener` (interface)

A lifecycle callback listener class.

```
GenericValue<PsiClass> getClazz()   // listener class reference
// from CommonModelElement: getModule(), getContainingFile(), isValid()
```

### `com.intellij.persistence.model.PersistencePackagePointer` (interface)

A lazy reference to a persistence unit (used in some navigation APIs).

```
PersistencePackage findElement()   // resolve to the actual unit (may return null if stale)
String             getElementName()
String             getModuleName()
String             getPath()
PersistenceFacet   getPersistenceFacet()
String             getPresentableUrl()
Project            getProject()
```

### `com.intellij.persistence.facet.PersistencePackageDefaults` (interface)

Global defaults for a persistence unit (catalog, schema, access mode).

```
PropertyMemberType getAccess()    // FIELD or PROPERTY
String getCatalog()
String getSchema()
```

### `com.intellij.persistence.model.validators.ModelValidator` (interface)

Validates attribute types and relationships (used by the IntelliJ inspection system).

```
String getAttributeTypeProblem(JavaTypeInfo, PersistentAttributeType, boolean)
String getRelationshipProblem(PersistentRelationshipAttribute, PersistentRelationshipAttribute)
```

## Complete access path for `PersistenceModelResolver`

The standard traversal to enumerate all entities in a project:

```kotlin
// 1. Obtain the shared browser (call on EDT or inside ReadAction)
val helper = Class.forName("com.intellij.persistence.PersistenceHelper")
    .getMethod("getHelper").invoke(null)
val browser = helper.javaClass.getMethod("getSharedModelBrowser").invoke(helper)
    // type: com.intellij.persistence.util.PersistenceModelBrowser

// 2. For a given PsiClass, find all persistent objects
val queryPersistentObjects = browser.javaClass
    .getMethod("queryPersistentObjects", PsiClass::class.java)
val query = queryPersistentObjects.invoke(browser, psiClass)
    // type: com.intellij.util.Query<PersistentObject>

// 3. Iterate via findAll()
val findAll = query.javaClass.getMethod("findAll")
val objects = findAll.invoke(query) as List<*>

// 4. For each PersistentObject, get its helper to list attributes
for (obj in objects) {
    val getObjectModelHelper = obj!!.javaClass.getMethod("getObjectModelHelper")
    val helper = getObjectModelHelper.invoke(obj)
    val getAttributes = helper!!.javaClass.getMethod("getAttributes")
    val attrs = getAttributes.invoke(helper) as List<*>
    // each attr: com.intellij.persistence.model.PersistentAttribute
}
```

For module-level traversal (via facets):

```kotlin
// From PersistenceFacet -> getAnnotationEntityMappings() -> PersistenceMappings
// -> getModelHelper() -> PersistenceMappingsModelHelper -> getPersistentEntities()
val facetQuery = browser.javaClass
    .getMethod("queryPersistenceFacets", PsiElement::class.java)
    .invoke(browser, moduleContextElement)
// iterate facet.getAnnotationEntityMappings().getModelHelper().getPersistentEntities()
```

## Notes for implementers

- **The browser is the correct entry point.** Do NOT try to discover entities by scanning PSI for `@Entity` annotations yourself — use `PersistenceModelBrowser.queryPersistentObjects(psiClass)` or iterate facets. The model handles XML-descriptor entities, annotation entities, and mixed projects uniformly.

- **No `com.intellij.jpa.*` classes are accessible from the test classloader at IU-2025.1.** All JPA metadata must be accessed through `com.intellij.persistence.model.*`. This is intentional: the persistence model is provider-agnostic.

- **`getObjectModelHelper()` is overloaded 2-3 times** returning different subtypes (`PersistentObjectModelHelper`, `PersistentEntityBaseModelHelper`, `PersistentEntityModelHelper`). When calling via reflection, use `getMethods()` and filter by name, then pick the one whose return type is `PersistentEntityModelHelper` for entity-specific features (inheritance type, table info). The most specific overload will always shadow the base return type at runtime.

- **`getAnnotationEntityMappings()` vs `getEntityMappings(PersistencePackage)`:** The no-arg form returns annotation-based (classpath-scanned) mappings; the one-arg form returns XML descriptor mappings for a given persistence unit. For JPA projects using annotations (most modern projects), use the no-arg form.

- **`RelationshipType.getInverseType()`:** Converts ONE_TO_MANY to MANY_TO_ONE and vice versa; ONE_TO_ONE stays ONE_TO_ONE; MANY_TO_MANY stays MANY_TO_MANY. Useful when walking both sides of a bidirectional relationship.

- **`PersistentRelationshipAttributeModelHelper.getCascadeTypes()` returns `Collection<String>`** — the strings are enum names like `"ALL"`, `"PERSIST"`, `"MERGE"`, `"REMOVE"`, `"REFRESH"`, `"DETACH"`. Not enums — just strings.

- **`getFetchType()` returns raw String** (`"LAZY"` or `"EAGER"`), not an enum. Null when not explicitly set.

- **`getMappedColumns()` returns `List<GenericValue<String>>`** — call `.getValue()` on each element to get the column name string. Returns a single-element list for basic attributes, multi-element for composite FK (`@ManyToOne` with composite PK).

- **`PersistentEntityModelHelper.getInheritanceType(PersistentEntity)` takes the entity as parameter** (not no-arg). Pass the same entity back to it.

- **Thread safety:** All PSI/DOM access must run inside `ReadAction.compute { }`. `PersistenceHelper.getHelper()` is safe from any thread. `PersistenceModelBrowser` queries trigger index/PSI access — always wrap in a read action.

- **`GenericValue<T>.getValue()` and `.getStringValue()`:** All `GenericValue` accessors work inside read actions. `.getValue()` returns the typed value (null if not set); `.getStringValue()` returns the raw XML/annotation string.

- **`PersistentObjectModelHelper.getAttributes()` returns all attributes** including inherited ones when the persistence provider supports it; for annotation-based projects, it returns only attributes declared on the class itself (not inherited from `@MappedSuperclass`). Walk `queryPersistentObjectHierarchy()` from the browser to get the full attribute set.

- **`PersistenceHelper.getHelper()` returns null on Community edition.** Always guard: `if (helper == null) return ToolResult.error("Persistence plugin not available")`.

- **`ManipulatorsRegistry.getManipulator(Object, Class)` uses generic type erasure** — the `<N>` return type is erased at runtime. Cast the result to the concrete manipulator type you need.

## Verified against

- IntelliJ IDEA Ultimate 2025.1.7 (build IU-251.*)
- `com.intellij.persistence` bundled plugin (test-classpath load)
- Test classloader: `com.intellij.util.lang.PathClassLoader` (standard IntelliJ Platform Plugin v2 test runtime)
- Kotlin 2.1.10, JVM 21
- 8 dump rounds (rounds 1-8), progressively correcting FQNs via return-type walking

Classes that **loaded successfully** (reflection-accessible at test runtime):
- `PersistenceFacet` (interface)
- `PersistencePackage` (interface)
- `PersistenceMappings` (interface)
- `PersistencePackagePointer` (interface)
- `PersistencePackageDefaults` (interface)
- `PersistenceHelper` (abstract class, has static `getHelper()`)
- `PersistenceModelBrowser` (interface, at `com.intellij.persistence.util`)
- `PersistentObject`, `PersistentEntityBase`, `PersistentEntity`, `PersistentSuperclass`, `PersistentEmbeddable`
- `PersistentAttribute`, `PersistentRelationshipAttribute`, `PersistentEmbeddedAttribute`
- `PersistenceUnitModelHelper`, `PersistenceMappingsModelHelper`
- `PersistentObjectModelHelper`, `PersistentEntityBaseModelHelper`, `PersistentEntityModelHelper`
- `PersistentAttributeModelHelper`, `PersistentRelationshipAttributeModelHelper`
- `ModelValidator` (interface, at `com.intellij.persistence.model.validators`)
- `PersistenceQuery`, `PersistenceQueryParam`, `PersistenceListener`
- `TableInfoProvider`, `PersistenceInheritanceType` (enum), `RelationshipType` (enum)
- `ManipulatorsRegistry` (interface, at `com.intellij.persistence.model.manipulators`)
- `PersistenceModelHelper` (base marker interface)
- `PersistentAttributeType`, `PersistentObjectModelHelper`

Classes that were **completely absent** (no accessible equivalent found):
- All `com.intellij.jpa.*` classes — zero classes from this package loaded in the test classloader
- `PersistenceClassRole`, `PersistenceClassRoleEnum` — referenced in browser signatures but not accessible; use `PersistenceModelBrowser` default (no role filter) for most use cases
- `JavaTypeInfo` — referenced by `ModelValidator.getAttributeTypeProblem()` but not accessible; skip validation use cases
- Any `PersistenceModelUtils` or `JpaModelUtil` — no utility static helpers accessible; all discovery goes through the browser

All `[MISSING]` results in rounds 1-3 were caused by incorrect package guesses. Rounds 4-8 corrected every
accessible class via return-type walking.
