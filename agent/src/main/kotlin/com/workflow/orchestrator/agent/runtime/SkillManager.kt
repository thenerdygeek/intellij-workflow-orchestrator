package com.workflow.orchestrator.agent.runtime

import java.io.File
import java.util.concurrent.TimeUnit

class SkillManager(val registry: SkillRegistry, val projectBasePath: String? = null) {

    data class ActiveSkill(
        val entry: SkillRegistry.SkillEntry,
        val content: String,
        val arguments: String?
    )

    var activeSkill: ActiveSkill? = null
        private set

    var onSkillActivated: ((ActiveSkill) -> Unit)? = null
    var onSkillDeactivated: (() -> Unit)? = null

    fun activateSkill(name: String, arguments: String? = null): ActiveSkill? {
        val entry = registry.getSkill(name) ?: return null
        val rawContent = registry.getSkillContent(name) ?: return null

        // Deactivate previous skill if any
        if (activeSkill != null) {
            deactivateSkill()
        }

        var processed = if (arguments != null) {
            var s = rawContent.replace("\$ARGUMENTS", arguments)
            val positionalArgs = arguments.split(" ")
            for ((index, arg) in positionalArgs.withIndex()) {
                s = s.replace("\$${index + 1}", arg)
            }
            s
        } else {
            rawContent
        }

        // Substitute ${CLAUDE_SKILL_DIR} with the skill's directory path
        val skillDir = when {
            entry.filePath.startsWith("builtin:") -> "" // built-in skills have no directory
            else -> File(entry.filePath).parent ?: ""
        }
        if (skillDir.isNotBlank()) {
            processed = processed.replace("\${CLAUDE_SKILL_DIR}", skillDir)
        }

        // Dynamic context injection: !`command` runs shell and replaces with output
        processed = preprocessDynamicContext(processed, projectBasePath)

        val MAX_SKILL_CHARS = 20_000  // ~5000 tokens at 4 chars/token
        val content = if (processed.length > MAX_SKILL_CHARS) {
            processed.take(MAX_SKILL_CHARS) + "\n\n[Skill content truncated at ~5000 tokens. Keep SKILL.md files concise.]"
        } else processed

        val skill = ActiveSkill(
            entry = entry,
            content = content,
            arguments = arguments
        )
        activeSkill = skill
        onSkillActivated?.invoke(skill)
        return skill
    }

    fun deactivateSkill() {
        activeSkill = null
        onSkillDeactivated?.invoke()
    }

    fun getPreferredTools(): Set<String> {
        return activeSkill?.entry?.preferredTools?.toSet() ?: emptySet()
    }

    fun getAllowedTools(): Set<String>? =
        activeSkill?.entry?.allowedTools?.toSet()

    fun isActive(): Boolean = activeSkill != null

    /**
     * Preprocesses !`command` patterns in skill content.
     * Runs each shell command and replaces the pattern with its stdout.
     * Commands run with the project base path as working directory.
     * Timeout: 10 seconds per command, 30 seconds total, 10K char cap per output.
     */
    internal fun preprocessDynamicContext(content: String, basePath: String?): String {
        if (basePath == null) return content
        val pattern = Regex("""!`([^`]+)`""")
        val matches = pattern.findAll(content).toList()
        if (matches.isEmpty()) return content

        var result = content
        val totalDeadline = System.currentTimeMillis() + 30_000

        for (match in matches) {
            if (System.currentTimeMillis() > totalDeadline) break
            val command = match.groupValues[1]
            try {
                val process = ProcessBuilder("sh", "-c", command)
                    .directory(File(basePath))
                    .redirectErrorStream(true)
                    .start()
                val completed = process.waitFor(10, TimeUnit.SECONDS)
                val output = if (completed) {
                    process.inputStream.bufferedReader().readText().trim().take(10_000)
                } else {
                    process.destroyForcibly()
                    "(command timed out: $command)"
                }
                result = result.replace(match.value, output)
            } catch (e: Exception) {
                result = result.replace(match.value, "(command failed: ${e.message})")
            }
        }
        return result
    }
}
