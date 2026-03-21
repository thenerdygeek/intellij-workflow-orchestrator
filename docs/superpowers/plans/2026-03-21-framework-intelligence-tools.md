# Framework Intelligence Tools — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add 10 IntelliJ-native framework intelligence tools using Maven, Spring, and PSI APIs — enabling the agent to understand dependency trees, resolved properties, Spring profiles, data repositories, build plugins, security config, and scheduled tasks.

**Architecture:** All tools use IntelliJ's indexed APIs via `ReadAction.nonBlocking { }.inSmartMode(project)` for thread safety. Maven tools use reflection (Maven plugin is optional). Spring tools use PSI annotation scanning + SpringManager reflection. Each tool follows the existing pattern: extend `AgentTool`, return `ToolResult` with LLM-optimized summary.

**Tech Stack:** Kotlin, IntelliJ Platform APIs (MavenProjectsManager via reflection, SpringManager via reflection, PsiSearchHelper, AnnotatedElementsSearch, JavaPsiFacade), kotlinx.serialization

---

## File Structure

### New Files (10 tools + tests)
| File | Responsibility |
|------|---------------|
| `agent/.../tools/framework/MavenDependenciesTool.kt` | Full dependency tree with scopes |
| `agent/.../tools/framework/MavenPropertiesTool.kt` | Resolved Maven properties from effective POM |
| `agent/.../tools/framework/MavenPluginsTool.kt` | Build plugins with configurations |
| `agent/.../tools/framework/MavenProfilesTool.kt` | Maven profiles (active + available) |
| `agent/.../tools/framework/SpringVersionTool.kt` | Spring/Boot versions + key dependency versions |
| `agent/.../tools/psi/SpringProfilesTool.kt` | Active Spring profiles from @Profile annotations + config |
| `agent/.../tools/psi/SpringRepositoriesTool.kt` | Spring Data @Repository interfaces with query methods |
| `agent/.../tools/psi/SpringSecurityTool.kt` | Security config — auth methods, URL patterns, roles |
| `agent/.../tools/psi/SpringScheduledTool.kt` | @Scheduled tasks with cron expressions |
| `agent/.../tools/psi/SpringEventListenersTool.kt` | @EventListener methods and event types |

### Modified Files
| File | Change |
|------|--------|
| `agent/.../AgentService.kt` | Register 10 new tools |
| `agent/.../tools/ToolCategoryRegistry.kt` | Add to framework category (63 total) |
| `agent/.../tools/DynamicToolSelector.kt` | Add keyword triggers |
| `agent/CLAUDE.md` | Update tool count and framework section |

---

## Task 1: Maven Dependencies — Full Tree with Scopes

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/MavenDependenciesTool.kt`

- [ ] **Step 1: Implement MavenDependenciesTool**

```kotlin
class MavenDependenciesTool : AgentTool {
    override val name = "maven_dependencies"
    override val description = "List Maven dependencies with groupId:artifactId:version and scope (compile/test/runtime/provided). Optionally filter by scope or search by artifact name."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "module" to ParameterProperty(type = "string", description = "Module name. Defaults to root module."),
            "scope" to ParameterProperty(type = "string", description = "Filter by scope: compile, test, runtime, provided. Omit for all."),
            "search" to ParameterProperty(type = "string", description = "Search in groupId or artifactId (case-insensitive).")
        ),
        required = emptyList()
    )
    allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.ORCHESTRATOR, WorkerType.TOOLER)
}
```

Implementation: Use `MavenProjectsManager` via reflection (same pattern as `ProjectModulesTool.tryGetMavenInfo()`):
```kotlin
// Get Maven project
val mavenProject = getMavenProject(project, moduleName)
// Get dependencies via reflection
val deps = mavenProject.javaClass.getMethod("getDependencies").invoke(mavenProject) as List<Any>
// For each: extract groupId, artifactId, version, scope
// Group by scope, format as:
//   compile:
//     org.springframework.boot:spring-boot-starter-web:3.2.0
//     org.springframework:spring-context:6.1.0
//   test:
//     org.junit.jupiter:junit-jupiter:5.10.0
```

- [ ] **Step 2: Verify and commit**

```bash
./gradlew :agent:compileKotlin --no-build-cache
git commit -m "feat(agent): maven_dependencies tool — full dependency list with scopes and search"
```

---

## Task 2: Maven Properties — Resolved from Effective POM

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/MavenPropertiesTool.kt`

- [ ] **Step 1: Implement MavenPropertiesTool**

