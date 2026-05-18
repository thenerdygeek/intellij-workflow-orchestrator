package com.workflow.orchestrator.agent.tools.framework.maven

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * Reflective façade over the JetBrains Maven plugin's modern async API
 * (`org.jetbrains.idea.maven.project.MavenAsyncProjectsManager`, marked
 * `@ApiStatus.Experimental` — but the only supported per-module entry point
 * in 2025.1+).
 *
 * Reflective so `:agent` stays compile-clean against IDEs without the bundled
 * Maven plugin (PyCharm Community, WebStorm, etc.). Method handles are cached
 * via `Lazy` so the per-tool-invocation cost is one HashMap lookup, not
 * `Class.forName` + `getMethod`.
 *
 * Every public method returns a structured result the caller can degrade
 * gracefully on — never throws on missing classes/methods. When the Maven
 * plugin is absent or the API drifts, the facade reports the failure mode
 * and callers fall back to the legacy `forceUpdateAllProjectsOrFindAllAvailablePomFiles`
 * (kept as a safety net in `MavenImportHelper`).
 */
internal object MavenAsyncFacade {

    sealed interface CallResult {
        object Triggered : CallResult
        data class Unavailable(val reason: String) : CallResult
        data class Failed(val message: String) : CallResult
    }

    sealed interface AwaitResult {
        object Completed : AwaitResult
        data class Unavailable(val reason: String) : AwaitResult
        data class Failed(val message: String) : AwaitResult
    }

    // ── cached class handles ─────────────────────────────────────────────────

    private val mavenProjectsManagerClass: Class<*>? by lazy {
        loadClass("org.jetbrains.idea.maven.project.MavenProjectsManager")
    }
    private val mavenAsyncProjectsManagerClass: Class<*>? by lazy {
        loadClass("org.jetbrains.idea.maven.project.MavenAsyncProjectsManager")
    }
    private val mavenSyncSpecClass: Class<*>? by lazy {
        loadClass("org.jetbrains.idea.maven.buildtool.MavenSyncSpec")
    }
    private val mavenDownloadSourcesRequestClass: Class<*>? by lazy {
        loadClass("org.jetbrains.idea.maven.project.MavenDownloadSourcesRequest")
    }
    private val mavenProjectClass: Class<*>? by lazy {
        loadClass("org.jetbrains.idea.maven.project.MavenProject")
    }

    private fun loadClass(fqn: String): Class<*>? = runCatching { Class.forName(fqn) }.getOrNull()

    fun isAvailable(): Boolean =
        mavenAsyncProjectsManagerClass != null && mavenSyncSpecClass != null

    // ── manager + spec helpers ───────────────────────────────────────────────

    fun getManager(project: Project): Any? {
        val cls = mavenProjectsManagerClass ?: return null
        return runCatching {
            cls.getMethod("getInstance", Project::class.java).invoke(null, project)
        }.getOrNull()
    }

    /**
     * Builds a `MavenSyncSpec` via the companion factory. Tries `@JvmStatic`
     * placement first (static method on the interface), then falls back to
     * `Companion.incremental(...)` invocation pattern.
     */
    fun makeSyncSpec(description: String, incremental: Boolean = true): Any? {
        val cls = mavenSyncSpecClass ?: return null
        val factory = if (incremental) "incremental" else "full"

        // Pattern 1: @JvmStatic on companion (most common in JetBrains Kotlin code)
        runCatching {
            return cls.getMethod(factory, String::class.java).invoke(null, description)
        }
        // Pattern 2: Companion instance method
        runCatching {
            val companionField = cls.getField("Companion")
            val companion = companionField.get(null)
            return companion.javaClass.getMethod(factory, String::class.java).invoke(companion, description)
        }
        // Pattern 3 (defensive): some versions add a boolean resolveIncrementally arg
        runCatching {
            val companion = cls.getField("Companion").get(null)
            return companion.javaClass.getMethod(factory, String::class.java, Boolean::class.javaPrimitiveType)
                .invoke(companion, description, incremental)
        }
        return null
    }

