# IntelliJ Ultimate Database Plugin — Reflection-Accessible API Surface

**Date:** 2026-04-21
**Source:** Runtime introspection against `com.intellij.database` bundled with IntelliJ IDEA Ultimate 2025.1.x (test-classpath load)
**Purpose:** Pin the reflection contract for `IdeDataSourceResolver` (to be written in the next dispatch).

> **Why reflection?** A3 is an augmentation, not a replacement. The `:agent` module ships as a
> single JAR against both Community and Ultimate. `compileOnly` against `com.intellij.database`
> would break Community-only classloaders at startup. Reflection keeps one JAR, at the cost of
> signature drift. This document pins the signatures so drift surfaces as a test failure, not a
> production "tool broken" message. On Community (no Database plugin), the existing JDBC-backed
> db tools continue to work unchanged; the IDE-augmentation path is simply skipped.

## Package corrections (non-obvious FQNs)

3 rounds of introspection were needed. The following table documents every FQN guess that missed,
and the actual location discovered via return-type walking.

| Guessed FQN | Actual FQN | Notes |
|---|---|---|
| `com.intellij.database.dataSource.DataSourceManager` | **`com.intellij.database.psi.DataSourceManager`** | Discovered via `DbPsiFacade.getDataSourceManager()` return type — lives in `psi` subpackage, not `dataSource` |
| `com.intellij.database.dataSource.DasDataSource` | **`com.intellij.database.model.DasDataSource`** | Lives in `model` package, not `dataSource`; also extended by `DatabaseSystem` |
| `com.intellij.database.dataSource.DataSourceRegistry` | **[not found]** | No such class; the registry abstraction is `DataSourceStorage` (project-scoped) + `DataSourceManager` (facade) |
| `com.intellij.database.dialects.SqlDialectManager` | **[not found]** | No accessible class at this path; dialect access goes through `LocalDataSourceManager.getDatabaseDialect(LocalDataSource)` |
| `com.intellij.database.dialects.DbmsType` | **[not found]** | No such class; DBMS identity is the `com.intellij.database.Dbms` value type (confirmed loaded) |
| `com.intellij.database.dialects.DbmsDialect` | **[not found]** | No accessible class at this path |
| `com.intellij.database.dataSource.RawDataSource` | **[not found as standalone]** | Interface not accessible by name; used only as parameter type in `DataSourceManager` methods — the concrete type is always `LocalDataSource` |

**Key naming insight:** The Database plugin has two parallel hierarchies:
- **`com.intellij.database.psi.*`** — PSI-backed objects visible in the Project tree (`DbPsiFacade`, `DbDataSource`, `DbElement`)
- **`com.intellij.database.model.*`** — raw data model interfaces (`DasDataSource`, `DasModel`, `DasTable`, `DasColumn`)

For `IdeDataSourceResolver`, the correct entry points are `LocalDataSourceManager.getInstance(project)` (for listing) and `DbPsiFacade.getInstance(project)` (for ID-based lookup).

## Signatures

### `com.intellij.database.psi.DbPsiFacade` (abstract class, singleton)

The **PSI-layer entry point** for the Database plugin. Obtain via `DbPsiFacade.getInstance(project)`.

```
static DbPsiFacade getInstance(Project)
List              getDataSources()               // returns List<DbDataSource> — IDE-configured data sources
DbDataSource      findDataSource(String)          // lookup by uniqueId string
DbElement         findElement(DasObject)          // resolve DasObject to its PSI DbElement
DataSourceManager getDataSourceManager(DbDataSource)
Project           getProject()
void              clearCaches()
extends: SimpleModificationTracker
```

Via reflection:
```kotlin
val facade = Class.forName("com.intellij.database.psi.DbPsiFacade")
    .getMethod("getInstance", Project::class.java).invoke(null, project)
val sources = facade.javaClass.getMethod("getDataSources").invoke(facade) as List<*>
```

### `com.intellij.database.dataSource.LocalDataSourceManager` (concrete class, project service)

The **primary listing API** for IDE-configured data sources. Use this as the main entry point.

