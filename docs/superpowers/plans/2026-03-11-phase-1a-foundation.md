# Phase 1A: Foundation — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create a working IntelliJ plugin that boots, connects to external services (Jira, Bamboo, SonarQube, Bitbucket, Nexus, Sourcegraph), persists credentials securely, and shows an empty tool window shell with 5 tabs.

**Architecture:** Modular monolith — single plugin with Gradle submodules (`:core` initially, feature modules added in later phases). Kotlin 2.x, coroutines for async, OkHttp for HTTP, kotlinx.serialization for JSON, IntelliJ PasswordSafe for credentials.

**Tech Stack:** Kotlin 2.1.10, IntelliJ Platform Gradle Plugin 2.12.0, IntelliJ IDEA 2025.1+, OkHttp 4.12.0, kotlinx-coroutines 1.8.0, kotlinx-serialization 1.7.3, JUnit 5, MockK

**Spec:** `docs/superpowers/specs/2026-03-11-workflow-orchestrator-plugin-design.md`

---

## File Map

### Root project files
| File | Purpose |
|---|---|
| `settings.gradle.kts` | Module declarations, repository config |
| `build.gradle.kts` | Root build: plugin composition, signing, verification |
| `gradle.properties` | Platform version, plugin metadata |
| `gradle/libs.versions.toml` | Version catalog |

### `:core` module — production code
| File | Purpose |
|---|---|
| `core/build.gradle.kts` | Submodule build: platform.module plugin |
| `core/src/main/resources/META-INF/plugin.xml` | Plugin descriptor |
| `core/src/main/resources/META-INF/pluginIcon.svg` | Plugin icon (40x40) |
| `core/src/main/resources/messages/WorkflowBundle.properties` | i18n strings |
| `core/src/main/kotlin/.../core/model/ApiResult.kt` | Sealed result type for all API calls |
| `core/src/main/kotlin/.../core/model/ServiceType.kt` | Enum of external services |
| `core/src/main/kotlin/.../core/auth/CredentialStore.kt` | PasswordSafe wrapper |
| `core/src/main/kotlin/.../core/auth/AuthTestService.kt` | "Test Connection" for each service |
| `core/src/main/kotlin/.../core/http/HttpClientFactory.kt` | Creates per-service OkHttpClients |
| `core/src/main/kotlin/.../core/http/AuthInterceptor.kt` | Injects auth token per request (Bearer or Basic) |
| `core/src/main/kotlin/.../core/http/RetryInterceptor.kt` | Exponential backoff on 429/5xx |
| `core/src/main/kotlin/.../core/offline/ConnectivityMonitor.kt` | Per-service reachability |
| `core/src/main/kotlin/.../core/offline/OfflineState.kt` | ServiceStatus + OverallState enums |
| `core/src/main/kotlin/.../core/settings/PluginSettings.kt` | PersistentStateComponent |
| `core/src/main/kotlin/.../core/settings/WorkflowSettingsConfigurable.kt` | Root settings configurable |
| `core/src/main/kotlin/.../core/settings/ConnectionsConfigurable.kt` | Connections sub-page (Kotlin UI DSL v2) |
| `core/src/main/kotlin/.../core/notifications/WorkflowNotificationService.kt` | Central notification dispatcher |
| `core/src/main/kotlin/.../core/toolwindow/WorkflowToolWindowFactory.kt` | Main tool window with 5 tabs |
| `core/src/main/kotlin/.../core/toolwindow/EmptyStatePanel.kt` | Reusable empty state component |
| `core/src/main/kotlin/.../core/onboarding/OnboardingService.kt` | First-run detection |
| `core/src/main/kotlin/.../core/onboarding/SetupDialog.kt` | Collapsible connection dialog |

> **Note:** `...` = `com/workflow/orchestrator` throughout this plan.

### `:core` module — test code
| File | Purpose |
|---|---|
| `core/src/test/kotlin/.../core/model/ApiResultTest.kt` | ApiResult mapping/folding tests |
| `core/src/test/kotlin/.../core/auth/CredentialStoreTest.kt` | PasswordSafe integration test |
| `core/src/test/kotlin/.../core/auth/AuthTestServiceTest.kt` | Connection test logic |
| `core/src/test/kotlin/.../core/http/HttpClientFactoryTest.kt` | Client creation tests |
| `core/src/test/kotlin/.../core/http/AuthInterceptorTest.kt` | Token injection test |
| `core/src/test/kotlin/.../core/http/RetryInterceptorTest.kt` | Backoff behavior test |
| `core/src/test/kotlin/.../core/offline/ConnectivityMonitorTest.kt` | State transitions |
| `core/src/test/kotlin/.../core/settings/PluginSettingsTest.kt` | State persistence |

---

## Chunk 1: Project Scaffolding

### Task 1: Initialize Gradle wrapper and version catalog

**Files:**
- Create: `gradle/libs.versions.toml`
- Create: `gradle.properties`

- [ ] **Step 1: Set up Gradle wrapper**

Run:
```bash
cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin
gradle wrapper --gradle-version 8.13
```
Expected: `gradlew`, `gradlew.bat`, `gradle/wrapper/` created.

- [ ] **Step 2: Create version catalog**

Create `gradle/libs.versions.toml`:

```toml
[versions]
kotlin = "2.1.10"
intellijPlatform = "2.12.0"
coroutines = "1.8.0"
serialization = "1.7.3"
okhttp = "4.12.0"
junit = "5.10.2"
mockk = "1.13.10"

[libraries]
coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
okhttp-coroutines = { module = "com.squareup.okhttp3:okhttp-coroutines", version.ref = "okhttp" }
okhttp-mockwebserver = { module = "com.squareup.okhttp3:mockwebserver", version.ref = "okhttp" }
junit-api = { module = "org.junit.jupiter:junit-jupiter-api", version.ref = "junit" }
junit-engine = { module = "org.junit.jupiter:junit-jupiter-engine", version.ref = "junit" }
mockk = { module = "io.mockk:mockk", version.ref = "mockk" }

[plugins]
kotlin = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
intelliJPlatform = { id = "org.jetbrains.intellij.platform", version.ref = "intellijPlatform" }
intelliJPlatformModule = { id = "org.jetbrains.intellij.platform.module", version.ref = "intellijPlatform" }
```

- [ ] **Step 3: Create gradle.properties**

Create `gradle.properties`:

```properties
# IntelliJ Platform
platformType=IC
platformVersion=2025.1

# Plugin metadata
pluginGroup=com.workflow.orchestrator
pluginName=Workflow Orchestrator
pluginVersion=0.1.0
pluginSinceBuild=251
pluginUntilBuild=253.*

# Kotlin
kotlin.stdlib.default.dependency=false

# Gradle
org.gradle.configuration-cache=true
org.gradle.caching=true
org.gradle.parallel=true
org.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=512m
```

- [ ] **Step 4: Commit**

```bash
git add gradle/ gradle.properties
git commit -m "chore: add Gradle wrapper and version catalog"
```

---

### Task 2: Create root build.gradle.kts and settings.gradle.kts

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`

- [ ] **Step 1: Create settings.gradle.kts**

```kotlin
rootProject.name = "intellij-workflow-orchestrator"

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

include(":core")
```

- [ ] **Step 2: Create root build.gradle.kts**

```kotlin
plugins {
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.serialization) apply false
    alias(libs.plugins.intelliJPlatform)
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion"))
        pluginVerifier()
        zipSigner()
        instrumentationTools()
    }

    // Compose :core into the plugin JAR
    intellijPlatformPluginComposedModule(implementation(project(":core")))
}

