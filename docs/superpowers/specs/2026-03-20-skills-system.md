# User-Extensible Skills System — Design Spec

## Problem

The AI agent has 50 built-in tools but no way for users to define custom workflows. A "hotfix" process, a "code review prep" checklist, or a "sprint standup context" are all repeatable workflows that combine existing tools in project-specific ways. Users must re-explain these workflows every session.

## Solution

A SKILL.md-based extensibility system following the Agent Skills standard. Users write markdown files with YAML frontmatter that provide workflow instructions. The agent discovers skills at startup, loads them on-demand, and follows their instructions using the existing tool set.

---

## Skill Format

```yaml
---
name: hotfix
description: Create and deploy a hotfix for production issues
disable-model-invocation: false
user-invocable: true
preferred-tools: [jira_get_ticket, jira_transition, bamboo_trigger_build, bitbucket_create_pr]
---

## Hotfix Workflow

1. Get the production issue ticket from user (or from $ARGUMENTS if provided)
2. Create branch from master: hotfix/PROJ-{ticketId}
3. Transition Jira ticket to "In Progress"
4. Guide user through the fix
5. Trigger Bamboo build to verify
6. Create PR targeting master
```

### Frontmatter Fields

| Field | Type | Default | Purpose |
|---|---|---|---|
| `name` | string | directory name | Display name and `/slash-command` identifier |
| `description` | string | *required* | LLM uses this for auto-discovery matching |
| `disable-model-invocation` | boolean | `false` | If true, only user can invoke (prevents accidental trigger) |
| `user-invocable` | boolean | `true` | If false, only LLM can trigger (background knowledge) |
| `preferred-tools` | string list | `[]` (all tools) | Tools to prioritize when skill is active (soft preference, not hard restriction) |

### String Substitution

- `$ARGUMENTS` — full argument string from `/skill-name args here`
- `$1`, `$2`, etc. — positional arguments (space-separated)
- If no arguments provided, placeholders remain as-is (LLM asks for context)

---

## Discovery Locations

| Scope | Path | Priority |
|---|---|---|
| Project | `{projectBasePath}/.workflow/skills/{name}/SKILL.md` | Higher (overrides user) |
| User | `~/.workflow-orchestrator/skills/{name}/SKILL.md` | Lower |

Project skills override user skills with the same name.

### Progressive Loading

1. **Startup**: Scan directories, parse YAML frontmatter only (name + description). Store in `SkillRegistry`.
2. **Context injection**: Skill descriptions injected into system prompt as a compact list (~2% context budget).
3. **Activation**: Load full SKILL.md content, substitute arguments, inject as `<active_skill>` system message.
4. **Deactivation**: Remove `<active_skill>` message from context, recalculate tokens.

---

## Invocation

### Three Modes

**1. User `/command`** — User types `/hotfix PROJ-456` in chat input.
- `AgentController` intercepts `/` prefix before sending to LLM
- Looks up skill by name in `SkillRegistry`
- Activates skill with `args = "PROJ-456"`
- Substitutes `$ARGUMENTS` in skill content
- Sends modified user message to LLM with skill content in context

**2. Toolbar dropdown** — Dropdown in agent panel header lists available skills.
- Click activates the skill (no arguments)
- Shows skill name + description as tooltip
- Grouped by scope (Project / Personal)

**3. LLM auto-trigger** — LLM matches conversation context to skill descriptions.
- LLM calls `activate_skill(name)` tool
- Only for skills where `disable-model-invocation` is false
- Skill content loaded into context for subsequent iterations

### Deactivation

- Banner × button → removes skill from context
- Session ends → skill deactivated
- LLM calls `deactivate_skill()` when workflow is complete
- New skill activation → deactivates previous skill

---

## Active Skill State

When a skill is active:
- Full SKILL.md content (with substitutions) injected as `<active_skill>` system message
- `preferred-tools` passed to `DynamicToolSelector` as priority hints
- Chat UI shows banner: "🔧 Skill active: hotfix ×"
- Chat input placeholder unchanged (normal behavior)

### Tool Preference (Soft)

`preferred-tools` is a **soft preference**, not a hard restriction:
- Listed tools are always included in the tool set (bypass keyword matching)
- All other tools remain available
- System prompt says: "The active skill prefers these tools: [list]. Use them when relevant but you may use any tool if needed."

---

## Components

### SkillRegistry

Scans skill directories, parses frontmatter, maintains index.