```
static LocalDataSourceManager getInstance(Project)     // project-scoped service
List   getDataSources()                                // all LocalDataSource in this project
void   addDataSource(LocalDataSource)
void   removeDataSource(LocalDataSource)
void   renameDataSource(LocalDataSource, String)
LocalDataSource copyDataSource(String, LocalDataSource)
LocalDataSource createEmpty()
DatabaseDialectEx getDatabaseDialect(LocalDataSource)
Language getQueryLanguage(LocalDataSource)
Promise  getLoadingPromise(LocalDataSource)
boolean  isLoading(LocalDataSource)
extends: AbstractDataSourceManager
```

Via reflection:
```kotlin
val mgr = Class.forName("com.intellij.database.dataSource.LocalDataSourceManager")
    .getMethod("getInstance", Project::class.java).invoke(null, project)
val list = mgr.javaClass.getMethod("getDataSources").invoke(mgr) as List<*>
// Each element is a LocalDataSource
```

### `com.intellij.database.psi.DataSourceManager` (abstract class)

Facade for a single manager (returned by `DbPsiFacade.getDataSourceManager(DbDataSource)`). Provides
cross-manager operations when multiple manager types exist (e.g., project-local vs. global).

```
List   getDataSources()
void   addDataSource(RawDataSource)
void   removeDataSource(RawDataSource)
void   renameDataSource(RawDataSource, String)
boolean containsDataSource(RawDataSource)
boolean isLoading(RawDataSource)
RawDataSource copyDataSource(String, RawDataSource)
RawDataSource createEmpty()
static DataSourceManager byDataSource(Project, RawDataSource)
static DataSourceManager byDataSource(Project, Class)
static List              getManagers(Project)               // all registered managers
AnAction getCreateDataSourceAction(Consumer)
```

### `com.intellij.database.dataSource.DataSourceStorage` (abstract class, persistence layer)

Handles persistence for project-scoped data sources. Lower-level than `LocalDataSourceManager`.
Prefer `LocalDataSourceManager` for listing; use `DataSourceStorage` only for project-file access.

```
static DataSourceStorage getProjectStorage(Project)         // primary factory
static DataSourceStorage getStorage()                        // global storage
static DataSourceStorage getStorage(Project)                 // project storage
List   getDataSources()
List   getOwnDataSources()
int    getCount()
LocalDataSource getDataSourceById(String)                    // lookup by ID
void   addDataSource(LocalDataSource)
void   removeDataSource(LocalDataSource)
void   updateDataSource(LocalDataSource)
boolean isLoading(LocalDataSource)
Promise getLoadingPromise(LocalDataSource)
long   getModificationCount()
void   dispose()
void   doWhenInitialized(Runnable)
static Path getStoragePath(Project)
static String getStorageDir(Project)
implements: Disposable
extends: SimpleModificationTracker
```

### `com.intellij.database.dataSource.LocalDataSource` (concrete class)

The **primary model** for an IDE-configured data source. Returned by `LocalDataSourceManager.getDataSources()`.
Contains all connection parameters needed by `IdeDataSourceResolver`.

```
String    getUrl()                              // JDBC URL e.g. "jdbc:postgresql://localhost:5432/mydb"
String    getUsername()                         // connection username (may require: getUsername(DatabaseConnectionPoint))
String    getDriverClass()                      // JDBC driver class e.g. "org.postgresql.Driver"
Dbms      getDbms()                             // database type enum (Dbms.isPostgres(), .isMysql(), etc.)
String    getName()                             // display name set in the IDE
String    getUniqueId()                         // stable ID for findDataSource(String)
String    getGroupName()                        // folder/group in the DB tool window
DasModel  getModel()                            // schema model (tables, columns, etc.)

DatabaseDriver getDatabaseDriver()              // driver metadata
Storage   getPasswordStorage()                  // where password is stored (see credential notes below)
static Storage getPasswordStorage(DatabaseConnectionPoint)

String    getDriverRef()                        // driver ID reference string
String    getDefaultDialect()
String    getComment()

boolean   isGlobal()                            // true = stored in global settings, not per-project
boolean   isTemporary()
boolean   isReadOnly()
boolean   isAutoCommit()
boolean   isAutoSynchronize()
boolean   isImported()

// SSH / SSL tunneling
DataSourceSshTunnelConfiguration getSshConfiguration()
DataSourceSslConfiguration       getSslCfg()

// Schema filtering
DataSourceSchemaMapping  getSchemaMapping()
SchemaControl            getSchemaControl()
TreePattern              getIntrospectionScope()
Level                    getIntrospectionLevel()

static LocalDataSource create(String name, String url, String username, String driverClass)
static LocalDataSource temporary()
LocalDataSource copy(boolean)
void clearModel()
void clearIntrospectionCache()

implements: DatabaseConnectionConfig, DatabaseConnectivityConfiguration, Iconable
extends: AbstractDataSource
```

