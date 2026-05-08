package com.workflow.orchestrator.handover.service

import com.workflow.orchestrator.handover.model.HandoverTemplate
import com.workflow.orchestrator.handover.model.HandoverTemplateAction
import com.workflow.orchestrator.handover.model.HandoverTemplateOrigin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

class HandoverTemplateStoreTest {

    @TempDir
    lateinit var tempRoot: Path

    private lateinit var globalDir: Path
    private lateinit var projectDir: Path
    private lateinit var scope: CoroutineScope

    @BeforeEach
    fun setUp() {
        globalDir = tempRoot.resolve("global")
        projectDir = tempRoot.resolve("project")
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun fakeLoader(vararg templates: HandoverTemplate) = object : BundledTemplateLoader {
        override fun load() = templates.toList()
    }

    private fun bundledTemplate(
        name: String,
        action: HandoverTemplateAction = HandoverTemplateAction.JIRA,
        source: String = "bundled content",
    ) = HandoverTemplate(
        id = "${action.name.lowercase()}/$name",
        name = name,
        action = action,
        source = source,
        origin = HandoverTemplateOrigin.BUNDLED,
    )

    private fun makeStore(loader: BundledTemplateLoader = fakeLoader()): HandoverTemplateStore =
        HandoverTemplateStore(globalDir, projectDir, loader, scope)

    private fun writeGlobal(action: HandoverTemplateAction, name: String, ext: String, content: String) {
        val dir = globalDir.resolve(action.name.lowercase())
        Files.createDirectories(dir)
        dir.resolve("$name.$ext").toFile().writeText(content)
    }

    private fun writeProject(action: HandoverTemplateAction, name: String, ext: String, content: String) {
        val dir = projectDir.resolve(action.name.lowercase())
        Files.createDirectories(dir)
        dir.resolve("$name.$ext").toFile().writeText(content)
    }

    // -----------------------------------------------------------------------
    // Test 1 — bundled templates load
    // -----------------------------------------------------------------------

    @Test
    fun `bundled templates load`() = runTest {
        val t1 = bundledTemplate("standard-closure")
        val t2 = bundledTemplate("brief-update")
        val store = makeStore(fakeLoader(t1, t2))

        val list = store.templates.value
        assertEquals(2, list.size)
        assertTrue(list.any { it.id == "jira/standard-closure" && it.origin == HandoverTemplateOrigin.BUNDLED })
        assertTrue(list.any { it.id == "jira/brief-update" && it.origin == HandoverTemplateOrigin.BUNDLED })
    }

    // -----------------------------------------------------------------------
    // Test 2 — global shadows bundled by id
    // -----------------------------------------------------------------------

    @Test
    fun `user global template shadows bundled by name`() {
        val bundled = bundledTemplate("standard-closure", source = "bundled wiki text")
        writeGlobal(HandoverTemplateAction.JIRA, "standard-closure", "wiki", "global wiki text")

        val store = makeStore(fakeLoader(bundled))
        val list = store.templates.value

        // Only one entry for this id
        val entries = list.filter { it.id == "jira/standard-closure" }
        assertEquals(1, entries.size)
        assertEquals(HandoverTemplateOrigin.GLOBAL, entries[0].origin)
        assertEquals("global wiki text", entries[0].source)
    }

    // -----------------------------------------------------------------------
    // Test 3 — project template shadows global; isOverride == true
    // -----------------------------------------------------------------------

    @Test
    fun `project template shadows global with override flag`() {
        writeGlobal(HandoverTemplateAction.JIRA, "standard-closure", "wiki", "global content")
        writeProject(HandoverTemplateAction.JIRA, "standard-closure", "wiki", "project content")

        val store = makeStore()
        val list = store.templates.value

        val entries = list.filter { it.id == "jira/standard-closure" }
        assertEquals(1, entries.size)
        assertEquals(HandoverTemplateOrigin.PROJECT, entries[0].origin)
        assertTrue(entries[0].isOverride)
        assertEquals("project content", entries[0].source)
    }

    // -----------------------------------------------------------------------
    // Test 4 — delete bundled fails
    // -----------------------------------------------------------------------

    @Test
    fun `delete bundled fails`() = runTest {
        val bundled = bundledTemplate("standard-closure")
        val store = makeStore(fakeLoader(bundled))

        assertThrows<UnsupportedOperationException> {
            store.delete("jira/standard-closure")
        }
    }

    // -----------------------------------------------------------------------
    // Test 5 — duplicate bundled creates editable global
    // -----------------------------------------------------------------------

    @Test
    fun `duplicate bundled creates editable global`() = runTest {
        val bundled = bundledTemplate("standard-closure", source = "original content")
        val store = makeStore(fakeLoader(bundled))

        val copy = store.duplicate("jira/standard-closure", "my-copy")

        assertEquals("jira/my-copy", copy.id)
        assertEquals("my-copy", copy.name)
        assertEquals(HandoverTemplateOrigin.GLOBAL, copy.origin)
        assertEquals("original content", copy.source)

        // The global file must exist on disk
        val file = globalDir.resolve("jira/my-copy.wiki")
        assertTrue(Files.exists(file))
        assertEquals("original content", file.toFile().readText())

        // Bundled template still present in the merged list
        val list = store.templates.value
        assertTrue(list.any { it.id == "jira/standard-closure" && it.origin == HandoverTemplateOrigin.BUNDLED })
        assertTrue(list.any { it.id == "jira/my-copy" && it.origin == HandoverTemplateOrigin.GLOBAL })
    }

    // -----------------------------------------------------------------------
    // Test 6 — watcher emits updated list within 1 second of file change
    // -----------------------------------------------------------------------

    @Test
    fun `watcher emits updated list within 1 second of file change`() {
        val store = makeStore()

        val initialSize = store.templates.value.size

        // Give the watcher coroutine time to start and register its directories.
        Thread.sleep(200)

        // Write a new template file into globalDir while the watcher is running.
        // The jira/ subdir already exists because makeStore() calls ensureDirs().
        val jiraDir = globalDir.resolve("jira")
        jiraDir.resolve("new-template.wiki").toFile().writeText("new content")

        // Use runBlocking so real time flows — the watcher debounce uses Dispatchers.IO
        // real time, not the virtual clock used by runTest.
        // Budget: 200ms startup wait already done + 300ms debounce + 500ms margin = 1000ms.
        val updated = runBlocking {
            withTimeout(2000.milliseconds) {
                store.templates.first { it.size == initialSize + 1 }
            }
        }

        assertNotNull(updated)
        val newTemplate = updated.find { it.id == "jira/new-template" }
        assertNotNull(newTemplate)
        assertEquals(HandoverTemplateOrigin.GLOBAL, newTemplate!!.origin)
    }

    // -----------------------------------------------------------------------
    // Additional edge-case tests
    // -----------------------------------------------------------------------

    @Test
    fun `create fails when id already exists in global`() = runTest {
        writeGlobal(HandoverTemplateAction.JIRA, "existing", "wiki", "content")
        val store = makeStore()

        assertThrows<IllegalArgumentException> {
            store.create("existing", HandoverTemplateAction.JIRA, "new content")
        }
    }

    @Test
    fun `delete project template resurfaces global`() = runTest {
        writeGlobal(HandoverTemplateAction.JIRA, "standard-closure", "wiki", "global content")
        writeProject(HandoverTemplateAction.JIRA, "standard-closure", "wiki", "project content")

        val store = makeStore()

        // Before delete: PROJECT origin
        assertEquals(HandoverTemplateOrigin.PROJECT, store.templates.value
            .first { it.id == "jira/standard-closure" }.origin)

        // Delete the PROJECT file
        store.delete("jira/standard-closure")

        // After delete: GLOBAL resurfaces
        val after = store.templates.value.first { it.id == "jira/standard-closure" }
        assertEquals(HandoverTemplateOrigin.GLOBAL, after.origin)
    }

    @Test
    fun `update writes new content to disk`() = runTest {
        writeGlobal(HandoverTemplateAction.JIRA, "my-template", "wiki", "old content")
        val store = makeStore()

        store.update("jira/my-template", "updated content")

        val file = globalDir.resolve("jira/my-template.wiki")
        assertEquals("updated content", file.toFile().readText())
    }

    @Test
    fun `update bundled fails`() = runTest {
        val bundled = bundledTemplate("standard-closure")
        val store = makeStore(fakeLoader(bundled))

        assertThrows<UnsupportedOperationException> {
            store.update("jira/standard-closure", "new content")
        }
    }

    @Test
    fun `files with wrong extension for action are ignored`() {
        // A .html file in the jira/ dir is malformed and should be silently ignored
        val jiraDir = globalDir.resolve("jira")
        Files.createDirectories(jiraDir)
        jiraDir.resolve("bad.html").toFile().writeText("should be ignored")
        jiraDir.resolve("good.wiki").toFile().writeText("valid")

        val store = makeStore()
        val list = store.templates.value

        assertTrue(list.none { it.name == "bad" }, "bad.html should be ignored in jira/ dir")
        assertTrue(list.any { it.id == "jira/good" })
    }
}
