package com.workflow.orchestrator.handover.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.util.ProjectIdentifier
import com.workflow.orchestrator.handover.model.HandoverTemplate
import com.workflow.orchestrator.handover.model.HandoverTemplateAction
import com.workflow.orchestrator.handover.model.HandoverTemplateOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.WatchService
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.io.path.writeText

// ---------------------------------------------------------------------------
// Pluggable classpath bundle source
// ---------------------------------------------------------------------------

/**
 * Loads the bundled (read-only) templates.  Separated so unit tests can inject
 * a fixed in-memory set without needing real classpath resources.
 */
interface BundledTemplateLoader {
    fun load(): List<HandoverTemplate>
}

/**
 * Production loader — reads from classpath `/handover/templates/{action}/<name>.<ext>`.
 * Supports both `file:` URLs (class-folder development run) and `jar:` URLs (production
 * plugin JAR), so bundled templates are always visible regardless of how the plugin is loaded.
 */
internal object ClasspathBundledLoader : BundledTemplateLoader {

    private val log = Logger.getInstance(ClasspathBundledLoader::class.java)

    override fun load(): List<HandoverTemplate> = buildList {
        for (action in HandoverTemplateAction.entries) {
            val actionDir = action.name.lowercase()
            val ext = if (action == HandoverTemplateAction.JIRA) "wiki" else "html"
            for (name in listResourceNames("/handover/templates/$actionDir/", ext)) {
                val source = readResource("/handover/templates/$actionDir/$name") ?: continue
                val baseName = name.substringBefore(".")
                add(
                    HandoverTemplate(
                        id = "$actionDir/$baseName",
                        name = baseName.replace('-', ' ').replaceFirstChar { it.uppercase() },
                        action = action,
                        source = source,
                        origin = HandoverTemplateOrigin.BUNDLED,
                    )
                )
            }
        }
    }

    private fun readResource(path: String): String? =
        ClasspathBundledLoader::class.java.getResourceAsStream(path)
            ?.bufferedReader()
            ?.use { it.readText() }

    /**
     * Reads the `index.txt` file co-located with the templates in the JAR.
     * Using an explicit index avoids directory-URL enumeration, which breaks under
     * IntelliJ's `PathClassLoader` (used in production and in the test sandbox) because
     * `getResource(dir/)` returns null for JAR-packaged directory entries.
     * Falls back to `file:`-based enumeration for class-folder dev runs where the index
     * may not have been written to the output directory yet.
     */
    private fun listResourceNames(dirPath: String, ext: String): List<String> {
        val cls = ClasspathBundledLoader::class.java
        // Primary: read index.txt shipped alongside the templates
        val indexPath = dirPath.trimEnd('/') + "/index.txt"
        val indexStream = cls.getResourceAsStream(indexPath)
        if (indexStream != null) {
            return indexStream.bufferedReader().use { reader ->
                reader.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && it.endsWith(".$ext") }
                    .toList()
            }
        }
        // Fallback: file: URL directory scan (class-folder dev run without index.txt on disk)
        val url = cls.getResource(dirPath) ?: return emptyList()
        return try {
            if (url.protocol == "file") {
                val dir = java.io.File(url.toURI())
                dir.listFiles { _, n -> n.endsWith(".$ext") }?.map { it.name } ?: emptyList()
            } else {
                log.warn("ClasspathBundledLoader: no index.txt and unsupported URL scheme '${url.protocol}' at $dirPath")
                emptyList()
            }
        } catch (e: Exception) {
            log.warn("ClasspathBundledLoader: failed to list resources at $dirPath", e)
            emptyList()
        }
    }
}

// ---------------------------------------------------------------------------
// Service
// ---------------------------------------------------------------------------

private const val DEBOUNCE_MS = 300L

private fun defaultExtension(action: HandoverTemplateAction) = when (action) {
    HandoverTemplateAction.JIRA -> "wiki"
    HandoverTemplateAction.EMAIL -> "html"
}

private fun isValidExtension(action: HandoverTemplateAction, ext: String): Boolean = when (action) {
    HandoverTemplateAction.JIRA -> ext == "wiki"
    HandoverTemplateAction.EMAIL -> ext == "html"
}