### `com.intellij.database.dataSource.AbstractDataSource` (abstract class)

Base for `LocalDataSource`. Provides the `DasDataSource`-compatible view of a data source.

```
String   getName()
String   getComment()
String   getGroupName()
String   getUniqueId()
DasModel getModel()
boolean  isGlobal()
void     setName(String)
void     setComment(String)
void     setGroupName(String)
void     modify(Runnable)
void     registered()
void     unregistered()
implements: RawDataSource, DatabaseSystem
extends: SimpleModificationTracker
```

### `com.intellij.database.model.DasDataSource` (interface)

The read-only model interface for a data source. `LocalDataSource` → `AbstractDataSource` → implements `DatabaseSystem` → extends `DasDataSource` (via `DatabaseSystem`).

```
String             getName()
String             getComment()
String             getUniqueId()
Dbms               getDbms()
DasModel           getModel()
Version            getVersion()
NameVersion        getDatabaseVersion()
RawConnectionConfig getConnectionConfig()
implements: Iconable
```

### `com.intellij.database.psi.DbDataSource` (interface, PSI layer)

The PSI-wrapped view of a data source. Returned by `DbPsiFacade.findDataSource(String)` and `DbPsiFacade.getDataSources()`.

```
DasModel              getModel()
RawDataSource         getDelegate()             // unwrap to LocalDataSource
RawDataSource         getDelegateDataSource()
DatabaseDialect       getDatabaseDialect()
Language              getQueryLanguage()
ModelNameIndex        getNameIndex()
Promise               getLoadingPromise()
ModificationTracker   getModificationTracker()
boolean               isLoading()
DbElement             findElement(DasObject)
DbElement             findElement(ObjectPath)
JBIterable            findObjects(ObjectPath)
Function              mapper()
implements: DbElement, DasDataSource
```

### `com.intellij.database.psi.DbElement` (interface)

Base for all PSI-wrapped database objects.

```
DbDataSource  getDataSource()
Object        getDelegate()
String        getComment()
String        getTypeName()
JBIterable    getDasChildren(ObjectKind)
DasObject     getDasParent()
CharSequence  getDocumentation(boolean)
implements: PsiObject, PsiFileSystemItem, DasPsiSymbol, DasSymbolObject
```

### `com.intellij.database.model.DasModel` (interface)

Schema model containing tables, namespaces, and traversal APIs.

```
boolean       contains(DasObject)
JBIterable    getModelRoots()                    // top-level namespaces (catalogs/schemas)
DasNamespace  getCurrentRootNamespace()
MetaModel     getMetaModel()
JBTreeTraverser traverser()
implements: CasingProvider
```

### `com.intellij.database.model.DasObject` (interface)

Base for all schema model objects (tables, columns, indices, etc.).

```
String     getName()       // via DasNamed
ObjectKind getKind()       // via DasNamed
boolean    isQuoted()      // via DasNamed
String     getComment()
DasObject  getDasParent()
JBIterable getDasChildren(ObjectKind)
JBIterable getDbChildren(Class, ObjectKind)
implements: DasNamed
```

### `com.intellij.database.model.DasNamespace` (interface, extends DasObject)

A schema or catalog namespace.
```
// inherits all DasObject methods
implements: DasObject
```

### `com.intellij.database.model.DasTable` (interface, extends DasSchemaChild)

A table or view in the schema.

```
Set     getColumnAttrs(DasColumn)    // attribute flags on a column for this table
boolean isSystem()
boolean isTemporary()
implements: DasSchemaChild           // → DasObject
```

### `com.intellij.database.model.DasColumn` (interface)

A column in a table.

