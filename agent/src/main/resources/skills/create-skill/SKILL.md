---
name: create-skill
description: Create a new custom skill for this project or personal use. Use when the user says "create a skill", "make a skill", "turn this into a skill", "I want a workflow for X", or when you identify a repeatable workflow worth capturing. Guides through intent capture, tool selection, and SKILL.md generation.
disable-model-invocation: true
user-invocable: true
preferred-tools: [think, ask_questions, edit_file, read_file, search_code]
---

# Skill Creator

Create custom skills (SKILL.md files) that extend the agent with project-specific workflows.

## Overview

Skills are markdown files with YAML frontmatter that provide workflow instructions. When activated, the skill's instructions are injected into your context and the preferred tools are prioritized.

**Skill format:**
```yaml
---
name: my-skill
description: When to trigger and what it does (be specific and a bit pushy)
preferred-tools: [tool1, tool2, tool3]
disable-model-invocation: false
user-invocable: true
---
# Instructions here
```

**Skill locations:**
- Project: `.workflow/skills/{name}/SKILL.md` (committed to VCS, shared with team)
- Personal: `~/.workflow-orchestrator/skills/{name}/SKILL.md` (your preferences only)

## Process

### Step 1: Capture Intent

If the conversation already contains a workflow the user wants to capture (e.g., "turn this into a skill"), extract the steps, tools used, and corrections made. Otherwise, use `ask_questions` to gather:

```
Questions:
1. What should this skill help you do? (free text)
2. Scope: Project skill (shared with team) or Personal skill? (single select)
3. Should this skill trigger automatically when the agent detects a relevant task, or only when you type /skill-name? (single select: auto + manual, manual only)
```

### Step 2: Identify Tools

Use `think` to analyze which of the 68 registered tools (15 meta-tools with 144 actions) are relevant to this workflow.

The agent has 68 registered tools across 10 categories. Use `think` to identify relevant tools, or refer to the tool list in the system prompt. Key categories:
- **Core:** read_file, edit_file, search_code, run_command, diagnostics, format_code, optimize_imports, file_structure, find_definition, find_references, type_hierarchy, call_hierarchy, agent, think
- **IDE Intelligence:** run_inspections, refactor_rename, list_quickfixes, find_implementations, semantic_diagnostics
- **Runtime & Debug:** runtime_config (get/create/modify/delete run configs), runtime_exec (run_tests, compile_module, get_test_results, get_run_output), debug_breakpoints, debug_step, debug_inspect
- **VCS:** git (actions: status, blame, diff, log, branches, show_file, show_commit, merge_base, file_history, stash_list, shelve)
- **Spring & Framework:** spring (actions: context, endpoints, bean_graph, config, jpa_entities, etc.), build (actions: maven_dependencies, gradle_dependencies, etc.)
- **Jira:** jira (actions: get_ticket, get_transitions, transition, comment, log_work, get_worklogs, search_issues, etc.)
- **Bamboo:** bamboo_builds (actions: build_status, get_build, trigger_build, get_build_log, get_test_results, etc.), bamboo_plans
- **SonarQube:** sonar (actions: issues, quality_gate, coverage, search_projects, analysis_tasks, etc.)
- **Bitbucket:** bitbucket_pr (actions: create_pr, get_pr_diff, get_pr_changes, get_pr_commits, etc.), bitbucket_review, bitbucket_repo
- **Planning:** create_plan, update_plan_step, ask_questions, archival_memory_insert, skill

Select the 3-8 most relevant tools for the `preferred-tools` field. These aren't restrictions — just priorities.

### Step 3: Write the SKILL.md

**Key principles:**
- Keep the main file under 500 lines (ideally under 200)
- Use imperative form ("Do X", not "You should do X")
- Include specific tool names in instructions (e.g., "Use `sonar(action=\"issues\")` to check for blockers")
- Be explicit about the workflow steps
- Include `$ARGUMENTS` placeholder if the skill accepts input (e.g., ticket ID)
- Explain WHY steps matter, not just WHAT to do

**Advanced frontmatter fields** (use when needed):
- `allowed-tools` — hard tool whitelist; overrides all tool selection when skill is active
- `context: fork` — run the skill in an isolated worker session (10 iterations, 5 min timeout)
- `agent` — subagent type to use when `context: fork` (e.g., `coder`, `explorer`)
- `argument-hint` — autocomplete hint shown for skill arguments in the UI

**Description writing tips:**
- Be pushy — the description is how the agent decides to auto-trigger the skill
- Include trigger phrases: "Use when the user mentions X, Y, or Z"
- Include what it does AND when to use it
- Example: "Prepare code for review — run quality checks, fix blockers, verify coverage. Use when the user says 'review prep', 'prepare for PR', 'ready for review', or mentions code quality before merging."

### Step 4: Save the Skill

Based on the scope chosen in Step 1:

**Project skill:**
```
.workflow/skills/{name}/SKILL.md
```

**Personal skill:**
```
~/.workflow-orchestrator/skills/{name}/SKILL.md
```

Use `edit_file` to create the file. Create the directory structure first if needed.

After saving, inform the user:
- "Skill created at {path}. It will be available on the next session start."
- "You can invoke it with `/{name}` or the agent will auto-trigger it when relevant."
- "To edit, modify the SKILL.md file directly."

### Step 5: Use archival_memory_insert to Remember

Call `archival_memory_insert` with tags ["skills-created"] to note what skills exist:
```
Created skill '{name}' at {path}. Purpose: {description}. Tools: {preferred-tools}.
```

This helps the agent remember available skills across sessions.

## Example Skills

### Hotfix Workflow
```yaml
---
name: hotfix
description: Create and deploy a hotfix for production issues. Use when the user mentions hotfix, production bug, urgent fix, P0, or emergency patch.
preferred-tools: [jira, git, bamboo_builds, bitbucket_pr]
---
## Hotfix Workflow
1. Get the ticket details: use `jira(action="get_ticket")` with $ARGUMENTS (ticket ID)
2. Use `git(action="status")` to check current branch state
3. Transition ticket to "In Progress": use `jira(action="transition")`
4. Guide the user through the fix
5. Verify the build: use `bamboo_builds(action="trigger_build")`
6. Create PR targeting master: use `bitbucket_pr(action="create_pr")`
```

### Pre-Review Checklist
```yaml
---
name: review-prep
description: Prepare code for review — quality checks, fix blockers, verify coverage. Use when the user says review prep, prepare for PR, ready for review, or code quality before merging.
preferred-tools: [sonar, runtime_exec, diagnostics]
---
## Pre-Review Checklist
1. Run `runtime_exec(action="compile_module")` to verify clean compilation
2. Run `runtime_exec(action="run_tests")` for the affected module
3. Check `sonar(action="issues")` for any blocker/critical issues on changed files
4. Check `sonar(action="coverage")` meets the project threshold
5. Run `diagnostics` on changed files for IDE-level warnings
6. Summarize findings and suggest fixes
```

### Sprint Standup Context
```yaml
---
name: standup
description: Gather context for daily standup — current ticket, recent commits, build status. Use when the user asks about their work, standup, or what they did yesterday.
user-invocable: true
disable-model-invocation: false
preferred-tools: [jira, git, bamboo_builds]
---
## Standup Context
1. Use `jira(action="get_ticket")` to get the active ticket details
2. Use `git(action="status")` to see current branch and recent changes
3. Use `bamboo_builds(action="build_status")` to check if the latest build passed
4. Summarize: what was done, what's in progress, any blockers
```
