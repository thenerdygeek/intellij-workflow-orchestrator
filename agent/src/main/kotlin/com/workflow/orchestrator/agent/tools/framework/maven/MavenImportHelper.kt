package com.workflow.orchestrator.agent.tools.framework.maven

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Shared helper used by `project_structure.refresh_external_project` (feedback #5)
 * and `java_runtime_exec.compile_module(refresh_maven_first=true)` (feedback #7).
 *
 * All Maven API access is reflective so `:agent` stays compile-clean against IDEs
 * without the bundled Maven plugin (e.g., PyCharm Community).
 */
internal sealed interface MavenDetectResult {
    object NoMavenPlugin : MavenDetectResult
    object NoPomFound : MavenDetectResult
    data class AlreadyImported(val projectCount: Int) : MavenDetectResult
    data class NewlyRegistered(val pomPaths: List<String>) : MavenDetectResult
    data class Failed(val message: String) : MavenDetectResult
}

/**
 * Detects pom.xml files at the project root + immediate subdirectories (top 2 levels)
 * and registers any newly-discovered ones with MavenProjectsManager.
 *
 * Returns a structured result the caller can use to decide whether to await the import,
 * report a warning, or fall through to other detection paths (e.g., Gradle).
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

    // Slow path: walk top 2 levels for pom.xml.
    val basePath = project.basePath ?: return MavenDetectResult.NoPomFound
    val pomFiles = findPomFiles(basePath)
    if (pomFiles.isEmpty()) {
        return MavenDetectResult.NoPomFound
    }

    // Resolve VirtualFiles (VFS access requires a read action).
    val vfs = LocalFileSystem.getInstance()
    val virtualPoms: List<VirtualFile> = readAction {
        pomFiles.mapNotNull { vfs.findFileByIoFile(it) }
    }
    if (virtualPoms.isEmpty()) {
        return MavenDetectResult.NoPomFound
    }

    return try {
        val method = mavenManagerClass.getMethod("addManagedFilesOrUnignore", List::class.java)
        method.invoke(manager, virtualPoms)
        MavenDetectResult.NewlyRegistered(virtualPoms.map { it.path })
    } catch (e: Throwable) {
        MavenDetectResult.Failed("addManagedFilesOrUnignore failed: ${e::class.simpleName}: ${e.message}")
    }
}

/**
 * Polls `MavenProjectsManager.isImportingInProgress` until it reports false or [timeoutMs]
 * elapses. Returns true on completion, false on timeout. Returns true immediately when the
 * Maven plugin isn't installed (nothing to await).
 */
internal suspend fun awaitMavenImport(project: Project, timeoutMs: Long = 30_000): Boolean {
    val mavenManagerClass = try {
        Class.forName("org.jetbrains.idea.maven.project.MavenProjectsManager")
    } catch (_: ClassNotFoundException) {
        return true
    }
    val manager = try {
        mavenManagerClass.getMethod("getInstance", Project::class.java).invoke(null, project)
    } catch (_: Throwable) {
        return true
    }
    val isImportingMethod = try {
        mavenManagerClass.getMethod("isImportingInProgress")
    } catch (_: NoSuchMethodException) {
        // API drift — assume done.
        return true
    }

    return try {
        withTimeout(timeoutMs) {
            while (true) {
                val busy = try {
                    isImportingMethod.invoke(manager) as? Boolean ?: false
                } catch (_: Throwable) { false }
                if (!busy) return@withTimeout true
                delay(POLL_INTERVAL_MS)
            }
            @Suppress("UNREACHABLE_CODE") true
        }
    } catch (_: TimeoutCancellationException) {
        false
    }
}

private const val POLL_INTERVAL_MS = 200L
private const val MAX_DEPTH = 2

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

private val EXCLUDED_DIRS = setOf(
    "node_modules", "target", "build", "out", "dist", ".idea", ".gradle",
    ".m2", "venv", ".venv", "__pycache__"
)
