# Unused IntelliJ Ultimate APIs — Survey

**Date:** 2026-04-21
**Platform:** IntelliJ IDEA Ultimate 2025.1.7
**Scope:** `:agent` module — bundled-plugin audit
**Context:** After making `spring(action=bean_graph)` Ultimate-native via `SpringModelResolver`, survey the rest of the Ultimate surface to find comparable "left on the table" APIs.

## Currently-used Ultimate APIs (baseline)

The agent already leverages:
- **Spring plugin (`com.intellij.spring`):** `SpringManager`, `SpringModelSearchers`, `CommonSpringModel`, `SpringBeanPointer`, `CommonSpringBean` — via [`SpringModelResolver.kt`](../../agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/spring/SpringModelResolver.kt) (added 2026-04-21). Signatures pinned in [`2026-04-21-intellij-spring-plugin-api-signatures.md`](./2026-04-21-intellij-spring-plugin-api-signatures.md).
- **Microservices plugin (`com.intellij.modules.microservices`):** `EndpointsProvider`, `UrlResolverManager`, `OasExportUtils` — via `agent/src/main/kotlin/.../endpoints/`.
- **Maven plugin (`org.jetbrains.idea.maven`):** `MavenProjectsManager` — via build tool actions.
- **Platform core:** `JavaPsiFacade`, `AnnotatedElementsSearch`, `XDebugger`, `CompilerManager`, `RunManager`, `ProjectFileIndex`.

Declared in `agent/build.gradle.kts` as `bundledPlugins(...)`.

## Executive summary

Significant Ultimate-only surface remains untapped. This survey enumerates **18 opportunities** across JPA/Persistence, Spring Boot metadata, Database, HTTP Client, and deeper Microservices. Tier A (5 items) delivers the highest user-visible ROI — primarily by replacing current PSI-annotation scans with model-backed queries.

---

## Tier A — Ship soon (high ROI)

### A1. JPA / Persistence plugin — rich ORM metadata

**Problem:** `spring(action=jpa_entities)` uses raw `@Entity` annotation scanning via PSI. Misses:
- Relationship cardinality (`@OneToMany` fetch/cascade modes, `mappedBy` resolution)
- Inheritance strategy (`SINGLE_TABLE`, `JOINED`, `TABLE_PER_CLASS`)
- Constraint metadata (`@UniqueConstraint`, `@Index`, `@CheckConstraint`)
- Lifecycle callbacks (`@PrePersist`, `@PostLoad`, `@EntityListeners`)
- Named queries (`@NamedQuery`, `@NamedNativeQuery`)
- Secondary tables / join columns
- Converter registration (`@Convert`)

**APIs:**
- `com.intellij.persistence.JpaFacet`
- `com.intellij.persistence.model.PersistenceCommonModel`
- `com.intellij.persistence.model.PersistenceCommonEntity`
- `com.intellij.persistence.model.PersistenceColumnInfo`
- `com.intellij.persistence.model.PersistenceRelationshipInfo`
- `com.intellij.persistence.model.utils.PersistenceModelUtils`

**Target:** `spring(action=jpa_entities)` — augment with "Relationships", "Inheritance", "Constraints", "Lifecycle" sub-sections.

**Benefit:** Agent can flag N+1 risks, lazy-loading gotchas, bidirectional mapping errors.

**Complexity:** Medium. Plugin IDs to add: `com.intellij.persistence`, `com.intellij.persistence.hibernate` (optional).

---

### A2. Spring Boot metadata index — `@ConfigurationProperties` completion

**Problem:** `spring(action=boot_config_properties)` scans annotations directly. Misses:
- Auto-configuration activation (`@AutoConfiguration`, `@ConditionalOnClass` resolution)
- Spring Boot's `META-INF/spring-configuration-metadata.json` (property descriptions, default values, deprecation notices)
- `META-INF/additional-spring-configuration-metadata.json` (custom hints)
- `@NestedConfigurationProperty` traversal
- Cross-module metadata flattening

