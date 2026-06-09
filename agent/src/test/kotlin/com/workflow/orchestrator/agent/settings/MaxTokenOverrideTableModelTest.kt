package com.workflow.orchestrator.agent.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MaxTokenOverrideTableModelTest {

    @Test
    fun `toJson encodes only positive overrides`() {
        val m = MaxTokenOverrideTableModel()
        val rows = listOf(
            MaxTokenOverrideTableModel.Row("m1", real = 132_000, override = 70_000),
            MaxTokenOverrideTableModel.Row("m2", real = 93_000, override = 0),
        )
        m.setRows(rows)
        assertEquals("""{"m1":70000}""", m.toJson())
    }

    @Test
    fun `seed merges overrides onto catalog rows`() {
        val m = MaxTokenOverrideTableModel()
        m.seed(modelReals = linkedMapOf("m1" to 132_000, "m2" to 93_000), overridesJson = """{"m2":80000}""")
        assertEquals(0, m.rowAt("m1").override)
        assertEquals(80_000, m.rowAt("m2").override)
    }

    @Test
    fun `aboveCatalog flags overrides exceeding real`() {
        val m = MaxTokenOverrideTableModel()
        m.setRows(listOf(MaxTokenOverrideTableModel.Row("m1", real = 132_000, override = 200_000)))
        assertEquals(true, m.rowAt("m1").aboveCatalog())
        assertEquals(false, MaxTokenOverrideTableModel.Row("m1", real = 132_000, override = 100_000).aboveCatalog())
        assertEquals(false, MaxTokenOverrideTableModel.Row("m1", real = null, override = 200_000).aboveCatalog())
    }
}