private fun actionSubDir(action: HandoverTemplateAction) = action.name.lowercase()

/**
 * Project-scoped service that merges handover templates from three layers:
 *
 *  1. **BUNDLED** — classpath `/handover/templates/{action}/<name>.<ext>` (read-only)
 *  2. **GLOBAL**  — `~/.workflow-orchestrator/handover/templates/{action}/<name>.<ext>`
 *  3. **PROJECT** — `~/.workflow-orchestrator/{projectDirHash}/handover/templates/{action}/<name>.<ext>`
 *
 * Later layers win by `id` (`{action}/{baseName}`).  File watches on the two
 * writable dirs trigger a 300 ms debounced re-scan.
 */
@Service(Service.Level.PROJECT)
class HandoverTemplateStore {

    private val log = Logger.getInstance(HandoverTemplateStore::class.java)

    private val globalDir: Path
    private val projectDir: Path
    private val bundledLoader: BundledTemplateLoader
    private val cs: CoroutineScope

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /** IntelliJ platform DI constructor. */
    constructor(project: Project, cs: CoroutineScope) {
        val basePath = project.basePath ?: System.getProperty("user.home")
        val home = Path.of(System.getProperty("user.home"))
        this.globalDir = home.resolve(".workflow-orchestrator/handover/templates")
        this.projectDir = home.resolve(
            ".workflow-orchestrator/${ProjectIdentifier.compute(basePath)}/handover/templates"
        )
        this.bundledLoader = ClasspathBundledLoader
        this.cs = cs
        init()
    }

    /** Test constructor — allows injecting dirs and a fake bundled loader. */
    constructor(
        globalDir: Path,
        projectDir: Path,
        bundledLoader: BundledTemplateLoader,
        cs: CoroutineScope,
    ) {
        this.globalDir = globalDir
        this.projectDir = projectDir
        this.bundledLoader = bundledLoader
        this.cs = cs
        init()
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    private val _templates = MutableStateFlow<List<HandoverTemplate>>(emptyList())
    val templates: StateFlow<List<HandoverTemplate>> = _templates.asStateFlow()

    /**
     * Creates a new GLOBAL template.  Fails if a template with the merged id
     * already exists in GLOBAL or PROJECT (use [duplicate] for that case).
     */
    suspend fun create(
        name: String,
        action: HandoverTemplateAction,
        sourceText: String,
    ): HandoverTemplate = withContext(Dispatchers.IO) {
        val id = "${actionSubDir(action)}/$name"
        val existing = _templates.value.find { it.id == id }
        if (existing != null && existing.origin != HandoverTemplateOrigin.BUNDLED) {
            throw IllegalArgumentException(
                "Template with id '$id' already exists in ${existing.origin}"
            )
        }
        val ext = defaultExtension(action)
        val file = globalDir.resolve("${actionSubDir(action)}/$name.$ext")
        Files.createDirectories(file.parent)
        file.writeText(sourceText)
        val template = HandoverTemplate(
            id = id,
            name = name,
            action = action,
            source = sourceText,
            origin = HandoverTemplateOrigin.GLOBAL,
        )
        rescan()
        template
    }

    /**
     * Renames the file backing [id].  BUNDLED templates are immutable.
     * If the template is PROJECT-origin, the global template with [newName]
     * must not already exist (it would become shadowed).
     */
    suspend fun rename(id: String, newName: String): Unit = withContext(Dispatchers.IO) {
        val template = findOrThrow(id)
        if (template.origin == HandoverTemplateOrigin.BUNDLED) {
            throw UnsupportedOperationException("Cannot rename bundled template")
        }
        val action = template.action
        val ext = defaultExtension(action)
        val newId = "${actionSubDir(action)}/$newName"
        if (template.origin == HandoverTemplateOrigin.PROJECT) {
            // Reject if a global template already has the new name
            val clash = _templates.value.find { it.id == newId && it.origin == HandoverTemplateOrigin.GLOBAL }
            if (clash != null) {
                throw IllegalArgumentException(
                    "A GLOBAL template with name '$newName' already exists"
                )
            }
        }
        val dir = dirFor(template.origin, action)
        val oldFile = dir.resolve("${template.name}.$ext")
        val newFile = dir.resolve("$newName.$ext")
        Files.move(oldFile, newFile)
        rescan()
    }

    /**
     * Deletes the file backing [id].  BUNDLED templates are immutable.
     * After deleting a PROJECT template, any GLOBAL or BUNDLED of the same id
     * resurfaces automatically on the next rescan.
     */
    suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        val template = findOrThrow(id)
        if (template.origin == HandoverTemplateOrigin.BUNDLED) {
            throw UnsupportedOperationException("Cannot delete bundled template")
        }
        val ext = defaultExtension(template.action)
        val file = dirFor(template.origin, template.action).resolve("${template.name}.$ext")
        Files.deleteIfExists(file)
        rescan()
    }

