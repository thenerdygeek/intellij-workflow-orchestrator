package com.workflow.orchestrator.agent.runtime

class SkillManager(val registry: SkillRegistry) {

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

        val substituted = if (arguments != null) {
            var s = rawContent.replace("\$ARGUMENTS", arguments)
            val positionalArgs = arguments.split(" ")
            for ((index, arg) in positionalArgs.withIndex()) {
                s = s.replace("\$${index + 1}", arg)
            }
            s
        } else {
            rawContent
        }

        val MAX_SKILL_CHARS = 20_000  // ~5000 tokens at 4 chars/token
        val content = if (substituted.length > MAX_SKILL_CHARS) {
            substituted.take(MAX_SKILL_CHARS) + "\n\n[Skill content truncated at ~5000 tokens. Keep SKILL.md files concise.]"
        } else substituted

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

    fun isActive(): Boolean = activeSkill != null
}
