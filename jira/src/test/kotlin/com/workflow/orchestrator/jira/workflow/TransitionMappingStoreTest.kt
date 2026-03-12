package com.workflow.orchestrator.jira.workflow

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TransitionMappingStoreTest {

    private lateinit var store: TransitionMappingStore

    @BeforeEach
    fun setUp() {
        store = TransitionMappingStore()
    }

    @Test
    fun `save and retrieve explicit mapping`() {
        val mapping = TransitionMapping(
            intent = "START_WORK",
            transitionName = "Begin Development",
            projectKey = "PROJ",
            issueTypeId = null,
            source = "explicit"
        )
        store.saveMapping(mapping)
        val result = store.getMapping("START_WORK", "PROJ")
        assertNotNull(result)
        assertEquals("Begin Development", result!!.transitionName)
        assertEquals("explicit", result.source)
    }

    @Test
    fun `return null for missing mapping`() {
        val result = store.getMapping("START_WORK", "PROJ")
        assertNull(result)
    }

    @Test
    fun `clear mapping removes it`() {
        store.saveMapping(TransitionMapping("START_WORK", "In Progress", "PROJ", null, "learned"))
        store.clearMapping("START_WORK", "PROJ")
        assertNull(store.getMapping("START_WORK", "PROJ"))
    }

    @Test
    fun `serialize and deserialize round-trip`() {
        store.saveMapping(TransitionMapping("START_WORK", "In Progress", "PROJ", null, "learned"))
        store.saveMapping(TransitionMapping("CLOSE", "Done", "PROJ", null, "explicit"))
        val json = store.toJson()
        val restored = TransitionMappingStore()
        restored.loadFromJson(json)
        assertEquals("In Progress", restored.getMapping("START_WORK", "PROJ")?.transitionName)
        assertEquals("Done", restored.getMapping("CLOSE", "PROJ")?.transitionName)
    }

    @Test
    fun `getAllMappings returns all saved`() {
        store.saveMapping(TransitionMapping("START_WORK", "In Progress", "PROJ", null, "learned"))
        store.saveMapping(TransitionMapping("CLOSE", "Done", "OTHER", null, "explicit"))
        assertEquals(2, store.getAllMappings().size)
    }

    @Test
    fun `issue type specific mapping takes priority`() {
        store.saveMapping(TransitionMapping("START_WORK", "In Progress", "PROJ", null, "learned"))
        store.saveMapping(TransitionMapping("START_WORK", "Begin Bug Fix", "PROJ", "10001", "explicit"))
        val general = store.getMapping("START_WORK", "PROJ")
        assertEquals("In Progress", general?.transitionName)
        val specific = store.getMapping("START_WORK", "PROJ", "10001")
        assertEquals("Begin Bug Fix", specific?.transitionName)
    }
}