```
String   getName()           // via DasNamed
String   getTableName()      // parent table name
DasType  getDasType()        // type info
DataType getDataType()       // JDBC-style type
String   getDefault()        // default value expression
boolean  isNotNull()
boolean  isQuoted()
implements: DasTableChild, DasTypedObject, DasPositioned
```

### `com.intellij.database.model.DasIndex` (interface)

An index on a table.

```
MultiRef  getColumnsRef()          // column references
boolean   isUnique()
boolean   isFunctionBased()
Sorting   getColumnSorting(String)
implements: DasTableChild
```

### `com.intellij.database.model.DasConstraint` (interface)

A constraint (primary key, foreign key, check).

```
MultiRef  getColumnsRef()
implements: DasTableChild
```

### `com.intellij.database.util.DasUtil` (concrete class, static utility)

The **primary schema traversal utility**. Provides typed iteration over schema objects.

```
static JBIterable  getTables(DasDataSource)              // all tables across all schemas
static JBIterable  getTables(DatabaseSystem)
static JBIterable  getColumns(DasObject)                 // columns of a table
static JBIterable  getIndices(DasTable)
static JBIterable  getIndices(DasObject)
static JBIterable  getForeignKeys(DasTable)
static JBIterable  getTableKeys(DasTable)
static JBIterable  getTableKeys(DasObject)
static JBIterable  getSchemas(DasDataSource)             // schema/namespace list
static JBIterable  getSchemaElements(DasDataSource, Class)  // typed schema objects
static JBIterable  getParameters(DasRoutine)

static ObjectKind  getKind(DasObject)
static String      getName(DasObject)
static String      getSchema(DasObject)
static String      getCatalog(DasObject)
static DasNamespace getNamespace(DasObject)
static DasObject   getSchemaObject(DasObject)
static DasObject   getCatalogObject(DasObject)
static DasTabelKey getPrimaryKey(DasTable)
static DasTabelKey getPrimaryKey(DasObject)

static DasObject   findChild(DasObject, Class, ObjectKind, String)
static Object      getParentOfClass(DasObject, Class, boolean)
static DasObject   getParentOfKind(DasObject, ObjectKind, boolean)
static JBIterable  dasParents(DasObject)
static JBTreeTraverser dasTraverser()

static boolean     isPrimary(DasColumn)
static boolean     isForeign(DasColumn)
static boolean     isAuto(DasColumn)
static boolean     isAutoGenerated(DasColumn)
static boolean     isComputed(DasColumn)
static boolean     isIndexColumn(DasColumn)
static boolean     isAncestor(DasObject, DasObject, boolean)
static boolean     isNoName(String)

static MultiRef    asRef(Iterable)
static MultiRef    emptyMultiRef()
static DasModel    emptyModel()
```

### `com.intellij.database.Dbms` (concrete class, value type)

Identifies the DBMS for a data source. Returned by `LocalDataSource.getDbms()`.

```
String  getName()          // internal name e.g. "POSTGRES"
String  getDisplayName()   // user-facing name e.g. "PostgreSQL"
Icon    getIcon()

// Type predicates (all return boolean)
isPostgres(), isMysql(), isMicrosoft(), isOracle(), isSqlite(), isH2()
isDb2(), isMongo(), isRedshift(), isSnowflake(), isClickHouse(), isHive()
isBigQuery(), isCassandra(), isCouchbase(), isDerby(), isGreenplum()
isHsqldb(), isExasol(), isVertica(), isSybase(), isTransactSql()

static Dbms byName(String)
static Dbms fromString(String)
static Dbms forConnection(RawConnectionConfig)
static Collection allValues()
static boolean isPredefined(Dbms)

implements: Comparable
```

### `com.intellij.database.dialects.DatabaseDialect` (interface)

SQL dialect for a data source. Retrieved via `DbDataSource.getDatabaseDialect()` or
`LocalDataSourceManager.getDatabaseDialect(LocalDataSource)`.

```
Dbms    getDbms()
String  getDisplayName()
boolean supportsCommonTableExpression()
boolean supportsInsertInto()
boolean supportsTableInfo()
boolean triggersIntrospection(PsiElement)
boolean canUnquoteAlias(String, ObjectKind, boolean)
boolean similarTo(DatabaseDialect)
String  getBinaryLiteralString(byte[])
int     getJavaTypeForNativeType(String)
```

