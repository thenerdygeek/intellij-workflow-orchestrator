package com.workflow.orchestrator.agent.tools.docs

import com.intellij.openapi.diagnostic.Logger

/**
 * Lazily loads long-form Markdown narratives bundled alongside the tool source.
 *
 * Resources live at `agent/src/main/resources/tool-docs/<name>.md`. The DSL points
 * at them by name via `narrative("read_file")` — the `.md` extension and
 * `tool-docs/` prefix are added here.
 *
 * Returns null when the resource is missing — narrative is always optional.
 */
object NarrativeLoader {
    private const val RESOURCE_PREFIX = "/tool-docs/"
    private val logger = Logger.getInstance(NarrativeLoader::class.java)

    fun load(name: String): String? {
        val path = "$RESOURCE_PREFIX$name.md"
        val stream = NarrativeLoader::class.java.getResourceAsStream(path)
        if (stream == null) {
            logger.warn("Narrative resource missing: $path (tool documentation declared narrative(\"$name\") but no file exists at agent/src/main/resources$path)")
            return null
        }
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