    /**
     * Copies the source content of [sourceId] into a new GLOBAL file named [newName].
     * Returns the new template.
     */
    suspend fun duplicate(sourceId: String, newName: String): HandoverTemplate = withContext(Dispatchers.IO) {
        val source = findOrThrow(sourceId)
        val action = source.action
        val ext = defaultExtension(action)
        val newId = "${actionSubDir(action)}/$newName"
        val destFile = globalDir.resolve("${actionSubDir(action)}/$newName.$ext")
        Files.createDirectories(destFile.parent)
        destFile.writeText(source.source)
        val template = HandoverTemplate(
            id = newId,
            name = newName,
            action = action,
            source = source.source,
            origin = HandoverTemplateOrigin.GLOBAL,
        )
        rescan()
        template
    }

    /**
     * Overwrites the source content of [id].  BUNDLED templates are immutable.
     * PROJECT templates are written to the project dir; GLOBAL to the global dir.
     */
    suspend fun update(id: String, newSource: String): Unit = withContext(Dispatchers.IO) {
        val template = findOrThrow(id)
        if (template.origin == HandoverTemplateOrigin.BUNDLED) {
            throw UnsupportedOperationException("Cannot update bundled template")
        }
        val ext = defaultExtension(template.action)
        val file = dirFor(template.origin, template.action).resolve("${template.name}.$ext")
        file.writeText(newSource)
        rescan()
    }

    // -----------------------------------------------------------------------
    // Companion
    // -----------------------------------------------------------------------

    companion object {
        fun getInstance(project: Project): HandoverTemplateStore =
            project.getService(HandoverTemplateStore::class.java)
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private fun init() {
        ensureDirs()
        rescan()
        startWatcher()
    }

    private fun ensureDirs() {
        for (action in HandoverTemplateAction.entries) {
            runCatching { Files.createDirectories(globalDir.resolve(actionSubDir(action))) }
            runCatching { Files.createDirectories(projectDir.resolve(actionSubDir(action))) }
        }
    }

    @Volatile
    private var debounceJob: Job? = null

    private fun scheduleRescan() {
        debounceJob?.cancel()
        debounceJob = cs.launch(Dispatchers.IO) {
            delay(DEBOUNCE_MS)
            rescan()
        }
    }

    private fun rescan() {
        val merged = mutableMapOf<String, HandoverTemplate>()

        // Layer 1: bundled (lowest priority)
        for (t in bundledLoader.load()) merged[t.id] = t

        // Layer 2: user global
        for (t in scanDir(globalDir, HandoverTemplateOrigin.GLOBAL)) merged[t.id] = t

        // Layer 3: per-project (highest priority)
        for (t in scanDir(projectDir, HandoverTemplateOrigin.PROJECT)) merged[t.id] = t

        _templates.value = merged.values.toList()
    }

    private fun scanDir(root: Path, origin: HandoverTemplateOrigin): List<HandoverTemplate> {
        if (!root.exists()) return emptyList()
        val result = mutableListOf<HandoverTemplate>()
        for (action in HandoverTemplateAction.entries) {
            val subDir = root.resolve(actionSubDir(action))
            if (!subDir.exists()) continue
            Files.list(subDir).use { stream ->
                stream.forEach { file ->
                    if (!Files.isRegularFile(file)) return@forEach
                    val ext = file.extension.lowercase()
                    if (!isValidExtension(action, ext)) return@forEach
                    val name = file.nameWithoutExtension
                    val id = "${actionSubDir(action)}/$name"
                    result += HandoverTemplate(
                        id = id,
                        name = name,
                        action = action,
                        source = runCatching { file.readText() }.getOrDefault(""),
                        origin = origin,
                    )
                }
            }
        }
        return result
    }

    private fun dirFor(origin: HandoverTemplateOrigin, action: HandoverTemplateAction): Path {
        val root = when (origin) {
            HandoverTemplateOrigin.GLOBAL -> globalDir
            HandoverTemplateOrigin.PROJECT -> projectDir
            HandoverTemplateOrigin.BUNDLED -> error("BUNDLED templates are read-only")
        }
        return root.resolve(actionSubDir(action))
    }

    private fun findOrThrow(id: String): HandoverTemplate =
        _templates.value.find { it.id == id }
            ?: throw NoSuchElementException("Template '$id' not found")

    /**
     * Registers [dir] with [watchService] for CREATE / DELETE / MODIFY events.
     * Extracted so both the initial setup and the runtime "new sub-dir" path share it.
     */
    private fun registerDir(dir: Path, watchService: WatchService) {
        runCatching { dir.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY) }
            .onFailure { log.warn("HandoverTemplateStore: cannot watch $dir", it) }
    }