intellijPlatform {
    pluginConfiguration {
        id = "${providers.gradleProperty("pluginGroup").get()}.plugin"
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }

        vendor {
            name = "Workflow Team"
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    wrapper {
        gradleVersion = "8.13"
    }
}
```

- [ ] **Step 3: Verify Gradle sync**

Run:
```bash
./gradlew --version
```
Expected: Gradle 8.13, JVM 21.

- [ ] **Step 4: Commit**

```bash
git add settings.gradle.kts build.gradle.kts
git commit -m "chore: add root build files with IntelliJ Platform Gradle Plugin v2"
```

---

### Task 3: Create :core submodule with build file and plugin descriptor

**Files:**
- Create: `core/build.gradle.kts`
- Create: `core/src/main/resources/META-INF/plugin.xml`
- Create: `core/src/main/resources/messages/WorkflowBundle.properties`

- [ ] **Step 1: Create core directory structure**

Run:
```bash
mkdir -p core/src/main/kotlin/com/workflow/orchestrator/core
mkdir -p core/src/main/resources/META-INF
mkdir -p core/src/main/resources/messages
mkdir -p core/src/test/kotlin/com/workflow/orchestrator/core
mkdir -p core/src/test/resources/fixtures
```

- [ ] **Step 2: Create core/build.gradle.kts**

```kotlin
plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.serialization)
    alias(libs.plugins.intelliJPlatformModule)
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion"))
        instrumentationTools()
    }

    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.coroutines)

    testImplementation(libs.junit.api)
    testRuntimeOnly(libs.junit.engine)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    useJUnitPlatform()
}
```

- [ ] **Step 3: Create plugin.xml**

Create `core/src/main/resources/META-INF/plugin.xml`:

```xml
<idea-plugin>
    <id>com.workflow.orchestrator.plugin</id>
    <name>Workflow Orchestrator</name>
    <vendor>Workflow Team</vendor>
    <description><![CDATA[
        Eliminates context-switching between Jira, Bamboo, SonarQube, Bitbucket, and Cody Enterprise
        by consolidating the entire Spring Boot development lifecycle into a single IDE interface.
    ]]></description>

    <depends>com.intellij.modules.platform</depends>
    <depends>com.intellij.modules.java</depends>

    <resource-bundle>messages.WorkflowBundle</resource-bundle>

    <extensions defaultExtensionNs="com.intellij">
        <!-- Settings -->
        <projectConfigurable
            parentId="tools"
            instance="com.workflow.orchestrator.core.settings.WorkflowSettingsConfigurable"
            id="workflow.orchestrator"
            displayName="Workflow Orchestrator"
            nonDefaultProject="true"/>

        <!-- Tool Window -->
        <toolWindow
            id="Workflow"
            anchor="bottom"
            icon="/icons/workflow.svg"
            factoryClass="com.workflow.orchestrator.core.toolwindow.WorkflowToolWindowFactory"/>

        <!-- Notification Groups -->
        <notificationGroup id="workflow.build" displayType="BALLOON"/>
        <notificationGroup id="workflow.quality" displayType="BALLOON"/>
        <notificationGroup id="workflow.queue" displayType="BALLOON"/>
        <notificationGroup id="workflow.automation" displayType="BALLOON"/>

        <!-- Project Service -->
        <projectService
            serviceImplementation="com.workflow.orchestrator.core.settings.PluginSettings"/>
        <projectService
            serviceImplementation="com.workflow.orchestrator.core.offline.ConnectivityMonitor"/>
        <projectService
            serviceImplementation="com.workflow.orchestrator.core.notifications.WorkflowNotificationService"/>
        <projectService
            serviceImplementation="com.workflow.orchestrator.core.onboarding.OnboardingService"/>
    </extensions>

    <extensions defaultExtensionNs="com.intellij">
        <!-- Startup (onboarding check) -->
        <postStartupActivity
            implementation="com.workflow.orchestrator.core.onboarding.OnboardingStartupListener"/>
    </extensions>
</idea-plugin>
```

- [ ] **Step 4: Create message bundle**

Create `core/src/main/resources/messages/WorkflowBundle.properties`:

```properties
# Settings
settings.title=Workflow Orchestrator
settings.connections.title=Connections
settings.connections.jira.title=Jira Connection
settings.connections.bamboo.title=Bamboo Connection
settings.connections.bitbucket.title=Bitbucket Connection
settings.connections.sonar.title=SonarQube Connection
settings.connections.sourcegraph.title=Cody Enterprise
settings.connections.nexus.title=Nexus Docker Registry
settings.connections.url=Server URL:
settings.connections.token=Access Token:
settings.connections.testConnection=Test Connection
settings.connections.testSuccess=Connected successfully
settings.connections.testFailed=Connection failed: {0}

# Tool Window Tabs
toolwindow.tab.sprint=Sprint
toolwindow.tab.build=Build
toolwindow.tab.quality=Quality
toolwindow.tab.automation=Automation
toolwindow.tab.handover=Handover

# Empty States
empty.sprint=No tickets assigned.\nConnect to Jira in Settings to get started.
empty.build=No builds found.\nPush your changes to trigger a CI build.
empty.quality=No quality data available.\nConnect to SonarQube in Settings.
empty.automation=Automation suite not configured.\nSet up Bamboo in Settings.
empty.handover=No active task to hand over.\nStart work on a ticket first.

# Onboarding
onboarding.tooltip.title=Welcome to Workflow Orchestrator!
onboarding.tooltip.body=Connect your tools to get started.
onboarding.tooltip.action=Start Setup
onboarding.setup.title=Setup Workflow Orchestrator

# Status
status.online=Online
status.offline=Offline
status.degraded=Degraded
```

- [ ] **Step 5: Create placeholder plugin icon**

Create `core/src/main/resources/icons/workflow.svg`:

```xml
<svg xmlns="http://www.w3.org/2000/svg" width="13" height="13" viewBox="0 0 13 13">
  <rect width="11" height="11" x="1" y="1" rx="2" fill="none" stroke="#6C707E" stroke-width="1.2"/>
  <circle cx="4" cy="4.5" r="1.2" fill="#6C707E"/>
  <line x1="6" y1="4.5" x2="10" y2="4.5" stroke="#6C707E" stroke-width="1.2"/>
  <circle cx="4" cy="8.5" r="1.2" fill="#6C707E"/>
  <line x1="6" y1="8.5" x2="10" y2="8.5" stroke="#6C707E" stroke-width="1.2"/>
