package com.workflow.orchestrator.agent.delegation

import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads the last-used IDE flavor and version from a project's `.idea/workspace.xml`,
 * if present. Used by the auto-launch path to detect a Toolbox flavor mismatch
 * before spawning a process that might be a different IDE than the one the
 * project was last opened with.
 *
 * Returns null when the project has never been opened (no `.idea`), the
 * `workspace.xml` is unreadable, or the `ApplicationInfo` component doesn't
 * record what we need. Callers treat null as "unknown — show softer banner."
 *
 * Plan 3 spec §5.6.
 */
data class IdeFlavor(val productCode: String, val majorVersion: String)

class ToolboxFlavorReader {
    fun readLastUsedFlavor(projectPath: Path): IdeFlavor? {
        val workspaceXml = projectPath.resolve(".idea/workspace.xml")
        if (!Files.exists(workspaceXml)) return null
        return try {
            val doc = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }.newDocumentBuilder().parse(workspaceXml.toFile())
            val components = doc.getElementsByTagName("component")
            for (i in 0 until components.length) {
                val node = components.item(i)
                val attrs = node.attributes
                if (attrs.getNamedItem("name")?.nodeValue == "ApplicationInfo") {
                    val productCode = attrs.getNamedItem("productCode")?.nodeValue
                    val majorVersion = attrs.getNamedItem("majorVersion")?.nodeValue
                    if (productCode != null && majorVersion != null) {
                        return IdeFlavor(productCode, majorVersion)
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}
