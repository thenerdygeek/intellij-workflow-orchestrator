package com.workflow.orchestrator.core.services.impl

import com.intellij.build.FilePosition
import com.intellij.build.events.BuildEvent
import com.intellij.build.events.FileMessageEvent
import com.intellij.build.events.MessageEvent
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.service.notification.ExternalSystemProgressNotificationManager
import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBusConnection
import com.workflow.orchestrator.core.model.build.BuildProblem
import com.workflow.orchestrator.core.model.build.BuildSource
import com.workflow.orchestrator.core.model.build.ProblemType
import com.workflow.orchestrator.core.model.build.Severity
import com.workflow.orchestrator.core.services.BuildEventCaptureService
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * V1.1 — captures Gradle import + compile errors into per-source ring buffers
 * so [BuildProblemsServiceImpl] can read them snapshot-style.
 *
 * **Threading.** `record` is called from listener callbacks on background threads.
 * Per-source `ArrayDeque`s are protected by a single `lock` object — read/write
 * volume is low (handful per build) so a coarse lock is fine.
 *
 * **Listener installation.** Constructor is intentionally side-effect-free for
 * unit-testability. [installCaptureListeners] is called by
 * `BuildEventCaptureProjectActivity` (post-startup) so the project-scoped
 * disposable lifetime is correct.
 */
@Service(Service.Level.PROJECT)
class BuildEventCaptureServiceImpl(
    private val project: Project,
) : BuildEventCaptureService, Disposable {

    private val log = Logger.getInstance(BuildEventCaptureServiceImpl::class.java)

    private val lock = Any()
    private val byBuildSource: MutableMap<BuildSource, ArrayDeque<BuildProblem>> = mutableMapOf()

    /** Stderr buffer per running Gradle import so onFailure can parse the captured text. */
    private val gradleStderrBuffer: MutableMap<ExternalSystemTaskId, StringBuilder> = mutableMapOf()

    @Volatile private var listenersInstalled = false

    override fun snapshot(source: BuildSource): List<BuildProblem> = synchronized(lock) {
        byBuildSource[source]?.toList() ?: emptyList()
    }

    override fun record(problem: BuildProblem): Unit = synchronized(lock) {
        val deque = byBuildSource.getOrPut(problem.source) { ArrayDeque() }
        if (deque.size >= MAX_PER_SOURCE) deque.removeFirst()
        deque.addLast(problem)
    }

    override fun clear(source: BuildSource): Unit = synchronized(lock) {
        byBuildSource.remove(source)
    }

    /**
     * Wire the two event-time captures. Idempotent — safe to call multiple times,
     * subsequent calls no-op. Called from `BuildEventCaptureProjectActivity`.
     */
    fun installCaptureListeners() {
        if (listenersInstalled) return
        listenersInstalled = true
        installGradleImportListener()
        installCompileEventListener()
    }

    private fun installGradleImportListener() {
        val listener = object : ExternalSystemTaskNotificationListener {
            // 2025.1 platform exposes only the deprecated `boolean stdOut` overload.
            // The newer `ProcessOutputType` overload is master-only; if/when we bump
            // platformVersion past it, swap to the typed form.
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onTaskOutput(id: ExternalSystemTaskId, text: String, stdOut: Boolean) {
                if (id.projectSystemId.id != GRADLE_SYSTEM_ID) return
                if (stdOut) return  // only retain stderr for failure-time parsing
                synchronized(lock) {
                    gradleStderrBuffer.getOrPut(id) { StringBuilder() }.append(text)
                }
            }

            override fun onFailure(projectPath: String, id: ExternalSystemTaskId, exception: Exception) {
                if (id.projectSystemId.id != GRADLE_SYSTEM_ID) return
                val stderr = synchronized(lock) { gradleStderrBuffer.remove(id)?.toString() ?: "" }
                val combined = listOf(exception.message.orEmpty(), stderr).filter { it.isNotBlank() }.joinToString("\n")
                if (combined.isNotBlank()) {
                    GradleErrorParser.parse(projectPath, combined).forEach(::record)
                }
            }

            override fun onSuccess(projectPath: String, id: ExternalSystemTaskId) {
                if (id.projectSystemId.id != GRADLE_SYSTEM_ID) return
                synchronized(lock) { gradleStderrBuffer.remove(id) }
            }

            override fun onCancel(projectPath: String, id: ExternalSystemTaskId) {
                if (id.projectSystemId.id != GRADLE_SYSTEM_ID) return
                synchronized(lock) { gradleStderrBuffer.remove(id) }
            }
        }
        try {
            ExternalSystemProgressNotificationManager.getInstance()
                .addNotificationListener(listener, this)
        } catch (e: Throwable) {
            log.warn("[BuildEventCapture] Could not register Gradle import listener", e)
        }
    }

    /**
     * Compile-error capture via `BuildViewManager.addListener(BuildProgressListener, Disposable)`.
     *
     * `BuildViewManager` lives in `platform/lang-impl` and is not a public-API class —
     * we access it reflectively, mirroring the Maven probe approach in
     * [ReflectiveMavenProblemsProbe]. The `BuildEvent` interface itself IS public API
     * (`platform/lang-api/src/com/intellij/build/events/BuildEvent.java`), so the
     * listener body imports the interfaces directly and only the registration is
     * reflective.
     */
    private fun installCompileEventListener() {
        try {
            val viewManagerClass = Class.forName("com.intellij.build.BuildViewManager")
            val service = project.getService(viewManagerClass) ?: return
            val listenerInterface = Class.forName("com.intellij.build.BuildProgressListener")

            val handler = InvocationHandler { _, method, args ->
                if (method.name == "onEvent" && args != null && args.size == 2) {
                    val event = args[1] as? BuildEvent
                    if (event != null) handleBuildEvent(event)
                }
                null
            }
            val proxyListener = Proxy.newProxyInstance(
                listenerInterface.classLoader,
                arrayOf(listenerInterface),
                handler,
            )
            val addListener = viewManagerClass.getMethod(
                "addListener",
                listenerInterface,
                Disposable::class.java,
            )
            addListener.invoke(service, proxyListener, this)
        } catch (e: ClassNotFoundException) {
            // Build view API not present on this platform — silent no-op.
            log.info("[BuildEventCapture] BuildViewManager not present; compile-event capture disabled")
        } catch (e: Throwable) {
            log.warn("[BuildEventCapture] Could not register compile-event listener", e)
        }
    }

    private fun handleBuildEvent(event: BuildEvent) {
        if (event !is MessageEvent) return
        if (event.kind != MessageEvent.Kind.ERROR) return

        val (path, line) = if (event is FileMessageEvent) {
            val pos: FilePosition? = event.filePosition
            (pos?.file?.absolutePath ?: "") to pos?.startLine?.let { it + 1 }
        } else {
            "" to null
        }

        val description = event.description?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: event.message
        record(
            BuildProblem(
                source = BuildSource.COMPILE,
                projectPath = path,
                description = description,
                type = ProblemType.COMPILE,
                severity = Severity.ERROR,
                line = line,
            )
        )
    }

    override fun dispose() {
        synchronized(lock) {
            byBuildSource.clear()
            gradleStderrBuffer.clear()
        }
    }

    companion object {
        const val MAX_PER_SOURCE = 50
        // ProjectSystemId.id for Gradle. Using a string literal to avoid a compile-time
        // dependency on `org.jetbrains.plugins.gradle.util.GradleConstants`.
        private const val GRADLE_SYSTEM_ID = "GRADLE"
    }
}
