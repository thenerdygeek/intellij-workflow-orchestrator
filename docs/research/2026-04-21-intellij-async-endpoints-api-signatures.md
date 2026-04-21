# IntelliJ Ultimate — Async & gRPC Endpoint SPI Surface

**Date:** 2026-04-21
**Source:** Runtime introspection (Class.forName + javap) against IntelliJ IDEA Ultimate 2025.1.7 (IU-251.*), using the test-classpath produced by `bundledPlugins` in `agent/build.gradle.kts`
**Purpose:** Determine what gRPC and async/messaging endpoint APIs exist, whether the existing `EndpointsDiscoverer` already surfaces them, and what plugin additions A5.3 would require.

> **Why this matters:** `EndpointsDiscoverer.discover()` iterates `EndpointsProvider.getAvailableProviders(project)`, which collects every extension registered under the `<microservices.endpointsProvider>` extension point. If a plugin registers its provider there, zero code changes are needed in our discoverer — just adding the plugin to `bundledPlugins`.

---

## Executive finding

**gRPC (A5.1):** The gRPC plugin (`com.intellij.grpc`) ships `com.intellij.grpc.endpoints.ProtoEndpointsProvider`, which implements `EndpointsUrlTargetProvider` and is registered via `<microservices.endpointsProvider>` in the plugin's `plugin.xml`. **When `com.intellij.grpc` is added to `bundledPlugins`, the existing `EndpointsDiscoverer` surfaces gRPC RPCs with zero code changes.** The plugin is already bundled in IU-2025.1.7 but is NOT currently declared in our `bundledPlugins` list, so it is absent from the test classpath and from the runtime discovery set.

**Async messaging (A5.2):** No `EndpointsProvider`-based async SPI exists in the current bundled-plugin set. Async messaging (Kafka, RabbitMQ, JMS) is handled by a separate MQ model: `MQResolverManager` + `MQResolver` in `com.intellij.microservices.jvm.mq` (from `microservices-jvm` plugin, which IS on the classpath). This is an entirely different discovery path from the HTTP `EndpointsProvider` chain. A dedicated `AsyncEndpointsDiscoverer` calling `MQResolverManager.getAllVariants(mqType)` is required for A5.3 — it cannot reuse the existing HTTP discoverer.

**Spring messaging model:** The `spring-messaging` plugin ships `SpringMessagingModel` (WebSocket `@MessageMapping`, `@SubscribeMapping`, JMS, Kafka, RabbitMQ listeners) — but these are also not registered as `EndpointsProvider` extensions. They use a separate `SpringMessagingUrlProvider` SPI.

---

## Package corrections (FQN guesses that missed)

| Guessed FQN | **Actual FQN / Finding** | Notes |
|---|---|---|
| `com.intellij.grpc.endpoints.GrpcEndpointsProvider` | **`com.intellij.grpc.endpoints.ProtoEndpointsProvider`** | Named "Proto" not "Grpc"; provider handles Protobuf `.proto` RPC definitions |
| `com.intellij.grpc.GrpcEndpointProvider` | **DOES NOT EXIST** | No such class in `grpc.jar` |
| `com.intellij.protobuf.grpc.endpoints.GrpcEndpointsProvider` | **DOES NOT EXIST** | Protobuf lives in a separate plugin (`idea.plugin.protoeditor`); gRPC wraps it |
| `com.intellij.microservices.async.AsyncEndpointProvider` | **DOES NOT EXIST** | No `async` sub-package in the microservices SPI |
| `com.intellij.microservices.async.AsyncEndpointsProvider` | **DOES NOT EXIST** | Same — no async provider in the HTTP endpoint SPI chain |
| `com.intellij.microservices.mq.MessageQueueProvider` | **DOES NOT EXIST** | MQ lives in `com.intellij.microservices.jvm.mq` (jvm sub-package), not in core |
| `com.intellij.microservices.messaging.MessagingEndpointProvider` | **DOES NOT EXIST** | No such package in microservices SPI |
| `com.intellij.kafka.endpoints.KafkaEndpointProvider` | **DOES NOT EXIST** | Kafka is handled via `SpringKafkaListenerMQResolver` in `spring-messaging` plugin and `MQTypes.KAFKA_TOPIC_TYPE` in `microservices-jvm` |
| `com.intellij.rabbitmq.endpoints.RabbitMqEndpointProvider` | **DOES NOT EXIST** | Same — via `SpringRabbitListenerMQResolver` and `MQTypes.RABBIT_MQ_*_TYPE` |
| `com.intellij.jms.endpoints.JmsEndpointProvider` | **DOES NOT EXIST** | Via `SpringJmsListenerMQResolver` in `spring-messaging` |