```kotlin
class MavenPropertiesTool : AgentTool {
    name = "maven_properties"
    description = "Get resolved Maven properties from the effective POM. Shows ${property.name} → resolved value. Includes inherited properties from parent POMs."
    parameters: module (optional), search (optional — filter by property name)
}
```

Implementation via reflection:
```kotlin
// MavenProject.getProperties() — returns java.util.Properties with all resolved properties
val properties = mavenProject.javaClass.getMethod("getProperties").invoke(mavenProject) as java.util.Properties
// Format as:
//   project.version = 1.0.0-SNAPSHOT
//   java.version = 17
//   spring-boot.version = 3.2.0
//   jdbc.url = jdbc:postgresql://localhost:5432/mydb
```

- [ ] **Step 2: Verify and commit**

---

## Task 3: Maven Plugins — Build Pipeline

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/MavenPluginsTool.kt`

- [ ] **Step 1: Implement MavenPluginsTool**

```kotlin
class MavenPluginsTool : AgentTool {
    name = "maven_plugins"
    description = "List Maven build plugins with their configurations. Shows plugin groupId:artifactId:version, executions, and key configuration values."
}
```

Implementation via reflection:
```kotlin
// MavenProject.getDeclaredPlugins() or getPlugins()
val plugins = mavenProject.javaClass.getMethod("getDeclaredPlugins").invoke(mavenProject) as List<Any>
// For each plugin: groupId, artifactId, version
// Also try to get executions and configuration
// Format as:
//   maven-compiler-plugin:3.11.0
//     source: 17, target: 17
//   maven-surefire-plugin:3.1.2
//   spring-boot-maven-plugin:3.2.0
//     mainClass: com.example.Application
//   docker-maven-plugin:0.43.4
```

- [ ] **Step 2: Verify and commit**

---

## Task 4: Maven Profiles

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/MavenProfilesTool.kt`

- [ ] **Step 1: Implement MavenProfilesTool**

```kotlin
class MavenProfilesTool : AgentTool {
    name = "maven_profiles"
    description = "List Maven profiles — which are active, which are available. Shows profile IDs and activation conditions."
}
```

Implementation:
```kotlin
// MavenProjectsManager.getExplicitProfiles() — active profiles
// MavenProjectsManager.getAvailableProfiles() — all defined profiles
// Format as:
//   Active profiles: dev, local
//   Available profiles:
//     dev — activatedBy: -Pdev
//     test — activatedBy: -Ptest
//     prod — activatedBy: -Pprod
//     docker — activatedBy: property docker.enabled
```

- [ ] **Step 2: Verify and commit**

---

## Task 5: Spring/Dependency Version Info

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/SpringVersionTool.kt`

- [ ] **Step 1: Implement SpringVersionTool**

```kotlin
class SpringVersionTool : AgentTool {
    name = "spring_version_info"
    description = "Get Spring Boot/Framework versions and key dependency versions (JUnit, Hibernate, Jackson, etc.) detected from the project."
}
```

Implementation: Scan Maven dependencies for known artifacts and extract versions:
```kotlin
// Extract from Maven deps:
//   Spring Boot: 3.2.0 (from spring-boot-starter-*)
//   Spring Framework: 6.1.0 (from spring-context/spring-web)
//   JUnit: 5.10.0 (from junit-jupiter)
//   Hibernate: 6.3.1 (from hibernate-core)
//   Jackson: 2.16.0 (from jackson-databind)
//   Kotlin: 2.1.10 (from kotlin-stdlib)
//   Java: 17 (from maven-compiler-plugin source)
```

- [ ] **Step 2: Verify and commit**

---

## Task 6: Spring Profiles — @Profile Annotations + Config

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/SpringProfilesTool.kt`

- [ ] **Step 1: Implement SpringProfilesTool**

```kotlin
class SpringProfilesTool : AgentTool {
    name = "spring_profiles"
    description = "List Spring profiles used in the project — from @Profile annotations, application-{profile}.properties files, and spring.profiles.active config."
}
```

