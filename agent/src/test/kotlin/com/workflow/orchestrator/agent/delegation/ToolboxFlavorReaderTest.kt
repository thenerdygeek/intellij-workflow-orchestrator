package com.workflow.orchestrator.agent.delegation

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ToolboxFlavorReaderTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `returns null when no idea workspace metadata exists`() {
        val flavor = ToolboxFlavorReader().readLastUsedFlavor(tmp)
        assertNull(flavor)
    }

    @Test
    fun `reads productCode and majorVersion from idea workspace xml when present`() {
        val ideaDir = Files.createDirectories(tmp.resolve(".idea"))
        Files.writeString(
            ideaDir.resolve("workspace.xml"),
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project version="4">
              <component name="ApplicationInfo" productCode="IC" majorVersion="2025" minorVersion="1.2"/>
            </project>
            """.trimIndent(),
        )
        val flavor = ToolboxFlavorReader().readLastUsedFlavor(tmp)
        assertNotNull(flavor)
        assertEquals("IC", flavor!!.productCode)
        assertEquals("2025", flavor.majorVersion)
    }
}