```kotlin
class SkillRegistry(private val projectBasePath: String?, private val userHome: String) {
    data class SkillEntry(
        val name: String,
        val description: String,
        val disableModelInvocation: Boolean,
        val userInvocable: Boolean,
        val preferredTools: List<String>,
        val filePath: String,
        val scope: SkillScope  // PROJECT or USER
    )

    enum class SkillScope { PROJECT, USER }

    fun scan(): List<SkillEntry>           // scan directories, parse frontmatter
    fun getSkill(name: String): SkillEntry? // lookup by name
    fun getSkillContent(name: String): String?  // load full SKILL.md
    fun getUserInvocableSkills(): List<SkillEntry>  // for toolbar + /command
    fun getAutoDiscoverableSkills(): List<SkillEntry>  // for LLM context
    fun buildDescriptionIndex(): String     // compact list for system prompt
}
```

### SkillManager

Manages active skill state within a session.

```kotlin
class SkillManager(private val registry: SkillRegistry) {
    var activeSkill: ActiveSkill? = null

    data class ActiveSkill(
        val entry: SkillRegistry.SkillEntry,
        val content: String,  // full content with substitutions
        val arguments: String?
    )

    fun activateSkill(name: String, arguments: String? = null): ActiveSkill?
    fun deactivateSkill()
    fun getPreferredTools(): Set<String>  // from active skill
    fun isActive(): Boolean

    // Callbacks
    var onSkillActivated: ((ActiveSkill) -> Unit)?
    var onSkillDeactivated: (() -> Unit)?
}
```

### Tools

**`activate_skill`** — LLM-invoked tool to activate a skill by name.
```
Parameters: name (required), arguments (optional)
Returns: Skill activated message with instructions summary
```

**`deactivate_skill`** — LLM-invoked tool to deactivate the current skill.
```
Parameters: none
Returns: Skill deactivated confirmation
```

---

## Integration Points

### ConversationSession
- `val skillManager: SkillManager` — new field
- `create()` — builds `SkillRegistry`, passes to `SkillManager`, loads descriptions into prompt

### PromptAssembler
- `skillDescriptions: String?` parameter — compact skill index for discovery
- Active skill instructions injected separately as context message

### ContextManager
- `skillAnchor: ChatMessage?` — dedicated slot (like `planAnchor`), never compressed
- `setSkillAnchor(message)` / clear on deactivation

### DynamicToolSelector
- `preferredTools: Set<String>` parameter — tools from active skill always included
- Added alongside existing `activatedTools` parameter

### AgentController
- Intercept `/` prefix in chat input
- Wire `SkillManager` callbacks to show/hide banner
- Wire toolbar dropdown to `SkillRegistry.getUserInvocableSkills()`

### AgentCefPanel
- `showSkillBanner(name)` — show banner with dismiss button
- `hideSkillBanner()` — remove banner

### AgentService
- Register `ActivateSkillTool` and `DeactivateSkillTool`
- `currentSkillManager` field (like `currentPlanManager`)

---

## UI

### Chat Banner (when skill active)
```
┌─────────────────────────────────────────────┐
│ 🔧 Skill active: hotfix                  × │
└─────────────────────────────────────────────┘
```
- Appears below toolbar, above chat messages
- × button calls `SkillManager.deactivateSkill()`

### Toolbar Dropdown
```
[🔧 Skills ▾]
├─ Project Skills
│  ├─ /hotfix — Create and deploy a hotfix
│  └─ /review-prep — Prepare code for review
├─ Personal Skills
│  └─ /standup — Generate standup context
└─ + Create Skill...  (opens docs link)
```

---

## Edge Cases

| Edge Case | Behavior |
|---|---|
| No skills found | Toolbar dropdown disabled, no descriptions in prompt |
| Skill file malformed (bad YAML) | Skip with warning log, don't crash |
| `/unknown-skill` | Show error: "Skill 'unknown-skill' not found. Available: ..." |
| Skill activated while another active | Previous skill deactivated first |
| Skill references non-existent tool in preferred-tools | Ignored silently (tool just won't be in the set) |
| `$ARGUMENTS` with no arguments provided | Left as literal `$ARGUMENTS` — LLM sees it and asks user |
| Very long SKILL.md (>5000 tokens) | Truncated with warning in content |
| Same skill name in project and user | Project wins (higher priority) |
| LLM tries to activate skill with `disable-model-invocation: true` | Tool returns error |