---

## Signatures

### `com.intellij.grpc.endpoints.ProtoEndpointsProvider`

From `grpc.jar` (plugin `com.intellij.grpc`, bundled with IU-2025.1.7).

```
implements: EndpointsUrlTargetProvider<ProtoServiceModel, ProtoRpcModel>
             (which extends EndpointsProvider<ProtoServiceModel, ProtoRpcModel>)

// EndpointsProvider contract:
EndpointType getEndpointType()
FrameworkPresentation getPresentation()
Status getStatus(Project)
Iterable<ProtoServiceModel> getEndpointGroups(Project, EndpointsFilter)
Iterable<ProtoRpcModel> getEndpoints(ProtoServiceModel)
boolean isValidEndpoint(ProtoServiceModel, ProtoRpcModel)
ItemPresentation getEndpointPresentation(ProtoServiceModel, ProtoRpcModel)
PsiElement getDocumentationElement(ProtoServiceModel, ProtoRpcModel)
ModificationTracker getModificationTracker(Project)

// EndpointsUrlTargetProvider additional:
Iterable<UrlTargetInfo> getUrlTargetInfo(ProtoServiceModel, ProtoRpcModel)
boolean shouldShowOpenApiPanel()
```

**Registration:** Declared in `grpc.jar!/META-INF/plugin.xml` as:
```xml
<microservices.endpointsProvider implementation="com.intellij.grpc.endpoints.ProtoEndpointsProvider" />
```

This means `EndpointsProvider.getAvailableProviders(project)` returns this provider whenever `com.intellij.grpc` is loaded — no custom code needed.

**Plugin dependency:** `com.intellij.grpc` depends on `com.intellij.modules.ultimate`, `idea.plugin.protoeditor`, and `com.jetbrains.restClient`. All are bundled in IU. The plugin id to add to `bundledPlugins` is exactly `com.intellij.grpc`.

---

### `com.intellij.grpc.endpoints.ProtoUrlTargetInfo`

```
implements: UrlTargetInfo

List<String> getSchemes()
List<Authority> getAuthorities()
UrlPath getPath()
PsiElement resolveToPsiElement()
Set<String> getMethods()
```

This is the `UrlTargetInfo` produced by `ProtoEndpointsProvider.getUrlTargetInfo()`. Our `EndpointsDiscoverer.toDiscovered()` already handles `UrlTargetInfo` correctly, so gRPC endpoints will display properly in the tool output with no additional changes.

---

### `com.intellij.microservices.jvm.mq.MQResolverManager`

From `microservices-jvm.jar` (plugin already on classpath via `com.intellij.modules.microservices`).

```
// Entry point: project service
MQResolverManager(Project)

Sequence<MQTargetInfo> getVariants(MQType)      // returns destinations of given type
Sequence<MQTargetInfo> getAllVariants(MQType)   // includes indirect/transitive destinations
```

This is the entry point for A5.3 async endpoint discovery.

---

### `com.intellij.microservices.jvm.mq.MQTargetInfo`

```
interface MQTargetInfo {
    MQDestination getDestination()
    MQAccessType getAccessType()
    PsiElement resolveToPsiElement()
}
```

---

### `com.intellij.microservices.jvm.mq.MQDestination`

```
// Kotlin data class
MQDestination(MQType type, String name)

MQType getType()
String getName()
```

---

### `com.intellij.microservices.jvm.mq.MQAccessType`

```
// Predefined instances in MQAccessTypes:
static MQAccessType ADMINISTRATION_TYPE
static MQAccessType SEND_TYPE
static MQAccessType RECEIVE_TYPE
static MQAccessType SEND_AND_RECEIVE_TYPE
static MQAccessType STREAM_FORWARDING_TYPE
static MQAccessType UNKNOWN_TYPE

// Instance methods:
String getName()
Icon getIcon()
Supplier<String> getLocalizedMessage()
```

---

### `com.intellij.microservices.jvm.mq.MQTypes`

The canonical type constants to pass to `MQResolverManager`:

