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

        val content = if (arguments != null) {
            var substituted = rawContent.replace("\$ARGUMENTS", arguments)
            val positionalArgs = arguments.split(" ")
            for ((index, arg) in positionalArgs.withIndex()) {
                substituted = substituted.replace("\$${index + 1}", arg)
            }
            substituted
        } else {
            rawContent
        }

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