### `com.intellij.database.dataSource.DatabaseDriver` (interface)

Driver configuration for a data source. Returned by `LocalDataSource.getDatabaseDriver()`.

```
String  getName()
String  getId()
String  getDriverClass()          // JDBC driver class name
Dbms    getForcedDbms()           // overrides the autodetected DBMS
String  getSampleUrl()
String  getSqlDialect()
List    getClasspathElements()
List    getArtifacts()
List    getAdditionalClasspathElements()
boolean hasDriverFiles()
boolean isPredefined()
boolean matchesUrl(String)
Map     getDriverProperties()
Map     getVmEnv()
String  getVmOptions()
implements: Iconable, DatabaseConnectivityConfiguration, UserDataHolder
```

### `com.intellij.database.dataSource.DatabaseConnectionPoint` (interface)

Common interface for objects with connection configuration. `LocalDataSource` implements this.

```
String              getUrl()                    // via RawConnectionConfig
String              getAuthProviderId()
Dbms                getDbms()
DatabaseDriver      getDatabaseDriver()
LocalDataSource     getDataSource()
int                 getTxIsolation()
boolean             isAutoCommit()
boolean             isReadOnly()
Properties          getConnectionProperties()
Map                 getAdditionalProperties()
String              getAdditionalProperty(String)
Set                 getAdditionalPropertiesNames()
String              getInitScript()
SchemaControl       getSchemaControl()
DatabaseConnectionConfig getMutableConfig()
implements: RawConnectionConfig
```

### `com.intellij.database.model.ObjectKind` (concrete class)

Identifies the kind of a `DasObject` (TABLE, VIEW, SCHEMA, COLUMN, INDEX, etc.).

```
String  name()               // kind name e.g. "TABLE", "VIEW", "SCHEMA"
String  code()               // short code
String  getPresentableName()
String  getPluralPresentableName()
int     getOrder()
static ObjectKind    getKind(String)
static Collection    getRegisteredKinds()
static JBIterable    getDatabaseKinds()
static boolean       isDatabaseKind(ObjectKind)
implements: Comparable
```

### `com.intellij.database.remote.jdbc.RemoteConnection` (interface)

Low-level connection API (JDBC-like). Not needed for `IdeDataSourceResolver` (which lists sources,
not executes queries), but useful for future query execution via the IDE engine.

```
RemoteStatement          createStatement()
RemotePreparedStatement  prepareStatement(String)
RemoteCallableStatement  prepareCall(String)
void commit(); void rollback(); void close()
boolean isAutoCommit(); boolean isReadOnly(); boolean isClosed()
String  getCatalog(); String getSchema()
String  getDetectedDbmsName(); String getDetectedDbmsVersion()
String  getDriverVersion()
RemoteDatabaseMetaData   getMetaData()
implements: RemoteCastable
```

### `com.intellij.database.run.ConsoleRunConfiguration` (concrete class)

Run configuration backed by a data source (used for script execution in the IDE console).

```
static ConsoleRunConfiguration newConfiguration(Project)
void setOptionsFromDataSource(LocalDataSource)
RunProfileState getState(Executor, ExecutionEnvironment)
SettingsEditor  getConfigurationEditor()
implements: RunProfile
extends: RunConfigurationBase
```

## Complete access path for `IdeDataSourceResolver`

### Primary path: listing all IDE-configured data sources

```kotlin
// 1. Check availability (guard for Community edition)
val mgrClass = try {
    Class.forName("com.intellij.database.dataSource.LocalDataSourceManager")
} catch (e: ClassNotFoundException) {
    return emptyList()  // Database plugin not available
}

// 2. Get project manager
val mgr = mgrClass.getMethod("getInstance", Project::class.java).invoke(null, project)
    ?: return emptyList()

// 3. List all data sources
val sources = mgrClass.getMethod("getDataSources").invoke(mgr) as List<*>

// 4. For each source, extract connection parameters
for (src in sources) {
    val cls = src!!.javaClass
    val name = cls.getMethod("getName").invoke(src) as String
    val url = cls.getMethod("getUrl").invoke(src) as? String ?: continue
    val driverClass = cls.getMethod("getDriverClass").invoke(src) as? String
    val dbms = cls.getMethod("getDbms").invoke(src)
    val dbmsName = dbms?.javaClass?.getMethod("getName")?.invoke(dbms) as? String
    // username: getUsername() is a no-arg method on LocalDataSource (not the static one)
    val username = cls.methods.firstOrNull {
        it.name == "getUsername" && it.parameterCount == 0
    }?.invoke(src) as? String
}
```