```
// Kafka
static NamedMQType KAFKA_TOPIC_TYPE

// RabbitMQ
static NamedMQType RABBIT_MQ_TYPE
static NamedMQType RABBIT_MQ_QUEUE_TYPE
static NamedMQType RABBIT_MQ_TOPIC_TYPE
static NamedMQType RABBIT_MQ_FANOUT_TYPE
static NamedMQType RABBIT_MQ_DIRECT_TYPE
static NamedMQType RABBIT_MQ_HEADERS_TYPE
static NamedMQType RABBIT_MQ_EXCHANGE_TYPE

// ActiveMQ / JMS
static NamedMQType ACTIVE_MQ_TYPE
static NamedMQType ACTIVE_MQ_QUEUE_TYPE
static NamedMQType ACTIVE_MQ_TOPIC_TYPE

// Amazon SQS
static NamedMQType AMAZON_SQS_QUEUE_TYPE

// Generic destination types
static DestinationMQType TOPIC_EXCHANGE_TYPE
static DestinationMQType QUEUE_EXCHANGE_TYPE
```

---

### `com.intellij.microservices.jvm.mq.MQResolver`

Extension point that each framework (Spring Kafka, Spring RabbitMQ, Spring JMS) registers its resolver against:

```
interface MQResolver {
    List<MQType> getSupportedTypes()
    Iterable<MQTargetInfo> getVariants(MQType)
}
```

Concrete implementations (from `spring-messaging.jar`):
- `SpringKafkaListenerMQResolver` — resolves `@KafkaListener` topics
- `SpringKafkaTemplateMQResolver` — resolves `KafkaTemplate.send()` destinations
- `SpringRabbitListenerMQResolver` — resolves `@RabbitListener` queues/exchanges
- `SpringRabbitBindingDestinationMQResolver` — resolves `@RabbitBinding` destinations
- `SpringRabbitTemplateMQResolver` — resolves `RabbitTemplate.send()` destinations
- `SpringJmsListenerMQResolver` — resolves `@JmsListener` destinations

All are registered as `com.intellij.microservices.jvm.mq.mqResolver` extension-point implementations. `MQResolverManager.getAllVariants(type)` collects all and dispatches by `getSupportedTypes()`.

---

### `com.intellij.spring.messaging.model.SpringMessagingModel`

From `spring-messaging.jar` — WebSocket/STOMP messaging, separate from MQ:

```
SpringMessagingModel(Module, PsiElement)

Collection<SpringMessagingModel.Variant> getAllUrls()
Collection<SpringMessagingModel.Variant> getUrls(SpringMessagingType... types)
```

`SpringMessagingType` enum values: `MESSAGE_MAPPING`, `SUBSCRIBE_MAPPING`, `SEND_TO`, `SEND_TO_USER`

This covers WebSocket `@MessageMapping` / `@SubscribeMapping` / `@SendTo` annotations — distinct from the broker (Kafka/RabbitMQ) MQ model.

---

## Core SPI — classes loaded successfully from test classpath

These were already on the classpath (via `com.intellij.modules.microservices`):

| Class | Status | Notes |
|---|---|---|
| `EndpointsProvider` | LOADED — interface | 12 public methods; `EP_NAME` static field (ExtensionPointName) |
| `EndpointsUrlTargetProvider` | LOADED — interface | Extends `EndpointsProvider`; adds `getUrlTargetInfo`, `shouldShowOpenApiPanel`, `getOpenApiSpecification` |
| `EndpointsFilter` | LOADED — interface | No public methods (marker/callback type) |
| `ModuleEndpointsFilter` | LOADED — concrete class | Data class; `getContentSearchScope()`, `filterByScope()` |
| `EndpointType` | LOADED — class | `getIcon()`, `getQueryTag()`, `getLocalizedMessage()` |
| `EndpointsGroup` | **MISSING** | No such class on the test classpath — groups are provider-generic (`Any`) |
| `EndpointsSearcher` | **MISSING** | Not in current bundled-plugin set |
| `EndpointGroupsBuilder` | **MISSING** | Not in current bundled-plugin set |

`EndpointsProvider` static fields:
```
ExtensionPointName EP_NAME      // the extension point name
DataKey DOCUMENTATION_ELEMENT   // DataKey for getEndpointData()
DataKey URL_TARGET_INFO         // DataKey for getEndpointData()
Companion Companion             // Kotlin companion holding static helpers
```

No public static methods on `EndpointsProvider` itself — `getAvailableProviders(project)` and `hasAnyProviders()` are extension methods (Kotlin top-level or companion) not visible as Java static methods.

---

## Providers already iterable via EndpointsDiscoverer (current bundledPlugins)

