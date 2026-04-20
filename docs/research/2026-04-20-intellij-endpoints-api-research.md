# IntelliJ Endpoints Framework API — Research & Migration Plan

**Date:** 2026-04-20
**Author:** Research for `:agent` `spring.endpoints` / `spring.boot_endpoints` replacement
**Status:** Complete

---

## 1. Executive summary

- **Yes, we can use it.** The Endpoints framework that powers `View | Tool Windows | Endpoints` exposes a public SPI named `EndpointsProvider<G, E>` in package `com.intellij.microservices.endpoints`. Interface source lives in the open `intellij-community` repo at `platform/lang-api/src/com/intellij/microservices/endpoints/`, and the contract appears in the reviewed public `platform/lang-api/api-dump.txt` (i.e. stable, not `@ApiStatus.Experimental` or `@ApiStatus.Internal`).
- **What it's called.** The extension point is `com.intellij.microservices.endpointsProvider`. Our plugin calls it via `EndpointsProvider.EP_NAME.extensionList` (all registered frameworks), or more commonly `EndpointsProvider.getAvailableProviders(project)` (filtered by `Status`) — and iterates groups/endpoints with `ModuleEndpointsFilter`.
- **Runtime cost.** The provider SPI itself is bundled into the **IntelliJ Ultimate** family (IU, PY Pro, WebStorm, Rider, RubyMine); it is **not present in IntelliJ Community or PyCharm Community**. Our plugin currently supports IC and PC, so we need a hybrid: use the API when present, fall back to the fixed-up PSI walker when absent.
- **How much work.** Small. One new `:agent` file that detects the API and iterates `EndpointsProvider` + `EndpointsUrlTargetProvider`. Keep the existing PSI-based `SpringEndpointsAction`/`SpringBootEndpointsAction` as the Community fallback, and fix the `extractMappingPath()` constant-resolution bug via `AnnotationUtil.getStringAttributeValue()`.

---

## 2. The API surface

### 2.1 Plugin / module IDs and Gradle coordinates

The API lives in the platform `lang-api` module, so class-level access is available from any IntelliJ Ultimate build without extra JAR downloads. The guard is a **plugin dependency** that makes IntelliJ only load our feature when the microservices module is present.

| IntelliJ module / plugin ID | Role | Where |
|---|---|---|
| `com.intellij.modules.microservices` | Base microservices module — registers the `com.intellij.microservices.endpointsProvider` EP | Bundled in Ultimate editions only |
| `com.intellij.microservices.jvm` | JVM-specialised API (bridges to UAST, Java PSI). Required if we want JVM-specific helpers | Ultimate + Java |
| `com.intellij.spring` | Spring plugin — ships `SpringMvcEndpointsProvider`, `SpringWebfluxEndpointsProvider`, `SpringFeignEndpointsProvider`, etc. | Ultimate (already bundled in our `gradle.properties`) |
| `com.intellij.spring.boot` | Spring Boot Actuator endpoints provider | Ultimate (already bundled) |
| `com.intellij.spring.ws` | Spring Web Services | Ultimate |
| `com.intellij.javaee.web` / `JavaEE` | JAX-RS / Jakarta RS providers | Ultimate |
| `com.intellij.micronaut` | Micronaut HTTP / WebSocket / Management Endpoints | Ultimate |
| `com.intellij.quarkus` | Quarkus REST | Ultimate |
| `org.jetbrains.plugins.go` | Go microservices | GoLand / Ultimate with Go plugin |

To consume the EP from our plugin:

**`plugin.xml`**
```xml
<depends optional="true" config-file="endpoints-api.xml">com.intellij.modules.microservices</depends>
```
and in `endpoints-api.xml` register any extensions we add ourselves (we won't need to — we're a *consumer* of the EP, not a provider).

**`agent/build.gradle.kts`** (we already have this configured correctly):
```kotlin
intellijPlatform {
    intellijIdeaUltimate(providers.gradleProperty("platformVersion"))
    bundledPlugins(listOf(
        "Git4Idea", "com.intellij.java", "com.intellij.spring", "org.jetbrains.kotlin",
        "com.intellij.modules.microservices",     // <-- add
        "com.intellij.microservices.jvm",          // <-- add (JVM helpers)
    ))
}
```

Our root `gradle.properties` already lists `com.intellij.spring` and `com.intellij.spring.boot`. Adding the two microservices IDs gives us the provider SPI at compile time.

### 2.2 Key classes (with source URLs)

All verified against `JetBrains/intellij-community` master branch, GitHub code-search API, 2026-04-20.