    // ── reload (all + per-module) ────────────────────────────────────────────

    fun scheduleUpdateAllMavenProjects(project: Project, description: String): CallResult {
        val manager = getManager(project) ?: return CallResult.Unavailable("MavenProjectsManager not available")
        val asyncCls = mavenAsyncProjectsManagerClass ?: return CallResult.Unavailable("MavenAsyncProjectsManager not available")
        val specCls = mavenSyncSpecClass ?: return CallResult.Unavailable("MavenSyncSpec not available")
        val spec = makeSyncSpec(description, incremental = false)
            ?: return CallResult.Failed("Could not build MavenSyncSpec")

        return runCatching {
            asyncCls.getMethod("scheduleUpdateAllMavenProjects", specCls).invoke(manager, spec)
            CallResult.Triggered
        }.getOrElse { CallResult.Failed("scheduleUpdateAllMavenProjects failed: ${it::class.simpleName}: ${it.message}") }
    }

    fun scheduleUpdateMavenProjects(
        project: Project,
        description: String,
        filesToUpdate: List<VirtualFile>,
        filesToDelete: List<VirtualFile> = emptyList()
    ): CallResult {
        val manager = getManager(project) ?: return CallResult.Unavailable("MavenProjectsManager not available")
        val asyncCls = mavenAsyncProjectsManagerClass ?: return CallResult.Unavailable("MavenAsyncProjectsManager not available")
        val specCls = mavenSyncSpecClass ?: return CallResult.Unavailable("MavenSyncSpec not available")
        val spec = makeSyncSpec(description, incremental = true)
            ?: return CallResult.Failed("Could not build MavenSyncSpec")

        return runCatching {
            asyncCls.getMethod(
                "scheduleUpdateMavenProjects", specCls, List::class.java, List::class.java
            ).invoke(manager, spec, filesToUpdate, filesToDelete)
            CallResult.Triggered
        }.getOrElse { CallResult.Failed("scheduleUpdateMavenProjects failed: ${it::class.simpleName}: ${it.message}") }
    }

    // ── suspend variants (await completion) ──────────────────────────────────

    suspend fun updateAllMavenProjects(project: Project, description: String): AwaitResult {
        val manager = getManager(project) ?: return AwaitResult.Unavailable("MavenProjectsManager not available")
        val asyncCls = mavenAsyncProjectsManagerClass ?: return AwaitResult.Unavailable("MavenAsyncProjectsManager not available")
        val specCls = mavenSyncSpecClass ?: return AwaitResult.Unavailable("MavenSyncSpec not available")
        val spec = makeSyncSpec(description, incremental = false)
            ?: return AwaitResult.Failed("Could not build MavenSyncSpec")

        return runCatching {
            invokeSuspendObj(
                asyncCls, "updateAllMavenProjects",
                arrayOf<Class<*>>(specCls), arrayOf<Any?>(spec), manager
            )
            AwaitResult.Completed
        }.getOrElse { AwaitResult.Failed("updateAllMavenProjects failed: ${it::class.simpleName}: ${it.message}") }
    }

    suspend fun updateMavenProjects(
        project: Project,
        description: String,
        filesToUpdate: List<VirtualFile>,
        filesToDelete: List<VirtualFile> = emptyList()
    ): AwaitResult {
        val manager = getManager(project) ?: return AwaitResult.Unavailable("MavenProjectsManager not available")
        val asyncCls = mavenAsyncProjectsManagerClass ?: return AwaitResult.Unavailable("MavenAsyncProjectsManager not available")
        val specCls = mavenSyncSpecClass ?: return AwaitResult.Unavailable("MavenSyncSpec not available")
        val spec = makeSyncSpec(description, incremental = true)
            ?: return AwaitResult.Failed("Could not build MavenSyncSpec")

        return runCatching {
            invokeSuspendObj(
                asyncCls, "updateMavenProjects",
                arrayOf<Class<*>>(specCls, List::class.java, List::class.java),
                arrayOf<Any?>(spec, filesToUpdate, filesToDelete),
                manager
            )
            AwaitResult.Completed
        }.getOrElse { AwaitResult.Failed("updateMavenProjects failed: ${it::class.simpleName}: ${it.message}") }
    }