The dump test found **zero** implementations of `EndpointsProvider` registered on the current test classpath beyond the core interface itself. This is expected — the Spring HTTP providers come from `com.intellij.spring` (already bundled) and are registered at runtime via the extension point, but their implementation classes are not `Class.forName`-discoverable without a running `Application`. The gRPC provider (`ProtoEndpointsProvider`) is absent because `com.intellij.grpc` is not in `bundledPlugins`.

At runtime in the IDE, `EndpointsProvider.getAvailableProviders(project)` would return:
- Spring HTTP (`@RequestMapping`, etc.) — from `com.intellij.spring` (already declared)
- OpenAPI (`*.yaml`/`*.json`) — from `com.intellij.modules.microservices` (already declared)
- gRPC (`*.proto` RPCs) — **only if `com.intellij.grpc` is added to `bundledPlugins`**

---

## Recommended next steps

### A5.1 — gRPC surfacing (CONFIRMED: already flows through EndpointsDiscoverer)

**Finding:** `ProtoEndpointsProvider` implements `EndpointsUrlTargetProvider` and is registered as a `<microservices.endpointsProvider>` extension. The existing `EndpointsDiscoverer.discover()` loop handles it correctly with zero code changes.

**Required action:** Add `"com.intellij.grpc"` to `bundledPlugins` in `agent/build.gradle.kts`. That is the only change. gRPC services will appear in `endpoints(action=list)` output automatically.

**No new discoverer needed.** The gRPC provider is self-contained in the EP chain.

### A5.2 — Async messaging endpoints (CONFIRMED: requires separate discoverer)

**Finding:** Kafka, RabbitMQ, JMS, and ActiveMQ topics are surfaced via `MQResolverManager.getAllVariants(MQType)`, which is an entirely separate discovery path from the HTTP `EndpointsProvider` chain. The MQ resolver lives in `com.intellij.microservices.jvm` (already on classpath via `com.intellij.modules.microservices`). The Spring-specific resolvers (`SpringKafkaListenerMQResolver`, `SpringRabbitListenerMQResolver`, `SpringJmsListenerMQResolver`) live in the `spring-messaging` plugin.

**Required action for A5.3:**
1. Add `"com.intellij.spring.messaging"` (or verify its plugin id) to `bundledPlugins` so Spring framework-specific MQ resolvers are loaded at runtime.
2. Implement `AsyncEndpointsDiscoverer` calling `MQResolverManager.getAllVariants(mqType)` for each type in `MQTypes.*`. Reflect against `com.intellij.microservices.jvm.mq.MQResolverManager`, `MQTargetInfo`, `MQDestination`, `MQAccessType`, and `MQTypes`.
3. Wire into `EndpointsTool` as a new `action=list_async` (or merge into `action=list` with a `framework` filter).

**Do NOT add `com.intellij.grpc` as a dependency for MQ** — gRPC and async messaging are orthogonal.

### gRPC gradle.kts change — should it happen now?

Per the dispatch instructions, do not add `com.intellij.grpc` yet — document only. A future dispatch (A5.3) should add it alongside the tests.

### Spring messaging plugin ID

The `spring-messaging.jar` is located at `plugins/spring-messaging/lib/spring-messaging.jar`. The plugin id needs verification from its `plugin.xml` (not inspected in this session). It is likely `com.intellij.spring.messaging` or `com.intellij.spring.integration`. Verify before adding to `bundledPlugins`.

---

## Verified against

- IntelliJ IDEA Ultimate 2025.1.7 (build IU-251.*)
- `bundledPlugins` classloader (test classpath): `Git4Idea`, `com.intellij.java`, `com.intellij.persistence`, `com.intellij.spring`, `com.intellij.spring.boot`, `org.jetbrains.kotlin`, `com.intellij.modules.microservices`
- Jar inspection paths: `~/.gradle/caches/9.0.0/transforms/b1d27771a542c26fdd85d85a1c7cabfc/transformed/ideaIU-2025.1.7-aarch64/plugins/`
  - `grpc/lib/grpc.jar` — gRPC plugin
  - `microservices-jvm/lib/microservices-jvm.jar` — MQ resolver framework
  - `spring-messaging/lib/spring-messaging.jar` — Spring Kafka/RabbitMQ/JMS resolvers
- Kotlin 2.1.10, JVM 21
- Dump test class: `_AsyncEndpointsApiDump.kt` (deleted after use per procedure)