</svg>
```

- [ ] **Step 6: Verify project compiles**

Run:
```bash
./gradlew :core:classes
```
Expected: BUILD SUCCESSFUL (no source files yet, but structure is valid).

- [ ] **Step 7: Commit**

```bash
git add core/ settings.gradle.kts
git commit -m "chore: add :core submodule with plugin descriptor and message bundle"
```

---

## Chunk 2: Core Data Model & Authentication

### Task 4: ApiResult sealed type

**Files:**
- Create: `core/src/main/kotlin/.../core/model/ApiResult.kt`
- Create: `core/src/main/kotlin/.../core/model/ServiceType.kt`
- Test: `core/src/test/kotlin/.../core/model/ApiResultTest.kt`

- [ ] **Step 1: Write the failing test**

Create `core/src/test/kotlin/com/workflow/orchestrator/core/model/ApiResultTest.kt`:

```kotlin
package com.workflow.orchestrator.core.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ApiResultTest {

    @Test
    fun `Success maps value`() {
        val result: ApiResult<Int> = ApiResult.Success(42)
        val mapped = result.map { it * 2 }
        assertEquals(ApiResult.Success(84), mapped)
    }

    @Test
    fun `Error propagates through map`() {
        val result: ApiResult<Int> = ApiResult.Error(ErrorType.NETWORK_ERROR, "timeout")
        val mapped = result.map { it * 2 }
        assertTrue(mapped is ApiResult.Error)
        assertEquals("timeout", (mapped as ApiResult.Error).message)
    }

    @Test
    fun `fold returns success value or error handler`() {
        val success: ApiResult<String> = ApiResult.Success("hello")
        val error: ApiResult<String> = ApiResult.Error(ErrorType.AUTH_FAILED, "bad token")

        assertEquals("hello", success.fold(onSuccess = { it }, onError = { "error" }))
        assertEquals("error", error.fold(onSuccess = { it }, onError = { "error" }))
    }

    @Test
    fun `getOrNull returns value on success, null on error`() {
        assertEquals("data", ApiResult.Success("data").getOrNull())
        assertNull(ApiResult.Error(ErrorType.NOT_FOUND, "gone").getOrNull())
    }

    @Test
    fun `isSuccess and isError`() {
        assertTrue(ApiResult.Success("ok").isSuccess)
        assertFalse(ApiResult.Success("ok").isError)
        assertTrue(ApiResult.Error(ErrorType.TIMEOUT, "slow").isError)
        assertFalse(ApiResult.Error(ErrorType.TIMEOUT, "slow").isSuccess)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
./gradlew :core:test --tests "*.model.ApiResultTest" --info
```
Expected: FAIL — classes not found.

- [ ] **Step 3: Write implementation**

Create `core/src/main/kotlin/com/workflow/orchestrator/core/model/ServiceType.kt`:

```kotlin
package com.workflow.orchestrator.core.model

enum class ServiceType(val displayName: String) {
    JIRA("Jira"),
    BAMBOO("Bamboo"),
    BITBUCKET("Bitbucket"),
    SONARQUBE("SonarQube"),
    SOURCEGRAPH("Cody Enterprise"),
    NEXUS("Nexus Docker Registry");
}
```

Create `core/src/main/kotlin/com/workflow/orchestrator/core/model/ApiResult.kt`:

```kotlin
package com.workflow.orchestrator.core.model

enum class ErrorType {
    AUTH_FAILED,
    FORBIDDEN,
    NOT_FOUND,
    RATE_LIMITED,
    SERVER_ERROR,
    NETWORK_ERROR,
    TIMEOUT
}

sealed class ApiResult<out T> {

    data class Success<T>(val data: T) : ApiResult<T>()

    data class Error(
        val type: ErrorType,
        val message: String,
        val cause: Throwable? = null
    ) : ApiResult<Nothing>()

    val isSuccess: Boolean get() = this is Success
    val isError: Boolean get() = this is Error

    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Error -> null
    }

    inline fun <R> map(transform: (T) -> R): ApiResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Error -> this
    }

    inline fun <R> fold(
        onSuccess: (T) -> R,
        onError: (Error) -> R
    ): R = when (this) {
        is Success -> onSuccess(data)
        is Error -> onError(this)
    }

    inline fun onSuccess(action: (T) -> Unit): ApiResult<T> {
        if (this is Success) action(data)
        return this
    }

    inline fun onError(action: (Error) -> Unit): ApiResult<T> {
        if (this is Error) action(this)
        return this
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
./gradlew :core:test --tests "*.model.ApiResultTest" -v
```
Expected: All 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/model/
git add core/src/test/kotlin/com/workflow/orchestrator/core/model/
git commit -m "feat(core): add ApiResult sealed type and ServiceType enum"
```

---

### Task 5: CredentialStore (PasswordSafe wrapper)

**Files:**
- Create: `core/src/main/kotlin/.../core/auth/CredentialStore.kt`
- Test: `core/src/test/kotlin/.../core/auth/CredentialStoreTest.kt`

- [ ] **Step 1: Write the failing test**

Create `core/src/test/kotlin/com/workflow/orchestrator/core/auth/CredentialStoreTest.kt`:

```kotlin
package com.workflow.orchestrator.core.auth

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.workflow.orchestrator.core.model.ServiceType
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CredentialStoreTest {

    private lateinit var store: CredentialStore
    private lateinit var mockPasswordSafe: PasswordSafe

    @BeforeEach
    fun setUp() {
        mockPasswordSafe = mockk(relaxed = true)
        mockkStatic(PasswordSafe::class)
        every { PasswordSafe.instance } returns mockPasswordSafe
        store = CredentialStore()
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `store and retrieve token`() {
        val slot = slot<Credentials>()
        every { mockPasswordSafe.set(any(), capture(slot)) } just Runs
        every { mockPasswordSafe.get(any()) } answers {
            if (slot.isCaptured) slot.captured else null
        }

        store.storeToken(ServiceType.JIRA, "test-token-123")
        assertEquals("test-token-123", store.getToken(ServiceType.JIRA))
    }

    @Test
    fun `retrieve missing token returns null`() {
        every { mockPasswordSafe.get(any()) } returns null
        assertNull(store.getToken(ServiceType.BAMBOO))
    }

    @Test
    fun `hasToken returns false when no token stored`() {
        every { mockPasswordSafe.get(any()) } returns null
        assertFalse(store.hasToken(ServiceType.BITBUCKET))
    }

    @Test
    fun `hasToken returns true when token exists`() {
        every { mockPasswordSafe.get(any()) } returns Credentials("user", "token")
        assertTrue(store.hasToken(ServiceType.BITBUCKET))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
./gradlew :core:test --tests "*.auth.CredentialStoreTest" --info
```
Expected: FAIL — class not found.

- [ ] **Step 3: Write implementation**

Create `core/src/main/kotlin/com/workflow/orchestrator/core/auth/CredentialStore.kt`:

```kotlin
package com.workflow.orchestrator.core.auth

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.workflow.orchestrator.core.model.ServiceType

class CredentialStore {

    fun storeToken(service: ServiceType, token: String) {
        val attributes = credentialAttributes(service)
        val credentials = Credentials(service.name, token)
        PasswordSafe.instance.set(attributes, credentials)
    }

    fun getToken(service: ServiceType): String? {
        val attributes = credentialAttributes(service)
        return PasswordSafe.instance.get(attributes)?.getPasswordAsString()
    }

    fun removeToken(service: ServiceType) {
        val attributes = credentialAttributes(service)
        PasswordSafe.instance.set(attributes, null)
    }

    fun hasToken(service: ServiceType): Boolean {
        return getToken(service) != null
    }

    private fun credentialAttributes(service: ServiceType): CredentialAttributes {
        return CredentialAttributes(
            generateServiceName("WorkflowOrchestrator", service.name)
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
./gradlew :core:test --tests "*.auth.CredentialStoreTest" -v
```
Expected: All 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/auth/CredentialStore.kt
git add core/src/test/kotlin/com/workflow/orchestrator/core/auth/CredentialStoreTest.kt
git commit -m "feat(core): add CredentialStore with PasswordSafe integration"
```

---

## Chunk 3: HTTP Client Layer

### Task 6: AuthInterceptor

**Files:**
- Create: `core/src/main/kotlin/.../core/http/AuthInterceptor.kt`
- Test: `core/src/test/kotlin/.../core/http/AuthInterceptorTest.kt`

- [ ] **Step 1: Write the failing test**

Create `core/src/test/kotlin/com/workflow/orchestrator/core/http/AuthInterceptorTest.kt`:

```kotlin
package com.workflow.orchestrator.core.http

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AuthInterceptorTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `adds Bearer token to request`() {
        server.enqueue(MockResponse().setBody("ok"))

        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor({ "my-secret-token" }, AuthScheme.BEARER))
            .build()

        client.newCall(Request.Builder().url(server.url("/test")).build()).execute()

        val recorded = server.takeRequest()
        assertEquals("Bearer my-secret-token", recorded.getHeader("Authorization"))
    }

    @Test
    fun `adds Basic auth header for BASIC scheme`() {
        server.enqueue(MockResponse().setBody("ok"))

        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor({ "my-token" }, AuthScheme.BASIC))
            .build()

        client.newCall(Request.Builder().url(server.url("/test")).build()).execute()

        val recorded = server.takeRequest()
        val expected = "Basic " + java.util.Base64.getEncoder().encodeToString("my-token:".toByteArray())
        assertEquals(expected, recorded.getHeader("Authorization"))
    }

    @Test
    fun `skips auth header when token provider returns null`() {
        server.enqueue(MockResponse().setBody("ok"))

        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor { null })
            .build()

        client.newCall(Request.Builder().url(server.url("/test")).build()).execute()

        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Authorization"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