    private fun startWatcher() {
        val watchService: WatchService = FileSystems.getDefault().newWatchService()

        // Register the WatchService close on the parent scope's coroutine completion so
        // that any residual take() call is cleaned up even if the coroutine was already done.
        cs.coroutineContext[Job]?.invokeOnCompletion {
            runCatching { watchService.close() }
        }

        cs.launch(Dispatchers.IO) {
            // Register all existing action sub-directories
            val dirsToWatch = mutableListOf<Path>()
            for (action in HandoverTemplateAction.entries) {
                val g = globalDir.resolve(actionSubDir(action))
                val p = projectDir.resolve(actionSubDir(action))
                if (g.exists()) dirsToWatch.add(g)
                if (p.exists()) dirsToWatch.add(p)
            }
            // Also watch the roots so we can detect newly created sub-dirs
            if (globalDir.exists()) dirsToWatch.add(globalDir)
            if (projectDir.exists()) dirsToWatch.add(projectDir)

            for (dir in dirsToWatch) {
                registerDir(dir, watchService)
            }

            try {
                // runInterruptible wraps the blocking take() so that coroutine cancellation
                // interrupts the thread immediately (InterruptedException), making the loop
                // fully cancellation-cooperative with zero idle CPU. The previous poll(100ms)
                // loop woke 10x/s for the project lifetime (audit P1-9).
                while (isActive) {
                    val key = runInterruptible { watchService.take() }
                    val events = key.pollEvents()
                    for (event in events) {
                        // If a new sub-directory was created at runtime, register it immediately
                        // so files dropped inside it are also tracked without waiting for a rescan.
                        @Suppress("UNCHECKED_CAST")
                        val eventPath = (key.watchable() as? Path)
                            ?.resolve((event.context() as? java.nio.file.Path) ?: continue)
                        if (event.kind() == ENTRY_CREATE && eventPath != null && Files.isDirectory(eventPath)) {
                            registerDir(eventPath, watchService)
                        }
                    }
                    if (events.isNotEmpty()) {
                        scheduleRescan()
                    }
                    if (!key.reset()) {
                        // B14: ONE invalid dir (e.g. deleted) must not kill the watch loop
                        // for every other directory. Drop just this key and keep watching.
                        log.warn(
                            "HandoverTemplateStore: watch key for ${key.watchable()} became invalid;" +
                                " continuing with remaining watches",
                        )
                        continue
                    }
                }
            } catch (_: InterruptedException) {
                // take() was interrupted — exit cleanly
            } catch (_: CancellationException) {
                // coroutine scope was cancelled — exit cleanly
            } catch (_: java.nio.file.ClosedWatchServiceException) {
                // WatchService was closed because scope was cancelled — exit cleanly
            }
        }
    }
}
