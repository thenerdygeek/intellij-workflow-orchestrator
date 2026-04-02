# Skill Tool Consolidation — Design Spec

> **Goal:** Replace `activate_skill` + `deactivate_skill` with a single `Skill` tool that returns full skill content as `ToolResult`, matching Claude Code's design while keeping our compression-proof anchor advantage.

**Architecture:** Single `Skill` tool reads SKILL.md, returns full content to LLM in tool_result, AND sets compression-proof `skillAnchor` for long-session persistence. No explicit deactivation — new skill replaces previous anchor.

---

## Changes

### 1. New file: `SkillTool.kt`

- **Name:** `Skill`
- **Parameters:** `skill` (required, string) + `args` (optional, string)
- **AllowedWorkers:** `ORCHESTRATOR` only (same as current)
- **Behavior (inline skills):**
  1. Look up skill via `SkillRegistry.getSkill(name)`
  2. Check `disableModelInvocation` flag
  3. Load + preprocess via `SkillManager.loadAndPreprocessSkill()`
  4. Call `skillManager.activateSkill()` (sets anchor via callback)
  5. Return `ToolResult` with full skill content in `content` field
- **Behavior (context:fork skills):**
  1. Same as current `ActivateSkillTool.executeForked()` — spawn WorkerSession, return summary

### 2. Delete files

- `ActivateSkillTool.kt`
- `DeactivateSkillTool.kt`

### 3. Update: `AgentService.kt` (line ~252-253)

```
- register(ActivateSkillTool())
- register(DeactivateSkillTool())
+ register(SkillTool())
```

### 4. Update: `PromptAssembler.kt` (line ~95)

```
- "To activate a skill, call activate_skill(name). Users can also type /skill-name in chat."
+ "To use a skill, call Skill(skill=\"name\"). Users can also type /skill-name in chat."
```

### 5. Update: `PromptAssembler.kt` (line ~473, few-shot example)

```
- Good approach: activate_skill(name="systematic-debugging")
+ Good approach: Skill(skill="systematic-debugging")
```

### 6. Update: `DynamicToolSelector.kt` (line ~88)

```
- private val SKILL_TOOL_NAMES = setOf("activate_skill", "deactivate_skill")
+ private val SKILL_TOOL_NAMES = setOf("Skill")
```

### 7. Update: `ToolCategoryRegistry.kt` (line ~108)

```
- tools = listOf("create_plan", "update_plan_step", "ask_questions", "activate_skill", "deactivate_skill", ...)
+ tools = listOf("create_plan", "update_plan_step", "ask_questions", "Skill", ...)
```

### 8. Update: `ApprovalGate.kt` (line ~209)

```
- "request_tools", "think", "activate_skill", "deactivate_skill",
+ "request_tools", "think", "Skill",
```

### 9. Update: `SkillManager.kt`

- `deactivateSkill()` stays (called internally when a new skill replaces the previous one)
- `onSkillDeactivated` callback stays (clears anchor)
- No external API change — SkillTool calls `activateSkill()` which already handles replacement

### 10. Implicit deactivation

- New skill call → `activateSkill()` already calls `deactivateSkill()` first (line 69-71 in SkillManager)
- Session end → skill anchor naturally cleared
- No need for explicit deactivation tool

## What stays the same

- `SkillRegistry` — untouched
- `SkillManager` — untouched (except no longer needs `deactivateSkill()` exposed as tool)
- `EventSourcedContextBridge.skillAnchor` — untouched
- `AgentController.onSkillActivated` callback — untouched
- All SKILL.md files — untouched
- Frontmatter fields — untouched
- Dynamic injection, argument substitution — untouched
- Context forking — untouched

## Test updates

- Delete/update tests referencing `activate_skill` / `deactivate_skill`
- Add tests for `SkillTool`: inline activation, forked execution, not-found, disabled invocation
- Verify full content returned in ToolResult.content