### Secondary path: PSI lookup by ID

```kotlin
val facadeClass = Class.forName("com.intellij.database.psi.DbPsiFacade")
val facade = facadeClass.getMethod("getInstance", Project::class.java).invoke(null, project)
val dbDataSource = facadeClass.getMethod("findDataSource", String::class.java)
    .invoke(facade, uniqueId)
```

### Schema traversal via DasUtil

```kotlin
val dasUtilClass = Class.forName("com.intellij.database.util.DasUtil")
val model = localDataSourceInstance.javaClass.getMethod("getModel").invoke(localDataSourceInstance)
// model: DasModel — may be null if not yet introspected

// Get all tables
val tables = dasUtilClass.getMethod("getTables",
    Class.forName("com.intellij.database.model.DasDataSource"))
    .invoke(null, localDataSourceInstance) as com.intellij.openapi.util.JBIterable<*>

for (table in tables) {
    val tableName = table!!.javaClass.getMethod("getName").invoke(table) as String
    val columns = dasUtilClass.getMethod("getColumns", 
        Class.forName("com.intellij.database.model.DasObject"))
        .invoke(null, table) as com.intellij.openapi.util.JBIterable<*>
}
```

## Notes for implementers (A3 dispatch 2)

### Entry point recommendation

Use `LocalDataSourceManager.getInstance(project)` as the single entry point for listing. It is:
- A proper project service (safe to call from any thread)
- Returns all data sources registered in the IDE's Database tool window
- Returns `List<LocalDataSource>` which has the complete connection configuration

Do NOT use `DbPsiFacade.getDataSources()` for listing — it returns `List<DbDataSource>` which are
PSI wrappers and require PSI context. Use `DbPsiFacade.findDataSource(uniqueId)` only for ID-based
lookup when you already have the ID.

### Extracting name, JDBC URL, username, driver class

All four are directly on `LocalDataSource`:
- `getName()` — display name (e.g., "Local PostgreSQL")
- `getUrl()` — full JDBC URL (e.g., `jdbc:postgresql://localhost:5432/mydb`)
- `getDriverClass()` — driver class (e.g., `org.postgresql.Driver`)
- `getDbms()` → `.getName()` — DBMS type string (e.g., `"POSTGRES"`)
- `getUsername()` (no-arg) — username string; NOTE: there is also a static `getUsername(DatabaseConnectionPoint)` — when calling via reflection, filter by `parameterCount == 0` to get the instance method

### Credential handling — passwords are NOT exposed via reflection API

The `getPasswordStorage()` method returns a `Storage` enum (where credentials are stored: `KEYCHAIN`, `KEEPASS`, `IN_MEMORY`, etc.) — not the actual password value. There is no `getPassword()` method on `LocalDataSource` or any accessible interface.

**Credential handling strategy for `IdeDataSourceResolver`:**
- The resolver should enumerate connection parameters (URL, username, driver class, DBMS) without the password.
- The agent tool (`DbListProfilesTool` augmentation) should surface these as "IDE-configured" profiles.
- Password retrieval is handled separately: when the agent needs to actually connect, it should use the `PasswordSafe` API (`com.intellij.credentialStore.CredentialAttributes`) — but this requires a separate reflection chain and is not needed for profile listing.
- Alternatively, for schema introspection: `LocalDataSource.getModel()` returns the already-loaded `DasModel` (populated when the IDE has already connected to the data source). If the model is non-null, schema traversal via `DasUtil` works without needing the password.

### Schema introspection

`DasUtil.getTables(DasDataSource)` is the correct entry point for table listing. Key notes:
- `LocalDataSource` implements `DatabaseSystem` which extends `DasDataSource` — pass it directly
- The model may be null if the IDE has never connected to the data source
- `DasUtil.getColumns(DasObject)` takes a table and returns `JBIterable<DasColumn>`
- `DasUtil.getSchemas(DasDataSource)` returns the schema/namespace list

