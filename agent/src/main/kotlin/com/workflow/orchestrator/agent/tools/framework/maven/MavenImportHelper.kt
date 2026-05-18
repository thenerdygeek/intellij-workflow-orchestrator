package com.workflow.orchestrator.agent.tools.framework.maven

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * Shared helper used by `project_structure.refresh_external_project` (feedback #5)
 * and `java_runtime_exec.compile_module(refresh_maven_first=true)` (feedback #7).
 *
 * All Maven API access is reflective so `:agent` stays compile-clean against IDEs
 * without the bundled Maven plugin (e.g., PyCharm Community).
 *
 * **2026-05-18 migration (per JetBrains source audit):**
 *  - Registration goes through `MavenOpenProjectProvider.forceLinkToExistingProjectAsync`
 *    (the trust-dialog-aware path that the "+" button in the Maven tool window uses)
 *    with `addManagedFilesOrUnignore` as fallback for older platforms.
 *  - Import-completion await uses `MavenImportListener.TOPIC` message bus subscription
 *    wrapped in `suspendCancellableCoroutine` — the prior polling `isImportingInProgress()`
 *    method does not exist on current `MavenProjectsManager` (2025.1+), so the old poll
 *    was effectively a no-op.
 */
internal sealed interface MavenDetectResult {
    object NoMavenPlugin : MavenDetectResult
    object NoPomFound : MavenDetectResult
    data class AlreadyImported(val projectCount: Int) : MavenDetectResult
    data class NewlyRegistered(val pomPaths: List<String>) : MavenDetectResult
    data class Failed(val message: String) : MavenDetectResult
}

/**
 * Detects pom.xml files at the project root + immediate subdirectories (depth 3)
 * and registers any newly-discovered ones with `MavenOpenProjectProvider`
 * (or `MavenProjectsManager.addManagedFilesOrUnignore` as fallback).
 */
internal suspend fun detectAndRegisterMaven(project: Project): MavenDetectResult {
    val mavenManagerClass = try {
        Class.forName("org.jetbrains.idea.maven.project.MavenProjectsManager")
    } catch (_: ClassNotFoundException) {
        return MavenDetectResult.NoMavenPlugin
    }

    val manager = try {
        mavenManagerClass.getMethod("getInstance", Project::class.java).invoke(null, project)
    } catch (e: Throwable) {
        return MavenDetectResult.Failed("MavenProjectsManager.getInstance failed: ${e::class.simpleName}: ${e.message}")
    }

    // Fast path: already imported.
    val projectCount = try {
        @Suppress("UNCHECKED_CAST")
        val projects = mavenManagerClass.getMethod("getProjects").invoke(manager) as List<Any>
        projects.size
    } catch (_: Throwable) { 0 }

    if (projectCount > 0) {
        return MavenDetectResult.AlreadyImported(projectCount)
    }

    // Slow path: walk for pom.xml.
    val basePath = project.basePath ?: return MavenDetectResult.NoPomFound
    val pomFiles = findPomFiles(basePath)
    if (pomFiles.isEmpty()) {
        return MavenDetectResult.NoPomFound
    }

    val vfs = LocalFileSystem.getInstance()
    val virtualPoms: List<VirtualFile> = readAction {
        pomFiles.mapNotNull { vfs.findFileByIoFile(it) }
    }
    if (virtualPoms.isEmpty()) {
        return MavenDetectResult.NoPomFound
    }

    // Preferred: MavenOpenProjectProvider — trust-dialog + auto-import-aware.
    val providerResult = tryForceLinkViaProvider(project, virtualPoms)
    if (providerResult != null) return providerResult

    // Fallback: MavenProjectsManager.addManagedFilesOrUnignore (legacy path).
    return try {
        val method = mavenManagerClass.getMethod("addManagedFilesOrUnignore", List::class.java)
        method.invoke(manager, virtualPoms)
        MavenDetectResult.NewlyRegistered(virtualPoms.map { it.path })
    } catch (e: Throwable) {
        MavenDetectResult.Failed("addManagedFilesOrUnignore failed: ${e::class.simpleName}: ${e.message}")
    }
}

/**
 * Tries the JetBrains-canonical `MavenOpenProjectProvider.forceLinkToExistingProjectAsync`
 * (suspend, trust-dialog-aware) for each pom. Returns NewlyRegistered on success,
 * null when the provider class is absent so the caller falls back to the legacy path.
 */
private suspend fun tryForceLinkViaProvider(project: Project, poms: List<VirtualFile>): MavenDetectResult? {
    val providerClass = try {
        Class.forName("org.jetbrains.idea.maven.wizards.MavenOpenProjectProvider")
    } catch (_: ClassNotFoundException) { return null }

    val provider = try { providerClass.getDeclaredConstructor().newInstance() } catch (_: Throwable) { return null }

    val registered = mutableListOf<String>()
    for (pom in poms) {
        val ok = runCatching {
            invokeSuspendUnit(
                providerClass, "forceLinkToExistingProjectAsync",
                arrayOf<Class<*>>(VirtualFile::class.java, Project::class.java),
                arrayOf<Any?>(pom, project),
                provider
            )
            true
        }.getOrDefault(false)
        if (ok) registered.add(pom.path)
    }

    return if (registered.isEmpty()) null
    else MavenDetectResult.NewlyRegistered(registered)
}

/**
 * Awaits Maven import completion via `MavenImportListener.TOPIC` message bus
 * subscription, wrapped in `suspendCancellableCoroutine` with a timeout.
 *
 * Returns true when the listener fires (or the topic class is absent — nothing to wait
 * for); false on timeout. Cancelling the coroutine cancels the subscription.
 */
internal suspend fun awaitMavenImport(project: Project, timeoutMs: Long = 30_000): Boolean {
    val listenerClass = try {
        Class.forName("org.jetbrains.idea.maven.project.MavenImportListener")
    } catch (_: ClassNotFoundException) { return true }

    val topicField = try {
        listenerClass.getField("TOPIC")
    } catch (_: NoSuchFieldException) {
        // Topic moved/renamed — fall back to legacy companion lookup
        runCatching { listenerClass.getDeclaredField("TOPIC").also { it.isAccessible = true } }.getOrNull()
            ?: return true
    }

    @Suppress("UNCHECKED_CAST")
    val topic = topicField.get(null) as? com.intellij.util.messages.Topic<Any> ?: return true

    return try {
        withTimeout(timeoutMs) {
            val deferred = CompletableDeferred<Boolean>()
            val connection = project.messageBus.connect()
            // Use a JDK proxy implementing MavenImportListener; any callback completes the wait.
            val listenerProxy = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.classLoader, arrayOf(listenerClass)
            ) { _, _, _ ->
                if (!deferred.isCompleted) deferred.complete(true)
                null
            }
            connection.subscribe(topic, listenerProxy)
            try {
                deferred.await()
            } finally {
                connection.disconnect()
            }
        }
    } catch (_: TimeoutCancellationException) {
        false
    } catch (_: Throwable) {
        // Subscription failed (API drift, topic shape changed) — degrade to no-await.
        true
    }
}

internal fun findPomFiles(basePath: String): List<File> {
    val base = File(basePath)
    if (!base.isDirectory) return emptyList()
    val results = mutableListOf<File>()
    walk(base, depth = 0, results)
    return results
}

private fun walk(dir: File, depth: Int, sink: MutableList<File>) {
    val pom = File(dir, "pom.xml")
    if (pom.isFile) sink.add(pom)
    if (depth >= MAX_DEPTH) return
    dir.listFiles()?.forEach { child ->
        if (child.isDirectory && !child.name.startsWith(".") && child.name !in EXCLUDED_DIRS) {
            walk(child, depth + 1, sink)
        }
    }
}

// Walk slightly deeper than JetBrains' depth-1 `streamPomFiles` to catch
// micro-service layouts (apps/<svc>/service/pom.xml). The expanded EXCLUDED_DIRS
// keeps false-positive cost low.
private const val MAX_DEPTH = 3

private val EXCLUDED_DIRS = setOf(
    "node_modules", "target", "build", "out", "dist", ".idea", ".gradle",
    ".m2", "venv", ".venv", "__pycache__",
    // Added 2026-05-18 per Maven plugin audit
    "cmake-build-debug", "cmake-build-release", "coverage", ".tox", ".next", ".nuxt"
)

/** See `MavenAsyncFacade.invokeSuspendObj` — same pattern, Unit-return variant. */
private suspend fun invokeSuspendUnit(
    cls: Class<*>,
    methodName: String,
    paramTypes: Array<Class<*>>,
    args: Array<Any?>,
    instance: Any
): Any? = suspendCoroutineUninterceptedOrReturn { cont: Continuation<Any?> ->
    val allParamTypes: Array<Class<*>> = paramTypes + Continuation::class.java
    val method = cls.getMethod(methodName, *allParamTypes)
    val allArgs = args + cont
    val result = method.invoke(instance, *allArgs)
    if (result === COROUTINE_SUSPENDED) COROUTINE_SUSPENDED else result
}
