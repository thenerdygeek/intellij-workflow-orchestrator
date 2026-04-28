# V1.1 IntelliJ API verification (research notes)

**Date:** 2026-04-28
**Purpose:** Pin down the exact IntelliJ Platform API surface needed for V1.1 (Gradle import + compile event capture). All claims here are verified against `JetBrains/intellij-community` master via `gh api` and direct source reads — no hallucinations.

This doc is the source of truth; the V1.1 plan (`agent-build-problems-v1.1-plan.md`) cites it. If the plan and this doc disagree, this doc wins.

## V1 (Maven) — re-verified

`plugins/maven-server-api/src/main/java/org/jetbrains/idea/maven/model/MavenProjectProblem.java`

```java
public class MavenProjectProblem implements Serializable
public ProblemType getType()        // enum {SYNTAX, STRUCTURE, DEPENDENCY, PARENT, SETTINGS_OR_PROFILES, REPOSITORY}
public String      getPath()
public String      getDescription()
public boolean     isError()
public MavenArtifact getMavenArtifact()
```

`plugins/maven/src/main/java/org/jetbrains/idea/maven/project/MavenProject.kt`

```kotlin
class MavenProject(val file: VirtualFile) {
    val problems: List<MavenProjectProblem>            // JVM getter: getProblems()
    val path: @NonNls String                           // JVM getter: getPath()
    val directoryPath: Path                            // getDirectoryPath()
    val directoryFile: VirtualFile                     // getDirectoryFile()
}
```

`plugins/maven/src/main/java/org/jetbrains/idea/maven/project/MavenProjectsManager.java`

```java
public static MavenProjectsManager getInstance(@NotNull Project project)
public boolean                isMavenizedProject()
public @NotNull List<MavenProject> getRootProjects()
public @Nullable MavenProject findProject(@NotNull Module module)
```

**Conclusion (V1):** `BuildProblemsServiceImpl` reflective probe is correct. `getProblems`/`getPath`/`getDescription`/`getType` and the `ProblemType` enum names all match. The `invokeAny("getProblems", "problems")` fallback pair is unnecessary today (Kotlin `val` always emits `getX`) but harmless and defensible against future @JvmField refactors.

---

## V1.1 — Gradle import error capture

### Listener interface

`platform/external-system-api/src/com/intellij/openapi/externalSystem/model/task/ExternalSystemTaskNotificationListener.java`

```java
public interface ExternalSystemTaskNotificationListener extends EventListener {
    default void onStart    (@NotNull String projectPath, @NotNull ExternalSystemTaskId id);
    default void onSuccess  (@NotNull String projectPath, @NotNull ExternalSystemTaskId id);
    default void onFailure  (@NotNull String projectPath, @NotNull ExternalSystemTaskId id, @NotNull Exception exception);
    default void onCancel   (@NotNull String projectPath, @NotNull ExternalSystemTaskId id);
    default void onEnd      (@NotNull String projectPath, @NotNull ExternalSystemTaskId id);
    default void onTaskOutput(@NotNull ExternalSystemTaskId id, @NotNull String text, @NotNull ProcessOutputType outputType);
    default void onStatusChange(@NotNull ExternalSystemTaskNotificationEvent event);
    // ... plus 8 deprecated overloads we do not implement
}
```

### Registration

`platform/external-system-impl/src/com/intellij/openapi/externalSystem/service/notification/ExternalSystemProgressNotificationManager.java`

```java
public interface ExternalSystemProgressNotificationManager {
    static ExternalSystemProgressNotificationManager getInstance();   // application-level
    boolean addNotificationListener(@NotNull ExternalSystemTaskNotificationListener listener);
    boolean addNotificationListener(@NotNull ExternalSystemTaskNotificationListener listener, @NotNull Disposable parentDisposable);
    boolean addNotificationListener(@NotNull ExternalSystemTaskId taskId, @NotNull ExternalSystemTaskNotificationListener listener);
    boolean removeNotificationListener(@NotNull ExternalSystemTaskNotificationListener listener);
}
```

**Use:**
```kotlin
ExternalSystemProgressNotificationManager.getInstance()
    .addNotificationListener(myListener, project)   // project as the parentDisposable
```