Implementation using PSI:
```kotlin
ReadAction.nonBlocking<String> {
    // 1. Scan for @Profile annotations via PSI
    val profileAnnotation = JavaPsiFacade.getInstance(project)
        .findClass("org.springframework.context.annotation.Profile", GlobalSearchScope.allScope(project))
    if (profileAnnotation != null) {
        AnnotatedElementsSearch.searchPsiClasses(profileAnnotation, scope).findAll()
        // Extract profile names from @Profile("dev") or @Profile({"dev", "test"})
    }

    // 2. Find application-*.properties/yml files
    // application-dev.properties → "dev" profile
    // application-test.yml → "test" profile

    // 3. Check spring.profiles.active in application.properties

    // Format as:
    //   Profiles found:
    //     dev — used by: SecurityConfig, DataSourceConfig; config: application-dev.properties
    //     test — used by: TestConfig; config: application-test.yml
    //     prod — used by: SecurityConfig; config: application-prod.properties
    //   Active (from config): dev
}.inSmartMode(project).executeSynchronously()
```

- [ ] **Step 2: Verify and commit**

---

## Task 7: Spring Data Repositories

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/SpringRepositoriesTool.kt`

- [ ] **Step 1: Implement SpringRepositoriesTool**

```kotlin
class SpringRepositoriesTool : AgentTool {
    name = "spring_repositories"
    description = "List Spring Data repositories — interface names, entity types, custom query methods. Shows @Repository classes that extend JpaRepository/CrudRepository."
}
```

Implementation using PSI:
```kotlin
ReadAction.nonBlocking<String> {
    // Find JpaRepository/CrudRepository base classes
    val jpaRepo = facade.findClass("org.springframework.data.jpa.repository.JpaRepository", scope)
    val crudRepo = facade.findClass("org.springframework.data.repository.CrudRepository", scope)

    // Find all subinterfaces/subclasses
    ClassInheritorsSearch.search(jpaRepo, scope, true).findAll()

    // For each repository:
    //   - Interface name
    //   - Entity type + ID type (from generics)
    //   - Custom query methods (methods beyond CrudRepository defaults)
    //   - @Query annotations

    // Format as:
    //   UserRepository extends JpaRepository<User, Long>
    //     Custom methods:
    //       findByEmail(String email): Optional<User>
    //       findByRole(Role role): List<User>
    //       @Query("SELECT u FROM User u WHERE u.active = true")
    //       findActiveUsers(): List<User>
    //
    //   OrderRepository extends JpaRepository<Order, UUID>
    //     Custom methods:
    //       findByCustomerIdAndStatus(Long customerId, OrderStatus status): List<Order>
}.inSmartMode(project).executeSynchronously()
```

- [ ] **Step 2: Verify and commit**

---

## Task 8: Spring Security Config

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/SpringSecurityTool.kt`

- [ ] **Step 1: Implement SpringSecurityTool**

```kotlin
class SpringSecurityTool : AgentTool {
    name = "spring_security_config"
    description = "Analyze Spring Security configuration — authentication methods, URL authorization patterns, CORS config, CSRF settings. Scans @EnableWebSecurity classes and SecurityFilterChain beans."
}
```

Implementation using PSI:
```kotlin
ReadAction.nonBlocking<String> {
    // 1. Find @EnableWebSecurity annotated classes
    val securityAnnotation = facade.findClass("org.springframework.security.config.annotation.web.configuration.EnableWebSecurity", scope)

    // 2. Find SecurityFilterChain @Bean methods
    // 3. Scan method bodies for common security config patterns:
    //    .authorizeHttpRequests() → URL patterns
    //    .oauth2Login() → OAuth2
    //    .httpBasic() → Basic auth
    //    .formLogin() → Form-based
    //    .csrf().disable() → CSRF off
    //    .cors() → CORS config

    // 4. Find @PreAuthorize/@Secured annotations on controllers

    // Format as:
    //   Security Configuration:
    //     Class: SecurityConfig (@EnableWebSecurity)
    //     Authentication: OAuth2 + Form Login
    //     URL Patterns:
    //       /api/public/** → permitAll
    //       /api/admin/** → hasRole('ADMIN')
    //       /api/** → authenticated
    //     CSRF: disabled
    //     CORS: enabled (origins: *)
    //     Method Security:
    //       AdminController.deleteUser() → @PreAuthorize("hasRole('ADMIN')")
}.inSmartMode(project).executeSynchronously()
```

- [ ] **Step 2: Verify and commit**

---