### Thread safety / read-action requirements

- `LocalDataSourceManager.getInstance(project)` and `getDataSources()` — safe from any thread; these are project services with their own thread safety
- `DbPsiFacade.getInstance(project)` — safe from any thread
- `LocalDataSource.getUrl()`, `getName()`, etc. — safe from any thread; data source config is not PSI
- `DasUtil` schema traversal (`getTables`, `getColumns`) — safe from any thread; the model is a data model, not PSI. No `ReadAction` required
- PSI-layer access (`DbElement`, `findElement`) — requires a read action if called from a non-EDT thread

### Community edition guard

`com.intellij.database.dataSource.LocalDataSourceManager` will throw `ClassNotFoundException` on
Community Edition (where the Database plugin is not bundled). Always guard:
```kotlin
val available = try {
    Class.forName("com.intellij.database.dataSource.LocalDataSourceManager")
    true
} catch (e: ClassNotFoundException) { false }
if (!available) return ToolResult.error("Database plugin not available (IntelliJ Community)")
```

### `getDataSources()` return type detail

Confirmed via introspection:
- `DbPsiFacade.getDataSources()` → `java.util.List` (erased; elements are `DbDataSource`)
- `LocalDataSourceManager.getDataSources()` → `java.util.List` (erased; elements are `LocalDataSource`)
- `DataSourceStorage.getDataSources()` → `java.util.List` (erased; elements are `LocalDataSource`)

All three use raw `List` return types in the bytecode (type erasure). Cast elements individually.

## Verified against

- IntelliJ IDEA Ultimate 2025.1.x (build IU-251.*)
- `com.intellij.database` bundled plugin (test-classpath load)
- Test classloader: `com.intellij.util.lang.PathClassLoader` (standard IntelliJ Platform Plugin v2 test runtime)
- Kotlin 2.1.10, JVM 21
- 3 dump rounds, progressively correcting FQNs via return-type walking

Classes that **loaded successfully** (reflection-accessible at test runtime):
- `DbPsiFacade` (abstract class, has static `getInstance(Project)`)
- `DbDataSource` (interface, PSI layer)
- `DbElement` (interface, PSI layer)
- `DataSourceManager` (abstract class, at `com.intellij.database.psi`)
- `LocalDataSourceManager` (concrete class, has static `getInstance(Project)`)
- `DataSourceStorage` (abstract class, has static `getProjectStorage(Project)`)
- `LocalDataSource` (concrete class, main config object)
- `AbstractDataSource` (abstract class, base for LocalDataSource)
- `DatabaseDriver` (interface)
- `DatabaseConnectionPoint` (interface)
- `DasDataSource` (interface, at `com.intellij.database.model`)
- `DasModel`, `DasObject`, `DasNamed`, `DasNamespace`
- `DasTable`, `DasColumn`, `DasIndex`, `DasConstraint`
- `DasSchemaChild`, `DasTableChild`, `DasTypedObject`
- `DatabaseSystem` (interface)
- `DasUtil` (static utility class)
- `DataSourceUtil` (static utility class)
- `Dbms` (value type)
- `DatabaseDialect` (interface, at `com.intellij.database.dialects`)
- `ObjectKind` (concrete class)
- `RemoteConnection` (interface)
- `ConsoleRunConfiguration` (concrete class)

Classes that were **completely absent** (no accessible equivalent found):
- `com.intellij.database.dataSource.DataSourceManager` — the real one is at `com.intellij.database.psi.DataSourceManager`
- `com.intellij.database.dataSource.DasDataSource` — the real one is at `com.intellij.database.model.DasDataSource`
- `com.intellij.database.dataSource.DataSourceRegistry` — no such class; use `LocalDataSourceManager` or `DataSourceStorage`
- `com.intellij.database.dialects.SqlDialectManager` — no such class
- `com.intellij.database.dialects.DbmsType` — no such class; use `com.intellij.database.Dbms`
- `com.intellij.database.dialects.DbmsDialect` — no such class
- `com.intellij.database.dataSource.RawDataSource` — interface not accessible by name (only as param type)
- No `getPassword()` or credential-retrieval method anywhere in the accessible API surface