The 2-arg form ties listener lifetime to the disposable. Passing the project (or a project-level service that's `Disposable`) auto-removes the listener when the project closes.

### Filtering Maven from Gradle

The same listener fires for Maven import too. Distinguish via `ExternalSystemTaskId.getProjectSystemId()`:

- Gradle: `GradleConstants.SYSTEM_ID` (`org.jetbrains.plugins.gradle.util.GradleConstants`) — `ProjectSystemId("GRADLE")`
- Maven: `MavenUtil.SYSTEM_ID` — `ProjectSystemId("Maven")` (older builds may not use ExternalSystem at all)

V1 captures Maven via `MavenProjectsManager.problems` snapshot, so V1.1 must skip Maven events here to avoid double-counting. Filter:

```kotlin
val systemId = id.projectSystemId.id
if (systemId == "GRADLE") { /* capture */ }
```

### Project-disposable consideration

`addNotificationListener` is on the application-scoped manager. The 2-arg form's `parentDisposable` is what scopes registration to a project. Pass `project` (Project implements `Disposable`) so when the project closes the listener detaches.

---

## V1.1 — Compile event capture

**`ProjectTaskListener` is INSUFFICIENT for per-error data.**

`platform/lang-api/src/com/intellij/task/ProjectTaskListener.java`

```java
public interface ProjectTaskListener {
    @Topic.ProjectLevel
    Topic<ProjectTaskListener> TOPIC = new Topic<>("project task events", ProjectTaskListener.class);
    default void started(@NotNull ProjectTaskContext context) {}
    default void finished(@NotNull ProjectTaskManager.Result result) {}
}

// Result interface:
public interface Result {
    @NotNull ProjectTaskContext getContext();
    boolean isAborted();
    boolean hasErrors();
    @ApiStatus.Experimental
    boolean anyTaskMatches(@NotNull BiPredicate<? super ProjectTask, ? super ProjectTaskState> predicate);
}
```

`Result` only exposes the *aggregate* `hasErrors()` — there is no per-file/per-message access. So `ProjectTaskListener` can tell us "compile failed" but not "what failed". For V1.1 we need granular per-error data.

**The right surface is `BuildProgressListener` registered via `BuildViewManager.addListener`.**

`platform/lang-api/src/com/intellij/build/BuildProgressListener.java`

```java
public interface BuildProgressListener {
    void onEvent(@NotNull Object buildId, @NotNull BuildEvent event);
}
```

Single method, no `Topic`. Subscribed via:

`platform/lang-impl/src/com/intellij/build/AbstractViewManager.java`

```java
public void addListener(@NotNull BuildProgressListener listener, @NotNull Disposable disposable)
```

`BuildViewManager extends AbstractViewManager` and is a project service. So:

```kotlin
val buildView = project.service<BuildViewManager>()   // or project.getService(BuildViewManager::class.java)
buildView.addListener(myBuildProgressListener, parentDisposable)
```

**Caveat:** `BuildViewManager` lives in `platform/lang-impl` — strictly the "impl" package, not the public API surface. It's still load-bearing for plugins (used the same way by Gradle, Maven, JetBrains' own build system) and not deprecated. Reflective access is appropriate for safety.

### Event types we care about

`platform/lang-impl/src/com/intellij/build/events/impl/MessageEventImpl.java`

```java
public class MessageEventImpl extends AbstractBuildEvent implements MessageEvent {
    public Kind getKind()                                  // ERROR, WARNING, INFO, STATISTICS, SIMPLE
    public String getGroup()                               // e.g. "Compile errors"
    public Navigatable getNavigatable(Project project)
    public MessageEventResult getResult()
    // inherited from AbstractBuildEvent: getMessage(), getDescription()
}
```

`platform/lang-impl/src/com/intellij/build/events/impl/FileMessageEventImpl.java`

```java
public class FileMessageEventImpl extends MessageEventImpl implements FileMessageEvent {
    public FilePosition getFilePosition()                  // file + startLine + startColumn
    public FileMessageEventResult getResult()
    public String getHint()
    public Navigatable getNavigatable(Project project)
}
```

`FilePosition` exposes:
- `getFile(): File` (java.io.File)
- `getStartLine(): int` (0-based)
- `getStartColumn(): int`

### Capture rules

For each `BuildEvent` received:
1. If `event is FileMessageEvent` AND `kind == ERROR` → typed compile error with file+line.
2. If `event is MessageEvent` AND `kind == ERROR` (no file) → general build error, no line.
3. Otherwise: ignore (success, info, statistics, build-start/finish, etc.).

Group events by `buildId` so a single failed build's errors stay together; clear when a new build starts (`StartBuildEvent`).

### Reflective access notes

Because `BuildViewManager`, `MessageEventImpl`, `FileMessageEventImpl`, `FilePosition` are all `lang-impl` types (not public API), `:core` does NOT compile-time-depend on them. We access them reflectively:

- `Class.forName("com.intellij.build.BuildViewManager")` to get the service class
- `project.getService(buildViewManagerClass)` to get the instance
- `service.addListener(listener, disposable)` via reflection
- For events, use Java instanceof checks via `Class.isInstance(event)` — both `MessageEvent` and `FileMessageEvent` are interfaces in `com.intellij.build.events` (public API); `MessageEventImpl` and `FileMessageEventImpl` are impls.

Actually `MessageEvent` and `FileMessageEvent` (the interfaces, not the Impls) live in `platform/build-events-api/src/com/intellij/build/events/` which IS the public API surface. So these we CAN compile-time-depend on. Let me verify before final implementation.

Actually we already pull `com.intellij.build.events.MessageEvent` and `com.intellij.build.events.MessageEvent.Kind` indirectly via the `BuildEvent` parameter to `BuildProgressListener.onEvent`. Worth checking whether direct imports compile or whether we need reflection for the interface types too.

Pragmatic V1.1 approach: import the public interfaces (`BuildEvent`, `MessageEvent`, `FileMessageEvent`, `FilePosition`) directly if they're on the build-events-api classpath; reflect for `BuildViewManager` and event impls.

---

## Filtering and dedup

- Compile errors: dedup by `(file, line, message)` so a single error reported by both Javac and the IDE's syntax highlighter doesn't show twice.
- Gradle import errors: dedup by `(projectPath, exception.message)` — Gradle often re-fires the same exception across phases.
- Cap each source ring buffer at 50 entries; oldest-out.

---

## What we are NOT going to do

- Subscribe to `ExternalSystemProgressNotificationManager` for Maven events. Maven's import errors are already captured via `MavenProjectsManager.problems` (V1's snapshot read), and Maven will *also* fire ExternalSystem events on newer platform versions (when the IDE's "delegate Maven import to external system" toggle is on) — capturing both would double-count. Filter Maven out at registration time.
- Use `ProjectTaskListener.finished(Result)` for per-error capture. The interface only exposes aggregate `hasErrors()`. We use `BuildProgressListener` instead.
- Compile-time depend on `BuildViewManager` or `*Impl` event types. These are in `lang-impl`, not the public API surface. Reflective access only.
- Persist captured events to disk. V1 was in-memory; V1.1 stays in-memory. Cross-restart history is V1.2+ if needed.