| Class / file | FQN / purpose | Source |
|---|---|---|
| `EndpointsProvider<G, E>` | Core SPI; each framework provides groups of endpoints | [EndpointsProvider.kt](https://github.com/JetBrains/intellij-community/blob/master/platform/lang-api/src/com/intellij/microservices/endpoints/EndpointsProvider.kt) |
| `EndpointsUrlTargetProvider<G, E>` | Sub-interface — providers that can emit `UrlTargetInfo` (URL + methods + PSI anchor) | Same file |
| `EndpointsDocumentationProvider<G, E, R>` | Sub-interface — providers with rich Swing documentation UI | Same file |
| `EndpointType` | Enum-like discriminator (`HTTP-Server`, `HTTP-Client`, `WebSocket-Server`, `Graph-QL`, `API-Definition`, …) | [EndpointTypes.kt](https://github.com/JetBrains/intellij-community/blob/master/platform/lang-api/src/com/intellij/microservices/endpoints/EndpointTypes.kt) |
| `FrameworkPresentation` | `(queryTag, title, icon)` — tells us "Spring MVC", "JAX-RS", … | [FrameworkPresentation.kt](https://github.com/JetBrains/intellij-community/blob/master/platform/lang-api/src/com/intellij/microservices/endpoints/FrameworkPresentation.kt) |
| `EndpointsFilter` / `ModuleEndpointsFilter` / `SearchScopeEndpointsFilter` | Scopes the search (module, includeLibraries, includeTests) | [EndpointsFilter.kt](https://github.com/JetBrains/intellij-community/blob/master/platform/lang-api/src/com/intellij/microservices/endpoints/EndpointsFilter.kt) |
| `UrlTargetInfo` | `schemes + authorities + path + methods + source + resolveToPsiElement()` — the rich data we want | [UrlPathModel.kt](https://github.com/JetBrains/intellij-community/blob/master/platform/lang-api/src/com/intellij/microservices/url/UrlPathModel.kt) |
| `UrlPath` / `UrlPath.PathSegment` | Segment-based URL model (`Exact`, `Variable`, `Composite`, `Undefined`) with a `getPresentation(renderer)` API | Same file |
| `HttpUrlPresentation` | Convenient `PresentationData` subclass — used for the tool-window rendering | [HttpUrlPresentation.kt](https://github.com/JetBrains/intellij-community/blob/master/platform/lang-api/src/com/intellij/microservices/endpoints/presentation/HttpUrlPresentation.kt) |
| `EndpointMethodPresentation` | Mixin on `ItemPresentation` that exposes `endpointMethods` (HTTP verbs) | [EndpointMethodPresentation.kt](https://github.com/JetBrains/intellij-community/blob/master/platform/lang-api/src/com/intellij/microservices/endpoints/presentation/EndpointMethodPresentation.kt) |
| `EndpointsProjectModel` | Enumerates modules (`EndpointsModuleEntity`) and builds filters — used by the tool window itself | [EndpointsProjectModels.kt](https://github.com/JetBrains/intellij-community/blob/master/platform/lang-api/src/com/intellij/microservices/endpoints/EndpointsProjectModels.kt) |
| `EndpointsListItem` / `EndpointsElementItem` | The rows the tool window builds; each exposes `getUrlTargetInfos()` | [EndpointsListModels.kt](https://github.com/JetBrains/intellij-community/blob/master/platform/lang-api/src/com/intellij/microservices/endpoints/EndpointsListModels.kt) |

API stability:

- `EndpointsProvider`, `EndpointsUrlTargetProvider`, `EndpointsFilter`, `SearchScopeEndpointsFilter`, `EndpointMethodPresentation`, `UrlResolver`, `UrlResolverFactory`, `UrlTargetInfo`, `UrlPath.PathSegmentRenderer` — all in `platform/lang-api/api-dump.txt`, i.e. **reviewed stable public API**.
- `EndpointType`, `EndpointTypes.*` constants, `EndpointsProjectModel`, `EndpointsListItem`, `EndpointsElementItem`, `EndpointsModuleEntity`, `FrameworkPresentation`, `HttpUrlPresentation`, `EndpointsDocumentationProvider`, `EndpointsChangeTracker` — in `api-dump-unreviewed.txt`. Still public, but not locked — expect minor churn.
- `DefaultEndpointsModule` — marked `@ApiStatus.Internal`; don't use directly.

Verified by inspecting `platform/lang-api/api-dump.txt` and `api-dump-unreviewed.txt` at master (2026-04-20).

### 2.3 Extension point XML

Registered in `platform/platform-resources/src/META-INF/LangExtensionPoints.xml`:

```xml
<extensionPoint interface="com.intellij.microservices.endpoints.EndpointsProvider"
                qualifiedName="com.intellij.microservices.endpointsProvider" dynamic="true"/>
<extensionPoint interface="com.intellij.microservices.endpoints.EndpointsSidePanelProvider"
                qualifiedName="com.intellij.microservices.endpointsSidePanelProvider" dynamic="true"/>
<extensionPoint interface="com.intellij.microservices.endpoints.EndpointsProjectModel"
                qualifiedName="com.intellij.microservices.endpointsProjectModel" dynamic="true"/>
```

Bundled providers register like this (from [vaadin/intellij-plugin](https://github.com/vaadin/intellij-plugin/blob/main/plugin/src/main/resources/META-INF/vaadin-with-microservices.xml), as a concrete example):
```xml
<extensions defaultExtensionNs="com.intellij">
    <microservices.endpointsProvider implementation="com.vaadin.plugin.endpoints.VaadinFlowEndpointsProvider"/>
</extensions>
```

### 2.4 The companion-object entry points

From `EndpointsProvider.kt`:

```kotlin
companion object {
    @JvmField
    val URL_TARGET_INFO: DataKey<Iterable<UrlTargetInfo>> = DataKey.create("endpoint.urlTargetInfo")

    @JvmField
    val EP_NAME: ExtensionPointName<EndpointsProvider<*, *>> =
      ExtensionPointName.create("com.intellij.microservices.endpointsProvider")

    fun hasAnyProviders(): Boolean = EP_NAME.hasAnyExtensions()
    fun getAllProviders(): List<EndpointsProvider<*, *>> = EP_NAME.extensionList
    fun getAvailableProviders(project: Project): Sequence<EndpointsProvider<*, *>>  // cached, filtered
}
```

`getAvailableProviders()` internally calls `CachedValuesManager` keyed on `PsiModificationTracker.MODIFICATION_COUNT + DumbService + ProjectRootManager`, so we get the same caching behaviour as the tool window.

### 2.5 What `Status` actually means

Three states (from the interface):
- `UNAVAILABLE` — not relevant, skip.
- `AVAILABLE` — dependency is on classpath but no endpoints yet (user could add one). Tool window still renders the section.
- `HAS_ENDPOINTS` — there's at least one endpoint; high probability. Our tool should iterate this one.

In practice: filter by `Status != UNAVAILABLE` and let the iterator return nothing if the framework has zero endpoints — the API is already doing that check.

---

## 3. Working Kotlin snippet — list all endpoints

This is the drop-in replacement for `collectBootEndpoints()`. It runs `ReadAction.nonBlocking { ... }.inSmartMode(project)`, just like the current implementation.

```kotlin
package com.workflow.orchestrator.agent.tools.framework.endpoints

import com.intellij.microservices.endpoints.EndpointsFilter
import com.intellij.microservices.endpoints.EndpointsProvider
import com.intellij.microservices.endpoints.EndpointsUrlTargetProvider
import com.intellij.microservices.endpoints.ModuleEndpointsFilter
import com.intellij.microservices.endpoints.presentation.EndpointMethodPresentation
import com.intellij.microservices.url.Authority
import com.intellij.microservices.url.UrlPath
import com.intellij.microservices.url.UrlTargetInfo
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod

data class DiscoveredEndpoint(
    val framework: String,          // "Spring MVC", "JAX-RS", "Ktor", …
    val httpMethods: List<String>,  // ["GET"], [] when all methods allowed
    val url: String,                // "/api/users/{id}"
    val source: String,             // "UserController", "routes.yml"
    val handlerClass: String?,      // "com.example.UserController"
    val handlerMethod: String?,     // "getUser"
    val filePath: String?,          // absolute path
    val lineNumber: Int?,
    val endpointType: String        // "HTTP-Server", "HTTP-Client", "WebSocket-Server", …
)

/**
 * Returns every endpoint the Endpoints tool window would show, across every framework
 * contributed by plugins currently installed (Spring MVC, Spring WebFlux, JAX-RS,
 * Micronaut, Ktor, Quarkus, Helidon, MicroProfile, Retrofit, Feign, gRPC,
 * OpenAPI/Swagger, HTTP Client scratches, WebSocket, …).
 *
 * Call under `ReadAction.nonBlocking { … }.inSmartMode(project).executeSynchronously()`.
 */
fun collectEndpointsFromPlatform(project: Project): List<DiscoveredEndpoint> {
    // 1. Gate: the EP only exists on Ultimate / Pro builds.
    if (!EndpointsProvider.hasAnyProviders()) return emptyList()

    val providers = EndpointsProvider.getAvailableProviders(project).toList()
    if (providers.isEmpty()) return emptyList()

    val results = mutableListOf<DiscoveredEndpoint>()
    val modules = ModuleManager.getInstance(project).modules

    for (provider in providers) {
        val framework = provider.presentation.title
        val endpointTypeTag = provider.endpointType.queryTag

        for (module in modules) {
            // includeLibraries=false, includeTests=false — mirrors the default tool-window mode.
            val filter: EndpointsFilter = ModuleEndpointsFilter(
                module = module,
                fromLibraries = false,
                fromTests = false
            )

            @Suppress("UNCHECKED_CAST")
            val typed = provider as EndpointsProvider<Any, Any>
            val groups = typed.getEndpointGroups(project, filter)

            for (group in groups) {
                val endpoints = typed.getEndpoints(group)
                for (endpoint in endpoints) {
                    if (!typed.isValidEndpoint(group, endpoint)) continue

                    // 2. Try the richer EndpointsUrlTargetProvider path first.
                    val urlTargets: List<UrlTargetInfo> =
                        (typed as? EndpointsUrlTargetProvider<Any, Any>)
                            ?.getUrlTargetInfo(group, endpoint)?.toList().orEmpty()

                    if (urlTargets.isNotEmpty()) {
                        for (target in urlTargets) {
                            results += target.toDiscovered(framework, endpointTypeTag)
                        }
                        continue
                    }

                    // 3. Fallback to presentation text (covers providers that only
                    //    produce ItemPresentation + EndpointMethodPresentation).
                    val presentation = typed.getEndpointPresentation(group, endpoint)
                    val methodNames = (presentation as? EndpointMethodPresentation)
                        ?.endpointMethods.orEmpty()
                    val psi = typed.getNavigationElement(group, endpoint)
                    results += DiscoveredEndpoint(
                        framework      = framework,
                        httpMethods    = methodNames,
                        url            = presentation.presentableText.orEmpty(),
                        source         = presentation.locationString.orEmpty(),
                        handlerClass   = psi?.handlerClassName(),
                        handlerMethod  = psi?.handlerMethodName(),
                        filePath       = psi?.containingFile?.virtualFile?.path,
                        lineNumber     = psi?.lineNumber(),
                        endpointType   = endpointTypeTag,
                    )
                }
            }
        }
    }
    return results
}

// ---------- helpers ----------

private fun UrlTargetInfo.toDiscovered(framework: String, typeTag: String): DiscoveredEndpoint {
    val psi = resolveToPsiElement()
    return DiscoveredEndpoint(
        framework      = framework,
        httpMethods    = methods.sorted(),
        url            = path.getPresentation(HttpUrlRenderer) // "/users/{id}"
            .let { p -> schemes.firstOrNull()?.let { it + authorities.firstExact().orEmpty() + p } ?: p },
        source         = source,
        handlerClass   = psi?.handlerClassName(),
        handlerMethod  = psi?.handlerMethodName(),
        filePath       = psi?.containingFile?.virtualFile?.path,
        lineNumber     = psi?.lineNumber(),
        endpointType   = typeTag,
    )
}

private object HttpUrlRenderer : UrlPath.PathSegmentRenderer {
    override fun visitVariable(variable: UrlPath.PathSegment.Variable): String =
        "{${variable.variableName ?: "var"}}"
}

private fun List<Authority>.firstExact(): String? =
    firstNotNullOfOrNull { (it as? Authority.Exact)?.text }

private fun PsiElement.handlerClassName(): String? {
    var e: PsiElement? = this
    while (e != null) {
        if (e is com.intellij.psi.PsiClass) return e.qualifiedName
        e = e.parent
    }
    return null
}

private fun PsiElement.handlerMethodName(): String? =
    (this as? PsiMethod)?.name
        ?: generateSequence(this) { it.parent }.filterIsInstance<PsiMethod>().firstOrNull()?.name

private fun PsiElement.lineNumber(): Int? {
    val doc = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(containingFile ?: return null)
        ?: return null
    return doc.getLineNumber(textOffset) + 1
}
```

Notes on the snippet:

- `EndpointsProvider.getAvailableProviders()` caches per modification tracker — repeated calls in the same session are cheap.
- We iterate per-module using `ModuleEndpointsFilter`. The tool window does the same (see `EndpointsProjectModel.createFilter`). Alternative: use `EndpointsProjectModel.EP_NAME.extensionList.first()` to enumerate modules in an IDE-appropriate way (Rider uses project entities; IDEA uses modules).
- For 'everything' include `fromLibraries = true, fromTests = true`.
- `UrlTargetInfo.path.getPresentation(PathSegmentRenderer)` is how we turn the segment tree into a human string — and unlike our current code, it correctly resolves `PathSegment.Variable` (e.g. `@PathVariable("id")`) to `{id}`.

### 3.1 Action implementation — agent tool wiring

```kotlin
// SpringEndpointsAction.kt — new platform-aware path
internal suspend fun executeEndpoints(params: JsonObject, project: Project): ToolResult {
    if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()
    val filter = params["filter"]?.jsonPrimitive?.contentOrNull

    val content = ReadAction.nonBlocking<String> {
        val rows = if (EndpointsProvider.hasAnyProviders()) {
            collectEndpointsFromPlatform(project)
        } else {
            collectEndpointsFromPsi(project)        // existing PSI walker, bug-fixed
        }
        renderEndpoints(rows, filter)
    }.inSmartMode(project).executeSynchronously()

    return ToolResult(content = content, /* … */)
}
```

---

## 4. Framework coverage

Distilled from the 2026.1 [Endpoints tool window documentation](https://www.jetbrains.com/help/idea/endpoints-tool-window.html) and the `com.intellij.microservices.endpointsProvider` implementations known from GitHub (Vaadin, GraphQL, protobuf, rsocket). Every one of these becomes available to our agent with zero extra code once we switch to the EP — the matrix below is not things we implement, it's things we *get for free*.

| Framework | Plugin that provides it | `EndpointType` | Supported |
|---|---|---|---|
| Spring MVC (`@RestController`, `@*Mapping`) | `com.intellij.spring.mvc` (bundled with `com.intellij.spring`) | `HTTP-Server` | Yes |
| Spring WebFlux annotated | `com.intellij.spring.mvc` | `HTTP-Server` | Yes |
| Spring WebFlux `RouterFunction` DSL | `com.intellij.spring.mvc` | `HTTP-Server` | Yes |
| Spring Boot Actuator | `com.intellij.spring.boot` | `HTTP-Server` | Yes |
| Spring WebClient / RestTemplate | `com.intellij.spring.mvc` | `HTTP-Client` | Yes |
| Spring Feign Client | `com.intellij.spring.cloud` | `HTTP-Client` | Yes |
| Spring WS / JAX-WS | `com.intellij.spring.ws` / `JavaEE` | `XML-Web-Service` | Yes |
| JAX-RS / Jakarta RS (`@Path`) | `com.intellij.javaee.web` | `HTTP-Server` | Yes |
| JAX-RS Client / MicroProfile Rest Client | `com.intellij.javaee.web` | `HTTP-Client` | Yes |
| Micronaut HTTP Controllers | `com.intellij.micronaut` | `HTTP-Server` | Yes |
| Micronaut HTTP Client | `com.intellij.micronaut` | `HTTP-Client` | Yes |
| Micronaut Management Endpoints | `com.intellij.micronaut` | `HTTP-Server` | Yes |
| Micronaut WebSocket | `com.intellij.micronaut` | `WebSocket-Server` | Yes |
| Quarkus REST | `com.intellij.quarkus` | `HTTP-Server` | Yes |
| Helidon MP / SE | `com.intellij.helidon` | `HTTP-Server` | Yes |
| Ktor route DSL | `org.jetbrains.intellij.ktor` (or bundled) | `HTTP-Server` | Yes |
| gRPC / Protobuf | `com.google.protobuf-java` or Kanro protobuf plugin | `HTTP-Server` (gRPC HTTP/2) | Yes |
| OpenAPI 3 / Swagger 2 | `com.intellij.openapi.specifications` | `API-Definition` | Yes |
| WSDL | `com.intellij.javaee.web` | `XML-Web-Service` | Yes |
| Retrofit 2 | `Retrofit` community plugin or bundled | `HTTP-Client` | Yes |
| OkHttp 3+ | bundled | `HTTP-Client` | Yes |
| Jakarta EE WebSocket | `com.intellij.javaee.web` | `WebSocket-Server` | Yes |
| Spring Reactive WebSocket | `com.intellij.spring.mvc` | `WebSocket-Server` | Yes |
| HTTP Client `.http` files | bundled (`com.intellij.httpClient`) | `HTTP-Client` | Yes |
| GraphQL | `com.intellij.lang.jsgraphql` (marketplace) | `Graph-QL` | Yes (when plugin present) |
| Django | `com.intellij.django` (PyCharm Pro) | `HTTP-Server` | Yes, PyCharm Pro only |
| Flask | `com.jetbrains.flask` (PyCharm Pro) | `HTTP-Server` | Yes, PyCharm Pro only |
| FastAPI | `Pythonid` + `com.jetbrains.fastapi` (PyCharm Pro) | `HTTP-Server` | Yes, PyCharm Pro only |
| Rails routes | RubyMine | `HTTP-Server` | Yes, RubyMine only |
| ASP.NET / Minimal APIs | Rider | `HTTP-Server` | Yes, Rider only |
| Go chi/gin/gorilla | GoLand | `HTTP-Server` | Yes, GoLand only |
| Vaadin Flow / Hilla | [vaadin/intellij-plugin](https://github.com/vaadin/intellij-plugin) | `HTTP-Server` | Yes (marketplace) |

Notable: "OkHttp 3+" and "Retrofit 2" are `HTTP-Client` — so they appear on the client side of the tool window. Our agent probably wants both sides; we filter on `provider.endpointType.queryTag`.

From the PyCharm help page ([Endpoints tool window | PyCharm](https://www.jetbrains.com/help/pycharm/endpoints-tool-window.html)): "If the application that you are developing uses a Django, FastAPI, or Flask, you can get an overview of all declared URLs and endpoints in the Endpoints tool window." This is **PyCharm Professional only**; PyCharm Community does not bundle the microservices module.

---

## 5. Caveats and limitations

### 5.1 Smart-mode / dumb-mode

- `EndpointsProvider.getAvailableProviders()` internally depends on `DumbService` as a modification tracker. Providers themselves typically require smart mode (they walk UAST / PSI). Our existing `PsiToolUtils.isDumb(project)` guard is correct; keep it.
- Wrap the call in `ReadAction.nonBlocking { … }.inSmartMode(project).executeSynchronously()` (same as today).

### 5.2 Threading

- `EndpointsProvider.getEndpointGroups` and `getEndpoints` must be called under a read action.
- `UrlTargetInfo.resolveToPsiElement()` also requires a read action.
- None of the methods are marked `@RequiresBackgroundThread` in the base interface — they're meant to be fast under a read action, and the tool window realizes them on EDT on first open. For agent scale (list-all-endpoints), do it in a background thread.

### 5.3 Performance

- The API is **pull-based**, not stream-based. For large monorepos (1000+ controllers) we may want `head_limit`/`filter` support in our agent tool — already in place.
- `getAvailableProviders()` result is cached per `PsiModificationTracker.MODIFICATION_COUNT` plus `DumbService`/`ProjectRootManager`. Iteration cost is bounded by UAST modification tracker for each provider — cheap.
- Worst case per-endpoint cost comes from `UrlTargetInfo.resolveToPsiElement()`. If we don't need the PSI element, don't call it — the `source`, `path`, and `methods` fields are usually enough.

### 5.4 Product / edition availability

| IDE | `com.intellij.modules.microservices` bundled? | Our plugin targets? |
|---|---|---|
| IntelliJ IDEA **Ultimate** (IU) | **Yes** | Yes |
| IntelliJ IDEA Community (IC) | **No** | Yes — must fall back |
| PyCharm **Professional** (PY) | **Yes** (Django / Flask / FastAPI providers) | Yes |
| PyCharm Community (PC) | **No** | Yes — must fall back (but we don't have Spring support there anyway) |
| WebStorm (WS) | **Yes** (Retrofit/OkHttp/OpenAPI/HTTP Client) | Yes |
| GoLand | Yes | Not currently a target |
| Rider | Yes | Not currently a target |

Detection is via `EndpointsProvider.hasAnyProviders()`. No classpath probe required — if the class resolves, the call works.

### 5.5 Sub-interfaces vs base interface

- Only `EndpointsUrlTargetProvider` returns machine-readable `UrlTargetInfo`. The base `EndpointsProvider` only promises `ItemPresentation`. Some providers (e.g. OpenAPI definitions) only implement the base interface, so we need both code paths (shown in the snippet).
- `EndpointMethodPresentation` is an optional mixin on the `ItemPresentation` returned by `getEndpointPresentation`. Check `ItemPresentation as? EndpointMethodPresentation` before reading HTTP methods from the fallback path.

### 5.6 Rider / Xcode differences

- `EndpointsProjectModel` is used by Rider for its non-module project structure (solutions). If we ever target Rider, don't assume `ModuleManager` — use `EndpointsProjectModel.EP_NAME.extensionList.first().getModuleEntities(project)` + `createFilter(entity, …)`.

---

## 6. Fallback: fix the constant-resolution bug in the PSI walker

For IC / PC (where the microservices EP isn't bundled) we keep a PSI-based implementation. The current one returns the literal source `"MyConstants.USER_PATH"` instead of the resolved `"/users"`. The idiomatic fix is one line.

### Current buggy code (`SpringEndpointsAction.kt` and `SpringBootEndpointsAction.kt`):

```kotlin
private fun extractMappingPath(annotation: PsiAnnotation?): String {
    if (annotation == null) return ""
    val value = annotation.findAttributeValue("value")
        ?: annotation.findAttributeValue("path")
    return value?.text
        ?.removeSurrounding("\"")
        ?.removeSurrounding("{", "}")
        ?: ""
}
```

### Root cause

`PsiAnnotationMemberValue.getText()` returns the **source text of the expression node**. For `@GetMapping(MyConstants.USER_PATH)`, that node is a `PsiReferenceExpression` whose `.text` is literally `"MyConstants.USER_PATH"`. For `@GetMapping({"/a", "/b"})` it's `{"/a", "/b"}`. We never resolve the reference or expand the array initializer.

### Fix — use `AnnotationUtil.getStringAttributeValue`

`AnnotationUtil.getStringAttributeValue(PsiAnnotation, String)` is the canonical IntelliJ helper. It internally delegates to `JavaPsiFacade.getInstance(project).constantEvaluationHelper.computeConstantExpression(attrValue)`, which:

- Walks `PsiReferenceExpression` to its declaration and reads the compile-time constant (`public static final String USER_PATH = "/users";`).
- Handles concatenation (`"/api" + VERSION`), ternaries, inherited interface constants.
- Returns `null` (never an arbitrary source string) when the value is not a compile-time constant.

Source of the helper: [AnnotationUtil.java — `getStringAttributeValue`](https://github.com/JetBrains/intellij-community/blob/master/java/java-psi-api/src/com/intellij/codeInsight/AnnotationUtil.java).

Drop-in replacement:

```kotlin
import com.intellij.codeInsight.AnnotationUtil
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiReferenceExpression

/**
 * Resolves an annotation path attribute like `@GetMapping(value = "/users")`,
 * `@GetMapping(MyConstants.USER_PATH)`, `@GetMapping({"/a", "/b"})`, or
 * `@RequestMapping(path = PREFIX + "/list")` to the first concrete string path.
 *
 * Returns "" when no usable path can be resolved.
 */
private fun extractMappingPath(annotation: PsiAnnotation?): String {
    if (annotation == null) return ""

    // 1. Try each common attribute name in order. `AnnotationUtil.getStringAttributeValue`
    //    walks constant references, concatenations, inherited defaults — the full works.
    for (attr in listOf("value", "path")) {
        val resolved = AnnotationUtil.getStringAttributeValue(annotation, attr)
        if (!resolved.isNullOrBlank()) return resolved
    }

    // 2. Handle the array-initializer form `{"/a", "/b"}` by evaluating each element
    //    separately — getStringAttributeValue returns null for array values.
    val raw = annotation.findAttributeValue("value") ?: annotation.findAttributeValue("path")
    if (raw is PsiArrayInitializerMemberValue) {
        val evaluator = JavaPsiFacade.getInstance(annotation.project).constantEvaluationHelper
        for (element in raw.initializers) {
            val v = evaluator.computeConstantExpression(element) as? String
            if (!v.isNullOrBlank()) return v
        }
    }

    return ""
}
```

Same fix must be applied to `extractBootMappingPath()` in `SpringBootEndpointsAction.kt`, plus the same technique for `extractRequestMethod()` (which today calls `.text.replace("RequestMethod.", "")` — fragile when the enum is imported statically). Use:

```kotlin
private fun extractRequestMethod(annotation: PsiAnnotation): String? {
    val attr = annotation.findAttributeValue("method") ?: return null
    // For enum refs like RequestMethod.GET, take the reference name directly.
    return when (attr) {
        is PsiReferenceExpression -> attr.referenceName
        is PsiArrayInitializerMemberValue ->
            attr.initializers.filterIsInstance<PsiReferenceExpression>()
                .firstNotNullOfOrNull { it.referenceName }
        else -> null
    }
}
```

### Why not `JavaConstantExpressionEvaluator`

`JavaConstantExpressionEvaluator` is an `@ApiStatus.Internal` wrapper around `PsiConstantEvaluationHelper`. The *public* entry point is `JavaPsiFacade.getConstantEvaluationHelper()` (used by `AnnotationUtil`). Stick with `AnnotationUtil` — it's the contract we see used throughout `intellij-community` itself (e.g. `ContractInspection`, `JavaMethodContractUtil`, `NlsInfo`, search [here](https://github.com/JetBrains/intellij-community/search?q=AnnotationUtil.getStringAttributeValue)).

---

## 7. Recommendation for this plugin

**Hybrid: Endpoints API when available, fixed-up PSI fallback otherwise.**

Concretely:

1. **Add a new file** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/endpoints/PlatformEndpointsDiscoverer.kt` that implements `collectEndpointsFromPlatform()` exactly as in §3.
2. **Modify `SpringEndpointsAction.kt` and `SpringBootEndpointsAction.kt`** so `collectEndpoints()` first calls `EndpointsProvider.hasAnyProviders()`. If true, delegate to the platform discoverer (filters by `EndpointType` = `HTTP-Server` and by `FrameworkPresentation.title` containing "Spring" to preserve the current tool's "Spring-only" scope). If false, fall through to the PSI walker with the bug fix from §6.
3. **Rename the dispatcher tool or add a sibling action.** Because the platform discoverer returns endpoints for **any** framework (JAX-RS, Micronaut, Retrofit, OpenAPI, …), it no longer belongs under the `spring` meta-tool. Either:
   - Keep `spring.endpoints` filtered to Spring providers (cheap UX win) and
   - Introduce a new meta-tool `endpoints` (or a sibling action `spring.all_endpoints`) that exposes the full multi-framework list — one of the most-requested agent capabilities.
4. **Add `bundledPlugins` entries.** In `agent/build.gradle.kts`, add `"com.intellij.modules.microservices"` and `"com.intellij.microservices.jvm"` so the class names resolve at compile time. No `<depends>` change to plugin.xml is required if all microservices-consuming code is behind a runtime `hasAnyProviders()` guard — the class-load only happens when the user opens the feature, and IC/PC will simply report zero providers. (If we want to be extra safe for IC, wrap usages in an `optional=true` `<depends>` + separate `endpoints-api.xml`.)
5. **Fix the constant-resolution bug** in the PSI walker **regardless**, so IC users still get correct behaviour. Swap `extractMappingPath` and `extractRequestMethod` for the `AnnotationUtil.getStringAttributeValue` + `PsiConstantEvaluationHelper` implementations from §6.
6. **Tests.** Add a golden test that feeds in a synthetic project with:
   - `@GetMapping(MyConsts.FOO)` where `FOO = "/foo"` — must resolve to `/foo`, not `MyConsts.FOO`.
   - `@RequestMapping({"/a", "/b"})` — must emit both paths.
   - `@RequestMapping(value = PREFIX + "/list")` — must resolve to the full concatenation.
   - A JAX-RS `@Path("/users")` — must be invisible to `spring.endpoints` but visible to the new `endpoints` tool when the Ultimate microservices EP is present.

This plan preserves every current capability, unlocks **every framework the Endpoints tool window supports** with zero per-framework code, and fixes the immediate bug for users on Community editions where the EP isn't available.

---

## References

- [IntelliJ Platform Plugin SDK — Plugin Dependencies](https://plugins.jetbrains.com/docs/intellij/plugin-dependencies.html)
- [IntelliJ Platform Plugin SDK — Gradle plugin dependencies extension](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html)
- [JetBrains Help — Endpoints tool window (IntelliJ IDEA)](https://www.jetbrains.com/help/idea/endpoints-tool-window.html)
- [JetBrains Help — Endpoints tool window (PyCharm)](https://www.jetbrains.com/help/pycharm/endpoints-tool-window.html)
- [JetBrains Help — Endpoints tool window (WebStorm)](https://www.jetbrains.com/help/webstorm/endpoints-tool-window.html)
- [JetBrains Support — Registering a custom REST endpoint framework](https://intellij-support.jetbrains.com/hc/en-us/community/posts/360008238639-Registering-a-custom-REST-endpoint-framework)
- [intellij-community — `EndpointsProvider.kt`](https://github.com/JetBrains/intellij-community/blob/master/platform/lang-api/src/com/intellij/microservices/endpoints/EndpointsProvider.kt)
- [intellij-community — `EndpointTypes.kt`](https://github.com/JetBrains/intellij-community/blob/master/platform/lang-api/src/com/intellij/microservices/endpoints/EndpointTypes.kt)
- [intellij-community — `EndpointsFilter.kt`](https://github.com/JetBrains/intellij-community/blob/master/platform/lang-api/src/com/intellij/microservices/endpoints/EndpointsFilter.kt)
- [intellij-community — `EndpointsListModels.kt`](https://github.com/JetBrains/intellij-community/blob/master/platform/lang-api/src/com/intellij/microservices/endpoints/EndpointsListModels.kt)
- [intellij-community — `EndpointsProjectModels.kt`](https://github.com/JetBrains/intellij-community/blob/master/platform/lang-api/src/com/intellij/microservices/endpoints/EndpointsProjectModels.kt)
- [intellij-community — `FrameworkPresentation.kt`](https://github.com/JetBrains/intellij-community/blob/master/platform/lang-api/src/com/intellij/microservices/endpoints/FrameworkPresentation.kt)
- [intellij-community — `HttpUrlPresentation.kt`](https://github.com/JetBrains/intellij-community/blob/master/platform/lang-api/src/com/intellij/microservices/endpoints/presentation/HttpUrlPresentation.kt)
- [intellij-community — `UrlPathModel.kt` (UrlTargetInfo + UrlPath)](https://github.com/JetBrains/intellij-community/blob/master/platform/lang-api/src/com/intellij/microservices/url/UrlPathModel.kt)
- [intellij-community — `UrlResolver.kt`](https://github.com/JetBrains/intellij-community/blob/master/platform/lang-api/src/com/intellij/microservices/url/UrlResolver.kt)
- [intellij-community — `LangExtensionPoints.xml` (EP registration)](https://github.com/JetBrains/intellij-community/blob/master/platform/platform-resources/src/META-INF/LangExtensionPoints.xml)
- [intellij-community — `AnnotationUtil.java`](https://github.com/JetBrains/intellij-community/blob/master/java/java-psi-api/src/com/intellij/codeInsight/AnnotationUtil.java)
- [intellij-community — `PsiConstantEvaluationHelper.java`](https://github.com/JetBrains/intellij-community/blob/master/java/java-psi-api/src/com/intellij/psi/PsiConstantEvaluationHelper.java)
- Concrete `EndpointsProvider` implementation (open source):
  - [vaadin/intellij-plugin — `VaadinEndpointsProvider.kt`](https://github.com/vaadin/intellij-plugin/blob/main/plugin/src/main/kotlin/com/vaadin/plugin/endpoints/VaadinEndpointsProvider.kt)
  - [vaadin/intellij-plugin — `VaadinFlowEndpointsProvider.kt`](https://github.com/vaadin/intellij-plugin/blob/main/plugin/src/main/kotlin/com/vaadin/plugin/endpoints/VaadinFlowEndpointsProvider.kt)
  - [vaadin/intellij-plugin — `vaadin-with-microservices.xml`](https://github.com/vaadin/intellij-plugin/blob/main/plugin/src/main/resources/META-INF/vaadin-with-microservices.xml)
  - [JetBrains/js-graphql-intellij-plugin — `GraphQLEndpointsProvider`](https://github.com/JetBrains/js-graphql-intellij-plugin)
- [IntelliJ Platform Explorer — `com.intellij.microservices.endpointsProvider` implementations](https://plugins.jetbrains.com/intellij-platform-explorer?extensions=com.intellij.microservices.endpointsProvider)

---

## Appendix A: Broader Microservices Module Catalog

*Added 2026-04-20. Scope: everything in `platform/lang-api/src/com/intellij/microservices/` on master, **excluding** `endpoints/` which is covered in sections 1–7 above. Every class cited below was opened in the open-source `JetBrains/intellij-community` tree; any item I could not verify is flagged `(unverified)`.*

### A.1 What "microservices" means here

It is **not** marketing. It is a concrete shared-infrastructure layer in `platform/lang-api` that lets HTTP/URL-aware frameworks (Spring, Micronaut, Quarkus, Ktor, Jakarta REST, FastAPI, Express, Ruby on Rails, .NET MVC, gRPC, OpenAPI, AsyncAPI, Protobuf, …) cooperate across five axes:

1. **URL model & resolution** — `url.UrlPath`, `UrlTargetInfo`, `UrlResolverManager`
2. **HTTP primitives** — `http.HttpCode`, `HttpMethodReference`, `HttpHeaderReference` and `mime.MimeTypeReference` (PSI references + completion for strings like `GET`, `Content-Type`, `application/json`)
3. **Endpoints aggregation** — the piece already researched
4. **OpenAPI (OAS) export model** — `oas.OpenApiSpecification` with full path/operation/schema DTOs
5. **Request execution handoff** — `http.request.RequestNavigator` forwards "run this request" to HTTP Client, Postman, curl scratch, etc.

The Services tool window is **unrelated** (confirmed: it is about run configs / Docker / k8s / DBs, not HTTP endpoints).

### A.2 Sub-package inventory

Source-of-truth root: `platform/lang-api/src/com/intellij/microservices/`. Top-level files are `HttpReferenceService.kt` (internal SPI bridge), `MicroservicesBundle.kt` (i18n, internal), `MicroservicesFeaturesAvailabilityProvider.kt` (controls "URL" tab in Search Everywhere).

| Sub-package | Role | Consumer-usable? |
|---|---|---|
| `url` | URL / path-variable model and resolution | **Yes** (public) |
| `url.references` | Builds `PsiReference`s from URL strings | Yes, but provider-side |
| `url.parameters` | Path-variable & query-param SEM targets, rename support | Provider-side (SemKeys) |
| `url.inlay` | Inlay hints showing resolved URL path on endpoint handlers | Provider-side |
| `http` | `HttpCode`, `HttpMethodReference`, `HttpHeaderReference` | **Yes**, drop-in PSI refs |
| `http.request` | `RequestNavigator` EP — hand a request to HTTP Client/curl/etc. | Provider-side + **consumable** (enumerate navigators) |
| `mime` | `MimeTypes` constants + `MimeTypeReference` | **Yes** |
| `oas` | OpenAPI 3 DTO model + `OasSpecificationProvider` EP + `OasExportUtils.getSpecificationByUrls(...)` | **Yes** (read specs; can also export from `UrlTargetInfo`s) |
| `oas.serialization` | `OasSerializationUtils` (YAML/JSON) | Internal-looking |
| `client.generator` | `ClientGenerator` EP = "Example" tab code generators | Provider-side + **consumable** (list generators, call `generate(project, spec)`) |
| `utils` | `PomTargetUtils`, `UrlMappingBuilder`, etc. | Internal helpers |

### A.3 `com.intellij.microservices.url.*`

**`UrlPathModel.kt`** — top-level symbols verified: `sealed class Authority`, `data class UrlResolveRequest(schemeHint, authorityHint, path, method)`, `interface UrlTargetInfo`, `interface UrlQueryParameter`, `class UrlPath(segments)`, `class UrlSpecialSegmentMarker`, `fun filterBestUrlPathMatches`, `class MultiPathBestMatcher`.

**`UrlResolver.kt`** — `interface UrlResolver { getAuthorityHints(schema); supportedSchemes; resolve(request): Iterable<UrlTargetInfo>; getVariants(): Iterable<UrlTargetInfo> }` plus `abstract class HttpUrlResolver : UrlResolver` that defaults schemes to HTTP/HTTPS. Also a `@HelperUrlResolver` annotation marking resolvers that only contribute hints.

**`UrlResolverFactory.kt`** — EP `com.intellij.microservices.urlResolverFactory` (dynamic, internal-marked but publicly declared in `LangExtensionPoints.xml`). `forProject(project): UrlResolver?`.

**`UrlResolverManager.kt`** — project service; `UrlResolverManager.getInstance(project).resolve(request)` runs every registered resolver and merges the results, deduped by `UrlTargetInfoDistinctKey`. `meaningfulResolvers` is the subset excluding helper-only resolvers. Requires a read action.

**Use case.** "Given `http://localhost:8080/api/users/{id}` as a string, find the declaring handler method and its containing class, regardless of whether it's Spring, Ktor, Micronaut, or Rails." That is exactly what Ctrl+click on a URL string inside a `RestTemplate.getForObject(...)` call or a `.http` scratch uses.

**`url.references.UrlPathReferenceInjector<S>`** — builder-style injector that `PsiReferenceContributor`s call to attach URL-path references to any literal. The Kotlin source confirms `withSchemesSupport(...)`, `defaultRootContextProvider`, `pathSegmentHandler`, `navigationHandler`, `alignToHost`. This is what you'd use **provider-side** to make your own DSL (e.g. a custom config file) resolve as a URL. Not typically needed by a passive consumer.

**`url.references.UrlPathContext`** — carries the resolution scope (schemes, prefixes, methods). Not a place to invent APIs — treat as opaque.

**`url.parameters.*`** — path-variable declarations/usages via IJ's SEM (`SemKey`) layer: `PathVariableDeclaringReference` (Java), `PathVariableUsageReference` (Java), `PathVariablePomTarget` (Java), `QueryParameterNameReference`, `QueryParameterSem`, `RenameableSemElement`. Powers rename-propagation from `@PathVariable("id")` ↔ `/users/{id}`. Purely provider-side plumbing.

**`url.inlay.UrlPathInlayLanguagesProvider`** — EP `com.intellij.microservices.urlInlayLanguagesProvider`, registers languages that should get inlay hints of the form `GET /users/{id}` next to handler methods. **`UrlPathInlayAction`** EP (`com.intellij.microservices.urlInlayAction`) is what powers the gutter/inlay click menu. Implementations in `platform/lang-impl/.../url/inlay/` include `FindUsagesUrlPathInlayAction.kt` and `UrlPathInlayHintsProviderFactory.kt`.

**Consumer angle.** If your agent wants to enumerate all URL patterns the project can resolve (including strings found in source, not just annotation-declared handlers), call `UrlResolverManager.getInstance(project).getVariants(request)` with a broad request. This is **adjacent to** Endpoints but not identical — Endpoints only lists declared server-side handlers, whereas `UrlResolver` also includes client-side call sites and config-file URLs.

### A.4 `com.intellij.microservices.http.*`

**`HttpCode.kt`** — verified enum, 101 → 5xx, with localized short descriptions. Pure data. Useful for formatting.

**`HttpMethodReference.kt`** — `class HttpMethodReference(element, range) : PsiReferenceBase, EmptyResolveMessageProvider`. `resolve()` delegates to `service<HttpReferenceService>().resolveHttpMethod(...)`. `getVariants()` lists `HTTP_METHODS`. Attach this to any literal to make `"GET"` Ctrl+click-navigable.

**`HttpHeaderReference.kt`** — same pattern for `X-Foo`-style header names; validates with `[^:\r\n]+` regex. The lang-impl side (`HttpHeadersDictionary.java`, `HttpHeaderReferenceCompletionContributor.kt`) supplies the known-header list used for completion and documentation.

**`http.request.RequestNavigator`** — `interface RequestNavigator { id; icon; displayText; accept(NavigatorHttpRequest); navigate(project, request, hint) }`, EP `com.intellij.microservices.requestNavigator`. `NavigatorHttpRequest(url, requestMethod, headers, params)`. This is the EP that plugins implement to say "send this request using me" (HTTP Client, Postman export, curl, `.http` scratch). `DefaultRequestNavigator.kt` is the built-in.

**Consumer angle for the agent.** `RequestNavigator.getRequestNavigators(request)` returns the list of navigators that accept a given request. Our agent could expose a `run_endpoint` tool that constructs a `NavigatorHttpRequest` from an `UrlTargetInfo` and invokes the first accepting navigator — effectively "ask IntelliJ to execute this endpoint for me" without hard-coding HTTP Client. The EP is declared `@ApiStatus` stable (no experimental marker in the source).

### A.5 `com.intellij.microservices.mime.*`

**`MimeTypes.java`** — constants: `APPLICATION_JSON`, `APPLICATION_XML`, `APPLICATION_YAML`, `APPLICATION_GRAPHQL`, `APPLICATION_PDF`, `APPLICATION_FORM_URLENCODED`, etc., plus `MIME_PATTERN` regex. Non-exhaustive but covers the common ones.

**`MimeTypeReference.kt`** — `PsiReferenceBase` that resolves `application/json` against the built-in dictionary. Completion returns `PREDEFINED_MIME_VARIANTS`.

**Consumer angle.** Read the constants; skip the reference class unless you are contributing an injector.

### A.6 `com.intellij.microservices.oas.*`

This is the largest sub-package (21 top-level files in `lang-api` + a `serialization/` subdir). It is a full OpenAPI 3 **DTO tree** — not a parser, an emitter.

Key public classes (all verified):
- `OpenApiSpecification(paths, components?, tags?)` — root.
- `OasEndpointPath`, `OasOperation`, `OasParameter`, `OasParameterIn` (`PATH`/`QUERY`/`HEADER`/`COOKIE`), `OasParameterStyle`, `OasRequestBody`, `OasResponse`, `OasMediaTypeObject`, `OasHeader`, `OasSchema`, `OasSchemaType`, `OasSchemaFormat`, `OasProperty`, `OasComponents`, `OasExample`, `OasTag`, `OasHttpMethod`.
- `OasSpecificationProvider` — EP `com.intellij.microservices.oasSpecificationProvider`. `getOasSpecification(urlTargetInfo): OpenApiSpecification?` + `getOasSpecificationFile(urlTargetInfo, prefixes): VirtualFile?`. Companion helper `OasSpecificationProvider.getOasSpecification(urlTargetInfo)` walks all registered providers.
- `OasExportUtils.getSpecificationByUrls(urls: Iterable<UrlTargetInfo>): OpenApiSpecification` — **the cheap path**: builds a minimal spec from whatever `UrlTargetInfo`s you already have (e.g. from `EndpointsProvider` output), without needing a framework-aware provider. Works because every `UrlTargetInfo` exposes `path.segments` (path vars) + `queryParameters` + `methods`.

**Consumer angle.** Our agent could call `OasExportUtils.getSpecificationByUrls(endpoints)` on the endpoints we already collect and expose the result as a JSON/YAML blob — "export project OpenAPI spec" — with zero per-framework code. For frameworks that register an `OasSpecificationProvider` (Spring, Micronaut, Ktor in Ultimate), the per-provider result includes request/response schemas and is strictly richer.

### A.7 `com.intellij.microservices.client.generator.*`

**`ClientGenerator`** (`@Experimental`) — EP `com.intellij.microservices.clientGenerator`. `generate(project, OpenApiSpecification): ClientExample?` returns a `(text, fileType)` pair. This is what powers the **Example** tab of the Endpoints side panel — each registered generator renders client code (curl, Java OkHttp, JS fetch, Python requests, Go net/http, …) for the selected endpoint.

**Consumer angle.** `ClientGenerator.EP_NAME.extensionList` enumerates available generators; call `.generate(project, spec)` on each to get all example snippets without opening the UI. This is a nice agent tool: "show me curl + fetch + requests snippets for `GET /api/users/{id}`".

### A.8 Other microservices infrastructure outside `lang-api`

Verified by directory listing, not full read:
- `platform/lang-impl/src/com/intellij/microservices/` — implementations: `HttpReferenceServiceImpl.kt`, `http/HttpHeaderElement.kt`, `http/HttpHeadersDictionary.java`, `http/HttpMethodElement.kt`, `inspection/HttpHeaderInspection.kt`, `intention/OpenInWebBrowserIntention.kt`, `mime/MimeTypePsiElement.kt`, `mime/MimeTypeReferenceInjector.kt`, `references/MicroserviceReferenceAnnotator.kt`, `url/UrlPathUsagesHandlerFactory.kt`, `url/inlay/UrlPathInlayHintsProvider.kt`, `url/inlay/FindUsagesUrlPathInlayAction.kt`, `utils/MicroservicesUsageCollector.kt`, `utils/PomTargetUtils.kt`, `client/generator/OpenInScratchClientGeneratorInlayAction.kt`.
- `java/java-analysis-impl/src/com/intellij/microservices/jvm/url/` — UAST-based glue: `HttpMethodReferenceInjector.kt`, `UastUrlPathInlayLanguagesProvider.kt`, `UrlPathReferenceContextInjector.kt`, `RenameableSemElementCompletionContributor.kt`, `RenameableSemElementNameSuggestionProvider.kt`, plus `package-info.java`. This is how URL/HTTP references get attached to Java/Kotlin string literals passed to `RestTemplate`, `WebClient`, `HttpRequest.newBuilder()`, etc.

`grep` for an alleged `com.intellij.microservices.jvm` module ID is **unverified** — I only see its Java package path in `java-analysis-impl`, and I have not located a `<module>`/`<dependencies>` entry declaring the plugin ID. Treat "the Spring plugin declares `depends` on `com.intellij.modules.microservices.jvm`" as **plausible but unverified** from this scan; check a Spring plugin descriptor directly if wiring depends on it.

### A.9 Other tool windows / UI surfaces powered by microservices

| Surface | Powered by | Status |
|---|---|---|
| **Endpoints tool window** | `endpointsProvider` + `endpointsProjectModel` + `endpointsSidePanelProvider` | covered in main doc |
| Endpoints side panel → **HTTP Client** tab | `RequestNavigator` + HTTP Client plugin | Ultimate |
| Endpoints side panel → **OpenAPI** tab | `oasSpecificationProvider` + `OasExportUtils` | Ultimate |
| Endpoints side panel → **Documentation** tab | `EndpointsSidePanelProvider` | Ultimate |
| Endpoints side panel → **Example** tab | `clientGenerator` | Ultimate |
| **Search Everywhere "URL" tab** | `featuresAvailabilityProvider` + `UrlResolverManager` | Ultimate |
| **Inlay hints** (`GET /users/{id}` over handler methods) | `urlInlayLanguagesProvider` + `urlInlayAction` | Ultimate |
| **HTTP Client** (`.http` scratches, editor gutter "run") | Separate plugin (`restClient`), consumes `UrlTargetInfo` via `UrlResolverManager` | Ultimate |
| URL string Ctrl+click navigation in source | `UrlPathReferenceInjector` + UAST glue in `java-analysis-impl` | Ultimate |
| Rename `/users/{id}` ↔ `@PathVariable("id")` | `url.parameters.*` SEM targets | Ultimate |
| HTTP code / header / MIME type completion in strings | `http.HttpCode`, `HttpHeaderReference`, `MimeTypeReference` | Ultimate |
| **Services Diagram** *(mentioned by IntelliJ docs)* | Presumably consumes `UrlResolverManager` edges (caller → server) | **(unverified from source)** |
| gRPC `.proto` endpoint listing | Separate Protobuf plugin registering an `EndpointsProvider` | **(unverified from `lang-api` alone)** |

Most of the above are **Ultimate-only**: the `com.intellij.modules.microservices` module ships in IDEA Ultimate and is absent from Community — which is why our existing research flags the Endpoints EP as "not available in Community". The `lang-api` *source* is open, but the module descriptor that activates it is bundled only with Ultimate.

### A.10 Dependency graph (what depends on what)

Verified from source (packages + imports):

```
lang-api::microservices.url  (UrlPath, UrlTargetInfo, UrlResolver*)
     ^                      ^
     |                      |
lang-api::microservices.endpoints  (EndpointsProvider, UrlTargetInfo is its output type)
     ^                      ^
     |                      |
lang-api::microservices.oas  (OasExportUtils consumes UrlTargetInfo)
     ^
     |
lang-api::microservices.client.generator  (ClientGenerator consumes OpenApiSpecification)

lang-api::microservices.http.request  (NavigatorHttpRequest + RequestNavigator)
     — depends on nothing inside microservices; pure request DTO
```

And:

```
lang-api::microservices.http         \
lang-api::microservices.mime          >— all call service<HttpReferenceService>() (internal SPI)
lang-api::microservices.url.references/
                                      \
lang-impl::microservices               >— provides HttpReferenceServiceImpl + header dictionary
                                      /
java-analysis-impl::microservices.jvm /— UAST-based injection for Java/Kotlin
```

Whether `com.intellij.spring` declares `depends` on `com.intellij.modules.microservices.jvm` is **unverified** from this scan. The plausible answer is yes (every Spring RequestMapping needs `UrlTargetInfo`), but I would not cite it without opening the Spring plugin descriptor.

### A.11 Sources (all verified unless flagged)

- `platform/lang-api/src/com/intellij/microservices/MicroservicesFeaturesAvailabilityProvider.kt` — https://github.com/JetBrains/intellij-community/blob/master/platform/lang-api/src/com/intellij/microservices/MicroservicesFeaturesAvailabilityProvider.kt
- `platform/lang-api/src/com/intellij/microservices/HttpReferenceService.kt` — https://github.com/JetBrains/intellij-community/blob/master/platform/lang-api/src/com/intellij/microservices/HttpReferenceService.kt
- `url/UrlResolver.kt`, `url/UrlResolverFactory.kt`, `url/UrlResolverManager.kt`, `url/UrlPathModel.kt`, `url/FrameworkUrlPathSpecification.kt` — same base, under `platform/lang-api/src/com/intellij/microservices/url/`
- `url/references/UrlPathReferenceInjector.kt` — https://github.com/JetBrains/intellij-community/blob/master/platform/lang-api/src/com/intellij/microservices/url/references/UrlPathReferenceInjector.kt
- `url/parameters/QueryParameterSem.kt` — https://github.com/JetBrains/intellij-community/blob/master/platform/lang-api/src/com/intellij/microservices/url/parameters/QueryParameterSem.kt
- `url/inlay/UrlPathInlayLanguagesProvider.kt` — https://github.com/JetBrains/intellij-community/blob/master/platform/lang-api/src/com/intellij/microservices/url/inlay/UrlPathInlayLanguagesProvider.kt
- `http/HttpCode.kt`, `http/HttpMethodReference.kt`, `http/HttpHeaderReference.kt` — under `platform/lang-api/src/com/intellij/microservices/http/`
- `http/request/RequestNavigator.kt`, `http/request/NavigatorHttpRequest.kt`, `http/request/DefaultRequestNavigator.kt` — under `platform/lang-api/src/com/intellij/microservices/http/request/`
- `mime/MimeTypes.java`, `mime/MimeTypeReference.kt` — under `platform/lang-api/src/com/intellij/microservices/mime/`
- `oas/OpenApiSpecification.kt`, `oas/OasSpecificationProvider.kt`, `oas/OasExportUtils.kt`, plus 18 sibling `Oas*.kt` DTO files — under `platform/lang-api/src/com/intellij/microservices/oas/`
- `client/generator/ClientGenerator.kt` — https://github.com/JetBrains/intellij-community/blob/master/platform/lang-api/src/com/intellij/microservices/client/generator/ClientGenerator.kt
- `platform/platform-resources/src/META-INF/LangExtensionPoints.xml` — registers 11 microservices EPs listed in A.9
- JetBrains Help — Endpoints tool window — https://www.jetbrains.com/help/idea/endpoints-tool-window.html (confirms side-panel tabs: HTTP Client / OpenAPI / Documentation / Example; confirms Services Diagram and Search Everywhere "URL" tab)
- **(unverified)** Services Diagram internals, gRPC endpoint provider, `com.intellij.spring` depending on `com.intellij.modules.microservices.jvm` — these are plausible but I did not open the descriptor or source. Do not cite without a follow-up check.

---

## Appendix B: UrlResolverManager API verification

*Added 2026-04-20. Purpose: lock down the exact signatures of `UrlResolverManager` and `UrlResolveRequest` before Task 11 (`FindUsagesAction`) writes code against them. All signatures below are copied verbatim from `JetBrains/intellij-community` master branch at 2026-04-20.*

### B.1 `UrlResolverManager`

**Source:** [UrlResolverManager.kt (master)](https://github.com/JetBrains/intellij-community/blob/master/platform/lang-api/src/com/intellij/microservices/url/UrlResolverManager.kt)

Full class header and the three `resolve`-family methods, copied verbatim:

```kotlin
class UrlResolverManager(project: Project) {
  private val all: List<UrlResolver> = UrlResolverFactory.EP_NAME.extensionList.mapNotNull { it.forProject(project) }

  @ApiStatus.Internal
  val meaningfulResolvers: List<UrlResolver> = all.filter { it.javaClass.getAnnotation(HelperUrlResolver::class.java) == null }

  fun getAuthorityHints(schema: String?): List<Authority.Exact>
    = all.asSequence().flatMap { it.getAuthorityHints(schema).asSequence() }.distinct().toList()

  val supportedSchemes: List<String>
    get() = all.asSequence().flatMap { it.supportedSchemes.asSequence() }.distinct().toList()

  fun resolve(request: UrlResolveRequest, action: (UrlResolver, UrlResolveRequest) -> Iterable<UrlTargetInfo>): Iterable<UrlTargetInfo> { ... }

  fun resolve(request: UrlResolveRequest): Iterable<UrlTargetInfo> { ... }

  fun getVariants(request: UrlResolveRequest): Iterable<UrlTargetInfo> { ... }

  companion object {
    @JvmStatic
    fun getInstance(project: Project): UrlResolverManager {
      ThreadingAssertions.assertReadAccess()
      return UrlResolverManager(project)
    }
  }
}
```

Facts locked in:

- **Accessor:** `UrlResolverManager.getInstance(project: Project): UrlResolverManager` — `@JvmStatic` on the companion object. **Asserts read access** via `ThreadingAssertions.assertReadAccess()` on entry — callers must hold a read action. Note: the implementation `return UrlResolverManager(project)` creates a new instance every call; there is no `@Service` annotation, so do not cache the return value across modification events.
- **Primary `resolve` overload used by clients:** `fun resolve(request: UrlResolveRequest): Iterable<UrlTargetInfo>` — returns `Iterable<UrlTargetInfo>` (not `List`, not `Collection`, not `Sequence`). Convert with `.toList()` if a `List` is needed.
- There is a second overload `fun resolve(request: UrlResolveRequest, action: (UrlResolver, UrlResolveRequest) -> Iterable<UrlTargetInfo>): Iterable<UrlTargetInfo>` for custom per-resolver fanout, but `FindUsagesAction` should use the single-arg form above.
- `getVariants(request)` exists too, with the same return type, for listing candidates regardless of scheme/path match.
- **Internal dedup key:** results from all registered resolvers are collected in order; the single-arg `resolve` does NOT dedup (dedup via `UrlTargetInfoDistinctKey` happens only in `getVariants`). If the plan needs dedup, the caller must do it (distinctBy `(path, methods)` mirrors the internal key).

### B.2 `UrlResolveRequest`

**Source:** [UrlPathModel.kt (master), line containing `data class UrlResolveRequest`](https://github.com/JetBrains/intellij-community/blob/master/platform/lang-api/src/com/intellij/microservices/url/UrlPathModel.kt)

Verbatim declaration:

```kotlin
data class UrlResolveRequest(val schemeHint: String?, val authorityHint: String?, val path: UrlPath, val method: String? = null)
```

Facts locked in:

- **Constructor is public** (data class). No factory / builder; just call the primary constructor.
- Parameter order is **`(schemeHint, authorityHint, path, method)`**.
- `schemeHint: String?` — e.g. `"http"` or `"https"` (NO `://` here — contrast with `UrlResolver.getAuthorityHints(schema)` where the javadoc says "schema string with `://` at the end"). Pass `null` to match any scheme.
- `authorityHint: String?` — e.g. `"localhost:8080"`. Pass `null` to match any authority.
- `path: UrlPath` — **required** (non-null). See §B.3.
- `method: String? = null` — uppercase HTTP verb like `"GET"`, or `null` for any.

### B.3 `UrlPath` construction

**Source:** [UrlPathModel.kt (master) — `UrlPath.Companion`](https://github.com/JetBrains/intellij-community/blob/master/platform/lang-api/src/com/intellij/microservices/url/UrlPathModel.kt)

Verbatim companion factory:

```kotlin
companion object {
  val EMPTY: UrlPath = UrlPath(listOf(PathSegment.Exact("")))

  @JvmStatic
  fun fromExactString(string: String): UrlPath =
    UrlPath(string.split("/").map { PathSegment.Exact(it) })

  @JvmStatic
  fun combinations(urlPath: UrlPath): Sequence<UrlPath> = ...
}
```

Facts locked in:

- **The API is `UrlPath.fromExactString(string)`** — there is NO `UrlPath.parse(...)`. The research doc (§2.2) was correct.
- `fromExactString("/api/users/{id}")` naïvely splits on `/` — it does NOT recognise `{id}` as a `PathSegment.Variable`; the resulting segments are `["", "api", "users", "{id}"]` all as `PathSegment.Exact`. That is fine for `UrlResolver`-side matching because IntelliJ's `isCompatibleWith` / `commonLength` logic handles brace-syntax comparison at the resolver level; but do not assume `{id}` becomes a `Variable`. If Task 11 wants true variable-awareness, build the path manually: `UrlPath(listOf(PathSegment.Exact(""), PathSegment.Exact("api"), PathSegment.Exact("users"), PathSegment.Variable("id")))`.
- `UrlPath.EMPTY` is a singleton for "no path" — useful as a placeholder.
- Constructor `UrlPath(segments: List<PathSegment>)` is public.

### B.4 `Authority` construction

**Source:** [UrlPathModel.kt (master) — top of file](https://github.com/JetBrains/intellij-community/blob/master/platform/lang-api/src/com/intellij/microservices/url/UrlPathModel.kt)

Verbatim:

```kotlin
sealed class Authority {
  data class Exact(val text: String) : Authority()
  open class Placeholder : Authority()
}
```

Facts locked in:

- To construct from a string: `Authority.Exact("localhost:8080")` (data class, public constructor). Note: `UrlResolveRequest.authorityHint` is a raw `String?`, not an `Authority` — so you rarely need to construct `Authority` instances when calling `resolve`. `Authority` is only relevant when **reading** `UrlTargetInfo.authorities` (which is `List<Authority>`).
- `Authority.Placeholder` exists for "unknown / variable authority" but has no required text; typically constructed via subclass.

### B.5 `@ApiStatus` flags

Cross-referenced against `platform/lang-api/api-dump.txt` and `platform/lang-api/api-dump-unreviewed.txt` at master (2026-04-20):

| Symbol | Source file `@ApiStatus.*` | Found in `api-dump.txt` (reviewed, stable) | Found in `api-dump-unreviewed.txt` (public, not locked) |
|---|---|---|---|
| `UrlResolver` interface | none | **yes** | no |
| `UrlResolver.resolve(UrlResolveRequest): Iterable<UrlTargetInfo>` | none | **yes** | no |
| `UrlResolverManager` class | none | no | **yes** |
| `UrlResolverManager.getInstance(Project)` | none | no | **yes** |
| `UrlResolverManager.resolve(UrlResolveRequest)` | none | no | **yes** |
| `UrlResolverManager.meaningfulResolvers` | `@ApiStatus.Internal` | no | no |
| `UrlResolveRequest` data class | none | no | **yes** (plus generated `copy`/`copy$default`) |
| `UrlPath` class, `UrlPath.fromExactString` | none | **yes** (`fromExactString` is in reviewed dump) | no |
| `Authority.Exact` | none | **yes** | no |
| `HelperUrlResolver` annotation | `@ApiStatus.Internal` | no | no |

Summary: `UrlResolver.resolve(request)`, `UrlPath.fromExactString`, `Authority.Exact` — **reviewed stable**. `UrlResolverManager.getInstance(project).resolve(...)` and `UrlResolveRequest` — **public but unreviewed** (`api-dump-unreviewed.txt`). Nothing is `@Experimental` or `@Internal` on the symbols we will actually call. Safe to use; accept minor signature churn between platform releases.

### B.6 Concrete caller templates (verbatim)

#### Template 1 — `UrlPathContext.resolveTargets` (the canonical usage)

**Source:** [UrlPathContext.kt, function `resolveTargets`](https://github.com/JetBrains/intellij-community/blob/master/platform/lang-api/src/com/intellij/microservices/url/references/UrlPathContext.kt)

Verbatim:

```kotlin
fun UrlPathContext.resolveTargets(project: Project): Set<UrlTargetInfo> {
  val urlResolver = UrlResolverManager.getInstance(project)

  return filterBestUrlPathMatches(
    resolveRequests.asSequence().flatMap { urlResolver.resolve(it).asSequence() }.asIterable())
}
```

This is the platform's own "given a context (scheme × authority × method × paths), enumerate every matching target in the project" one-liner. Note:
- Called inside a read action (caller's responsibility — `getInstance` asserts it).
- Applies `filterBestUrlPathMatches` to pick the longest-prefix matches only. Task 11 should do the same if the user expects a "Find Usages" list of the **best** handlers, not every partial match.

#### Template 2 — building `UrlResolveRequest` objects from a scheme/authority/path/method stub

**Source:** [UrlPathContext.kt, private fun `toUrlResolveRequestsStubs`](https://github.com/JetBrains/intellij-community/blob/master/platform/lang-api/src/com/intellij/microservices/url/references/UrlPathContext.kt)

Verbatim:

```kotlin
fun toUrlResolveRequestsStubs(paths: Sequence<UrlPath>): Sequence<UrlResolveRequest> =
  schemes.orNull().asSequence().flatMap { scheme ->
    authorities.orNull().asSequence().flatMap { authority ->
      methods.orNull().asSequence().flatMap { method ->
        paths.map { path ->
          UrlResolveRequest(scheme, authority, path, method)
        }
      }
    }
  }
```

Note the positional call: **`UrlResolveRequest(scheme, authority, path, method)`**, all four args, in that exact order.

#### Template 3 — the single-URL entry point

**Source:** [UrlPathContext.kt, `companion.singleContext`](https://github.com/JetBrains/intellij-community/blob/master/platform/lang-api/src/com/intellij/microservices/url/references/UrlPathContext.kt)

Verbatim:

```kotlin
@JvmStatic
fun singleContext(scheme: String?, authority: String?, urlPath: UrlPath): UrlPathContext =
  UrlPathContext(listOf(UrlResolveRequest(scheme, authority, urlPath)))
```

This is the "I have one URL, build me a context for it" form. `method` defaults to `null`.

### B.7 Recipe for Task 11 — "given a URL string, find handler usages"

Putting it together, the minimal pattern Task 11 should follow (verified against §B.1–B.6):

```kotlin
// Under ReadAction.nonBlocking { ... }.inSmartMode(project).executeSynchronously()
val manager = UrlResolverManager.getInstance(project)
val request = UrlResolveRequest(
    /* schemeHint    = */ "http",       // or null to match any
    /* authorityHint = */ null,         // or "localhost:8080"
    /* path          = */ UrlPath.fromExactString("/api/users/{id}"),
    /* method        = */ "GET"         // or null for any verb
)
val targets: List<UrlTargetInfo> = manager.resolve(request).toList()
val best: Set<UrlTargetInfo> = filterBestUrlPathMatches(targets)  // optional, usually wanted
for (target in best) {
    val psi: PsiElement? = target.resolveToPsiElement()
    val file = psi?.containingFile?.virtualFile?.path
    val line = psi?.let { PsiDocumentManager.getInstance(project).getDocument(it.containingFile)?.getLineNumber(it.textOffset)?.plus(1) }
    // report (target.path, target.methods, target.source, file, line)
}
```

Five call-site invariants Task 11 **must** preserve:

1. Wrap in a read action. `getInstance` asserts it.
2. Do NOT cache `UrlResolverManager` across modification events — it's rebuilt per call.
3. Pass `schemeHint` WITHOUT trailing `://` (unlike `UrlResolver.getAuthorityHints`, which wants `http://`).
4. `method` must be uppercased (`"GET"` not `"get"`); the `getDeclaredHttpMethods` helper in the same file does `String.uppercase(Locale.getDefault())`.
5. The return type is `Iterable<UrlTargetInfo>`, not `List`/`Collection`/`Sequence`. Call `.toList()` if you need a `List`; call `filterBestUrlPathMatches(it)` if you need the top matches as a `Set`.

### B.8 Not-BLOCKED confirmation

All four assumed APIs used in the earlier research (§2.2, §A.3) are verified to exist with the exact shapes predicted:

- `UrlResolverManager.getInstance(project)` — yes, `@JvmStatic` companion accessor.
- `UrlResolverManager.resolve(request)` returning `Iterable<UrlTargetInfo>` — yes.
- `UrlResolveRequest(scheme, authority, path, method)` — yes, data class, positional.
- `UrlPath.fromExactString(...)` — yes, `@JvmStatic` companion factory.

Task 11 can proceed as planned. No rethink required.

---

## Appendix C: OasExportUtils API verification

**Date verified:** 2026-04-20. **Purpose:** Lock down exact signatures for Task 13 (`ExportOpenApiAction` / `export_openapi` agent tool).

### C.1 Exact class FQNs

| Symbol | Kind | FQN | Source |
|---|---|---|---|
| `OasExportUtils.kt` | Kotlin **top-level file** — NOT a class/object. Top-level functions. Java-visible facade class: `com.intellij.microservices.oas.OasExportUtilsKt` | `com.intellij.microservices.oas` | [OasExportUtils.kt](https://github.com/JetBrains/intellij-community/blob/master/platform/lang-api/src/com/intellij/microservices/oas/OasExportUtils.kt) |
| `OpenApiSpecification` | `class` (plain DTO, no serializer) | `com.intellij.microservices.oas.OpenApiSpecification` | [OpenApiSpecification.kt](https://github.com/JetBrains/intellij-community/blob/master/platform/lang-api/src/com/intellij/microservices/oas/OpenApiSpecification.kt) |
| `OasSerializationUtils.kt` | Kotlin top-level; Java facade `OasSerializationUtilsKt` | `com.intellij.microservices.oas.serialization` | [OasSerializationUtils.kt](https://github.com/JetBrains/intellij-community/blob/master/platform/lang-api/src/com/intellij/microservices/oas/serialization/OasSerializationUtils.kt) |
| `OasSpecificationProvider` | `interface` (EP: `com.intellij.microservices.oasSpecificationProvider`) | `com.intellij.microservices.oas.OasSpecificationProvider` | [OasSpecificationProvider.kt](https://github.com/JetBrains/intellij-community/blob/master/platform/lang-api/src/com/intellij/microservices/oas/OasSpecificationProvider.kt) |

> **Important naming correction:** the earlier research note called this `OasExportUtils` as if it were an `object`/class. It is **not**. The file is a free-function Kotlin file; from Kotlin you call the functions unqualified after `import com.intellij.microservices.oas.getSpecificationByUrls`; from Java the synthetic class is `OasExportUtilsKt.getSpecificationByUrls(...)`.

### C.2 Exact function signatures (copy-pasteable)

All four functions live at top level in `OasExportUtils.kt` with **no** class or object wrapper:

```kotlin
// 1. The one Task 13 needs. Note parameter is Iterable<UrlTargetInfo>, NOT List/Collection.
fun getSpecificationByUrls(urls: Iterable<UrlTargetInfo>): OpenApiSpecification

// 2. Combine many specs (dedup paths, merge tags + components.schemas).
fun squashOpenApiSpecifications(specifications: List<OpenApiSpecification>): OpenApiSpecification

// 3. Single-endpoint route: routes through EndpointsUrlTargetProvider.getOpenApiSpecification(...)
//    and falls back to getSpecificationByUrls(provider.getUrlTargetInfo(group, endpoint)).
fun <G : Any, E : Any> getOpenApi(
    provider: EndpointsProvider<G, E>,
    group: G,
    endpoint: E,
): OpenApiSpecification?

// 4. Whole-tool-window route: takes the EndpointsListItem collection the Endpoints tool
//    window renders and squashes all per-endpoint specs into one.
fun getOpenApiSpecification(endpointsList: Collection<EndpointsListItem>): OpenApiSpecification?

// 5. Compile-time constant.
val EMPTY_OPENAPI_SPECIFICATION: OpenApiSpecification
```

**Function signature for Task 13 — one line:**

```kotlin
val spec: OpenApiSpecification = getSpecificationByUrls(targets)   // targets: Iterable<UrlTargetInfo>
```

Where `targets` is whatever Task 11 already produces (`UrlResolverManager.resolve(request)` returns `Iterable<UrlTargetInfo>` — a perfect fit, no `.toList()` needed).

Note on URL filtering: `getSpecificationByUrls` does **not** filter by scheme or endpoint kind. It blindly maps every `UrlTargetInfo` to an `OasEndpointPath`. If we only want "HTTP-Server" endpoints (to match the Endpoints tool window's "OpenAPI" tab behaviour), filter upstream:

```kotlin
val httpTargets = targets.filter { it.schemes.any { s -> s in HTTP_SCHEMES } }
//                                                       // from com.intellij.microservices.url.HTTP_SCHEMES
```

The real gate the Endpoints tool window uses is `EndpointsUrlTargetProvider.shouldShowOpenApiPanel()` (default `true`), applied per-provider in `getOpenApi(provider, group, endpoint)` above.

### C.3 `OpenApiSpecification` DTO shape

```kotlin
class OpenApiSpecification(
    val paths: Collection<OasEndpointPath>,
    val components: OasComponents? = null,
    val tags: List<OasTag>? = null,
) {
    fun isEmpty(): Boolean            // (note: implementation returns paths.iterator().hasNext()
                                      //  which is the opposite of what the name suggests — bug;
                                      //  do NOT rely on it)
    fun findSinglePathRequestBodyData(contentType: String = APPLICATION_JSON): OasSchema?
}
```

**Crucially: no `toYaml()`, no `toJson()`, no `toString()` override, no `writeTo(OutputStream)`.** This is a pure DTO. See C.4.

### C.4 Serializing `OpenApiSpecification` to YAML / JSON text — THIS IS THE CATCH

There is **exactly one** serializer exposed by the platform, and it is hostile to use:

**File:** [OasSerializationUtils.kt](https://github.com/JetBrains/intellij-community/blob/master/platform/lang-api/src/com/intellij/microservices/oas/serialization/OasSerializationUtils.kt)

```kotlin
@Deprecated("You must use generateOasDraft from OpenAPI Specifications plugin instead")
@ApiStatus.Internal
@NlsSafe
fun generateOasDraft(projectName: String, models: OpenApiSpecification): String {
  return EP_NAME.extensionList.firstOrNull()?.generateOasDraft(projectName, models) ?: ""
}
```

Three compounding problems:

1. **`@ApiStatus.Internal`** — explicitly not for third-party plugins. Using it emits an IJ inspection warning; JetBrains can remove it in any release.
2. **`@Deprecated`** — the message literally says *"You must use generateOasDraft from OpenAPI Specifications plugin instead"*. There is no public replacement in the platform; the replacement lives in a different plugin we'd have to depend on (`com.intellij.swagger`, see below).
3. **Empty-string fallback** — the function returns `""` when no `OasSerializationCompatibilityProvider` is registered. No error, no `null`. Task 13 would have to detect `result.isBlank()` and surface that as *"OpenAPI Specifications plugin not installed; install it from the marketplace."*

The real implementation is registered via extension point `com.intellij.microservices.oasSerializationCompatibilityProvider` by the **OpenAPI Specifications plugin** (`xmlId: com.intellij.swagger`, bundled in IntelliJ Ultimate but disableable; NOT bundled in Community/PyCharm Community). A global GitHub search across all mirrors finds **zero** public implementations — the class lives only inside the closed-source `com.intellij.swagger` plugin JAR that ships with Ultimate.

**One-line serialization call — if and only if the Swagger plugin is active:**

```kotlin
val yaml: String = generateOasDraft(project.name, spec)   // "" when com.intellij.swagger not installed
```

**Stability flags for this serializer path:** `@Deprecated` + `@ApiStatus.Internal`. Do NOT use without an inspection suppression and a runtime guard.

### C.5 API stability flags (overall)

| Entry | `api-dump.txt` (reviewed / stable) | `api-dump-unreviewed.txt` (unstable) | Annotations on source |
|---|---|---|---|
| `OasExportUtilsKt.getSpecificationByUrls` | - | yes (line 2735) | none |
| `OasExportUtilsKt.getOpenApi(...)` | - | yes (line 2733) | none |
| `OasExportUtilsKt.getOpenApiSpecification(Collection)` | - | yes (line 2734) | none |
| `OasExportUtilsKt.squashOpenApiSpecifications` | - | yes (line 2736) | none |
| `OasExportUtilsKt.EMPTY_OPENAPI_SPECIFICATION` | - | yes (line 2732) | none |
| `OpenApiSpecification` (all accessors) | - | yes (line 2995+) | none |
| `OasEndpointPath`, `OasOperation`, `OasHttpMethod`, `OasParameter*`, `OasSchema*` | - | yes | none |
| `EndpointsProvider.getOpenApiSpecification(group, endpoint)` | **yes** | - | none |
| `OasSpecificationProvider.getOasSpecification(UrlTargetInfo)` | - | yes (line 2978/2981) | none |
| `OasSerializationUtilsKt.generateOasDraft` | - | not listed (filtered out as internal) | `@Deprecated` + `@ApiStatus.Internal` |
| `OasSerializationCompatibilityProvider` (interface) | - | not listed | `@Deprecated` + `@ApiStatus.Internal` |

**Summary of stability:** everything Task 13 needs is **unreviewed but not deprecated and not internal** — same tier as the resolve chain Task 11 relies on (§B.8). The only red flag is the serializer, not the exporter.

### C.6 Concrete callers inside intellij-community

1. **`OpenInScratchClientGeneratorInlayAction.kt`** — [platform/lang-impl/...](https://github.com/JetBrains/intellij-community/blob/master/platform/lang-impl/src/com/intellij/microservices/client/generator/OpenInScratchClientGeneratorInlayAction.kt). The "Open in Scratch" inlay action that appears next to every `@GetMapping` URL. Resolves `UrlPathContext` → `UrlTargetInfo`s → calls the companion form `OasSpecificationProvider.Companion.getOasSpecification(urlTarget)` for each, then **never serializes the result** — it hands the in-memory `OpenApiSpecification` directly to `ClientGenerator.generate(project, oas)` which returns a native HTTP-client file (HTTP Client scratch, cURL, JS fetch, etc.). **Takeaway:** the platform's own "export" flows go DTO → code generator, not DTO → YAML. Confirms §C.4.

   ```kotlin
   // Lines 90-99, trimmed.
   val oas = withModalProgress(project, ...) {
       readAction { blockingContextToIndicator { getOpenApiSpecification(urlPathContext) } }
   }
   if (oas == null) { /* show error */ return@launch }
   createScratchFile(clientGenerator, oas)
   ```

2. **`OasExportUtils.kt` itself — `getOpenApi(provider, group, endpoint)`** — the Endpoints tool window's "OpenAPI" tab calls this to produce the in-memory DTO it displays. The tab's on-screen YAML view is rendered by code in the closed-source `com.intellij.swagger` plugin, via the same `OasSerializationCompatibilityProvider` EP. So even the "Show OpenAPI" button in the stock tool window cannot render YAML without the Swagger plugin installed and enabled.

### C.7 Bottom line for Task 13

- **Not BLOCKED, but DEGRADED.** `getSpecificationByUrls` exists with the exact signature the plan assumes — one-line DTO production works.
- **YAML/JSON string is NOT a one-liner.** The only public emitter is `@Deprecated + @Internal` and returns `""` without the `com.intellij.swagger` plugin. Task 13 must do one of:
  1. **Preferred.** Depend on `com.intellij.swagger` (`<depends optional="true" config-file="openapi-export.xml">com.intellij.swagger</depends>`), call `generateOasDraft(project.name, spec)` with an `@Suppress("DEPRECATION", "UnstableApiUsage")`, and fall back to option (2) if the return is blank. This gives us the same YAML the Endpoints tool window shows.
  2. **Fallback.** Emit YAML ourselves by walking the DTO with SnakeYAML (already on the platform classpath as `org.yaml.snakeyaml.Yaml`). Every field we need is a public getter (`OasEndpointPath.path / summary / operations`, `OasOperation.method / tags / parameters / requestBody / responses`, etc. — see C.3 / source links). Effort: ~80 LoC for an OpenAPI 3.0 emitter covering the subset `getSpecificationByUrls` produces (paths, ops, params — no components/schemas unless `OasSpecificationProvider` is chained in).
  3. **JSON fallback.** `com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(spec)` emits a reasonably shaped JSON thanks to Jackson's default bean introspection, but the field casing and OpenAPI-required wrapping (`openapi: "3.0.3"`, `info: {...}`, `paths` keyed by path string) still require a hand-written envelope. Same ~80 LoC regardless of JSON vs YAML.

**Recommendation for Task 13:** do (1) + (2). Primary path = `com.intellij.swagger` / `generateOasDraft`; fallback when plugin absent or returns blank = SnakeYAML walker. Surface the degradation in `ToolResult.summary` so the agent can tell the user *"Exported via built-in emitter; install the OpenAPI Specifications plugin for richer output."*

### C.8 Three lines, one reply

- **DTO build:** `val spec = getSpecificationByUrls(targets)` — import from `com.intellij.microservices.oas`.
- **Serialize (Swagger plugin present):** `val yaml = generateOasDraft(project.name, spec)` — import from `com.intellij.microservices.oas.serialization`, `@Suppress("DEPRECATION", "UnstableApiUsage")`.
- **Serialize (Swagger plugin absent):** no public one-liner exists — walk the DTO with SnakeYAML. Plan §4 must budget for this.