## Task 9: Spring Scheduled Tasks

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/SpringScheduledTool.kt`

- [ ] **Step 1: Implement SpringScheduledTool**

```kotlin
class SpringScheduledTool : AgentTool {
    name = "spring_scheduled_tasks"
    description = "List @Scheduled methods — cron expressions, fixed rates/delays, and the class they belong to."
}
```

Implementation using PSI:
```kotlin
ReadAction.nonBlocking<String> {
    val scheduled = facade.findClass("org.springframework.scheduling.annotation.Scheduled", scope)
    AnnotatedElementsSearch.searchPsiMethods(scheduled, scope).findAll()

    // For each method:
    //   - Class name + method name
    //   - @Scheduled params: cron, fixedRate, fixedDelay, initialDelay

    // Format as:
    //   Scheduled Tasks:
    //     ReportService.generateDailyReport()
    //       cron: "0 0 6 * * *" (daily at 6am)
    //     CacheService.evictExpired()
    //       fixedRate: 300000 (every 5 minutes)
    //     HealthCheckService.checkServices()
    //       fixedDelay: 60000, initialDelay: 10000
}.inSmartMode(project).executeSynchronously()
```

- [ ] **Step 2: Verify and commit**

---

## Task 10: Spring Event Listeners

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/SpringEventListenersTool.kt`

- [ ] **Step 1: Implement SpringEventListenersTool**

```kotlin
class SpringEventListenersTool : AgentTool {
    name = "spring_event_listeners"
    description = "List @EventListener methods and ApplicationListener implementations — event types they handle, conditions, and async flags."
}
```

Implementation using PSI:
```kotlin
ReadAction.nonBlocking<String> {
    // 1. Find @EventListener annotations
    val eventListener = facade.findClass("org.springframework.context.event.EventListener", scope)
    AnnotatedElementsSearch.searchPsiMethods(eventListener, scope).findAll()

    // 2. Find ApplicationListener implementations
    val appListener = facade.findClass("org.springframework.context.ApplicationListener", scope)
    ClassInheritorsSearch.search(appListener, scope).findAll()

    // Format as:
    //   Event Listeners:
    //     OrderService.onOrderCreated(OrderCreatedEvent)
    //       @EventListener, @Async
    //     NotificationService.onUserRegistered(UserRegisteredEvent)
    //       @EventListener(condition = "#event.verified")
    //     AuditLogger implements ApplicationListener<AuthenticationSuccessEvent>
}.inSmartMode(project).executeSynchronously()
```

- [ ] **Step 2: Verify and commit**

---

## Task 11: Register All 10 Tools + Update Categories

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ToolCategoryRegistry.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/DynamicToolSelector.kt`
- Modify: `agent/CLAUDE.md`

- [ ] **Step 1: Register in AgentService**

Add all 10 tools after existing framework tools.

- [ ] **Step 2: Update ToolCategoryRegistry**

Add to framework category:
```kotlin
tools = listOf(
    "spring_context", "spring_endpoints", "spring_bean_graph", "spring_config",
    "jpa_entities", "project_modules",
    // New Phase 3 tools:
    "maven_dependencies", "maven_properties", "maven_plugins", "maven_profiles",
    "spring_version_info", "spring_profiles", "spring_repositories",
    "spring_security_config", "spring_scheduled_tasks", "spring_event_listeners"
)
```

Update total to 63 tools.

- [ ] **Step 3: Add keyword triggers in DynamicToolSelector**

```kotlin
"dependency" to setOf("maven_dependencies", "project_modules"),
"dependencies" to setOf("maven_dependencies", "project_modules"),
"pom" to setOf("maven_dependencies", "maven_properties", "maven_plugins", "project_modules"),
"maven" to setOf("maven_dependencies", "maven_properties", "maven_plugins", "maven_profiles"),
"version" to setOf("spring_version_info"),
"profile" to setOf("spring_profiles", "maven_profiles"),
"repository" to setOf("spring_repositories", "spring_context"),
"security" to setOf("spring_security_config"),
"auth" to setOf("spring_security_config"),
"scheduled" to setOf("spring_scheduled_tasks"),
"cron" to setOf("spring_scheduled_tasks"),
"event" to setOf("spring_event_listeners"),
"listener" to setOf("spring_event_listeners"),
"plugin" to setOf("maven_plugins"),
```

- [ ] **Step 4: Update agent/CLAUDE.md**

Update framework section with all 16 tools. Update total to 63.

- [ ] **Step 5: Full verification**

```bash
./gradlew :agent:clean :agent:test --rerun --no-build-cache
./gradlew verifyPlugin
```

- [ ] **Step 6: Commit**

```bash
git commit -m "feat(agent): register 10 framework intelligence tools, update to 63 total"
```

---

## Implementation Order

```
Tasks 1-5 (Maven tools): Independent, can run in any order
Tasks 6-10 (Spring/PSI tools): Independent, can run in any order
Task 11 (Register + wire): Depends on all above
```

All Tasks 1-10 are completely independent — each creates one new file.