./gradlew :core:test --tests "*.http.AuthInterceptorTest" --info
```
Expected: FAIL — class not found.

- [ ] **Step 3: Write implementation**

Create `core/src/main/kotlin/com/workflow/orchestrator/core/http/AuthInterceptor.kt`:

```kotlin
package com.workflow.orchestrator.core.http

import okhttp3.Interceptor
import okhttp3.Response

enum class AuthScheme { BEARER, BASIC }

class AuthInterceptor(
    private val tokenProvider: () -> String?,
    private val scheme: AuthScheme = AuthScheme.BEARER
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val token = tokenProvider()

        val request = if (token != null) {
            val headerValue = when (scheme) {
                AuthScheme.BEARER -> "Bearer $token"
                AuthScheme.BASIC -> "Basic " + java.util.Base64.getEncoder()
                    .encodeToString("$token:".toByteArray())
            }
            originalRequest.newBuilder()
                .header("Authorization", headerValue)
                .build()
        } else {
            originalRequest
        }

        return chain.proceed(request)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
./gradlew :core:test --tests "*.http.AuthInterceptorTest" -v
```
Expected: Both tests PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/http/AuthInterceptor.kt
git add core/src/test/kotlin/com/workflow/orchestrator/core/http/AuthInterceptorTest.kt
git commit -m "feat(core): add AuthInterceptor for Bearer token injection"
```

---

### Task 7: RetryInterceptor

**Files:**
- Create: `core/src/main/kotlin/.../core/http/RetryInterceptor.kt`
- Test: `core/src/test/kotlin/.../core/http/RetryInterceptorTest.kt`

- [ ] **Step 1: Write the failing test**

Create `core/src/test/kotlin/com/workflow/orchestrator/core/http/RetryInterceptorTest.kt`:

```kotlin
package com.workflow.orchestrator.core.http

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RetryInterceptorTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `retries on 503 and succeeds`() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("ok").setResponseCode(200))

        val client = OkHttpClient.Builder()
            .addInterceptor(RetryInterceptor(maxRetries = 3, baseDelayMs = 10))
            .build()

        val response = client.newCall(
            Request.Builder().url(server.url("/test")).build()
        ).execute()

        assertEquals(200, response.code)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `retries on 429 and succeeds`() {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setBody("ok").setResponseCode(200))

        val client = OkHttpClient.Builder()
            .addInterceptor(RetryInterceptor(maxRetries = 3, baseDelayMs = 10))
            .build()

        val response = client.newCall(
            Request.Builder().url(server.url("/test")).build()
        ).execute()

        assertEquals(200, response.code)
    }

    @Test
    fun `does not retry on 401`() {
        server.enqueue(MockResponse().setResponseCode(401))

        val client = OkHttpClient.Builder()
            .addInterceptor(RetryInterceptor(maxRetries = 3, baseDelayMs = 10))
            .build()

        val response = client.newCall(
            Request.Builder().url(server.url("/test")).build()
        ).execute()

        assertEquals(401, response.code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `gives up after max retries`() {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(500)) }

        val client = OkHttpClient.Builder()
            .addInterceptor(RetryInterceptor(maxRetries = 3, baseDelayMs = 10))
            .build()

        val response = client.newCall(
            Request.Builder().url(server.url("/test")).build()
        ).execute()

        assertEquals(500, response.code)
        assertEquals(4, server.requestCount) // 1 original + 3 retries
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
./gradlew :core:test --tests "*.http.RetryInterceptorTest" --info
```
Expected: FAIL.

- [ ] **Step 3: Write implementation**

Create `core/src/main/kotlin/com/workflow/orchestrator/core/http/RetryInterceptor.kt`:

```kotlin
package com.workflow.orchestrator.core.http

import okhttp3.Interceptor
import okhttp3.Response

class RetryInterceptor(
    private val maxRetries: Int = 3,
    private val baseDelayMs: Long = 1000
) : Interceptor {

    private val retryableCodes = setOf(429, 500, 502, 503, 504)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var response = chain.proceed(request)
        var attempt = 0

        while (response.code in retryableCodes && attempt < maxRetries) {
            response.close()
            attempt++
            val delay = baseDelayMs * (1L shl (attempt - 1)) // exponential: 1x, 2x, 4x
            Thread.sleep(delay.coerceAtMost(60_000))
            response = chain.proceed(request)
        }

        return response
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
./gradlew :core:test --tests "*.http.RetryInterceptorTest" -v
```
Expected: All 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/http/RetryInterceptor.kt
git add core/src/test/kotlin/com/workflow/orchestrator/core/http/RetryInterceptorTest.kt
git commit -m "feat(core): add RetryInterceptor with exponential backoff"
```

---

### Task 8: HttpClientFactory

**Files:**
- Create: `core/src/main/kotlin/.../core/http/HttpClientFactory.kt`
- Test: `core/src/test/kotlin/.../core/http/HttpClientFactoryTest.kt`

- [ ] **Step 1: Write the failing test**

Create `core/src/test/kotlin/com/workflow/orchestrator/core/http/HttpClientFactoryTest.kt`:

```kotlin
package com.workflow.orchestrator.core.http

import com.workflow.orchestrator.core.model.ServiceType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HttpClientFactoryTest {

    private lateinit var server: MockWebServer
    private lateinit var factory: HttpClientFactory

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        factory = HttpClientFactory(
            tokenProvider = { service ->
                if (service == ServiceType.JIRA) "jira-token" else null
            }
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `creates client with auth for service`() {
        server.enqueue(MockResponse().setBody("ok"))

        val client = factory.clientFor(ServiceType.JIRA)
        val response = client.newCall(
            okhttp3.Request.Builder().url(server.url("/api")).build()
        ).execute()

        val recorded = server.takeRequest()
        assertEquals("Bearer jira-token", recorded.getHeader("Authorization"))
        assertEquals(200, response.code)
    }

    @Test
    fun `caches client per service type`() {
        val client1 = factory.clientFor(ServiceType.JIRA)
        val client2 = factory.clientFor(ServiceType.JIRA)
        assertSame(client1, client2)
    }

    @Test
    fun `different services get different clients`() {
        val jiraClient = factory.clientFor(ServiceType.JIRA)
        val bambooClient = factory.clientFor(ServiceType.BAMBOO)
        assertNotSame(jiraClient, bambooClient)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
./gradlew :core:test --tests "*.http.HttpClientFactoryTest" --info
```
Expected: FAIL.

- [ ] **Step 3: Write implementation**

Create `core/src/main/kotlin/com/workflow/orchestrator/core/http/HttpClientFactory.kt`:

```kotlin
package com.workflow.orchestrator.core.http

import com.workflow.orchestrator.core.model.ServiceType
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class HttpClientFactory(
    private val tokenProvider: (ServiceType) -> String?,
    private val connectTimeoutSeconds: Long = 10,
    private val readTimeoutSeconds: Long = 30
) {
    private val clients = ConcurrentHashMap<ServiceType, OkHttpClient>()

    private val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .addInterceptor(RetryInterceptor())
            .build()
    }

    fun clientFor(service: ServiceType): OkHttpClient {
        return clients.getOrPut(service) {
            val scheme = when (service) {
                ServiceType.NEXUS -> AuthScheme.BASIC
                else -> AuthScheme.BEARER
            }
            baseClient.newBuilder()
                .addInterceptor(AuthInterceptor({ tokenProvider(service) }, scheme))
                .build()
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
./gradlew :core:test --tests "*.http.HttpClientFactoryTest" -v
```
Expected: All 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/http/HttpClientFactory.kt
git add core/src/test/kotlin/com/workflow/orchestrator/core/http/HttpClientFactoryTest.kt
git commit -m "feat(core): add HttpClientFactory with per-service cached clients"
```

---

### Task 9: AuthTestService

**Files:**
- Create: `core/src/main/kotlin/.../core/auth/AuthTestService.kt`
- Test: `core/src/test/kotlin/.../core/auth/AuthTestServiceTest.kt`

- [ ] **Step 1: Write the failing test**

Create `core/src/test/kotlin/com/workflow/orchestrator/core/auth/AuthTestServiceTest.kt`:

```kotlin
package com.workflow.orchestrator.core.auth

import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AuthTestServiceTest {

    private lateinit var server: MockWebServer
    private lateinit var service: AuthTestService

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        service = AuthTestService()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `Jira test connection succeeds on 200`() = runTest {
        server.enqueue(MockResponse()
            .setBody("""{"displayName":"John","emailAddress":"john@co.com"}""")
            .setResponseCode(200))

        val result = service.testConnection(
            serviceType = ServiceType.JIRA,
            baseUrl = server.url("/").toString().trimEnd('/'),
            token = "test-token"
        )

        assertTrue(result.isSuccess)
        val recorded = server.takeRequest()
        assertEquals("/rest/api/2/myself", recorded.path)
        assertEquals("Bearer test-token", recorded.getHeader("Authorization"))
    }

    @Test
    fun `test connection returns error on 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = service.testConnection(
            serviceType = ServiceType.JIRA,
            baseUrl = server.url("/").toString().trimEnd('/'),
            token = "bad-token"
        )

        assertTrue(result.isError)
    }

    @Test
    fun `Bamboo test connection hits correct endpoint`() = runTest {
        server.enqueue(MockResponse()
            .setBody("""{"name":"admin"}""")
            .setResponseCode(200))

        service.testConnection(
            serviceType = ServiceType.BAMBOO,
            baseUrl = server.url("/").toString().trimEnd('/'),
            token = "bamboo-token"
        )

        val recorded = server.takeRequest()
        assertEquals("/rest/api/latest/currentUser", recorded.path)
    }

    @Test
    fun `SonarQube test connection hits correct endpoint`() = runTest {
        server.enqueue(MockResponse()
            .setBody("""{"login":"admin"}""")
            .setResponseCode(200))

        service.testConnection(
            serviceType = ServiceType.SONARQUBE,
            baseUrl = server.url("/").toString().trimEnd('/'),
            token = "sonar-token"
        )

        val recorded = server.takeRequest()
        assertEquals("/api/authentication/validate", recorded.path)
    }

    @Test
    fun `Bitbucket test connection hits correct endpoint`() = runTest {
        server.enqueue(MockResponse()
            .setBody("""{"name":"admin"}""")
            .setResponseCode(200))

        service.testConnection(
            serviceType = ServiceType.BITBUCKET,
            baseUrl = server.url("/").toString().trimEnd('/'),
            token = "bb-token"
        )

        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.contains("/rest/api/"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
./gradlew :core:test --tests "*.auth.AuthTestServiceTest" --info
```
Expected: FAIL.

- [ ] **Step 3: Write implementation**

Create `core/src/main/kotlin/com/workflow/orchestrator/core/auth/AuthTestService.kt`:

```kotlin
package com.workflow.orchestrator.core.auth

import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.core.model.ServiceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class AuthTestService {

    private val testClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun testConnection(
        serviceType: ServiceType,
        baseUrl: String,
        token: String
    ): ApiResult<String> = withContext(Dispatchers.IO) {
        val endpoint = healthEndpoint(serviceType)
        val url = "${baseUrl}$endpoint"

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        try {
            val response = testClient.newCall(request).execute()
            response.use {
                when (it.code) {
                    in 200..299 -> ApiResult.Success(it.body?.string() ?: "")
                    401 -> ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid token or unauthorized")
                    403 -> ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient permissions")
                    else -> ApiResult.Error(
                        ErrorType.SERVER_ERROR,
                        "Server returned ${it.code}: ${it.message}"
                    )
                }
            }
        } catch (e: IOException) {
            ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach $baseUrl: ${e.message}", e)
        }
    }

    private fun healthEndpoint(serviceType: ServiceType): String = when (serviceType) {
        ServiceType.JIRA -> "/rest/api/2/myself"
        ServiceType.BAMBOO -> "/rest/api/latest/currentUser"
        ServiceType.SONARQUBE -> "/api/authentication/validate"
        ServiceType.BITBUCKET -> "/rest/api/1.0/users"
        ServiceType.SOURCEGRAPH -> "/.api/client-config"
        ServiceType.NEXUS -> "/v2/"
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
./gradlew :core:test --tests "*.auth.AuthTestServiceTest" -v
```
Expected: All 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/auth/AuthTestService.kt
git add core/src/test/kotlin/com/workflow/orchestrator/core/auth/AuthTestServiceTest.kt
git commit -m "feat(core): add AuthTestService with per-service health endpoints"
```

---

## Chunk 4: Offline Detection & Settings

### Task 10: ConnectivityMonitor and OfflineState

**Files:**
- Create: `core/src/main/kotlin/.../core/offline/OfflineState.kt`
- Create: `core/src/main/kotlin/.../core/offline/ConnectivityMonitor.kt`
- Test: `core/src/test/kotlin/.../core/offline/ConnectivityMonitorTest.kt`

- [ ] **Step 1: Write the failing test**

Create `core/src/test/kotlin/com/workflow/orchestrator/core/offline/ConnectivityMonitorTest.kt`:

```kotlin
package com.workflow.orchestrator.core.offline

import com.workflow.orchestrator.core.model.ServiceType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConnectivityMonitorTest {

    @Test
    fun `initially all services are UNKNOWN`() {
        val monitor = ConnectivityMonitor(mockk(relaxed = true))
        assertEquals(ServiceStatus.UNKNOWN, monitor.statusOf(ServiceType.JIRA))
    }

    @Test
    fun `markOnline sets service to ONLINE`() {
        val monitor = ConnectivityMonitor(mockk(relaxed = true))
        monitor.markOnline(ServiceType.JIRA)
        assertEquals(ServiceStatus.ONLINE, monitor.statusOf(ServiceType.JIRA))
    }

    @Test
    fun `markOffline sets service to OFFLINE`() {
        val monitor = ConnectivityMonitor(mockk(relaxed = true))
        monitor.markOnline(ServiceType.JIRA)
        monitor.markOffline(ServiceType.JIRA)
        assertEquals(ServiceStatus.OFFLINE, monitor.statusOf(ServiceType.JIRA))
    }

    @Test
    fun `overallState is ONLINE when all configured services are online`() {
        val monitor = ConnectivityMonitor(mockk(relaxed = true))
        monitor.markOnline(ServiceType.JIRA)
        monitor.markOnline(ServiceType.BAMBOO)
        assertEquals(OverallState.ONLINE, monitor.overallState(setOf(ServiceType.JIRA, ServiceType.BAMBOO)))
    }

    @Test
    fun `overallState is DEGRADED when some services offline`() {
        val monitor = ConnectivityMonitor(mockk(relaxed = true))
        monitor.markOnline(ServiceType.JIRA)
        monitor.markOffline(ServiceType.BAMBOO)
        assertEquals(
            OverallState.DEGRADED,
            monitor.overallState(setOf(ServiceType.JIRA, ServiceType.BAMBOO))
        )
    }

    @Test
    fun `overallState is OFFLINE when all services offline`() {
        val monitor = ConnectivityMonitor(mockk(relaxed = true))
        monitor.markOffline(ServiceType.JIRA)
        monitor.markOffline(ServiceType.BAMBOO)
        assertEquals(
            OverallState.OFFLINE,
            monitor.overallState(setOf(ServiceType.JIRA, ServiceType.BAMBOO))
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
./gradlew :core:test --tests "*.offline.ConnectivityMonitorTest" --info
```
Expected: FAIL.

- [ ] **Step 3: Write implementation**

Create `core/src/main/kotlin/com/workflow/orchestrator/core/offline/OfflineState.kt`:

```kotlin
package com.workflow.orchestrator.core.offline

enum class ServiceStatus {
    UNKNOWN,
    ONLINE,
    OFFLINE
}

enum class OverallState {
    ONLINE,
    DEGRADED,
    OFFLINE
}
```

Create `core/src/main/kotlin/com/workflow/orchestrator/core/offline/ConnectivityMonitor.kt`:

```kotlin
package com.workflow.orchestrator.core.offline

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.ServiceType
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class ConnectivityMonitor(private val project: Project) {

    private val statuses = ConcurrentHashMap<ServiceType, ServiceStatus>()

    fun statusOf(service: ServiceType): ServiceStatus {
        return statuses.getOrDefault(service, ServiceStatus.UNKNOWN)
    }

    fun markOnline(service: ServiceType) {
        statuses[service] = ServiceStatus.ONLINE
    }

    fun markOffline(service: ServiceType) {
        statuses[service] = ServiceStatus.OFFLINE
    }

    fun overallState(configuredServices: Set<ServiceType>): OverallState {
        if (configuredServices.isEmpty()) return OverallState.ONLINE
        val serviceStatuses = configuredServices.map { statusOf(it) }
        return when {
            serviceStatuses.all { it == ServiceStatus.ONLINE } -> OverallState.ONLINE
            serviceStatuses.all { it == ServiceStatus.OFFLINE } -> OverallState.OFFLINE
            else -> OverallState.DEGRADED
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
./gradlew :core:test --tests "*.offline.ConnectivityMonitorTest" -v
```
Expected: All 6 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/offline/
git add core/src/test/kotlin/com/workflow/orchestrator/core/offline/
git commit -m "feat(core): add ConnectivityMonitor with per-service tracking"
```

---

### Task 11: PluginSettings (PersistentStateComponent)

**Files:**
- Create: `core/src/main/kotlin/.../core/settings/PluginSettings.kt`

- [ ] **Step 1: Write PluginSettings**

Create `core/src/main/kotlin/com/workflow/orchestrator/core/settings/PluginSettings.kt`:

```kotlin
package com.workflow.orchestrator.core.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(
    name = "WorkflowOrchestratorSettings",
    storages = [Storage("workflowOrchestrator.xml")]
)
class PluginSettings : SimplePersistentStateComponent<PluginSettings.State>(State()) {

    class State : BaseState() {
        // Service endpoints
        var jiraUrl by string("")
        var bambooUrl by string("")
        var bitbucketUrl by string("")
        var sonarUrl by string("")
        var sourcegraphUrl by string("")
        var nexusUrl by string("")

        // Polling intervals (seconds)
        var buildPollIntervalSeconds by property(30)
        var queuePollIntervalSeconds by property(60)
        var sonarPollIntervalSeconds by property(60)

        // Feature toggles
        var sprintModuleEnabled by property(true)
        var buildModuleEnabled by property(true)
        var qualityModuleEnabled by property(true)
        var automationModuleEnabled by property(true)
        var handoverModuleEnabled by property(true)

        // Commit message format
        var useConventionalCommits by property(false)

        // Branch naming pattern
        var branchPattern by string("feature/{ticketId}-{summary}")
    }

    val isAnyServiceConfigured: Boolean
        get() = state.jiraUrl.isNotBlank() ||
                state.bambooUrl.isNotBlank() ||
                state.bitbucketUrl.isNotBlank() ||
                state.sonarUrl.isNotBlank() ||
                state.sourcegraphUrl.isNotBlank() ||
                state.nexusUrl.isNotBlank()

    companion object {
        fun getInstance(project: Project): PluginSettings {
            return project.service<PluginSettings>()
        }
    }
}
```

- [ ] **Step 3: Verify compilation**

Run:
```bash
./gradlew :core:classes
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/settings/
git commit -m "feat(core): add PluginSettings with PersistentStateComponent"
```

---

### Task 12: Settings UI (Connections page with Kotlin UI DSL v2)

**Files:**
- Create: `core/src/main/kotlin/.../core/settings/WorkflowSettingsConfigurable.kt`
- Create: `core/src/main/kotlin/.../core/settings/ConnectionsConfigurable.kt`

- [ ] **Step 1: Write root settings configurable**

Create `core/src/main/kotlin/com/workflow/orchestrator/core/settings/WorkflowSettingsConfigurable.kt`:

```kotlin
package com.workflow.orchestrator.core.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project

class WorkflowSettingsConfigurable(
    private val project: Project
) : SearchableConfigurable, Configurable.Composite {

    override fun getId(): String = "workflow.orchestrator"
    override fun getDisplayName(): String = "Workflow Orchestrator"

    override fun getConfigurables(): Array<Configurable> {
        return arrayOf(
            ConnectionsConfigurable(project)
        )
    }

    override fun createComponent() = null
    override fun isModified() = false
    override fun apply() {}
}
```

- [ ] **Step 2: Write Connections sub-page**

Create `core/src/main/kotlin/com/workflow/orchestrator/core/settings/ConnectionsConfigurable.kt`:

```kotlin
package com.workflow.orchestrator.core.settings

import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.*
import com.intellij.openapi.progress.runBackgroundableTask
import com.workflow.orchestrator.core.auth.AuthTestService
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import kotlinx.coroutines.runBlocking
import javax.swing.JLabel
import javax.swing.SwingUtilities

class ConnectionsConfigurable(
    private val project: Project
) : BoundSearchableConfigurable("Connections", "workflow.orchestrator.connections") {

    private val settings = PluginSettings.getInstance(project)
    private val credentialStore = CredentialStore()
    private val authTestService = AuthTestService()

    override fun createPanel() = panel {
        serviceGroup("Jira Connection", ServiceType.JIRA,
            { settings.state.jiraUrl }, { settings.state.jiraUrl = it })
        serviceGroup("Bamboo Connection", ServiceType.BAMBOO,
            { settings.state.bambooUrl }, { settings.state.bambooUrl = it })
        serviceGroup("Bitbucket Connection", ServiceType.BITBUCKET,
            { settings.state.bitbucketUrl }, { settings.state.bitbucketUrl = it })
        serviceGroup("SonarQube Connection", ServiceType.SONARQUBE,
            { settings.state.sonarUrl }, { settings.state.sonarUrl = it })
        serviceGroup("Cody Enterprise", ServiceType.SOURCEGRAPH,
            { settings.state.sourcegraphUrl }, { settings.state.sourcegraphUrl = it })
        serviceGroup("Nexus Docker Registry", ServiceType.NEXUS,
            { settings.state.nexusUrl }, { settings.state.nexusUrl = it })
    }

    private fun Panel.serviceGroup(
        title: String,
        serviceType: ServiceType,
        urlGetter: () -> String,
        urlSetter: (String) -> Unit
    ) {
        val existingToken = credentialStore.getToken(serviceType) ?: ""
        val statusLabel = JLabel("")

        collapsibleGroup(title) {
            row("Server URL:") {
                textField()
                    .columns(40)
                    .bindText(urlGetter, urlSetter)
                    .comment("e.g., https://${serviceType.name.lowercase()}.company.com")
            }
            row("Access Token:") {
                passwordField()
                    .columns(40)
                    .applyToComponent {
                        text = existingToken
                    }
                    .onChanged { field ->
                        val newToken = String(field.password)
                        if (newToken.isNotBlank()) {
                            credentialStore.storeToken(serviceType, newToken)
                        }
                    }
            }
            row {
                button("Test Connection") {
                    val url = urlGetter()
                    val token = credentialStore.getToken(serviceType)
                    if (url.isBlank() || token.isNullOrBlank()) {
                        statusLabel.text = "Please enter URL and token"
                        return@button
                    }
                    statusLabel.text = "Testing..."
                    // Run on background thread to avoid blocking EDT
                    runBackgroundableTask("Testing $title", project, false) {
                        val result = runBlocking {
                            authTestService.testConnection(serviceType, url, token)
                        }
                        SwingUtilities.invokeLater {
                            statusLabel.text = when (result) {
                                is ApiResult.Success -> "Connected successfully"
                                is ApiResult.Error -> "Failed: ${result.message}"
                            }
                        }
                    }
                }
                cell(statusLabel)
            }
        }
    }
}
```

- [ ] **Step 3: Verify compilation**

Run:
```bash
./gradlew :core:classes
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/settings/WorkflowSettingsConfigurable.kt
git add core/src/main/kotlin/com/workflow/orchestrator/core/settings/ConnectionsConfigurable.kt
git commit -m "feat(core): add Settings UI with per-service connection pages"
```

---

## Chunk 5: Tool Window, Notifications & Onboarding

### Task 13: WorkflowNotificationService

**Files:**
- Create: `core/src/main/kotlin/.../core/notifications/WorkflowNotificationService.kt`

- [ ] **Step 1: Write implementation**

Create `core/src/main/kotlin/com/workflow/orchestrator/core/notifications/WorkflowNotificationService.kt`:

```kotlin
package com.workflow.orchestrator.core.notifications

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class WorkflowNotificationService(private val project: Project) {

    fun notifyInfo(groupId: String, title: String, content: String) {
        notify(groupId, title, content, NotificationType.INFORMATION)
    }

    fun notifyWarning(groupId: String, title: String, content: String) {
        notify(groupId, title, content, NotificationType.WARNING)
    }

    fun notifyError(groupId: String, title: String, content: String) {
        notify(groupId, title, content, NotificationType.ERROR)
    }

    private fun notify(groupId: String, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(groupId)
            .createNotification(title, content, type)
            .notify(project)
    }

    companion object {
        const val GROUP_BUILD = "workflow.build"
        const val GROUP_QUALITY = "workflow.quality"
        const val GROUP_QUEUE = "workflow.queue"
        const val GROUP_AUTOMATION = "workflow.automation"

        fun getInstance(project: Project): WorkflowNotificationService {
            return project.getService(WorkflowNotificationService::class.java)
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/notifications/
git commit -m "feat(core): add WorkflowNotificationService with 4 notification groups"
```

---

### Task 14: Tool Window with Empty State Panels

**Files:**
- Create: `core/src/main/kotlin/.../core/toolwindow/EmptyStatePanel.kt`
- Create: `core/src/main/kotlin/.../core/toolwindow/WorkflowToolWindowFactory.kt`

- [ ] **Step 1: Write EmptyStatePanel**

Create `core/src/main/kotlin/com/workflow/orchestrator/core/toolwindow/EmptyStatePanel.kt`:

```kotlin
package com.workflow.orchestrator.core.toolwindow

import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingConstants

class EmptyStatePanel(
    private val project: Project,
    message: String,
    showSettingsLink: Boolean = true
) : JPanel(BorderLayout()) {

    init {
        val centerPanel = JPanel(FlowLayout(FlowLayout.CENTER))
        centerPanel.border = JBUI.Borders.emptyTop(50)

        val label = JBLabel(message.replace("\n", "<br>").let { "<html><center>$it</center></html>" })
        label.horizontalAlignment = SwingConstants.CENTER
        label.foreground = JBUI.CurrentTheme.Label.disabledForeground()
        centerPanel.add(label)

        if (showSettingsLink) {
            val settingsButton = JButton("Open Settings")
            settingsButton.addActionListener {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, "Workflow Orchestrator")
            }
            val buttonPanel = JPanel(FlowLayout(FlowLayout.CENTER))
            buttonPanel.add(settingsButton)
            add(buttonPanel, BorderLayout.CENTER)
            add(centerPanel, BorderLayout.NORTH)
        } else {
            add(centerPanel, BorderLayout.CENTER)
        }
    }
}
```

- [ ] **Step 2: Write WorkflowToolWindowFactory**

Create `core/src/main/kotlin/com/workflow/orchestrator/core/toolwindow/WorkflowToolWindowFactory.kt`:

```kotlin
package com.workflow.orchestrator.core.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class WorkflowToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentManager = toolWindow.contentManager

        val tabs = listOf(
            TabDefinition("Sprint", "No tickets assigned.\nConnect to Jira in Settings to get started."),
            TabDefinition("Build", "No builds found.\nPush your changes to trigger a CI build."),
            TabDefinition("Quality", "No quality data available.\nConnect to SonarQube in Settings."),
            TabDefinition("Automation", "Automation suite not configured.\nSet up Bamboo in Settings."),
            TabDefinition("Handover", "No active task to hand over.\nStart work on a ticket first.")
        )

        tabs.forEach { tab ->
            val panel = EmptyStatePanel(project, tab.emptyMessage)
            val content = ContentFactory.getInstance().createContent(panel, tab.title, false)
            content.isCloseable = false
            contentManager.addContent(content)
        }
    }

    private data class TabDefinition(val title: String, val emptyMessage: String)
}
```

- [ ] **Step 3: Verify compilation**

Run:
```bash
./gradlew :core:classes
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/toolwindow/
git commit -m "feat(core): add Workflow tool window with 5 empty state tabs"
```

---

### Task 15: Onboarding (First-run detection + Setup dialog)

**Files:**
- Create: `core/src/main/kotlin/.../core/onboarding/OnboardingService.kt`
- Create: `core/src/main/kotlin/.../core/onboarding/SetupDialog.kt`
- Create: `core/src/main/kotlin/.../core/onboarding/OnboardingStartupListener.kt`

- [ ] **Step 1: Write OnboardingService**

Create `core/src/main/kotlin/com/workflow/orchestrator/core/onboarding/OnboardingService.kt`:

```kotlin
package com.workflow.orchestrator.core.onboarding

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.settings.PluginSettings

@Service(Service.Level.PROJECT)
class OnboardingService(private val project: Project) {

    var hasShownOnboarding: Boolean = false
        private set

    fun shouldShowOnboarding(): Boolean {
        if (hasShownOnboarding) return false
        val settings = PluginSettings.getInstance(project)
        return !settings.isAnyServiceConfigured
    }

    fun markOnboardingShown() {
        hasShownOnboarding = true
    }

    fun showSetupDialog() {
        val dialog = SetupDialog(project)
        dialog.show()
        markOnboardingShown()
    }

    companion object {
        fun getInstance(project: Project): OnboardingService {
            return project.service<OnboardingService>()
        }
    }
}
```

- [ ] **Step 2: Write SetupDialog**

Create `core/src/main/kotlin/com/workflow/orchestrator/core/onboarding/SetupDialog.kt`:

```kotlin
package com.workflow.orchestrator.core.onboarding

import com.intellij.openapi.progress.runBackgroundableTask
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.dsl.builder.*
import com.workflow.orchestrator.core.auth.AuthTestService
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.coroutines.runBlocking
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPasswordField
import javax.swing.JTextField
import javax.swing.SwingUtilities

class SetupDialog(private val project: Project) : DialogWrapper(project) {

    private val settings = PluginSettings.getInstance(project)
    private val credentialStore = CredentialStore()
    private val authTestService = AuthTestService()

    init {
        title = "Setup Workflow Orchestrator"
        setOKButtonText("Finish Setup")
        setCancelButtonText("Skip")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            text("Connect your development tools. You can configure any service later in Settings.")
        }
        separator()

        connectionSection("Jira", ServiceType.JIRA) { settings.state.jiraUrl = it }
        connectionSection("Bamboo", ServiceType.BAMBOO) { settings.state.bambooUrl = it }
        connectionSection("Bitbucket", ServiceType.BITBUCKET) { settings.state.bitbucketUrl = it }
        connectionSection("SonarQube", ServiceType.SONARQUBE) { settings.state.sonarUrl = it }
        connectionSection("Cody Enterprise", ServiceType.SOURCEGRAPH) { settings.state.sourcegraphUrl = it }
        connectionSection("Nexus Docker Registry", ServiceType.NEXUS) { settings.state.nexusUrl = it }
    }

    private fun Panel.connectionSection(
        title: String,
        serviceType: ServiceType,
        urlSaver: (String) -> Unit
    ) {
        val urlField = JTextField(20)
        val tokenField = JPasswordField(20)
        val statusLabel = JLabel("")

        collapsibleGroup(title) {
            row("Server URL:") {
                cell(urlField).columns(COLUMNS_LARGE)
            }
            row("Access Token:") {
                cell(tokenField).columns(COLUMNS_LARGE)
            }
            row {
                button("Test Connection") {
                    val url = urlField.text.trim()
                    val token = String(tokenField.password).trim()
                    if (url.isBlank() || token.isBlank()) {
                        statusLabel.text = "Enter URL and token"
                        return@button
                    }
                    statusLabel.text = "Testing..."
                    // Run on background thread to avoid blocking EDT
                    runBackgroundableTask("Testing $title", project, false) {
                        val result = runBlocking {
                            authTestService.testConnection(serviceType, url, token)
                        }
                        SwingUtilities.invokeLater {
                            when (result) {
                                is ApiResult.Success -> {
                                    statusLabel.text = "Connected!"
                                    credentialStore.storeToken(serviceType, token)
                                    urlSaver(url)
                                }
                                is ApiResult.Error -> {
                                    statusLabel.text = "Failed: ${result.message}"
                                }
                            }
                        }
                    }
                }
                cell(statusLabel)
            }
        }
    }
}
```

- [ ] **Step 3: Write OnboardingStartupListener**

Create `core/src/main/kotlin/com/workflow/orchestrator/core/onboarding/OnboardingStartupListener.kt`:

```kotlin
package com.workflow.orchestrator.core.onboarding

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.GotItTooltip
import java.awt.Point

class OnboardingStartupListener : ProjectActivity {

    override suspend fun execute(project: Project) {
        val onboarding = OnboardingService.getInstance(project)
        if (!onboarding.shouldShowOnboarding()) return

        // Show GotItTooltip anchored to the Workflow tool window stripe button
        com.intellij.openapi.application.invokeLater {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Workflow") ?: return@invokeLater

            val tooltip = GotItTooltip(
                "workflow.orchestrator.onboarding",
                "Connect your development tools to get started.",
                project
            )
            tooltip.withHeader("Welcome to Workflow Orchestrator!")
            tooltip.withLink("Start Setup") {
                onboarding.showSetupDialog()
            }

            toolWindow.component?.let { component ->
                tooltip.show(component, GotItTooltip.BOTTOM)
            }

            onboarding.markOnboardingShown()
        }
    }
}
```

- [ ] **Step 4: Verify plugin.xml already has correct registration**

The `plugin.xml` from Task 3 already registers `OnboardingStartupListener` via `<postStartupActivity>`. No changes needed.

- [ ] **Step 5: Verify compilation**

Run:
```bash
./gradlew :core:classes
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/onboarding/
git add core/src/main/resources/META-INF/plugin.xml
git commit -m "feat(core): add onboarding with GotItTooltip and setup dialog"
```

---

## Chunk 6: Integration & Verification

### Task 16: Full plugin build and runIde verification

- [ ] **Step 1: Run full test suite**

Run:
```bash
./gradlew :core:test
```
Expected: All tests PASS (ApiResult: 5, CredentialStore: 4, AuthInterceptor: 3, RetryInterceptor: 4, HttpClientFactory: 3, AuthTestService: 5, ConnectivityMonitor: 6 = 30 tests total).

- [ ] **Step 2: Run plugin verifier**

Run:
```bash
./gradlew verifyPlugin
```
Expected: No compatibility problems reported.

- [ ] **Step 3: Build the plugin artifact**

Run:
```bash
./gradlew buildPlugin
```
Expected: Plugin ZIP created in `build/distributions/intellij-workflow-orchestrator-0.1.0.zip`.

- [ ] **Step 4: Test in IDE**

Run:
```bash
./gradlew runIde
```
Expected:
1. IntelliJ IDEA opens with the plugin loaded
2. "Workflow" tool window button appears at the bottom
3. Clicking it shows 5 tabs (Sprint, Build, Quality, Automation, Handover) with empty states
4. GotItTooltip appears on first open prompting setup
5. Settings > Tools > Workflow Orchestrator > Connections shows 6 service sections
6. Each section has URL field, token field, and "Test Connection" button

- [ ] **Step 5: Final commit**

```bash
git add -A
git commit -m "chore: Phase 1A foundation complete - plugin boots and connects"
```

---

### Task 17: Create .gitignore

**Files:**
- Create: `.gitignore`

- [ ] **Step 1: Write .gitignore**

```gitignore
# Gradle
.gradle/
build/
!gradle/wrapper/gradle-wrapper.jar
!**/src/main/**/build/
!**/src/test/**/build/

# IntelliJ
.idea/
*.iml
*.iws
*.ipr
out/

# OS
.DS_Store
Thumbs.db

# Kotlin
*.class

# Plugin
/run/
```

- [ ] **Step 2: Commit**

```bash
git add .gitignore
git commit -m "chore: add .gitignore"
```

---

## Gate 1 Acceptance Criteria

After completing all 17 tasks, the plugin meets these criteria:

| Criterion | Verification |
|---|---|
| Plugin installs in IntelliJ 2025.1+ | `./gradlew runIde` launches without errors |
| "Workflow" tool window appears (bottom dock) | Visual check: 5 tabs visible |
| Empty state messages shown for each tab | Visual check: messages match WorkflowBundle |
| Settings page exists under Tools | Settings > Tools > Workflow Orchestrator > Connections |
| 6 service connections configurable | URL + token fields for Jira, Bamboo, Bitbucket, SonarQube, Sourcegraph, Nexus |
| "Test Connection" works for each service | MockWebServer tests pass + manual test with real endpoints |
| Credentials stored in OS keychain | PasswordSafe integration test passes |
| Onboarding tooltip appears on first run | Visual check in `runIde` |
| Setup dialog allows partial configuration | Can connect 1 service and skip others |
| Offline detection tracks per-service | ConnectivityMonitor unit tests pass |
| HTTP clients reuse connections with auth | HttpClientFactory tests pass |
| Retry with exponential backoff | RetryInterceptor tests pass |
| 30 unit tests pass | `./gradlew :core:test` |
| Plugin verifier passes | `./gradlew verifyPlugin` |
| Plugin ZIP builds | `./gradlew buildPlugin` |