    // ── downloads ────────────────────────────────────────────────────────────

    /**
     * Builds `MavenDownloadSourcesRequest` via its `builder()` chain. `forProjects` accepts
     * a collection of `MavenProject` instances (resolved by the caller). Returns null when
     * the request class is absent or the builder API has drifted.
     */
    fun buildDownloadRequest(
        forProjects: Collection<Any>,
        downloadSources: Boolean,
        downloadDocs: Boolean
    ): Any? {
        val cls = mavenDownloadSourcesRequestClass ?: return null
        return runCatching {
            val builder = cls.getMethod("builder").invoke(null)
            val bCls = builder.javaClass
            bCls.getMethod("forProjects", Collection::class.java).invoke(builder, forProjects)
            bCls.getMethod("forAllArtifacts").invoke(builder)
            bCls.getMethod("downloadSources", Boolean::class.javaPrimitiveType).invoke(builder, downloadSources)
            bCls.getMethod("downloadDocs", Boolean::class.javaPrimitiveType).invoke(builder, downloadDocs)
            bCls.getMethod("build").invoke(builder)
        }.getOrNull()
    }

    fun scheduleDownloadArtifacts(project: Project, request: Any): CallResult {
        val manager = getManager(project) ?: return CallResult.Unavailable("MavenProjectsManager not available")
        val asyncCls = mavenAsyncProjectsManagerClass ?: return CallResult.Unavailable("MavenAsyncProjectsManager not available")
        val reqCls = mavenDownloadSourcesRequestClass ?: return CallResult.Unavailable("MavenDownloadSourcesRequest not available")

        return runCatching {
            asyncCls.getMethod("scheduleDownloadArtifacts", reqCls).invoke(manager, request)
            CallResult.Triggered
        }.getOrElse { CallResult.Failed("scheduleDownloadArtifacts failed: ${it::class.simpleName}: ${it.message}") }
    }

    // ── module → MavenProject resolution ─────────────────────────────────────

    /**
     * Resolves a pom.xml `VirtualFile` to its `MavenProject` instance via
     * `MavenProjectsManager.findProject(VirtualFile)`. Returns null when the
     * file isn't tracked as a Maven module.
     */
    fun findProjectByPomFile(project: Project, pomFile: VirtualFile): Any? {
        val manager = getManager(project) ?: return null
        val cls = mavenProjectsManagerClass ?: return null
        return runCatching {
            cls.getMethod("findProject", VirtualFile::class.java).invoke(manager, pomFile)
        }.getOrNull()
    }

    /** Returns all known `MavenProject` instances (empty when manager absent). */
    @Suppress("UNCHECKED_CAST")
    fun getAllProjects(project: Project): List<Any> {
        val manager = getManager(project) ?: return emptyList()
        val cls = mavenProjectsManagerClass ?: return emptyList()
        return runCatching {
            (cls.getMethod("getProjects").invoke(manager) as? List<Any>) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /**
     * Returns the pom.xml `VirtualFile` for a `MavenProject`. Reflectively reads
     * `MavenProject.getFile()`. Used when building DataContext for action-based dispatch.
     */
    fun getMavenProjectFile(mavenProject: Any): VirtualFile? {
        val cls = mavenProjectClass ?: return null
        return runCatching {
            cls.getMethod("getFile").invoke(mavenProject) as? VirtualFile
        }.getOrNull()
    }
}

/**
 * Calls a Kotlin `suspend fun` via reflection. The Kotlin compiler appends a
 * `Continuation` parameter to the JVM signature; we hand it the current
 * coroutine's continuation and either return the synchronous result or
 * propagate `COROUTINE_SUSPENDED` so the coroutine machinery resumes us when
 * the suspend completes.
 */
private suspend fun invokeSuspendObj(
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