**APIs:**
- `com.intellij.spring.boot.SpringBootLibraryUtil` (classpath probe: Spring Boot version, enabled starters)
- `com.intellij.spring.boot.metadata.ApplicationMetadataIndex` (IDE's cached property index)
- `com.intellij.spring.boot.metadata.model.ConfigurationMetadata` (structured property model)
- Existing: `SpringBootApplicationConfigurationType` (run-config — already used)

**Target:** `spring(action=boot_config_properties)` — add "Metadata" section with:
- Which auto-config class activates the property
- Human-readable descriptions from metadata JSON
- Default values + deprecation notices
- Suggested `application.yml` snippets

**Benefit:** Agent can explain WHY a property exists and WHEN it's active, not just THAT it exists.

**Complexity:** Medium. Plugin: `com.intellij.spring.boot` (promote from implicit to explicit bundled).

---

### A3. Database plugin — integrate IDE-configured data sources

**Problem:** `db_query`, `db_schema`, `db_stats`, `db_explain` tools require users to manually enter connection details in agent settings. But IntelliJ Ultimate's Database tool window already manages data sources — we're duplicating state.

**APIs:**
- `com.intellij.database.psi.DbPsiFacade` (entry point)
- `com.intellij.database.dataSource.LocalDataSource` (IDE's data sources)
- `com.intellij.database.dataSource.DataSourceManager`
- `com.intellij.database.model.basic.BasicModel` → `DasNamespace` / `DasTable` / `DasColumn` (schema metadata — no extra JDBC trip)
- `com.intellij.database.util.DasUtil` (navigation helpers)
- `com.intellij.database.dialects.SqlDialectManager` (vendor-aware SQL parsing)

**Target:**
- `db_list_profiles` — auto-populate from IDE data sources first, fall back to manual profiles
- `db_schema` — introspect via `DasTable.columns`, no round-trip to the DB
- `db_query` — target either an IDE data source or a manual profile

**Benefit:** One source of truth for DB credentials; schema introspection without a live connection; SQL autocomplete by dialect.

**Complexity:** Medium. Plugin: `com.intellij.database` (optional — wrap in availability check like `MicroservicesDetector`).

---

### A4. HTTP Client plugin — `.http` scratch file generation

**Problem:** `endpoints(action=list)` returns JSON descriptions. To test an endpoint, user manually crafts a curl or pastes into Postman. IntelliJ Ultimate ships an HTTP Client (`.http` files with run gutter) — zero integration today.

**APIs:**
- `com.intellij.httpClient.http.request.psi.HttpRequest` (PSI for `.http`)
- `com.intellij.httpClient.http.request.HttpRequestFileType`
- `com.intellij.ide.scratch.ScratchRootType` (create scratch files)
- `com.intellij.httpClient.http.request.run.HttpClientRunConfigurationProducer` (optional — create run configs)

**Target:** New action `endpoints(action=export_http_scratch, filter?)` — synthesize a `.http` file from discovered endpoints with placeholder bodies, open it in the editor. User clicks the gutter "Run" to execute.

**Benefit:** Discovery → test in one click, without leaving the IDE.

**Complexity:** Trivial. Template strings + scratch-file creation ~120 lines. Plugin: `com.intellij.restClient` (or the newer `com.intellij.httpClient`).

---

### A5. Microservices plugin — deeper than endpoints

**Problem:** We use `EndpointsProvider` + `UrlResolverManager`. The Microservices plugin ships more:
- `EndpointsSearcher` — named endpoint lookup (better than iterating every provider)
- `EndpointGroupsBuilder` — group endpoints by service/module
- `MicroservicesDiagramProvider` — service-call graph export (Mermaid-ready)
- gRPC endpoints: `com.intellij.grpc.endpoints.GrpcEndpointsProvider` (if gRPC plugin installed)
- Async endpoints: `com.intellij.microservices.async.AsyncEndpointProvider` (Kafka/RabbitMQ/JMS topics)

**Target:**
- `endpoints(action=service_graph)` — directed graph of service-to-service calls, output as Mermaid
- `endpoints(action=list, framework="gRPC")` — gRPC services (currently invisible)
- `endpoints(action=list, framework="Async")` — message broker topics

**Benefit:** Agent reasons about service topology, not just individual HTTP handlers. Critical for microservice diagnostics.

**Complexity:** Medium. Plugin: already declared. gRPC/async providers activate only when those plugins are installed — degrade gracefully.

**2026-04-21 update — A5.1/A5.2 verification findings:**

gRPC: `ProtoEndpointsProvider` (`com.intellij.grpc.endpoints`) implements `EndpointsUrlTargetProvider` and is registered as a `<microservices.endpointsProvider>` EP — meaning the existing `EndpointsDiscoverer` already handles it with zero code changes. Adding `"com.intellij.grpc"` to `bundledPlugins` is the only action required. Async messaging (Kafka, RabbitMQ, JMS): no `EndpointsProvider`-based SPI exists; instead, `MQResolverManager.getAllVariants(MQType)` in `com.intellij.microservices.jvm.mq` (already on classpath) is the discovery path, requiring a dedicated `AsyncEndpointsDiscoverer` for A5.3. The `spring-messaging` plugin must also be added to `bundledPlugins` to load framework-specific resolvers (`SpringKafkaListenerMQResolver`, `SpringRabbitListenerMQResolver`, `SpringJmsListenerMQResolver`). See `docs/research/2026-04-21-intellij-async-endpoints-api-signatures.md` for verified signatures and full findings.

---

## Tier B — Nice to have

### B1. Liquibase / Flyway migration scanning
**Problem:** Agent is unaware of pending schema migrations.
**APIs:** `com.intellij.liquibase.LiquibaseSupport`, Flyway via file scan; `com.intellij.database.psi.DbPsiFacade` for schema drift check.
**Target:** New tool action `db_migrations(action=pending|applied|drift)`.
**Complexity:** Trivial.

### B2. Thymeleaf / FreeMarker template variable resolution
**Problem:** `@Controller` returns `"users/list"` — agent can't verify the template exists or list the variables it expects.
**APIs:** `com.intellij.thymeleaf.ThymeleafIndex`, `com.intellij.freemarker.psi`.
**Target:** `spring(action=template_variables, template=...)`.
**Complexity:** Medium.

### B3. Spring Data repository query derivation
**Problem:** `spring(action=repositories)` lists method signatures but doesn't resolve `findByNameAndStatusIn(...)` to an equivalent JPQL/SQL.
**APIs:** `com.intellij.spring.data.references.SpringDataMethodReferenceResolver`.
**Target:** Augment `repositories` action — show derived query per method.
**Complexity:** Medium-large (Spring Data grammar).

### B4. Kotlin-specific bean detection
**Problem:** Kotlin `object` singletons with stereotype annotations, KSP-generated beans, `@Component open class` — current resolver handles via Spring plugin API mostly, but Kotlin-specific idioms (companion objects, delegation) need Kotlin PSI awareness.
**APIs:** `org.jetbrains.kotlin.idea.base.psi.KotlinPsiHeuristics`, `org.jetbrains.kotlin.idea.stubindex`.
**Target:** `spring(action=repositories)` + `bean_graph` — handle Kotlin edges.
**Complexity:** Medium.

### B5. Docker / Docker Compose awareness
**Problem:** Agent doesn't know the deployment shape — what containers run, which ports they expose, which service depends on which.
**APIs:** `com.intellij.docker.dockerFile.DockerFileType`, `com.intellij.docker.compose.DockerComposeFileType`, `DockerServerRuntimeManager`.
**Target:** New tool `docker(action=compose_services|dockerfile_stages|running_containers)`.
**Complexity:** Large (APIs less stable).

### B6. AspectJ / AOP pointcut resolution
**Problem:** `@Aspect` classes are beans, but `@Around("execution(...)")` pointcuts are invisible to the agent — it doesn't know WHICH methods are advised.
**APIs:** `com.intellij.spring.aop.AspectJIntrospector`, pointcut AST.
**Target:** New `spring(action=aspects)` listing advised join points.
**Complexity:** Medium.

---

## Tier C — Niche (defer)

### C1. Kubernetes manifests
**APIs:** `com.intellij.kubernetes.YamlSchemaIndex`, `KubeConfigManager`.
**Target:** `kubernetes(action=deployments|services|configmaps)`.

### C2. Java Flight Recorder / Profiler
**APIs:** `com.intellij.profiler.ProfilerServiceManager`, `SamplingProfiler`.
**Target:** `debug(action=cpu_profile|allocations)`. (Wraps a large Ultimate-licensed API.)

### C3. UML diagram generation
**APIs:** `com.intellij.diagram.DiagramProvider`, `UmlGraphBuilder`.
**Target:** Export class/package diagrams as SVG for `render_artifact`.

### C4. GraphQL schema resolution
**APIs:** `com.intellij.lang.jsgraphql.schema.GraphQLSchemaProvider` (if plugin present).
**Target:** `graphql(action=list_resolvers|schema)`.

### C5. JPQL / HQL query validation
**APIs:** `com.intellij.persistence.jpql.JpqlLanguage`, `QueryValidationUtil`.
**Target:** Validate embedded queries in `spring(action=repositories)`.

### C6. Spring Security policy resolution
**APIs:** `com.intellij.spring.security.SpringSecurityCommonUtils`, `@PreAuthorize` SpEL evaluation.
**Target:** `spring(action=security_config)` — show effective role mapping per endpoint.

### C7. Message broker queue inspection (ActiveMQ / RabbitMQ / Kafka)
**APIs:** `com.intellij.microservices.async.*`, vendor plugins.
**Target:** `async(action=list_topics|consumers|producers)`.

---

## Non-opportunities (intentionally skipped)

- **Python/PyCharm APIs** — agent already uses PSI via `PythonPsiHelper`. PyCharm-exclusive APIs don't apply to the Ultimate-Java focus of this survey.
- **Remote development / Code With Me** — not agent-relevant.
- **Performance testing plugins (JUnit Perf, Gatling)** — can already invoke via `run_command`.
- **AI Assistant / JetBrains AI** — competing tool, not a substrate.

---

## Implementation order (recommended)

| # | Item | Tier | Effort | Unlocks |
|---|---|---|---|---|
| 1 | Database plugin integration | A3 | 1–2 wk | Removes manual DB profile friction immediately |
| 2 | Spring Boot metadata index | A2 | 1–2 wk | Biggest prompt-side improvement (agent understands properties) |
| 3 | JPA/Persistence plugin | A1 | 2 wk | Unblocks accurate ORM analysis for Hibernate-heavy users |
| 4 | HTTP Client `.http` scratch | A4 | 3 days | High UX win for anyone using `endpoints(list)` |
| 5 | Microservices service graph + gRPC | A5 | 2–3 wk | Multi-service codebase support |

Tier B items should wait until Tier A lands and we have user feedback on which to prioritize.

---

## Signature-pinning discipline

Every Tier A item will introduce reflection against Ultimate-only APIs. Follow the pattern established by [`SpringModelResolver`](../../agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/spring/SpringModelResolver.kt):

1. Centralize all reflection in one `*Resolver` internal object per plugin.
2. Cache the reflected `Method` handles (`@Volatile` + synchronized init).
3. Add contract tests in the corresponding `*ToolTest` that pin each signature — graceful skip if plugin absent.
4. Save the runtime-verified API surface to a `docs/research/YYYY-MM-DD-*-api-signatures.md` document alongside the implementation.

Without this discipline, plugin version upgrades silently break tools. With it, drift becomes a test failure at build time.

---

**Verified against:** IntelliJ IDEA Ultimate 2025.1.7 / IntelliJ Platform Plugin v2 / `bundledPlugins` classloader.
